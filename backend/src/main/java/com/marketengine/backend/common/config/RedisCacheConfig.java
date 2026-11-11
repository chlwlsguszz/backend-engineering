package com.marketengine.backend.common.config;

import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketengine.backend.product.infrastructure.cache.FeedCacheEntry;
import com.marketengine.backend.product.infrastructure.cache.FeedRedisStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration(proxyBeanMethods = false)
@Profile("!reindex")
@ConditionalOnProperty(prefix = "marketengine.cache.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisCacheConfig {

    // v5 includes query-bound continuation cursors alongside availability and the absolute expiry.
    public static final String PRODUCT_LIST_FEED = "productListFeed:v5";

    @Bean
    FeedRedisStore productFeedRedisStore(RedisConnectionFactory connectionFactory, MeterRegistry meters,
            @Value("${marketengine.search.backend:elasticsearch}") String searchBackend,
            @Value("${marketengine.cache.redis.breaker-failure-count:5}") int failureCount,
            @Value("${marketengine.cache.redis.breaker-open-duration:5s}") Duration openDuration) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var template = new RedisTemplate<String, FeedCacheEntry>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(mapper, FeedCacheEntry.class));
        template.afterPropertiesSet();
        return new FeedRedisStore(template, redisCircuitBreaker(failureCount, openDuration), meters,
                Clock.systemUTC(), PRODUCT_LIST_FEED + ":" + searchBackend + "::");
    }

    public static CircuitBreaker redisCircuitBreaker(int failureCount, Duration openDuration) {
        if (failureCount < 1 || openDuration.isZero() || openDuration.isNegative()) {
            throw new IllegalArgumentException("Redis breaker failure count and open duration must be positive");
        }
        return CircuitBreaker.of("productFeedRedis", CircuitBreakerConfig.custom()
                .slidingWindowSize(failureCount).minimumNumberOfCalls(failureCount)
                .failureRateThreshold(100).waitDurationInOpenState(openDuration)
                .permittedNumberOfCallsInHalfOpenState(1)
                .ignoreException(failure -> !FeedRedisStore.isUnavailable(failure)).build());
    }
}
