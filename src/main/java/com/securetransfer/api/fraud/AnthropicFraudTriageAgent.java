package com.securetransfer.api.fraud;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetransfer.api.config.AnthropicProperties;
import com.securetransfer.api.domain.RecommendedAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The fraud-triage agent, backed by the Anthropic SDK.
 *
 * GUARDRAILS (why "AI near money" is safe here):
 *  - READ-ONLY TOOLS: the model is given exactly three tools (get_account,
 *    get_transaction_history, get_velocity_stats), all served by
 *    {@link FraudToolExecutor}, which only reads. The agent cannot move, hold,
 *    or change money — it can look, never touch.
 *  - ADVISORY ONLY: this returns a {@link FraudVerdict}; it never updates a
 *    transfer or account. A human records the final decision elsewhere.
 *  - VALIDATED OUTPUT: the model's JSON is parsed and validated (risk 0–100,
 *    a known action) before use; anything malformed falls back to rules.
 *  - GRACEFUL DEGRADATION: with no API key, or on any error, we return a
 *    deterministic rules-based verdict instead of failing.
 *  - The API key comes from config/env and is never logged.
 */
@Component
public class AnthropicFraudTriageAgent implements FraudTriageAgent {

    private static final Logger log = LoggerFactory.getLogger(AnthropicFraudTriageAgent.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String SYSTEM_PROMPT = """
            You are a fraud-triage analyst at a bank. A money transfer has been FLAGGED by
            simple rules and you must assess its risk. Investigate using the provided
            READ-ONLY tools — you can look up account details, transaction history, and
            velocity stats, but you CANNOT move, hold, or change money. You only advise; a
            human makes the final call.

            When you have enough information, respond with ONLY a JSON object (no prose,
            no markdown) of exactly this shape:
            {"risk_score": <integer 0-100>, "reasoning": "<2-4 sentences>", "recommended_action": "APPROVE" | "HOLD" | "ESCALATE"}

            Base risk_score on genuine signals: how large the amount is relative to the
            account's balance BEFORE this transfer (moving most of an account is a
            draining signal), transaction history, velocity, and whether the payee is
            new. IMPORTANT: a flagged transfer has ALREADY executed and passed a funds
            check, so the balance returned by get_account is the CURRENT (post-transfer)
            balance — never treat it as "insufficient funds" or an "overdraft" for the
            transfer under review. APPROVE low risk, HOLD moderate risk, ESCALATE high
            risk.
            """;

    private final AnthropicProperties props;
    private final FraudToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    // A pre-supplied client (for tests). Null in production, where the client is
    // created lazily on first use (only when a key is present) — see client().
    private final AnthropicClient injectedClient;

    // The SDK client is created lazily on first use, so the app starts fine — and
    // tests run — with no API key configured.
    private volatile AnthropicClient cachedClient;

    @Autowired
    public AnthropicFraudTriageAgent(AnthropicProperties props,
                                     FraudToolExecutor toolExecutor,
                                     ObjectMapper objectMapper) {
        this(props, toolExecutor, objectMapper, null);
    }

    /**
     * Visible for testing: inject a stub/mock {@link AnthropicClient} so the
     * tool-use loop and JSON-parsing path can be exercised without any network
     * call. Production uses the 3-arg constructor above (injectedClient = null).
     */
    AnthropicFraudTriageAgent(AnthropicProperties props,
                              FraudToolExecutor toolExecutor,
                              ObjectMapper objectMapper,
                              AnthropicClient injectedClient) {
        this.props = props;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.injectedClient = injectedClient;
    }

    @Override
    public FraudVerdict triage(FraudContext ctx) {
        if (!isAvailable()) {
            return FraudVerdict.fallback(ctx.flagReasons(),
                    "AI agent unavailable (no ANTHROPIC_API_KEY configured).");
        }
        try {
            return runAgent(ctx);
        } catch (Exception e) {
            // Never let an agent/network error break the review — fall back.
            log.warn("Fraud-triage agent failed for transfer {} ({}); using rules fallback.",
                    ctx.transferId(), e.getClass().getSimpleName());
            return FraudVerdict.fallback(ctx.flagReasons(),
                    "AI agent error (" + e.getClass().getSimpleName() + "); used rules fallback.");
        }
    }

    private boolean isAvailable() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    private FraudVerdict runAgent(FraudContext ctx) {
        AnthropicClient client = client();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(props.getModel())
                .maxTokens(props.getMaxTokens())
                .system(SYSTEM_PROMPT)
                .addUserMessage(buildUserPrompt(ctx))
                .addTool(getAccountTool())
                .addTool(getTransactionHistoryTool())
                .addTool(getVelocityStatsTool())
                .build();

        for (int iteration = 0; iteration < props.getMaxToolIterations(); iteration++) {
            Message response = client.messages().create(params);

            boolean hasToolUse = response.content().stream().anyMatch(ContentBlock::isToolUse);
            if (!hasToolUse) {
                String text = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(b -> b.asText().text())
                        .collect(Collectors.joining("\n"));
                return parseVerdict(text, ctx);
            }

            // Run each requested tool (read-only) and feed the results back.
            List<ContentBlockParam> toolResults = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    Map<String, Object> input = toolUse._input().convert(MAP_TYPE);
                    String result = toolExecutor.execute(
                            toolUse.name(), input == null ? Map.of() : input);
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(result)
                                    .build()));
                }
            }

            params = params.toBuilder()
                    .addMessage(response)                       // the assistant's tool-call turn
                    .addUserMessageOfBlockParams(toolResults)   // our tool results
                    .build();
        }

        return FraudVerdict.fallback(ctx.flagReasons(),
                "AI agent exceeded the tool-use limit; used rules fallback.");
    }

    private String buildUserPrompt(FraudContext ctx) {
        String balanceBefore = ctx.fromAccountBalanceBeforeTransfer() == null
                ? "unknown"
                : ctx.fromAccountBalanceBeforeTransfer().toPlainString();
        return """
                A transfer has been flagged for review.
                  transferId: %d
                  fromAccount: %d
                  toAccount: %d
                  amount: %s
                  fromAccount balance BEFORE this transfer: %s
                  rules that fired: %s

                Note: this transfer has ALREADY executed and passed a funds check, so the
                sender had enough money at the time — it is NOT an overdraft. The balance
                get_account returns is the CURRENT (post-transfer) balance, so never call a
                low current balance "insufficient funds" for this transfer. To gauge how
                much of the account this transfer moved, compare the amount against the
                fromAccount balance BEFORE this transfer shown above.

                Investigate with the tools, then return your JSON verdict.
                """.formatted(ctx.transferId(), ctx.fromAccount(), ctx.toAccount(),
                ctx.amount().toPlainString(), balanceBefore, ctx.flagReasons());
    }

    /** Parse + VALIDATE the model's JSON. Anything off → rules fallback. */
    private FraudVerdict parseVerdict(String text, FraudContext ctx) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return FraudVerdict.fallback(ctx.flagReasons(), "Agent returned no JSON; used rules fallback.");
            }
            JsonNode node = objectMapper.readTree(text.substring(start, end + 1));

            int riskScore = Math.max(0, Math.min(100, node.path("risk_score").asInt()));
            String reasoning = node.path("reasoning").asText("");
            RecommendedAction action =
                    RecommendedAction.valueOf(node.path("recommended_action").asText().trim().toUpperCase());

            return new FraudVerdict(riskScore, "AI_REVIEW", reasoning, action, props.getModel(), true);
        } catch (Exception e) {
            return FraudVerdict.fallback(ctx.flagReasons(),
                    "Agent JSON invalid (" + e.getClass().getSimpleName() + "); used rules fallback.");
        }
    }

    private AnthropicClient client() {
        if (injectedClient != null) {
            return injectedClient; // test-supplied stub/mock; no network
        }
        AnthropicClient c = cachedClient;
        if (c == null) {
            synchronized (this) {
                c = cachedClient;
                if (c == null) {
                    c = AnthropicOkHttpClient.builder().apiKey(props.getApiKey()).build();
                    cachedClient = c;
                }
            }
        }
        return c;
    }

    // --- Read-only tool definitions ---

    private static Tool getAccountTool() {
        return buildTool("get_account",
                "Look up an account's owner, currency, current balance (already reflects "
                        + "completed transfers, including the one under review), and status "
                        + "(ACTIVE/FROZEN).",
                Map.of("accountId", Map.of("type", "integer", "description", "The account id to look up")),
                List.of("accountId"));
    }

    private static Tool getTransactionHistoryTool() {
        return buildTool("get_transaction_history",
                "Recent transfers involving an account (as sender or receiver), newest first.",
                Map.of(
                        "accountId", Map.of("type", "integer", "description", "The account id"),
                        "limit", Map.of("type", "integer", "description", "Max rows to return (default 10, max 50)")),
                List.of("accountId"));
    }

    private static Tool getVelocityStatsTool() {
        return buildTool("get_velocity_stats",
                "How many transfers an account has sent recently, and the total amount, within the velocity window.",
                Map.of("accountId", Map.of("type", "integer", "description", "The account id")),
                List.of("accountId"));
    }

    private static Tool buildTool(String name, String description,
                                  Map<String, Object> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, schema) -> propsBuilder.putAdditionalProperty(key, JsonValue.from(schema)));
        Tool.InputSchema inputSchema = Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(propsBuilder.build())
                .required(required)
                .build();
        return Tool.builder().name(name).description(description).inputSchema(inputSchema).build();
    }
}
