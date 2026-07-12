package com.finbank.backend.loan.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 대출 상품 엔티티. 금리·한도·기간 등 상품 설정을 담는다.
 *
 * <p>중요: 상품의 금리는 "현재 판매 조건"일 뿐이다. 대출 실행 시점에
 * {@link LoanAccount}로 금리·상환방식이 복사(스냅샷)되므로,
 * 이후 상품 금리를 변경해도 기존 계약에는 영향이 없다.</p>
 */
@Entity
@Table(name = "loan_products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 상품명 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 상환 방식 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private RepaymentType repaymentType;

    /** 연이율 (예: 0.059 = 연 5.9%) */
    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal interestRate;

    /** 연체 가산율 */
    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal overdueExtraRate;

    /** 최소 신청 금액 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal minAmount;

    /** 최대 신청 금액 (한도) */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal maxAmount;

    /** 최대 대출 기간 (개월) */
    @Column(nullable = false)
    private int maxTermMonths;

    /** 판매 상태 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ON_SALE;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 판매 중 여부 */
    public boolean isOnSale() {
        return this.status == ProductStatus.ON_SALE;
    }

    /** 신청 금액이 상품의 min~max 범위 안인지 */
    public boolean isAmountInRange(BigDecimal amount) {
        return amount.compareTo(minAmount) >= 0 && amount.compareTo(maxAmount) <= 0;
    }

    /** 신청 기간이 상품의 최대 기간 이내인지 */
    public boolean isTermAllowed(int termMonths) {
        return termMonths >= 1 && termMonths <= maxTermMonths;
    }
}
