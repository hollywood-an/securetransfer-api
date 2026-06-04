package com.securetransfer.api.web;

import com.securetransfer.api.service.AuthService;
import com.securetransfer.api.web.dto.AuthResponse;
import com.securetransfer.api.web.dto.LoginRequest;
import com.securetransfer.api.web.dto.RegisterRequest;
import com.securetransfer.api.web.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints. These are the only routes under /auth/**, and
 * the security config leaves them open (everything else needs a valid token).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // POST /auth/register — self-service signup (always creates a CUSTOMER).
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    // POST /auth/login — returns a signed JWT on success, 401 on bad credentials.
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
