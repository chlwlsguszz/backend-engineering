package com.marketengine.backend.common.config;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;

/**
 * Step 1 (cache): Redis-backed Spring Cache. Product list feed caching uses {@link #PRODUCT_LIST_FEED} in a later step.
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(prefix = "marketengine.cache.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisCacheConfig {

    public static final String PRODUCT_LIST_FEED = "productListFeed";

    @Bean
    RedisCacheConfiguration productListFeedCacheConfiguration(
            @Value("${marketengine.cache.redis.product-list-feed-ttl:2s}") Duration productListFeedTtl
    ) {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Jackson2JsonRedisSerializer<ProductPageResponse> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, ProductPageResponse.class);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(productListFeedTtl)
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        valueSerializer
                ));
    }

    @Bean
    CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisCacheConfiguration productListFeedCacheConfiguration
    ) {
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(productListFeedCacheConfiguration)
                .withCacheConfiguration(PRODUCT_LIST_FEED, productListFeedCacheConfiguration)
                .build();
    }
}
