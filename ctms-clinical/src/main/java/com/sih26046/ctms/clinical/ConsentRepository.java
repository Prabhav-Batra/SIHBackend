package com.sih26046.ctms.clinical;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRepository extends JpaRepository<ConsentEntity, UUID> {

    List<ConsentEntity> findAllByParticipantId(UUID participantId);

    Optional<ConsentEntity> findFirstByParticipantIdAndStatus(UUID participantId, String status);
}
