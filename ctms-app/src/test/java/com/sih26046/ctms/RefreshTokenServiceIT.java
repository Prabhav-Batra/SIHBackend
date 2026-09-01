package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sih26046.ctms.security.IssuedRefreshToken;
import com.sih26046.ctms.security.RefreshTokenReuseException;
import com.sih26046.ctms.security.RefreshTokenService;
import com.sih26046.ctms.security.SessionRevocationReason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §18.6 — refresh rotation and reuse detection.
 *
 * <p>The behaviour under test is the one that actually protects a stolen session: a refresh
 * token is single-use, and presenting one twice revokes the entire family. The server cannot
 * distinguish a legitimate retry from a replayed theft, so it logs out both.
 */
@SpringBootTest
class RefreshTokenServiceIT extends AbstractPostgresIT {

    @Autowired RefreshTokenService refreshTokens;

    @Autowired JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void createUser() {
        String roleId =
                jdbc.queryForObject(
                        "SELECT id FROM roles WHERE name = 'RESEARCH_STAFF'", String.class);
        userId =
                UUID.fromString(
                        jdbc.queryForObject(
                                "INSERT INTO users (email, password_hash, full_name, role_id)"
                                        + " VALUES (?,?,?,?::uuid) RETURNING id",
                                String.class,
                                UUID.randomUUID() + "@example.in",
                                "irrelevant",
                                "Refresh Tester",
                                roleId));
    }

    @Test
    void neverStoresTheRawToken() {
        // §8.6: a database leak must not yield usable tokens.
        IssuedRefreshToken issued = refreshTokens.issue(userId, null, null);

        Integer matches =
                jdbc.queryForObject(
                        "SELECT count(*) FROM sessions WHERE token_hash = ?",
                        Integer.class,
                        issued.token());
        assertThat(matches).isZero();
    }

    @Test
    void rotationIssuesANewTokenInTheSameFamily() {
        IssuedRefreshToken first = refreshTokens.issue(userId, null, null);

        IssuedRefreshToken second = refreshTokens.rotate(first.token(), null, null);

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.familyId()).isEqualTo(first.familyId());
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
    }

    @Test
    void rotationRevokesThePresentedToken() {
        IssuedRefreshToken first = refreshTokens.issue(userId, null, null);

        refreshTokens.rotate(first.token(), null, null);

        String reason =
                jdbc.queryForObject(
                        "SELECT revoked_reason FROM sessions WHERE id = ?::uuid",
                        String.class,
                        first.sessionId().toString());
        assertThat(reason).isEqualTo(SessionRevocationReason.ROTATED.name());
    }

    @Test
    void replayingARotatedTokenRevokesTheWholeFamily() {
        IssuedRefreshToken first = refreshTokens.issue(userId, null, null);
        IssuedRefreshToken second = refreshTokens.rotate(first.token(), null, null);
        IssuedRefreshToken third = refreshTokens.rotate(second.token(), null, null);

        // The attacker replays a token that was already exchanged.
        assertThatThrownBy(() -> refreshTokens.rotate(first.token(), null, null))
                .isInstanceOf(RefreshTokenReuseException.class);

        List<String> reasons =
                jdbc.queryForList(
                        "SELECT revoked_reason FROM sessions WHERE family_id = ?::uuid",
                        String.class,
                        first.familyId().toString());
        // Every session in the family is revoked — including the one the legitimate user
        // still held. Safety over convenience (§18.6).
        assertThat(reasons).hasSize(3).doesNotContainNull();
        assertThat(reasons).contains(SessionRevocationReason.REUSE_DETECTED.name());

        Integer stillLive =
                jdbc.queryForObject(
                        "SELECT count(*) FROM sessions"
                                + " WHERE family_id = ?::uuid AND revoked_at IS NULL",
                        Integer.class,
                        third.familyId().toString());
        assertThat(stillLive).isZero();
    }

    @Test
    void rejectsAnUnknownToken() {
        assertThatThrownBy(() -> refreshTokens.rotate("not-a-real-token", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
