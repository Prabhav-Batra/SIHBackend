package com.sih26046.ctms.security;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the authentication services from configuration (§18.2). */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    /** Injected rather than called statically, so token lifetimes are testable. */
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    public AccessTokenService accessTokenService(AuthProperties properties, Clock authClock) {
        return new AccessTokenService(
                properties.jwtSecret(), properties.accessTokenTtl(), authClock);
    }

    @Bean
    public RefreshTokenService refreshTokenService(
            SessionRepository sessions, AuthProperties properties, Clock authClock) {
        return new RefreshTokenService(sessions, properties.refreshTokenTtl(), authClock);
    }
}
