package com.finbank.backend.loan.harness;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 상환 스케줄 입력 계약 — 엔진이 받아들여야 하는 입력의 범위 (명세, INP-01~04).
 *
 * <p>계약 밖의 입력은 {@link IllegalArgumentException}으로 거부되어야 한다.
 * 상한값은 구현 쪽 상수({@code MoneyPolicy} 등)를 참조하지 않고 여기에 따로 둔다.
 * 판정 기준이 구현에 기대면, 구현이 상수를 바꾸는 순간 기준도 같이 느슨해지기 때문이다.</p>
 *
 * <p>도입 배경: 검증 에이전트 1라운드(harness-reports/demo/verify-1.md)가 원 미만 원금, 법정 최고금리 초과,
 * 날짜 범위 초과, 기간 상한 부재를 찾았고, 사람이 아래 계약으로 확정했다(2026-11-04).</p>
 */
public final class InputContract {

    /** 법정 최고금리 연 20% — 정확히 20%는 허용 */
    public static final BigDecimal MAX_ANNUAL_RATE = new BigDecimal("0.20");

    /** 대출 기간 상한 600개월(50년) — 상품 정책이 아니라 엔진이 감당할 수 있는 범위 */
    public static final int MAX_TERM_MONTHS = 600;

    private InputContract() {
    }

    /** 위반한 계약 목록. 비어 있으면 계약 안의 입력이다. */
    public static List<LoanInvariant> violations(BigDecimal principal, BigDecimal annualRate,
                                                 int termMonths, LocalDate executedAt) {
        List<LoanInvariant> v = new ArrayList<>();
        if (principal == null || principal.signum() <= 0 || principal.stripTrailingZeros().scale() > 0) {
            v.add(LoanInvariant.INP_01);
        }
        if (annualRate == null || annualRate.signum() < 0 || annualRate.compareTo(MAX_ANNUAL_RATE) > 0) {
            v.add(LoanInvariant.INP_02);
        }
        if (termMonths < 1 || termMonths > MAX_TERM_MONTHS) {
            v.add(LoanInvariant.INP_03);
        }
        if (executedAt == null) {
            v.add(LoanInvariant.INP_04);
        } else {
            try {
                executedAt.plusMonths(termMonths);
            } catch (DateTimeException e) {
                v.add(LoanInvariant.INP_04);
            }
        }
        return v;
    }
}
