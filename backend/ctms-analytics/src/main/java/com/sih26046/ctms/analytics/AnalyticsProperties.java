package com.sih26046.ctms.analytics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** §23.8: dashboard payloads are cached 60–120s; §10.3 refreshes the rollup periodically. */
@ConfigurationProperties(prefix = "ctms.analytics")
public record AnalyticsProperties(
        @DefaultValue("90s") Duration dashboardCacheTtl,
        @DefaultValue("60s") Duration rollupRefreshInterval,
        @DefaultValue("true") boolean rollupRefreshScheduled) {}
