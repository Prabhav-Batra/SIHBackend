package com.sih26046.ctms.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to the audit trail (§19).
 *
 * <p>Plain JDBC rather than JPA, because {@code audit_logs} is append-only by construction: it
 * has no version column, no updated_at, and V12 revokes UPDATE from the application role
 * outright. An entity mapping would imply a mutability the table does not have.
 *
 * <p>{@code REQUIRES_NEW} is deliberate and is the opposite of the job queue's choice. A job
 * must roll back with the work that scheduled it; an audit record must not. "The caller was
 * authorised to download this file" stays true even if the response later fails, and the
 * security-relevant events are exactly the ones whose transactions do not always commit.
 */
@Service
public class AuditTrail {

    private final JdbcTemplate jdbc;

    public AuditTrail(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID userId, String action, String entityType, UUID entityId, UUID trialId) {
        jdbc.update(
                """
                INSERT INTO audit_logs (user_id, action, entity_type, entity_id, trial_id)
                VALUES (?, ?, ?, ?, ?)
                """,
                userId,
                action,
                entityType,
                entityId,
                trialId);
    }
}
