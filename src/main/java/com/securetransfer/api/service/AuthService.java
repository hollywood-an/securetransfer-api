package com.securetransfer.api.service;

import com.securetransfer.api.domain.Customer;
import com.securetransfer.api.domain.Role;
import com.securetransfer.api.domain.User;
import com.securetransfer.api.error.ConflictException;
import com.securetransfer.api.error.UnauthorizedException;
import com.securetransfer.api.repository.CustomerRepository;
import com.securetransfer.api.repository.UserRepository;
import com.securetransfer.api.security.JwtService;
import com.securetransfer.api.web.dto.AuthResponse;
import com.securetransfer.api.web.dto.LoginRequest;
import com.securetransfer.api.web.dto.RegisterRequest;
import com.securetransfer.api.web.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Authentication: registering customers and logging users in.
 */
@Service
public class AuthService {

    // One generic message for any registration conflict, so the endpoint can't be
    // used to enumerate which usernames / emails already exist.
    private static final String REGISTRATION_CONFLICT = "That username or email is not available";

    private final UserRepository users;
    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    // A throwaway BCrypt hash, computed once, used only to equalise login timing
    // for unknown usernames (see login()).
    private final String dummyPasswordHash;

    public AuthService(UserRepository users,
                       CustomerRepository customers,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.users = users;
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode("login-timing-equaliser");
    }

    /**
     * Self-registration: creates a CUSTOMER user and a linked customer profile.
     * The role is ALWAYS CUSTOMER here — nobody can self-register as staff.
     * The password is stored only as a BCrypt hash.
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        // Single generic message for both checks (and we never echo the value),
        // so registration can't reveal which usernames/emails are taken.
        if (users.existsByUsername(request.username())
                || customers.existsByEmail(request.email())) {
            throw new ConflictException(REGISTRATION_CONFLICT);
        }

        Customer customer = customers.save(new Customer(request.name(), request.email()));
        User user = users.save(new User(
                request.username(),
                passwordEncoder.encode(request.password()),
                Role.CUSTOMER,
                customer.getId()));

        return UserResponse.from(user);
    }

    /**
     * Verify the username + password and return a signed JWT. A wrong username
     * OR wrong password both give the same 401 (so we don't reveal which exists).
     *
     * We ALWAYS run a BCrypt comparison — against a dummy hash when the username
     * is unknown — so "no such user" costs the same as "wrong password". Without
     * this, the missing-user path would return noticeably faster, leaking which
     * usernames exist via a timing side channel.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = users.findByUsername(request.username()).orElse(null);

        boolean passwordMatches = (user != null)
                ? passwordEncoder.matches(request.password(), user.getPassword())
                : passwordEncoder.matches(request.password(), dummyPasswordHash);

        if (user == null || !passwordMatches) {
            throw new UnauthorizedException("Invalid username or password");
        }

        String token = jwtService.generate(user);
        return new AuthResponse(token, "Bearer", user.getUsername(), user.getRole(),
                jwtService.expirationMinutes());
    }

    /** All users — used by the ADMIN-only endpoint. */
    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return users.findAll().stream().map(UserResponse::from).toList();
    }
}
