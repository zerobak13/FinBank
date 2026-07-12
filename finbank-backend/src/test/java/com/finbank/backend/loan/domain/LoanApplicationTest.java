package com.finbank.backend.loan.domain;

import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대출 신청 상태 전이 단위 테스트 (순수 도메인 — Spring/DB 불필요).
 * 전이표의 허용/금지 케이스를 전부 커버한다.
 */
class LoanApplicationTest {

    private LoanApplication newApplication() {
        return new LoanApplication(null, null, new BigDecimal("10000000"), 12);
    }

    @Test
    @DisplayName("APPLIED → approve() → APPROVED, reviewedAt 기록")
    void approve_fromApplied() {
        LoanApplication app = newApplication();
        app.approve();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("APPLIED → reject(룰코드) → REJECTED, 사유 기록")
    void reject_fromApplied() {
        LoanApplication app = newApplication();
        app.reject("CREDIT_LIMIT_EXCEEDED");
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(app.getRejectReason()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("REJECTED 상태에서 approve() 시도 → 409 INVALID_STATE")
    void approve_fromRejected_throws() {
        LoanApplication app = newApplication();
        app.reject("NO_ACTIVE_ACCOUNT");
        assertThatThrownBy(app::approve)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("APPROVED → markExecuted() → EXECUTED")
    void markExecuted_fromApproved() {
        LoanApplication app = newApplication();
        app.approve();
        app.markExecuted();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.EXECUTED);
    }

    @Test
    @DisplayName("승인 없이 markExecuted() 시도 → 409 (APPLIED에서 바로 실행 불가)")
    void markExecuted_fromApplied_throws() {
        LoanApplication app = newApplication();
        assertThatThrownBy(app::markExecuted).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("APPLIED/APPROVED에서는 취소 가능, EXECUTED에서는 불가")
    void cancel_rules() {
        LoanApplication applied = newApplication();
        applied.cancel();
        assertThat(applied.getStatus()).isEqualTo(ApplicationStatus.CANCELED);

        LoanApplication approved = newApplication();
        approved.approve();
        approved.cancel();
        assertThat(approved.getStatus()).isEqualTo(ApplicationStatus.CANCELED);

        LoanApplication executed = newApplication();
        executed.approve();
        executed.markExecuted();
        assertThatThrownBy(executed::cancel).isInstanceOf(BusinessException.class);
    }
}
