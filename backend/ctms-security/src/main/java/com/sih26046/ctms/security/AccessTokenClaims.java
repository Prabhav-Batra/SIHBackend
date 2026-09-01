package com.sih26046.ctms.security;

import java.util.UUID;

/**
 * The claims of a verified access token (§18.2).
 *
 * <p>Note what is absent: no trial or site identifiers. Scope is resolved by the database on
 * every query (§7.3), so revoking an assignment takes effect on the next request rather than
 * on the next login. Putting scope in the token would make revocation wait for expiry.
 *
 * @param userId {@code sub} — the authenticated user
 * @param sessionId {@code sid} — the `sessions` row this token belongs to, so it can be revoked
 * @param role {@code role} — for rendering navigation only, never for authorization (§18.2)
 * @param jti unique token identifier
 */
public record AccessTokenClaims(UUID userId, UUID sessionId, String role, UUID jti) {}
