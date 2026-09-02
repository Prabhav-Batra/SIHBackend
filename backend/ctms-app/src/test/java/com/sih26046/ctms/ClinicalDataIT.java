package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * §20.3, §8.13 — visits, observations, medications, and the rule that governs all of them.
 *
 * <p>Consent is the lawful basis for collecting clinical data. A visit recorded against a
 * participant who has withdrawn is not a tidiness problem; it is data collected without
 * permission, and it must be impossible rather than discouraged.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClinicalDataIT extends ApiTestSupport {

    private static UUID institutionId;
    private static boolean seeded;

    private Cookie investigator;
    private String trialId;
    private String siteId;
    private String participantId;

    @BeforeEach
    void seedInstitution() {
        if (!seeded) {
            seeded = true;
            institutionId =
                    UUID.fromString(
                            ownerJdbc()
                                    .queryForObject(
                                            "INSERT INTO institutions (name, institution_type,"
                                                + " city, state) VALUES"
                                                + " (?,'MEDICAL_COLLEGE','Delhi','Delhi')"
                                                + " RETURNING id",
                                            String.class,
                                            "Clinical Institute " + UUID.randomUUID()));
        }
    }

    private void givenAnEnrolledParticipant() throws Exception {
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        trialId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/trials")
                                                .cookie(investigator)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"protocolNumber":"%s","title":"Data study",
                                                         "sponsorInstitutionId":"%s","phase":"III"}
                                                        """
                                                                .formatted(
                                                                        "CLIN-" + UUID.randomUUID(),
                                                                        institutionId)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");

        siteId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/sites")
                                                .cookie(investigator)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"trialId":"%s","institutionId":"%s",
                                                         "siteCode":"%s"}
                                                        """
                                                                .formatted(
                                                                        trialId,
                                                                        institutionId,
                                                                        "C-"
                                                                            + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 6))))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");

        for (String next : new String[] {"PENDING_ETHICS", "APPROVED", "ACTIVE"}) {
            String etag =
                    mockMvc.perform(get("/api/v1/trials/" + trialId).cookie(investigator))
                            .andReturn()
                            .getResponse()
                            .getHeader("ETag");
            mockMvc.perform(
                            post("/api/v1/trials/" + trialId + "/status")
                                    .cookie(investigator)
                                    .header("If-Match", etag)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"status\":\"%s\"}".formatted(next)))
                    .andExpect(status().isOk());
        }

        participantId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/participants")
                                                .cookie(investigator)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"trialId":"%s","trialSiteId":"%s",
                                                         "subjectCode":"%s","dateOfBirthYear":1990,
                                                         "sex":"MALE",
                                                         "identity":{"fullName":"Test Subject"},
                                                         "consent":{"consentVersion":"ICF v1.0",
                                                                    "consentMethod":"WRITTEN"}}
                                                        """
                                                                .formatted(
                                                                        trialId,
                                                                        siteId,
                                                                        "S-"
                                                                            + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8))))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");
    }

    private MvcResult recordVisit(int number) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/visits")
                                .cookie(investigator)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"participantId":"%s","visitName":"Week %d",
                                         "visitNumber":%d,"scheduledDate":"2026-10-01"}
                                        """
                                                .formatted(participantId, number, number)))
                .andReturn();
    }

    private MvcResult recordObservation(String visitId, String code, String value)
            throws Exception {
        return mockMvc.perform(
                        post("/api/v1/observations")
                                .cookie(investigator)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"visitId":"%s","observationCode":"%s",
                                         "observationName":"Systolic BP","category":"VITAL_SIGN",
                                         "valueNumeric":%s,"unit":"mmHg"}
                                        """
                                                .formatted(visitId, code, value)))
                .andReturn();
    }

    @Test
    void aConsentedParticipantAcceptsClinicalData() throws Exception {
        givenAnEnrolledParticipant();

        MvcResult visit = recordVisit(1);
        assertThat(visit.getResponse().getStatus()).isEqualTo(201);

        MvcResult observation = recordObservation(read(visit, "$.id"), "VITALS_SBP", "120");
        assertThat(observation.getResponse().getStatus()).isEqualTo(201);
        // Read as Number: a create returns the value as submitted (120) while a later read
        // returns it as stored in numeric(12,4) (120.0000), so the JSON type differs.
        assertThat(this.<Number>read(observation, "$.valueNumeric").doubleValue())
                .isEqualTo(120.0);
    }

    @Test
    void anObservationWithNoValueIsRejected() throws Exception {
        givenAnEnrolledParticipant();
        String visitId = read(recordVisit(1), "$.id");

        assertThat(recordObservation(visitId, "EMPTY", "null").getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void oneValuePerObservationCodePerVisit() throws Exception {
        givenAnEnrolledParticipant();
        String visitId = read(recordVisit(1), "$.id");
        recordObservation(visitId, "VITALS_SBP", "120");

        assertThat(recordObservation(visitId, "VITALS_SBP", "130").getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void aWithdrawnParticipantAcceptsNoNewData() throws Exception {
        // §20.3 — this is the rule that matters. Recording a visit after withdrawal is data
        // collected without a lawful basis.
        givenAnEnrolledParticipant();
        recordVisit(1);

        mockMvc.perform(
                        post("/api/v1/participants/" + participantId + "/withdrawal")
                                .cookie(investigator)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Participant request\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        assertThat(recordVisit(2).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void withdrawalDoesNotRemoveDataAlreadyCollected() throws Exception {
        // §20.3 — withdrawal stops collection; it does not erase the record. Data gathered
        // while the participant was consented remains part of the trial.
        givenAnEnrolledParticipant();
        String visitId = read(recordVisit(1), "$.id");
        recordObservation(visitId, "VITALS_SBP", "118");

        mockMvc.perform(
                        post("/api/v1/participants/" + participantId + "/withdrawal")
                                .cookie(investigator)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Participant request\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/observations?visitId=" + visitId).cookie(investigator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].valueNumeric").value(118.0));
    }

    @Test
    void withdrawingConsentAlsoStopsCollection() throws Exception {
        givenAnEnrolledParticipant();
        String consentId =
                ownerJdbc()
                        .queryForObject(
                                "SELECT id FROM consents WHERE participant_id = ?::uuid",
                                String.class,
                                participantId);

        mockMvc.perform(
                        post("/api/v1/consents/" + consentId + "/withdrawal")
                                .cookie(investigator)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Consent withdrawn\"}"))
                .andExpect(status().isOk());

        assertThat(recordVisit(2).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void medicationsAreRecordedAgainstAConsentedParticipant() throws Exception {
        givenAnEnrolledParticipant();

        MvcResult medication =
                mockMvc.perform(
                                post("/api/v1/medications")
                                        .cookie(investigator)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"participantId":"%s","medicationName":"Ibuprofen",
                                                 "medicationType":"CONCOMITANT","route":"ORAL",
                                                 "dose":400,"startDate":"2026-09-01"}
                                                """
                                                        .formatted(participantId)))
                        .andReturn();

        assertThat(medication.getResponse().getStatus()).isEqualTo(201);
        assertThat(this.<String>read(medication, "$.medicationType")).isEqualTo("CONCOMITANT");
    }

    @Test
    void anUnknownMedicationTypeIsRejected() throws Exception {
        givenAnEnrolledParticipant();

        assertThat(
                        mockMvc.perform(
                                        post("/api/v1/medications")
                                                .cookie(investigator)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"participantId":"%s",
                                                         "medicationName":"Mystery",
                                                         "medicationType":"PLACEBO_ISH",
                                                         "startDate":"2026-09-01"}
                                                        """
                                                                .formatted(participantId)))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
    }
}
