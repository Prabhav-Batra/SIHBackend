package com.sih26046.ctms.trials;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Trials the caller may see.
 *
 * <p>There is no scope predicate in any query here, and that is the point: row-level security
 * applies it in the database (§7.5). A findAll() returns the caller's trials because the
 * database will not return anything else.
 */
public interface TrialRepository extends JpaRepository<TrialEntity, UUID> {

    List<TrialEntity> findAllByOrderByProtocolNumber();

    Optional<TrialEntity> findByProtocolNumber(String protocolNumber);
}
