package com.sih26046.ctms.ethics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Whether one trial has met one requirement, optionally at one site (§8.22). */
@Entity
@Table(name = "trial_compliance")
public class TrialComplianceEntity {

    /**
     * Statuses that leave work to do. {@code NOT_APPLICABLE} and {@code WAIVED} are settled
     * answers, not unfinished ones — a requirement that does not apply is not outstanding.
     */
    private static final Set<String> OUTSTANDING = Set.of("PENDING", "IN_PROGRESS", "NON_COMPLIANT");

    /** Reaching one of these is a claim someone made, so it records who and when. */
    private static final Set<String> VERIFIED =
            Set.of("COMPLIANT", "NON_COMPLIANT", "NOT_APPLICABLE", "WAIVED");

    @Id private UUID id;

    @Column(name = "trial_id", nullable = false, updatable = false)
    private UUID trialId;

    @Column(name = "compliance_requirement_id", nullable = false, updatable = false)
    private UUID complianceRequirementId;

    @Column(name = "trial_site_id", updatable = false)
    private UUID trialSiteId;

    @Column(nullable = false)
    private String status;

    @Column(name = "evidence_document_id")
    private UUID evidenceDocumentId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "completed_date")
    private LocalDate completedDate;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    private String notes;

    @Version private int version;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected TrialComplianceEntity() {} // JPA

    public TrialComplianceEntity(
            UUID id,
            UUID trialId,
            UUID complianceRequirementId,
            UUID trialSiteId,
            LocalDate dueDate) {
        this.id = id;
        this.trialId = trialId;
        this.complianceRequirementId = complianceRequirementId;
        this.trialSiteId = trialSiteId;
        this.dueDate = dueDate;
        this.status = "PENDING";
    }

    public static boolean isOutstanding(String status) {
        return OUTSTANDING.contains(status);
    }

    /**
     * Records an assessment.
     *
     * <p>{@code verified_by} and {@code verified_at} move together, because
     * {@code ck_trial_compliance_verification} requires it and because half a verification
     * record is not evidence of anything. Moving back to an unsettled status clears both:
     * leaving a stale verifier on a row that is once again pending would attribute to them a
     * judgement they did not make.
     */
    public void assess(String status, UUID verifier, UUID evidenceDocumentId, String notes) {
        this.status = status;
        this.notes = notes;
        if (evidenceDocumentId != null) {
            this.evidenceDocumentId = evidenceDocumentId;
        }
        if (VERIFIED.contains(status)) {
            this.verifiedBy = verifier;
            this.verifiedAt = Instant.now();
        } else {
            this.verifiedBy = null;
            this.verifiedAt = null;
        }
        this.completedDate = "COMPLIANT".equals(status) ? LocalDate.now() : null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTrialId() {
        return trialId;
    }

    public UUID getComplianceRequirementId() {
        return complianceRequirementId;
    }

    public UUID getTrialSiteId() {
        return trialSiteId;
    }

    public String getStatus() {
        return status;
    }

    public UUID getEvidenceDocumentId() {
        return evidenceDocumentId;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getCompletedDate() {
        return completedDate;
    }

    public UUID getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getNotes() {
        return notes;
    }

    public int getVersion() {
        return version;
    }
}
