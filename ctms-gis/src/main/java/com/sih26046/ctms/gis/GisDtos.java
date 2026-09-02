package com.sih26046.ctms.gis;

import java.util.List;
import java.util.UUID;

/**
 * Response shapes for {@code /api/v1/gis} (§10.5).
 *
 * <p>GeoJSON for anything with a location, plain JSON for aggregates and drill-down — matching
 * §21.1's convention that spatial responses are GeoJSON and nothing else has to be.
 */
public final class GisDtos {

    private GisDtos() {}

    // ── GeoJSON envelope ─────────────────────────────────────────────────────

    public record Point(String type, double[] coordinates) {
        static Point of(double longitude, double latitude) {
            return new Point("Point", new double[] {longitude, latitude});
        }
    }

    public record Feature<P>(String type, Point geometry, P properties) {
        static <P> Feature<P> of(double longitude, double latitude, P properties) {
            return new Feature<>("Feature", Point.of(longitude, latitude), properties);
        }
    }

    public record FeatureCollection<P>(String type, List<Feature<P>> features) {
        static <P> FeatureCollection<P> of(List<Feature<P>> features) {
            return new FeatureCollection<>("FeatureCollection", features);
        }
    }

    // ── base map (§11.3 Level 0) ─────────────────────────────────────────────

    public record InstitutionProperties(
            UUID id,
            String name,
            String institutionType,
            String city,
            String state,
            String country,
            boolean hasEthicsCommittee,
            String status) {}

    public record SiteMarkerProperties(
            UUID id,
            UUID trialId,
            String siteCode,
            String status,
            UUID institutionId,
            String institutionName,
            String city,
            String state) {}

    // ── clustering, §10.3 ────────────────────────────────────────────────────

    /**
     * One marker on the clustered layer: either a single site (count == 1, the site fields
     * populated) or a collapsed group (count > 1, the site fields absent — a cluster of more
     * than one site has no single site identity to report).
     */
    public record ClusterProperties(
            int count,
            UUID siteId,
            String siteCode,
            String status,
            String institutionName) {}

    // ── aggregates (§11.3 Level 1, §11.4) ────────────────────────────────────

    /** The k-anonymity envelope app.suppress_small produces, unwrapped into Java. */
    public record SuppressedValue(Long value, boolean suppressed, String label) {}

    public record AreaAggregate(
            String area,
            long institutionCount,
            long siteCount,
            long trialCount,
            SuppressedValue enrollment,
            long complianceTotal,
            long complianceCompliant,
            long complianceMandatoryOpen) {}

    public record AggregatesResponse(String level, List<AreaAggregate> areas) {}

    // ── drill-down (§11.3 Level 2/3) ─────────────────────────────────────────

    public record ComplianceSummary(long total, long compliant, long mandatoryOpen) {}

    /**
     * A single site's detail. {@code compliance} is {@code null} when the caller holds no
     * {@code compliance:read}, and {@code adverseEventCount} is {@code null} when the caller
     * has no genuine row-level visibility into this trial's adverse events (§V24) — both
     * absent rather than zero, because a permission the caller does not hold is not the same
     * fact as a count of zero.
     */
    public record SiteDetail(
            UUID siteId,
            UUID trialId,
            String siteCode,
            String status,
            UUID institutionId,
            String institutionName,
            String city,
            String state,
            SuppressedValue enrollment,
            Integer targetEnrollment,
            ComplianceSummary compliance,
            Long adverseEventCount) {}
}
