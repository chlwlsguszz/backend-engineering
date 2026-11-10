package com.marketengine.backend.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** On a Redis outage the feed reads its configured origin; mapping and serialization errors remain visible. */
@Component
@RequiredArgsConstructor
@Profile("!reindex")
@ConditionalOnProperty(prefix = "marketengine.cache.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
class RedisCacheFailurePolicy extends SimpleCacheErrorHandler implements CachingConfigurer {
    private final ObjectProvider<MeterRegistry> meters;

    @Override
    public CacheErrorHandler errorHandler() {
        return this;
    }

    @Override
    public void handleCacheGetError(RuntimeException failure, Cache cache, Object key) {
        if (!unavailable(failure, cache, "get")) {
            super.handleCacheGetError(failure, cache, key);
        }
    }

    @Override
    public void handleCachePutError(RuntimeException failure, Cache cache, Object key, Object value) {
        if (!unavailable(failure, cache, "put")) {
            super.handleCachePutError(failure, cache, key, value);
        }
    }

    private boolean unavailable(RuntimeException failure, Cache cache, String operation) {
        if (!RedisCacheConfig.PRODUCT_LIST_FEED.equals(cache.getName())
                || !(failure instanceof DataAccessResourceFailureException || failure instanceof QueryTimeoutException)) {
            return false;
        }
        meters.ifAvailable(registry -> registry.counter("product.feed.cache.errors", "operation", operation).increment());
        return true;
    }
}
