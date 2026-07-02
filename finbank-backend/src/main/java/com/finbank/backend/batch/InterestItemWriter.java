package com.finbank.backend.batch;

import com.finbank.backend.batch.dto.InterestResult;
import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.TransactionLog;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이자 반영 Writer.
 *
 * [청크 단위 처리]
 *   Spring Batch는 chunkSize(100건)만큼 쌓인 뒤 이 Writer를 한 번 호출한다.
 *   청크 단위로 트랜잭션이 묶이므로, 한 청크 내에서 실패하면 해당 청크만 롤백된다.
 *
 * [처리 내용]
 *   - 계좌 잔액에 이자 반영 (deposit)
 *   - 거래 로그 INTEREST 타입으로 기록
 */
@Component
public class InterestItemWriter implements ItemWriter<InterestResult> {

    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;

    public InterestItemWriter(AccountRepository accountRepository,
                              TransactionLogRepository transactionLogRepository) {
        this.accountRepository = accountRepository;
        this.transactionLogRepository = transactionLogRepository;
    }

    @Override
    @Transactional
    public void write(Chunk<? extends InterestResult> chunk) {
        for (InterestResult result : chunk) {
            Account account = accountRepository.findById(result.getAccountId()).orElse(null);
            if (account == null) continue;

            account.deposit(result.getInterestAmount());
            accountRepository.save(account);

            transactionLogRepository.save(
                    TransactionLog.interest(account, result.getInterestAmount(), account.getBalance())
            );
        }
    }
}
