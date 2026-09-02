package com.sih26046.ctms.gis;

import com.sih26046.ctms.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §10, §11 — one GIS subsystem, one implementation.
 *
 * <p>The base map and Level-1 aggregates (§11.3) read through {@code app.gis_site_markers()}
 * and {@code app.gis_area_aggregates()} (V23) — narrow, SECURITY DEFINER functions that expose
 * exactly the fields §11.2 lists as public, deliberately global rather than scoped by the
 * caller's trial assignment. Level 2/3 drill-down ({@link #siteDetail}) does the opposite: it
 * queries {@code trial_sites} directly, under the caller's own RLS, so a site outside their
 * scope is genuinely absent rather than merely hidden by this class.
 *
 * <p>Filtering the marker layer by viewport, trial, or status happens here in Java rather than
 * in SQL. That is safe only because these are plain public fields with nothing to suppress —
 * the k-anonymity suppression in {@link #aggregates} is the opposite case, and stays in SQL for
 * exactly the reason §11.4 gives: the true value must never exist in Java memory or a log line
 * on its way to being hidden.
 *
 * <p>Every public method is {@code @Transactional}, and that is load-bearing rather than
 * decorative: {@code RlsAwareTransactionManager} binds {@code app.current_user_id} only when a
 * Spring-managed transaction begins (§4.1). A bare {@code JdbcTemplate} call with no
 * surrounding transaction gets a connection with the GUC unset, and every policy — and the
 * {@code IS NOT NULL} guard in V23's own functions — then evaluates to false. The result is
 * not an error, just an empty map, which is exactly the failure this annotation exists to
 * prevent from being silent.
 */
@Service
public class GisService {

    private final JdbcTemplate jdbc;
    private final GisProperties properties;

    public GisService(JdbcTemplate jdbc, GisProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    // ── Level 0: base map ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public GisDtos.FeatureCollection<GisDtos.InstitutionProperties> institutions() {
        List<GisDtos.Feature<GisDtos.InstitutionProperties>> features =
                jdbc.query(
                        """
                        SELECT id, name, institution_type, city, state, country,
                               has_ethics_committee, status, latitude, longitude
                        FROM institutions
                        WHERE latitude IS NOT NULL
                        """,
                        (rs, row) ->
                                GisDtos.Feature.of(
                                        rs.getDouble("longitude"),
                                        rs.getDouble("latitude"),
                                        new GisDtos.InstitutionProperties(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("name"),
                                                rs.getString("institution_type"),
                                                rs.getString("city"),
                                                rs.getString("state"),
                                                rs.getString("country"),
                                                rs.getBoolean("has_ethics_committee"),
                                                rs.getString("status"))));
        return GisDtos.FeatureCollection.of(features);
    }

    @Transactional(readOnly = true)
    public GisDtos.FeatureCollection<GisDtos.SiteMarkerProperties> sites(
            Optional<BoundingBox> bbox, Optional<UUID> trialId, Optional<String> status) {

        List<GisDtos.Feature<GisDtos.SiteMarkerProperties>> features =
                allMarkers().stream()
                        .filter(m -> bbox.isEmpty() || bbox.get().contains(m.longitude(), m.latitude()))
                        .filter(m -> trialId.isEmpty() || trialId.get().equals(m.trialId()))
                        .filter(m -> status.isEmpty() || status.get().equals(m.status()))
                        .map(
                                m ->
                                        GisDtos.Feature.of(
                                                m.longitude(),
                                                m.latitude(),
                                                new GisDtos.SiteMarkerProperties(
                                                        m.siteId(),
                                                        m.trialId(),
                                                        m.siteCode(),
                                                        m.status(),
                                                        m.institutionId(),
                                                        m.institutionName(),
                                                        m.city(),
                                                        m.state())))
                        .toList();
        return GisDtos.FeatureCollection.of(features);
    }

    private record Marker(
            UUID siteId,
            UUID trialId,
            String siteCode,
            String status,
            UUID institutionId,
            String institutionName,
            String city,
            String state,
            double latitude,
            double longitude) {}

    private List<Marker> allMarkers() {
        return jdbc.query(
                "SELECT * FROM app.gis_site_markers()",
                (rs, row) ->
                        new Marker(
                                rs.getObject("site_id", UUID.class),
                                rs.getObject("trial_id", UUID.class),
                                rs.getString("site_code"),
                                rs.getString("status"),
                                rs.getObject("institution_id", UUID.class),
                                rs.getString("institution_name"),
                                rs.getString("city"),
                                rs.getString("state"),
                                rs.getDouble("latitude"),
                                rs.getDouble("longitude")));
    }

    // ── clustering, §10.3 ────────────────────────────────────────────────────

    /**
     * Server-side clustering. {@code eps} is degrees of longitude/latitude, chosen from the
     * requested zoom level with a simple halving-per-zoom heuristic — a UX tuning knob, not a
     * correctness concern, and one a frontend can always override by asking for a tighter
     * viewport instead.
     */
    @Transactional(readOnly = true)
    public GisDtos.FeatureCollection<GisDtos.ClusterProperties> clusters(
            BoundingBox bbox, int zoom) {

        double eps = Math.max(0.01, 20.0 / Math.pow(2, Math.max(0, zoom)));

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT cluster_id,
                               count(*)                              AS member_count,
                               avg(longitude)                        AS centroid_lon,
                               avg(latitude)                         AS centroid_lat,
                               (array_agg(site_id))[1]               AS sample_site_id,
                               min(site_code)                        AS sample_site_code,
                               min(status)                           AS sample_status,
                               min(institution_name)                 AS sample_institution
                        FROM (
                            SELECT *,
                                   ST_ClusterDBSCAN(
                                       ST_SetSRID(ST_MakePoint(longitude, latitude), 4326),
                                       eps := ?, minpoints := 1) OVER () AS cluster_id
                            FROM app.gis_site_markers()
                            WHERE longitude BETWEEN ? AND ? AND latitude BETWEEN ? AND ?
                        ) marked
                        GROUP BY cluster_id
                        """,
                        eps,
                        bbox.west(),
                        bbox.east(),
                        bbox.south(),
                        bbox.north());

        List<GisDtos.Feature<GisDtos.ClusterProperties>> features =
                rows.stream()
                        .map(
                                row -> {
                                    long count = ((Number) row.get("member_count")).longValue();
                                    boolean single = count == 1;
                                    return GisDtos.Feature.of(
                                            ((Number) row.get("centroid_lon")).doubleValue(),
                                            ((Number) row.get("centroid_lat")).doubleValue(),
                                            new GisDtos.ClusterProperties(
                                                    (int) count,
                                                    single ? (UUID) row.get("sample_site_id") : null,
                                                    single ? (String) row.get("sample_site_code") : null,
                                                    single ? (String) row.get("sample_status") : null,
                                                    single ? (String) row.get("sample_institution") : null));
                                })
                        .collect(Collectors.toList());
        return GisDtos.FeatureCollection.of(features);
    }

    // ── Level 1: aggregates ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public GisDtos.AggregatesResponse aggregates(String level) {
        if (!"state".equals(level) && !"city".equals(level)) {
            throw new IllegalArgumentException(
                    "level must be 'state' or 'city' (district is not implemented — no such"
                            + " column exists; see §10.5 in BACKEND_CONTEXT.md)");
        }
        List<GisDtos.AreaAggregate> areas =
                jdbc.query(
                        "SELECT * FROM app.gis_area_aggregates(?)",
                        (rs, row) -> {
                            boolean suppressed = rs.getBoolean("enrollment_suppressed");
                            long rawValue = rs.getLong("enrollment_value");
                            Long value = suppressed ? null : rawValue;
                            return new GisDtos.AreaAggregate(
                                    rs.getString("area"),
                                    rs.getLong("institution_count"),
                                    rs.getLong("site_count"),
                                    rs.getLong("trial_count"),
                                    new GisDtos.SuppressedValue(
                                            value,
                                            suppressed,
                                            suppressed
                                                    ? "<" + properties.kAnonymityThreshold()
                                                    : null),
                                    rs.getLong("compliance_total"),
                                    rs.getLong("compliance_compliant"),
                                    rs.getLong("compliance_mandatory_open"));
                        },
                        level);
        return new GisDtos.AggregatesResponse(level, areas);
    }

    // ── Level 2/3: drill-down ────────────────────────────────────────────────

    /**
     * One site's detail, or empty when the site does not exist or is outside the caller's RLS
     * scope — the two are indistinguishable on purpose (§6.4).
     *
     * <p>Unlike everything above, this reads {@code trial_sites} directly under the caller's
     * ordinary RLS, which is what makes an out-of-scope site genuinely invisible here — the
     * one place in this class where that matters.
     */
    @Transactional(readOnly = true)
    public Optional<GisDtos.SiteDetail> siteDetail(UUID siteId, CurrentUser caller) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT s.id AS site_id, s.trial_id, s.site_code, s.status,
                               s.current_enrollment, s.target_enrollment,
                               i.id AS institution_id, i.name AS institution_name,
                               i.city, i.state
                        FROM trial_sites s
                        JOIN institutions i ON i.id = s.institution_id
                        WHERE s.id = ?
                        """,
                        siteId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        UUID trialId = (UUID) row.get("trial_id");
        long enrollment = ((Number) row.get("current_enrollment")).longValue();

        // §11.4 protects a single named site the same way it protects a state: a role with
        // no clinical stake in this trial (Safety, Regulatory) sees it suppressed; a role
        // operating the trial (PI, Coordinator, Staff) already has this number through
        // /participants and gains nothing from having it hidden here.
        boolean rawEnrollmentVisible = caller.permissions().contains("participant:read");
        GisDtos.SuppressedValue enrollmentView =
                rawEnrollmentVisible
                        ? new GisDtos.SuppressedValue(enrollment, false, null)
                        : suppressedSingle(enrollment);

        GisDtos.ComplianceSummary compliance =
                caller.permissions().contains("compliance:read") ? complianceSummary(trialId) : null;

        Long adverseEventCount =
                caller.permissions().contains("adverse_event:read") && mayReadTrialSafety(trialId)
                        ? adverseEventCount(trialId)
                        : null;

        return Optional.of(
                new GisDtos.SiteDetail(
                        (UUID) row.get("site_id"),
                        trialId,
                        (String) row.get("site_code"),
                        (String) row.get("status"),
                        (UUID) row.get("institution_id"),
                        (String) row.get("institution_name"),
                        (String) row.get("city"),
                        (String) row.get("state"),
                        enrollmentView,
                        (Integer) row.get("target_enrollment"),
                        compliance,
                        adverseEventCount));
    }

    private GisDtos.SuppressedValue suppressedSingle(long enrollment) {
        boolean suppressed = enrollment < properties.kAnonymityThreshold();
        return new GisDtos.SuppressedValue(
                suppressed ? null : enrollment,
                suppressed,
                suppressed ? "<" + properties.kAnonymityThreshold() : null);
    }

    private GisDtos.ComplianceSummary complianceSummary(UUID trialId) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        """
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE tc.status = 'COMPLIANT') AS compliant,
                               count(*) FILTER (
                                   WHERE tc.status IN ('PENDING','IN_PROGRESS','NON_COMPLIANT')
                                     AND cr.is_mandatory) AS mandatory_open
                        FROM trial_compliance tc
                        JOIN compliance_requirements cr ON cr.id = tc.compliance_requirement_id
                        WHERE tc.trial_id = ?
                        """,
                        trialId);
        return new GisDtos.ComplianceSummary(
                ((Number) row.get("total")).longValue(),
                ((Number) row.get("compliant")).longValue(),
                ((Number) row.get("mandatory_open")).longValue());
    }

    /** V24: whether an RLS-scoped query would see anything, before running one. */
    private boolean mayReadTrialSafety(UUID trialId) {
        Boolean may =
                jdbc.queryForObject(
                        "SELECT app.gis_may_see_trial_safety(?)", Boolean.class, trialId);
        return Boolean.TRUE.equals(may);
    }

    private long adverseEventCount(UUID trialId) {
        Long count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM adverse_events WHERE trial_id = ?",
                        Long.class,
                        trialId);
        return count == null ? 0 : count;
    }
}
