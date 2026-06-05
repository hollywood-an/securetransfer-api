package com.securetransfer.api.fraud;

/**
 * Investigates a flagged transfer and returns a structured risk verdict.
 *
 * Implementations must be SIDE-EFFECT FREE with respect to the bank's state:
 * the agent may only READ (via its read-only tools) and RECOMMEND. It can never
 * move, hold, or change money — a human makes the final decision.
 */
public interface FraudTriageAgent {

    FraudVerdict triage(FraudContext context);
}
