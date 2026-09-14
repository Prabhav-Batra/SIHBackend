package com.sih26046.ctms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The binding itself, because that is where this configuration is most likely to break
 * silently: {@code ctms.cors.allowed-origins} is supplied as {@code ${CTMS_CORS_ALLOWED_ORIGINS:}}
 * in application.yml, so the ordinary same-origin deployment hands the binder an empty string
 * rather than an absent key.
 */
class CorsPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EnableProperties.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class EnableProperties {}

    @Test
    void anUnsetEnvironmentVariableLeavesCorsOff() {
        runner.withPropertyValues("ctms.cors.allowed-origins=")
                .run(ctx -> assertThat(ctx.getBean(CorsProperties.class).enabled()).isFalse());
    }

    @Test
    void anAbsentKeyLeavesCorsOff() {
        runner.run(ctx -> assertThat(ctx.getBean(CorsProperties.class).enabled()).isFalse());
    }

    @Test
    void aSingleOriginBinds() {
        runner.withPropertyValues("ctms.cors.allowed-origins=http://localhost:3000")
                .run(
                        ctx -> {
                            CorsProperties p = ctx.getBean(CorsProperties.class);
                            assertThat(p.enabled()).isTrue();
                            assertThat(p.allowedOrigins())
                                    .containsExactly("http://localhost:3000");
                        });
    }

    @Test
    void aCommaSeparatedListBinds() {
        // The shape an operator actually types into a Render environment variable.
        runner.withPropertyValues(
                        "ctms.cors.allowed-origins=http://localhost:3000,https://ctms.example.com")
                .run(
                        ctx ->
                                assertThat(ctx.getBean(CorsProperties.class).allowedOrigins())
                                        .containsExactly(
                                                "http://localhost:3000",
                                                "https://ctms.example.com"));
    }

    @Test
    void aTrailingCommaDoesNotProduceABlankOrigin() {
        runner.withPropertyValues("ctms.cors.allowed-origins=http://localhost:3000,")
                .run(
                        ctx ->
                                assertThat(ctx.getBean(CorsProperties.class).allowedOrigins())
                                        .containsExactly("http://localhost:3000"));
    }

    @Test
    void aWildcardFailsStartup() {
        runner.withPropertyValues("ctms.cors.allowed-origins=*")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
