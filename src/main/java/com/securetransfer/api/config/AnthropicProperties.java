package com.securetransfer.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Anthropic fraud-triage agent (app.anthropic.* in
 * application.yml).
 *
 * The API key comes from the ANTHROPIC_API_KEY environment variable. If it's
 * blank, the agent runs in a deterministic rules-based fallback mode instead of
 * calling the model — so the system works end-to-end without a key (and tests
 * never need one).
 */
@ConfigurationProperties(prefix = "app.anthropic")
public class AnthropicProperties {

    /** Anthropic API key (from env ANTHROPIC_API_KEY). Blank → fallback mode. */
    private String apiKey = "";

    /** Model id used for triage. */
    private String model = "claude-haiku-4-5-20251001";

    /** Max output tokens per model call. */
    private long maxTokens = 1024;

    /** Safety cap on the agent's tool-use loop. */
    private int maxToolIterations = 5;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getMaxToolIterations() {
        return maxToolIterations;
    }

    public void setMaxToolIterations(int maxToolIterations) {
        this.maxToolIterations = maxToolIterations;
    }
}
