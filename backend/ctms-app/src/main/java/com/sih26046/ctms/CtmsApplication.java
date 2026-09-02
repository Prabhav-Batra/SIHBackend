package com.sih26046.ctms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition root for the CTMS backend.
 *
 * <p>Domain logic lives in the ctms-* library modules; this module wires them together and
 * owns nothing but configuration (spec §8).
 *
 * <p>{@code @EnableScheduling} lives here, not in a library module, because two of them now
 * register timers (the document scan/orphan-sweep pollers, and B8's rollup refresh) — one
 * platform-wide scheduling infrastructure rather than each module assuming it owns the only
 * one.
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.sih26046.ctms")
public class CtmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CtmsApplication.class, args);
    }
}
