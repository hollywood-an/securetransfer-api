package com.securetransfer.api.security;

import com.securetransfer.api.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal placed in Spring Security's context for each
 * request. Built from the verified JWT's claims (no database lookup needed).
 *
 * Implements Spring Security's UserDetails so role checks (hasRole(...)) work,
 * and exposes our own id / role / customerId for the ownership checks.
 */
public class AuthenticatedUser implements UserDetails {

    private final Long id;
    private final String username;
    private final Role role;
    private final Long customerId; // null for staff (TELLER/ADMIN)

    public AuthenticatedUser(Long id, String username, Role role, Long customerId) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.customerId = customerId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Spring Security's hasRole('ADMIN') looks for the authority "ROLE_ADMIN".
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return null; // not used: authentication already happened via the JWT
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }
}
