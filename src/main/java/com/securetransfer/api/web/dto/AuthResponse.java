package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.Role;
import com.securetransfer.api.domain.Tenant;

/**
 * Response for a successful login: the signed JWT to send on later requests as
 * "Authorization: Bearer &lt;token&gt;".
 */
public record AuthResponse(
        String token,
        String tokenType,   // always "Bearer"
        String username,
        Role role,
        Tenant tenant,      // which bank the caller is in (STAFF vs DEMO)
        long expiresInMinutes
) {
}
