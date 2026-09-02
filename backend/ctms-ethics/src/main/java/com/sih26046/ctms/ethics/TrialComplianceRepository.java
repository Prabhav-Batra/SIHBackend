package com.sih26046.ctms.ethics;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TrialComplianceRepository extends JpaRepository<TrialComplianceEntity, UUID> {

    List<TrialComplianceEntity> findAllByTrialIdOrderByCreatedAt(UUID trialId);

    Optional<TrialComplianceEntity> findByIdAndTrialId(UUID id, UUID trialId);

    /**
     * The rollup, grouped in the database rather than in Java.
     *
     * <p>The join to {@code compliance_requirements} is what supplies {@code is_mandatory}, and
     * it is safe under RLS: the catalogue is readable by every authenticated session, so the
     * join drops no row that {@code trial_compliance}'s own policy would have kept.
     */
    @Query(
            value =
                    """
                    SELECT tc.status AS status,
                           r.is_mandatory AS mandatory,
                           count(*) AS total
                    FROM trial_compliance tc
                    JOIN compliance_requirements r ON r.id = tc.compliance_requirement_id
                    WHERE tc.trial_id = :trialId
                    GROUP BY tc.status, r.is_mandatory
                    """,
            nativeQuery = true)
    List<StatusTally> tallyByTrial(UUID trialId);

    /** Projection for {@link #tallyByTrial}. */
    interface StatusTally {
        String getStatus();

        boolean getMandatory();

        long getTotal();
    }
}
