package com.sih26046.ctms.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import java.time.Duration;

/**
 * The tiers from §18.10, keyed by threat rather than by capacity (§9.2): strict where abuse is
 * cheap and damaging (credential stuffing, mass re-identification, differencing overlapping GIS
 * queries to defeat k-anonymity suppression), generous everywhere else. This is a defence
 * against abuse, not a capacity control — Bucket4j in-memory is enough because at one instance a
 * per-instance limit already is the global limit.
 */
public enum RateLimitTier {
    /** Credential stuffing (§18.10). Checked per IP <em>and</em> per email — see the filter. */
    LOGIN(5, Duration.ofMinutes(15)),

    /** Per session (the refresh token's own value is the key — see the filter). */
    REFRESH(30, Duration.ofHours(1)),

    /** Document upload — a scan and a storage write, not a free action to spam. */
    DOCUMENT_UPLOAD(20, Duration.ofHours(1)),

    /**
     * Repeated, overlapping bounding-box queries can difference a k-anonymity-suppressed cell
     * back into a real number (§11.4) — the sharpest limit in the table for exactly that reason.
     */
    GIS_DRILLDOWN(20, Duration.ofMinutes(1)),

    /** Every other {@code /gis/*} read — spatial aggregation is expensive even when innocuous. */
    GIS_READ(120, Duration.ofMinutes(1)),

    /** Ordinary authenticated writes. */
    WRITE(60, Duration.ofMinutes(1)),

    /** Ordinary authenticated reads — not the constraint at this scale. */
    READ(300, Duration.ofMinutes(1));

    private final int capacity;
    private final Duration period;

    RateLimitTier(int capacity, Duration period) {
        this.capacity = capacity;
        this.period = period;
    }

    Bandwidth bandwidth() {
        return Bandwidth.builder().capacity(capacity).refillIntervally(capacity, period).build();
    }

    /** An approximate but always-safe {@code Retry-After}: the whole window, not a precise ETA. */
    public long periodSeconds() {
        return period.toSeconds();
    }
}
