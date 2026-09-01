package com.sih26046.ctms.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

    Optional<SessionEntity> findByTokenHash(String tokenHash);

    List<SessionEntity> findAllByFamilyIdAndRevokedAtIsNull(UUID familyId);
}
