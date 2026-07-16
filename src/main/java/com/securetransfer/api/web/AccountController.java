package com.securetransfer.api.web;

import com.securetransfer.api.security.AuthenticatedUser;
import com.securetransfer.api.service.AccountService;
import com.securetransfer.api.service.AuditService;
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

import java.util.Map;

/**
 * Web layer for accounts. All endpoints require authentication (see
 * SecurityConfig); the rules below add per-role authorization on top.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AuditService auditService;

    public AccountController(AccountService accountService, AuditService auditService) {
        this.accountService = accountService;
        this.auditService = auditService;
    }

    // POST /accounts — opening an account is a staff action (TELLER/ADMIN).
    @PostMapping
    @PreAuthorize("hasAnyRole('TELLER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request,
                                  @AuthenticationPrincipal AuthenticatedUser currentUser) {
        return accountService.create(request, currentUser.getTenant());
    }

    // GET /accounts/{id} — a CUSTOMER may only view their own accounts; staff any
    // (within their own bank).
    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable Long id,
                                   @AuthenticationPrincipal AuthenticatedUser currentUser) {
        Long restrictToCustomerId = currentUser.isCustomer() ? currentUser.getCustomerId() : null;
        AccountResponse account = accountService.getById(id, restrictToCustomerId, currentUser.getTenant());

        // Sensitive action: a staff member (TELLER/ADMIN) viewing a customer's
        // account is "viewing another user's data" — audit it. A customer viewing
        // their OWN account is ordinary, so we don't log it.
        if (!currentUser.isCustomer()) {
            auditService.record(
                    currentUser.getUsername(),
                    "ACCOUNT_VIEWED",
                    "account:" + id,
                    Map.of("accountId", id, "ownerCustomerId", account.customerId()));
        }

        return account;
    }
}
