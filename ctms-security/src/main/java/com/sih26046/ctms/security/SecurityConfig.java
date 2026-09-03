package com.sih26046.ctms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih26046.ctms.security.ratelimit.RateLimitFilter;
import com.sih26046.ctms.security.ratelimit.RateLimiter;
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

/**
 * The filter chain (§6.4, §18).
 *
 * <p>Everything is denied by default and opened explicitly. The failure mode of forgetting to
 * secure a new endpoint is then "too strict" rather than "wide open" (§6.5).
 */
@Configuration
@EnableMethodSecurity
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
            ObjectMapper mapper)
            throws Exception {
        return http
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
}
