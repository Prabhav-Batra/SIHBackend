package com.sih26046.ctms.ethics;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplianceRequirementRepository
        extends JpaRepository<ComplianceRequirementEntity, UUID> {

    List<ComplianceRequirementEntity> findAllByStatusOrderByCode(String status);

    List<ComplianceRequirementEntity> findAllByCategoryAndStatusOrderByCode(
            String category, String status);
}
