package com.marketengine.backend.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 1 (cache): confirms Redis is reachable when cache is enabled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "marketengine.cache.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisStartupVerifier implements ApplicationListener<ApplicationReadyEvent> {

    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String ping = redisConnectionFactory.getConnection().ping();
        log.info("Redis connected: ping={}", ping);
    }
}
