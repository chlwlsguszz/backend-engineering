package com.marketengine.backend.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.lang.reflect.Method;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.data.redis.cache.FixedDurationTtlFunction;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;

import com.marketengine.backend.product.application.ProductService;

class RedisCacheConfigTest {

    private final RedisCacheConfig config = new RedisCacheConfig();

    @Test
    void feedWritesReceiveAnExpiryWithinTheConfiguredWindow() {
        Duration maximum = Duration.ofSeconds(60);
        TtlFunction ttl = config.productListFeedCacheConfiguration(maximum).getTtlFunction();

        assertThat(ttl).isNotInstanceOf(FixedDurationTtlFunction.class);
        for (int i = 0; i < 64; i++) {
            assertThat(ttl.getTimeToLive("feed:home:latest:0", null))
                    .isBetween(Duration.ofSeconds(30), maximum);
        }
    }

    @Test
    void oneMillisecondMaximumStillExpiresTheEntry() {
        Duration minimum = Duration.ofMillis(1);
        TtlFunction ttl = config.productListFeedCacheConfiguration(minimum).getTtlFunction();

        assertThat(ttl.getTimeToLive("feed:home:latest:0", null)).isEqualTo(minimum);
    }

    @Test
    void rejectsTtlValuesThatWouldBePersistentAtRedisPrecision() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config.productListFeedCacheConfiguration(Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config.productListFeedCacheConfiguration(Duration.ofNanos(999_999)));
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
