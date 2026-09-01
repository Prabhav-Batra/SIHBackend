package com.sih26046.ctms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * §18.4 — Argon2id with OWASP parameters.
 *
 * <p>Memory-hardness is the point: an attacker with GPUs gains far less than against bcrypt
 * or PBKDF2, because memory bandwidth does not parallelise the way raw hashing does.
 */
class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    void usesArgon2idWithTheParametersFromTheSpecification() {
        String hash = encoder.encode("correct horse battery staple");

        // The encoded string carries its own parameters; asserting on them means a silent
        // downgrade of the work factor cannot pass review.
        assertThat(hash).startsWith("$argon2id$");
        assertThat(hash).contains("$m=65536,t=3,p=4$");
    }

    @Test
    void acceptsTheCorrectPassword() {
        String hash = encoder.encode("correct horse battery staple");
        assertThat(encoder.matches("correct horse battery staple", hash)).isTrue();
    }

    @Test
    void rejectsAWrongPassword() {
        String hash = encoder.encode("correct horse battery staple");
        assertThat(encoder.matches("Correct horse battery staple", hash)).isFalse();
    }

    @Test
    void saltsEachHashSeparately() {
        // Two encodes of one password must differ, or the hashes are precomputable in bulk.
        String a = encoder.encode("correct horse battery staple");
        String b = encoder.encode("correct horse battery staple");
        assertThat(a).isNotEqualTo(b);
    }
}
