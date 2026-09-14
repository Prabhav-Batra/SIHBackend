package com.sih26046.ctms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * The browser preflight is the request that has to succeed before any of the others exist.
 *
 * <p>It is an {@code OPTIONS} carrying no cookie and no {@code X-CSRF-Token} — it cannot carry
 * them, by construction. With no CORS configuration on the chain it falls through to
 * {@code anyRequest().authenticated()} and the entry point answers 401, so the frontend reports
 * a failed login even though the login endpoint is {@code permitAll} and was never reached.
 */
class CorsPreflightTest {

    private static final String ORIGIN = "http://localhost:3000";

    private final SecurityConfig config = new SecurityConfig();

    private CorsConfigurationSource sourceFor(String... origins) {
        return config.corsConfigurationSource(new CorsProperties(List.of(origins)));
    }

    /** Fires a real preflight through Spring's own CorsFilter, as the filter chain would. */
    private MockHttpServletResponse preflight(CorsConfigurationSource source, String method)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        request.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-csrf-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new CorsFilter(source).doFilter(request, response, chain);
        return response;
    }

    @Test
    void preflightIsAnsweredWithoutAuthenticationWhenTheOriginIsAllowed() throws Exception {
        MockHttpServletResponse response = preflight(sourceFor(ORIGIN), "POST");

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);
    }

    @Test
    void preflightAllowsCredentialsSoTheBrowserWillSendAndStoreTheAuthCookies() throws Exception {
        MockHttpServletResponse response = preflight(sourceFor(ORIGIN), "POST");

        // Without this the browser drops Set-Cookie on the login response and omits the cookie
        // on every later call — login looks fine, everything after it is 401.
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isEqualTo("true");
    }

    @Test
    void preflightAllowsTheCsrfHeader() throws Exception {
        MockHttpServletResponse response = preflight(sourceFor(ORIGIN), "POST");

        // X-CSRF-Token is not CORS-safelisted, so an unnamed one fails the preflight and every
        // mutating call dies before it is sent.
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .containsIgnoringCase(AuthCookies.CSRF_HEADER);
    }

    @Test
    void anUnlistedOriginIsRefused() throws Exception {
        MockHttpServletResponse response = preflight(sourceFor(ORIGIN), "POST");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        request.addHeader(HttpHeaders.ORIGIN, "https://evil.example.com");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        MockHttpServletResponse refused = new MockHttpServletResponse();
        new CorsFilter(sourceFor(ORIGIN)).doFilter(request, refused, new MockFilterChain());

        assertThat(refused.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(refused.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void everyMethodTheApiUsesSurvivesPreflight() throws Exception {
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            assertThat(preflight(sourceFor(ORIGIN), method).getStatus())
                    .as("preflight for %s", method)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
    }

    @Test
    void withNoConfiguredOriginsCorsStaysOffEntirely() {
        // The same-origin deployment must behave exactly as it did before this existed: the
        // source returns null, CorsFilter adds no headers and passes the request through.
        CorsConfigurationSource source = sourceFor();

        assertThat(source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/trials")))
                .isNull();
    }

    @Test
    void blankEntriesDoNotSwitchCorsOnWithAnUnmatchableOrigin() {
        // An unset env var binds as the empty string; a trailing comma leaves an empty element.
        assertThat(new CorsProperties(List.of("")).enabled()).isFalse();
        assertThat(new CorsProperties(List.of("   ")).enabled()).isFalse();
        assertThat(new CorsProperties(List.of(ORIGIN, "")).allowedOrigins())
                .containsExactly(ORIGIN);
    }

    @Test
    void surroundingWhitespaceInACommaSeparatedListIsTrimmed() {
        assertThat(new CorsProperties(List.of(" " + ORIGIN + " ")).allowedOrigins())
                .containsExactly(ORIGIN);
    }

    @Test
    void aWildcardOriginIsRejectedAtStartupRatherThanAtRuntime() {
        // '*' with allowCredentials(true) is invalid per the CORS spec; Spring throws only when
        // the first request arrives, which would look like an intermittent runtime fault.
        assertThatThrownBy(() -> new CorsProperties(List.of("*")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot contain '*'");
    }
}
