package com.sih26046.ctms;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostGIS container for the whole test JVM.
 *
 * <p>Started in a static initialiser and never stopped — Testcontainers' Ryuk sidecar reaps it
 * when the JVM exits. Per-class {@code @Container} lifecycle would start a fresh database for
 * every test class, which by B3 (23 tables, RLS policies, partitions) costs more than the tests.
 */
public abstract class AbstractPostgresIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                            DockerImageName.parse("postgis/postgis:17-3.5")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ctms");

    static {
        POSTGRES.start();
    }

    /** A test-only signing key. Production has no default and fails fast without one. */
    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "ctms.auth.jwt-secret",
                () -> "integration-test-signing-key-at-least-32-bytes");
    }
}
