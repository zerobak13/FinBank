package com.finbank.backend.account.service;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.member.domain.Member;
import com.finbank.backend.account.domain.TransactionLog;
import com.finbank.backend.account.domain.TransactionType;
import com.finbank.backend.account.dto.*;
import com.finbank.backend.common.idempotency.IdempotentExecutor;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.account.repository.AccountRepository;
import com.finbank.backend.member.service.CurrentMemberProvider;
import com.finbank.backend.account.repository.TransactionLogRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import com.finbank.backend.common.PageResponse;
import com.finbank.backend.common.PagingPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.finbank.backend.common.MoneyPolicy;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 계좌 도메인 유스케이스를 담당하는 서비스.
 *
 * <p>계좌 개설·조회와 입금·출금·이체를 처리하며, 금융 거래의 핵심인
 * 동시성 제어와 잔액 무결성을 책임진다. 잔액이 변경되는 모든 연산은
 * {@code @Transactional} 범위 안에서 비관적 락(PESSIMISTIC_WRITE)으로 보호되고,
 * 락 조회를 각 계좌의 "첫 조회"로 수행해 1차 캐시로 인한 stale read를 방지한다.</p>
 */
@Service
public class AccountService {

    private final CurrentMemberProvider currentMemberProvider;
    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final IdempotentExecutor idempotentExecutor;

    public AccountService(CurrentMemberProvider currentMemberProvider,
                          AccountRepository accountRepository,
                          TransactionLogRepository transactionLogRepository,
                          IdempotentExecutor idempotentExecutor) {
        this.currentMemberProvider = currentMemberProvider;
        this.accountRepository = accountRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.idempotentExecutor = idempotentExecutor;
    }

    @Transactional
    public AccountSummaryResponse createAccount(AccountCreateRequest request) {
        Member member = currentMemberProvider.current();

        String accountNumber = generateAccountNumber();
        Account account = new Account(member, accountNumber, BigDecimal.ZERO);
        if (request.getInitialDeposit().signum() > 0) {
            account.deposit(request.getInitialDeposit());
        }
        Account saved = accountRepository.save(account);

        if (request.getInitialDeposit().signum() > 0) {
            TransactionLog log = TransactionLog.deposit(saved,
                    request.getInitialDeposit(), saved.getBalance());
            transactionLogRepository.save(log);
        }

        return toSummary(saved);
    }
    @Transactional(readOnly = true)
    public List<AccountSummaryResponse> getMyAccounts() {
        Member member = currentMemberProvider.current();
        List<Account> accounts = accountRepository.findByMember(member);
        return accounts.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AccountSummaryResponse getAccountSummary(Long accountId) {
        Member member = currentMemberProvider.current();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + accountId));

        if (!account.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인 계좌만 조회할 수 있습니다.");
        }

        return toSummary(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionLogResponse> getAccountTransactions(Long accountId, int page, int size) {
        // 최신순, 같은 시각이면 나중에 생긴 거래가 먼저 (페이지 사이에서 순서가 흔들리지 않게)
        Pageable pageable = PagingPolicy.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Member member = currentMemberProvider.current();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + accountId));

        if (!account.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인 계좌만 조회할 수 있습니다.");
        }

        Page<TransactionLog> logPage = transactionLogRepository.findByAccountPerspective(
                account,
                List.of(TransactionType.WITHDRAW, TransactionType.TRANSFER_OUT),
                List.of(TransactionType.DEPOSIT, TransactionType.TRANSFER_IN),
                pageable
        );

        Page<TransactionLogResponse> dtoPage = logPage.map(this::toTxDto);
        return new PageResponse<>(dtoPage);
    }


    @Transactional
    public void deposit(Long accountId, BigDecimal amount) {
        validateAmount(amount, "입금");

        Member member = currentMemberProvider.current();

        // 권한 확인 → 락 순서. 남의 계좌에 락이 걸리는 일 자체를 막는다.
        // (락 조회가 계좌 엔티티의 첫 조회이므로 항상 커밋된 최신 잔액을 읽는다.)
        assertOwner(accountId, member, "본인 계좌만 입금할 수 있습니다.");
        Account account = lockAccount(accountId);

        if (account.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "잠금된 계좌에는 입금할 수 없습니다.");
        }

        // 잔액 증가
        account.deposit(amount);
        accountRepository.save(account);

        // 입금 로그
        transactionLogRepository.save(
                TransactionLog.deposit(account, amount, account.getBalance())
        );
    }


    @Transactional
    public void withdraw(Long accountId, BigDecimal amount) {
        validateAmount(amount, "출금");

        Member member = currentMemberProvider.current();

        // 권한 확인 → 락 순서 (입금과 동일)
        assertOwner(accountId, member, "본인 계좌만 출금할 수 있습니다.");
        Account account = lockAccount(accountId);

        if (account.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "잠금된 계좌에서는 출금할 수 없습니다.");
        }

        // 잔액 비교는 반드시 compareTo — equals는 스케일까지 비교한다 (1000 != 1000.0000)
        if (account.getBalance().compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        // 잔액 감소
        account.withdraw(amount);
        accountRepository.save(account);

        // 출금 로그
        transactionLogRepository.save(
                TransactionLog.withdraw(account, amount, account.getBalance())
        );
    }

    /**
     * 계좌 간 이체.
     *
     * <p><b>동시성 제어 — 비관적 락(PESSIMISTIC_WRITE)</b><br>
     * 두 계좌를 항상 ID 오름차순으로 잠가 데드락을 방지한다.</p>
     *
     * <p><b>중요: 락 조회를 각 행의 "첫 조회"로 수행한다.</b><br>
     * 락 없이 일반 조회({@code findById} 등)로 먼저 읽으면 엔티티가
     * 영속성 컨텍스트(1차 캐시)에 적재된다. 이후 {@code findWithLockingById}로 락을
     * 걸어도 Hibernate는 이미 캐시에 있는 엔티티의 값을 다시 읽지 않으므로,
     * 락을 기다렸다 통과한 스레드가 <b>락 이전의 낡은 잔액</b>을 보게 되어
     * 비관적 락이 무력화되고 lost update가 발생한다.<br>
     * 이를 막기 위해 잔액이 걸린 두 계좌는 오직 락 조회로만 적재한다.
     * (받는 계좌는 락 순서 결정을 위해 ID만 프로젝션으로 조회한다.)</p>
     *
     * <p><b>권한 확인은 락보다 먼저.</b> 출금 계좌의 소유자를 프로젝션으로 확인한 뒤에만 락을 건다.
     * 소유자는 바뀌지 않는 값이라 락 없이 읽어도 안전하고, 남의 계좌 ID를 넣은 요청이
     * 그 계좌(와 받는 계좌)에 락을 걸어 정상 거래를 붙잡아두는 일을 막는다.</p>
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        return transfer(request, null);
    }

    /**
     * 멱등 이체의 트랜잭션 본체 — {@link AccountTransferService}가 키를 선점한 뒤 호출한다.
     *
     * <p>응답 스냅샷 확정({@code complete})을 이체와 <b>같은 트랜잭션</b>에서 수행한다.
     * "돈이 움직인 사실"과 "COMPLETED 기록"이 한 커밋에 묶여야, 커밋 직후 장애가 나도
     * 재시도가 이체를 한 번 더 실행하지 않는다(상환과 동일한 원칙).</p>
     *
     * @param idempotencyKey null이면 멱등 처리 없이 이체만 수행한다(내부·테스트용)
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        Member member = currentMemberProvider.current();

        validateAmount(request.getAmount(), "이체");

        Long fromId = request.getFromAccountId();

        // 받는 계좌는 ID만 조회 (엔티티를 캐시에 올리지 않아 이후 락 조회가 항상 최신 값을 읽게 함)
        Long toId = accountRepository.findIdByAccountNumber(request.getToAccountNumber())
                .orElseThrow(() -> new NotFoundException("받는 계좌를 찾을 수 없습니다."));

        if (fromId.equals(toId)) {
            throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER);
        }

        // 락보다 권한 확인이 먼저다 (위 Javadoc 참고)
        assertOwner(fromId, member, "본인 계좌에서만 이체할 수 있습니다.");

        // 데드락 방지를 위해 항상 ID 오름차순으로 락을 획득한다.
        // 이 락 조회가 두 계좌의 첫 조회이므로 항상 커밋된 최신 잔액을 읽는다.
        Long firstLockId = Math.min(fromId, toId);
        Long secondLockId = Math.max(fromId, toId);

        Account firstLocked = accountRepository.findWithLockingById(firstLockId)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다."));

        Account secondLocked = accountRepository.findWithLockingById(secondLockId)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다."));

        Account from = firstLocked.getId().equals(fromId) ? firstLocked : secondLocked;
        Account to   = firstLocked.getId().equals(toId)   ? firstLocked : secondLocked;

        if (from.isLocked() || to.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (from.getBalance().compareTo(request.getAmount()) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        // 잔액 이동
        from.withdraw(request.getAmount());
        to.deposit(request.getAmount());

        accountRepository.save(from);
        accountRepository.save(to);

        // 이체 로그
        transactionLogRepository.save(
                TransactionLog.transferOut(from, to, request.getAmount(), from.getBalance())
        );
        transactionLogRepository.save(
                TransactionLog.transferIn(from, to, request.getAmount(), to.getBalance())
        );

        TransferResponse response = new TransferResponse(
                from.getId(),
                to.getAccountNumber(),
                MoneyPolicy.toWon(request.getAmount()),
                MoneyPolicy.toWon(from.getBalance()),
                "이체 완료");

        if (idempotencyKey != null) {
            idempotentExecutor.complete(idempotencyKey, response);
        }
        return response;
    }

    /** 금액 상한 — 이체·계좌 개설 요청의 {@code @Digits(integer = 15)}와 같다 */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999");

    /**
     * 금액 규칙: 0보다 큰 원 단위 정수, 상한 이하.
     * 이체·계좌 개설은 요청 DTO가 같은 규칙을 검사하지만, 입금·출금은 쿼리 파라미터로 받아서 여기서 막는다
     * (0.5원이 들어오면 잔액에 화면에 안 보이는 소수가 남는다).
     */
    private static void validateAmount(BigDecimal amount, String action) {
        // BigDecimal 비교는 부호 검사(signum)로 — compareTo(ZERO)와 동일하지만 의도가 명확하다.
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, action + " 금액은 0보다 커야 합니다.");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, action + " 금액은 원 단위 정수여야 합니다.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, action + " 금액이 허용 범위를 넘습니다.");
        }
    }

    /**
     * 계좌 소유자를 락 없이 확인한다(프로젝션 — 엔티티를 1차 캐시에 올리지 않는다).
     * 계좌가 없으면 404, 본인 것이 아니면 403.
     */
    private void assertOwner(Long accountId, Member member, String forbiddenMessage) {
        Long ownerId = accountRepository.findOwnerIdById(accountId)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + accountId));
        if (!ownerId.equals(member.getId())) {
            throw new ForbiddenException(forbiddenMessage);
        }
    }

    /** 비관적 쓰기 락으로 계좌를 적재한다 — 반드시 해당 계좌 엔티티의 첫 조회여야 한다. */
    private Account lockAccount(Long accountId) {
        return accountRepository.findWithLockingById(accountId)
                .orElseThrow(() -> new NotFoundException("계좌를 찾을 수 없습니다: " + accountId));
    }


    private AccountSummaryResponse toSummary(Account a) {
        // 응답은 원 단위 정수로 스케일 정리 (DB의 DECIMAL(19,4)가 1000.0000으로 내려와도 JSON은 1000)
        return new AccountSummaryResponse(
                a.getId(),
                a.getAccountNumber(),
                MoneyPolicy.toWon(a.getBalance()),
                a.isLocked(),
                a.getMember().getEmail(),
                a.getMember().getName()
        );
    }

    private TransactionLogResponse toTxDto(TransactionLog t) {
        Long fromId = t.getFromAccount() != null ? t.getFromAccount().getId() : null;
        Long toId = t.getToAccount() != null ? t.getToAccount().getId() : null;

        return new TransactionLogResponse(
                t.getId(),
                t.getType().name(),
                fromId,
                toId,
                MoneyPolicy.toWon(t.getAmount()),
                MoneyPolicy.toWon(t.getBalanceAfter()),
                t.getDescription(),
                t.getCreatedAt()
        );
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 10;

    /**
     * 12자리 계좌번호를 생성한다.
     * 예측 가능한 Random 대신 SecureRandom을 사용하고,
     * 유니크 제약과 충돌하면 최대 {@value #MAX_ACCOUNT_NUMBER_ATTEMPTS}회까지 재생성한다.
     */
    private String generateAccountNumber() {
        for (int attempt = 0; attempt < MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String candidate = randomAccountNumber();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.ACCOUNT_NUMBER_GENERATION_FAILED);
    }

    private String randomAccountNumber() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(SECURE_RANDOM.nextInt(10));
        }
        return sb.toString();
    }





}
