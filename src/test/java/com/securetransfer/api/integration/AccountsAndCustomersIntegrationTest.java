package com.securetransfer.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integration tests for the customer- and account-creation web layer.
 *
 * <p>Every test drives the real HTTP stack with {@link #mockMvc} (full Spring
 * Security filter chain) against the Testcontainers Postgres, then asserts BOTH
 * the HTTP response AND the resulting row(s) in the database via {@link #jdbc}.
 * Database assertions are always scoped to entities this test created (by id or
 * by the unique email/customer_id it used) — never a global table count — so the
 * tests stay independent and order-free.
 */
class AccountsAndCustomersIntegrationTest extends IntegrationTestBase {

    // ----- small local helpers (kept private to this file) -----

    /** Build a POST /customers body. */
    private static Map<String, Object> customerBody(String name, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("email", email);
        return body;
    }

    /** Build a POST /accounts body. A null value is still sent (e.g. to omit balance, pass null). */
    private static Map<String, Object> accountBody(Long customerId, String currency, BigDecimal initialBalance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("currency", currency);
        body.put("initialBalance", initialBalance);
        return body;
    }

    /** Create a customer via the API (as staff) and return its generated id. */
    private long createCustomer(String staffToken, String name, String email) throws Exception {
        String resp = mockMvc.perform(post("/customers")
                        .header("Authorization", bearer(staffToken))
                        .contentType(APPLICATION_JSON)
                        .content(json(customerBody(name, email))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(readField(resp, "id"));
    }

    // ----- 1. create customer -----

    @Test
    @DisplayName("POST /customers as staff creates the customer (201) and persists the row")
    void createCustomerPersistsRow() throws Exception {
        String token = adminToken();

        MvcResult result = mockMvc.perform(post("/customers")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(customerBody("Ada Lovelace", "ada@example.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn();

        long customerId = Long.parseLong(readField(result.getResponse().getContentAsString(), "id"));

        // DB: exactly the row we created, looked up by its returned id.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, email FROM customers WHERE id = ?", customerId);
        assertThat(row.get("name")).isEqualTo("Ada Lovelace");
        assertThat(row.get("email")).isEqualTo("ada@example.com");
    }

    // ----- 2. create account with an initial balance -----

    @Test
    @DisplayName("POST /accounts with initialBalance 100.00 creates an ACTIVE account (201) and persists balance 100")
    void createAccountWithInitialBalancePersists() throws Exception {
        String token = adminToken();
        long customerId = createCustomer(token, "Grace Hopper", "grace@example.com");

        MvcResult result = mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(accountBody(customerId, "USD", new BigDecimal("100.00")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value((int) customerId))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value(100))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ledger").isArray())
                .andExpect(jsonPath("$.ledger").isEmpty())
                .andReturn();

        long accountId = Long.parseLong(readField(result.getResponse().getContentAsString(), "id"));

        // DB: balance is NUMERIC(19,4) → 100.0000; status 'ACTIVE'; owner matches.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT customer_id, currency, balance, status FROM accounts WHERE id = ?", accountId);
        assertThat(((Number) row.get("customer_id")).longValue()).isEqualTo(customerId);
        assertThat(row.get("currency")).isEqualTo("USD");
        assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        // The inherited helper sees the same persisted value.
        assertThat(balanceOf(accountId)).isEqualByComparingTo("100.0000");
    }

    // ----- 3. create account with the initial balance omitted -----

    @Test
    @DisplayName("POST /accounts with initialBalance omitted defaults the balance to 0")
    void createAccountWithoutInitialBalanceDefaultsToZero() throws Exception {
        String token = adminToken();
        long customerId = createCustomer(token, "Alan Turing", "alan@example.com");

        // Omit initialBalance entirely (only customerId + currency in the body).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("currency", "EUR");

        MvcResult result = mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        long accountId = Long.parseLong(readField(result.getResponse().getContentAsString(), "id"));

        // DB: balance defaulted to 0.0000.
        BigDecimal balance = jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
        assertThat(balance).isEqualByComparingTo(new BigDecimal("0.0000"));
    }

    // ----- 4. read an account back -----

    @Test
    @DisplayName("GET /accounts/{id} returns the balance and an empty ledger (200)")
    void getAccountReturnsBalanceAndEmptyLedger() throws Exception {
        String token = adminToken();
        long customerId = createCustomer(token, "Edsger Dijkstra", "edsger@example.com");
        long accountId = createAccount(token, customerId, "GBP", new BigDecimal("250.00"));

        mockMvc.perform(get("/accounts/{id}", accountId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) accountId))
                .andExpect(jsonPath("$.customerId").value((int) customerId))
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.balance").value(250))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ledger").isArray())
                .andExpect(jsonPath("$.ledger").isEmpty());

        // DB: the account exists with the expected balance.
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ?", accountId)).isEqualTo(1L);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("250.0000");
    }

    // ----- 5. validation: blank name + invalid email -----

    @Test
    @DisplayName("POST /customers with blank name and invalid email returns 400 with both fieldErrors and saves nothing")
    void createCustomerWithInvalidFieldsReturnsFieldErrors() throws Exception {
        String token = adminToken();
        String badEmail = "not-an-email";

        mockMvc.perform(post("/customers")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(customerBody("   ", badEmail))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").exists())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        // DB: no customer row was created for that (invalid) email.
        assertThat(count("SELECT count(*) FROM customers WHERE email = ?", badEmail)).isZero();
    }

    // ----- 6. validation: negative initial balance -----

    @Test
    @DisplayName("POST /accounts with a negative initialBalance returns 400 and creates no account")
    void createAccountWithNegativeBalanceRejected() throws Exception {
        String token = adminToken();
        long customerId = createCustomer(token, "Margaret Hamilton", "margaret@example.com");

        mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(accountBody(customerId, "USD", new BigDecimal("-1.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.initialBalance").exists());

        // DB: no account exists for this customer.
        assertThat(count("SELECT count(*) FROM accounts WHERE customer_id = ?", customerId)).isZero();
    }

    // ----- 7. unknown customer -----

    @Test
    @DisplayName("POST /accounts for an unknown customerId returns 404 and creates no account")
    void createAccountForUnknownCustomerReturnsNotFound() throws Exception {
        String token = adminToken();
        long unknownCustomerId = 999999L;

        mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(accountBody(unknownCustomerId, "USD", new BigDecimal("10.00")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // DB: no account points at that non-existent customer.
        assertThat(count("SELECT count(*) FROM accounts WHERE customer_id = ?", unknownCustomerId)).isZero();
    }

    // ----- 8. validation: bad currency -----

    @Test
    @DisplayName("POST /accounts with a non-ISO currency returns 400 and creates no account")
    void createAccountWithInvalidCurrencyRejected() throws Exception {
        String token = adminToken();
        long customerId = createCustomer(token, "Katherine Johnson", "katherine@example.com");

        // "usd" is lowercase (and "US" would be 2 letters) — both fail the ^[A-Z]{3}$ pattern.
        mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(accountBody(customerId, "usd", new BigDecimal("10.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.currency").exists());

        // DB: nothing was opened for this customer.
        assertThat(count("SELECT count(*) FROM accounts WHERE customer_id = ?", customerId)).isZero();
    }

    // ----- 9. unknown account -----

    @Test
    @DisplayName("GET /accounts/{unknownId} returns 404")
    void getUnknownAccountReturnsNotFound() throws Exception {
        String token = adminToken();
        long unknownAccountId = 999999L;

        mockMvc.perform(get("/accounts/{id}", unknownAccountId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // DB: confirm no such account exists.
        assertThat(count("SELECT count(*) FROM accounts WHERE id = ?", unknownAccountId)).isZero();
    }

    // ----- 10. duplicate email -----

    @Test
    @DisplayName("POST /customers with a duplicate email returns 409 and keeps exactly one row for that email")
    void createCustomerWithDuplicateEmailReturnsConflict() throws Exception {
        String token = adminToken();
        String email = "duplicate@example.com";

        long firstId = createCustomer(token, "First Owner", email);

        // Second attempt with the SAME email → 409 Conflict, no new row.
        mockMvc.perform(post("/customers")
                        .header("Authorization", bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content(json(customerBody("Second Owner", email))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // DB: exactly one customer holds that email, and it is the first one we created.
        assertThat(count("SELECT count(*) FROM customers WHERE email = ?", email)).isEqualTo(1L);
        Long onlyId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE email = ?", Long.class, email);
        assertThat(onlyId).isEqualTo(firstId);
    }
}
