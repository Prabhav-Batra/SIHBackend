package com.sih26046.ctms.security;

import java.util.UUID;

/**
 * A refresh token was presented after it had already been exchanged (§18.6).
 *
 * <p>Either the legitimate client retried, or a stolen token is being replayed. The server
 * cannot tell the two apart, so the whole family is revoked before this is thrown.
 */
public class RefreshTokenReuseException extends RuntimeException {

    private final UUID familyId;

    public RefreshTokenReuseException(UUID familyId) {
        super("Refresh token reuse detected; family revoked");
        this.familyId = familyId;
    }

    public UUID familyId() {
        return familyId;
    }
}
