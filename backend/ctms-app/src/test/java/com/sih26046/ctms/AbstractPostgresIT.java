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

        // The scan poller is off in tests. Integration tests call DocumentScanWorker.runOnce()
        // themselves, and a timer draining the same queue would claim jobs mid-assertion — a
        // race that would read as flakiness rather than as the design decision it is.
        registry.add("ctms.documents.scan.scheduled", () -> "false");

        // Same reasoning for the orphan sweep: tests call DocumentOrphanSweepWorker.sweep()
        // themselves, on files they have deliberately backdated, and a nightly timer walking
        // the same shared storage root mid-test would be a second, uncontrolled sweeper.
        registry.add("ctms.documents.orphan-sweep.scheduled", () -> "false");

        // Same reasoning again: tests refresh mv_trial_rollup themselves, on data they just
        // wrote, and a timer refreshing mid-assertion would be a second, uncontrolled refresh.
        registry.add("ctms.analytics.rollup-refresh-scheduled", () -> "false");

        // Neither keep-alive job (§4/B9) should fire mid-suite — one pings Supabase, which
        // does not exist in a Testcontainers run, and the other pings an external monitor that
        // is never configured in tests anyway (empty ctms.ops.health-ping-url is already a
        // no-op, but there is no reason to let the scheduler even try).
        registry.add("ctms.ops.supabase-ping-scheduled", () -> "false");
        registry.add("ctms.ops.health-ping-scheduled", () -> "false");

        // Bucket4j buckets live for the process lifetime with nothing to reset them (§18.10).
        // Hundreds of test methods logging in through the real /auth/login endpoint would
        // exhaust a 5-per-15-minute production budget within the first few test classes — the
        // same category of problem the schedulers above solve, for the same reason: test
        // volume is not the thing §18.10 is calibrated against. RateLimitIT overrides this back
        // to true to test the real behaviour.
        registry.add("ctms.security.rate-limit.enabled", () -> "false");

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
