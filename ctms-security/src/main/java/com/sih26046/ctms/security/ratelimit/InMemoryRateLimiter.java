package com.sih26046.ctms.security.ratelimit;

import io.github.bucket4j.Bucket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One {@link Bucket} per (tier, key) pair, held for the process lifetime. Nothing evicts an
 * idle bucket — at the seed sizes this deployment runs at (§7.1) the map never grows large
 * enough for that to matter, and adding an eviction policy before it does would be solving a
 * problem this deployment does not have.
 *
 * <p>{@code ctms.security.rate-limit.enabled=false} (set by {@code AbstractPostgresIT} for the
 * whole test suite) makes every check pass unconditionally, the same way {@code
 * ctms.documents.scan.scheduled=false} disables a timer in tests rather than the behaviour it
 * times: hundreds of test methods logging in through the real {@code /auth/login} endpoint
 * would exhaust a 5-per-15-minute bucket sized for production abuse, not test volume, within
 * the first few test classes. A dedicated test exercises the real limiting behaviour by
 * overriding this property back to {@code true} for just that test class.
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final boolean enabled;

    public InMemoryRateLimiter(
            @Value("${ctms.security.rate-limit.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean tryConsume(String key, RateLimitTier tier) {
        if (!enabled) {
            return true;
        }
        String bucketKey = tier.name() + ':' + key;
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> Bucket.builder()
                .addLimit(tier.bandwidth())
                .build());
        return bucket.tryConsume(1);
    }
}
