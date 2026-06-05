package com.securetransfer.api.web.dto;

import com.securetransfer.api.domain.FraudReview;
import com.securetransfer.api.domain.FraudReviewStatus;
import com.securetransfer.api.domain.RecommendedAction;

import java.time.OffsetDateTime;
import java.util.List;

/** A fraud review as returned by the /fraud-reviews endpoints. */
public record FraudReviewResponse(
        Long id,
        Long transferId,
        FraudReviewStatus status,
        List<String> flagReasons,
        Integer riskScore,
        String verdict,
        String reasoning,
        RecommendedAction recommendedAction,
        String agentModel,
        RecommendedAction humanDecision,
        String decidedByUser,
        OffsetDateTime decidedAt,
        OffsetDateTime createdAt
) {
    public static FraudReviewResponse from(FraudReview r) {
        return new FraudReviewResponse(
                r.getId(), r.getTransferId(), r.getStatus(), r.getFlagReasons(),
                r.getRiskScore(), r.getVerdict(), r.getReasoning(), r.getRecommendedAction(),
                r.getAgentModel(), r.getHumanDecision(), r.getDecidedByUser(),
                r.getDecidedAt(), r.getCreatedAt());
    }
}
