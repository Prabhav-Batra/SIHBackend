package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;

import com.sih26046.ctms.security.RlsUserContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scope harness (BACKEND_PHASES B3).
 *
 * <p>Replays a plain {@code SELECT} against every RLS-scoped table as each of the seven roles
 * and compares the row count against a declared matrix. Exact counts, not "some rows": a
 * policy that is too permissive returns more than expected, which an emptiness check would
 * miss entirely.
 *
 * <p>{@link #everyRlsEnabledTableIsRegistered()} is what makes this a harness rather than a
 * test. It asks PostgreSQL which tables have RLS switched on and fails if any of them is
 * absent from the matrix below, so a table added in a later phase cannot quietly ship without
 * its scope declared.
 */
@SpringBootTest
class ScopeHarnessIT extends AbstractPostgresIT {

    @Autowired TransactionTemplate transactions;

    @Autowired JdbcTemplate jdbc;

    /**
     * Expected visible row counts, per table, per role, for the fixture below.
     *
     * <p>Derived from the §5.8 capability matrix. Where §8 overrides the matrix for a specific
     * table, the override is noted inline.
     */
    private static final Map<String, Map<String, Integer>> EXPECTED = new LinkedHashMap<>();

    static {
        // §8.7 overrides the matrix here: institution names and coordinates are public
        // infrastructure facts, and §1.3 makes the map global, so every authenticated session
        // reads them all.
        EXPECTED.put(
                "institutions",
                Map.of(
                        "SYSTEM_ADMIN", 2, "PRINCIPAL_INVESTIGATOR", 2, "TRIAL_COORDINATOR", 2,
                        "RESEARCH_STAFF", 2, "ETHICS_MEMBER", 2, "SAFETY_OFFICER", 2,
                        "REGULATORY_OFFICER", 2));

        // Structural: admin, safety and the regulator read in full; everyone else by assignment.
        EXPECTED.put(
                "trials",
                Map.of(
                        "SYSTEM_ADMIN", 2, "PRINCIPAL_INVESTIGATOR", 1, "TRIAL_COORDINATOR", 0,
                        "RESEARCH_STAFF", 1, "ETHICS_MEMBER", 0, "SAFETY_OFFICER", 2,
                        "REGULATORY_OFFICER", 2));

        EXPECTED.put(
                "trial_sites",
                Map.of(
                        "SYSTEM_ADMIN", 2, "PRINCIPAL_INVESTIGATOR", 1, "TRIAL_COORDINATOR", 0,
                        "RESEARCH_STAFF", 1, "ETHICS_MEMBER", 0, "SAFETY_OFFICER", 2,
                        "REGULATORY_OFFICER", 2));

        // §5.8 gives trial_staff to ADMIN (F), PI (S), COORD (S) and REG (F) only. Research
        // staff, ethics and safety have no access to staffing records at all — note this is
        // narrower than the other structural tables, where safety reads in full.
        EXPECTED.put(
                "trial_staff",
                Map.of(
                        "SYSTEM_ADMIN", 2, "PRINCIPAL_INVESTIGATOR", 2, "TRIAL_COORDINATOR", 0,
                        "RESEARCH_STAFF", 0, "ETHICS_MEMBER", 0, "SAFETY_OFFICER", 0,
                        "REGULATORY_OFFICER", 2));
    }

    private static boolean seeded;
    private static Map<String, UUID> userByRole = new LinkedHashMap<>();

    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        seeded = true;
        JdbcTemplate owner = ownerJdbc();

        UUID instA = institution(owner, "Harness A", "Delhi");
        UUID instB = institution(owner, "Harness B", "Mumbai");
        UUID trialA = trial(owner, instA);
        UUID trialB = trial(owner, instB);
        UUID siteA = site(owner, trialA, instA, "H-A1");
        site(owner, trialB, instB, "H-B1");

        for (String role :
                List.of(
                        "SYSTEM_ADMIN", "PRINCIPAL_INVESTIGATOR", "TRIAL_COORDINATOR",
                        "RESEARCH_STAFF", "ETHICS_MEMBER", "SAFETY_OFFICER",
                        "REGULATORY_OFFICER")) {
            userByRole.put(role, user(owner, role, "ETHICS_MEMBER".equals(role) ? instA : null));
        }

        // Exactly two assignments, both on trial A: one trial-wide, one site-scoped.
        assign(owner, trialA, null, userByRole.get("PRINCIPAL_INVESTIGATOR"), "PI");
        assign(owner, trialA, siteA, userByRole.get("RESEARCH_STAFF"), "STAFF");
    }

    @Test
    void everyRoleSeesExactlyItsDeclaredScope() {
        List<String> mismatches = new java.util.ArrayList<>();

        EXPECTED.forEach(
                (table, byRole) ->
                        byRole.forEach(
                                (role, expected) -> {
                                    int actual = countAs(userByRole.get(role), table);
                                    if (actual != expected) {
                                        mismatches.add(
                                                "%s as %s: expected %d, saw %d"
                                                        .formatted(table, role, expected, actual));
                                    }
                                }));

        assertThat(mismatches).isEmpty();
    }

    @Test
    void everyRlsEnabledTableIsRegistered() {
        // Asks the database what it is actually protecting, rather than trusting this file to
        // have kept up. A table that gains RLS in a later phase without a scope declaration
        // fails here instead of shipping unverified.
        List<String> rlsTables =
                ownerJdbc()
                        .queryForList(
                                """
                                SELECT c.relname
                                FROM pg_class c
                                JOIN pg_namespace n ON n.oid = c.relnamespace
                                WHERE n.nspname = 'public'
                                  AND c.relkind = 'r'
                                  AND c.relrowsecurity
                                ORDER BY c.relname
                                """,
                                String.class);

        assertThat(rlsTables)
                .as("RLS-enabled tables missing a scope declaration in ScopeHarnessIT")
                .containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
    }

    private int countAs(UUID user, String table) {
        Integer count =
                RlsUserContext.callAs(
                        user,
                        () ->
                                transactions.execute(
                                        tx ->
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM " + table,
                                                        Integer.class)));
        return count == null ? 0 : count;
    }

    // ── fixture helpers, all on the owner connection ─────────────────────────

    private static UUID uuid(JdbcTemplate t, String sql, Object... args) {
        return UUID.fromString(t.queryForObject(sql, String.class, args));
    }

    private static UUID institution(JdbcTemplate t, String name, String city) {
        return uuid(
                t,
                "INSERT INTO institutions (name, institution_type, city, state) VALUES"
                        + " (?,'MEDICAL_COLLEGE',?,?) RETURNING id",
                name + " " + UUID.randomUUID(),
                city,
                city);
    }

    private static UUID trial(JdbcTemplate t, UUID institution) {
        return uuid(
                t,
                "INSERT INTO trials (protocol_number, title, sponsor_institution_id, phase)"
                        + " VALUES (?,'Harness trial',?::uuid,'III') RETURNING id",
                "HARNESS-" + UUID.randomUUID(),
                institution.toString());
    }

    private static UUID site(JdbcTemplate t, UUID trial, UUID institution, String code) {
        return uuid(
                t,
                "INSERT INTO trial_sites (trial_id, institution_id, site_code) VALUES"
                        + " (?::uuid,?::uuid,?) RETURNING id",
                trial.toString(),
                institution.toString(),
                code + "-" + UUID.randomUUID().toString().substring(0, 4));
    }

    private static UUID user(JdbcTemplate t, String roleName, UUID institution) {
        return uuid(
                t,
                "INSERT INTO users (email, password_hash, full_name, role_id, institution_id)"
                        + " VALUES (?,'x','Harness',(SELECT id FROM roles WHERE name = ?),?::uuid)"
                        + " RETURNING id",
                UUID.randomUUID() + "@example.in",
                roleName,
                institution == null ? null : institution.toString());
    }

    private static void assign(JdbcTemplate t, UUID trial, UUID site, UUID user, String role) {
        t.update(
                "INSERT INTO trial_staff (trial_id, trial_site_id, user_id, staff_role) VALUES"
                        + " (?::uuid,?::uuid,?::uuid,?)",
                trial.toString(),
                site == null ? null : site.toString(),
                user.toString(),
                role);
    }
}
