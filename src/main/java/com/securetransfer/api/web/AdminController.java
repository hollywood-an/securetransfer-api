package com.securetransfer.api.web;

import com.securetransfer.api.security.AuthenticatedUser;
import com.securetransfer.api.service.AccountService;
import com.securetransfer.api.service.AuthService;
import com.securetransfer.api.web.dto.AccountResponse;
import com.securetransfer.api.web.dto.UserResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only endpoints. The class-level @PreAuthorize means EVERY method here
 * requires the ADMIN role — a CUSTOMER or TELLER calling these gets 403.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AuthService authService;
    private final AccountService accountService;

    public AdminController(AuthService authService, AccountService accountService) {
        this.authService = authService;
        this.accountService = accountService;
    }

    // GET /admin/users — list the login accounts in the admin's bank (ADMIN only).
    @GetMapping("/users")
    public List<UserResponse> listUsers(@AuthenticationPrincipal AuthenticatedUser admin) {
        return authService.listUsers(admin.getTenant());
    }

    // POST /admin/accounts/{id}/freeze — freeze an account (blocks its transfers).
    // The action is audit-logged with the admin as the actor.
    @PostMapping("/accounts/{id}/freeze")
    public AccountResponse freeze(@PathVariable Long id,
                                  @AuthenticationPrincipal AuthenticatedUser admin) {
        return accountService.setFrozen(id, true, admin.getUsername(), admin.getTenant());
    }

    // POST /admin/accounts/{id}/unfreeze — return an account to normal service.
    @PostMapping("/accounts/{id}/unfreeze")
    public AccountResponse unfreeze(@PathVariable Long id,
                                    @AuthenticationPrincipal AuthenticatedUser admin) {
        return accountService.setFrozen(id, false, admin.getUsername(), admin.getTenant());
    }
}
