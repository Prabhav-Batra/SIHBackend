package com.sih26046.ctms.analytics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class CaffeineCacheProvider implements CacheProvider {

    private final Cache<String, Object> cache;

    public CaffeineCacheProvider(AnalyticsProperties properties) {
        this.cache =
                Caffeine.newBuilder().expireAfterWrite(properties.dashboardCacheTtl()).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> onMiss) {
        return (T) cache.get(key, k -> onMiss.get());
    }

    @Override
    public void invalidate(String key) {
        cache.invalidate(key);
    }
}
