package com.securetransfer.api.web;

import com.securetransfer.api.service.AuthService;
import com.securetransfer.api.web.dto.UserResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    public AdminController(AuthService authService) {
        this.authService = authService;
    }

    // GET /admin/users — list all login accounts (ADMIN only).
    @GetMapping("/users")
    public List<UserResponse> listUsers() {
        return authService.listUsers();
    }
}
