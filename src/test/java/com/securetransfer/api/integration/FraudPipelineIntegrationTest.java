package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * End-to-end tests for the fraud-triage pipeline: flagging at transfer time, the
 * async (after-commit) agent review, the read-only review queue, and the
 * human-in-the-loop decision endpoint.
 *
 * <p>The fraud agent is a deterministic STUB in these tests (see
 * {@link IntegrationTestBase}) — it never calls the network and always returns
 * the rules-based fallback verdict (agent_model = 'rules-fallback'). The
 * test-only thresholds make any transfer of >= 10000 flag LARGE_AMOUNT and any
 * brand-new payee pair flag NEW_PAYEE; flagging never blocks, so a flagged
 * transfer still moves the money (status FLAGGED, not COMPLETED).
 *
 * <p>Every test asserts BOTH the HTTP response AND the resulting database state,
 * scoped by transfer_id / review id / action — never global counts.
 */
class FraudPipelineIntegrationTest extends IntegrationTestBase {

    // ------------------------------------------------------------------
    // helpers (local to this test file)
    // ------------------------------------------------------------------

    /** Build the JSON body for POST /transfers. */
    private String transferBody(long fromAccount, long toAccount, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccount", fromAccount);
        body.put("toAccount", toAccount);
        body.put("amount", new BigDecimal(amount));
        return json(body);
    }

    /** A fresh, unique idempotency key for every transfer. */
    private String newKey() {
        return UUID.randomUUID().toString();
    }

    /** POST a transfer as the given staff/customer token, assert 201, return the new transfer id. */
    private long postTransfer(String token, long fromAccount, long toAccount, String amount) throws Exception {
        String responseJson = mockMvc.perform(post("/transfers")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", newKey())
                        .contentType(APPLICATION_JSON)
                        .content(transferBody(fromAccount, toAccount, amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(readField(responseJson, "id"));
    }

    /** Wait until the async agent has finished the review for this transfer. */
    private void awaitAgentCompleted(long transferId) {
        awaitUntil(Duration.ofSeconds(10), () -> count(
                "SELECT count(*) FROM fraud_reviews WHERE transfer_id = ? AND status = 'AGENT_COMPLETED'",
                transferId) == 1);
    }

    // ------------------------------------------------------------------
    // 1. a large transfer flags but still completes, and the agent reviews it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A large transfer (>= 10000) flags LARGE_AMOUNT but still moves the money, and the agent completes the review")
    void largeTransferFlagsButStillCompletesAndIsReviewed() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("frlarge", "pw-frlarge-123",
                "Large Example", "large@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("100000.00"));
        long accountB = createAccount(admin, customer.customerId(), "USD", new BigDecimal("0.00"));

        // --- HTTP: 201, money moves ---
        long transferId = postTransfer(admin, accountA, accountB, "25000.00");

        // --- DB: balances moved exactly ---
        assertThat(balanceOf(accountA)).isEqualByComparingTo("75000.0000");
        assertThat(balanceOf(accountB)).isEqualByComparingTo("25000.0000");

        // --- DB: exactly two ledger legs (DEBIT on A, CREDIT on B) for this transfer ---
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", transferId))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ledger_entries "
                        + "WHERE transfer_id = ? AND account_id = ? AND direction = 'DEBIT' AND amount = 25000.0000",
                transferId, accountA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_entries "
                        + "WHERE transfer_id = ? AND account_id = ? AND direction = 'CREDIT' AND amount = 25000.0000",
                transferId, accountB)).isEqualTo(1);

        // --- DB: the transfer is FLAGGED, not COMPLETED (flagging never blocks money) ---
        String transferStatus = jdbc.queryForObject(
                "SELECT status FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(transferStatus).isEqualTo("FLAGGED");

        // --- async: wait for the agent to finish, then assert the review row ---
        awaitAgentCompleted(transferId);

        Map<String, Object> review = jdbc.queryForMap(
                "SELECT id, status, risk_score, recommended_action, agent_model "
                        + "FROM fraud_reviews WHERE transfer_id = ?", transferId);
        assertThat(review.get("status")).isEqualTo("AGENT_COMPLETED");
        assertThat(review.get("risk_score")).as("risk_score must be set by the verdict").isNotNull();
        assertThat(review.get("recommended_action")).as("recommended_action must be set").isNotNull();
        assertThat(review.get("agent_model")).isEqualTo("rules-fallback");

        long reviewId = ((Number) review.get("id")).longValue();
        String flagReasons = jdbc.queryForObject(
                "SELECT flag_reasons::text FROM fraud_reviews WHERE id = ?", String.class, reviewId);
        assertThat(flagReasons).contains("LARGE_AMOUNT");

        // --- DB: the agent's completion was audit-logged for this transfer ---
        assertThat(count("SELECT count(*) FROM audit_log "
                        + "WHERE action = 'FRAUD_REVIEW_COMPLETED' AND target = ? AND actor = 'agent:fraud-triage'",
                "transfer:" + transferId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2. a normal small repeat transfer is NOT flagged
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A small repeat transfer to a known payee completes without flagging and creates no fraud review")
    void smallRepeatTransferIsNotFlagged() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("frsmall", "pw-frsmall-123",
                "Small Example", "small@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("1000.00"));
        long accountB = createAccount(admin, customer.customerId(), "USD", new BigDecimal("0.00"));

        // First send to a brand-new payee pair: this flags NEW_PAYEE (still completes).
        // We don't assert on it — just record its id so we can drain its async review.
        long firstTransferId = postTransfer(admin, accountA, accountB, "100.00");
        awaitAgentCompleted(firstTransferId);

        // Second send to the SAME payee for < 10000: NOT a new payee, under the
        // large-amount threshold, velocity disabled -> no flags -> COMPLETED.
        long transferId2 = postTransfer(admin, accountA, accountB, "100.00");

        // --- DB: balances reflect both transfers (200 total moved) ---
        assertThat(balanceOf(accountA)).isEqualByComparingTo("800.0000");
        assertThat(balanceOf(accountB)).isEqualByComparingTo("200.0000");

        // --- DB: the repeat transfer COMPLETED (not flagged) ---
        String status2 = jdbc.queryForObject(
                "SELECT status FROM transfers WHERE id = ?", String.class, transferId2);
        assertThat(status2).isEqualTo("COMPLETED");

        // --- DB: no fraud review was created for the repeat transfer ---
        assertThat(count("SELECT count(*) FROM fraud_reviews WHERE transfer_id = ?", transferId2))
                .isZero();
    }

    // ------------------------------------------------------------------
    // 3. human decision recorded, then re-deciding the same review -> 409
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An admin records a HOLD decision on a flagged review (200), and re-deciding the same review returns 409")
    void humanDecisionRecordedAndReDecideReturns409() throws Exception {
        String admin = adminToken();
        TestCustomer customer = registerCustomer("frdecide", "pw-frdecide-123",
                "Decide Example", "decide@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("100000.00"));
        long accountB = createAccount(admin, customer.customerId(), "USD", new BigDecimal("0.00"));

        // Large transfer -> flagged -> async review completes.
        long transferId = postTransfer(admin, accountA, accountB, "25000.00");
        awaitAgentCompleted(transferId);

        Long reviewId = jdbc.queryForObject(
                "SELECT id FROM fraud_reviews WHERE transfer_id = ?", Long.class, transferId);

        // --- HTTP: record the human decision (HOLD) as admin -> 200 ---
        String decisionBody = json(Map.of("decision", "HOLD"));
        String decisionJson = mockMvc.perform(post("/fraud-reviews/" + reviewId + "/decision")
                        .header("Authorization", bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content(decisionBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(readField(decisionJson, "status")).isEqualTo("DECIDED");
        assertThat(readField(decisionJson, "humanDecision")).isEqualTo("HOLD");
        assertThat(readField(decisionJson, "decidedByUser")).isEqualTo("admin");

        // --- DB: the review is DECIDED with the human's call recorded ---
        Map<String, Object> review = jdbc.queryForMap(
                "SELECT status, human_decision, decided_by_user, decided_at "
                        + "FROM fraud_reviews WHERE id = ?", reviewId);
        assertThat(review.get("status")).isEqualTo("DECIDED");
        assertThat(review.get("human_decision")).isEqualTo("HOLD");
        assertThat(review.get("decided_by_user")).isEqualTo("admin");
        assertThat(review.get("decided_at")).as("decided_at must be stamped").isNotNull();

        // --- DB: the decision was audit-logged for this transfer ---
        assertThat(count("SELECT count(*) FROM audit_log "
                        + "WHERE action = 'FRAUD_DECISION_RECORDED' AND target = ?",
                "transfer:" + transferId)).isEqualTo(1);

        // --- HTTP: re-deciding the SAME review -> 409 Conflict ---
        mockMvc.perform(post("/fraud-reviews/" + reviewId + "/decision")
                        .header("Authorization", bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content(decisionBody))
                .andExpect(status().isConflict());

        // --- DB: the review is unchanged — still DECIDED with the same HOLD decision ---
        Map<String, Object> afterRetry = jdbc.queryForMap(
                "SELECT status, human_decision FROM fraud_reviews WHERE id = ?", reviewId);
        assertThat(afterRetry.get("status")).isEqualTo("DECIDED");
        assertThat(afterRetry.get("human_decision")).isEqualTo("HOLD");
    }

    // ------------------------------------------------------------------
    // 4. the review queue is TELLER/ADMIN-only
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /fraud-reviews is forbidden to a customer (403) but allowed to teller and admin (200), and lists the flagged review")
    void reviewQueueIsTellerOrAdminOnly() throws Exception {
        String admin = adminToken();
        String teller = tellerToken();
        TestCustomer customer = registerCustomer("frqueue", "pw-frqueue-123",
                "Queue Example", "queue@example.com");
        long accountA = createAccount(admin, customer.customerId(), "USD", new BigDecimal("100000.00"));
        long accountB = createAccount(admin, customer.customerId(), "USD", new BigDecimal("0.00"));

        // Large transfer -> flagged -> async review completes.
        long transferId = postTransfer(admin, accountA, accountB, "25000.00");
        awaitAgentCompleted(transferId);

        // --- HTTP: a CUSTOMER may not read the review queue -> 403 ---
        mockMvc.perform(get("/fraud-reviews")
                        .header("Authorization", bearer(customer.token())))
                .andExpect(status().isForbidden());

        // --- HTTP: a TELLER may, and the queue contains this transfer's review ---
        String tellerJson = mockMvc.perform(get("/fraud-reviews")
                        .header("Authorization", bearer(teller)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(contentContainsTransfer(tellerJson, transferId))
                .as("teller queue contains the flagged transfer's review")
                .isTrue();

        // --- HTTP: an ADMIN may too -> 200 ---
        mockMvc.perform(get("/fraud-reviews")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        // --- DB: there is exactly one review for this transfer, in AGENT_COMPLETED ---
        assertThat(count("SELECT count(*) FROM fraud_reviews "
                        + "WHERE transfer_id = ? AND status = 'AGENT_COMPLETED'", transferId))
                .isEqualTo(1);
    }

    /** True if the paged /fraud-reviews body has a content row with this transferId. */
    private boolean contentContainsTransfer(String pagedJson, long transferId) throws Exception {
        JsonNode content = objectMapper.readTree(pagedJson).get("content");
        if (content == null || !content.isArray()) {
            return false;
        }
        for (JsonNode row : content) {
            JsonNode tid = row.get("transferId");
            if (tid != null && tid.asLong() == transferId) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 5. fetching an unknown review -> 404
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /fraud-reviews/{id} for an unknown id returns 404")
    void unknownReviewReturns404() throws Exception {
        String admin = adminToken();
        long unknownReviewId = 999999L;

        mockMvc.perform(get("/fraud-reviews/" + unknownReviewId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());

        // --- DB: sanity — no such review row exists ---
        assertThat(count("SELECT count(*) FROM fraud_reviews WHERE id = ?", unknownReviewId))
                .isZero();
    }
}
