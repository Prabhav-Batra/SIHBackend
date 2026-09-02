package com.sih26046.ctms.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refreshes {@code mv_trial_rollup} (V25). Unconditional and directly callable so tests can
 * refresh on demand — {@link TrialRollupRefreshScheduler}, which is disabled in tests, is the
 * only other caller.
 */
@Component
public class TrialRollupRefresher {

    private final JdbcTemplate jdbc;

    public TrialRollupRefresher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code REFRESH MATERIALIZED VIEW CONCURRENTLY} takes its own lock and must not run inside
     * a transaction that also touches the view, hence {@code REQUIRES_NEW} rather than
     * whatever transaction the caller happens to be in.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh() {
        jdbc.query("SELECT app.refresh_trial_rollup()", rs -> null);
    }
}
