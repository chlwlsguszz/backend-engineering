package com.marketengine.backend.common.config;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;

@Configuration
@EnableCaching
@Profile("!reindex")
@ConditionalOnProperty(prefix = "marketengine.cache.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisCacheConfig {

    public static final String PRODUCT_LIST_FEED = "productListFeed:v3";

    @Bean
    RedisCacheConfiguration productListFeedCacheConfiguration(
            @Value("${marketengine.cache.redis.product-list-feed-ttl:60s}") Duration maxTtl
    ) {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Jackson2JsonRedisSerializer<ProductPageResponse> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, ProductPageResponse.class);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(jitteredTtl(maxTtl))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        valueSerializer
                ));
    }

    private static RedisCacheWriter.TtlFunction jitteredTtl(Duration maxTtl) {
        long maxMillis = maxTtl.toMillis();
        if (maxMillis < 1) {
            throw new IllegalArgumentException("product-list-feed-ttl must be at least 1 ms");
        }
        // Draw on each cache write. Every entry lives between half the cap (rounded up) and the cap.
        long jitterBound = maxMillis / 2 + 1;
        return (key, value) -> Duration.ofMillis(maxMillis - ThreadLocalRandom.current().nextLong(jitterBound));
    }

    @Bean
    CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisCacheConfiguration productListFeedCacheConfiguration,
            @Value("${marketengine.search.backend:elasticsearch}") String searchBackend
    ) {
        var config = productListFeedCacheConfiguration.computePrefixWith(name -> name + ":" + searchBackend + "::");
        return RedisCacheManager.builder(connectionFactory)
                .enableStatistics()
                .cacheDefaults(config)
                .withCacheConfiguration(PRODUCT_LIST_FEED, config)
                .build();
    }
}
