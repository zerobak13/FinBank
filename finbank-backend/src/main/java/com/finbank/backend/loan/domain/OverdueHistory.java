package com.finbank.backend.loan.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 연체 이력 엔티티. 연체 감지 배치가 생성하고, 연체이자 가산 배치가 매일 penalty를 누적한다.
 *
 * <p>lastAccruedAt으로 "오늘 이미 가산했는지"를 판별해 배치 재실행 시
 * 이중 가산을 방지한다(배치 멱등).</p>
 */
@Entity
@Table(name = "overdue_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OverdueHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 대출 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    /** 연체된 회차 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private RepaymentSchedule schedule;

    /** 연체 시작일 */
    @Column(nullable = false)
    private LocalDate overdueStart;

    /** 연체 해소일 (NULL이면 진행 중) */
    private LocalDate resolvedAt;

    /** 연체 원리금 (연체이자 계산의 기준 금액) */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal overdueAmount;

    /** 가산된 연체이자 누계 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal penaltyAccrued = BigDecimal.ZERO;

    /** 마지막 가산일 — 배치 재실행 시 이중 가산 방지 */
    private LocalDate lastAccruedAt;

    /** 연체 개시 팩토리 (연체 감지 배치가 호출) */
    public static OverdueHistory open(RepaymentSchedule schedule, LocalDate overdueStart) {
        OverdueHistory h = new OverdueHistory();
        h.loanAccount = schedule.getLoanAccount();
        h.schedule = schedule;
        h.overdueStart = overdueStart;
        h.overdueAmount = schedule.getTotalAmount();
        return h;
    }

    /** 연체이자 일할 가산 (가산 배치가 호출) */
    public void accrue(BigDecimal penalty, LocalDate accruedAt) {
        this.penaltyAccrued = this.penaltyAccrued.add(penalty);
        this.lastAccruedAt = accruedAt;
    }

    /** 연체 해소 (연체 회차 상환 시) */
    public void resolve(LocalDate resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    /** 해당 일자에 이미 가산됐는지 (배치 멱등 판별) */
    public boolean alreadyAccruedOn(LocalDate date) {
        return this.lastAccruedAt != null && !this.lastAccruedAt.isBefore(date);
    }

    public boolean isResolved() {
        return this.resolvedAt != null;
    }
}
