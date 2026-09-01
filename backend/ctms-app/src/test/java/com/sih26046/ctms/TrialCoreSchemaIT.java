package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** §8.7–§8.10 — institutions, trials, sites, and the staff assignment table RLS depends on. */
@SpringBootTest
class TrialCoreSchemaIT extends AbstractPostgresIT {

    // The owner connection: these tests exercise CHECK constraints and generated columns,
    // not visibility. Running them through ctms_app would additionally require binding an
    // identity and satisfying the V6 policies, which is a different test's job.
    private final JdbcTemplate jdbc = ownerJdbc();

    private String institution(Double lat, Double lon) {
        return jdbc.queryForObject(
                "INSERT INTO institutions (name, institution_type, city, state, latitude,"
                        + " longitude) VALUES (?,?,?,?,?,?) RETURNING id",
                String.class,
                "Institute " + UUID.randomUUID(),
                "MEDICAL_COLLEGE",
                "Delhi",
                "Delhi",
                lat,
                lon);
    }

    private String trial(String institutionId) {
        return jdbc.queryForObject(
                "INSERT INTO trials (protocol_number, title, sponsor_institution_id, phase)"
                        + " VALUES (?,?,?::uuid,?) RETURNING id",
                String.class,
                "CT-" + UUID.randomUUID(),
                "A trial",
                institutionId,
                "III");
    }

    @Test
    void locationIsGeneratedFromLatitudeAndLongitude() {
        // §10.2 — generated, so the point and the coordinates cannot drift apart.
        String id = institution(28.6139, 77.2090);

        Double lon =
                jdbc.queryForObject(
                        "SELECT ST_X(location::geometry) FROM institutions WHERE id = ?::uuid",
                        Double.class,
                        id);
        assertThat(lon).isCloseTo(77.2090, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void locationIsNullWhenCoordinatesAreAbsent() {
        String id = institution(null, null);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT location IS NULL FROM institutions WHERE id = ?::uuid",
                                Boolean.class,
                                id))
                .isTrue();
    }

    @Test
    void rejectsHalfACoordinate() {
        // §8.7 — half a coordinate is worse than none: it produces a point on the equator.
        assertThatThrownBy(() -> institution(28.6139, null))
                .hasMessageContaining("ck_institutions_coordinate_pair");
    }

    @Test
    void rejectsAnOutOfRangeLatitude() {
        assertThatThrownBy(() -> institution(120.0, 77.2090))
                .hasMessageContaining("ck_institutions_lat_range");
    }

    @Test
    void rejectsEnrollmentAboveTarget() {
        String inst = institution(28.6139, 77.2090);
        String id = trial(inst);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE trials SET target_enrollment = 10,"
                                                + " current_enrollment = 11 WHERE id = ?::uuid",
                                        id))
                .hasMessageContaining("ck_trials_enrollment_bounds");
    }

    @Test
    void rejectsAnUnknownTrialPhase() {
        String inst = institution(28.6139, 77.2090);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO trials (protocol_number, title,"
                                            + " sponsor_institution_id, phase) VALUES"
                                            + " (?,?,?::uuid,?)",
                                        "CT-" + UUID.randomUUID(),
                                        "A trial",
                                        inst,
                                        "PHASE_ZERO"))
                .hasMessageContaining("ck_trials_phase");
    }

    @Test
    void siteCodeIsUniqueWithinATrialButNotAcrossTrials() {
        // Two institutions, so this exercises uq_trial_sites_trial_code rather than
        // uq_trial_sites_trial_institution — which would otherwise fire first and make the
        // test pass for the wrong reason.
        String instA = institution(28.6139, 77.2090);
        String instB = institution(19.0760, 72.8777);
        String trialA = trial(instA);
        String trialB = trial(instA);

        jdbc.update(
                "INSERT INTO trial_sites (trial_id, institution_id, site_code) VALUES"
                        + " (?::uuid,?::uuid,?)",
                trialA,
                instA,
                "DEL-01");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO trial_sites (trial_id, institution_id,"
                                                + " site_code) VALUES (?::uuid,?::uuid,?)",
                                        trialA,
                                        instB,
                                        "DEL-01"))
                .hasMessageContaining("uq_trial_sites_trial_code");

        // The same code under a different trial is a different site and must be allowed.
        assertThatCode(
                        () ->
                                jdbc.update(
                                        "INSERT INTO trial_sites (trial_id, institution_id,"
                                                + " site_code) VALUES (?::uuid,?::uuid,?)",
                                        trialB,
                                        instA,
                                        "DEL-01"))
                .doesNotThrowAnyException();
    }

    @Test
    void siteLocationFallsBackToTheInstitution() {
        // §10.2 — a site without its own coordinates sits where its institution sits.
        String inst = institution(28.6139, 77.2090);
        String trialId = trial(inst);
        String siteId =
                jdbc.queryForObject(
                        "INSERT INTO trial_sites (trial_id, institution_id, site_code) VALUES"
                                + " (?::uuid,?::uuid,?) RETURNING id",
                        String.class,
                        trialId,
                        inst,
                        "DEL-02");

        Double lon =
                jdbc.queryForObject(
                        "SELECT ST_X(effective_location::geometry) FROM trial_sites_located"
                                + " WHERE id = ?::uuid",
                        Double.class,
                        siteId);
        assertThat(lon).isCloseTo(77.2090, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void geographyColumnsAreGistIndexed() {
        // §28.2 — without GiST every map query is a sequential scan.
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE indexname IN"
                                        + " ('ix_institutions_location','ix_trial_sites_location')",
                                String.class))
                .containsExactlyInAnyOrder(
                        "ix_institutions_location", "ix_trial_sites_location");
    }
}
