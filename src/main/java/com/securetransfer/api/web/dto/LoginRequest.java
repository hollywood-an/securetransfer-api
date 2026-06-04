package com.securetransfer.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for POST /auth/login. */
public record LoginRequest(

        @NotBlank(message = "username is required")
        String username,

        @NotBlank(message = "password is required")
        String password
) {
}
