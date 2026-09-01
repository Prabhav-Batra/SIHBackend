package com.sih26046.ctms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root for the CTMS backend.
 *
 * <p>Domain logic lives in the ctms-* library modules; this module wires them together and
 * owns nothing but configuration (spec §8).
 */
@SpringBootApplication(scanBasePackages = "com.sih26046.ctms")
public class CtmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CtmsApplication.class, args);
    }
}
