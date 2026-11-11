package com.marketengine.backend.product.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.domain.ProductListQuery;
import com.marketengine.backend.product.infrastructure.cache.FeedCacheEntry;
import com.marketengine.backend.product.infrastructure.cache.FeedCacheLifetime;
import com.marketengine.backend.product.infrastructure.cache.FeedRedisStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProductListFeedCache {
    private final ProductListSearcher origin;
    private final FeedRedisStore redis;
    private final Cache<String, FeedCacheEntry> local;
    private final FeedCacheLifetime lifetime;
    private final Semaphore originPermits;
    private final Counter localHits;
    private final Counter localMisses;
    private final Counter rejected;
    private final Counter originFailures;
    private final Timer originTime;
    private final boolean enabled;

    @Autowired
    public ProductListFeedCache(ProductListSearcher origin, ObjectProvider<FeedRedisStore> redis,
            MeterRegistry meters,
            @Value("${marketengine.cache.redis.enabled:true}") boolean enabled,
            @Value("${marketengine.cache.redis.product-list-feed-ttl:60s}") Duration maximumTtl,
            @Value("${marketengine.cache.local.ttl:2s}") Duration localTtl,
            @Value("${marketengine.cache.local.maximum-entries:10000}") long maximumEntries,
            @Value("${marketengine.search.max-concurrent:32}") int maximumConcurrent) {
        this(origin, redis.getIfAvailable(), meters, enabled, maximumTtl, localTtl, maximumEntries,
                maximumConcurrent, Clock.systemUTC(), Ticker.systemTicker());
    }

    ProductListFeedCache(ProductListSearcher origin, FeedRedisStore redis, MeterRegistry meters,
            boolean enabled, Duration maximumTtl, Duration localTtl, long maximumEntries,
            int maximumConcurrent, Clock clock, Ticker ticker) {
        if (localTtl.isNegative() || localTtl.isZero() || maximumEntries < 1 || maximumConcurrent < 1) {
            throw new IllegalArgumentException("Local cache limits and search concurrency must be positive");
        }
        this.origin = origin;
        this.redis = redis;
        this.enabled = enabled;
        this.lifetime = new FeedCacheLifetime(maximumTtl, clock);
        this.originPermits = new Semaphore(maximumConcurrent);
        this.local = Caffeine.newBuilder().maximumSize(maximumEntries).ticker(ticker)
                .expireAfter(new Expiry<String, FeedCacheEntry>() {
                    @Override
                    public long expireAfterCreate(String key, FeedCacheEntry value, long currentTime) {
                        return Math.max(0, Math.min(localTtl.toNanos(),
                                Duration.between(clock.instant(), value.expiresAt()).toNanos()));
                    }
                    @Override
                    public long expireAfterUpdate(String key, FeedCacheEntry value, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }
                    @Override
                    public long expireAfterRead(String key, FeedCacheEntry value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                }).recordStats().build();
        CaffeineCacheMetrics.monitor(meters, local, "productFeedLocal");
        this.localHits = meters.counter("product.feed.cache.requests", "layer", "local", "outcome", "hit");
        this.localMisses = meters.counter("product.feed.cache.requests", "layer", "local", "outcome", "miss");
        this.rejected = meters.counter("product.search.rejected");
        this.originFailures = meters.counter("product.search.origin.errors");
        this.originTime = meters.timer("product.search.origin");
    }

    public ProductPageResponse get(ProductListFeedKey key) {
        if (!enabled) {
            return loadUncached(key.query());
        }
        FeedCacheEntry hit = local.getIfPresent(key.value());
        if (hit != null) {
            localHits.increment();
            return hit.response();
        }
        localMisses.increment();
        // Caffeine executes this loader once per key within the JVM, including concurrent misses.
        return local.get(key.value(), ignored -> load(key)).response();
    }

    private FeedCacheEntry load(ProductListFeedKey key) {
        FeedRedisStore.Read read = redis == null ? new FeedRedisStore.Read(null, false) : redis.get(key.value());
        if (read.entry() != null) {
            return read.entry();
        }
        FeedCacheEntry entry = lifetime.wrap(loadUncached(key.query()));
        if (read.writable()) {
            redis.put(key.value(), entry);
        }
        return entry;
    }

    /** Used for non-cacheable pages as well, so origin protection cannot be bypassed by query shape. */
    public ProductPageResponse loadUncached(ProductListQuery query) {
        if (!originPermits.tryAcquire()) {
            rejected.increment();
            throw new BusinessException(ErrorCode.SEARCH_CAPACITY_EXCEEDED,
                    ErrorCode.SEARCH_CAPACITY_EXCEEDED.defaultMessage(), Map.of("retryAfterMillis", 100));
        }
        try {
            return originTime.record(() -> ProductPageResponse.from(origin.search(query)));
        } catch (RuntimeException failure) {
            originFailures.increment();
            throw failure;
        } finally {
            originPermits.release();
        }
    }
}
