package com.sih26046.ctms.security;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh token issue, rotation, and reuse detection (§18.6).
 *
 * <p>Every refresh issues a new token and revokes the one presented; both belong to the same
 * family. Presenting an already-exchanged token revokes the entire family — the server cannot
 * distinguish a legitimate retry from a replayed theft, and leaving a thief with a working
 * session is the worse of the two failures.
 */
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final SessionRepository sessions;
    private final Duration ttl;
    private final Clock clock;

    public RefreshTokenService(SessionRepository sessions, Duration ttl, Clock clock) {
        this.sessions = sessions;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * Issues a refresh token, starting a new family.
     *
     * @param familyId an existing family to join, or {@code null} to start one
     */
    @Transactional
    public IssuedRefreshToken issue(UUID userId, InetAddress ip, String userAgent) {
        return issueInFamily(userId, UUID.randomUUID(), ip, userAgent);
    }

    private IssuedRefreshToken issueInFamily(
            UUID userId, UUID familyId, InetAddress ip, String userAgent) {
        Instant now = clock.instant();
        Instant expiry = now.plus(ttl);
        String token = OpaqueTokens.generate();

        SessionEntity session =
                new SessionEntity(
                        UUID.randomUUID(),
                        userId,
                        familyId,
                        OpaqueTokens.hash(token),
                        now,
                        expiry,
                        ip,
                        userAgent);
        sessions.save(session);

        return new IssuedRefreshToken(token, session.getId(), familyId, userId, expiry);
    }

    /**
     * Exchanges a refresh token for its successor.
     *
     * @throws RefreshTokenReuseException if the token was already exchanged — the family is
     *     revoked before this is thrown
     * @throws IllegalArgumentException if the token is unknown or expired
     */
    // noRollbackFor is load-bearing, not a style choice. Spring rolls back on any
    // RuntimeException leaving a @Transactional method, so throwing the reuse exception
    // would undo the family revocation performed moments earlier: reuse would be detected
    // and logged, the caller would get a 401, and the stolen token's family would remain
    // live. The revocation must survive the exception that reports it.
    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public IssuedRefreshToken rotate(String presentedToken, InetAddress ip, String userAgent) {
        SessionEntity presented =
                sessions.findByTokenHash(OpaqueTokens.hash(presentedToken))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown refresh token"));

        Instant now = clock.instant();

        if (presented.isRevoked()) {
            revokeFamily(presented.getFamilyId(), now);
            log.warn(
                    "Refresh token reuse detected for family {} (user {}); family revoked",
                    presented.getFamilyId(),
                    presented.getUserId());
            throw new RefreshTokenReuseException(presented.getFamilyId());
        }

        if (!presented.getExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException("Expired refresh token");
        }

        presented.revoke(SessionRevocationReason.ROTATED, now);
        sessions.save(presented);

        return issueInFamily(presented.getUserId(), presented.getFamilyId(), ip, userAgent);
    }

    /** Revokes one session, e.g. on logout. */
    @Transactional
    public void revoke(UUID sessionId, SessionRevocationReason reason) {
        sessions.findById(sessionId)
                .filter(s -> !s.isRevoked())
                .ifPresent(
                        s -> {
                            s.revoke(reason, clock.instant());
                            sessions.save(s);
                        });
    }

    private void revokeFamily(UUID familyId, Instant at) {
        List<SessionEntity> live = sessions.findAllByFamilyIdAndRevokedAtIsNull(familyId);
        live.forEach(s -> s.revoke(SessionRevocationReason.REUSE_DETECTED, at));
        sessions.saveAll(live);
    }
}
