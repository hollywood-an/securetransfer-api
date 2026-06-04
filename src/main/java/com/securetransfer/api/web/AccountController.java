package com.securetransfer.api.web;

import com.securetransfer.api.security.AuthenticatedUser;
import com.securetransfer.api.service.AccountService;
import com.securetransfer.api.web.dto.AccountResponse;
import com.securetransfer.api.web.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web layer for accounts. All endpoints require authentication (see
 * SecurityConfig); the rules below add per-role authorization on top.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // POST /accounts — opening an account is a staff action (TELLER/ADMIN).
    @PostMapping
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(request);
    }

    // GET /accounts/{id} — a CUSTOMER may only view their own accounts; staff any.
    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable Long id,
                                   @AuthenticationPrincipal AuthenticatedUser currentUser) {
        Long restrictToCustomerId = currentUser.isCustomer() ? currentUser.getCustomerId() : null;
        return accountService.getById(id, restrictToCustomerId);
    }
}
