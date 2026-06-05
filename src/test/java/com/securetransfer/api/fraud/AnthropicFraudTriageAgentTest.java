package com.securetransfer.api.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.securetransfer.api.config.AnthropicProperties;
import com.securetransfer.api.domain.RecommendedAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unit tests for the REAL fraud agent's tool-use loop and JSON parsing — the
 * code path the integration tests deliberately stub out.
 *
 * NO network call happens: the Anthropic client is a Mockito mock injected via
 * the package-private constructor, and its canned {@link Message} responses are
 * built by deserializing real API-response JSON with the SDK's own Jackson
 * mapper. These exercise the agentic loop (model asks for a tool, we run it,
 * model returns a verdict) and the "don't trust a bad verdict" fallback.
 */
class AnthropicFraudTriageAgentTest {

    // The SDK's configured mapper: deserializes a Message exactly as it would a
    // real API response (so we don't have to satisfy every builder's required
    // fields by hand).
    private static final JsonMapper SDK_MAPPER = ObjectMappers.jsonMapper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AnthropicProperties props() {
        AnthropicProperties props = new AnthropicProperties();
        props.setApiKey("test-key-not-a-real-secret"); // non-blank => agent uses the (mocked) client
        props.setModel("claude-test-model");
        props.setMaxTokens(256);
        props.setMaxToolIterations(5);
        return props;
    }

    @Test
    @DisplayName("Drives the tool-use loop and parses a valid model verdict into an AI FraudVerdict")
    void parsesValidVerdictViaToolUseLoop() {
        FraudToolExecutor toolExecutor = mock(FraudToolExecutor.class);
        when(toolExecutor.execute(anyString(), anyMap()))
                .thenReturn("{\"accountId\":2,\"balance\":\"100.00\",\"status\":\"ACTIVE\"}");

        // Turn 1: the model asks to call get_account. Turn 2: it returns the verdict JSON.
        Message toolUse = toolUseMessage("get_account", "tool_1", "{\"accountId\":2}");
        Message verdict = textMessage(
                "{\"risk_score\": 72, \"reasoning\": \"Large transfer to a brand-new payee.\","
                        + " \"recommended_action\": \"HOLD\"}");

        AnthropicClient client = clientReturning(toolUse, verdict);
        AnthropicFraudTriageAgent agent =
                new AnthropicFraudTriageAgent(props(), toolExecutor, objectMapper, client);

        FraudVerdict result = agent.triage(new FraudContext(
                1L, 1L, 2L, new BigDecimal("25000.00"), List.of("LARGE_AMOUNT", "NEW_PAYEE")));

        // The verdict came from the AI, parsed correctly.
        assertThat(result.fromAgent()).isTrue();
        assertThat(result.model()).isEqualTo("claude-test-model");
        assertThat(result.verdict()).isEqualTo("AI_REVIEW");
        assertThat(result.riskScore()).isEqualTo(72);
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.HOLD);
        assertThat(result.reasoning()).contains("brand-new payee");

        // The loop actually ran the requested read-only tool, then made a second call.
        verify(toolExecutor).execute(eq("get_account"), anyMap());
        verify(client.messages(), times(2)).create(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("Falls back to rules when the model returns non-JSON instead of a verdict")
    void nonJsonResponseFallsBackToRules() {
        FraudToolExecutor toolExecutor = mock(FraudToolExecutor.class);
        when(toolExecutor.execute(anyString(), anyMap()))
                .thenReturn("{\"accountId\":1,\"outgoingCount\":0}");

        Message toolUse = toolUseMessage("get_velocity_stats", "tool_2", "{\"accountId\":1}");
        Message garbage = textMessage("I looked into it and it seems fine to me.");

        AnthropicClient client = clientReturning(toolUse, garbage);
        AnthropicFraudTriageAgent agent =
                new AnthropicFraudTriageAgent(props(), toolExecutor, objectMapper, client);

        FraudVerdict result = agent.triage(new FraudContext(
                1L, 1L, 2L, new BigDecimal("25000.00"), List.of("LARGE_AMOUNT")));

        // Not trusted: deterministic rules-based fallback instead (LARGE_AMOUNT -> 45 -> HOLD).
        assertThat(result.fromAgent()).isFalse();
        assertThat(result.model()).isEqualTo("rules-fallback");
        assertThat(result.riskScore()).isEqualTo(45);
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.HOLD);
    }

    @Test
    @DisplayName("Falls back to rules when the model returns an invalid recommended_action")
    void invalidActionFallsBackToRules() {
        FraudToolExecutor toolExecutor = mock(FraudToolExecutor.class);

        // Single turn: the model answers immediately with a malformed verdict (bad action).
        Message verdict = textMessage(
                "{\"risk_score\": 30, \"reasoning\": \"ok\", \"recommended_action\": \"FREEZE_EVERYTHING\"}");

        AnthropicClient client = clientReturning(verdict);
        AnthropicFraudTriageAgent agent =
                new AnthropicFraudTriageAgent(props(), toolExecutor, objectMapper, client);

        FraudVerdict result = agent.triage(new FraudContext(
                1L, 1L, 2L, new BigDecimal("50.00"), List.of("NEW_PAYEE")));

        // Invalid enum -> fallback (NEW_PAYEE -> 20 -> APPROVE).
        assertThat(result.fromAgent()).isFalse();
        assertThat(result.model()).isEqualTo("rules-fallback");
        assertThat(result.recommendedAction()).isEqualTo(RecommendedAction.APPROVE);
    }

    // ----- helpers: a mock client returning canned Message responses in order -----

    private AnthropicClient clientReturning(Message first, Message... rest) {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        when(messages.create(any(MessageCreateParams.class))).thenReturn(first, rest);
        return client;
    }

    /** An assistant message whose single content block is a tool_use call. */
    private Message toolUseMessage(String toolName, String toolUseId, String inputJson) {
        return parseMessage("""
                {"id":"msg_tooluse","type":"message","role":"assistant","model":"claude-test-model",
                 "stop_reason":"tool_use",
                 "content":[{"type":"tool_use","id":"%s","name":"%s","input":%s}],
                 "usage":{"input_tokens":10,"output_tokens":20}}
                """.formatted(toolUseId, toolName, inputJson));
    }

    /** An assistant message whose single content block is text (the model's final answer). */
    private Message textMessage(String text) {
        return parseMessage("""
                {"id":"msg_text","type":"message","role":"assistant","model":"claude-test-model",
                 "stop_reason":"end_turn",
                 "content":[{"type":"text","text":%s}],
                 "usage":{"input_tokens":10,"output_tokens":20}}
                """.formatted(jsonStringLiteral(text)));
    }

    private Message parseMessage(String json) {
        try {
            return SDK_MAPPER.readValue(json, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build canned Message from JSON", e);
        }
    }

    /** Turn raw text into a properly-escaped JSON string literal (with quotes). */
    private String jsonStringLiteral(String raw) {
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
