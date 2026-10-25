package com.finbank.backend.loan.harness;

import com.finbank.backend.loan.domain.RepaymentType;
import com.finbank.backend.loan.domain.calculator.BulletGenerator;
import com.finbank.backend.loan.domain.calculator.EqualPaymentGenerator;
import com.finbank.backend.loan.domain.calculator.EqualPrincipalGenerator;
import com.finbank.backend.loan.domain.calculator.RepaymentScheduleGenerator;

/**
 * 상환방식 → 생성기 매핑 (하네스 전용, Spring 컨텍스트 없이 쓰기 위함).
 * 프로덕션 매핑은 {@code LoanCalculatorConfig}에 있고, 둘이 같은 구현체를 가리켜야 한다.
 */
public final class HarnessGenerators {

    private HarnessGenerators() {
    }

    public static RepaymentScheduleGenerator of(RepaymentType type) {
        return switch (type) {
            case EQUAL_PAYMENT -> new EqualPaymentGenerator();
            case EQUAL_PRINCIPAL -> new EqualPrincipalGenerator();
            case BULLET -> new BulletGenerator();
        };
    }
}
