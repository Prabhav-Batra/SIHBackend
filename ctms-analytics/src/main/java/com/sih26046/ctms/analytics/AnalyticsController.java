package com.sih26046.ctms.analytics;

import com.sih26046.ctms.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §21.3, §21.4 — dashboards and the audit trail.
 *
 * <p>{@code /trials/{id}/compliance} is deliberately absent: {@code
 * ComplianceController#summary} already serves it, and duplicating that query here would be a
 * second place for its RLS scope and its "mandatory outstanding" definition to drift apart.
 */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final JdbcTemplate jdbc;

    public AnalyticsController(AnalyticsService analytics, JdbcTemplate jdbc) {
        this.analytics = analytics;
        this.jdbc = jdbc;
    }

    /** One endpoint, seven payload shapes (§21.4) — the role on the session decides which. */
    @GetMapping("/analytics/dashboard")
    public Object dashboard(@AuthenticationPrincipal CurrentUser caller) {
        return analytics.dashboard(caller);
    }

    /**
     * Cumulative enrolment by date. Queries {@code participants} directly under the caller's
     * own RLS — a live per-trial read, not the national rollup {@link AnalyticsService} uses,
     * so an out-of-scope trial simply returns no rows rather than needing its own check.
     */
    @GetMapping("/analytics/trials/{id}/enrollment")
    @PreAuthorize("hasAuthority('trial:read')")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> enrollment(@PathVariable UUID id) {
        return jdbc.queryForList(
                """
                SELECT enrollment_date AS date, count(*) AS enrolled
                FROM participants WHERE trial_id = ? AND status <> 'WITHDRAWN'
                GROUP BY enrollment_date ORDER BY enrollment_date
                """,
                id);
    }

    /** Aggregate safety trend for one trial — never a narrative, only counts (§11.2). */
    @GetMapping("/analytics/trials/{id}/safety")
    @PreAuthorize("hasAuthority('trial:read')")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> safety(@PathVariable UUID id) {
        return jdbc.queryForList(
                """
                SELECT onset_date AS date, severity, seriousness, count(*) AS total
                FROM adverse_events WHERE trial_id = ?
                GROUP BY onset_date, severity, seriousness ORDER BY onset_date
                """,
                id);
    }

    /**
     * Read-only audit trail (§19.6). No mutations exist on this table by construction (§7.8);
     * every filter is optional and RLS (§8.24's {@code audit_logs_read}) — not this method —
     * decides which rows a given caller's filters can ever match.
     */
    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('audit:read')")
    @Transactional(readOnly = true)
    public List<AnalyticsDtos.AuditEntry> audit(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return jdbc.query(
                """
                SELECT id, user_id, action, entity_type, entity_id, trial_id, outcome, occurred_at
                FROM audit_logs
                WHERE (CAST(? AS text) IS NULL OR entity_type = CAST(? AS text))
                  AND (CAST(? AS uuid) IS NULL OR entity_id = CAST(? AS uuid))
                  AND (CAST(? AS uuid) IS NULL OR user_id = CAST(? AS uuid))
                  AND (CAST(? AS timestamptz) IS NULL OR occurred_at >= CAST(? AS timestamptz))
                  AND (CAST(? AS timestamptz) IS NULL OR occurred_at <= CAST(? AS timestamptz))
                ORDER BY occurred_at DESC LIMIT 200
                """,
                (rs, row) ->
                        new AnalyticsDtos.AuditEntry(
                                rs.getObject("id", UUID.class),
                                rs.getObject("user_id", UUID.class),
                                rs.getString("action"),
                                rs.getString("entity_type"),
                                rs.getObject("entity_id", UUID.class),
                                rs.getObject("trial_id", UUID.class),
                                rs.getString("outcome"),
                                rs.getTimestamp("occurred_at").toInstant()),
                entityType,
                entityType,
                entityId,
                entityId,
                userId,
                userId,
                from,
                from,
                to,
                to);
    }
}
