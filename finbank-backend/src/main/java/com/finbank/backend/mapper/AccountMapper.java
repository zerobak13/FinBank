package com.finbank.backend.mapper;

import com.finbank.backend.batch.dto.InterestSettlementItem;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/**
 * MyBatis Mapper - 배치 대량 조회 전용.
 *
 * JPA는 단건 CRUD에 적합하지만, 배치처럼 수십만 건을 페이징으로 읽는 경우
 * N+1 문제와 영속성 컨텍스트 부담이 생긴다.
 * MyBatis는 SQL을 직접 제어하므로 대량 처리에 더 효율적이다.
 */
@Mapper
public interface AccountMapper {

    /**
     * MyBatisPagingItemReader가 호출하는 메서드.
     * _skiprows, _pagesize 파라미터는 Spring Batch가 자동으로 주입한다.
     */
    List<InterestSettlementItem> findSavingsAccountsForBatch(Map<String, Object> params);
}
