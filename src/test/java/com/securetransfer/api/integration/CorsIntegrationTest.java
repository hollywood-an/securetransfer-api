package com.securetransfer.api.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the CORS policy (Phase 9) that lets the browser frontend call the API:
 * an allowed origin's preflight is answered with the allow-origin header; any
 * other origin's preflight is rejected. Allowed origins come from
 * app.cors.allowed-origins (the test/default value includes localhost:5173).
 */
class CorsIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("A CORS preflight from an allowed origin is permitted with the allow-origin header")
    void preflightFromAllowedOriginIsPermitted() throws Exception {
        mockMvc.perform(options("/transfers")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("A CORS preflight from a disallowed origin is rejected")
    void preflightFromDisallowedOriginIsRejected() throws Exception {
        mockMvc.perform(options("/transfers")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
