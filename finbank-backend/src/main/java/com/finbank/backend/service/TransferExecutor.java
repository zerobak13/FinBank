package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.TransactionLog;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.NotFoundException;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이체의 실제 DB 작업(잔액 이동 + 로그 기록)을 전담하는 컴포넌트.
 *
 * <p><b>왜 별도 빈으로 분리했는가</b><br>
 * {@code AccountService.transfer()}는 Redisson 분산 락을 획득한 *이후에*
 * 이 클래스의 {@link #execute} 메서드를 호출해야 한다.
 * 트랜잭션은 메서드에 진입하는 "시점"에 시작되고, MySQL의 기본 격리수준인
 * REPEATABLE READ는 그 시작 시점의 스냅샷을 트랜잭션 내내 유지한다.</p>
 *
 * <p>만약 락 획득과 DB 재조회가 같은 트랜잭션(= transfer() 전체에 걸린
 * {@code @Transactional}) 안에서 일어난다면, 락을 기다렸다가 통과한 스레드가
 * "최신 데이터"를 다시 읽어도 실제로는 트랜잭션이 시작된(=락 획득 전) 시점의
 * 오래된 스냅샷을 보게 된다. 락은 정상적으로 걸리지만 재조회가 무의미해져서,
 * 두 스레드가 모두 "잔액 충분"으로 판단하고 동시에 차감을 시도하는 lost update가
 * 발생할 수 있다.</p>
 *
 * <p>이를 막기 위해 실제 차감/적립 로직을 별도 빈의 {@code @Transactional}
 * 메서드로 분리했다. {@code AccountService}가 같은 클래스 안에서
 * {@code this.executeTransfer(...)}를 호출하면 Spring AOP 프록시를 거치지
 * 않아 {@code @Transactional}이 적용되지 않으므로(self-invocation 문제),
 * 별도 빈을 통해 호출해야만 새 트랜잭션이 "락 획득 이후" 시점에 열린다.</p>
 */
@Component
class TransferExecutor {

    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;

    TransferExecutor(AccountRepository accountRepository,
                      TransactionLogRepository transactionLogRepository) {
        this.accountRepository = accountRepository;
        this.transactionLogRepository = transactionLogRepository;
    }

    @Transactional
    void execute(Long fromAccountId, Long toAccountId, long amount) {
        // 락 획득 이후 새 트랜잭션에서 다시 조회 — 락 대기 중 다른 트랜잭션이
        // 커밋한 최신 잔액을 정확히 반영한다.
        Account from = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> new NotFoundException("From account not found"));
        Account to = accountRepository.findById(toAccountId)
                .orElseThrow(() -> new NotFoundException("받는 계좌를 찾을 수 없습니다."));

        if (from.isLocked() || to.isLocked()) {
            throw new BusinessException("잠금된 계좌가 있습니다.");
        }

        if (from.getBalance() < amount) {
            throw new BusinessException("잔액이 부족합니다.");
        }

        from.withdraw(amount);
        to.deposit(amount);

        accountRepository.save(from);
        accountRepository.save(to);

        transactionLogRepository.save(
                TransactionLog.transferOut(from, to, amount, from.getBalance())
        );
        transactionLogRepository.save(
                TransactionLog.transferIn(from, to, amount, to.getBalance())
        );
    }
}
