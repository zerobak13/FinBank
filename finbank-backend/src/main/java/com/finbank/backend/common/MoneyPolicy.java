package com.finbank.backend.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 금액 처리 정책의 단일 출처(Single Source of Truth).
 *
 * <p>프로젝트 전역의 금액 스케일과 반올림 규칙을 이 클래스 하나에 고정한다.
 * 이자·수수료 계산(여신 모듈)이 이 정책을 그대로 사용하므로,
 * 규칙 변경은 반드시 여기서만 이뤄져야 한다.</p>
 *
 * <ul>
 *   <li>중간 계산: scale 10, HALF_UP — 계산 도중 정밀도 손실 방지</li>
 *   <li>확정 금액(잔액·거래액·이자): 원 미만 절사(DOWN) — 국내 은행 관행</li>
 *   <li>일할 계산: 연 365일 고정 (윤년 무시 — 컨벤션 명시)</li>
 * </ul>
 */
public final class MoneyPolicy {

    private MoneyPolicy() {
    }

    /** 중간 계산용 스케일 (금리 나눗셈, 거듭제곱 등) */
    public static final int CALC_SCALE = 10;

    /** 중간 계산용 반올림 */
    public static final RoundingMode CALC_ROUND = RoundingMode.HALF_UP;

    /** 확정 금액 스케일 — 원 단위 정수 */
    public static final int WON_SCALE = 0;

    /** 확정 이자/금액 반올림 — 원 미만 절사 */
    public static final RoundingMode INTEREST_ROUND = RoundingMode.DOWN;

    /** 월 상환금(원리금균등) 반올림 */
    public static final RoundingMode PAYMENT_ROUND = RoundingMode.HALF_UP;

    /** 일할 계산 기준일수 (윤년 무시, 365 고정) */
    public static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    /** 법정 최고금리 (연 20%) */
    public static final BigDecimal MAX_LEGAL_RATE = new BigDecimal("0.20");

    /** 확정 금액으로 변환: 원 미만 절사 */
    public static BigDecimal toWon(BigDecimal value) {
        return value.setScale(WON_SCALE, INTEREST_ROUND);
    }
}
