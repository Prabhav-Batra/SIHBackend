package com.sih26046.ctms.clinical;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicationRepository extends JpaRepository<MedicationEntity, UUID> {
    List<MedicationEntity> findAllByParticipantIdOrderByStartDate(UUID participantId);
}
