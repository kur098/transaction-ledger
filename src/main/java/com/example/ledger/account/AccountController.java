package com.example.ledger.account;

import java.net.URI;
import java.util.UUID;

import com.example.ledger.account.dto.AccountResponse;
import com.example.ledger.account.dto.CreateAccountRequest;
import com.example.ledger.account.dto.UpdateAccountStatusRequest;
import com.example.ledger.common.PageRequestParams;
import com.example.ledger.common.PageResponse;
import com.example.ledger.ledger.LedgerService;
import com.example.ledger.ledger.dto.LedgerEntryResponse;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    public AccountController(AccountService accountService, LedgerService ledgerService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.from(accountService.get(accountId));
    }

    @PatchMapping("/{accountId}/status")
    public AccountResponse changeStatus(@PathVariable UUID accountId,
                                        @Valid @RequestBody UpdateAccountStatusRequest request) {
        return AccountResponse.from(accountService.changeStatus(accountId, request.status()));
    }

    @GetMapping("/{accountId}/ledger")
    public PageResponse<LedgerEntryResponse> ledger(@PathVariable UUID accountId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return ledgerService.accountLedger(accountId, PageRequestParams.of(page, size));
    }
}
