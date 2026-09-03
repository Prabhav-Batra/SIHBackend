package com.sih26046.ctms.ops;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two keep-alives spec §4/B9 requires as deliverables, not afterthoughts.
 *
 * @param supabasePingInterval well under Supabase's 7-day idle pause (spec §4) — daily is a
 *     wide margin against a missed run
 * @param healthPingUrl a dead-man's-switch webhook (e.g. healthchecks.io) an operator watches
 *     for silence. Empty by default: no such account exists yet, and pinging an unconfigured
 *     URL is worse than not pinging at all
 */
@ConfigurationProperties(prefix = "ctms.ops")
public record KeepAliveProperties(
        @DefaultValue("24h") Duration supabasePingInterval,
        @DefaultValue("5m") Duration healthPingInterval,
        @DefaultValue("") String healthPingUrl,
        @DefaultValue("true") boolean supabasePingScheduled,
        @DefaultValue("true") boolean healthPingScheduled) {}
