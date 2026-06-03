package com.securetransfer.api.web;

import com.securetransfer.api.service.AccountService;
import com.securetransfer.api.web.dto.AccountResponse;
import com.securetransfer.api.web.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web layer for accounts: create an account and read it back with its balance
 * and ledger history.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // POST /accounts — create an account. 201 Created on success.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(request);
    }

    // GET /accounts/{id} — return balance + ledger history. 200 OK, or 404 if
    // the account doesn't exist (handled by GlobalExceptionHandler).
    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable Long id) {
        return accountService.getById(id);
    }
}
