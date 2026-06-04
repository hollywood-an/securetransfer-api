package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.Role;
import com.securetransfer.api.domain.User;

/** Public view of a user (never includes the password hash). */
public record UserResponse(
        Long id,
        String username,
        Role role,
        Long customerId
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.getCustomerId());
    }
}
