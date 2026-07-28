package com.finbank.backend.loan.repository;

import com.finbank.backend.loan.domain.LoanProduct;
import com.finbank.backend.loan.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanProductRepository extends JpaRepository<LoanProduct, Long> {

    /** 판매 중인 상품 목록 */
    List<LoanProduct> findByStatus(ProductStatus status);
}
