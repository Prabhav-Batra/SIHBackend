package com.sih26046.ctms.documents;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findAllByTrialIdOrderByUploadedAtDesc(UUID trialId);

    List<DocumentEntity> findAllByDocumentFamilyIdOrderByVersion(UUID documentFamilyId);
}
