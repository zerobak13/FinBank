package com.finbank.backend.batch;

import com.finbank.backend.batch.dto.InterestResult;
import com.finbank.backend.batch.dto.InterestSettlementItem;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * 이자 계산 Processor.
 *
 * [일일 이자 공식]
 *   일일 이자 = 잔액 × (연이율 / 365)
 *   소수점 이하는 버림 (금융 관행: 고객에게 불리하지 않은 방향)
 *
 * [null 반환 시]
 *   Spring Batch 규약: Processor가 null을 반환하면 해당 아이템은 Writer로 넘어가지 않는다.
 *   계산된 이자가 0원이면 정산 대상에서 제외.
 */
@Component
public class InterestItemProcessor implements ItemProcessor<InterestSettlementItem, InterestResult> {

    @Override
    public InterestResult process(InterestSettlementItem item) {
        // 일일 이자 계산 (소수점 이하 버림)
        long dailyInterest = (long) (item.getBalance() * item.getInterestRate().doubleValue() / 365);

        // 이자가 0원이면 skip
        if (dailyInterest <= 0) {
            return null;
        }

        return new InterestResult(item.getId(), dailyInterest);
    }
}
