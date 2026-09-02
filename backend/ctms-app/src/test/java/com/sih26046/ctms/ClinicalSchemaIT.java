package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §8.11–§8.16 — constraints on the clinical record.
 *
 * <p>Run on the owner connection: these assert what the database refuses to store, which is a
 * different question from who may read it.
 */
@SpringBootTest
class ClinicalSchemaIT extends AbstractPostgresIT {

    private final JdbcTemplate jdbc = ownerJdbc();

    private static UUID participantId;
    private static UUID visitId;
    private static boolean seeded;

    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        seeded = true;
        UUID institution =
                id("INSERT INTO institutions (name, institution_type, city, state) VALUES"
                        + " (?,'MEDICAL_COLLEGE','Delhi','Delhi') RETURNING id",
                        "Clinical " + UUID.randomUUID());
        UUID trial =
                id("INSERT INTO trials (protocol_number, title, sponsor_institution_id, phase)"
                                + " VALUES (?,'T',?::uuid,'III') RETURNING id",
                        "CLIN-" + UUID.randomUUID(), institution.toString());
        UUID site =
                id("INSERT INTO trial_sites (trial_id, institution_id, site_code) VALUES"
                                + " (?::uuid,?::uuid,'C-01') RETURNING id",
                        trial.toString(), institution.toString());
        participantId =
                id("INSERT INTO participants (trial_id, trial_site_id, subject_code,"
                                + " enrollment_date) VALUES (?::uuid,?::uuid,?,CURRENT_DATE)"
                                + " RETURNING id",
                        trial.toString(), site.toString(), "S-" + UUID.randomUUID());
        visitId =
                id("INSERT INTO visits (participant_id, visit_name, visit_number,"
                                + " scheduled_date) VALUES (?::uuid,'V',1,CURRENT_DATE)"
                                + " RETURNING id",
                        participantId.toString());
    }

    private UUID id(String sql, Object... args) {
        return UUID.fromString(jdbc.queryForObject(sql, String.class, args));
    }

    // ── medications (§8.16) ──────────────────────────────────────────────────

    @Test
    void medicationRequiresAType() {
        // STUDY_DRUG vs CONCOMITANT vs RESCUE is the distinction causality assessment rests
        // on; a medication row without it cannot be interpreted during a safety review.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO medications (participant_id,"
                                            + " medication_name, start_date) VALUES"
                                            + " (?::uuid,'Paracetamol',CURRENT_DATE)",
                                        participantId.toString()))
                .hasMessageContaining("medication_type");
    }

    @Test
    void medicationRejectsAnUnknownType() {
        assertThatThrownBy(() -> medication("Paracetamol", "PLACEBO_ISH", "ORAL", "500"))
                .hasMessageContaining("ck_medications_type");
    }

    @Test
    void medicationRejectsAnUnknownRoute() {
        assertThatThrownBy(() -> medication("Paracetamol", "CONCOMITANT", "TELEPATHY", "500"))
                .hasMessageContaining("ck_medications_route");
    }

    @Test
    void medicationDoseIsNumericNotFreeText() {
        // numeric(10,3) per §8.16 — a text dose cannot be compared, summed or range-checked.
        assertThatThrownBy(() -> medication("Paracetamol", "CONCOMITANT", "ORAL", "two tablets"))
                .isNotNull();

        assertThatCode(() -> medication("Ibuprofen", "CONCOMITANT", "ORAL", "400.5"))
                .doesNotThrowAnyException();
    }

    private void medication(String name, String type, String route, String dose) {
        jdbc.update(
                "INSERT INTO medications (participant_id, medication_name, medication_type,"
                        + " route, dose, start_date) VALUES (?::uuid,?,?,?,?::numeric,CURRENT_DATE)",
                participantId.toString(),
                name,
                type,
                route,
                dose);
    }

    // ── observations (§8.15) ─────────────────────────────────────────────────

    @Test
    void observationMustCarryAValue() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO observations (visit_id, observation_code,"
                                            + " observation_name, category) VALUES"
                                            + " (?::uuid,'EMPTY','Nothing','OTHER')",
                                        visitId.toString()))
                .hasMessageContaining("ck_observations_has_value");
    }

    @Test
    void amendedObservationMustCarryAReason() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO observations (visit_id, observation_code,"
                                            + " observation_name, category, value_numeric,"
                                            + " status) VALUES"
                                            + " (?::uuid,'AMD','Amended','LABORATORY',1,'AMENDED')",
                                        visitId.toString()))
                .hasMessageContaining("ck_observations_amendment_reason");
    }

    // ── consents (§8.13) ─────────────────────────────────────────────────────

    @Test
    void onlyOneActiveConsentPerParticipant() {
        // Create the user rather than reusing whatever another test left behind — this class
        // must not depend on execution order.
        UUID obtainedBy =
                id(
                        "INSERT INTO users (email, password_hash, full_name, role_id) VALUES"
                                + " (?,'x','Consent Taker',(SELECT id FROM roles WHERE name ="
                                + " 'PRINCIPAL_INVESTIGATOR')) RETURNING id",
                        UUID.randomUUID() + "@example.in");
        jdbc.update(
                "INSERT INTO consents (participant_id, consent_version, consented_at,"
                        + " consent_method, obtained_by) VALUES"
                        + " (?::uuid,'ICF v1',now(),'WRITTEN',?::uuid)",
                participantId.toString(),
                obtainedBy.toString());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO consents (participant_id, consent_version,"
                                            + " consented_at, consent_method, obtained_by)"
                                            + " VALUES (?::uuid,'ICF v2',now(),'WRITTEN',?::uuid)",
                                        participantId.toString(),
                                        obtainedBy.toString()))
                .hasMessageContaining("uq_consents_one_active");
    }
}
