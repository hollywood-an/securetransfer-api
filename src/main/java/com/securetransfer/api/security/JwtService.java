package com.securetransfer.api.security;

import com.securetransfer.api.domain.Role;
import com.securetransfer.api.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Creates and verifies signed JWTs (JSON Web Tokens).
 *
 * A JWT is a tamper-proof token: we sign it with a secret key, so anyone can
 * read its claims but nobody can forge or alter one without the key. The token
 * carries the user's id, role, and (for customers) their customerId, so each
 * request is authenticated WITHOUT a database lookup.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:60}") long expirationMinutes) {
        // HS256 requires a key of at least 256 bits (32 bytes) — hence the
        // "secret must be >= 32 characters" rule.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    /** Issue a signed token for a freshly authenticated user. */
    public String generate(User user) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationMinutes * 60)));
        if (user.getCustomerId() != null) {
            builder.claim("customerId", user.getCustomerId());
        }
        return builder.signWith(key).compact();
    }

    /**
     * Verify a token's signature + expiry and build the principal from its claims.
     * Throws (a JwtException) if the token is invalid, tampered, or expired.
     */
    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = toLong(claims.get("userId"));
        String username = claims.getSubject();
        Role role = Role.valueOf((String) claims.get("role"));
        Long customerId = toLong(claims.get("customerId")); // null for staff

        return new AuthenticatedUser(userId, username, role, customerId);
    }

    public long expirationMinutes() {
        return expirationMinutes;
    }

    // JSON numbers may parse as Integer or Long; normalise to Long safely.
    private static Long toLong(Object claim) {
        return claim == null ? null : ((Number) claim).longValue();
    }
}
