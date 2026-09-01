package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;

import com.sih26046.ctms.security.RlsUserContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §7.5 — structural table policies, and the fail-closed default.
 *
 * <p>Fixtures are written through the owner connection; every assertion reads through
 * {@code ctms_app}. Building fixtures through the policies under test would prove only that
 * they are self-consistent.
 */
@SpringBootTest
class TrialScopeRlsIT extends AbstractPostgresIT {

    @Autowired TransactionTemplate transactions;

    @Autowired JdbcTemplate jdbc;

    private static UUID institutionA;
    private static UUID trialA;
    private static UUID siteA1;
    private static UUID trialB;

    private static UUID staffOnA1;
    private static UUID piOnTrialA;
    private static UUID unassignedStaff;
    private static UUID admin;
    private static UUID safetyOfficer;
    private static UUID regulator;

    private static boolean seeded;

    /**
     * Seeded per class on first test rather than in {@code @BeforeAll}: that callback runs
     * before the Spring context refreshes, so Flyway has not yet created the tables.
     */
    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        seeded = true;
        JdbcTemplate owner = ownerJdbc();

        institutionA = uuid(owner, """
                INSERT INTO institutions (name, institution_type, city, state)
                VALUES ('Institute A','MEDICAL_COLLEGE','Delhi','Delhi') RETURNING id""");
        UUID institutionB = uuid(owner, """
                INSERT INTO institutions (name, institution_type, city, state)
                VALUES ('Institute B','MEDICAL_COLLEGE','Mumbai','Maharashtra') RETURNING id""");

        trialA = trial(owner, "CT-A-" + UUID.randomUUID(), institutionA);
        trialB = trial(owner, "CT-B-" + UUID.randomUUID(), institutionB);

        siteA1 = site(owner, trialA, institutionA, "DEL-01");

        staffOnA1 = user(owner, "RESEARCH_STAFF", null);
        piOnTrialA = user(owner, "PRINCIPAL_INVESTIGATOR", null);
        unassignedStaff = user(owner, "RESEARCH_STAFF", null);
        admin = user(owner, "SYSTEM_ADMIN", null);
        safetyOfficer = user(owner, "SAFETY_OFFICER", null);
        regulator = user(owner, "REGULATORY_OFFICER", null);

        assign(owner, trialA, siteA1, staffOnA1, "STAFF");
        assign(owner, trialA, null, piOnTrialA, "PI"); // trial-wide: site_id IS NULL
    }

    private static UUID uuid(JdbcTemplate t, String sql, Object... args) {
        return UUID.fromString(t.queryForObject(sql, String.class, args));
    }

    private static UUID trial(JdbcTemplate t, String protocol, UUID institution) {
        return uuid(
                t,
                "INSERT INTO trials (protocol_number, title, sponsor_institution_id, phase)"
                        + " VALUES (?,?,?::uuid,'III') RETURNING id",
                protocol,
                "Trial " + protocol,
                institution.toString());
    }

    private static UUID site(JdbcTemplate t, UUID trial, UUID institution, String code) {
        return uuid(
                t,
                "INSERT INTO trial_sites (trial_id, institution_id, site_code)"
                        + " VALUES (?::uuid,?::uuid,?) RETURNING id",
                trial.toString(),
                institution.toString(),
                code);
    }

    private static UUID user(JdbcTemplate t, String roleName, UUID institution) {
        return uuid(
                t,
                "INSERT INTO users (email, password_hash, full_name, role_id, institution_id)"
                        + " VALUES (?,'x','Scope Tester',"
                        + " (SELECT id FROM roles WHERE name = ?), ?::uuid) RETURNING id",
                UUID.randomUUID() + "@example.in",
                roleName,
                institution == null ? null : institution.toString());
    }

    private static void assign(
            JdbcTemplate t, UUID trial, UUID site, UUID user, String staffRole) {
        t.update(
                "INSERT INTO trial_staff (trial_id, trial_site_id, user_id, staff_role)"
                        + " VALUES (?::uuid,?::uuid,?::uuid,?)",
                trial.toString(),
                site == null ? null : site.toString(),
                user.toString(),
                staffRole);
    }

    private List<String> trialsVisibleTo(UUID user) {
        return RlsUserContext.callAs(
                user,
                () ->
                        transactions.execute(
                                tx ->
                                        jdbc.queryForList(
                                                "SELECT protocol_number FROM trials",
                                                String.class)));
    }

    // ── the guard ────────────────────────────────────────────────────────────

    @Test
    void applicationConnectsAsANonSuperuserRole() {
        // PostgreSQL exempts superusers from RLS entirely. If this ever regresses, every
        // scope assertion below would pass no matter what the policies said.
        String user = jdbc.queryForObject("SELECT current_user", String.class);
        Boolean superuser =
                jdbc.queryForObject(
                        "SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                        Boolean.class);

        assertThat(user).isEqualTo("ctms_app");
        assertThat(superuser).isFalse();
    }

    // ── fail-closed ──────────────────────────────────────────────────────────

    @Test
    void withoutAnIdentityNothingIsVisible() {
        // §7.4 — a path that forgets to bind an identity shows an empty screen, not a leak.
        List<String> visible =
                transactions.execute(
                        tx -> jdbc.queryForList("SELECT protocol_number FROM trials", String.class));

        assertThat(visible).isEmpty();
    }

    // ── scoped roles ─────────────────────────────────────────────────────────

    @Test
    void siteScopedStaffSeeOnlyTheirAssignedTrial() {
        List<String> visible = trialsVisibleTo(staffOnA1);

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0)).startsWith("CT-A-");
    }

    @Test
    void aTrialWideAssignmentAlsoResolves() {
        assertThat(trialsVisibleTo(piOnTrialA)).hasSize(1);
    }

    @Test
    void anUnassignedUserSeesNothing() {
        assertThat(trialsVisibleTo(unassignedStaff)).isEmpty();
    }

    // ── broad roles ──────────────────────────────────────────────────────────

    @Test
    void adminSafetyAndRegulatorSeeEveryTrial() {
        // §7.5 structural class: these three read in full, without an assignment.
        for (UUID broad : List.of(admin, safetyOfficer, regulator)) {
            assertThat(trialsVisibleTo(broad))
                    .as("broad role %s", broad)
                    .hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void institutionsAreReadableByAnyAuthenticatedSession() {
        // §8.7 — names and coordinates are public infrastructure facts.
        List<String> visible =
                RlsUserContext.callAs(
                        unassignedStaff,
                        () ->
                                transactions.execute(
                                        tx ->
                                                jdbc.queryForList(
                                                        "SELECT name FROM institutions",
                                                        String.class)));

        assertThat(visible).isNotEmpty();
    }

    @Test
    void sitesFollowTheirTrialsScope() {
        List<String> visible =
                RlsUserContext.callAs(
                        staffOnA1,
                        () ->
                                transactions.execute(
                                        tx ->
                                                jdbc.queryForList(
                                                        "SELECT site_code FROM trial_sites",
                                                        String.class)));

        assertThat(visible).containsExactly("DEL-01");
    }
}
