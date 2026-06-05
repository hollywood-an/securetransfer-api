package com.securetransfer.api.fraud;

import com.securetransfer.api.service.FraudReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Runs the fraud review asynchronously, AFTER the flagged transfer has committed.
 *
 * @TransactionalEventListener(AFTER_COMMIT) guarantees we only review a transfer
 * that durably happened; @Async runs it on the fraud-review thread pool so the
 * transfer's HTTP response is never held up by the AI call. The DB work is kept
 * in FraudReviewService's short transactions — the agent's network call runs
 * between them, holding no transaction open.
 */
@Component
public class FraudReviewProcessor {

    private static final Logger log = LoggerFactory.getLogger(FraudReviewProcessor.class);

    private final FraudReviewService reviewService;
    private final FraudTriageAgent agent;

    public FraudReviewProcessor(FraudReviewService reviewService, FraudTriageAgent agent) {
        this.reviewService = reviewService;
        this.agent = agent;
    }

    @Async("fraudReviewExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferFlagged(TransferFlaggedEvent event) {
        runReview(event.reviewId());
    }

    /** Load context → ask the agent → persist the verdict. Each step is safe. */
    public void runReview(Long reviewId) {
        Optional<FraudContext> context = reviewService.loadContextIfPending(reviewId);
        if (context.isEmpty()) {
            return; // already decided, or not pending — nothing to do
        }
        FraudVerdict verdict;
        try {
            verdict = agent.triage(context.get());
        } catch (Exception e) {
            // The agent already falls back internally, but belt-and-suspenders.
            log.warn("Fraud review {} failed in the agent ({}); using rules fallback.",
                    reviewId, e.getClass().getSimpleName());
            verdict = FraudVerdict.fallback(context.get().flagReasons(),
                    "Agent threw (" + e.getClass().getSimpleName() + ").");
        }
        reviewService.applyAgentVerdict(reviewId, verdict);
        log.info("Fraud review {} completed: score={}, action={}, source={}",
                reviewId, verdict.riskScore(), verdict.recommendedAction(),
                verdict.fromAgent() ? "ai-agent" : "rules-fallback");
    }
}
