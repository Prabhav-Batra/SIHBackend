package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * §10, §11 — the one GIS API every role shares, and its sharpest privacy tests.
 *
 * <p>The two claims this file exists to prove:
 *
 * <ul>
 *   <li>the base map and Level-1 aggregates are genuinely global — a Research Staff member
 *       scoped to one site still sees the whole national picture, because that layer reads
 *       through V23's SECURITY DEFINER functions rather than the ordinary clinical RLS the
 *       same tables carry for /sites and /compliance;
 *   <li>drill-down is the opposite — an out-of-scope site is a 404 there, and a role whose
 *       RBAC permission outruns its row-level access (REGULATORY_OFFICER on adverse_events,
 *       V24) gets an absent field rather than a misleading zero.
 * </ul>
 *
 * <p>Every fixture uses a randomly generated state name. The aggregate tests group by state,
 * and the shared Postgres container persists across test methods in this class — a fixed
 * state name would let one test's enrolment leak into another's tally.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GisApiIT extends ApiTestSupport {

    /** Two institutions, far enough apart that no plausible bbox contains both by accident. */
    private static final double DELHI_LAT = 28.6139;
    private static final double DELHI_LON = 77.2090;
    private static final double MUMBAI_LAT = 19.0760;
    private static final double MUMBAI_LON = 72.8777;

    private String state;
    private String cityA;
    private String cityB;
    private Cookie[] investigator;
    private UUID institutionA; // Delhi
    private UUID institutionB; // Mumbai
    private String trialA;
    private String siteA;
    private String trialB;
    private String siteB;

    @BeforeEach
    void seed() throws Exception {
        // A fresh suffix per test: the shared Postgres container persists across methods in
        // this class, and the aggregate tests group by exactly these city/state values — a
        // fixed name would let one test's enrolment leak into another's tally.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        state = "State-" + suffix;
        cityA = "Delhi-" + suffix;
        cityB = "Mumbai-" + suffix;
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");

        institutionA = createInstitution(state, cityA, DELHI_LAT, DELHI_LON);
        institutionB = createInstitution(state, cityB, MUMBAI_LAT, MUMBAI_LON);

        trialA = createTrial(institutionA);
        siteA = createSite(trialA, institutionA);
        activate(trialA);

        trialB = createTrial(institutionB);
        siteB = createSite(trialB, institutionB);
        activate(trialB);
    }

    // ── fixture helpers ──────────────────────────────────────────────────────

    private UUID createInstitution(String stateName, String city, double lat, double lon) {
        return UUID.fromString(
                ownerJdbc()
                        .queryForObject(
                                "INSERT INTO institutions (name, institution_type, city, state,"
                                    + " latitude, longitude) VALUES (?,'MEDICAL_COLLEGE',?,?,?,?)"
                                    + " RETURNING id",
                                String.class,
                                "GIS Institute " + UUID.randomUUID(),
                                city,
                                stateName,
                                lat,
                                lon));
    }

    private String createTrial(UUID institutionId) throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/trials")
                                        .cookie(investigator)
                                        .with(csrf(investigator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"protocolNumber":"%s","title":"GIS study",
                                                 "sponsorInstitutionId":"%s","phase":"III",
                                                 "targetEnrollment":100}
                                                """
                                                        .formatted(
                                                                "GIS-" + UUID.randomUUID(),
                                                                institutionId)))
                        .andExpect(status().isCreated())
                        .andReturn(),
                "$.id");
    }

    private String createSite(String trialId, UUID institutionId) throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/sites")
                                        .cookie(investigator)
                                        .with(csrf(investigator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"trialId":"%s","institutionId":"%s","siteCode":"%s"}
                                                """
                                                        .formatted(
                                                                trialId,
                                                                institutionId,
                                                                "S-"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 6))))
                        .andExpect(status().isCreated())
                        .andReturn(),
                "$.id");
    }

    /** Through the API, matching the other IT suites' shortcut: legal predecessors only. */
    private void activate(String trialId) throws Exception {
        advance(trialId, "PENDING_ETHICS");
        advance(trialId, "APPROVED");
        advance(trialId, "ACTIVE");
    }

    private void advance(String trialId, String status) throws Exception {
        String etag =
                mockMvc.perform(get("/api/v1/trials/" + trialId).cookie(investigator))
                        .andReturn()
                        .getResponse()
                        .getHeader("ETag");
        mockMvc.perform(
                        post("/api/v1/trials/" + trialId + "/status")
                                .cookie(investigator)
                                .with(csrf(investigator))
                                .header("If-Match", etag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"%s\"}".formatted(status)))
                .andExpect(status().isOk());
    }

    private void setEnrollment(String siteId, int count) {
        ownerJdbc()
                .update("UPDATE trial_sites SET current_enrollment = ? WHERE id = ?::uuid", count, siteId);
    }

    private String enrolParticipant(String trialId, String siteId) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/participants")
                                        .cookie(investigator)
                                        .with(csrf(investigator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"trialId":"%s","trialSiteId":"%s","subjectCode":"%s",
                                                 "dateOfBirthYear":1985,"sex":"FEMALE",
                                                 "identity":{"fullName":"GIS Test Subject"},
                                                 "consent":{"consentVersion":"ICF v1.0","consentMethod":"WRITTEN"}}
                                                """
                                                        .formatted(
                                                                trialId,
                                                                siteId,
                                                                "SUBJ-"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result, "$.id");
    }

    private void reportEvent(String participantId, String seriousness, String criteria)
            throws Exception {
        mockMvc.perform(
                        post("/api/v1/adverse-events")
                                .cookie(investigator)
                                .with(csrf(investigator))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"participantId":"%s","eventTerm":"Nausea",
                                         "description":"Reported after dosing",
                                         "onsetDate":"2026-09-02","severity":"MODERATE",
                                         "seriousness":"%s","seriousCriteria":%s}
                                        """
                                                .formatted(participantId, seriousness, criteria)))
                .andExpect(status().isCreated());
    }

    private UUID userIdOf(Cookie[] cookie) throws Exception {
        return UUID.fromString(
                read(mockMvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn(), "$.userId"));
    }

    private void assignStaffToSite(String trialId, String siteId, UUID userId) throws Exception {
        mockMvc.perform(
                        post("/api/v1/trial-staff")
                                .cookie(investigator)
                                .with(csrf(investigator))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"trialId":"%s","trialSiteId":"%s","userId":"%s","staffRole":"STAFF"}
                                        """
                                                .formatted(trialId, siteId, userId)))
                .andExpect(status().isCreated());
    }

    // ── Level 0: the base map is global, not clinically scoped ─────────────────

    @Test
    void institutionsAreVisibleToEveryAuthenticatedRole() throws Exception {
        mockMvc.perform(get("/api/v1/gis/institutions").cookie(loginAs("ETHICS_MEMBER", institutionA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"));
    }

    @Test
    void theBaseMapShowsSitesOutsideTheCallersAssignmentEvenThoughSitesReadDoesNot()
            throws Exception {
        Cookie[] staff = loginAs("RESEARCH_STAFF");
        assignStaffToSite(trialA, siteA, userIdOf(staff));

        // The ordinary, clinically-scoped endpoint: exactly what §8.9's RLS promises — this
        // staff member's own site only.
        MvcResult scoped =
                mockMvc.perform(get("/api/v1/sites").param("trialId", trialB).cookie(staff))
                        .andReturn();
        assertThat(this.<List<Object>>read(scoped, "$")).isEmpty();

        // The base map: the same session, the same trial B, and site B is there anyway,
        // because §1.3/§11.3 make this layer global rather than assignment-scoped.
        MvcResult base = mockMvc.perform(get("/api/v1/gis/sites").cookie(staff)).andReturn();
        List<Map<String, Object>> features = read(base, "$.features");
        List<String> siteIds =
                features.stream().map(f -> (String) ((Map<String, Object>) f.get("properties")).get("id")).toList();
        assertThat(siteIds).contains(siteA, siteB);
    }

    @Test
    void aBoundingBoxAroundOneInstitutionExcludesTheOther() throws Exception {
        // Delhi only: a box nowhere near Mumbai's longitude.
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/gis/sites")
                                        .param("bbox", "76,27,78,29")
                                        .cookie(investigator))
                        .andReturn();
        List<Map<String, Object>> features = read(result, "$.features");
        List<String> siteIds =
                features.stream().map(f -> (String) ((Map<String, Object>) f.get("properties")).get("id")).toList();
        assertThat(siteIds).contains(siteA).doesNotContain(siteB);
    }

    @Test
    void aMalformedBoundingBoxIs400() throws Exception {
        mockMvc.perform(get("/api/v1/gis/sites").param("bbox", "not-a-bbox").cookie(investigator))
                .andExpect(status().isBadRequest());
    }

    // ── clustering ───────────────────────────────────────────────────────────

    @Test
    void nearbySitesCollapseIntoOneClusterAndFarOnesDoNot() throws Exception {
        // Deliberately not this class's Delhi/Mumbai fixture: the base map is global (that is
        // the point of this whole module), so every other test method's institutions are also
        // in view here. A bbox wide enough to demonstrate clustering would otherwise sweep up
        // whatever earlier methods in this shared-container run happened to create at the
        // same fixed coordinates. A random offset unrelated to any other fixture in this file
        // is what actually isolates this test, not the bbox.
        double baseLat = 1.0 + Math.random();
        double baseLon = 1.0 + Math.random();
        UUID near1 = createInstitution(state, "Cluster", baseLat, baseLon);
        UUID near2 = createInstitution(state, "Cluster", baseLat + 0.0005, baseLon + 0.0005);
        UUID far = createInstitution(state, "Cluster", baseLat + 10, baseLon + 10);

        for (UUID institution : List.of(near1, near2, far)) {
            String trial = createTrial(institution);
            createSite(trial, institution);
            activate(trial);
        }

        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/gis/clusters")
                                        .param(
                                                "bbox",
                                                "%s,%s,%s,%s"
                                                        .formatted(
                                                                baseLon - 1,
                                                                baseLat - 1,
                                                                baseLon + 11,
                                                                baseLat + 11))
                                        .param("zoom", "4")
                                        .cookie(investigator))
                        .andReturn();
        List<Map<String, Object>> features = read(result, "$.features");
        List<Integer> counts =
                features.stream().map(f -> (Integer) ((Map<String, Object>) f.get("properties")).get("count")).toList();

        assertThat(counts).containsExactlyInAnyOrder(2, 1);
    }

    // ── Level 1: aggregates and k-anonymity (§11.4) ─────────────────────────────

    @Test
    void aSmallCohortsEnrollmentIsSuppressedAndALargeOnesIsNot() throws Exception {
        setEnrollment(siteA, 2); // below k=5
        setEnrollment(siteB, 12); // above k=5

        // Delhi and Mumbai share this test's random state name, so both land in one row.
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/gis/aggregates")
                                        .param("level", "city")
                                        .cookie(investigator))
                        .andReturn();

        assertThat(readAreaField(result, cityA, "$.enrollment.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(readAreaField(result, cityA, "$.enrollment.value")).isNull();
        assertThat(readAreaField(result, cityA, "$.enrollment.label")).isEqualTo("<5");

        assertThat(readAreaField(result, cityB, "$.enrollment.suppressed")).isEqualTo(Boolean.FALSE);
        assertThat(readAreaField(result, cityB, "$.enrollment.value")).isEqualTo(12);
    }

    @SuppressWarnings("unchecked")
    private Object readAreaField(MvcResult result, String area, String fieldPath) throws Exception {
        List<Map<String, Object>> areas = read(result, "$.areas");
        Map<String, Object> row =
                areas.stream().filter(a -> area.equals(a.get("area"))).findFirst().orElseThrow();
        Object node = row;
        for (String part : fieldPath.replace("$.", "").split("\\.")) {
            node = ((Map<String, Object>) node).get(part);
        }
        return node;
    }

    @Test
    void structuralCountsAreNeverSuppressedEvenForASmallCohort() throws Exception {
        setEnrollment(siteA, 1);
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/gis/aggregates")
                                        .param("level", "city")
                                        .cookie(investigator))
                        .andReturn();
        assertThat(readAreaField(result, cityA, "$.siteCount")).isEqualTo(1);
        assertThat(readAreaField(result, cityA, "$.trialCount")).isEqualTo(1);
    }

    @Test
    void districtLevelIsRejectedRatherThanApproximatedFromCity() throws Exception {
        mockMvc.perform(
                        get("/api/v1/gis/aggregates")
                                .param("level", "district")
                                .cookie(investigator))
                .andExpect(status().isUnprocessableContent());
    }

    // ── Level 2/3: drill-down is RLS-scoped, unlike the base map ────────────────

    @Test
    void drilldownOnASiteOutsideTheCallersTrialIs404() throws Exception {
        // This investigator is assigned to trial A, not B.
        Cookie[] otherInvestigator = loginAs("PRINCIPAL_INVESTIGATOR");
        mockMvc.perform(get("/api/v1/gis/sites/" + siteA + "/detail").cookie(otherInvestigator))
                .andExpect(status().isNotFound());
    }

    @Test
    void theAssignedInvestigatorReachesTheirOwnSitesDetail() throws Exception {
        mockMvc.perform(get("/api/v1/gis/sites/" + siteA + "/detail").cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteId").value(siteA));
    }

    @Test
    void aRoleWithoutGisDrilldownIsForbidden() throws Exception {
        // SYSTEM_ADMIN holds gis:read but not gis:drilldown (§5.8, §6.3).
        mockMvc.perform(get("/api/v1/gis/sites/" + siteA + "/detail").cookie(loginAs("SYSTEM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void enrollmentIsRawForTheOperatingInvestigatorButSuppressedForSafety() throws Exception {
        setEnrollment(siteA, 2); // below k=5

        mockMvc.perform(get("/api/v1/gis/sites/" + siteA + "/detail").cookie(investigator))
                .andExpect(jsonPath("$.enrollment.suppressed").value(false))
                .andExpect(jsonPath("$.enrollment.value").value(2));

        // SAFETY_OFFICER reaches this site via reads_all_structure() (unconditional trial_sites
        // read) but holds no participant:read — the same re-identification exposure as an
        // aggregate small cell, just at n=1 site instead of n=many.
        mockMvc.perform(get("/api/v1/gis/sites/" + siteA + "/detail").cookie(loginAs("SAFETY_OFFICER")))
                .andExpect(jsonPath("$.enrollment.suppressed").value(true))
                .andExpect(jsonPath("$.enrollment.value").doesNotExist());
    }

    @Test
    void complianceIsOmittedForARoleWithoutComplianceReadAndPresentForOneWithIt() throws Exception {
        // RESEARCH_STAFF holds no compliance:read at all (§6.3) — genuinely assigned to this
        // exact site, so the request clears RLS scope and reaches the compliance gate itself
        // rather than 404ing before it, which would pass this assertion for the wrong reason.
        Cookie[] staff = loginAs("RESEARCH_STAFF");
        assignStaffToSite(trialA, siteA, userIdOf(staff));
        mockMvc.perform(get("/api/v1/gis/sites/" + siteA + "/detail").cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliance").doesNotExist());

        // REGULATORY_OFFICER holds compliance:read and reads_all_structure() covers any site.
        mockMvc.perform(get("/api/v1/gis/sites/" + siteA + "/detail").cookie(loginAs("REGULATORY_OFFICER")))
                .andExpect(jsonPath("$.compliance.total").value(0));
    }

    /**
     * The point of V24: REGULATORY_OFFICER holds adverse_event:read (RBAC) but no row-level
     * access to adverse_events (RLS) — the two are supposed to disagree here, on purpose. A
     * genuine event exists on this trial; the regulator must never see it reported as zero.
     */
    @Test
    void adverseEventCountIsAbsentForRegulatorButRealForSafetyOfficerWhenAnEventExists()
            throws Exception {
        String participantId = enrolParticipant(trialA, siteA);
        reportEvent(participantId, "NON_SERIOUS", "null");

        mockMvc.perform(get("/api/v1/gis/sites/" + siteA + "/detail").cookie(loginAs("SAFETY_OFFICER")))
                .andExpect(jsonPath("$.adverseEventCount").value(1));

        mockMvc.perform(
                        get("/api/v1/gis/sites/" + siteA + "/detail")
                                .cookie(loginAs("REGULATORY_OFFICER")))
                .andExpect(jsonPath("$.adverseEventCount").doesNotExist());
    }

    @Test
    void drillDownOnANonexistentSiteIs404() throws Exception {
        mockMvc.perform(
                        get("/api/v1/gis/sites/" + UUID.randomUUID() + "/detail")
                                .cookie(investigator))
                .andExpect(status().isNotFound());
    }
}
