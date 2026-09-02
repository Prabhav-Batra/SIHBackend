package com.sih26046.ctms.ethics;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EthicsSubmissionRepository extends JpaRepository<EthicsSubmissionEntity, UUID> {

    List<EthicsSubmissionEntity> findAllByTrialIdOrderBySubmittedAtDesc(UUID trialId);

    List<EthicsSubmissionEntity> findAllByInstitutionIdOrderBySubmittedAtDesc(UUID institutionId);

    List<EthicsSubmissionEntity> findAllByInstitutionIdAndStatusOrderBySubmittedAtDesc(
            UUID institutionId, String status);
}
