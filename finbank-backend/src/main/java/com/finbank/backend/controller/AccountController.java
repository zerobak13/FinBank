package com.finbank.backend.controller;

import com.finbank.backend.dto.*;
import com.finbank.backend.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
@CrossOrigin(origins = "http://localhost:5173")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountSummaryResponse> createAccount(
            @Valid @RequestBody AccountCreateRequest request) {
        return ResponseEntity.ok(accountService.createAccount(request));
    }

    @GetMapping
    public ResponseEntity<List<AccountSummaryResponse>> getMyAccounts() {
        return ResponseEntity.ok(accountService.getMyAccounts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountDetailResponse> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(accountService.getAccountDetail(id));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<Map<String, String>> deposit(
            @PathVariable Long id,
            @RequestParam Long amount
    ) {
        accountService.deposit(id, amount);
        return ResponseEntity.ok(Map.of("message", "입금 완료"));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<Map<String, String>> withdraw(
            @PathVariable Long id,
            @RequestParam Long amount
    ) {
        accountService.withdraw(id, amount);
        return ResponseEntity.ok(Map.of("message", "출금 완료"));
    }


    @PostMapping("/transfer")
    public ResponseEntity<Map<String, String>> transfer(
            @Valid @RequestBody TransferRequest request) {
        accountService.transfer(request);
        return ResponseEntity.ok(
                Map.of("message", "이체 완료")
        );
    }

}
