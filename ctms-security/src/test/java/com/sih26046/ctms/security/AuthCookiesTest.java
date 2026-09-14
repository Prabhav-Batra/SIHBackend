package com.sih26046.ctms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §18.3. The cookie attributes are the other half of the cross-site failure: CORS lets the
 * request be made, {@code SameSite} decides whether the browser attaches the credential to it.
 * A {@code Lax} access cookie is simply not sent on a cross-site {@code fetch}, so the API sees
 * an anonymous request and answers 401 — with no CORS error to point at.
 */
class AuthCookiesTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Nested
    class SameSiteDefault {

        @Test
        void accessCookieStaysLax() {
            assertThat(AuthCookies.access("t", TTL, "Lax").toString())
                    .contains("SameSite=Lax")
                    .contains("HttpOnly")
                    .contains("Secure")
                    .contains("Path=/");
        }

        @Test
        void refreshCookieStaysStrictAndPathScoped() {
            assertThat(AuthCookies.refresh("t", TTL, "Lax").toString())
                    .contains("SameSite=Strict")
                    .contains("Path=" + AuthCookies.REFRESH_PATH);
        }

        @Test
        void csrfCookieIsReadableByTheFrontend() {
            // Deliberately not HttpOnly — the page must read it to echo X-CSRF-Token.
            assertThat(AuthCookies.csrf("t", TTL, "Lax").toString()).doesNotContain("HttpOnly");
        }

        @Test
        void anAbsentPolicyFallsBackToLax() {
            assertThat(AuthCookies.access("t", TTL, null).toString()).contains("SameSite=Lax");
            assertThat(AuthCookies.access("t", TTL, "  ").toString()).contains("SameSite=Lax");
        }
    }

    @Nested
    class CrossSite {

        @Test
        void accessCookieBecomesNoneSoTheBrowserSendsItCrossSite() {
            assertThat(AuthCookies.access("t", TTL, "None").toString())
                    .contains("SameSite=None")
                    // None is honoured only on a Secure cookie; without it the browser drops it.
                    .contains("Secure");
        }

        @Test
        void refreshCookieIsRelaxedTooOrRefreshWouldFailWhereLoginSucceeded() {
            assertThat(AuthCookies.refresh("t", TTL, "None").toString())
                    .contains("SameSite=None")
                    .contains("Path=" + AuthCookies.REFRESH_PATH);
        }

        @Test
        void csrfCookieBecomesNone() {
            assertThat(AuthCookies.csrf("t", TTL, "None").toString()).contains("SameSite=None");
        }

        @Test
        void logoutCookiesCarryTheSameAttributesAsTheOnesTheyErase() {
            // A Lax erasure sent on a cross-site response is itself refused by the browser,
            // which would leave a live credential in place after "logout".
            assertThat(AuthCookies.clearAccess("None").toString())
                    .contains("SameSite=None")
                    .contains("Max-Age=0");
            assertThat(AuthCookies.clearRefresh("None").toString()).contains("SameSite=None");
            assertThat(AuthCookies.clearCsrf("None").toString()).contains("SameSite=None");
        }

        @Test
        void theValueIsCaseInsensitive() {
            assertThat(AuthCookies.access("t", TTL, "none").toString()).contains("SameSite=None");
            assertThat(AuthCookies.refresh("t", TTL, "NONE").toString()).contains("SameSite=None");
        }
    }

    @Nested
    class Configuration {

        private AuthProperties properties(String sameSite) {
            return new AuthProperties(
                    "0123456789abcdef0123456789abcdef", TTL, Duration.ofDays(14), sameSite);
        }

        @Test
        void defaultsToLax() {
            assertThat(properties(null).cookieSameSite()).isEqualTo("Lax");
            assertThat(properties("  ").cookieSameSite()).isEqualTo("Lax");
        }

        @Test
        void acceptsTheThreeLegalValues() {
            assertThat(properties("None").cookieSameSite()).isEqualTo("None");
            assertThat(properties("Strict").cookieSameSite()).isEqualTo("Strict");
            assertThat(properties("Lax").cookieSameSite()).isEqualTo("Lax");
        }

        @Test
        void aTypoFailsStartupInsteadOfEveryRequest() {
            // "Nonne" would otherwise be emitted verbatim, the browser would discard the cookie,
            // and the symptom would be a 401 on every call with nothing in the logs.
            assertThatThrownBy(() -> properties("Nonne"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cookie-same-site");
        }
    }
}
