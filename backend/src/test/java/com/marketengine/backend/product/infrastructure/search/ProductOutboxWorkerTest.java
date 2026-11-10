package com.marketengine.backend.product.infrastructure.search;

import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;

import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox.Entry;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductOutboxWorkerTest {

    @Mock private ProductSearchOutbox outbox;
    @Mock private ProductOutboxPublisher publisher;
    @Mock private ApplicationAvailability availability;

    private final Entry first = new Entry(1, 7, UUID.randomUUID(), null);
    private final Entry second = new Entry(2, 8, UUID.randomUUID(), null);
    private final List<Entry> claimed = List.of(first, second);
    private ProductOutboxWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ProductOutboxWorker(outbox, publisher, availability, new SimpleMeterRegistry(), 500);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void retriesOnlyFailedBulkItems() {
        when(outbox.claim(500)).thenReturn(claimed);
        when(publisher.publish(claimed)).thenReturn(List.of(second));
        worker.publishPending();
        verify(outbox).complete(List.of(second));
        verify(outbox).retry(List.of(first));
    }

    @Test
    void retriesWholeClaimWhenBulkOutcomeIsUnknown() {
        when(outbox.claim(500)).thenReturn(claimed);
        when(publisher.publish(claimed)).thenThrow(new IllegalStateException("Connection lost"));
        worker.publishPending();
        verify(outbox, never()).complete(anyList());
        verify(outbox).retry(claimed);
    }

    @Test
    void idlePollDoesNotContactElasticsearch() {
        when(outbox.claim(500)).thenReturn(List.of());
        worker.publishPending();
        verifyNoInteractions(publisher);
    }
}
