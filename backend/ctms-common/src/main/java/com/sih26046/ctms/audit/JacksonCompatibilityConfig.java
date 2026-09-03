package com.sih26046.ctms.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * A classic Jackson 2 {@link ObjectMapper} bean.
 *
 * <p>Spring Boot 4's own auto-configuration now defaults to Jackson 3 ({@code
 * tools.jackson.databind}) via {@code spring-boot-starter-jackson}, and no longer registers a
 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean on its own — even though classic
 * Jackson 2 (chosen deliberately, see this module's {@code build.gradle.kts}) is still on the
 * classpath for {@link AuditTrail}'s old/new-value redaction (§19.5) and, in {@code
 * ctms-security}, {@code SecurityConfig}'s filter chain (which wires up {@code
 * CsrfDoubleSubmitFilter} and {@code RateLimitFilter}, both of which write a JSON error body).
 * Without this bean neither can be constructed and the whole application context fails to
 * start. {@link Jackson2ObjectMapperBuilder} reproduces Boot's own historical defaults (module
 * auto-detection included) rather than a bare {@code new ObjectMapper()}.
 */
@Configuration
class JacksonCompatibilityConfig {

    @Bean
    ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }
}
