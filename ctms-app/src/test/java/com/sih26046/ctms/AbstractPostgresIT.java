package com.sih26046.ctms;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostGIS container for the whole test JVM.
 *
 * <p>Started in a static initialiser and never stopped — Testcontainers' Ryuk sidecar reaps it
 * when the JVM exits. Per-class {@code @Container} lifecycle would start a fresh database for
 * every test class, which at 23 tables and a full policy set costs more than the tests.
 *
 * <p><strong>The application connects as {@code ctms_app}, not as the container superuser.</strong>
 * PostgreSQL exempts superusers from row-level security entirely, so tests run as one would
 * pass whether the policies were correct, wrong, or absent. Migrations still run as the owner
 * via {@code spring.flyway.user}, which is also the production shape (§7.7).
 */
public abstract class AbstractPostgresIT {

    private static final String APP_USER = "ctms_app";
    private static final String APP_PASSWORD = "ctms_app";

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                            DockerImageName.parse("postgis/postgis:17-3.5")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("ctms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseAndAuthProperties(DynamicPropertyRegistry registry) {
        // Deliberately NOT @ServiceConnection. That annotation contributes a
        // JdbcConnectionDetails bean, which outranks properties entirely — so a
        // spring.datasource.username override is accepted silently and ignored, and the
        // application keeps connecting as the container superuser. Superusers are exempt from
        // RLS, so every scope test would then pass regardless of the policies.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);

        // Flyway keeps the owner credentials; the application pool drops to ctms_app so RLS
        // actually binds. The role itself is created by V5, which Flyway runs as the owner.
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);

        /* A test-only signing key. Production has no default and fails fast without one. */
        registry.add(
                "ctms.auth.jwt-secret", () -> "integration-test-signing-key-at-least-32-bytes");
    }

    /**
     * A template on the owner connection, for test fixtures that must sidestep RLS.
     *
     * <p>Setup data is not the thing under test. Creating a trial as the owner and then
     * asserting what {@code ctms_app} can see is what makes a scope test meaningful; building
     * the fixture through the same policies being tested would prove only self-consistency.
     */
    protected static JdbcTemplate ownerJdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return new JdbcTemplate((DataSource) dataSource);
    }
}
