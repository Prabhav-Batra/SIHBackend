package com.sih26046.ctms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * §8.21, §8.22 — the compliance requirement catalogue and per-trial status.
 *
 * <p>The catalogue is reference data: it is what every role is measured against, so everyone
 * reads it and only those who define compliance write it. Per-trial status is the opposite
 * shape — scoped to the trial, and writable only by those who verify compliance rather than by
 * the investigator whose trial is being measured.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComplianceApiIT extends ApiTestSupport {

    private static UUID institutionId;

    private Cookie[] regulator;
    private Cookie[] investigator;
    private String trialId;

    @BeforeEach
    void seed() throws Exception {
        if (institutionId == null) {
            institutionId =
                    UUID.fromString(
                            ownerJdbc()
                                    .queryForObject(
                                            "INSERT INTO institutions (name, institution_type,"
                                                + " city, state) VALUES"
                                                + " (?,'MEDICAL_COLLEGE','Pune','Maharashtra')"
                                                + " RETURNING id",
                                            String.class,
                                            "Compliance College " + UUID.randomUUID()));
        }
        regulator = loginAs("REGULATORY_OFFICER");
    }

    private void givenATrial() throws Exception {
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        trialId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/trials")
                                                .cookie(investigator)
                                                .with(csrf(investigator))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"protocolNumber":"%s","title":"Compliance study",
                                                         "sponsorInstitutionId":"%s","phase":"III"}
                                                        """
                                                                .formatted(
                                                                        "CMP-" + UUID.randomUUID(),
                                                                        institutionId)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");
    }

    private String defineRequirement(boolean mandatory) throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/compliance/requirements")
                                        .cookie(regulator)
                                        .with(csrf(regulator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"code":"%s","title":"Ethics approval on file",
                                                 "description":"A current IEC approval must exist.",
                                                 "category":"ETHICS","authority":"CDSCO",
                                                 "isMandatory":%s}
                                                """
                                                        .formatted(
                                                                "REQ-" + UUID.randomUUID(),
                                                                mandatory)))
                        .andExpect(status().isCreated())
                        .andReturn(),
                "$.id");
    }

    private String attach(String requirementId) throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/compliance/trials/" + trialId + "/requirements")
                                        .cookie(regulator)
                                        .with(csrf(regulator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"complianceRequirementId\":\"%s\"}"
                                                        .formatted(requirementId)))
                        .andExpect(status().isCreated())
                        .andReturn(),
                "$.id");
    }

    // ── the catalogue ────────────────────────────────────────────────────────

    @Test
    void theRequirementCatalogueIsReadableByEveryRole() throws Exception {
        String requirementId = defineRequirement(true);

        for (Cookie[] who :
                new Cookie[][] {
                    loginAs("PRINCIPAL_INVESTIGATOR"),
                    loginAs("TRIAL_COORDINATOR"),
                    loginAs("ETHICS_MEMBER", institutionId),
                    loginAs("SAFETY_OFFICER"),
                    loginAs("SYSTEM_ADMIN")
                }) {
            mockMvc.perform(get("/api/v1/compliance/requirements/" + requirementId).cookie(who))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.category").value("ETHICS"));
        }
    }

    @Test
    void anInvestigatorCannotDefineWhatCountsAsCompliance() throws Exception {
        Cookie[] investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        mockMvc.perform(
                        post("/api/v1/compliance/requirements")
                                .cookie(investigator)
                                .with(csrf(investigator))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"code":"%s","title":"A rule I invented",
                                         "description":"Convenient.","category":"ETHICS"}
                                        """
                                                .formatted("REQ-" + UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    // ── per-trial status ─────────────────────────────────────────────────────

    @Test
    void aRequirementAttachedToATrialStartsPending() throws Exception {
        givenATrial();
        String id = attach(defineRequirement(true));

        mockMvc.perform(get("/api/v1/compliance/trials/" + trialId).cookie(regulator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void theSameRequirementCannotBeAttachedTwiceAtTheSameScope() throws Exception {
        givenATrial();
        String requirementId = defineRequirement(true);
        attach(requirementId);

        // uq_trial_compliance_scope — two rows for one obligation would make the rollup
        // count it twice and let one be COMPLIANT while the other is not.
        mockMvc.perform(
                        post("/api/v1/compliance/trials/" + trialId + "/requirements")
                                .cookie(regulator)
                                .with(csrf(regulator))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"complianceRequirementId\":\"%s\"}"
                                                .formatted(requirementId)))
                .andExpect(status().isConflict());
    }

    @Test
    void theRollupCountsByStatusAndReportsOutstandingMandatoryWork() throws Exception {
        givenATrial();
        String mandatory = attach(defineRequirement(true));
        attach(defineRequirement(true));
        attach(defineRequirement(false));

        mockMvc.perform(get("/api/v1/compliance/trials/" + trialId + "/summary").cookie(regulator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.byStatus.PENDING").value(3))
                .andExpect(jsonPath("$.mandatoryOutstanding").value(2))
                .andExpect(jsonPath("$.compliant").value(false));

        markCompliant(mandatory);

        mockMvc.perform(get("/api/v1/compliance/trials/" + trialId + "/summary").cookie(regulator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.COMPLIANT").value(1))
                .andExpect(jsonPath("$.byStatus.PENDING").value(2))
                .andExpect(jsonPath("$.mandatoryOutstanding").value(1))
                .andExpect(jsonPath("$.compliant").value(false));
    }

    @Test
    void aTrialIsCompliantOnlyWhenNoMandatoryRequirementIsOutstanding() throws Exception {
        givenATrial();
        markCompliant(attach(defineRequirement(true)));

        // The optional one stays PENDING; it must not hold the trial back.
        attach(defineRequirement(false));

        mockMvc.perform(get("/api/v1/compliance/trials/" + trialId + "/summary").cookie(regulator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mandatoryOutstanding").value(0))
                .andExpect(jsonPath("$.compliant").value(true));
    }

    private void markCompliant(String trialComplianceId) throws Exception {
        String etag =
                mockMvc.perform(
                                get("/api/v1/compliance/trials/" + trialId + "/" + trialComplianceId)
                                        .cookie(regulator))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getHeader(HttpHeaders.ETAG);

        mockMvc.perform(
                        post("/api/v1/compliance/trials/"
                                        + trialId
                                        + "/"
                                        + trialComplianceId
                                        + "/status")
                                .cookie(regulator)
                                .with(csrf(regulator))
                                .header(HttpHeaders.IF_MATCH, etag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"COMPLIANT\",\"notes\":\"Approval on file\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLIANT"))
                // ck_trial_compliance_verification: a verification claim must record who and
                // when, together. Setting the status is what makes the claim.
                .andExpect(jsonPath("$.verifiedBy").isNotEmpty())
                .andExpect(jsonPath("$.verifiedAt").isNotEmpty());
    }

    // ── scope ────────────────────────────────────────────────────────────────

    @Test
    void anInvestigatorSeesTheirObligationsButCannotMarkThemMet() throws Exception {
        givenATrial();
        String id = attach(defineRequirement(true));

        mockMvc.perform(get("/api/v1/compliance/trials/" + trialId).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        // §5.8 grants the investigator compliance:read and no more. Being measured and
        // recording the measurement are different jobs.
        mockMvc.perform(
                        post("/api/v1/compliance/trials/" + trialId + "/" + id + "/status")
                                .cookie(investigator)
                                .with(csrf(investigator))
                                .header(HttpHeaders.IF_MATCH, "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"COMPLIANT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnassignedInvestigatorSeesNoObligationsForThatTrial() throws Exception {
        givenATrial();
        attach(defineRequirement(true));

        mockMvc.perform(
                        get("/api/v1/compliance/trials/" + trialId)
                                .cookie(loginAs("PRINCIPAL_INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
