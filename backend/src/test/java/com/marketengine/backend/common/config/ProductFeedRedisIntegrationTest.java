package com.marketengine.backend.common.config;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketengine.backend.product.api.ProductDtos.ProductSummaryResponse;
import com.marketengine.backend.product.application.ProductListFeedCache;
import com.marketengine.backend.product.application.ProductListFeedKeyResolver;
import com.marketengine.backend.product.application.ProductListSearcher;
import com.marketengine.backend.product.domain.ProductCategory;
import com.marketengine.backend.product.domain.ProductListQuery;
import com.marketengine.backend.product.infrastructure.cache.FeedRedisStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(ProductFeedRedisIntegrationTest.Config.class)
@TestPropertySource(properties = {"marketengine.cache.redis.product-list-feed-ttl=4s", "marketengine.search.backend=postgres"})
class ProductFeedRedisIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @TestConfiguration(proxyBeanMethods = false)
    @Import({RedisCacheConfig.class, ProductListFeedCache.class})
    static class Config {
        @Bean static ConversionService conversionService() {
            return ApplicationConversionService.getSharedInstance();
        }
        @Bean LettuceConnectionFactory connectionFactory() {
            return new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        }
        @Bean StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
        @Bean ProductListSearcher origin() {
            return mock(ProductListSearcher.class);
        }
        @Bean MeterRegistry meters() { return new SimpleMeterRegistry(); }
    }

    @Test
    void servesSerializedFeedUntilJitteredTtlExpires(@Autowired ProductListFeedCache feed,
            @Autowired ProductListSearcher origin, @Autowired FeedRedisStore store, @Autowired StringRedisTemplate redisTemplate,
            @Autowired MeterRegistry meters) {
        var query = new ProductListQuery("M42", null, null, null, null, null, null, "LATEST", 0, 12);
        var key = ProductListFeedKeyResolver.resolve(query).orElseThrow();
        var item = new ProductSummaryResponse(8L, "M42", BigDecimal.TEN, true, ProductCategory.TOP,
                "Brand", "BLACK", "UNISEX", 1, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        when(origin.search(query)).thenReturn(new SliceImpl<>(List.of(item), query.pageable(), false));
        var first = feed.get(key);
        var physicalKey = RedisCacheConfig.PRODUCT_LIST_FEED + ":postgres::" + key.value();
        assertThat(redisTemplate.getExpire(physicalKey, TimeUnit.MILLISECONDS)).isBetween(1L, 4000L);
        assertThat(feed.get(key)).isEqualTo(first);
        verify(origin).search(query);
        assertThat(store.get(key.value()).entry().response()).isEqualTo(first);
        assertThat(meters.get("product.feed.cache.requests").tags("layer", "local", "outcome", "hit").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("product.feed.cache.requests").tags("layer", "redis", "outcome", "miss").counter().count())
                .isEqualTo(1);
        await().atMost(Duration.ofSeconds(7)).until(() -> !Boolean.TRUE.equals(redisTemplate.hasKey(physicalKey)));
        assertThat(feed.get(key)).isEqualTo(first);
        verify(origin, times(2)).search(query);
    }
}
