package com.sih26046.ctms.ops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Times {@link KeepAliveWorker}'s two jobs — independently gated, same worker/scheduler split
 * as every other scheduled job in this codebase (§B6c's orphan sweep, §B8's rollup refresh), so
 * a test can disable one without the other and call the worker directly instead.
 */
@Configuration
@EnableConfigurationProperties(KeepAliveProperties.class)
class KeepAliveSchedulerConfig {}

@Component
@ConditionalOnProperty(
        name = "ctms.ops.supabase-ping-scheduled",
        havingValue = "true",
        matchIfMissing = true)
class SupabaseKeepAliveScheduler {

    private final KeepAliveWorker worker;

    SupabaseKeepAliveScheduler(KeepAliveWorker worker) {
        this.worker = worker;
    }

    @Scheduled(
            fixedDelayString = "${ctms.ops.supabase-ping-interval:24h}",
            initialDelayString = "${ctms.ops.supabase-ping-interval:24h}")
    void run() {
        worker.pingSupabase();
    }
}

@Component
@ConditionalOnProperty(
        name = "ctms.ops.health-ping-scheduled",
        havingValue = "true",
        matchIfMissing = true)
class HealthPingScheduler {

    private final KeepAliveWorker worker;

    HealthPingScheduler(KeepAliveWorker worker) {
        this.worker = worker;
    }

    @Scheduled(
            fixedDelayString = "${ctms.ops.health-ping-interval:5m}",
            initialDelayString = "${ctms.ops.health-ping-interval:5m}")
    void run() {
        worker.pingHealthMonitor();
    }
}
