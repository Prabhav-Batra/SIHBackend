package com.sih26046.ctms.trials;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrialStaffRepository extends JpaRepository<TrialStaffEntity, UUID> {

    List<TrialStaffEntity> findAllByTrialIdAndEndDateIsNull(UUID trialId);
}
