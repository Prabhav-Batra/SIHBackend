package com.sih26046.ctms.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * The actual work behind the two keep-alives — unconditional and directly callable, same
 * worker/scheduler split as the document orphan sweep and the analytics rollup refresh, so a
 * test can trigger either without waiting on a timer.
 */
@Component
public class KeepAliveWorker {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveWorker.class);

    private final JdbcTemplate jdbc;
    private final KeepAliveProperties properties;
    private final RestClient restClient;

    public KeepAliveWorker(JdbcTemplate jdbc, KeepAliveProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    /**
     * A trivial read query against Supabase (spec §4: the free-tier project pauses after 7
     * days idle). Nothing about the query matters beyond "the connection was used" — it is
     * deliberately outside the RLS-scoped identity machinery, since this is infrastructure
     * upkeep, not a user action.
     */
    @Transactional(readOnly = true)
    public void pingSupabase() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        log.debug("Supabase keep-alive ping succeeded");
    }

    /**
     * A dead-man's-switch ping: silence, not failure, is what an operator should notice. If no
     * URL is configured, this is a deliberate no-op rather than an error — most environments
     * (local dev, a fresh deploy before the monitoring account exists) have none.
     */
    public void pingHealthMonitor() {
        String url = properties.healthPingUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            restClient.get().uri(url).retrieve().toBodilessEntity();
        } catch (Exception e) {
            // The ping failing to send is exactly the condition the external monitor exists to
            // catch from the other side — logging it here is diagnostic, not the actual alarm.
            log.warn("Health ping to {} failed: {}", url, e.getMessage());
        }
    }
}
