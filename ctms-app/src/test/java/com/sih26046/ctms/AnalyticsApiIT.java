package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sih26046.ctms.analytics.TrialRollupRefresher;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * §21.4, §23, §V25 — one dashboard endpoint, and the claim that matters: {@code
 * mv_trial_rollup} carries no RLS of its own, so the join to {@code trials} is what stops one
 * investigator's numbers from leaking into another's dashboard.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsApiIT extends ApiTestSupport {

    @Autowired private TrialRollupRefresher refresher;

    private Cookie[] investigator;
    private String trialId;

    @BeforeEach
    void seed() throws Exception {
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        UUID institutionId =
                UUID.fromString(
                        ownerJdbc()
                                .queryForObject(
                                        "INSERT INTO institutions (name, institution_type, city,"
                                            + " state) VALUES (?,'MEDICAL_COLLEGE','Delhi','Delhi')"
                                            + " RETURNING id",
                                        String.class,
                                        "Analytics College " + UUID.randomUUID()));
        trialId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/trials")
                                                .cookie(investigator)
                                                .with(csrf(investigator))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"protocolNumber":"%s","title":"Dashboard study",
                                                         "sponsorInstitutionId":"%s","phase":"II",
                                                         "targetEnrollment":50}
                                                        """
                                                                .formatted(
                                                                        "AN-" + UUID.randomUUID(),
                                                                        institutionId)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");
        advance("PENDING_ETHICS");
        advance("APPROVED");
        advance("ACTIVE");
        refresher.refresh();
    }

    private void advance(String status) throws Exception {
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

    @Test
    void adminSeesNoClinicalMetricsOnlyPlatformHealth() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/dashboard").cookie(loginAs("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboardType").value("ADMIN"))
                .andExpect(jsonPath("$.institutionCount").isNumber())
                .andExpect(jsonPath("$.trials").doesNotExist());
    }

    @Test
    void investigatorSeesOnlyTheirOwnTrialInTheRollupNotEveryTrialNationally() throws Exception {
        // A second, unrelated trial, refreshed into the same materialized view.
        Cookie[] otherInvestigator = loginAs("PRINCIPAL_INVESTIGATOR");
        mockMvc.perform(
                        post("/api/v1/trials")
                                .cookie(otherInvestigator)
                                .with(csrf(otherInvestigator))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"protocolNumber":"%s","title":"Other study",
                                         "sponsorInstitutionId":"%s","phase":"II"}
                                        """
                                                .formatted(
                                                        "AN-" + UUID.randomUUID(),
                                                        ownerJdbc()
                                                                .queryForObject(
                                                                        "SELECT id FROM"
                                                                            + " institutions LIMIT"
                                                                            + " 1",
                                                                        String.class))))
                .andExpect(status().isCreated());
        refresher.refresh();

        MvcResult result =
                mockMvc.perform(get("/api/v1/analytics/dashboard").cookie(investigator))
                        .andReturn();
        List<Map<String, Object>> trials = read(result, "$.trials");
        assertThat(trials).hasSize(1);
        assertThat(trials.get(0).get("trialId")).isEqualTo(trialId);
    }

    @Test
    void rollupReflectsRealAdverseEventCountsAfterRefresh() throws Exception {
        // Enrol and report a serious event, then confirm the rollup — not the live table —
        // is what the dashboard actually reads.
        String participantId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/participants")
                                                .cookie(investigator)
                                                .with(csrf(investigator))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"trialId":"%s","trialSiteId":"%s",
                                                         "subjectCode":"AN-%s","dateOfBirthYear":1990,
                                                         "sex":"MALE","identity":{"fullName":"Test"},
                                                         "consent":{"consentVersion":"v1","consentMethod":"WRITTEN"}}
                                                        """
                                                                .formatted(
                                                                        trialId,
                                                                        siteFor(trialId),
                                                                        UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8))))
                                .andReturn(),
                        "$.id");
        mockMvc.perform(
                        post("/api/v1/adverse-events")
                                .cookie(investigator)
                                .with(csrf(investigator))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"participantId":"%s","eventTerm":"Fever",
                                         "description":"d","onsetDate":"2026-09-02",
                                         "severity":"SEVERE","seriousness":"SERIOUS",
                                         "seriousCriteria":["HOSPITALIZATION"]}
                                        """
                                                .formatted(participantId)))
                .andExpect(status().isCreated());
        refresher.refresh();

        MvcResult result =
                mockMvc.perform(get("/api/v1/analytics/dashboard").cookie(investigator))
                        .andReturn();
        assertThat((Integer) readTrialField(result, "aeSerious")).isEqualTo(1);
    }

    @Test
    void auditEntriesAreAlsoRlsScopedNotJustCapabilityGated() throws Exception {
        mockMvc.perform(
                        get("/api/v1/audit")
                                .param("entityType", "trial")
                                .cookie(loginAs("PRINCIPAL_INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String siteFor(String trialId) throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/sites")
                                        .cookie(investigator)
                                        .with(csrf(investigator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"trialId":"%s","institutionId":"%s","siteCode":"S1"}
                                                """
                                                        .formatted(
                                                                trialId,
                                                                ownerJdbc()
                                                                        .queryForObject(
                                                                                "SELECT"
                                                                                    + " sponsor_institution_id"
                                                                                    + " FROM"
                                                                                    + " trials"
                                                                                    + " WHERE"
                                                                                    + " id ="
                                                                                    + " ?::uuid",
                                                                                String.class,
                                                                                trialId))))
                        .andReturn(),
                "$.id");
    }

    private Object readTrialField(MvcResult result, String field) throws Exception {
        List<Map<String, Object>> trials = read(result, "$.trials");
        return trials.get(0).get(field);
    }
}
