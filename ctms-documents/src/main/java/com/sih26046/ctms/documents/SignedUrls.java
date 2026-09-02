package com.sih26046.ctms.documents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-signed, expiring URLs for the local storage backend.
 *
 * <p>The local backend needs an equivalent of Cloudinary's signed delivery so that everything
 * above it — the expiry window, the tamper check, the fact that the URL <em>is</em> the
 * credential — is exercised in tests and works in local development. Cloudinary does its own
 * signing; this is the same contract, not a second implementation of it.
 */
final class SignedUrls {

    /**
     * A key generated once per boot.
     *
     * <p>Signed URLs therefore die when the process restarts, which is correct for a
     * development backend: these URLs are meant to live for five minutes, and a key persisted
     * to disk would be one more secret to protect for no benefit.
     */
    private static final byte[] KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(KEY);
    }

    private SignedUrls() {}

    static String sign(String publicId, String resourceType, long expiresAtEpochSecond) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            // The expiry is inside the signed payload, so a leaked URL cannot be handed a
            // longer life by editing the query string.
            String payload = publicId + "|" + resourceType + "|" + expiresAtEpochSecond;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is required by every JVM", e);
        }
    }

    static boolean isValid(
            String publicId, String resourceType, long expiresAtEpochSecond, String signature) {
        if (Instant.now().getEpochSecond() > expiresAtEpochSecond) {
            return false;
        }
        // Constant-time: a byte-by-byte comparison that returns early leaks how much of a
        // forged signature was correct, which is enough to recover the rest one byte at a time.
        return MessageDigest.isEqual(
                sign(publicId, resourceType, expiresAtEpochSecond)
                        .getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    static long expiryFor(Duration ttl) {
        return Instant.now().plus(ttl).getEpochSecond();
    }
}
