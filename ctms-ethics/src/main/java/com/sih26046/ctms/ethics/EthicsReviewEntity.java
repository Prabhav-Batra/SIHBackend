package com.sih26046.ctms.ethics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One committee member's assessment of a submission (§8.20).
 *
 * <p>Deliberation content. §5.7 keeps {@code comments} from the submitting investigator, who
 * would otherwise read the committee's candid view of their own trial, and from the regulator,
 * who verifies that a decision exists without reading the argument behind it. The privacy is
 * enforced by the row-level policy on this table; the API layer only has to avoid routing
 * around it.
 */
@Entity
@Table(name = "ethics_reviews")
public class EthicsReviewEntity {

    @Id private UUID id;

    @Column(name = "ethics_submission_id", nullable = false, updatable = false)
    private UUID ethicsSubmissionId;

    @Column(name = "reviewer_id", nullable = false, updatable = false)
    private UUID reviewerId;

    @Generated(event = EventType.INSERT)
    @Column(name = "review_date", insertable = false, updatable = false)
    private Instant reviewDate;

    @Column(nullable = false)
    private String recommendation;

    @Column(nullable = false)
    private String comments;

    protected EthicsReviewEntity() {} // JPA

    public EthicsReviewEntity(
            UUID id,
            UUID ethicsSubmissionId,
            UUID reviewerId,
            String recommendation,
            String comments) {
        this.id = id;
        this.ethicsSubmissionId = ethicsSubmissionId;
        this.reviewerId = reviewerId;
        this.recommendation = recommendation;
        this.comments = comments;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEthicsSubmissionId() {
        return ethicsSubmissionId;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public Instant getReviewDate() {
        return reviewDate;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public String getComments() {
        return comments;
    }
}
