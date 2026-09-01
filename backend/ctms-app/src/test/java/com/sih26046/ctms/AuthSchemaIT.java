package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B2 schema acceptance — the five identity tables of §8.2–§8.6 and their seed data.
 *
 * <p>These assert behaviour the database must enforce on its own, not merely that columns
 * exist: a role set that cannot drift, a permission catalogue that matches §6.3 exactly, and
 * constraints that reject bad rows regardless of what the application does.
 */
@SpringBootTest
class AuthSchemaIT extends AbstractPostgresIT {

    /** The seven roles of §1.2. */
    private static final List<String> EXPECTED_ROLES =
            List.of(
                    "SYSTEM_ADMIN",
                    "PRINCIPAL_INVESTIGATOR",
                    "TRIAL_COORDINATOR",
                    "RESEARCH_STAFF",
                    "ETHICS_MEMBER",
                    "SAFETY_OFFICER",
                    "REGULATORY_OFFICER");

    /** §6.3 catalogue size. Counted from the document; a drift here is a real divergence. */
    private static final int EXPECTED_PERMISSION_COUNT = 58;

    @Autowired JdbcTemplate jdbc;

    @Test
    void seedsExactlyTheSevenRoles() {
        List<String> names =
                jdbc.queryForList("SELECT name FROM roles ORDER BY name", String.class);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_ROLES);
    }

    @Test
    void seedsTheFullPermissionCatalogue() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM permissions", Integer.class);
        assertThat(count).isEqualTo(EXPECTED_PERMISSION_COUNT);
    }

    @Test
    void everyRoleHasAtLeastOnePermission() {
        Integer rolesWithout =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM roles r
                        WHERE NOT EXISTS (
                          SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id)
                        """,
                        Integer.class);
        assertThat(rolesWithout).isZero();
    }

    @Test
    void emailUniquenessIsCaseInsensitive() {
        // §8.2: citext, so A@x.in and a@x.in cannot both register.
        String roleId = jdbc.queryForObject("SELECT id FROM roles LIMIT 1", String.class);
        jdbc.update(
                "INSERT INTO users (email, password_hash, full_name, role_id) VALUES (?,?,?,?::uuid)",
                "Case@Example.in",
                "x",
                "Case Tester",
                roleId);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO users (email, password_hash, full_name, role_id)"
                                                + " VALUES (?,?,?,?::uuid)",
                                        "case@example.in",
                                        "x",
                                        "Case Duplicate",
                                        roleId))
                .hasMessageContaining("uq_users_email");
    }

    @Test
    void sessionsRejectAnUnknownRevocationReason() {
        // §8.6: revoked_reason is CHECK-constrained; an unlisted reason is a bug, not data.
        String roleId = jdbc.queryForObject("SELECT id FROM roles LIMIT 1", String.class);
        String userId =
                jdbc.queryForObject(
                        "INSERT INTO users (email, password_hash, full_name, role_id)"
                                + " VALUES (?,?,?,?::uuid) RETURNING id",
                        String.class,
                        "session-owner@example.in",
                        "x",
                        "Session Owner",
                        roleId);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO sessions
                                          (user_id, family_id, token_hash, expires_at,
                                           revoked_at, revoked_reason)
                                        VALUES (?::uuid, gen_random_uuid(), 'hash-1',
                                                now() + interval '7 days', now(), 'NOT_A_REASON')
                                        """,
                                        userId))
                .hasMessageContaining("ck_sessions_revoked_reason");
    }
}
