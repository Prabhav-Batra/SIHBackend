package com.sih26046.ctms.trials;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A clinical trial (§8.8). */
@Entity
@Table(name = "trials")
public class TrialEntity {

    @Id private UUID id;

    @Column(name = "protocol_number", nullable = false)
    private String protocolNumber;

    @Column(name = "ctri_number")
    private String ctriNumber;

    @Column(nullable = false)
    private String title;

    @Column(name = "short_title")
    private String shortTitle;

    @Column(name = "sponsor_institution_id", nullable = false)
    private UUID sponsorInstitutionId;

    @Column(nullable = false)
    private String phase;

    @Column(name = "therapeutic_area")
    private String therapeuticArea;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrialStatus status;

    @Column(name = "target_enrollment")
    private Integer targetEnrollment;

    @Column(name = "current_enrollment", nullable = false)
    private int currentEnrollment;

    @Column(name = "regulatory_status", nullable = false)
    private String regulatoryStatus;

    /**
     * Optimistic lock (§14.4).
     *
     * <p>Hibernate's {@code @Version} turns a stale write into an
     * {@code OptimisticLockingFailureException} rather than a silent overwrite, which is what
     * the API surfaces as 409. Two coordinators editing the same trial is routine; last-write-
     * wins would discard one of them without telling anyone.
     */
    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected TrialEntity() {} // JPA

    public TrialEntity(
            UUID id,
            String protocolNumber,
            String title,
            UUID sponsorInstitutionId,
            String phase,
            UUID createdBy) {
        this.id = id;
        this.protocolNumber = protocolNumber;
        this.title = title;
        this.sponsorInstitutionId = sponsorInstitutionId;
        this.phase = phase;
        this.status = TrialStatus.DRAFT;
        this.regulatoryStatus = "NOT_SUBMITTED";
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public String getProtocolNumber() {
        return protocolNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getShortTitle() {
        return shortTitle;
    }

    public UUID getSponsorInstitutionId() {
        return sponsorInstitutionId;
    }

    public String getPhase() {
        return phase;
    }

    public String getTherapeuticArea() {
        return therapeuticArea;
    }

    public TrialStatus getStatus() {
        return status;
    }

    public Integer getTargetEnrollment() {
        return targetEnrollment;
    }

    public int getCurrentEnrollment() {
        return currentEnrollment;
    }

    public int getVersion() {
        return version;
    }

    void rename(String title, String shortTitle, String therapeuticArea, UUID by) {
        if (title != null) {
            this.title = title;
        }
        if (shortTitle != null) {
            this.shortTitle = shortTitle;
        }
        if (therapeuticArea != null) {
            this.therapeuticArea = therapeuticArea;
        }
        this.updatedBy = by;
    }

    void setTargetEnrollment(Integer target) {
        this.targetEnrollment = target;
    }

    /** @throws IllegalTrialTransitionException if §20.2 does not permit the move */
    void transitionTo(TrialStatus next, UUID by) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalTrialTransitionException(status, next);
        }
        this.status = next;
        this.updatedBy = by;
    }
}
