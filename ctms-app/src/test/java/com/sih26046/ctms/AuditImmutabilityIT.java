package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §19.4 — why audit records are immutable.
 *
 * <p>An audit log's value rests entirely on the assumption that it was not edited afterwards.
 * If a sufficiently privileged user can alter it, every entry is only as trustworthy as the
 * least trustworthy account that could reach it — and the entries most worth altering are the
 * ones an attacker would target first.
 *
 * <p>So the guarantee is structural, not administrative. These tests assert it holds against
 * the owner connection, which is the most privileged path the application has.
 */
@SpringBootTest
class AuditImmutabilityIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate appJdbc;

    private final JdbcTemplate owner = ownerJdbc();

    private UUID writeEntry() {
        return UUID.fromString(
                owner.queryForObject(
                        "INSERT INTO audit_logs (action, entity_type, entity_id, outcome)"
                                + " VALUES ('CREATE_TRIAL','trial',?::uuid,'SUCCESS') RETURNING id",
                        String.class,
                        UUID.randomUUID().toString()));
    }

    @Test
    void entriesCanBeWritten() {
        assertThat(writeEntry()).isNotNull();
    }

    @Test
    void entriesCannotBeUpdatedEvenByTheOwner() {
        UUID id = writeEntry();

        assertThatThrownBy(
                        () ->
                                owner.update(
                                        "UPDATE audit_logs SET outcome = 'SUCCESS' WHERE id ="
                                                + " ?::uuid",
                                        id.toString()))
                .hasStackTraceContaining("immutable");
    }

    @Test
    void entriesCannotBeDeletedEvenByTheOwner() {
        UUID id = writeEntry();

        assertThatThrownBy(
                        () -> owner.update("DELETE FROM audit_logs WHERE id = ?::uuid", id.toString()))
                .hasStackTraceContaining("immutable");
    }

    @Test
    void theApplicationRoleHoldsNoUpdateOrDeleteGrant() {
        // A revoked grant is a fact; "administrators do not edit the audit log" is a policy.
        // The trigger and the grant are two independent controls on purpose (§19.4).
        List<String> privileges =
                owner.queryForList(
                        "SELECT privilege_type FROM information_schema.role_table_grants"
                                + " WHERE grantee = 'ctms_app' AND table_name = 'audit_logs'",
                        String.class);

        assertThat(privileges).contains("INSERT", "SELECT");
        assertThat(privileges).doesNotContain("UPDATE", "DELETE", "TRUNCATE");
    }

    @Test
    void deletingAUserDoesNotEraseWhatTheyDid() {
        // ON DELETE SET NULL, never CASCADE (§8.24). Removing an account must not remove the
        // record of its actions — that is exactly the deletion an attacker would want.
        UUID user =
                UUID.fromString(
                        owner.queryForObject(
                                "INSERT INTO users (email, password_hash, full_name, role_id)"
                                    + " VALUES (?,'x','Audit Subject',(SELECT id FROM roles WHERE"
                                    + " name = 'RESEARCH_STAFF')) RETURNING id",
                                String.class,
                                UUID.randomUUID() + "@example.in"));
        UUID entry =
                UUID.fromString(
                        owner.queryForObject(
                                "INSERT INTO audit_logs (user_id, action, entity_type, outcome)"
                                    + " VALUES (?::uuid,'LOGIN','user','SUCCESS') RETURNING id",
                                String.class,
                                user.toString()));

        owner.update("DELETE FROM users WHERE id = ?::uuid", user.toString());

        Integer surviving =
                owner.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE id = ?::uuid AND user_id IS NULL",
                        Integer.class,
                        entry.toString());
        assertThat(surviving).isEqualTo(1);
    }
}
