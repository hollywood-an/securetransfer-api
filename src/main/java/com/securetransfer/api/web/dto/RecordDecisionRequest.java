package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.RecommendedAction;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /fraud-reviews/{id}/decision — the human's final call.
 */
public record RecordDecisionRequest(

        @NotNull(message = "decision is required (APPROVE, HOLD, or ESCALATE)")
        RecommendedAction decision
) {
}
