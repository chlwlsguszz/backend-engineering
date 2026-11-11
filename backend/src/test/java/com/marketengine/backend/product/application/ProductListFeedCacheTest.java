package com.marketengine.backend.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.domain.ProductListQuery;
import com.marketengine.backend.product.infrastructure.cache.FeedCacheEntry;
import com.marketengine.backend.product.infrastructure.cache.FeedRedisStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;

class ProductListFeedCacheTest {
    private final ProductListSearcher origin = mock(ProductListSearcher.class);
    private final FeedRedisStore redis = mock(FeedRedisStore.class);
    private final TestClock time = new TestClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ProductListQuery query = new ProductListQuery(null, null, null, null, null, null, null, "LATEST", 0, 12);
    private final ProductListFeedKey key = ProductListFeedKeyResolver.resolve(query).orElseThrow();
    private final ProductPageResponse response = new ProductPageResponse(List.of(), 0, 12, false);

    @BeforeEach
    void defaults() {
        when(redis.get(anyString())).thenReturn(new FeedRedisStore.Read(null, true));
        when(origin.search(any())).thenReturn(new SliceImpl<>(List.of(), query.pageable(), false));
    }

    private ProductListFeedCache cache(boolean enabled, int permits) {
        return new ProductListFeedCache(origin, redis, meters, enabled, Duration.ofSeconds(60),
                Duration.ofSeconds(2), 100, permits, time, time.nanos::get);
    }

    @Test
    void localHitAvoidsRedisAndOrigin() {
        var cache = cache(true, 2);
        assertThat(cache.get(key)).isEqualTo(response);
        assertThat(cache.get(key)).isEqualTo(response);
        verify(redis).get(key.value());
        verify(redis).put(eq(key.value()), any());
        verify(origin).search(query);
    }

    @Test
    void concurrentMissesLoadTheSameKeyOnce() throws Exception {
        var cache = cache(true, 2);
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(origin.search(query)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new SliceImpl<>(List.of(), query.pageable(), false);
        });
        try (var workers = Executors.newFixedThreadPool(12)) {
            List<Future<ProductPageResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cache.get(key);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(response);
            }
        } finally {
            release.countDown();
        }
        verify(origin).search(query);
        verify(redis).get(key.value());
    }

    @Test
    void localExpiryNeverExtendsRedisDeadline() {
        var entry = new FeedCacheEntry(response, time.instant().plusMillis(100));
        when(redis.get(key.value())).thenReturn(new FeedRedisStore.Read(entry, true), new FeedRedisStore.Read(null, true));
        var cache = cache(true, 2);
        cache.get(key);
        time.advance(Duration.ofMillis(99));
        cache.get(key);
        verify(origin, never()).search(any());
        time.advance(Duration.ofMillis(2));
        cache.get(key);
        verify(redis, times(2)).get(key.value());
        verify(origin).search(query);
    }

    @Test
    void failedRedisGetSkipsPutButStillCachesLocally() {
        when(redis.get(key.value())).thenReturn(new FeedRedisStore.Read(null, false));
        var cache = cache(true, 2);
        cache.get(key);
        cache.get(key);
        verify(redis, never()).put(anyString(), any());
        verify(origin).search(query);
    }

    @Test
    void disabledCacheBypassesBothLayers() {
        var cache = cache(false, 2);
        cache.get(key);
        cache.get(key);
        verifyNoInteractions(redis);
        verify(origin, times(2)).search(query);
    }

    @Test
    void originFailureIsNotCachedAndReleasesPermit() {
        when(origin.search(query)).thenThrow(new IllegalStateException("origin unavailable"))
                .thenReturn(new SliceImpl<>(List.of(), query.pageable(), false));
        var cache = cache(true, 1);
        assertThatThrownBy(() -> cache.get(key)).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get(key)).isEqualTo(response);
        verify(origin, times(2)).search(query);
        verify(redis).put(eq(key.value()), any());
    }

    @Test
    void saturatedOriginRejectsUncachedSearchWithoutWaiting() throws Exception {
        var cache = cache(false, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(origin.search(query)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new SliceImpl<>(List.of(), query.pageable(), false);
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> cache.get(key));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> cache.loadUncached(query))
                        .isInstanceOfSatisfying(BusinessException.class,
                                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.SEARCH_CAPACITY_EXCEEDED));
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(response);
            assertThat(cache.loadUncached(query)).isEqualTo(response);
        }
    }

    private static class TestClock extends Clock {
        private final AtomicLong nanos = new AtomicLong();
        void advance(Duration duration) { nanos.addAndGet(duration.toNanos()); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.EPOCH.plusNanos(nanos.get()); }
    }
}
