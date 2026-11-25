package com.finbank.backend.loan.domain;

import com.finbank.backend.account.domain.Account;
import com.finbank.backend.member.domain.Member;
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
 * <p>심사 전이(approve/reject)는 도메인 메서드로 하고, 잘못된 전이는 {@link ErrorCode#INVALID_STATE}(409)로
 * 거부한다. 신청을 저장하는 같은 트랜잭션에서 곧바로 일어나 다른 요청과 겹칠 일이 없다.</p>
 *
 * <p>실행(APPROVED → EXECUTED)과 취소(→ CANCELED)는 도메인 메서드가 없다. 두 요청이 같은 신청을 동시에
 * 바꾸는 경쟁이 있어서, 읽고 검사하고 쓰는 메서드로는 둘 다 통과하거나 한쪽이 다른 쪽 결과를 덮어쓴다.
 * 조건과 변경을 한 문장으로 하는 조건부 UPDATE({@code LoanApplicationRepository}의
 * {@code markExecutedIfApproved}·{@code cancelIfCancelable})로만 바꾼다 — 상환 회차의 PAID 전이와 같은 원칙이다.</p>
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

    /**
     * 대출금을 입금받을 계좌 — 신청자가 지정한다.
     *
     * <p>V7 이전에 만들어진 신청은 값이 없다(NULL). 실행 시점에 NULL이면
     * 종전 동작대로 회원의 첫 활성 계좌로 폴백한다.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id")
    private Account linkedAccount;

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

    public LoanApplication(Member member, LoanProduct product, Account linkedAccount,
                           BigDecimal requestedAmount, int termMonths) {
        this.member = member;
        this.product = product;
        this.linkedAccount = linkedAccount;
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

    private void transition(ApplicationStatus from, ApplicationStatus to) {
        if (this.status != from) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "현재 상태(" + this.status + ")에서는 " + to + "(으)로 전이할 수 없습니다.");
        }
        this.status = to;
    }
}
