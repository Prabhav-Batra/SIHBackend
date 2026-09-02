package com.sih26046.ctms.safety;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A Safety Officer's assessment of a reported event (§8.18). */
@Entity
@Table(name = "safety_reviews")
public class SafetyReviewEntity {

    @Id private UUID id;

    @Column(name = "adverse_event_id", nullable = false)
    private UUID adverseEventId;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @Column(name = "review_date", nullable = false)
    private Instant reviewDate;

    /** May differ from the reported severity; that divergence is itself a signal (§8.18). */
    @Column(name = "assessed_severity", nullable = false)
    private String assessedSeverity;

    @Column(name = "assessed_causality", nullable = false)
    private String assessedCausality;

    /** Listed in the Investigator's Brochure? Unexpected + serious + related = expedited. */
    @Column(name = "is_expected", nullable = false)
    private boolean expected;

    @Column(name = "requires_expedited_reporting", nullable = false)
    private boolean requiresExpeditedReporting;

    private String comments;

    @Column(nullable = false)
    private String decision;

    protected SafetyReviewEntity() {} // JPA

    public SafetyReviewEntity(
            UUID id,
            UUID adverseEventId,
            UUID reviewerId,
            String assessedSeverity,
            String assessedCausality,
            boolean expected,
            boolean requiresExpeditedReporting,
            String comments,
            String decision) {
        this.id = id;
        this.adverseEventId = adverseEventId;
        this.reviewerId = reviewerId;
        this.reviewDate = Instant.now();
        this.assessedSeverity = assessedSeverity;
        this.assessedCausality = assessedCausality;
        this.expected = expected;
        this.requiresExpeditedReporting = requiresExpeditedReporting;
        this.comments = comments;
        this.decision = decision;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAdverseEventId() {
        return adverseEventId;
    }

    public String getAssessedSeverity() {
        return assessedSeverity;
    }

    public String getAssessedCausality() {
        return assessedCausality;
    }

    public boolean isExpected() {
        return expected;
    }

    public boolean isRequiresExpeditedReporting() {
        return requiresExpeditedReporting;
    }

    public String getDecision() {
        return decision;
    }
}
