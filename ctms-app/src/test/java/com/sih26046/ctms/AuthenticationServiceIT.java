package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sih26046.ctms.security.AuthenticatedPrincipal;
import com.sih26046.ctms.security.AuthenticationService;
import com.sih26046.ctms.security.InvalidCredentialsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** §18.5 — login flow, lockout, and the absence of an account-existence oracle. */
@SpringBootTest
class AuthenticationServiceIT extends AbstractPostgresIT {

    private static final String PASSWORD = "a-sufficiently-long-passphrase";

    @Autowired AuthenticationService authentication;

    @Autowired PasswordEncoder passwordEncoder;

    @Autowired JdbcTemplate jdbc;

    private String createUser(String roleName, String status) {
        String email = UUID.randomUUID() + "@example.in";
        String roleId =
                jdbc.queryForObject(
                        "SELECT id FROM roles WHERE name = ?", String.class, roleName);
        jdbc.update(
                "INSERT INTO users (email, password_hash, full_name, role_id, status,"
                        + " institution_id) VALUES (?,?,?,?::uuid,?,?::uuid)",
                email,
                passwordEncoder.encode(PASSWORD),
                "Login Tester",
                roleId,
                status,
                // ETHICS_MEMBER needs an institution (ck_users_ethics_needs_institution).
                "ETHICS_MEMBER".equals(roleName) ? UUID.randomUUID().toString() : null);
        return email;
    }

    private int failedCount(String email) {
        return jdbc.queryForObject(
                "SELECT failed_login_count FROM users WHERE email = ?", Integer.class, email);
    }

    private String status(String email) {
        return jdbc.queryForObject(
                "SELECT status FROM users WHERE email = ?", String.class, email);
    }

    @Test
    void authenticatesAValidUserAndResolvesTheirPermissions() {
        String email = createUser("RESEARCH_STAFF", "ACTIVE");

        AuthenticatedPrincipal principal = authentication.authenticate(email, PASSWORD);

        assertThat(principal.roleName()).isEqualTo("RESEARCH_STAFF");
        assertThat(principal.permissions()).contains("observation:create");
    }

    @Test
    void matchesEmailCaseInsensitively() {
        String email = createUser("RESEARCH_STAFF", "ACTIVE");

        assertThat(authentication.authenticate(email.toUpperCase(), PASSWORD)).isNotNull();
    }

    @Test
    void rejectsAWrongPassword() {
        String email = createUser("RESEARCH_STAFF", "ACTIVE");

        assertThatThrownBy(() -> authentication.authenticate(email, "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsAnUnknownAccountWithTheSameFailureAsAWrongPassword() {
        // §18.5: the response must not reveal whether an account exists.
        assertThatThrownBy(
                        () ->
                                authentication.authenticate(
                                        "nobody-" + UUID.randomUUID() + "@example.in", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsAnInactiveAccount() {
        String email = createUser("RESEARCH_STAFF", "INACTIVE");

        assertThatThrownBy(() -> authentication.authenticate(email, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void countsFailedAttempts() {
        String email = createUser("RESEARCH_STAFF", "ACTIVE");

        assertThatThrownBy(() -> authentication.authenticate(email, "wrong")).isInstanceOf(
                InvalidCredentialsException.class);
        assertThatThrownBy(() -> authentication.authenticate(email, "wrong")).isInstanceOf(
                InvalidCredentialsException.class);

        assertThat(failedCount(email)).isEqualTo(2);
    }

    @Test
    void locksTheAccountAtTenFailures() {
        String email = createUser("RESEARCH_STAFF", "ACTIVE");

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> authentication.authenticate(email, "wrong"))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThat(status(email)).isEqualTo("LOCKED");
        // Even the correct password must now fail — the lock is the point.
        assertThatThrownBy(() -> authentication.authenticate(email, PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void resetsTheFailureCountOnSuccess() {
        String email = createUser("RESEARCH_STAFF", "ACTIVE");
        assertThatThrownBy(() -> authentication.authenticate(email, "wrong")).isInstanceOf(
                InvalidCredentialsException.class);

        authentication.authenticate(email, PASSWORD);

        assertThat(failedCount(email)).isZero();
    }

    @Test
    void recordsTheLastLoginTime() {
        String email = createUser("RESEARCH_STAFF", "ACTIVE");

        authentication.authenticate(email, PASSWORD);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_login_at FROM users WHERE email = ?",
                                java.time.OffsetDateTime.class,
                                email))
                .isNotNull();
    }
}
