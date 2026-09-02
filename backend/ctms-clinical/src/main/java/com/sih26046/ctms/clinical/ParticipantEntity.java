package com.sih26046.ctms.clinical;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A trial participant, pseudonymised (§8.11).
 *
 * <p>There is no name, no contact detail and no full date of birth on this entity, and that is
 * the design rather than an omission (ADR-011). Everything that identifies a person lives in
 * {@code participant_identities}; every analytics query, GIS aggregate and export reads this
 * class and never that one, which makes the privacy guarantee a property of which tables a
 * code path opens rather than of how carefully its queries were written.
 */
@Entity
@Table(name = "participants")
public class ParticipantEntity {

    @Id private UUID id;

    @Column(name = "trial_id", nullable = false)
    private UUID trialId;

    @Column(name = "trial_site_id", nullable = false)
    private UUID trialSiteId;

    @Column(name = "subject_code", nullable = false)
    private String subjectCode;

    @Column(name = "screening_number")
    private String screeningNumber;

    @Column(name = "enrollment_date", nullable = false)
    private LocalDate enrollmentDate;

    @Column(name = "randomization_arm")
    private String randomizationArm;

    @Column(nullable = false)
    private String status;

    @Column(name = "withdrawal_date")
    private LocalDate withdrawalDate;

    @Column(name = "withdrawal_reason")
    private String withdrawalReason;

    /** Year only — age stratification without a re-identifying date (§8.11). */
    @Column(name = "date_of_birth_year")
    private Integer dateOfBirthYear;

    private String sex;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_by")
    private UUID createdBy;

    protected ParticipantEntity() {} // JPA

    public ParticipantEntity(
            UUID id,
            UUID trialId,
            UUID trialSiteId,
            String subjectCode,
            Integer dateOfBirthYear,
            String sex,
            UUID createdBy) {
        this.id = id;
        this.trialId = trialId;
        this.trialSiteId = trialSiteId;
        this.subjectCode = subjectCode;
        this.enrollmentDate = LocalDate.now();
        this.status = "ENROLLED";
        this.dateOfBirthYear = dateOfBirthYear;
        this.sex = sex;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTrialId() {
        return trialId;
    }

    public UUID getTrialSiteId() {
        return trialSiteId;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public LocalDate getEnrollmentDate() {
        return enrollmentDate;
    }

    public String getStatus() {
        return status;
    }

    public Integer getDateOfBirthYear() {
        return dateOfBirthYear;
    }

    public String getSex() {
        return sex;
    }

    public int getVersion() {
        return version;
    }

    /** §20.3 — withdrawal stops new data; it never removes what was already collected. */
    void withdraw(String reason) {
        this.status = "WITHDRAWN";
        this.withdrawalDate = LocalDate.now();
        this.withdrawalReason = reason;
    }

    public boolean isWithdrawn() {
        return "WITHDRAWN".equals(status);
    }
}
