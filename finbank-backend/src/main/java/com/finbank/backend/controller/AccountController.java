package com.finbank.backend.controller;

import com.finbank.backend.dto.*;
import com.finbank.backend.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PostMapping("/transfer")
    public ResponseEntity<Void> transfer(@Valid @RequestBody TransferRequest request) {
        accountService.transfer(request);
        return ResponseEntity.ok().build();
    }
}
