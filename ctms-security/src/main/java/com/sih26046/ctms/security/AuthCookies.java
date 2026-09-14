package com.sih26046.ctms.security;

import java.time.Duration;
import java.util.Locale;
import org.springframework.http.ResponseCookie;

/**
 * Cookie construction for the auth tokens (§18.3).
 *
 * <p>The two cookies deliberately differ. The access cookie is {@code SameSite=Lax} and
 * site-wide, because ordinary navigation must carry it. The refresh cookie is
 * {@code SameSite=Strict} and scoped to the refresh path, so it is transmitted only on the
 * one endpoint that needs it and never travels cross-site at all.
 *
 * <p>That pairing assumes the frontend is served from the same site as the API. When it is not
 * — a separately hosted frontend, or a local dev server against the deployed API — the browser
 * withholds a {@code Lax} cookie from every {@code fetch} the page makes, and a {@code Strict}
 * one from all of them, so the API sees an anonymous request and answers 401. Setting
 * {@code ctms.auth.cookie-same-site=None} relaxes both to {@code None}, which is the only value
 * a browser will send cross-site. It is a real reduction in defence-in-depth: {@code SameSite}
 * is one of the two halves of CSRF protection here, leaving the double-submit token
 * (CsrfDoubleSubmitFilter, §18.12) and the origin allow-list ({@link CorsProperties}) carrying
 * it alone. Turn it on only together with an explicit, short CORS origin list.
 *
 * <p>Both token cookies are {@code HttpOnly}: an XSS defect can then act within the page but
 * cannot exfiltrate a credential for offline reuse. Tokens are never placed in
 * {@code localStorage}.
 *
 * <p>The third cookie, {@code csrf_token} (§18.12), is the opposite on purpose: it is
 * deliberately readable by JavaScript. It is not a credential — it is a value the legitimate
 * first-party page must be able to read and echo back in an {@code X-CSRF-Token} header. Its
 * security comes from the same-origin policy stopping a foreign page from reading it, not from
 * secrecy the way the other two cookies work.
 */
public final class AuthCookies {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String CSRF_COOKIE = "csrf_token";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String REFRESH_PATH = "/api/v1/auth/refresh";

    /** The value that makes a cookie travel cross-site at all. */
    static final String NONE = "None";

    private AuthCookies() {}

    /**
     * The refresh cookie is the strictest of the three, but "stricter than the configured
     * policy" is unreachable once that policy is {@code None} — a {@code Strict} refresh cookie
     * would simply never be sent, and refresh would fail where login had just succeeded.
     */
    private static String refreshPolicy(String sameSite) {
        return isNone(sameSite) ? NONE : "Strict";
    }

    private static boolean isNone(String sameSite) {
        return NONE.equalsIgnoreCase(sameSite == null ? "" : sameSite.trim());
    }

    /**
     * {@code SameSite=None} is only honoured on a {@code Secure} cookie, and browsers drop a
     * {@code None} cookie that lacks it outright. Every cookie here is already {@code Secure},
     * so this is a normalisation of the caller's string, not a policy decision.
     */
    private static String normalise(String sameSite) {
        if (sameSite == null || sameSite.isBlank()) {
            return "Lax";
        }
        String trimmed = sameSite.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT)
                + trimmed.substring(1).toLowerCase(Locale.ROOT);
    }

    public static ResponseCookie access(String token, Duration maxAge, String sameSite) {
        return ResponseCookie.from(ACCESS_COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite(normalise(sameSite))
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie refresh(String token, Duration maxAge, String sameSite) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite(refreshPolicy(sameSite))
                .path(REFRESH_PATH)
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie csrf(String token, Duration maxAge, String sameSite) {
        return ResponseCookie.from(CSRF_COOKIE, token)
                .httpOnly(false)
                .secure(true)
                .sameSite(normalise(sameSite))
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    // Zero-length, zero-age cookies that clear the browser's copies on logout. They carry the
    // same attributes as the cookies they replace: a browser that would refuse the original
    // cross-site would refuse the erasure too, leaving a stale credential in place.

    public static ResponseCookie clearAccess(String sameSite) {
        return access("", Duration.ZERO, sameSite);
    }

    public static ResponseCookie clearRefresh(String sameSite) {
        return refresh("", Duration.ZERO, sameSite);
    }

    public static ResponseCookie clearCsrf(String sameSite) {
        return csrf("", Duration.ZERO, sameSite);
    }
}
