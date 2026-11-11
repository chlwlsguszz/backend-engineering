package com.marketengine.backend.product.infrastructure.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;

public final class FeedCacheLifetime {
    private final long maximumMillis;
    private final Clock clock;

    public FeedCacheLifetime(Duration maximum, Clock clock) {
        if (maximum.toMillis() < 1) {
            throw new IllegalArgumentException("product-list-feed-ttl must be at least 1 ms");
        }
        this.maximumMillis = maximum.toMillis();
        this.clock = clock;
    }

    public FeedCacheEntry wrap(ProductPageResponse response) {
        long ttlMillis = maximumMillis - ThreadLocalRandom.current().nextLong(maximumMillis / 2 + 1);
        return new FeedCacheEntry(response, clock.instant().plusMillis(ttlMillis));
    }
}
