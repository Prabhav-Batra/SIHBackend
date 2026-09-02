package com.sih26046.ctms.clinical;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservationRepository extends JpaRepository<ObservationEntity, UUID> {
    List<ObservationEntity> findAllByVisitIdOrderByObservationCode(UUID visitId);
}
