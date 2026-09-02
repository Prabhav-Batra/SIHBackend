package com.sih26046.ctms.gis;

import com.sih26046.ctms.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one GIS API every role shares (§1.3, §10.5).
 *
 * <p>Class-level {@code gis:read} covers the base map and aggregates, which every role holds
 * (§6.3) — that is what makes the map global. Drill-down narrows to {@code gis:drilldown} per
 * method, and narrows again inside {@link GisService#siteDetail} by RLS scope and by which
 * finer permissions the caller actually holds (§11.3).
 */
@RestController
@RequestMapping("/api/v1/gis")
@PreAuthorize("hasAuthority('gis:read')")
public class GisController {

    private final GisService gis;

    public GisController(GisService gis) {
        this.gis = gis;
    }

    @GetMapping("/institutions")
    public GisDtos.FeatureCollection<GisDtos.InstitutionProperties> institutions() {
        return gis.institutions();
    }

    @GetMapping("/sites")
    public GisDtos.FeatureCollection<GisDtos.SiteMarkerProperties> sites(
            @RequestParam(required = false) String bbox,
            @RequestParam(required = false) UUID trialId,
            @RequestParam(required = false) String status) {
        return gis.sites(parseBbox(bbox), Optional.ofNullable(trialId), Optional.ofNullable(status));
    }

    @GetMapping("/clusters")
    public GisDtos.FeatureCollection<GisDtos.ClusterProperties> clusters(
            @RequestParam String bbox, @RequestParam(defaultValue = "5") int zoom) {
        return gis.clusters(requireBbox(bbox), zoom);
    }

    @GetMapping("/aggregates")
    public GisDtos.AggregatesResponse aggregates(@RequestParam String level) {
        try {
            return gis.aggregates(level);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
    }

    /**
     * The only endpoint gated on {@code gis:drilldown} (§10.5). A site outside the caller's
     * RLS scope and a site that does not exist are the same 404 (§6.4) — {@link
     * GisService#siteDetail} does not tell them apart, and neither does this method.
     */
    @GetMapping("/sites/{id}/detail")
    @PreAuthorize("hasAuthority('gis:drilldown')")
    public GisDtos.SiteDetail siteDetail(
            @PathVariable UUID id, @AuthenticationPrincipal CurrentUser caller) {
        return gis.siteDetail(id, caller)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
    }

    private Optional<BoundingBox> parseBbox(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(requireBbox(raw));
    }

    private BoundingBox requireBbox(String raw) {
        try {
            return BoundingBox.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
