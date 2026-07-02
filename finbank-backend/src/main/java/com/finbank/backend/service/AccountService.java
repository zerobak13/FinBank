package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.TransactionLog;
import com.finbank.backend.domain.TransactionType;
import com.finbank.backend.dto.*;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.finbank.backend.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private final MemberRepository memberRepository;
    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final RedissonClient redissonClient;
    private final TransferExecutor transferExecutor;

    public AccountService(MemberRepository memberRepository,
                          AccountRepository accountRepository,
                          TransactionLogRepository transactionLogRepository,
                          RedissonClient redissonClient,
                          TransferExecutor transferExecutor) {
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.redissonClient = redissonClient;
        this.transferExecutor = transferExecutor;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    Member getCurrentMember() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String email = principal.toString();
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("Member not found: " + email));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 계좌 개설
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 계좌 개설 후 해당 사용자의 계좌 목록 캐시를 무효화한다.
     * 캐시 키는 이메일(현재 로그인 사용자)이므로,
     * 계좌를 새로 만들면 목록 캐시가 즉시 갱신되어야 한다.
     */
    @Caching(evict = {
            @CacheEvict(value = "accounts",
                    key = "T(org.springframework.security.core.context.SecurityContextHolder)" +
                            ".getContext().getAuthentication().getName()")
    })
    @Transactional
    public AccountSummaryResponse createAccount(AccountCreateRequest request) {
        Member member = getCurrentMember();

        String accountNumber = generateAccountNumber();

        // SAVINGS 계좌는 연 2% 고정 이자율 적용
        java.math.BigDecimal interestRate = request.getAccountType() == com.finbank.backend.domain.AccountType.SAVINGS
                ? new java.math.BigDecimal("0.0200")
                : java.math.BigDecimal.ZERO;

        Account account = new Account(member, accountNumber, 0L, request.getAccountType(), interestRate);
        if (request.getInitialDeposit() > 0) {
            account.deposit(request.getInitialDeposit());
        }
        Account saved = accountRepository.save(account);

        if (request.getInitialDeposit() > 0) {
            TransactionLog log = TransactionLog.deposit(saved,
                    request.getInitialDeposit(), saved.getBalance());
            transactionLogRepository.save(log);
        }

        return toSummary(saved);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 조회 (캐시 적용)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 내 계좌 목록 — 로그인 이메일을 캐시 키로 사용.
     * 입금/출금/이체/계좌 개설 시 해당 키의 캐시가 무효화된다.
     */
    @Cacheable(value = "accounts",
            key = "T(org.springframework.security.core.context.SecurityContextHolder)" +
                    ".getContext().getAuthentication().getName()")
    @Transactional(readOnly = true)
    public List<AccountSummaryResponse> getMyAccounts() {
        Member member = getCurrentMember();
        List<Account> accounts = accountRepository.findByMember(member);
        return accounts.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    /**
     * 계좌 상세 (거래 내역 포함) — accountId를 캐시 키로 사용.
     * 잔액이 변경되는 모든 연산에서 해당 키의 캐시가 무효화된다.
     */
    @Cacheable(value = "accountDetail", key = "#accountId")
    @Transactional(readOnly = true)
    public AccountDetailResponse getAccountDetail(Long accountId) {
        Member member = getCurrentMember();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));

        if (!account.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌만 조회할 수 있습니다.");
        }

        List<TransactionLog> logs =
                transactionLogRepository.findByAccountPerspective(
                        account,
                        List.of(TransactionType.WITHDRAW, TransactionType.TRANSFER_OUT),
                        List.of(TransactionType.DEPOSIT, TransactionType.TRANSFER_IN),
                        Pageable.unpaged()
                ).getContent();

        AccountSummaryResponse summary = toSummary(account);
        List<TransactionLogResponse> txDtos = logs.stream()
                .map(this::toTxDto)
                .collect(Collectors.toList());

        return new AccountDetailResponse(summary, txDtos);
    }

    @Transactional(readOnly = true)
    public AccountSummaryResponse getAccountSummary(Long accountId) {
        Member member = getCurrentMember();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));

        if (!account.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌만 조회할 수 있습니다.");
        }

        return toSummary(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionLogResponse> getAccountTransactions(Long accountId, int page, int size) {
        Member member = getCurrentMember();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));

        if (!account.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌만 조회할 수 있습니다.");
        }

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<TransactionLog> logPage = transactionLogRepository.findByAccountPerspective(
                account,
                List.of(TransactionType.WITHDRAW, TransactionType.TRANSFER_OUT),
                List.of(TransactionType.DEPOSIT, TransactionType.TRANSFER_IN),
                pageable
        );

        Page<TransactionLogResponse> dtoPage = logPage.map(this::toTxDto);
        return new PageResponse<>(dtoPage);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 입금
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 입금 후 계좌 목록·상세 캐시를 함께 무효화.
     * accounts 캐시는 이메일 키이므로 SecurityContextHolder에서 추출.
     * accountDetail 캐시는 accountId 키로 직접 지정.
     */
    @Caching(evict = {
            @CacheEvict(value = "accounts",
                    key = "T(org.springframework.security.core.context.SecurityContextHolder)" +
                            ".getContext().getAuthentication().getName()"),
            @CacheEvict(value = "accountDetail", key = "#accountId")
    })
    @Transactional
    public void deposit(Long accountId, long amount) {
        if (amount <= 0) {
            throw new BusinessException("입금 금액은 0보다 커야 합니다.");
        }

        Member member = getCurrentMember();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        if (!account.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌만 입금할 수 있습니다.");
        }

        account.deposit(amount);
        accountRepository.save(account);

        transactionLogRepository.save(
                TransactionLog.deposit(account, amount, account.getBalance())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 출금
    // ─────────────────────────────────────────────────────────────────────────

    @Caching(evict = {
            @CacheEvict(value = "accounts",
                    key = "T(org.springframework.security.core.context.SecurityContextHolder)" +
                            ".getContext().getAuthentication().getName()"),
            @CacheEvict(value = "accountDetail", key = "#accountId")
    })
    @Transactional
    public void withdraw(Long accountId, long amount) {
        if (amount <= 0) {
            throw new BusinessException("출금 금액은 0보다 커야 합니다.");
        }

        Member member = getCurrentMember();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        if (!account.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌만 출금할 수 있습니다.");
        }

        if (account.getBalance() < amount) {
            throw new BusinessException("잔액이 부족합니다.");
        }

        account.withdraw(amount);
        accountRepository.save(account);

        transactionLogRepository.save(
                TransactionLog.withdraw(account, amount, account.getBalance())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 이체 — Redisson 분산 락
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 계좌 간 이체.
     *
     * <p><b>분산 락 전략</b><br>
     * DB 비관적 락(SELECT FOR UPDATE) 대신 Redisson MultiLock을 사용한다.<br>
     * 두 계좌를 항상 ID 오름차순으로 락을 획득해 데드락을 원천 차단한다.<br>
     * 단일 서버에서는 DB 락으로 충분하지만, 다중 서버(Scale-out) 환경에서는
     * JVM 수준의 synchronized나 DB 행 락이 서버 간 동시성을 보장하지 못한다.
     * Redisson은 Redis를 통해 서버를 가리지 않는 분산 락을 제공한다.</p>
     *
     * <p><b>이체 완료 후 양측 계좌의 캐시를 모두 무효화한다.</b><br>
     * 송금인과 수취인이 서로 다른 회원일 수 있으므로
     * accounts 캐시는 allEntries=true로 전체 무효화,
     * accountDetail 캐시는 두 계좌 ID를 각각 무효화한다.</p>
     */
    /**
     * 주의: 이 메서드 자체는 {@code @Transactional}이 아니다.
     * 락 획득 "이후"에 {@link TransferExecutor#execute}가 새 트랜잭션을 열어야
     * REPEATABLE READ 스냅샷이 락 대기 중 커밋된 최신 데이터를 반영하기 때문이다.
     * (자세한 이유는 {@link TransferExecutor} 클래스 주석 참고)
     */
    @Caching(evict = {
            @CacheEvict(value = "accounts", allEntries = true),
            @CacheEvict(value = "accountDetail", key = "#request.fromAccountId"),
    })
    public void transfer(TransferRequest request) {
        Member member = getCurrentMember();

        // 1. 계좌 조회 (락 없이) — 존재 여부 및 권한 검증
        Account fromAccount = accountRepository.findById(request.getFromAccountId())
                .orElseThrow(() -> new NotFoundException("From account not found"));

        if (!fromAccount.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌에서만 이체할 수 있습니다.");
        }

        Account toAccount = accountRepository.findByAccountNumber(request.getToAccountNumber())
                .orElseThrow(() -> new NotFoundException("받는 계좌를 찾을 수 없습니다."));

        if (fromAccount.getId().equals(toAccount.getId())) {
            throw new BusinessException("같은 계좌로는 이체할 수 없습니다.");
        }

        if (request.getAmount() <= 0) {
            throw new BusinessException("이체 금액은 0보다 커야 합니다.");
        }

        // 2. Redisson MultiLock — ID 오름차순으로 획득해 데드락 방지
        Long firstId  = Math.min(fromAccount.getId(), toAccount.getId());
        Long secondId = Math.max(fromAccount.getId(), toAccount.getId());

        RLock lock1 = redissonClient.getLock("account:lock:" + firstId);
        RLock lock2 = redissonClient.getLock("account:lock:" + secondId);
        RLock multiLock = redissonClient.getMultiLock(lock1, lock2);

        try {
            // 대기 5초, 락 보유 최대 10초
            boolean acquired = multiLock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException("현재 다른 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");
            }

            // 3. 락 획득 후 — 별도 빈 호출로 "새" 트랜잭션을 열어 최신 데이터를 반영
            transferExecutor.execute(fromAccount.getId(), toAccount.getId(), request.getAmount());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("락 획득 중 인터럽트가 발생했습니다.");
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 변환 헬퍼
    // ─────────────────────────────────────────────────────────────────────────

    private AccountSummaryResponse toSummary(Account a) {
        return new AccountSummaryResponse(
                a.getId(),
                a.getAccountNumber(),
                a.getBalance(),
                a.isLocked(),
                a.getAccountType(),
                a.getInterestRate(),
                a.getMember().getEmail(),
                a.getMember().getName()
        );
    }

    private TransactionLogResponse toTxDto(TransactionLog t) {
        Long fromId = t.getFromAccount() != null ? t.getFromAccount().getId() : null;
        Long toId   = t.getToAccount()   != null ? t.getToAccount().getId()   : null;

        return new TransactionLogResponse(
                t.getId(),
                t.getType().name(),
                fromId,
                toId,
                t.getAmount(),
                t.getBalanceAfter(),
                t.getDescription(),
                t.getCreatedAt()
        );
    }

    private String generateAccountNumber() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
