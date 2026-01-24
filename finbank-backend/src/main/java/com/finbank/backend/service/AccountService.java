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
import jakarta.transaction.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
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

    public List<AccountSummaryResponse> getMyAccounts() {
        Member member = getCurrentMember();
        List<Account> accounts = accountRepository.findByMember(member);
        return accounts.stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

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
                        List.of(TransactionType.DEPOSIT, TransactionType.TRANSFER_IN)
                );

        AccountSummaryResponse summary = toSummary(account);
        List<TransactionLogResponse> txDtos = logs.stream()
                .map(this::toTxDto)
                .collect(Collectors.toList());

        return new AccountDetailResponse(summary, txDtos);
    }


    @Transactional
    public void deposit(Long accountId, long amount) {
        if (amount <= 0) {
            throw new BusinessException("입금 금액은 0보다 커야 합니다.");
        }

        Member member = getCurrentMember();


        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        //본인 계좌 체크
        if (!account.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌만 입금할 수 있습니다.");
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

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
        //본인 계좌 체크
        if (!account.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌만 출금할 수 있습니다.");
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

    @Transactional
    public void transfer(TransferRequest request) {
        Member member = getCurrentMember();

        Account from = accountRepository.findWithLockingById(request.getFromAccountId())
                .orElseThrow(() -> new NotFoundException("From account not found"));

        if (!from.getMember().getId().equals(member.getId())) {
            throw new BusinessException("본인 계좌에서만 이체할 수 있습니다.");
        }

        Account to = accountRepository.findWithLockingByAccountNumber(request.getToAccountNumber())
                .orElseThrow(() -> new NotFoundException("받는 계좌를 찾을 수 없습니다."));

        if (from.getAccountNumber().equals(to.getAccountNumber())) {
            throw new BusinessException("같은 계좌로는 이체할 수 없습니다.");
        }

        if (from.isLocked() || to.isLocked()) {
            throw new BusinessException("잠금된 계좌가 있습니다.");
        }

        if (from.getBalance() < request.getAmount()) {
            throw new BusinessException("잔액이 부족합니다.");
        }

        //잔액 이동
        from.withdraw(request.getAmount());
        to.deposit(request.getAmount());

        accountRepository.save(from);
        accountRepository.save(to);

        //이체 로그
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
