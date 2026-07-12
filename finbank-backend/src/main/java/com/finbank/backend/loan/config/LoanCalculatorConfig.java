package com.finbank.backend.loan.config;

import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.calculator.BulletGenerator;
import com.finbank.backend.loan.domain.calculator.EqualPaymentGenerator;
import com.finbank.backend.loan.domain.calculator.EqualPrincipalGenerator;
import com.finbank.backend.loan.domain.calculator.RepaymentScheduleGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 순수 자바 계산기를 스프링 세계에 연결하는 유일한 지점.
 * 대출 실행 서비스(C3)는 이 Map에서 상환방식에 맞는 생성기를 꺼내 쓴다.
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
}
