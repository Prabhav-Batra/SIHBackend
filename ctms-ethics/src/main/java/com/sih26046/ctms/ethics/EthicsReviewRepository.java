package com.sih26046.ctms.ethics;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EthicsReviewRepository extends JpaRepository<EthicsReviewEntity, UUID> {

    List<EthicsReviewEntity> findAllByEthicsSubmissionIdOrderByReviewDateDesc(UUID submissionId);
}
