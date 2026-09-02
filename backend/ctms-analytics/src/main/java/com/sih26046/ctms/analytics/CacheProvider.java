package com.sih26046.ctms.analytics;

import java.util.function.Supplier;

/**
 * Cache-aside, one tier (spec §9.1, §12.1).
 *
 * <p>The design calls for Caffeine L1 behind this interface with a Redis L2 wired in but
 * inactive at one instance. Only L1 is implemented here: a Redis L2 that never activates until
 * a second instance exists is a dependency, a container, and a config surface bought today for
 * a property this deployment does not have yet (§9 "Standing constraints" — one instance, no
 * managed Redis on the free tier either). The interface is what a second tier would sit behind
 * without any caller changing; adding it is additive, not a rewrite.
 */
public interface CacheProvider {

    /** Returns the cached value, computing and storing it on a miss. */
    <T> T get(String key, Supplier<T> onMiss);

    /** Drops one key, so the next read is a genuine recompute. */
    void invalidate(String key);
}
