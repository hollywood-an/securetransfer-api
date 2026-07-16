package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.securetransfer.api.domain.Role;
import com.securetransfer.api.domain.Tenant;
import com.securetransfer.api.domain.User;
import com.securetransfer.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proves the tenant boundary end-to-end: the public {@code demo} login is a
 * self-contained "bank" (tenant {@code DEMO}) that can never see or touch the
 * {@code STAFF} bank shared by {@code admin}/{@code teller}, and vice versa.
 *
 * <p>Everything runs against a real PostgreSQL (via {@link IntegrationTestBase})
 * through the full security filter chain, so the tenant is derived from the login
 * token exactly as in production. The base seeds the STAFF staff; this test adds a
 * demo TELLER in the DEMO bank and then drives both worlds over HTTP.
 */
class TenantIsolationTest extends IntegrationTestBase {

    private static final String DEMO_USERNAME = "demo";
    private static final String DEMO_PASSWORD = "demo-integration-pw";

    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    /**
     * Seed a demo TELLER in the DEMO bank. Runs AFTER the base's @BeforeEach
     * (which truncates every table and re-seeds the STAFF admin/teller), so we
     * always start from: STAFF = {admin, teller}, DEMO = {demo}, no other data.
     */
    @BeforeEach
    void seedDemoLogin() {
        users.save(new User(DEMO_USERNAME, passwordEncoder.encode(DEMO_PASSWORD),
                Role.TELLER, null, Tenant.DEMO));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void demoCannotSeeStaffAccounts_andStaffCannotSeeDemoAccounts() throws Exception {
        String admin = adminToken();
        String demo = demoToken();

        long staffAccount = anAccount(admin, "staff-owner@example.com");
        long demoAccount = anAccount(demo, "demo-owner@example.com");

        // A caller sees their OWN bank's account…
        mockMvc.perform(get("/accounts/" + staffAccount).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/accounts/" + demoAccount).header("Authorization", bearer(demo)))
                .andExpect(status().isOk());

        // …but the other bank's account is INVISIBLE (404, not 403 — so neither
        // bank can even probe which ids exist in the other).
        mockMvc.perform(get("/accounts/" + staffAccount).header("Authorization", bearer(demo)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/accounts/" + demoAccount).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void fraudReviewQueueIsScopedPerTenant() throws Exception {
        String admin = adminToken();
        String demo = demoToken();

        long staffTransfer = flagTransfer(admin, "staff-flag@example.com")[0];
        long demoTransfer = flagTransfer(demo, "demo-flag@example.com")[0];

        // Each bank's queue contains ONLY its own review.
        assertThat(reviewTransferIds(fraudReviews(demo))).containsExactly(demoTransfer);
        assertThat(reviewTransferIds(fraudReviews(admin))).containsExactly(staffTransfer);
    }

    @Test
    void crossTenantTransferReturns404AndMovesNoMoney() throws Exception {
        String admin = adminToken();
        String demo = demoToken();

        long demoCustomer = createCustomer(demo, "Demo", "demo-xfer@example.com");
        long demoA = createAccount(demo, demoCustomer, "USD", new BigDecimal("100000.00"));
        long demoB = createAccount(demo, demoCustomer, "USD", new BigDecimal("0.00"));

        long staffCustomer = createCustomer(admin, "Staff", "staff-xfer@example.com");
        long staffAccount = createAccount(admin, staffCustomer, "USD", new BigDecimal("100000.00"));

        // Demo tries to move money INTO a staff account → the staff id is invisible
        // (404), and the check runs before any debit, so nothing moves.
        doTransfer(demo, demoA, staffAccount, "500.00").andExpect(status().isNotFound());
        // Staff tries to pull FROM a demo account → same.
        doTransfer(admin, staffAccount, demoA, "500.00").andExpect(status().isNotFound());

        assertThat(balanceOf(demoA)).isEqualByComparingTo("100000.0000");
        assertThat(balanceOf(staffAccount)).isEqualByComparingTo("100000.0000");

        // Sanity: a transfer WITHIN the demo bank works normally.
        doTransfer(demo, demoA, demoB, "500.00").andExpect(status().isCreated());
        assertThat(balanceOf(demoA)).isEqualByComparingTo("99500.0000");
        assertThat(balanceOf(demoB)).isEqualByComparingTo("500.0000");
    }

    @Test
    void demoDecidesItsOwnReviewButNotAStaffReview() throws Exception {
        String admin = adminToken();
        String demo = demoToken();

        long demoReview = flagTransfer(demo, "demo-decide@example.com")[1];
        long staffReview = flagTransfer(admin, "staff-decide@example.com")[1];

        // Demo CAN record a decision on its OWN review (the demo now runs the full
        // human-in-the-loop step in its sandbox).
        decide(demo, demoReview, "APPROVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.humanDecision").value("APPROVE"))
                .andExpect(jsonPath("$.decidedByUser").value(DEMO_USERNAME));

        // But a staff review is invisible to demo (404), and a demo review is
        // invisible to staff (404) — neither can decide the other's.
        decide(demo, staffReview, "ESCALATE").andExpect(status().isNotFound());
        decide(admin, demoReview, "ESCALATE").andExpect(status().isNotFound());
    }

    @Test
    void adminUserListIsScopedToTheStaffBank() throws Exception {
        // The STAFF admin sees only STAFF logins — never the demo user.
        List<String> names = usernames(mockMvc.perform(
                        get("/admin/users").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(names).containsExactlyInAnyOrder("admin", "teller");
        assertThat(names).doesNotContain(DEMO_USERNAME);
    }

    @Test
    void sameEmailAllowedInBothBanks_andRowsAreStampedWithTheCallersTenant() throws Exception {
        String admin = adminToken();
        String demo = demoToken();
        String sharedEmail = "shared@example.com";

        // The SAME email is accepted in each bank (email is unique per-tenant now),
        // proving the two banks are independent.
        long staffCustomer = createCustomer(admin, "Shared Staff", sharedEmail);
        long demoCustomer = createCustomer(demo, "Shared Demo", sharedEmail);

        long staffAccount = createAccount(admin, staffCustomer, "USD", new BigDecimal("10.00"));
        long demoAccount = createAccount(demo, demoCustomer, "USD", new BigDecimal("10.00"));

        // New rows are stamped with the CALLER's tenant.
        assertThat(count("SELECT count(*) FROM customers WHERE id = ? AND tenant = 'STAFF'", staffCustomer)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM customers WHERE id = ? AND tenant = 'DEMO'", demoCustomer)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ? AND tenant = 'STAFF'", staffAccount)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ? AND tenant = 'DEMO'", demoAccount)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private String demoToken() throws Exception {
        return login(DEMO_USERNAME, DEMO_PASSWORD);
    }

    /** Create a customer (as staff/demo) and return its id. */
    private long createCustomer(String token, String name, String email) throws Exception {
        String resp = mockMvc.perform(post("/customers")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(Map.of("name", name, "email", email))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(readField(resp, "id"));
    }

    /** A one-liner: a customer + one funded USD account in the given bank. */
    private long anAccount(String token, String email) throws Exception {
        long customerId = createCustomer(token, "Owner", email);
        return createAccount(token, customerId, "USD", new BigDecimal("1000.00"));
    }

    private ResultActions doTransfer(String token, long from, long to, String amount) throws Exception {
        Map<String, Object> body = Map.of("fromAccount", from, "toAccount", to, "amount", new BigDecimal(amount));
        return mockMvc.perform(post("/transfers")
                .header("Authorization", bearer(token))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions decide(String token, long reviewId, String decision) throws Exception {
        return mockMvc.perform(post("/fraud-reviews/" + reviewId + "/decision")
                .header("Authorization", bearer(token))
                .contentType(APPLICATION_JSON)
                .content(json(Map.of("decision", decision))));
    }

    private String fraudReviews(String token) throws Exception {
        return mockMvc.perform(get("/fraud-reviews").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Make a large transfer to a brand-new payee (flags LARGE_AMOUNT + NEW_PAYEE)
     * in the caller's bank. Returns {@code [transferId, reviewId]}. The review row
     * is created synchronously inside the transfer transaction.
     */
    private long[] flagTransfer(String token, String email) throws Exception {
        long customerId = createCustomer(token, "Flagger", email);
        long a = createAccount(token, customerId, "USD", new BigDecimal("100000.00"));
        long b = createAccount(token, customerId, "USD", new BigDecimal("0.00"));
        String resp = doTransfer(token, a, b, "15000.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FLAGGED"))
                .andReturn().getResponse().getContentAsString();
        long transferId = Long.parseLong(readField(resp, "id"));
        long reviewId = jdbc.queryForObject(
                "SELECT id FROM fraud_reviews WHERE transfer_id = ?", Long.class, transferId);
        return new long[]{transferId, reviewId};
    }

    private List<Long> reviewTransferIds(String pagedJson) throws Exception {
        List<Long> ids = new ArrayList<>();
        JsonNode content = objectMapper.readTree(pagedJson).get("content");
        content.forEach(n -> ids.add(n.get("transferId").asLong()));
        return ids;
    }

    private List<String> usernames(String arrayJson) throws Exception {
        List<String> names = new ArrayList<>();
        objectMapper.readTree(arrayJson).forEach(n -> names.add(n.get("username").asText()));
        return names;
    }
}
