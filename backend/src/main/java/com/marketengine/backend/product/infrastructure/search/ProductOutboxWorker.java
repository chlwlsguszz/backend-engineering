package com.marketengine.backend.product.infrastructure.search;

import java.util.HashSet;
import java.util.List;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox.Entry;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile("!reindex")
@ConditionalOnProperty(prefix = "marketengine.elasticsearch", name = {"enabled", "outbox.enabled"},
        havingValue = "true", matchIfMissing = true)
public class ProductOutboxWorker {

    private final ProductSearchOutbox outbox;
    private final ProductOutboxPublisher publisher;
    private final ApplicationAvailability availability;
    private final int batchSize;
    private final Counter published;
    private final Counter failed;

    public ProductOutboxWorker(ProductSearchOutbox outbox, ProductOutboxPublisher publisher,
                               ApplicationAvailability availability, MeterRegistry registry,
                               @Value("${marketengine.elasticsearch.outbox.batch-size:500}") int batchSize) {
        Assert.isTrue(batchSize > 0, "Outbox batch size must be positive");
        this.outbox = outbox;
        this.publisher = publisher;
        this.availability = availability;
        this.batchSize = batchSize;
        this.published = registry.counter("product.outbox.publications", "outcome", "success");
        this.failed = registry.counter("product.outbox.publications", "outcome", "failure");
    }

    @Scheduled(fixedDelayString = "${marketengine.elasticsearch.outbox.poll-delay:250ms}")
    public void publishPending() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return;
        }
        List<Entry> claimed = outbox.claim(batchSize);
        if (claimed.isEmpty()) {
            return;
        }

        List<Entry> completed;
        try {
            completed = publisher.publish(claimed);
        } catch (RuntimeException failure) {
            failed.increment(claimed.size());
            log.warn("Product outbox bulk request failed for {} products", claimed.size(), failure);
            outbox.retry(claimed);
            return;
        }

        if (!completed.isEmpty()) {
            published.increment(completed.size());
            outbox.complete(completed);
        }
        if (completed.size() < claimed.size()) {
            failed.increment(claimed.size() - completed.size());
            var successes = new HashSet<>(completed);
            outbox.retry(claimed.stream().filter(entry -> !successes.contains(entry)).toList());
        }
    }
}
