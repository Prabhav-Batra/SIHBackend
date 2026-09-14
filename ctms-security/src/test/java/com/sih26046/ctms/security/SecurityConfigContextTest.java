package com.sih26046.ctms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih26046.ctms.security.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Boots the real chain against the real auto-configuration.
 *
 * <p>The unit tests around {@link CorsProperties} exercise the CORS bean in isolation, which is
 * exactly why they missed the way this first shipped: Spring MVC's own
 * {@code HandlerMappingIntrospector} is <em>also</em> a {@link CorsConfigurationSource}, so
 * there are always two beans of that type in a web application and injecting one by type alone
 * fails the context outright. Nothing short of starting a web context catches that, so this
 * test starts one.
 */
class SecurityConfigContextTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    // WebMvcAutoConfiguration is the one that matters: it contributes
                    // mvcHandlerMappingIntrospector, the second CorsConfigurationSource whose
                    // presence is the whole point of this test.
                    .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class))
                    .withUserConfiguration(SecurityConfig.class, StubCollaborators.class);

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class StubCollaborators {

        @Bean
        AccessTokenService accessTokenService() {
            return mock(AccessTokenService.class);
        }

        @Bean
        SessionValidator sessionValidator() {
            return mock(SessionValidator.class);
        }

        @Bean
        RateLimiter rateLimiter() {
            return mock(RateLimiter.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void theContextStartsWithCorsDisabled() {
        // The same-origin deployment: the default, and the path every existing install takes.
        runner.run(
                ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(SecurityFilterChain.class);
                });
    }

    @Test
    void theContextStartsWithCorsEnabled() {
        runner.withPropertyValues("ctms.cors.allowed-origins=http://localhost:3000")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            assertThat(ctx).hasSingleBean(SecurityFilterChain.class);
                        });
    }

    @Test
    void moreThanOneCorsConfigurationSourceBeanExistsInAWebContext() {
        // Guards the guard. If this ever stops being true, the @Qualifier on filterChain looks
        // like dead ceremony and someone will remove it — and the context will break again.
        runner.run(
                ctx ->
                        assertThat(ctx.getBeanNamesForType(CorsConfigurationSource.class))
                                .hasSizeGreaterThan(1)
                                .contains("corsConfigurationSource"));
    }

    @Test
    void theChainUsesOurSourceRatherThanTheMvcIntrospector() {
        // The introspector resolves CORS from @CrossOrigin annotations, of which this codebase
        // has none — binding to it would start cleanly and then answer no preflight at all.
        runner.withPropertyValues("ctms.cors.allowed-origins=http://localhost:3000")
                .run(
                        ctx -> {
                            CorsConfigurationSource source =
                                    ctx.getBean(
                                            "corsConfigurationSource",
                                            CorsConfigurationSource.class);
                            MockHttpServletRequest request =
                                    new MockHttpServletRequest("POST", "/api/v1/auth/login");

                            assertThat(source.getCorsConfiguration(request)).isNotNull();
                            assertThat(source.getCorsConfiguration(request).getAllowedOrigins())
                                    .containsExactly("http://localhost:3000");
                        });
    }
}
