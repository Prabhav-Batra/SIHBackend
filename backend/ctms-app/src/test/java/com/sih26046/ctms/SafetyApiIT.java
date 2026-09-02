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
 * §8.17, §8.18, §5.6 — adverse events, safety review, and the event-triggered clinical read.
 *
 * <p>The last of those is the interesting one. A Safety Officer has no standing access to
 * clinical data; they gain it for a participant only once that participant has an adverse
 * event on record. Access follows clinical justification rather than role, and the tests below
 * exercise both sides of that line with two participants identical in every other respect.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SafetyApiIT extends ApiTestSupport {

    private static UUID institutionId;
    private static boolean seeded;

    private Cookie investigator;
    private String trialId;
    private String siteId;

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
                                            "Safety Institute " + UUID.randomUUID()));
        }
    }

    private void givenAnActiveTrial() throws Exception {
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        trialId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/trials")
                                                .cookie(investigator)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"protocolNumber":"%s","title":"Safety study",
                                                         "sponsorInstitutionId":"%s","phase":"III"}
                                                        """
                                                                .formatted(
                                                                        "SAFE-" + UUID.randomUUID(),
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
                                                                        "SF-"
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
    }

    private String enrol() throws Exception {
        return read(
                mockMvc.perform(
                                post("/api/v1/participants")
                                        .cookie(investigator)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"trialId":"%s","trialSiteId":"%s",
                                                 "subjectCode":"%s","dateOfBirthYear":1988,
                                                 "sex":"FEMALE",
                                                 "identity":{"fullName":"Safety Subject"},
                                                 "consent":{"consentVersion":"ICF v1.0",
                                                            "consentMethod":"WRITTEN"}}
                                                """
                                                        .formatted(
                                                                trialId,
                                                                siteId,
                                                                "SF-"
                                                                        + UUID.randomUUID()
                                                                                .toString()
                                                                                .substring(0, 8))))
                        .andExpect(status().isCreated())
                        .andReturn(),
                "$.id");
    }

    private String recordObservationFor(String participantId) throws Exception {
        String visitId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/visits")
                                                .cookie(investigator)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"participantId":"%s","visitName":"Baseline",
                                                         "visitNumber":1,
                                                         "scheduledDate":"2026-09-01"}
                                                        """
                                                                .formatted(participantId)))
                                .andExpect(status().isCreated())
                                .andReturn(),
                        "$.id");
        mockMvc.perform(
                        post("/api/v1/observations")
                                .cookie(investigator)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"visitId":"%s","observationCode":"VITALS_SBP",
                                         "observationName":"Systolic BP","category":"VITAL_SIGN",
                                         "valueNumeric":128,"unit":"mmHg"}
                                        """
                                                .formatted(visitId)))
                .andExpect(status().isCreated());
        return visitId;
    }

    private MvcResult reportEvent(Cookie auth, String participantId, String seriousness,
            String criteria) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/adverse-events")
                                .cookie(auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"participantId":"%s","eventTerm":"Nausea",
                                         "description":"Reported after dosing",
                                         "onsetDate":"2026-09-02","severity":"MODERATE",
                                         "seriousness":"%s","seriousCriteria":%s}
                                        """
                                                .formatted(participantId, seriousness, criteria)))
                .andReturn();
    }

    // ── reporting ────────────────────────────────────────────────────────────

    @Test
    void anInvestigatorCanReportAnAdverseEvent() throws Exception {
        givenAnActiveTrial();
        MvcResult reported = reportEvent(investigator, enrol(), "NON_SERIOUS", "null");

        assertThat(reported.getResponse().getStatus()).isEqualTo(201);
        assertThat(this.<String>read(reported, "$.status")).isEqualTo("REPORTED");
    }

    @Test
    void theTrialIsDerivedRatherThanTrusted() throws Exception {
        // §8.17 — trial_id is denormalised onto the event so cross-trial safety queries need no
        // join. It is set by trigger from the participant, never from the request, or the
        // Safety Officer's view could disagree with the participant's actual trial.
        givenAnActiveTrial();
        String eventId = read(reportEvent(investigator, enrol(), "NON_SERIOUS", "null"), "$.id");

        assertThat(
                        ownerJdbc()
                                .queryForObject(
                                        "SELECT trial_id FROM adverse_events WHERE id = ?::uuid",
                                        String.class,
                                        eventId))
                .isEqualTo(trialId);
    }

    @Test
    void aSeriousEventWithoutCriteriaIsRejected() throws Exception {
        // §8.17 — a serious event with no criteria recorded cannot be reported to an
        // authority, which is the one thing a serious event exists to trigger.
        givenAnActiveTrial();

        assertThat(reportEvent(investigator, enrol(), "SERIOUS", "null").getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void aSeriousEventWithCriteriaIsAccepted() throws Exception {
        givenAnActiveTrial();

        MvcResult reported =
                reportEvent(investigator, enrol(), "SERIOUS", "[\"HOSPITALISATION\"]");

        assertThat(reported.getResponse().getStatus()).isEqualTo(201);
        assertThat(this.<String>read(reported, "$.seriousness")).isEqualTo("SERIOUS");
    }

    // ── visibility ───────────────────────────────────────────────────────────

    @Test
    void aSafetyOfficerSeesEventsWithoutAnAssignment() throws Exception {
        // §7.5 — cross-trial comparison is the role's purpose, so scoping safety to
        // assignments would defeat it.
        givenAnActiveTrial();
        String participantId = enrol();
        reportEvent(investigator, participantId, "NON_SERIOUS", "null");

        mockMvc.perform(
                        get("/api/v1/adverse-events?participantId=" + participantId)
                                .cookie(loginAs("SAFETY_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventTerm").value("Nausea"));
    }

    // ── the event-triggered clinical read (§5.6) ─────────────────────────────

    @Test
    void aSafetyOfficerCannotReadObservationsOfAParticipantWithoutAnEvent() throws Exception {
        givenAnActiveTrial();
        String visitId = recordObservationFor(enrol());

        mockMvc.perform(
                        get("/api/v1/observations?visitId=" + visitId)
                                .cookie(loginAs("SAFETY_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void anAdverseEventOpensThatParticipantsObservations() throws Exception {
        // The same request, on a participant who now has an event. Nothing about the Safety
        // Officer changed; the clinical justification did.
        givenAnActiveTrial();
        String participantId = enrol();
        String visitId = recordObservationFor(participantId);
        reportEvent(investigator, participantId, "NON_SERIOUS", "null");

        mockMvc.perform(
                        get("/api/v1/observations?visitId=" + visitId)
                                .cookie(loginAs("SAFETY_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].observationCode").value("VITALS_SBP"));
    }

    @Test
    void theEventDoesNotOpenTheEnrolmentRecord() throws Exception {
        // §5.8 gives safety no access to participants, visits or consents even for an event
        // they are reviewing. The justification reaches clinical measurements, not the
        // enrolment record.
        givenAnActiveTrial();
        String participantId = enrol();
        recordObservationFor(participantId);
        reportEvent(investigator, participantId, "NON_SERIOUS", "null");

        mockMvc.perform(
                        get("/api/v1/participants?trialId=" + trialId)
                                .cookie(loginAs("SAFETY_OFFICER")))
                .andExpect(status().isForbidden());
    }

    // ── review ───────────────────────────────────────────────────────────────

    @Test
    void aSafetyOfficerReviewsAnEventAndSetsCausality() throws Exception {
        // §8.17 — causality is set by the Safety Officer at review, not by the reporter.
        givenAnActiveTrial();
        String eventId = read(reportEvent(investigator, enrol(), "NON_SERIOUS", "null"), "$.id");

        MvcResult review =
                mockMvc.perform(
                                post("/api/v1/safety/reviews")
                                        .cookie(loginAs("SAFETY_OFFICER"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"adverseEventId":"%s","assessedSeverity":"MODERATE",
                                                 "assessedCausality":"PROBABLE","isExpected":false,
                                                 "decision":"ESCALATED"}
                                                """
                                                        .formatted(eventId)))
                        .andReturn();

        assertThat(review.getResponse().getStatus()).isEqualTo(201);
        assertThat(this.<String>read(review, "$.assessedCausality")).isEqualTo("PROBABLE");

        // Reviewing moves the event out of REPORTED so it cannot be silently left unhandled.
        assertThat(
                        ownerJdbc()
                                .queryForObject(
                                        "SELECT status FROM adverse_events WHERE id = ?::uuid",
                                        String.class,
                                        eventId))
                .isEqualTo("REVIEWED");
    }

    @Test
    void anInvestigatorCannotReviewAnEvent() throws Exception {
        givenAnActiveTrial();
        String eventId = read(reportEvent(investigator, enrol(), "NON_SERIOUS", "null"), "$.id");

        mockMvc.perform(
                        post("/api/v1/safety/reviews")
                                .cookie(investigator)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"adverseEventId":"%s","assessedSeverity":"MILD",
                                         "assessedCausality":"UNRELATED","isExpected":true,
                                         "decision":"ACCEPTED"}
                                        """
                                                .formatted(eventId)))
                .andExpect(status().isForbidden());
    }
}
