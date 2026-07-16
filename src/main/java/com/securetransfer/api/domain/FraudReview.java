package com.securetransfer.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The fraud-triage review of a single flagged transfer. Maps to "fraud_reviews"
 * (V1 + V5).
 *
 * Holds the rules that flagged the transfer, the AGENT's recommendation (the
 * agent only ever RECOMMENDS), and the HUMAN's final decision. The agent never
 * mutates money or the transfer — its output lives only here.
 */
@Entity
@Table(name = "fraud_reviews")
public class FraudReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    // Which bank this review belongs to (copied from the flagged transfer's
    // accounts). The review queue is listed filtered by tenant.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudReviewStatus status;

    // Which rules fired, e.g. ["LARGE_AMOUNT", "NEW_PAYEE"]. JSONB.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flag_reasons")
    private List<String> flagReasons;

    // --- The agent's recommendation (filled in by the async review) ---
    @Column(name = "risk_score")
    private Integer riskScore; // 0–100

    @Column(length = 50)
    private String verdict;

    @Column(columnDefinition = "text")
    private String reasoning;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommended_action", length = 20)
    private RecommendedAction recommendedAction;

    @Column(name = "agent_model", length = 100)
    private String agentModel;

    // Source of the recommendation — always 'AGENT' here (the human decision is
    // captured separately below). Kept for the V1 schema's decided_by column.
    @Column(name = "decided_by", nullable = false, length = 20)
    private String decidedBy;

    // --- The human's final decision (human-in-the-loop) ---
    @Enumerated(EnumType.STRING)
    @Column(name = "human_decision", length = 20)
    private RecommendedAction humanDecision;

    @Column(name = "decided_by_user", length = 255)
    private String decidedByUser;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected FraudReview() {
        // JPA requires a no-arg constructor.
    }

    /** A freshly flagged transfer's review: PENDING, with the rules that fired. */
    public static FraudReview pending(Long transferId, List<String> flagReasons, Tenant tenant) {
        FraudReview r = new FraudReview();
        r.transferId = transferId;
        r.tenant = tenant;
        r.status = FraudReviewStatus.PENDING;
        r.flagReasons = flagReasons;
        r.decidedBy = "AGENT";
        return r;
    }

    /** Record the agent's verdict (called by the async review). */
    public void applyAgentVerdict(int riskScore, String verdict, String reasoning,
                                  RecommendedAction recommendedAction, String model) {
        this.riskScore = riskScore;
        this.verdict = verdict;
        this.reasoning = reasoning;
        this.recommendedAction = recommendedAction;
        this.agentModel = model;
        this.status = FraudReviewStatus.AGENT_COMPLETED;
    }

    /** Record the human's final decision (human-in-the-loop). */
    public void recordHumanDecision(RecommendedAction decision, String user, OffsetDateTime at) {
        this.humanDecision = decision;
        this.decidedByUser = user;
        this.decidedAt = at;
        this.status = FraudReviewStatus.DECIDED;
    }

    public Long getId() {
        return id;
    }

    public Long getTransferId() {
        return transferId;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public FraudReviewStatus getStatus() {
        return status;
    }

    public List<String> getFlagReasons() {
        return flagReasons;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public String getVerdict() {
        return verdict;
    }

    public String getReasoning() {
        return reasoning;
    }

    public RecommendedAction getRecommendedAction() {
        return recommendedAction;
    }

    public String getAgentModel() {
        return agentModel;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public RecommendedAction getHumanDecision() {
        return humanDecision;
    }

    public String getDecidedByUser() {
        return decidedByUser;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
