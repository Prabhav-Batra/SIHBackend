package com.sih26046.ctms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (§18.4).
 *
 * <p>Argon2id with OWASP's recommended parameters. The work factors are named constants
 * rather than inline numbers because they are a security control that will need revising as
 * hardware improves, and a reviewer should be able to see what they currently are.
 */
@Configuration
public class PasswordEncoderConfig {

    /** Salt length in bytes. */
    private static final int SALT_LENGTH = 16;

    /** Derived hash length in bytes. */
    private static final int HASH_LENGTH = 32;

    /** Lanes. OWASP: parallelism = 4. */
    private static final int PARALLELISM = 4;

    /** Memory cost in KiB. OWASP: 64 MiB. This is the parameter that resists GPU attack. */
    private static final int MEMORY_KIB = 65536;

    /** Time cost — number of passes. OWASP: 3. */
    private static final int ITERATIONS = 3;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(
                SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }
}
