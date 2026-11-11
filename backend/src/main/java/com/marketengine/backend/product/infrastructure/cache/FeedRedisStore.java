package com.marketengine.backend.product.infrastructure.cache;

import java.time.Clock;
import java.time.Duration;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisOperations;

/** Applies failure protection to actual Redis calls, before an unavailable cache is bypassed. */
public class FeedRedisStore {
    private final RedisOperations<String, FeedCacheEntry> redis;
    private final CircuitBreaker breaker;
    private final Clock clock;
    private final String prefix;
    private final Counter hits;
    private final Counter misses;
    private final Counter getErrors;
    private final Counter putErrors;
    private final Counter bypasses;

    public FeedRedisStore(RedisOperations<String, FeedCacheEntry> redis, CircuitBreaker breaker,
            MeterRegistry meters, Clock clock, String prefix) {
        this.redis = redis;
        this.breaker = breaker;
        this.clock = clock;
        this.prefix = prefix;
        this.hits = meters.counter("product.feed.cache.requests", "layer", "redis", "outcome", "hit");
        this.misses = meters.counter("product.feed.cache.requests", "layer", "redis", "outcome", "miss");
        this.getErrors = meters.counter("product.feed.cache.errors", "operation", "get");
        this.putErrors = meters.counter("product.feed.cache.errors", "operation", "put");
        this.bypasses = meters.counter("product.feed.cache.redis.bypasses");
        meters.gauge("product.feed.cache.redis.breaker.open", breaker,
                value -> value.getState() == CircuitBreaker.State.CLOSED ? 0 : 1);
    }

    public Read get(String key) {
        try {
            FeedCacheEntry entry = breaker.executeSupplier(() -> redis.opsForValue().get(prefix + key));
            if (entry != null && entry.expiresAt().isAfter(clock.instant())) {
                hits.increment();
                return new Read(entry, true);
            }
            misses.increment();
            return new Read(null, true);
        } catch (CallNotPermittedException blocked) {
            bypasses.increment();
            return new Read(null, false);
        } catch (RuntimeException failure) {
            if (!isUnavailable(failure)) {
                throw failure;
            }
            getErrors.increment();
            return new Read(null, false);
        }
    }

    public void put(String key, FeedCacheEntry entry) {
        Duration remaining = Duration.between(clock.instant(), entry.expiresAt());
        if (remaining.toMillis() < 1) {
            return;
        }
        try {
            breaker.executeRunnable(() -> redis.opsForValue().set(prefix + key, entry, remaining));
        } catch (CallNotPermittedException blocked) {
            bypasses.increment();
        } catch (RuntimeException failure) {
            if (!isUnavailable(failure)) {
                throw failure;
            }
            putErrors.increment();
        }
    }

    public static boolean isUnavailable(Throwable failure) {
        return failure instanceof DataAccessResourceFailureException || failure instanceof QueryTimeoutException;
    }

    /** A failed GET suppresses this request's PUT, avoiding a second Redis timeout. */
    public record Read(FeedCacheEntry entry, boolean writable) {}
}
