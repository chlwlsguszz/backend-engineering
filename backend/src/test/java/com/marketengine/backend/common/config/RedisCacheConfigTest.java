package com.marketengine.backend.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.CacheEvictOperation;
import com.marketengine.backend.product.infrastructure.cache.FeedCacheLifetime;

import com.marketengine.backend.product.application.ProductService;

class RedisCacheConfigTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void feedWritesReceiveAnExpiryWithinTheConfiguredWindow() {
        Duration maximum = Duration.ofSeconds(60);
        var ttl = new FeedCacheLifetime(maximum, clock);
        for (int i = 0; i < 64; i++) {
            assertThat(Duration.between(clock.instant(), ttl.wrap(null).expiresAt()))
                    .isBetween(Duration.ofSeconds(30), maximum);
        }
    }

    @Test
    void oneMillisecondMaximumStillExpiresTheEntry() {
        Duration minimum = Duration.ofMillis(1);
        var ttl = new FeedCacheLifetime(minimum, clock);
        assertThat(Duration.between(clock.instant(), ttl.wrap(null).expiresAt())).isEqualTo(minimum);
    }

    @Test
    void rejectsTtlValuesThatWouldBePersistentAtRedisPrecision() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeedCacheLifetime(Duration.ZERO, clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FeedCacheLifetime(Duration.ofNanos(999_999), clock));
    }

    @Test
    void productOperationsDoNotRequestWholeFeedEviction() {
        var source = new AnnotationCacheOperationSource();
        for (Method method : ProductService.class.getDeclaredMethods()) {
            var operations = source.getCacheOperations(method, ProductService.class);
            if (operations != null) {
                assertThat(operations).as(method.getName()).noneMatch(operation ->
                        operation instanceof CacheEvictOperation eviction && eviction.isCacheWide()
                                && eviction.getCacheNames().contains(RedisCacheConfig.PRODUCT_LIST_FEED));
            }
        }
    }
}
