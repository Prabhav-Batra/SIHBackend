package com.sih26046.ctms.clinical;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The only way into {@code participant_identities}.
 *
 * <p>Deliberately minimal: no list, no search, no join. Every method that could return more
 * than one identity at a time is a re-identification surface, and there is no requirement for
 * one (§8.12).
 */
public interface ParticipantIdentityRepository
        extends JpaRepository<ParticipantIdentityEntity, UUID> {}
