package com.sih26046.ctms.trials;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrialSiteRepository extends JpaRepository<TrialSiteEntity, UUID> {

    /**
     * Sites of a trial.
     *
     * <p>The trial filter narrows the result; it does not secure it. A caller with no
     * assignment sees an empty list for any trial id they pass, because RLS has already
     * removed the rows (§7.5).
     */
    List<TrialSiteEntity> findAllByTrialIdOrderBySiteCode(UUID trialId);
}
