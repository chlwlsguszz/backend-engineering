package com.marketengine.backend.product.infrastructure.cache;

import java.time.Instant;
import com.marketengine.backend.product.api.ProductDtos.ProductPageResponse;

/** Both cache tiers share this deadline; an L2 hit must never restart its freshness window. */
public record FeedCacheEntry(ProductPageResponse response, Instant expiresAt) {}
