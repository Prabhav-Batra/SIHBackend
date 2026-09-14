package com.sih26046.ctms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih26046.ctms.security.ratelimit.RateLimitFilter;
import com.sih26046.ctms.security.ratelimit.RateLimiter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The filter chain (§6.4, §18).
 *
 * <p>Everything is denied by default and opened explicitly. The failure mode of forgetting to
 * secure a new endpoint is then "too strict" rather than "wide open" (§6.5).
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    @Bean
    public AccessTokenAuthFilter accessTokenAuthFilter(
            AccessTokenService accessTokens, SessionValidator sessionValidator) {
        return new AccessTokenAuthFilter(accessTokens, sessionValidator);
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            AccessTokenAuthFilter authFilter,
            RateLimiter rateLimiter,
            ObjectMapper mapper,
            // By name, not by type. Spring MVC's own HandlerMappingIntrospector is also a
            // CorsConfigurationSource, so an unqualified parameter is ambiguous and the context
            // refuses to start — and the plausible-looking alternative, letting Spring Security
            // resolve the source itself, silently prefers the introspector (which serves
            // @CrossOrigin annotations, of which this codebase has none) over the bean below.
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource)
            throws Exception {
        return http
                // Must come before authorization: a browser preflight is an unauthenticated,
                // credential-less OPTIONS, so without this Spring's CorsFilter never answers it
                // and anyRequest().authenticated() turns it into a 401 the frontend reports as
                // a failed login. The source returns null unless ctms.cors.allowed-origins is
                // set, so a same-origin deployment behaves exactly as before.
                .cors(cors -> cors.configurationSource(corsSource))
                // Authentication is by cookie-borne JWT; there is no server-side HTTP session
                // to fixate, and no form or basic login to fall back to.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Spring's own CSRF machinery assumes a session-scoped token; this platform's
                // is the double-submit cookie pattern (§18.12) instead, enforced by
                // CsrfDoubleSubmitFilter below — a second, competing mechanism here would be
                // redundant at best and would fight the first at worst.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/refresh",
                                                // A signed download URL is itself the
                                                // credential (§16.4). It must be followable by
                                                // a browser redirect without a session, and it
                                                // expires in five minutes; the endpoint behind
                                                // it verifies the signature before serving a
                                                // byte.
                                                "/api/v1/documents/content",
                                                "/actuator/health/**",
                                                // The API explorer itself (§0 of TEST.md) — a
                                                // local-testing convenience, not part of the
                                                // platform's own auth surface. It only serves
                                                // the docs page and the OpenAPI JSON; every
                                                // request it makes through "Try it out" still
                                                // hits the real, still-authenticated endpoint.
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/v3/api-docs/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        e ->
                                // 401 for "not authenticated". Spring's default would redirect
                                // to a login page, which is wrong for an API.
                                e.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // §18.15. HSTS only ever renders on an HTTPS request (Spring Security's own
                // requiresSecureRequestMatcher), so it is silently absent over plain-HTTP local
                // testing rather than something to toggle by profile. Caddy sets these again at
                // the edge in production (spec §18.15's original design) — belt and suspenders,
                // not a contradiction: either layer being bypassed still leaves the other.
                .headers(
                        headers ->
                                headers
                                        .contentTypeOptions(withDefaults -> {})
                                        .frameOptions(frame -> frame.deny())
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31536000))
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                        .contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'self'; "
                                                                        + "img-src 'self' data:; "
                                                                        + "style-src 'self' 'unsafe-inline'; "
                                                                        + "script-src 'self' 'unsafe-inline'; "
                                                                        + "connect-src 'self'; "
                                                                        + "frame-ancestors 'none'; "
                                                                        + "object-src 'none'; "
                                                                        + "base-uri 'self'"))
                                        .addHeaderWriter(
                                                new StaticHeadersWriter(
                                                        "Permissions-Policy",
                                                        "geolocation=(), microphone=(), camera=()")))
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfDoubleSubmitFilter(mapper), AccessTokenAuthFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, mapper), CsrfDoubleSubmitFilter.class)
                .build();
    }

    /**
     * Returns a configuration only for origins named in {@code ctms.cors.allowed-origins}, and
     * {@code null} for every other request — which is how Spring's {@code CorsFilter} is told
     * "this is not a CORS request I handle" and leaves the response untouched.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        if (!properties.enabled()) {
            return request -> null;
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        // The CSRF double-submit header (§18.12) is not a CORS-safelisted request header, so it
        // has to be named here or every mutating call fails its preflight.
        config.setAllowedHeaders(
                List.of("Content-Type", "Accept", AuthCookies.CSRF_HEADER, "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        // The whole point: without this the browser sends no cookies and honours no Set-Cookie
        // on the response, so login would appear to succeed and every later call would 401.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        return request -> config;
    }
}
