package com.sih26046.ctms.security;

import java.time.Instant;
import java.util.UUID;

/**
 * A newly issued refresh token.
 *
 * @param token the raw opaque token — the only moment it exists in plaintext; only its
 *     SHA-256 hash is persisted (§8.6)
 */
public record IssuedRefreshToken(
        String token, UUID sessionId, UUID familyId, UUID userId, Instant expiresAt) {}
