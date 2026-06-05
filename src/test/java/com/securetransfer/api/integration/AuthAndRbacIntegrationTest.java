package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Integration tests for authentication and role-based access control (Phase 4).
 *
 * <p>Every test drives the real HTTP stack with {@link #mockMvc} (the full Spring
 * Security filter chain: JwtAuthenticationFilter + the 401/403 JSON handlers)
 * against the Testcontainers Postgres, and authenticates with REAL JWTs minted by
 * the inherited helpers ({@link #adminToken()}, {@link #tellerToken()},
 * {@link #registerCustomer}, {@link #login}). No {@code @WithMockUser} is used —
 * we exercise the genuine token path end to end.
 *
 * <p>Each test asserts BOTH the HTTP response AND the resulting database state via
 * {@link #jdbc}, always scoped to the rows this test created (by id / username) —
 * never a global table count — so the tests stay independent and order-free.
 */
class AuthAndRbacIntegrationTest extends IntegrationTestBase {

    // ----- small local helpers (kept private to this file) -----

    /** Build a POST /transfers body. */
    private static Map<String, Object> transferBody(long fromAccount, long toAccount, BigDecimal amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccount", fromAccount);
        body.put("toAccount", toAccount);
        body.put("amount", amount);
        return body;
    }

    /** Build a POST /customers body. */
    private static Map<String, Object> customerBody(String name, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("email", email);
        return body;
    }

    /** Build a POST /accounts body. */
    private static Map<String, Object> accountBody(long customerId, String currency, BigDecimal initialBalance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("currency", currency);
        body.put("initialBalance", initialBalance);
        return body;
    }

    // ----- 1. unauthenticated access is rejected -----

    @Test
    @DisplayName("GET /accounts/{id} with NO token -> 401 (account still exists in DB)")
    void noTokenIsUnauthorized() throws Exception {
        // The account must exist so we know the 401 is about auth, not a missing id.
        long accountId = createAccount(adminToken(), registerCustomer(
                "nt_owner", "password123", "No Token Owner", "nt_owner@example.com").customerId(),
                "USD", new BigDecimal("100.00"));

        mockMvc.perform(get("/accounts/{id}", accountId))
                .andExpect(status().isUnauthorized())
                // Clean JSON body from RestAuthenticationEntryPoint, not Spring's default.
                .andExpect(jsonPath("$.status").value(401));

        // The account row genuinely exists; the request was blocked purely by auth.
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ?", accountId)).isEqualTo(1L);
    }

    // ----- 2. a garbage bearer token is rejected -----

    @Test
    @DisplayName("GET /accounts/{id} with a garbage Bearer token -> 401")
    void garbageTokenIsUnauthorized() throws Exception {
        long accountId = createAccount(adminToken(), registerCustomer(
                "gt_owner", "password123", "Garbage Token Owner", "gt_owner@example.com").customerId(),
                "USD", new BigDecimal("50.00"));

        mockMvc.perform(get("/accounts/{id}", accountId)
                        .header("Authorization", "Bearer not-a-real-jwt.totally.bogus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Still there: the invalid token never reached the (read-only) handler anyway.
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ?", accountId)).isEqualTo(1L);
    }

    // ----- 3. register creates a CUSTOMER and stores a BCrypt hash -----

    @Test
    @DisplayName("POST /auth/register -> 201 CUSTOMER; DB stores role CUSTOMER + BCrypt hash, not the plaintext")
    void registerCreatesCustomerWithBcryptHash() throws Exception {
        String username = "carol_register";
        String plaintext = "super-secret-pw";

        mockMvc.perform(post("/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", plaintext,
                                "name", "Carol Register",
                                "email", "carol_register@example.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                // The role can never be self-assigned: registration always yields CUSTOMER.
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.customerId").isNumber());

        // DB row: exactly one user, role CUSTOMER, linked to a customer profile.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT role, customer_id, password FROM users WHERE username = ?", username);
        assertThat(row.get("role")).isEqualTo("CUSTOMER");
        assertThat(row.get("customer_id")).isNotNull();

        // The password is stored ONLY as a BCrypt hash ($2 prefix), never the plaintext.
        String storedHash = (String) row.get("password");
        assertThat(storedHash).startsWith("$2");
        assertThat(storedHash).isNotEqualTo(plaintext);
    }

    // ----- 4. a CUSTOMER may view their OWN account but not another's -----

    @Test
    @DisplayName("GET /accounts/{id}: a CUSTOMER sees their own account (200) but not another's (403)")
    void customerCanOnlyViewOwnAccount() throws Exception {
        String adminToken = adminToken();

        TestCustomer alice = registerCustomer("alice_view", "password123", "Alice", "alice_view@example.com");
        TestCustomer bob = registerCustomer("bob_view", "password123", "Bob", "bob_view@example.com");

        long aliceAccount = createAccount(adminToken, alice.customerId(), "USD", new BigDecimal("200.00"));
        long bobAccount = createAccount(adminToken, bob.customerId(), "USD", new BigDecimal("300.00"));

        // Confirm ownership in the DB before testing the rule.
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ? AND customer_id = ?",
                aliceAccount, alice.customerId())).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ? AND customer_id = ?",
                bobAccount, bob.customerId())).isEqualTo(1L);

        // Alice viewing her OWN account succeeds.
        mockMvc.perform(get("/accounts/{id}", aliceAccount)
                        .header("Authorization", bearer(alice.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceAccount))
                .andExpect(jsonPath("$.customerId").value(alice.customerId()));

        // Alice viewing BOB's account is forbidden.
        mockMvc.perform(get("/accounts/{id}", bobAccount)
                        .header("Authorization", bearer(alice.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // ----- 5. a CUSTOMER cannot transfer FROM an account they don't own -----

    @Test
    @DisplayName("POST /transfers from another customer's account -> 403; no transfers row is created")
    void customerCannotTransferFromForeignAccount() throws Exception {
        String adminToken = adminToken();

        TestCustomer alice = registerCustomer("alice_xfer", "password123", "Alice", "alice_xfer@example.com");
        TestCustomer bob = registerCustomer("bob_xfer", "password123", "Bob", "bob_xfer@example.com");

        long aliceAccount = createAccount(adminToken, alice.customerId(), "USD", new BigDecimal("0.00"));
        long bobAccount = createAccount(adminToken, bob.customerId(), "USD", new BigDecimal("500.00"));

        // Alice tries to pull money OUT of Bob's account into her own. Forbidden.
        mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(alice.token()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(APPLICATION_JSON)
                        .content(json(transferBody(bobAccount, aliceAccount, new BigDecimal("10")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // The ownership check runs BEFORE the transfer: no row from Bob's account.
        assertThat(count("SELECT count(*) FROM transfers WHERE from_account = ?", bobAccount)).isEqualTo(0L);
        // And Bob's money never moved.
        assertThat(balanceOf(bobAccount)).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ----- 6. staff-only endpoints reject a CUSTOMER (403) -----

    @Test
    @DisplayName("A CUSTOMER calling staff/admin endpoints (POST /customers, POST /accounts, GET /admin/users) -> 403")
    void customerCannotReachStaffEndpoints() throws Exception {
        TestCustomer alice = registerCustomer("alice_rbac", "password123", "Alice", "alice_rbac@example.com");

        // POST /customers is TELLER/ADMIN only.
        mockMvc.perform(post("/customers")
                        .header("Authorization", bearer(alice.token()))
                        .contentType(APPLICATION_JSON)
                        .content(json(customerBody("Mallory", "mallory_rbac@example.com"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // POST /accounts is TELLER/ADMIN only.
        mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(alice.token()))
                        .contentType(APPLICATION_JSON)
                        .content(json(accountBody(alice.customerId(), "USD", new BigDecimal("100.00")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // GET /admin/users is ADMIN only.
        mockMvc.perform(get("/admin/users")
                        .header("Authorization", bearer(alice.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // None of the forbidden calls created anything: no customer/account for Mallory.
        assertThat(count("SELECT count(*) FROM customers WHERE email = ?", "mallory_rbac@example.com")).isEqualTo(0L);
        assertThat(count("SELECT count(*) FROM accounts WHERE customer_id = ?", alice.customerId())).isEqualTo(0L);
    }

    // ----- 7. admin reaches admin endpoints; teller is staff but not admin -----

    @Test
    @DisplayName("ADMIN can GET /admin/users (200, lists admin); TELLER can POST /accounts (201) but not GET /admin/users (403)")
    void adminAndTellerRolesAreEnforced() throws Exception {
        String adminToken = adminToken();
        String tellerToken = tellerToken();

        // ADMIN can list users, and the body is a JSON array containing the admin user.
        mockMvc.perform(get("/admin/users")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.username == '" + ADMIN_USERNAME + "')]").exists())
                .andExpect(jsonPath("$[?(@.username == '" + ADMIN_USERNAME + "' && @.role == 'ADMIN')]").exists());

        // The seeded admin really is in the DB with the ADMIN role (the array reflects this).
        assertThat(count("SELECT count(*) FROM users WHERE username = ? AND role = 'ADMIN'", ADMIN_USERNAME))
                .isEqualTo(1L);

        // TELLER (staff) CAN open an account.
        TestCustomer customer = registerCustomer("teller_target", "password123", "Target", "teller_target@example.com");
        String resp = mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(tellerToken))
                        .contentType(APPLICATION_JSON)
                        .content(json(accountBody(customer.customerId(), "USD", new BigDecimal("75.00")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long createdAccountId = Long.parseLong(readField(resp, "id"));

        // The account row really exists and is owned by the intended customer.
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ? AND customer_id = ?",
                createdAccountId, customer.customerId())).isEqualTo(1L);

        // But TELLER is NOT ADMIN: /admin/users is forbidden for it.
        mockMvc.perform(get("/admin/users")
                        .header("Authorization", bearer(tellerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // ----- 8. bad login credentials -> 401 (same outcome for wrong pw vs unknown user) -----

    @Test
    @DisplayName("POST /auth/login -> 401 for a real user with the wrong password AND for an unknown username")
    void badLoginCredentialsAreUnauthorized() throws Exception {
        // A genuine user exists...
        TestCustomer dave = registerCustomer("dave_login", "correct-password", "Dave", "dave_login@example.com");
        assertThat(count("SELECT count(*) FROM users WHERE username = ?", dave.username())).isEqualTo(1L);

        // ...but the wrong password is rejected with 401.
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("username", dave.username(), "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // An unknown username is ALSO rejected with 401 (no user enumeration).
        String unknownUser = "nobody_login";
        assertThat(count("SELECT count(*) FROM users WHERE username = ?", unknownUser)).isEqualTo(0L);
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("username", unknownUser, "password", "any-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
