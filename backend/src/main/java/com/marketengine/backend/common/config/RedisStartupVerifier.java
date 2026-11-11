package com.marketengine.backend.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
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
@Profile("!reindex")
@ConditionalOnProperty(prefix = "marketengine.cache.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisStartupVerifier implements ApplicationListener<ApplicationReadyEvent> {

    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try (var connection = redisConnectionFactory.getConnection()) {
            log.info("Redis connected: ping={}", connection.ping());
        } catch (DataAccessResourceFailureException | QueryTimeoutException unavailable) {
            log.warn("Redis is unavailable at startup. Feed requests will query the search backend.",
                    unavailable);
        }
    }
}
