package com.finbank.backend.service;

import com.finbank.backend.domain.Account;
import com.finbank.backend.domain.AccountType;
import com.finbank.backend.domain.Member;
import com.finbank.backend.domain.TransactionType;
import com.finbank.backend.repository.AccountRepository;
import com.finbank.backend.repository.IdempotencyKeyRepository;
import com.finbank.backend.repository.MemberRepository;
import com.finbank.backend.repository.RefreshTokenRepository;
import com.finbank.backend.repository.TransactionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InterestBatchTest {

    @Autowired JobLauncher jobLauncher;
    @Autowired Job interestSettlementJob;
    @Autowired MemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionLogRepository transactionLogRepository;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        // members를 참조하는 테이블을 먼저 정리 (FK 제약)
        transactionLogRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("SAVINGS 계좌는 이자 정산 후 잔액이 증가하고 INTEREST 로그가 생긴다")
    void savings_account_should_receive_daily_interest() throws Exception {
        // given
        Member member = memberRepository.save(new Member("batch@test.com", "테스터", "1234"));

        // 연 2% 적금 계좌 (잔액 365만원 → 일일 이자 = 3,650,000 × 0.02 / 365 = 200원)
        Account savings = accountRepository.save(
                new Account(member, "100000000001", 3_650_000L,
                        AccountType.SAVINGS, new BigDecimal("0.0200"))
        );

        // 일반 계좌 (이자 정산 대상 아님)
        Account regular = accountRepository.save(
                new Account(member, "200000000002", 1_000_000L)
        );

        // when
        JobParameters params = new JobParametersBuilder()
                .addString("settlementDate", LocalDate.now().toString() + "_test_" + System.currentTimeMillis())
                .toJobParameters();
        jobLauncher.run(interestSettlementJob, params);

        // then
        Account updatedSavings = accountRepository.findById(savings.getId()).orElseThrow();
        Account updatedRegular = accountRepository.findById(regular.getId()).orElseThrow();

        // 적금 계좌 잔액 증가 확인 (일일 이자 200원)
        assertThat(updatedSavings.getBalance()).isEqualTo(3_650_200L);

        // 일반 계좌 잔액 변동 없음
        assertThat(updatedRegular.getBalance()).isEqualTo(1_000_000L);

        // 거래 로그에 INTEREST 타입 1건 기록 확인
        assertThat(transactionLogRepository.findAll())
                .filteredOn(log -> log.getType() == TransactionType.INTEREST)
                .hasSize(1)
                .first()
                .satisfies(log -> assertThat(log.getAmount()).isEqualTo(200L));
    }

    @Test
    @DisplayName("잠금된 SAVINGS 계좌는 이자 정산 대상에서 제외된다")
    void locked_savings_account_should_be_excluded() throws Exception {
        // given
        Member member = memberRepository.save(new Member("batchlock@test.com", "테스터", "1234"));

        Account lockedSavings = accountRepository.save(
                new Account(member, "300000000003", 1_000_000L,
                        AccountType.SAVINGS, new BigDecimal("0.0200"))
        );
        // 계좌 잠금 처리 (직접 쿼리)
        accountRepository.lockAccount(lockedSavings.getId());

        // when
        JobParameters params = new JobParametersBuilder()
                .addString("settlementDate", LocalDate.now().toString() + "_lock_" + System.currentTimeMillis())
                .toJobParameters();
        jobLauncher.run(interestSettlementJob, params);

        // then - 잔액 변동 없음
        Account result = accountRepository.findById(lockedSavings.getId()).orElseThrow();
        assertThat(result.getBalance()).isEqualTo(1_000_000L);
        assertThat(transactionLogRepository.findAll())
                .filteredOn(log -> log.getType() == TransactionType.INTEREST)
                .isEmpty();
    }
}
