package com.finbank.backend.loan.dto;

import com.finbank.backend.common.MoneyPolicy;
import com.finbank.backend.loan.domain.ApplicationStatus;
import com.finbank.backend.loan.domain.LoanApplication;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 대출 신청 응답 — 자동심사 결과(status, rejectReason)를 포함한다 */
public record LoanApplicationResponse(
        Long id,
        Long productId,
        String productName,
        BigDecimal requestedAmount,
        int termMonths,
        ApplicationStatus status,
        String rejectReason,
        LocalDateTime appliedAt,
        LocalDateTime reviewedAt
) {
    public static LoanApplicationResponse from(LoanApplication a) {
        return new LoanApplicationResponse(
                a.getId(),
                a.getProduct().getId(),
                a.getProduct().getName(),
                MoneyPolicy.toWon(a.getRequestedAmount()),
                a.getTermMonths(),
                a.getStatus(),
                a.getRejectReason(),
                a.getAppliedAt(),
                a.getReviewedAt()
        );
    }
}
