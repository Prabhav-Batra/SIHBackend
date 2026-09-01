package com.sih26046.ctms.security;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to serve traffic on a connection that row-level security cannot constrain (§7.7).
 *
 * <p>PostgreSQL exempts superusers from RLS unconditionally, and {@code BYPASSRLS} does the
 * same. A deployment that points the application at the migration role therefore disables
 * every policy at once — silently, with no error and no behavioural difference until someone
 * reads rows they should not. One environment variable is the whole distance between "RLS
 * enforced" and "RLS absent", so it is checked at startup rather than trusted.
 */
@Component
public class RlsConnectionGuard {

    private static final Logger log = LoggerFactory.getLogger(RlsConnectionGuard.class);

    private final DataSource dataSource;

    public RlsConnectionGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        verify(new JdbcTemplate(dataSource));
    }

    /** @throws IllegalStateException if the connected role is exempt from RLS */
    public void verify(JdbcTemplate jdbc) {
        String role = jdbc.queryForObject("SELECT current_user", String.class);
        Boolean exempt =
                jdbc.queryForObject(
                        "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname ="
                                + " current_user",
                        Boolean.class);

        if (Boolean.TRUE.equals(exempt)) {
            throw new IllegalStateException(
                    "Database role '"
                            + role
                            + "' is exempt from row-level security (superuser or BYPASSRLS)."
                            + " Every policy would be silently inert. Point"
                            + " spring.datasource.username at the unprivileged application"
                            + " role and leave migrations to spring.flyway.user.");
        }
        log.info("Row-level security is enforceable: connected as '{}'", role);
    }
}
