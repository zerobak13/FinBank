package com.finbank.backend.loan.config;

import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.calculator.AccruedInterestCalculator;
import com.finbank.backend.loan.domain.calculator.BulletGenerator;
import com.finbank.backend.loan.domain.calculator.EqualPaymentGenerator;
import com.finbank.backend.loan.domain.calculator.EqualPrincipalGenerator;
import com.finbank.backend.loan.domain.calculator.OverdueInterestCalculator;
import com.finbank.backend.loan.domain.calculator.PrepaymentFeeCalculator;
import com.finbank.backend.loan.domain.calculator.RepaymentScheduleGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 순수 자바 계산기를 스프링 세계에 연결하는 유일한 지점.
 * 대출 실행·상환 서비스(C3)는 이 Map/빈에서 필요한 계산기를 꺼내 쓴다.
 *
 * <p>계산기 자체에 {@code @Component}를 붙이지 않는 이유: 돈 계산 로직을
 * 프레임워크에서 독립시켜 두면 단위테스트가 컨텍스트 없이 ms 단위로 돌아간다.</p>
 */
@Configuration
public class LoanCalculatorConfig {

    @Bean
    public Map<RepaymentType, RepaymentScheduleGenerator> scheduleGenerators() {
        return Map.of(
                RepaymentType.EQUAL_PAYMENT, new EqualPaymentGenerator(),
                RepaymentType.EQUAL_PRINCIPAL, new EqualPrincipalGenerator(),
                RepaymentType.BULLET, new BulletGenerator()
        );
    }

    /** 경과이자 (중도상환 시 회차 사이 일할 이자) */
    @Bean
    public AccruedInterestCalculator accruedInterestCalculator() {
        return new AccruedInterestCalculator();
    }

    /** 중도상환수수료 (잔존기간 체감) */
    @Bean
    public PrepaymentFeeCalculator prepaymentFeeCalculator() {
        return new PrepaymentFeeCalculator();
    }

    /** 연체이자 (D단계 배치가 사용) */
    @Bean
    public OverdueInterestCalculator overdueInterestCalculator() {
        return new OverdueInterestCalculator();
    }
}
