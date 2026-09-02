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
        // ── site-scoped clinical class (§7.5) ────────────────────────────────
        // SYSTEM_ADMIN and REGULATORY_OFFICER are excluded *entirely*, not merely scoped:
        // §5.1 keeps platform administration out of participant data, and ADR-010 confines
        // oversight to aggregates. SAFETY_OFFICER reads clinical data only for participants
        // with a reported adverse event (§5.6); with no events in this fixture, zero.
        // Two participants, both in the assigned scope. Only the second has an adverse event.
        Map<String, Integer> clinicalBoth = counts(0, 2, 0, 2, 0, 0, 0);
        EXPECTED.put("participants", clinicalBoth);
        EXPECTED.put("visits", clinicalBoth);
        // Only participant 1 was consented.
        EXPECTED.put("consents", counts(0, 1, 0, 1, 0, 0, 0));

        // §5.6 — the Safety Officer's clinical read is event-triggered. They see the
        // observations and medications of the participant with a reported adverse event, and
        // not those of the participant without one. That asymmetry is the whole point: access
        // follows clinical justification rather than role.
        EXPECTED.put("observations", counts(0, 2, 0, 2, 0, 1, 0));
        EXPECTED.put("medications", counts(0, 2, 0, 2, 0, 1, 0));

        // §5.8 gives the Safety Officer no access to participants, visits or consents even for
        // an event they are reviewing — the justification extends to clinical measurements,
        // not to the enrolment record.

        // ── safety (§7.5 safety class) ────────────────────────────────────────
        EXPECTED.put("adverse_events", counts(0, 1, 0, 1, 0, 1, 0));
        EXPECTED.put("safety_reviews", counts(0, 1, 0, 0, 0, 1, 0));

        // ── institution-scoped (§7.5) ─────────────────────────────────────────
        // The regulator reads submission status and decisions (§5.7) but never the
        // deliberation content in ethics_reviews.
        EXPECTED.put("ethics_submissions", counts(0, 1, 0, 0, 1, 0, 1));
        EXPECTED.put("ethics_reviews", counts(0, 0, 0, 0, 1, 0, 0));

        // ── documents and compliance ──────────────────────────────────────────
        // The seeded document is trial-level, so the ethics member — scoped by institution —
        // does not see it.
        EXPECTED.put("documents", counts(0, 1, 0, 1, 0, 1, 1));
        // Reference data: the requirement catalogue is readable by every authenticated session.
        EXPECTED.put("compliance_requirements", counts(1, 1, 1, 1, 1, 1, 1));
        EXPECTED.put("trial_compliance", counts(1, 1, 0, 0, 0, 0, 1));

        // §8.12 — the strictest policy on the platform. Re-identification requires the
        // participant_identity:read permission, which V3 grants to no role at all, so every
        // role sees nothing even where the participant itself is in scope.
        EXPECTED.put(
                "participant_identities",
                Map.of(
                        "SYSTEM_ADMIN", 0, "PRINCIPAL_INVESTIGATOR", 0, "TRIAL_COORDINATOR", 0,
                        "RESEARCH_STAFF", 0, "ETHICS_MEMBER", 0, "SAFETY_OFFICER", 0,
                        "REGULATORY_OFFICER", 0));

        EXPECTED.put(
                "trial_staff",
                Map.of(
                        "SYSTEM_ADMIN", 2, "PRINCIPAL_INVESTIGATOR", 2, "TRIAL_COORDINATOR", 0,
                        "RESEARCH_STAFF", 0, "ETHICS_MEMBER", 0, "SAFETY_OFFICER", 0,
                        "REGULATORY_OFFICER", 2));
    }

    /** Reads in §5.8 column order: ADMIN, PI, COORD, STAFF, ETHICS, SAFETY, REG. */
    private static Map<String, Integer> counts(
            int admin, int pi, int coord, int staff, int ethics, int safety, int regulator) {
        return Map.of(
                "SYSTEM_ADMIN", admin,
                "PRINCIPAL_INVESTIGATOR", pi,
                "TRIAL_COORDINATOR", coord,
                "RESEARCH_STAFF", staff,
                "ETHICS_MEMBER", ethics,
                "SAFETY_OFFICER", safety,
                "REGULATORY_OFFICER", regulator);
    }

    private static boolean seeded;
    private static Map<String, UUID> userByRole = new LinkedHashMap<>();

    /**
     * Per-table count queries, restricted to this class's own fixture rows.
     *
     * <p>Counting whole tables would make the harness depend on what every other test class
     * happens to have committed to the shared database — it broke the moment a schema test
     * inserted an institution. Restricting to known ids keeps the counts exact, which is the
     * property that catches an over-permissive policy, without coupling to execution order.
     * Leakage is still detectable because the fixture deliberately contains rows the caller
     * must not see (trial B).
     */
    private static final Map<String, String> COUNT_SQL = new LinkedHashMap<>();

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

        // One participant inside the assigned scope. Nothing is seeded on trial B, so a
        // policy that leaked across trials would show up as a count of 2 rather than 1.
        UUID participant = participant(owner, trialA, siteA);
        UUID visit = visit(owner, participant);
        observation(owner, visit);
        medication(owner, participant);
        consent(owner, participant, userByRole.get("PRINCIPAL_INVESTIGATOR"));
        identity(owner, participant);

        // A second participant, identical in scope, differing only in having an adverse
        // event. Every Safety Officer expectation below is the difference between the two.
        UUID participant2 = participant(owner, trialA, siteA);
        UUID visit2 = visit(owner, participant2);
        observation(owner, visit2);
        medication(owner, participant2);
        UUID event = adverseEvent(owner, trialA, participant2,
                userByRole.get("RESEARCH_STAFF"));
        safetyReview(owner, event, userByRole.get("SAFETY_OFFICER"));

        UUID submission = ethicsSubmission(owner, trialA, instA,
                userByRole.get("PRINCIPAL_INVESTIGATOR"));
        ethicsReview(owner, submission, userByRole.get("ETHICS_MEMBER"));

        UUID document = document(owner, trialA, userByRole.get("PRINCIPAL_INVESTIGATOR"));
        UUID requirement = complianceRequirement(owner);
        trialCompliance(owner, trialA, requirement);

        String trials = "'%s','%s'".formatted(trialA, trialB);
        COUNT_SQL.put(
                "institutions",
                "SELECT count(*) FROM institutions WHERE id IN ('%s','%s')"
                        .formatted(instA, instB));
        COUNT_SQL.put(
                "trials", "SELECT count(*) FROM trials WHERE id IN (%s)".formatted(trials));
        COUNT_SQL.put(
                "trial_sites",
                "SELECT count(*) FROM trial_sites WHERE trial_id IN (%s)".formatted(trials));
        COUNT_SQL.put(
                "trial_staff",
                "SELECT count(*) FROM trial_staff WHERE trial_id IN (%s)".formatted(trials));
        COUNT_SQL.put(
                "participants",
                "SELECT count(*) FROM participants WHERE trial_id IN (%s)".formatted(trials));
        COUNT_SQL.put(
                "consents",
                "SELECT count(*) FROM consents WHERE participant_id = '%s'"
                        .formatted(participant));
        COUNT_SQL.put(
                "visits",
                "SELECT count(*) FROM visits WHERE participant_id = '%s'".formatted(participant));
        COUNT_SQL.put(
                "observations",
                "SELECT count(*) FROM observations WHERE visit_id = '%s'".formatted(visit));
        COUNT_SQL.put(
                "medications",
                "SELECT count(*) FROM medications WHERE participant_id = '%s'"
                        .formatted(participant));
        COUNT_SQL.put(
                "participant_identities",
                "SELECT count(*) FROM participant_identities WHERE participant_id = '%s'"
                        .formatted(participant));

        String participants = "'%s','%s'".formatted(participant, participant2);
        String visits = "'%s','%s'".formatted(visit, visit2);
        COUNT_SQL.put(
                "participants",
                "SELECT count(*) FROM participants WHERE id IN (%s)".formatted(participants));
        COUNT_SQL.put(
                "visits",
                "SELECT count(*) FROM visits WHERE participant_id IN (%s)"
                        .formatted(participants));
        COUNT_SQL.put(
                "observations",
                "SELECT count(*) FROM observations WHERE visit_id IN (%s)".formatted(visits));
        COUNT_SQL.put(
                "medications",
                "SELECT count(*) FROM medications WHERE participant_id IN (%s)"
                        .formatted(participants));
        COUNT_SQL.put(
                "adverse_events",
                "SELECT count(*) FROM adverse_events WHERE participant_id IN (%s)"
                        .formatted(participants));
        COUNT_SQL.put(
                "safety_reviews",
                "SELECT count(*) FROM safety_reviews WHERE adverse_event_id = '%s'"
                        .formatted(event));
        COUNT_SQL.put(
                "ethics_submissions",
                "SELECT count(*) FROM ethics_submissions WHERE id = '%s'".formatted(submission));
        COUNT_SQL.put(
                "ethics_reviews",
                "SELECT count(*) FROM ethics_reviews WHERE ethics_submission_id = '%s'"
                        .formatted(submission));
        COUNT_SQL.put(
                "documents", "SELECT count(*) FROM documents WHERE id = '%s'".formatted(document));
        COUNT_SQL.put(
                "compliance_requirements",
                "SELECT count(*) FROM compliance_requirements WHERE id = '%s'"
                        .formatted(requirement));
        COUNT_SQL.put(
                "trial_compliance",
                "SELECT count(*) FROM trial_compliance WHERE trial_id = '%s'".formatted(trialA));
    }

    private static UUID adverseEvent(
            JdbcTemplate t, UUID trial, UUID participant, UUID reportedBy) {
        return uuid(
                t,
                "INSERT INTO adverse_events (participant_id, trial_id, event_term, description,"
                        + " onset_date, severity, reported_by) VALUES"
                        + " (?::uuid,?::uuid,'Nausea','Reported after dosing',CURRENT_DATE,"
                        + " 'MILD',?::uuid) RETURNING id",
                participant.toString(),
                trial.toString(),
                reportedBy.toString());
    }

    private static void safetyReview(JdbcTemplate t, UUID event, UUID reviewer) {
        t.update(
                "INSERT INTO safety_reviews (adverse_event_id, reviewer_id, assessed_severity,"
                        + " assessed_causality, is_expected, decision) VALUES"
                        + " (?::uuid,?::uuid,'MILD','POSSIBLE',true,'ACCEPTED')",
                event.toString(),
                reviewer.toString());
    }

    private static UUID ethicsSubmission(
            JdbcTemplate t, UUID trial, UUID institution, UUID submittedBy) {
        return uuid(
                t,
                "INSERT INTO ethics_submissions (trial_id, institution_id, submission_number,"
                        + " submission_type, submitted_by, summary) VALUES"
                        + " (?::uuid,?::uuid,?,'INITIAL',?::uuid,'Initial submission')"
                        + " RETURNING id",
                trial.toString(),
                institution.toString(),
                "IEC/" + UUID.randomUUID().toString().substring(0, 8),
                submittedBy.toString());
    }

    private static void ethicsReview(JdbcTemplate t, UUID submission, UUID reviewer) {
        t.update(
                "INSERT INTO ethics_reviews (ethics_submission_id, reviewer_id, recommendation,"
                        + " comments) VALUES (?::uuid,?::uuid,'APPROVE','Looks sound')",
                submission.toString(),
                reviewer.toString());
    }

    private static UUID document(JdbcTemplate t, UUID trial, UUID uploadedBy) {
        UUID id = UUID.randomUUID();
        t.update(
                "INSERT INTO documents (id, document_family_id, trial_id, document_type, title,"
                        + " file_name, mime_type, file_size_bytes, checksum_sha256,"
                        + " cloudinary_public_id, cloudinary_resource_type, uploaded_by) VALUES"
                        + " (?::uuid,?::uuid,?::uuid,'PROTOCOL','Protocol v1','protocol.pdf',"
                        + " 'application/pdf',1024,'abc123','ctms/protocol','raw',?::uuid)",
                id.toString(),
                id.toString(),
                trial.toString(),
                uploadedBy.toString());
        return id;
    }

    private static UUID complianceRequirement(JdbcTemplate t) {
        return uuid(
                t,
                "INSERT INTO compliance_requirements (code, title, description, category)"
                        + " VALUES (?,'CDSCO registration','Trial must be registered',"
                        + " 'REGULATORY') RETURNING id",
                "REQ-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static void trialCompliance(JdbcTemplate t, UUID trial, UUID requirement) {
        t.update(
                "INSERT INTO trial_compliance (trial_id, compliance_requirement_id) VALUES"
                        + " (?::uuid,?::uuid)",
                trial.toString(),
                requirement.toString());
    }

    @Test
    void everyDeclaredTableHasACountQuery() {
        // Guards the harness against itself: a table declared in EXPECTED but missing here
        // would throw rather than silently skip, but this fails with a clearer message.
        assertThat(COUNT_SQL.keySet()).containsAll(EXPECTED.keySet());
    }

    private static UUID participant(JdbcTemplate t, UUID trial, UUID site) {
        return uuid(
                t,
                "INSERT INTO participants (trial_id, trial_site_id, subject_code,"
                        + " enrollment_date) VALUES (?::uuid,?::uuid,?,CURRENT_DATE) RETURNING id",
                trial.toString(),
                site.toString(),
                "SUBJ-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static UUID visit(JdbcTemplate t, UUID participant) {
        return uuid(
                t,
                "INSERT INTO visits (participant_id, visit_name, visit_number, scheduled_date)"
                        + " VALUES (?::uuid,'Screening',1,CURRENT_DATE) RETURNING id",
                participant.toString());
    }

    private static void observation(JdbcTemplate t, UUID visit) {
        t.update(
                "INSERT INTO observations (visit_id, observation_code, observation_name,"
                        + " category, value_numeric, unit) VALUES"
                        + " (?::uuid,'VITALS_SBP','Systolic BP','VITAL_SIGN',120,'mmHg')",
                visit.toString());
    }

    private static void medication(JdbcTemplate t, UUID participant) {
        t.update(
                "INSERT INTO medications (participant_id, medication_name, medication_type,"
                        + " start_date) VALUES (?::uuid,'Paracetamol','CONCOMITANT',CURRENT_DATE)",
                participant.toString());
    }

    private static void consent(JdbcTemplate t, UUID participant, UUID obtainedBy) {
        t.update(
                "INSERT INTO consents (participant_id, consent_version, consented_at,"
                        + " consent_method, obtained_by) VALUES"
                        + " (?::uuid,'ICF v1.0',now(),'WRITTEN',?::uuid)",
                participant.toString(),
                obtainedBy.toString());
    }

    private static void identity(JdbcTemplate t, UUID participant) {
        t.update(
                "INSERT INTO participant_identities (participant_id, full_name) VALUES"
                        + " (?::uuid,'Real Name')",
                participant.toString());
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
                                                        COUNT_SQL.get(table), Integer.class)));
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
