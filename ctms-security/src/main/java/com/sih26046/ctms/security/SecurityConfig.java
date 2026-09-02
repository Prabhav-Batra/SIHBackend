package com.sih26046.ctms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
    public SecurityFilterChain filterChain(HttpSecurity http, AccessTokenAuthFilter authFilter)
            throws Exception {
        return http
                // Authentication is by cookie-borne JWT; there is no server-side HTTP session
                // to fixate, and no form or basic login to fall back to.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()) // double-submit token arrives in B9 (§18.12)
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
                                                "/actuator/health/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        e ->
                                // 401 for "not authenticated". Spring's default would redirect
                                // to a login page, which is wrong for an API.
                                e.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
