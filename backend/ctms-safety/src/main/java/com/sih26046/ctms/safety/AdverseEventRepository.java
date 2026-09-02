package com.sih26046.ctms.safety;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdverseEventRepository extends JpaRepository<AdverseEventEntity, UUID> {

    List<AdverseEventEntity> findAllByParticipantIdOrderByOnsetDateDesc(UUID participantId);

    List<AdverseEventEntity> findAllByTrialIdOrderByOnsetDateDesc(UUID trialId);
}
