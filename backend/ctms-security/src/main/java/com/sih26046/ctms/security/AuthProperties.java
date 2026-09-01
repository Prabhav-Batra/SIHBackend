package com.sih26046.ctms.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication configuration (§18.2, §25.3).
 *
 * @param jwtSecret HS256 signing key. No default is supplied anywhere: an application that
 *     cannot find a secret must fail to start rather than fall back to a shipped one.
 * @param accessTokenTtl §18.2 — 15 minutes; expiry is the only control on access tokens
 * @param refreshTokenTtl §18.2 — 14 days, rotating
 */
@ConfigurationProperties(prefix = "ctms.auth")
public record AuthProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    /** HS256 requires a key of at least 256 bits; a shorter one is rejected by Nimbus. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes().length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "ctms.auth.jwt-secret must be at least "
                            + MINIMUM_SECRET_BYTES
                            + " bytes for HS256");
        }
    }
}
