package com.sih26046.ctms.ethics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A submission to an institutional ethics committee (§8.19).
 *
 * <p>{@code institutionId} is the scope key, and it is the receiving IEC rather than the
 * sponsor: §5.5 scopes a committee to its own institution's submissions, so this one column
 * decides who deliberates on the row.
 */
@Entity
@Table(name = "ethics_submissions")
public class EthicsSubmissionEntity {

    @Id private UUID id;

    @Column(name = "trial_id", nullable = false, updatable = false)
    private UUID trialId;

    @Column(name = "institution_id", nullable = false, updatable = false)
    private UUID institutionId;

    @Column(name = "submission_number", nullable = false, updatable = false)
    private String submissionNumber;

    @Column(name = "submission_type", nullable = false, updatable = false)
    private String submissionType;

    @Column(name = "submitted_by", nullable = false, updatable = false)
    private UUID submittedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "submitted_at", insertable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "protocol_document_id")
    private UUID protocolDocumentId;

    @Column(nullable = false)
    private String summary;

    @Column(nullable = false)
    private String status;

    @Column(name = "decision_date")
    private LocalDate decisionDate;

    @Column(name = "approval_valid_until")
    private LocalDate approvalValidUntil;

    private String conditions;

    /** Drives the ETag; §21.1 makes every decision a conditional write. */
    @Version private int version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected EthicsSubmissionEntity() {} // JPA

    public EthicsSubmissionEntity(
            UUID id,
            UUID trialId,
            UUID institutionId,
            String submissionNumber,
            String submissionType,
            String summary,
            UUID protocolDocumentId,
            UUID submittedBy) {
        this.id = id;
        this.trialId = trialId;
        this.institutionId = institutionId;
        this.submissionNumber = submissionNumber;
        this.submissionType = submissionType;
        this.summary = summary;
        this.protocolDocumentId = protocolDocumentId;
        this.submittedBy = submittedBy;
        this.status = EthicsDecision.SUBMITTED;
    }

    /**
     * Records the committee's decision.
     *
     * <p>The decision date is set here rather than accepted from the request: when a committee
     * decided is a fact about this system's records, and a caller-supplied date could
     * pre- or post-date the deliberation it claims to summarise.
     */
    public void decide(String decision, String conditions, LocalDate approvalValidUntil) {
        this.status = decision;
        this.conditions = conditions;
        this.approvalValidUntil = approvalValidUntil;
        this.decisionDate = LocalDate.now();
    }

    /** Entering review is not a decision, so it records no decision date. */
    public void markUnderReview() {
        this.status = EthicsDecision.UNDER_REVIEW;
    }

    public void withdraw() {
        this.status = EthicsDecision.WITHDRAWN;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTrialId() {
        return trialId;
    }

    public UUID getInstitutionId() {
        return institutionId;
    }

    public String getSubmissionNumber() {
        return submissionNumber;
    }

    public String getSubmissionType() {
        return submissionType;
    }

    public UUID getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public UUID getProtocolDocumentId() {
        return protocolDocumentId;
    }

    public String getSummary() {
        return summary;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getDecisionDate() {
        return decisionDate;
    }

    public LocalDate getApprovalValidUntil() {
        return approvalValidUntil;
    }

    public String getConditions() {
        return conditions;
    }

    public int getVersion() {
        return version;
    }
}
