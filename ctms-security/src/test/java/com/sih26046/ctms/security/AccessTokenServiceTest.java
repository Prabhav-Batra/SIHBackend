package com.sih26046.ctms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * §18.2 — access token design.
 *
 * <p>15-minute HS256 JWT carrying {@code sub}, {@code sid}, {@code role} and {@code jti}.
 * The {@code role} claim exists only so the frontend can render navigation without a round
 * trip; it is never the authorization source, and these tests do not treat it as one.
 */
class AccessTokenServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final AccessTokenService service =
            new AccessTokenService(SECRET, TTL, Clock.systemUTC());

    @Test
    void roundTripsTheClaimsItWasIssuedWith() {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        AccessTokenClaims claims = service.verify(service.issue(user, session, "RESEARCH_STAFF"));

        assertThat(claims.userId()).isEqualTo(user);
        assertThat(claims.sessionId()).isEqualTo(session);
        assertThat(claims.role()).isEqualTo("RESEARCH_STAFF");
    }

    @Test
    void issuesAUniqueJtiPerToken() {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        AccessTokenClaims first = service.verify(service.issue(user, session, "RESEARCH_STAFF"));
        AccessTokenClaims second = service.verify(service.issue(user, session, "RESEARCH_STAFF"));

        assertThat(first.jti()).isNotEqualTo(second.jti());
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        AccessTokenService attacker =
                new AccessTokenService(
                        "a-completely-different-secret-of-sufficient-length!", TTL,
                        Clock.systemUTC());
        String forged = attacker.issue(UUID.randomUUID(), UUID.randomUUID(), "SYSTEM_ADMIN");

        assertThatThrownBy(() -> service.verify(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnExpiredToken() {
        // Issued two hours ago with a 15-minute lifetime — far outside any clock skew.
        Clock twoHoursAgo =
                Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String stale =
                new AccessTokenService(SECRET, TTL, twoHoursAgo)
                        .issue(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_STAFF");

        assertThatThrownBy(() -> service.verify(stale)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATamperedPayload() {
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_STAFF");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];

        assertThatThrownBy(() -> service.verify(tampered)).isInstanceOf(JwtException.class);
    }
}
