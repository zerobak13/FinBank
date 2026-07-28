package com.finbank.backend.loan.controller;

import com.finbank.backend.loan.dto.LoanDetailResponse;
import com.finbank.backend.loan.dto.LoanExecutionResponse;
import com.finbank.backend.loan.dto.LoanSummaryResponse;
import com.finbank.backend.loan.dto.RepaymentRequest;
import com.finbank.backend.loan.dto.RepaymentResponse;
import com.finbank.backend.loan.dto.RepaymentScheduleResponse;
import com.finbank.backend.loan.service.LoanExecutionService;
import com.finbank.backend.loan.service.LoanQueryService;
import com.finbank.backend.loan.service.LoanRepaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 대출 실행·상환 API.
 *
 * <p>돈이 움직이는 모든 요청은 <b>Idempotency-Key 헤더가 필수</b>다.
 * 클라이언트는 요청마다 UUID를 생성해 보내고, 재시도할 때는 <b>같은 키</b>를 보낸다.
 * 서버는 같은 키의 두 번째 요청에 저장해둔 응답을 그대로 돌려주므로 돈은 한 번만 움직인다.</p>
 */
@Tag(name = "Loans", description = "대출 실행/상환/조회")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final LoanExecutionService loanExecutionService;
    private final LoanRepaymentService loanRepaymentService;
    private final LoanQueryService loanQueryService;

    public LoanController(LoanExecutionService loanExecutionService,
                          LoanRepaymentService loanRepaymentService,
                          LoanQueryService loanQueryService) {
        this.loanExecutionService = loanExecutionService;
        this.loanRepaymentService = loanRepaymentService;
        this.loanQueryService = loanQueryService;
    }

    @Operation(summary = "대출 실행 (승인된 신청 → 원장 생성 + 스케줄 확정 + 연결계좌 입금)",
            description = "같은 Idempotency-Key로 재요청하면 저장된 응답이 그대로 반환되고 돈은 다시 움직이지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "실행 완료"),
            @ApiResponse(responseCode = "409", description = "이미 실행된 신청 / 동일 키 요청 처리 중"),
            @ApiResponse(responseCode = "422", description = "같은 멱등키로 다른 내용의 요청")
    })
    @PostMapping("/applications/{applicationId}/execute")
    public ResponseEntity<LoanExecutionResponse> execute(
            @PathVariable Long applicationId,
            @Parameter(description = "요청 고유 키(UUID). 재시도 시 같은 값을 보냅니다.", required = true)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {

        LoanExecutionResponse response = loanExecutionService.execute(applicationId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "회차 상환 (가장 빠른 미납 회차)",
            description = "회차는 순서대로만 상환됩니다. body의 installmentNo는 확인용(선택)입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상환 완료"),
            @ApiResponse(responseCode = "400", description = "계좌 잔액 부족"),
            @ApiResponse(responseCode = "409", description = "이미 상환된 회차 / 순서 위반 / 동일 키 요청 처리 중")
    })
    @PostMapping("/{loanId}/repayments")
    public RepaymentResponse repay(@PathVariable Long loanId,
                                   @RequestBody(required = false) RepaymentRequest request,
                                   @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        Integer installmentNo = (request == null) ? null : request.installmentNo();
        return loanRepaymentService.repay(loanId, installmentNo, idempotencyKey);
    }

    @Operation(summary = "전액 중도상환 (잔여원금 + 경과이자 + 중도상환수수료)",
            description = "남은 회차는 삭제하지 않고 CANCELED로 보존합니다. 정상(ACTIVE) 대출만 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "중도상환 완료"),
            @ApiResponse(responseCode = "400", description = "계좌 잔액 부족"),
            @ApiResponse(responseCode = "409", description = "완납/연체 상태 / 동일 키 요청 처리 중")
    })
    @PostMapping("/{loanId}/prepayment")
    public RepaymentResponse prepay(@PathVariable Long loanId,
                                    @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        return loanRepaymentService.prepayAll(loanId, idempotencyKey);
    }

    @Operation(summary = "내 대출 목록")
    @GetMapping
    public List<LoanSummaryResponse> getMyLoans() {
        return loanQueryService.getMyLoans();
    }

    @Operation(summary = "대출 상세 (계약 조건 + 전 회차 스케줄)")
    @GetMapping("/{loanId}")
    public LoanDetailResponse getLoan(@PathVariable Long loanId) {
        return loanQueryService.getLoan(loanId);
    }

    @Operation(summary = "대출 상환 스케줄")
    @GetMapping("/{loanId}/schedules")
    public List<RepaymentScheduleResponse> getSchedules(@PathVariable Long loanId) {
        return loanQueryService.getSchedules(loanId);
    }
}
