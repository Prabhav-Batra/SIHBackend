package com.sih26046.ctms.security;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * Cookie construction for the auth tokens (§18.3).
 *
 * <p>The two cookies deliberately differ. The access cookie is {@code SameSite=Lax} and
 * site-wide, because ordinary navigation must carry it. The refresh cookie is
 * {@code SameSite=Strict} and scoped to the refresh path, so it is transmitted only on the
 * one endpoint that needs it and never travels cross-site at all.
 *
 * <p>Both are {@code HttpOnly}: an XSS defect can then act within the page but cannot
 * exfiltrate a credential for offline reuse. Tokens are never placed in {@code localStorage}.
 */
public final class AuthCookies {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String REFRESH_PATH = "/api/v1/auth/refresh";

    private AuthCookies() {}

    public static ResponseCookie access(String token, Duration maxAge) {
        return ResponseCookie.from(ACCESS_COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie refresh(String token, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_PATH)
                .maxAge(maxAge)
                .build();
    }

    /** Zero-length, zero-age cookies that clear the browser's copies on logout. */
    public static ResponseCookie clearAccess() {
        return access("", Duration.ZERO);
    }

    public static ResponseCookie clearRefresh() {
        return refresh("", Duration.ZERO);
    }
}
