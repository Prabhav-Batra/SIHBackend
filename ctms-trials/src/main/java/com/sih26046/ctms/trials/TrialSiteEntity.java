package com.sih26046.ctms.trials;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A participating site of a trial (§8.9).
 *
 * <p>As with institutions, {@code location} is a generated column and is not mapped; site
 * coordinates are an override for a distinct campus, and the fallback to the institution's
 * position lives in the {@code trial_sites_located} view.
 */
@Entity
@Table(name = "trial_sites")
public class TrialSiteEntity {

    @Id private UUID id;

    @Column(name = "trial_id", nullable = false)
    private UUID trialId;

    @Column(name = "institution_id", nullable = false)
    private UUID institutionId;

    @Column(name = "site_code", nullable = false)
    private String siteCode;

    @Column(nullable = false)
    private String status;

    @Column(name = "activation_date")
    private LocalDate activationDate;

    @Column(name = "target_enrollment")
    private Integer targetEnrollment;

    @Column(name = "current_enrollment", nullable = false)
    private int currentEnrollment;

    private BigDecimal latitude;

    private BigDecimal longitude;

    @Version
    @Column(nullable = false)
    private int version;

    protected TrialSiteEntity() {} // JPA

    public TrialSiteEntity(UUID id, UUID trialId, UUID institutionId, String siteCode) {
        this.id = id;
        this.trialId = trialId;
        this.institutionId = institutionId;
        this.siteCode = siteCode;
        this.status = "PLANNED";
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

    public String getSiteCode() {
        return siteCode;
    }

    public String getStatus() {
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

    void setStatus(String status) {
        this.status = status;
    }

    void setTargetEnrollment(Integer targetEnrollment) {
        this.targetEnrollment = targetEnrollment;
    }
}
