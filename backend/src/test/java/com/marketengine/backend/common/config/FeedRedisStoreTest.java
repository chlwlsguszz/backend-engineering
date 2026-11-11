package com.marketengine.backend.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;
import com.marketengine.backend.product.infrastructure.cache.FeedCacheEntry;
import com.marketengine.backend.product.infrastructure.cache.FeedRedisStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

class FeedRedisStoreTest {
    @SuppressWarnings("unchecked")
    private final RedisOperations<String, FeedCacheEntry> redis = mock(RedisOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, FeedCacheEntry> values = mock(ValueOperations.class);
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final CircuitBreaker breaker = RedisCacheConfig.redisCircuitBreaker(2, Duration.ofSeconds(1));
    private final FeedRedisStore store = new FeedRedisStore(redis, breaker, new SimpleMeterRegistry(), clock, "prefix:");

    @Test
    void outageOpensCircuitAndSuccessfulProbeRestoresReadsAndWrites() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThat(store.get("a").writable()).isFalse();
        assertThat(store.get("b").writable()).isFalse();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(store.get("c").writable()).isFalse();
        verify(values, times(2)).get(anyString());

        breaker.transitionToHalfOpenState();
        doReturn(null).when(values).get(anyString());
        assertThat(store.get("probe").writable()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        var entry = new FeedCacheEntry(new ProductPageResponse(List.of(), 0, 12, false), clock.instant().plusSeconds(1));
        store.put("probe", entry);
        verify(values).set("prefix:probe", entry, Duration.ofSeconds(1));
    }

    @Test
    void serializationFailuresAreVisibleAndDoNotOpenOutageCircuit() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new SerializationException("invalid JSON"));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> store.get("bad")).isInstanceOf(SerializationException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void expiredEnvelopeIsAMissEvenIfRedisStillContainsIt() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("prefix:expired")).thenReturn(new FeedCacheEntry(null, Instant.EPOCH.minusMillis(1)));
        assertThat(store.get("expired")).isEqualTo(new FeedRedisStore.Read(null, true));
        store.put("expired", new FeedCacheEntry(null, Instant.EPOCH.minusMillis(1)));
        verify(values, never()).set(anyString(), any(), any(Duration.class));
    }
}
