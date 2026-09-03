package com.sih26046.ctms.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih26046.ctms.web.RequestIdFilter;
import java.util.Map;
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
 * <p>Two methods for two different guarantees, both real requirements of §19.1 but for different
 * event classes:
 *
 * <ul>
 *   <li>{@link #recordAccess} — {@code REQUIRES_NEW}, for §19.3's three audited <em>reads</em>
 *       (a document download, an identity re-identification, reading someone else's audit
 *       trail). "The caller was authorised to do this" stays true even if the response that
 *       followed later failed — the authorisation decision, not the response, is the event.
 *   <li>{@link #recordChange} — joins the caller's own transaction (plain {@code REQUIRED}), for
 *       §19.2's write events. Spec: "an audit entry cannot describe a change that rolled back,
 *       and a change cannot commit if its audit write failed" — only possible if both live or die
 *       together, which {@code REQUIRES_NEW} would not give.
 * </ul>
 */
@Service
public class AuditTrail {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditTrail(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAccess(
            UUID userId, String action, String entityType, UUID entityId, UUID trialId) {
        insert(userId, action, entityType, entityId, trialId, null, null, "SUCCESS");
    }

    /**
     * Retained under its original name for the one existing caller (document download).
     *
     * <p>Carries its own {@code @Transactional(REQUIRES_NEW)} rather than delegating to {@link
     * #recordAccess} via a plain {@code this} call — B8 already paid for this exact mistake
     * once (see BACKEND_CONTEXT.md): a self-invocation never goes through the Spring proxy, so
     * the annotation on the method it would have called is silently skipped, and the insert
     * runs inside whatever transaction the caller already has open. For a {@code
     * readOnly = true} caller like {@code DocumentController.download()}, that is exactly the
     * failure — "cannot execute INSERT in a read-only transaction" — this duplication exists to
     * avoid.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID userId, String action, String entityType, UUID entityId, UUID trialId) {
        insert(userId, action, entityType, entityId, trialId, null, null, "SUCCESS");
    }

    @Transactional
    public void recordChange(
            UUID userId,
            String action,
            String entityType,
            UUID entityId,
            UUID trialId,
            Map<String, ?> oldValues,
            Map<String, ?> newValues) {
        insert(
                userId,
                action,
                entityType,
                entityId,
                trialId,
                Redaction.redact(oldValues),
                Redaction.redact(newValues),
                "SUCCESS");
    }

    /** A write attempt that the database or a policy refused (§19.6's "failed access" query). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(UUID userId, String action, String entityType) {
        insert(userId, action, entityType, null, null, null, null, "DENIED");
    }

    private void insert(
            UUID userId,
            String action,
            String entityType,
            UUID entityId,
            UUID trialId,
            Map<String, ?> oldValues,
            Map<String, ?> newValues,
            String outcome) {
        String requestId = RequestIdFilter.current();
        jdbc.update(
                """
                INSERT INTO audit_logs
                    (user_id, action, entity_type, entity_id, trial_id,
                     old_values, new_values, request_id, outcome)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::uuid, ?)
                """,
                userId,
                action,
                entityType,
                entityId,
                trialId,
                toJson(oldValues),
                toJson(newValues),
                requestId,
                outcome);
    }

    private String toJson(Map<String, ?> values) {
        if (values == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            // A field that fails to serialise must not take the write itself down with it —
            // the change already happened and needs its audit row more than it needs the diff.
            return "{\"error\":\"serialisation failed\"}";
        }
    }
}
