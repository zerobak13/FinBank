package com.finbank.backend.loan.controller;

import com.finbank.backend.loan.dto.LoanApplicationResponse;
import com.finbank.backend.loan.dto.LoanApplyRequest;
import com.finbank.backend.loan.service.LoanApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/** 대출 신청 API — 신청 즉시 자동심사가 수행되어 응답에 승인/탈락이 담긴다 */
@Tag(name = "Loan Applications", description = "대출 신청/자동심사")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/loans/applications")
public class LoanApplicationController {

    private final LoanApplicationService loanApplicationService;

    public LoanApplicationController(LoanApplicationService loanApplicationService) {
        this.loanApplicationService = loanApplicationService;
    }

    @Operation(summary = "대출 신청 (즉시 자동심사)",
            description = "활성 계좌 보유 · 한도(기존 대출 잔액 합산) · 연체 이력 3개 룰로 즉시 심사합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "신청 접수 (body의 status로 승인/탈락 확인)"),
            @ApiResponse(responseCode = "404", description = "판매 중인 상품이 아님"),
            @ApiResponse(responseCode = "422", description = "금액/기간이 상품 허용 범위를 벗어남")
    })
    @PostMapping
    public ResponseEntity<LoanApplicationResponse> apply(@Valid @RequestBody LoanApplyRequest request) {
        LoanApplicationResponse response = loanApplicationService.apply(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "내 대출 신청 이력 (최신순)")
    @GetMapping
    public List<LoanApplicationResponse> getMyApplications() {
        return loanApplicationService.getMyApplications();
    }

    @Operation(summary = "대출 신청 단건 조회 (본인 것만)")
    @GetMapping("/{id}")
    public LoanApplicationResponse getApplication(@PathVariable Long id) {
        return loanApplicationService.getApplication(id);
    }

    @Operation(summary = "대출 신청 취소 (실행 전만 가능)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "취소 완료"),
            @ApiResponse(responseCode = "409", description = "이미 실행/거절된 신청")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        loanApplicationService.cancel(id);
        return ResponseEntity.noContent().build();
    }
}
