package com.sih26046.ctms.clinical;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantRepository extends JpaRepository<ParticipantEntity, UUID> {

    List<ParticipantEntity> findAllByTrialIdOrderBySubjectCode(UUID trialId);

    /**
     * Increments the trial's enrolment counter.
     *
     * <p>A single UPDATE rather than read-modify-write: two concurrent enrolments would
     * otherwise both read the same count and both write count+1, losing one. The database also
     * enforces ck_trials_enrollment_bounds here, so exceeding the target fails the statement
     * rather than being checked and then raced past (§14.2).
     */
    @Modifying
    @Query(
            value = "UPDATE trials SET current_enrollment = current_enrollment + 1"
                    + " WHERE id = :trialId",
            nativeQuery = true)
    void incrementTrialEnrollment(@Param("trialId") UUID trialId);

    @Modifying
    @Query(
            value = "UPDATE trial_sites SET current_enrollment = current_enrollment + 1"
                    + " WHERE id = :siteId",
            nativeQuery = true)
    void incrementSiteEnrollment(@Param("siteId") UUID siteId);

    @Query(value = "SELECT status FROM trials WHERE id = :trialId", nativeQuery = true)
    Optional<String> trialStatus(@Param("trialId") UUID trialId);
}
