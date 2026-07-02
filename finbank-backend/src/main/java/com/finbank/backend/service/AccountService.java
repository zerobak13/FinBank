package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.TransactionLog;
import com.finbank.backend.domain.TransactionType;
import com.finbank.backend.dto.*;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ForbiddenException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.TransactionLogRepository;
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
import java.util.stream.Collectors;

@Service
public class AccountService {

    private final MemberRepository memberRepository;
    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;

    public AccountService(MemberRepository memberRepository,
                          AccountRepository accountRepository,
                          TransactionLogRepository transactionLogRepository) {
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.transactionLogRepository = transactionLogRepository;
    }

    private Member getCurrentMember() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String email = principal.toString();
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("Member not found: " + email));
    }

    @Transactional
    public AccountSummaryResponse createAccount(AccountCreateRequest request) {
        Member member = getCurrentMember();

        String accountNumber = generateAccountNumber();
        Account account = new Account(member, accountNumber, 0L);
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
    @Transactional(readOnly = true)
    public List<AccountSummaryResponse> getMyAccounts() {
        Member member = getCurrentMember();
        List<Account> accounts = accountRepository.findByMember(member);
        return accounts.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AccountSummaryResponse getAccountSummary(Long accountId) {
        Member member = getCurrentMember();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));

        if (!account.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인 계좌만 조회할 수 있습니다.");
        }

        return toSummary(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionLogResponse> getAccountTransactions(Long accountId, int page, int size) {
        Member member = getCurrentMember();

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));

        if (!account.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인 계좌만 조회할 수 있습니다.");
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


    @Transactional
    public void deposit(Long accountId, long amount) {
        if (amount <= 0) {
            throw new BusinessException("입금 금액은 0보다 커야 합니다.");
        }

        Member member = getCurrentMember();

        // 동시 입금 시 lost update 방지를 위해 비관적 락으로 조회한다.
        // (이 조회가 계좌의 첫 조회이므로 항상 커밋된 최신 잔액을 읽는다.)
        Account account = accountRepository.findWithLockingById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        //본인 계좌 체크
        if (!account.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인 계좌만 입금할 수 있습니다.");
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
    public void withdraw(Long accountId, long amount) {
        if (amount <= 0) {
            throw new BusinessException("출금 금액은 0보다 커야 합니다.");
        }

        Member member = getCurrentMember();

        // 동시 출금 시 lost update 방지를 위해 비관적 락으로 조회한다.
        // (이 조회가 계좌의 첫 조회이므로 항상 커밋된 최신 잔액을 읽는다.)
        Account account = accountRepository.findWithLockingById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        //본인 계좌 체크
        if (!account.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인 계좌만 출금할 수 있습니다.");
        }

        if (account.getBalance() < amount) {
            throw new BusinessException("잔액이 부족합니다.");
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
     */
    @Transactional
    public void transfer(TransferRequest request) {
        Member member = getCurrentMember();

        if (request.getAmount() <= 0) {
            throw new BusinessException("이체 금액은 0보다 커야 합니다.");
        }

        Long fromId = request.getFromAccountId();

        // 받는 계좌는 ID만 조회 (엔티티를 캐시에 올리지 않아 이후 락 조회가 항상 최신 값을 읽게 함)
        Long toId = accountRepository.findIdByAccountNumber(request.getToAccountNumber())
                .orElseThrow(() -> new NotFoundException("받는 계좌를 찾을 수 없습니다."));

        if (fromId.equals(toId)) {
            throw new BusinessException("같은 계좌로는 이체할 수 없습니다.");
        }

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

        if (!from.getMember().getId().equals(member.getId())) {
            throw new ForbiddenException("본인 계좌에서만 이체할 수 있습니다.");
        }

        if (from.isLocked() || to.isLocked()) {
            throw new BusinessException("잠금된 계좌가 있습니다.");
        }

        if (from.getBalance() < request.getAmount()) {
            throw new BusinessException("잔액이 부족합니다.");
        }

        // 잔액 이동
        from.withdraw(request.getAmount());
        to.deposit(request.getAmount());

        accountRepository.save(from);
        accountRepository.save(to);

        // 5. 이체 로그
        transactionLogRepository.save(
                TransactionLog.transferOut(from, to, request.getAmount(), from.getBalance())
        );
        transactionLogRepository.save(
                TransactionLog.transferIn(from, to, request.getAmount(), to.getBalance())
        );
    }


    private AccountSummaryResponse toSummary(Account a) {
        return new AccountSummaryResponse(
                a.getId(),
                a.getAccountNumber(),
                a.getBalance(),
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
