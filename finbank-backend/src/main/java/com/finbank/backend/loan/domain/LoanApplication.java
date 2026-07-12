package com.finbank.backend.loan.domain;

import com.finbank.backend.domain.Member;
import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 대출 신청 엔티티.
 *
 * <p>상태 전이는 반드시 도메인 메서드(approve/reject/cancel/markExecuted)로만 이뤄진다.
 * 잘못된 전이는 {@link ErrorCode#INVALID_STATE}(409)로 거부 — "불변식은 도메인이 지킨다".</p>
 */
@Entity
@Table(name = "loan_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 신청 회원 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** 신청 상품 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private LoanProduct product;

    /** 신청 금액 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal requestedAmount;

    /** 대출 기간 (개월) */
    @Column(nullable = false)
    private int termMonths;

    /** 신청 상태 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    /** 자동심사 탈락 룰 코드 (탈락 시에만) */
    @Column(length = 50)
    private String rejectReason;

    @Column(nullable = false)
    private LocalDateTime appliedAt = LocalDateTime.now();

    private LocalDateTime reviewedAt;

    public LoanApplication(Member member, LoanProduct product, BigDecimal requestedAmount, int termMonths) {
        this.member = member;
        this.product = product;
        this.requestedAmount = requestedAmount;
        this.termMonths = termMonths;
    }

    /** 심사 통과: APPLIED → APPROVED */
    public void approve() {
        transition(ApplicationStatus.APPLIED, ApplicationStatus.APPROVED);
        this.reviewedAt = LocalDateTime.now();
    }

    /** 심사 탈락: APPLIED → REJECTED (탈락 룰 코드 기록) */
    public void reject(String ruleCode) {
        transition(ApplicationStatus.APPLIED, ApplicationStatus.REJECTED);
        this.rejectReason = ruleCode;
        this.reviewedAt = LocalDateTime.now();
    }

    /** 사용자 취소: APPLIED/APPROVED → CANCELED (실행 후에는 불가) */
    public void cancel() {
        if (this.status != ApplicationStatus.APPLIED && this.status != ApplicationStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "현재 상태(" + this.status + ")에서는 취소할 수 없습니다.");
        }
        this.status = ApplicationStatus.CANCELED;
    }

    /** 실행 완료 표시: APPROVED → EXECUTED (승인된 신청만 실행 가능) */
    public void markExecuted() {
        transition(ApplicationStatus.APPROVED, ApplicationStatus.EXECUTED);
    }

    private void transition(ApplicationStatus from, ApplicationStatus to) {
        if (this.status != from) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "현재 상태(" + this.status + ")에서는 " + to + "(으)로 전이할 수 없습니다.");
        }
        this.status = to;
    }
}
