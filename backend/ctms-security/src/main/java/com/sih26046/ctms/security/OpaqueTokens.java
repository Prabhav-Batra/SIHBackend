package com.sih26046.ctms.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing of opaque refresh tokens (§18.2).
 *
 * <p>The refresh token is opaque rather than a JWT on purpose: a JWT is valid until it
 * expires and carries no server-side state, which is exactly wrong for a credential that
 * must be revocable on logout, password change, or reuse detection.
 */
final class OpaqueTokens {

    /** 256 bits, per §18.2. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OpaqueTokens() {}

    static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * SHA-256 of the token. Unsalted and fast by design — unlike a password this is a
     * 256-bit random value, so there is no dictionary to attack and no need to slow down a
     * lookup that happens on every refresh.
     */
    static String hash(String token) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
