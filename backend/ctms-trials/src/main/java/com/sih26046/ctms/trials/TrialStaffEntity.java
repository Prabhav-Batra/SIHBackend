package com.sih26046.ctms.trials;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A staff assignment (§8.10) — the table every scoped policy resolves through.
 *
 * <p>Assignments end, they are not deleted: who worked on a trial is part of its history, and
 * no DELETE privilege is granted on this table at all.
 */
@Entity
@Table(name = "trial_staff")
public class TrialStaffEntity {

    @Id private UUID id;

    @Column(name = "trial_id", nullable = false)
    private UUID trialId;

    /** {@code NULL} means trial-wide: a principal investigator spans every site (§8.10). */
    @Column(name = "trial_site_id")
    private UUID trialSiteId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "staff_role", nullable = false)
    private String staffRole;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    protected TrialStaffEntity() {} // JPA

    public TrialStaffEntity(UUID id, UUID trialId, UUID trialSiteId, UUID userId, String staffRole) {
        this.id = id;
        this.trialId = trialId;
        this.trialSiteId = trialSiteId;
        this.userId = userId;
        this.staffRole = staffRole;
        this.startDate = LocalDate.now();
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

    public UUID getUserId() {
        return userId;
    }

    public String getStaffRole() {
        return staffRole;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    /**
     * Ends the assignment today.
     *
     * <p>§8.10: a past end date removes access immediately, because
     * {@code app.active_assignments()} filters on it. Access revocation and history retention
     * are the same act.
     */
    void end() {
        this.endDate = LocalDate.now();
    }
}
