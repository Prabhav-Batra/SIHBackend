package com.sih26046.ctms.security;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication configuration (§18.2, §25.3).
 *
 * @param jwtSecret HS256 signing key. No default is supplied anywhere: an application that
 *     cannot find a secret must fail to start rather than fall back to a shipped one.
 * @param accessTokenTtl §18.2 — 15 minutes; expiry is the only control on access tokens
 * @param refreshTokenTtl §18.2 — 14 days, rotating
 * @param cookieSameSite the {@code SameSite} policy for the auth cookies (§18.3). {@code Lax} is
 *     the default and the right value whenever the frontend is served from the same site as the
 *     API. A separately hosted frontend — including a local dev server calling the deployed API —
 *     needs {@code None}, because no browser sends a {@code Lax} cookie on a cross-site
 *     {@code fetch}; see {@link AuthCookies} for what that costs and what still covers it.
 */
@ConfigurationProperties(prefix = "ctms.auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        @DefaultValue("Lax") String cookieSameSite) {

    /** HS256 requires a key of at least 256 bits; a shorter one is rejected by Nimbus. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private static final Set<String> SAME_SITE_VALUES = Set.of("lax", "strict", "none");

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes().length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "ctms.auth.jwt-secret must be at least "
                            + MINIMUM_SECRET_BYTES
                            + " bytes for HS256");
        }
        cookieSameSite = cookieSameSite == null || cookieSameSite.isBlank() ? "Lax" : cookieSameSite.trim();
        if (!SAME_SITE_VALUES.contains(cookieSameSite.toLowerCase(java.util.Locale.ROOT))) {
            // A typo here fails open in the worst way: the cookie is rejected by the browser and
            // every request looks unauthenticated, which reads as "the login endpoint is broken".
            throw new IllegalStateException(
                    "ctms.auth.cookie-same-site must be one of Lax, Strict or None (was: "
                            + cookieSameSite
                            + ")");
        }
    }
}
