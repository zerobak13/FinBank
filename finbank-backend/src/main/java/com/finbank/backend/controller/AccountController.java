package com.finbank.backend.controller;

import com.finbank.backend.dto.*;
import com.finbank.backend.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import java.util.Map;

/**
 * 계좌 API 컨트롤러. 계좌 생성/조회와 입금·출금·이체 엔드포인트를 제공한다.
 * 모든 요청에 JWT 인증이 필요하다.
 */
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "계좌 (Account)", description = "계좌 생성, 조회, 입출금, 이체 API. 모든 요청에 JWT 인증이 필요합니다.")
@SecurityRequirement(name = "BearerAuth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "계좌 생성", description = "새 계좌를 개설합니다. 초기 입금액(initialDeposit)을 0 이상으로 설정할 수 있습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "계좌 생성 성공 (Location 헤더에 생성된 계좌 URI 포함)"),
            @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료")
    })
    @PostMapping
    public ResponseEntity<AccountSummaryResponse> createAccount(
            @Valid @RequestBody AccountCreateRequest request) {
        AccountSummaryResponse created = accountService.createAccount(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "내 계좌 목록 조회", description = "로그인한 사용자의 전체 계좌 목록을 반환합니다.")
    @ApiResponse(responseCode = "200", description = "계좌 목록 반환")
    @GetMapping
    public ResponseEntity<List<AccountSummaryResponse>> getMyAccounts() {
        return ResponseEntity.ok(accountService.getMyAccounts());
    }

    @Operation(summary = "계좌 상세 조회", description = "계좌 ID로 잔액, 계좌번호 등 상세 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계좌 정보 반환"),
            @ApiResponse(responseCode = "403", description = "본인 계좌가 아닌 경우"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 계좌")
    })
    @GetMapping("/{id}")
    public ResponseEntity<AccountSummaryResponse> getDetail(
            @Parameter(description = "계좌 ID", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountSummary(id));
    }

    @Operation(summary = "거래 내역 조회", description = "계좌의 거래 내역을 페이지 단위로 조회합니다. 최신순으로 정렬됩니다.")
    @ApiResponse(responseCode = "200", description = "거래 내역 페이지 반환")
    @GetMapping("/{id}/transactions")
    public ResponseEntity<PageResponse<TransactionLogResponse>> getTransactions(
            @Parameter(description = "계좌 ID", example = "1") @PathVariable Long id,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "20") @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(accountService.getAccountTransactions(id, page, size));
    }

    @Operation(summary = "입금", description = "지정한 계좌에 금액을 입금합니다. 본인 계좌만 가능합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "입금 완료"),
            @ApiResponse(responseCode = "400", description = "입금액이 0 이하인 경우"),
            @ApiResponse(responseCode = "403", description = "본인 계좌가 아닌 경우")
    })
    @PostMapping("/{id}/deposit")
    public ResponseEntity<Map<String, String>> deposit(
            @Parameter(description = "계좌 ID", example = "1") @PathVariable Long id,
            @Parameter(description = "입금 금액 (1 이상)", example = "10000") @RequestParam Long amount
    ) {
        accountService.deposit(id, amount);
        return ResponseEntity.ok(Map.of("message", "입금 완료"));
    }

    @Operation(summary = "출금", description = "지정한 계좌에서 금액을 출금합니다. 잔액 부족 시 오류가 발생합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "출금 완료"),
            @ApiResponse(responseCode = "400", description = "출금액이 0 이하이거나 잔액 부족"),
            @ApiResponse(responseCode = "403", description = "본인 계좌가 아닌 경우")
    })
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<Map<String, String>> withdraw(
            @Parameter(description = "계좌 ID", example = "1") @PathVariable Long id,
            @Parameter(description = "출금 금액 (1 이상)", example = "5000") @RequestParam Long amount
    ) {
        accountService.withdraw(id, amount);
        return ResponseEntity.ok(Map.of("message", "출금 완료"));
    }

    @Operation(
            summary = "계좌 이체",
            description = """
                    계좌 간 이체를 수행합니다.

                    **동시성 제어**: 데드락 방지를 위해 ID 오름차순으로 비관적 락(PESSIMISTIC_WRITE)을 획득합니다.

                    **검증 항목**
                    - 본인 계좌에서만 출금 가능
                    - 같은 계좌로의 이체 불가
                    - 잠금 계좌 이체 불가
                    - 잔액 부족 시 이체 불가
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이체 완료"),
            @ApiResponse(responseCode = "400", description = "잔액 부족 또는 잘못된 요청"),
            @ApiResponse(responseCode = "403", description = "본인 계좌가 아닌 경우"),
            @ApiResponse(responseCode = "404", description = "받는 계좌 없음")
    })
    @PostMapping("/transfer")
    public ResponseEntity<Map<String, String>> transfer(
            @Valid @RequestBody TransferRequest request) {
        accountService.transfer(request);
        return ResponseEntity.ok(Map.of("message", "이체 완료"));
    }
}
