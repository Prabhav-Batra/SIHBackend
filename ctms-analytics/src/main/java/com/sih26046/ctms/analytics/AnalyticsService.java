package com.sih26046.ctms.analytics;

import com.sih26046.ctms.security.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §21.4, §23 — one dashboard endpoint, seven shapes.
 *
 * <p>The trial-scoped widgets read {@code mv_trial_rollup} (V25) joined to {@code trials}:
 * RLS on {@code trials} does the scoping — a materialized view carries no policies of its own,
 * so the join to a real, protected table is what stands between this class and every trial's
 * numbers leaking to everyone (§4.1's ordinary mechanism, not a new one). Everything else here
 * queries its base table directly, exactly as every other controller in this codebase does.
 *
 * <p>RLS identity binds only when a Spring-managed transaction begins (§4.1) — the same
 * requirement B7's {@code GisService} documents at length. See {@link #dashboard} for why that
 * annotation sits there rather than on the method that does the actual querying.
 */
@Service
public class AnalyticsService {

    private final JdbcTemplate jdbc;
    private final CacheProvider cache;

    public AnalyticsService(JdbcTemplate jdbc, CacheProvider cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    /**
     * Cached per caller for §23.8's 60–120 s window; a stale card is not a stale record.
     *
     * <p>{@code @Transactional} lives here, not on {@link #compute}. {@code compute} is
     * invoked from the cache-miss lambda below as a plain self-call — {@code this.compute(...)},
     * never through the Spring proxy — so an annotation on it alone would be silently ignored
     * (the classic self-invocation gap) and every RLS-scoped query inside would run with no
     * identity bound (§4.1), returning zero rows for every role, silently. Starting the
     * transaction here, before the cache is even consulted, means it is already active by the
     * time {@code compute} runs on a miss.
     */
    @Transactional(readOnly = true)
    public Object dashboard(CurrentUser caller) {
        return cache.get("dashboard:" + caller.userId(), () -> compute(caller));
    }

    private Object compute(CurrentUser caller) {
        return switch (caller.roleName()) {
            case "SYSTEM_ADMIN" -> adminDashboard();
            case "PRINCIPAL_INVESTIGATOR" -> new AnalyticsDtos.InvestigatorDashboard(
                    "INVESTIGATOR", trialRollups());
            case "TRIAL_COORDINATOR" -> coordinatorDashboard();
            case "RESEARCH_STAFF" -> researchDashboard();
            case "ETHICS_MEMBER" -> ethicsDashboard(caller);
            case "SAFETY_OFFICER" -> safetyDashboard();
            case "REGULATORY_OFFICER" -> regulatoryDashboard();
            default -> Map.of("dashboardType", "UNKNOWN");
        };
    }

    // ── per-role widgets ─────────────────────────────────────────────────────

    private AnalyticsDtos.AdminDashboard adminDashboard() {
        Map<String, Object> users =
                jdbc.queryForMap(
                        """
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                               count(*) FILTER (WHERE status = 'LOCKED') AS locked
                        FROM users
                        """);
        long institutions = count("SELECT count(*) FROM institutions");
        long activeTrials = count("SELECT count(*) FROM trials WHERE status = 'ACTIVE'");
        long alerts =
                count(
                        "SELECT count(*) FROM audit_logs WHERE outcome <> 'SUCCESS' AND"
                                + " occurred_at >= now() - interval '24 hours'");
        return new AnalyticsDtos.AdminDashboard(
                "ADMIN",
                ((Number) users.get("total")).longValue(),
                ((Number) users.get("active")).longValue(),
                ((Number) users.get("locked")).longValue(),
                institutions,
                activeTrials,
                alerts);
    }

    private AnalyticsDtos.CoordinatorDashboard coordinatorDashboard() {
        long today = count(TODAYS_VISITS_SQL);
        long missed =
                count(
                        "SELECT count(*) FROM visits WHERE status = 'SCHEDULED' AND"
                                + " scheduled_date < CURRENT_DATE");
        return new AnalyticsDtos.CoordinatorDashboard("COORDINATOR", today, missed, trialRollups());
    }

    private AnalyticsDtos.ResearchDashboard researchDashboard() {
        long today = count(TODAYS_VISITS_SQL);
        long participants = count("SELECT count(*) FROM participants WHERE status <> 'WITHDRAWN'");
        return new AnalyticsDtos.ResearchDashboard("RESEARCH", today, participants);
    }

    private static final String TODAYS_VISITS_SQL =
            "SELECT count(*) FROM visits WHERE scheduled_date = CURRENT_DATE AND status ="
                    + " 'SCHEDULED'";

    private AnalyticsDtos.EthicsDashboard ethicsDashboard(CurrentUser caller) {
        long pending =
                count(
                        "SELECT count(*) FROM ethics_submissions WHERE institution_id ="
                                + " app.current_institution_id() AND status IN ('SUBMITTED',"
                                + " 'UNDER_REVIEW')");
        long decided =
                count(
                        "SELECT count(*) FROM ethics_submissions WHERE institution_id ="
                            + " app.current_institution_id() AND decision_date >= CURRENT_DATE -"
                            + " 30");
        return new AnalyticsDtos.EthicsDashboard("ETHICS", pending, decided);
    }

    private AnalyticsDtos.SafetyDashboard safetyDashboard() {
        long pending =
                count("SELECT count(*) FROM adverse_events WHERE status IN ('REPORTED', 'UNDER_REVIEW')");
        long openSerious =
                count(
                        "SELECT count(*) FROM adverse_events WHERE seriousness = 'SERIOUS' AND"
                                + " status <> 'CLOSED'");
        long expeditedOverdue =
                count(
                        "SELECT count(*) FROM safety_reviews WHERE requires_expedited_reporting"
                                + " AND reported_to_authority_at IS NULL");
        return new AnalyticsDtos.SafetyDashboard("SAFETY", pending, openSerious, expeditedOverdue);
    }

    private AnalyticsDtos.RegulatoryDashboard regulatoryDashboard() {
        long institutions = count("SELECT count(*) FROM institutions");
        long trials = count("SELECT count(*) FROM trials");
        long sites = count("SELECT count(*) FROM trial_sites");
        List<AnalyticsDtos.TrialRollup> rollups = trialRollups();
        long compliant =
                rollups.stream()
                        .filter(
                                r ->
                                        r.complianceTotal() > 0
                                                && r.complianceCompliant() == r.complianceTotal())
                        .count();
        long noncompliant = rollups.size() - compliant;
        long noCurrentApproval = rollups.stream().filter(r -> !r.ethicsApprovedCurrent()).count();
        return new AnalyticsDtos.RegulatoryDashboard(
                "REGULATORY", institutions, trials, sites, compliant, noncompliant, noCurrentApproval);
    }

    /** The RLS-scoped join every trial-bearing widget above reuses (§4.1). */
    private List<AnalyticsDtos.TrialRollup> trialRollups() {
        return jdbc.query(
                """
                SELECT t.id, t.protocol_number, t.short_title, t.status,
                       r.current_enrollment, r.target_enrollment, r.site_count,
                       r.ae_total, r.ae_serious, r.ae_unreviewed,
                       r.compliance_total, r.compliance_compliant, r.compliance_mandatory_open,
                       r.ethics_approved_current
                FROM mv_trial_rollup r
                JOIN trials t ON t.id = r.trial_id
                ORDER BY t.protocol_number
                """,
                (rs, row) ->
                        new AnalyticsDtos.TrialRollup(
                                rs.getObject("id", java.util.UUID.class),
                                rs.getString("protocol_number"),
                                rs.getString("short_title"),
                                rs.getString("status"),
                                rs.getInt("current_enrollment"),
                                (Integer) rs.getObject("target_enrollment"),
                                rs.getLong("site_count"),
                                rs.getLong("ae_total"),
                                rs.getLong("ae_serious"),
                                rs.getLong("ae_unreviewed"),
                                rs.getLong("compliance_total"),
                                rs.getLong("compliance_compliant"),
                                rs.getLong("compliance_mandatory_open"),
                                rs.getBoolean("ethics_approved_current")));
    }

    private long count(String sql) {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0 : result;
    }
}
