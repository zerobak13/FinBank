package com.finbank.backend.batch;

import com.finbank.backend.batch.dto.InterestResult;
import com.finbank.backend.batch.dto.InterestSettlementItem;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.batch.MyBatisPagingItemReader;
import org.mybatis.spring.batch.builder.MyBatisPagingItemReaderBuilder;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 이자 정산 배치 Job 설정.
 *
 * [구조]
 *   Job → Step → Reader(MyBatis) → Processor → Writer
 *
 * [Chunk 기반 처리 이유]
 *   수십만 건의 계좌를 한 번에 메모리에 올리면 OOM 위험.
 *   chunkSize = 100으로 100건씩 읽고 → 계산하고 → 반영한다.
 *   한 청크 실패 시 해당 청크만 롤백되고 Job은 실패 지점부터 재시작 가능.
 *
 * [MyBatisPagingItemReader 선택 이유]
 *   JPA로 전체 계좌를 조회하면 영속성 컨텍스트에 엔티티가 쌓여 메모리 부담.
 *   MyBatis 페이징 Reader는 SQL 레벨에서 LIMIT/OFFSET으로 끊어 읽어 메모리 효율적.
 */
@Configuration
@EnableScheduling
public class InterestSettlementJobConfig {

    private static final int CHUNK_SIZE = 100;

    @Bean
    public Job interestSettlementJob(JobRepository jobRepository, Step interestSettlementStep) {
        return new JobBuilder("interestSettlementJob", jobRepository)
                .start(interestSettlementStep)
                .build();
    }

    @Bean
    public Step interestSettlementStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       MyBatisPagingItemReader<InterestSettlementItem> interestItemReader,
                                       InterestItemProcessor processor,
                                       InterestItemWriter writer) {
        return new StepBuilder("interestSettlementStep", jobRepository)
                .<InterestSettlementItem, InterestResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(interestItemReader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    @Bean
    public MyBatisPagingItemReader<InterestSettlementItem> interestItemReader(SqlSessionFactory sqlSessionFactory) {
        return new MyBatisPagingItemReaderBuilder<InterestSettlementItem>()
                .pageSize(CHUNK_SIZE)
                .sqlSessionFactory(sqlSessionFactory)
                .queryId("com.finbank.backend.mapper.AccountMapper.findSavingsAccountsForBatch")
                .build();
    }
}
