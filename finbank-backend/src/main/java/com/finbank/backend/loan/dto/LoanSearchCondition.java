package com.finbank.backend.loan.dto;

import com.finbank.backend.loan.domain.LoanAccountStatus;
import com.finbank.backend.loan.domain.RepaymentType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 대출 검색 조건 — <b>모든 필드가 선택</b>이다.
 *
 * <p>null인 조건은 "제약 없음"으로 취급해 WHERE 절에서 아예 빠진다.
 * 조건 6개면 조합이 64가지인데, 이걸 문자열 JPQL로 조립하면 if문마다 공백·AND를
 * 손으로 관리해야 한다. QueryDSL은 null을 반환하는 표현식을 그냥 무시하므로
 * 조합 폭발이 코드에 드러나지 않는다.</p>
 */
public record LoanSearchCondition(

        @Schema(description = "대출 상태", example = "ACTIVE")
        LoanAccountStatus status,

        @Schema(description = "상환 방식", example = "EQUAL_PAYMENT")
        RepaymentType repaymentType,

        @Schema(description = "상품 ID", example = "1")
        Long productId,

        @Schema(description = "실행일 시작(포함)", example = "2026-01-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate executedFrom,

        @Schema(description = "실행일 종료(포함)", example = "2026-12-31")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate executedTo,

        @Schema(description = "원금 최소", example = "1000000")
        BigDecimal minPrincipal,

        @Schema(description = "원금 최대", example = "50000000")
        BigDecimal maxPrincipal
) {
    /** 아무 조건도 없으면 전체 조회 */
    public boolean isEmpty() {
        return status == null && repaymentType == null && productId == null
                && executedFrom == null && executedTo == null
                && minPrincipal == null && maxPrincipal == null;
    }
}
