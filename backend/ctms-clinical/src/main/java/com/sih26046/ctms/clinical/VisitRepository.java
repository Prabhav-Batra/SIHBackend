package com.sih26046.ctms.clinical;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitRepository extends JpaRepository<VisitEntity, UUID> {
    List<VisitEntity> findAllByParticipantIdOrderByVisitNumber(UUID participantId);
}
