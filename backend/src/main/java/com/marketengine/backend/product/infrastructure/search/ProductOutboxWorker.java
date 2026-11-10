package com.marketengine.backend.product.infrastructure.search;

import java.util.HashSet;
import java.util.List;

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

    public ProductOutboxWorker(ProductSearchOutbox outbox, ProductOutboxPublisher publisher,
                               ApplicationAvailability availability,
                               @Value("${marketengine.elasticsearch.outbox.batch-size:500}") int batchSize) {
        Assert.isTrue(batchSize > 0, "Outbox batch size must be positive");
        this.outbox = outbox;
        this.publisher = publisher;
        this.availability = availability;
        this.batchSize = batchSize;
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
            log.warn("Product outbox bulk request failed for {} products", claimed.size(), failure);
            outbox.retry(claimed);
            return;
        }

        if (!completed.isEmpty()) {
            outbox.complete(completed);
        }
        if (completed.size() < claimed.size()) {
            var successes = new HashSet<>(completed);
            outbox.retry(claimed.stream().filter(entry -> !successes.contains(entry)).toList());
        }
    }
}
