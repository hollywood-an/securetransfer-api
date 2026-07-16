package com.securetransfer.api.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Actuator operational endpoints. /actuator/health and /actuator/info are
 * PUBLIC (Render's health check and the deploy-verification workflow read them
 * without a token); everything else — including other actuator endpoints — still
 * requires auth. Health reflects real dependencies (the DB is up here via
 * Testcontainers), and info reports the deployed commit (RENDER_GIT_COMMIT;
 * 'local' off-platform, e.g. in tests).
 */
class ActuatorIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("/actuator/health is public and reports UP with the DB connected")
    void healthIsPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("/actuator/info is public and exposes the app + deployed commit")
    void infoIsPublicAndExposesCommit() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("securetransfer-api"))
                .andExpect(jsonPath("$.git.commit").exists())   // 'local' off-platform (e.g. in tests)
                .andExpect(jsonPath("$.git.branch").exists());
    }

    @Test
    @DisplayName("No sensitive actuator endpoint is publicly readable — only health + info are exposed")
    void sensitiveActuatorEndpointsAreNotPublic() throws Exception {
        // If exposure ever widened to '*', these would start returning 200 and this test fails loudly.
        for (String endpoint : new String[]{
                "env", "beans", "configprops", "heapdump", "threaddump", "loggers", "mappings", "metrics"}) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().is4xxClientError()); // 401/404 — never a 200 with data
        }
    }
}
