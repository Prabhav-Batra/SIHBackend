package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * §14.6 — participant enrolment, the platform's one genuinely multi-table write.
 *
 * <p>Enrolment touches participants, participant_identities, consents and the enrolment
 * counters on both trials and trial_sites. Any partial application leaves the platform
 * describing a trial that does not exist: a counter without a participant, or a participant
 * with no consent on record and therefore no lawful basis for the data about to be collected.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnrollmentIT extends ApiTestSupport {

    private static UUID institutionId;
    private static boolean seeded;

    private Cookie[] investigator;
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
                                            "Enrol Institute " + UUID.randomUUID()));
        }
    }

    /** An ACTIVE trial with one site — the only state in which enrolment is permitted. */
    private void givenAnActiveTrial(Integer target) throws Exception {
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/trials")
                                        .cookie(investigator)
                                        .with(csrf(investigator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"protocolNumber":"%s","title":"Enrolment study",
                                                 "sponsorInstitutionId":"%s","phase":"III",
                                                 "targetEnrollment":%s}
                                                """
                                                        .formatted(
                                                                "ENR-" + UUID.randomUUID(),
                                                                institutionId,
                                                                target)))
                        .andExpect(status().isCreated())
                        .andReturn();
        trialId = read(created, "$.id");

        siteId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/sites")
                                                .cookie(investigator)
                                                .with(csrf(investigator))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"trialId":"%s","institutionId":"%s",
                                                         "siteCode":"%s"}
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

        // DRAFT -> PENDING_ETHICS -> APPROVED -> ACTIVE, through the API so the lifecycle is
        // exercised rather than sidestepped.
        advance("PENDING_ETHICS");
        advance("APPROVED");
        advance("ACTIVE");
    }

    private void advance(String status) throws Exception {
        String etag =
                mockMvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/api/v1/trials/" + trialId)
                                        .cookie(investigator))
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

    private MvcResult enrol(String idempotencyKey) throws Exception {
        var request =
                post("/api/v1/participants")
                        .cookie(investigator)
                        .with(csrf(investigator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"trialId":"%s","trialSiteId":"%s","subjectCode":"%s",
                                 "dateOfBirthYear":1985,"sex":"FEMALE",
                                 "identity":{"fullName":"Asha Rao","phone":"+91-99999-00000"},
                                 "consent":{"consentVersion":"ICF v1.0","consentMethod":"WRITTEN"}}
                                """
                                        .formatted(
                                                trialId,
                                                siteId,
                                                "SUBJ-"
                                                        + UUID.randomUUID()
                                                                .toString()
                                                                .substring(0, 8)));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private int count(String sql, Object... args) {
        return ownerJdbc().queryForObject(sql, Integer.class, args);
    }

    @Test
    void enrolmentWritesEveryTableInOneTransaction() throws Exception {
        givenAnActiveTrial(10);

        MvcResult enrolled = enrol(null);
        assertThat(enrolled.getResponse().getStatus()).isEqualTo(201);
        String participantId = read(enrolled, "$.id");

        assertThat(count("SELECT count(*) FROM participants WHERE id = ?::uuid", participantId))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM participant_identities WHERE participant_id"
                                        + " = ?::uuid",
                                participantId))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM consents WHERE participant_id = ?::uuid AND"
                                        + " status = 'ACTIVE'",
                                participantId))
                .isEqualTo(1);
        assertThat(count("SELECT current_enrollment FROM trials WHERE id = ?::uuid", trialId))
                .isEqualTo(1);
        assertThat(count("SELECT current_enrollment FROM trial_sites WHERE id = ?::uuid", siteId))
                .isEqualTo(1);
    }

    @Test
    void theResponseNeverCarriesTheParticipantsIdentity() throws Exception {
        // ADR-011 — the pseudonymisation boundary. A name reaching a clinical response would
        // undo the separation the whole schema is built around.
        givenAnActiveTrial(10);

        String body = enrol(null).getResponse().getContentAsString();

        assertThat(body).doesNotContain("Asha Rao").doesNotContain("99999");
    }

    @Test
    void aTrialThatIsNotActiveCannotEnrol() throws Exception {
        // §20.2 — only an ACTIVE trial enrols. A DRAFT trial has no ethics approval, so
        // enrolling into one is the failure the lifecycle exists to prevent.
        investigator = loginAs("PRINCIPAL_INVESTIGATOR");
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/trials")
                                        .cookie(investigator)
                                        .with(csrf(investigator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"protocolNumber":"%s","title":"Draft study",
                                                 "sponsorInstitutionId":"%s","phase":"III"}
                                                """
                                                        .formatted(
                                                                "DRAFT-" + UUID.randomUUID(),
                                                                institutionId)))
                        .andReturn();
        trialId = read(created, "$.id");
        siteId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/sites")
                                                .cookie(investigator)
                                                .with(csrf(investigator))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"trialId":"%s","institutionId":"%s",
                                                         "siteCode":"D-1"}
                                                        """
                                                                .formatted(trialId, institutionId)))
                                .andReturn(),
                        "$.id");

        assertThat(enrol(null).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void enrolmentStopsAtTheTargetRatherThanExceedingIt() throws Exception {
        givenAnActiveTrial(1);

        assertThat(enrol(null).getResponse().getStatus()).isEqualTo(201);
        assertThat(enrol(null).getResponse().getStatus()).isEqualTo(422);

        assertThat(count("SELECT current_enrollment FROM trials WHERE id = ?::uuid", trialId))
                .isEqualTo(1);
    }

    @Test
    void aReplayedRequestEnrolsExactlyOneParticipant() throws Exception {
        // §14.5 — a retried enrolment must not produce a second person. The counter is the
        // tell: a duplicate would show as 2 even if the caller only ever saw one response.
        givenAnActiveTrial(10);
        String key = UUID.randomUUID().toString();

        MvcResult first = enrol(key);
        MvcResult second = enrol(key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(this.<String>read(second, "$.id")).isEqualTo(read(first, "$.id"));
        assertThat(count("SELECT current_enrollment FROM trials WHERE id = ?::uuid", trialId))
                .isEqualTo(1);
    }

    @Test
    void aFailedEnrolmentLeavesNothingBehind() throws Exception {
        // The atomicity that matters: a counter incremented for a participant who does not
        // exist would misreport the trial's size to every dashboard and regulator report.
        givenAnActiveTrial(10);
        enrol(null);

        String duplicateSubjectCode =
                ownerJdbc()
                        .queryForObject(
                                "SELECT subject_code FROM participants WHERE trial_id = ?::uuid"
                                        + " LIMIT 1",
                                String.class,
                                trialId);

        MvcResult clash =
                mockMvc.perform(
                                post("/api/v1/participants")
                                        .cookie(investigator)
                                        .with(csrf(investigator))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"trialId":"%s","trialSiteId":"%s",
                                                 "subjectCode":"%s","dateOfBirthYear":1990,
                                                 "sex":"MALE",
                                                 "identity":{"fullName":"Someone Else"},
                                                 "consent":{"consentVersion":"ICF v1.0",
                                                            "consentMethod":"WRITTEN"}}
                                                """
                                                        .formatted(
                                                                trialId,
                                                                siteId,
                                                                duplicateSubjectCode)))
                        .andReturn();

        assertThat(clash.getResponse().getStatus()).isEqualTo(422);
        assertThat(count("SELECT current_enrollment FROM trials WHERE id = ?::uuid", trialId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM participant_identities pi JOIN participants p ON"
                                + " p.id = pi.participant_id WHERE p.trial_id = ?::uuid", trialId))
                .isEqualTo(1);
    }
}
