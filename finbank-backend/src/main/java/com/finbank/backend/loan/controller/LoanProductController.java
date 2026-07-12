package com.finbank.backend.loan.controller;

import com.finbank.backend.loan.dto.LoanProductResponse;
import com.finbank.backend.loan.service.LoanApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 대출 상품 조회 API */
@Tag(name = "Loan Products", description = "대출 상품 조회")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/loans/products")
public class LoanProductController {

    private final LoanApplicationService loanApplicationService;

    public LoanProductController(LoanApplicationService loanApplicationService) {
        this.loanApplicationService = loanApplicationService;
    }

    @Operation(summary = "판매 중인 대출 상품 목록")
    @GetMapping
    public List<LoanProductResponse> getProducts() {
        return loanApplicationService.getProducts();
    }
}
