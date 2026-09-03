package com.sih26046.ctms.security.ratelimit;

/**
 * Abuse control, not capacity control (§9.2). One method: has this key already used up its
 * budget for this tier?
 *
 * <p>In-memory at one instance (§5), because a Redis-backed bucket would spend a network round
 * trip per request to reach the identical answer a local one gives for free. A Redis
 * implementation is the seam for a multi-instance deployment, not built here — the same
 * single-tier decision B8 made for the analytics dashboard cache.
 */
public interface RateLimiter {

    boolean tryConsume(String key, RateLimitTier tier);
}
