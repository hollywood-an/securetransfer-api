package com.securetransfer.api.service;

import com.securetransfer.api.domain.Account;
import com.securetransfer.api.domain.FraudReview;
import com.securetransfer.api.domain.FraudReviewStatus;
import com.securetransfer.api.domain.RecommendedAction;
import com.securetransfer.api.domain.Tenant;
import com.securetransfer.api.domain.Transfer;
import com.securetransfer.api.error.ConflictException;
import com.securetransfer.api.error.NotFoundException;
import com.securetransfer.api.fraud.FraudContext;
import com.securetransfer.api.fraud.FraudVerdict;
import com.securetransfer.api.repository.AccountRepository;
import com.securetransfer.api.repository.FraudReviewRepository;
import com.securetransfer.api.repository.TransferRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final AccountRepository accounts;
    private final AuditService auditService;

    public FraudReviewService(FraudReviewRepository reviews,
                              TransferRepository transfers,
                              AccountRepository accounts,
                              AuditService auditService) {
        this.reviews = reviews;
        this.transfers = transfers;
        this.accounts = accounts;
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

        // Reconstruct the sender's balance BEFORE this transfer. Money moves even
        // when a transfer is flagged, so the account's live balance is already
        // post-debit; the sender was debited exactly `amount`, so adding it back
        // gives the balance excluding this transfer. Handing this to the agent
        // stops it misreading the depleted post-debit balance as an "overdraft"
        // and lets it judge how much of the account the transfer actually moved.
        BigDecimal fromBalanceBefore = accounts.findById(transfer.getFromAccount())
                .map(Account::getBalance)
                .map(current -> current.add(transfer.getAmount()))
                .orElse(null);

        return Optional.of(new FraudContext(
                transfer.getId(), transfer.getFromAccount(), transfer.getToAccount(),
                transfer.getAmount(), review.getFlagReasons(), fromBalanceBefore));
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

    /**
     * The review queue for the caller's bank (optionally filtered by status),
     * newest first. Scoped by tenant so DEMO never sees STAFF reviews and vice
     * versa.
     */
    @Transactional(readOnly = true)
    public Page<FraudReview> list(FraudReviewStatus status, Tenant tenant, Pageable pageable) {
        return status == null
                ? reviews.findByTenant(tenant, pageable)
                : reviews.findByStatusAndTenant(status, tenant, pageable);
    }

    @Transactional(readOnly = true)
    public FraudReview getById(Long id, Tenant tenant) {
        return reviews.findById(id)
                .filter(r -> r.getTenant() == tenant) // cross-tenant → 404 (invisible)
                .orElseThrow(() -> new NotFoundException("Fraud review " + id + " not found"));
    }

    /**
     * Human-in-the-loop: a TELLER/ADMIN records the actual decision. This only
     * RECORDS the decision (and audits it) — it does not itself move or hold
     * money; acting on a HOLD/ESCALATE is a separate, human-driven step.
     */
    @Transactional
    public FraudReview recordDecision(Long id, RecommendedAction decision, String actor, Tenant tenant) {
        // Lock the row so this serializes against the async agent verdict: we
        // re-read the committed status under the lock before deciding.
        FraudReview review = reviews.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Fraud review " + id + " not found"));
        // A caller may only decide reviews in their OWN bank; a cross-tenant id
        // is invisible (404), so DEMO can decide only its own reviews and staff
        // only theirs.
        if (review.getTenant() != tenant) {
            throw new NotFoundException("Fraud review " + id + " not found");
        }
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
