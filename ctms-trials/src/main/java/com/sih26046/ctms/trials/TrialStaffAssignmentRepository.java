package com.sih26046.ctms.trials;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Writes into the scope table every policy resolves through (§8.10). */
public interface TrialStaffAssignmentRepository extends JpaRepository<TrialEntity, UUID> {

    /**
     * Records a trial-wide assignment.
     *
     * <p>A native statement rather than an entity because trial_staff has no behaviour worth
     * mapping and is written in exactly two places.
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO trial_staff (trial_id, trial_site_id, user_id, staff_role)"
                            + " VALUES (:trialId, NULL, :userId, :staffRole)",
            nativeQuery = true)
    void assignTrialWide(
            @Param("trialId") UUID trialId,
            @Param("userId") UUID userId,
            @Param("staffRole") String staffRole);
}
