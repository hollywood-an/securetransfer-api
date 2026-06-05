package com.securetransfer.api.service;

import com.securetransfer.api.domain.FraudReview;
import com.securetransfer.api.domain.FraudReviewStatus;
import com.securetransfer.api.domain.RecommendedAction;
import com.securetransfer.api.domain.Transfer;
import com.securetransfer.api.error.ConflictException;
import com.securetransfer.api.error.NotFoundException;
import com.securetransfer.api.fraud.FraudContext;
import com.securetransfer.api.fraud.FraudVerdict;
import com.securetransfer.api.repository.FraudReviewRepository;
import com.securetransfer.api.repository.TransferRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The transactional operations on fraud reviews: building the agent's context,
 * persisting its verdict, recording the human decision, and reading the queue.
 *
 * The async orchestration (calling the agent off the transfer's thread) lives in
 * FraudReviewProcessor; this service holds the short, well-scoped transactions
 * so no DB transaction is held open across the agent's network call.
 */
@Service
public class FraudReviewService {

    private final FraudReviewRepository reviews;
    private final TransferRepository transfers;
    private final AuditService auditService;

    public FraudReviewService(FraudReviewRepository reviews,
                              TransferRepository transfers,
                              AuditService auditService) {
        this.reviews = reviews;
        this.transfers = transfers;
        this.auditService = auditService;
    }

    /** Build the agent's context for a review IF it's still PENDING, else empty. */
    @Transactional(readOnly = true)
    public Optional<FraudContext> loadContextIfPending(Long reviewId) {
        FraudReview review = reviews.findById(reviewId).orElse(null);
        if (review == null || review.getStatus() != FraudReviewStatus.PENDING) {
            return Optional.empty();
        }
        Transfer transfer = transfers.findById(review.getTransferId())
                .orElseThrow(() -> new NotFoundException("Transfer " + review.getTransferId() + " not found"));
        return Optional.of(new FraudContext(
                transfer.getId(), transfer.getFromAccount(), transfer.getToAccount(),
                transfer.getAmount(), review.getFlagReasons()));
    }

    /**
     * Persist the agent's verdict and log it to the audit trail — in one
     * transaction. Skips if the review is no longer PENDING (e.g. a human already
     * decided), so a slow agent can never clobber a human decision.
     */
    @Transactional
    public void applyAgentVerdict(Long reviewId, FraudVerdict verdict) {
        // Lock the row so a concurrent human decision can't be clobbered: if the
        // human got here first, we re-read DECIDED under the lock and skip.
        FraudReview review = reviews.findByIdForUpdate(reviewId).orElse(null);
        if (review == null || review.getStatus() != FraudReviewStatus.PENDING) {
            return;
        }
        review.applyAgentVerdict(verdict.riskScore(), verdict.verdict(), verdict.reasoning(),
                verdict.recommendedAction(), verdict.model());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reviewId", reviewId);
        metadata.put("riskScore", verdict.riskScore());
        metadata.put("recommendedAction", verdict.recommendedAction().name());
        metadata.put("model", verdict.model());
        metadata.put("source", verdict.fromAgent() ? "ai-agent" : "rules-fallback");
        // The agent is not a human user — record it as a system actor.
        auditService.record("agent:fraud-triage", "FRAUD_REVIEW_COMPLETED",
                "transfer:" + review.getTransferId(), metadata);
    }

    /** The review queue (optionally filtered by status), newest first. */
    @Transactional(readOnly = true)
    public Page<FraudReview> list(FraudReviewStatus status, Pageable pageable) {
        return status == null ? reviews.findAll(pageable) : reviews.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public FraudReview getById(Long id) {
        return reviews.findById(id)
                .orElseThrow(() -> new NotFoundException("Fraud review " + id + " not found"));
    }

    /**
     * Human-in-the-loop: a TELLER/ADMIN records the actual decision. This only
     * RECORDS the decision (and audits it) — it does not itself move or hold
     * money; acting on a HOLD/ESCALATE is a separate, human-driven step.
     */
    @Transactional
    public FraudReview recordDecision(Long id, RecommendedAction decision, String actor) {
        // Lock the row so this serializes against the async agent verdict: we
        // re-read the committed status under the lock before deciding.
        FraudReview review = reviews.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Fraud review " + id + " not found"));
        if (review.getStatus() == FraudReviewStatus.DECIDED) {
            throw new ConflictException("Fraud review " + id + " has already been decided");
        }
        review.recordHumanDecision(decision, actor, OffsetDateTime.now(ZoneOffset.UTC));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reviewId", id);
        metadata.put("decision", decision.name());
        auditService.record(actor, "FRAUD_DECISION_RECORDED",
                "transfer:" + review.getTransferId(), metadata);
        return review;
    }
}
