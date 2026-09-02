package com.sih26046.ctms.safety;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyReviewRepository extends JpaRepository<SafetyReviewEntity, UUID> {

    List<SafetyReviewEntity> findAllByAdverseEventIdOrderByReviewDateDesc(UUID adverseEventId);
}
