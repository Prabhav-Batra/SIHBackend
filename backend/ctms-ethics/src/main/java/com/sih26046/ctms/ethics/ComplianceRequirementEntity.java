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
 * One rule a trial is measured against (§8.21).
 *
 * <p>Reference data, and the only table in this phase that is not scoped: every role reads the
 * catalogue, because a requirement nobody can see is one nobody can plan for. What is scoped is
 * the answer to "has this trial met it", which lives in {@link TrialComplianceEntity}.
 */
@Entity
@Table(name = "compliance_requirements")
public class ComplianceRequirementEntity {

    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String category;

    private String authority;

    /** NULL means every phase. */
    @Column(name = "applies_to_phase", columnDefinition = "text[]")
    private String[] appliesToPhase;

    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory;

    @Column(name = "evidence_required", nullable = false)
    private boolean evidenceRequired;

    @Column(nullable = false)
    private String status;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ComplianceRequirementEntity() {} // JPA

    public ComplianceRequirementEntity(
            UUID id,
            String code,
            String title,
            String description,
            String category,
            String authority,
            String[] appliesToPhase,
            boolean mandatory,
            boolean evidenceRequired) {
        this.id = id;
        this.code = code;
        this.title = title;
        this.description = description;
        this.category = category;
        this.authority = authority;
        this.appliesToPhase = appliesToPhase == null ? null : appliesToPhase.clone();
        this.mandatory = mandatory;
        this.evidenceRequired = evidenceRequired;
        this.status = "ACTIVE";
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public String getAuthority() {
        return authority;
    }

    public String[] getAppliesToPhase() {
        return appliesToPhase == null ? null : appliesToPhase.clone();
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public boolean isEvidenceRequired() {
        return evidenceRequired;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
