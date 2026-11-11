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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
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
        worker = new ProductOutboxWorker(outbox, publisher, availability, new SimpleMeterRegistry(), 500, 8);
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

    @Test
    void drainsAnotherFullBatchWithoutWaitingForNextPoll() {
        worker = new ProductOutboxWorker(outbox, publisher, availability, new SimpleMeterRegistry(), 2, 8);
        var tail = List.of(first);
        when(outbox.claim(2)).thenReturn(claimed, tail);
        when(publisher.publish(claimed)).thenReturn(claimed);
        when(publisher.publish(tail)).thenReturn(tail);

        worker.publishPending();

        var order = inOrder(outbox, publisher);
        order.verify(outbox).claim(2);
        order.verify(publisher).publish(claimed);
        order.verify(outbox).complete(claimed);
        order.verify(outbox).claim(2);
        order.verify(publisher).publish(tail);
        order.verify(outbox).complete(tail);
        verify(outbox, times(2)).claim(2);
    }

    @Test
    void yieldsAfterConfiguredBatchLimitEvenWithContinuousWrites() {
        worker = new ProductOutboxWorker(outbox, publisher, availability, new SimpleMeterRegistry(), 2, 3);
        when(outbox.claim(2)).thenReturn(claimed);
        when(publisher.publish(claimed)).thenReturn(claimed);

        worker.publishPending();

        verify(outbox, times(3)).claim(2);
        verify(publisher, times(3)).publish(claimed);
    }

    @Test
    void stopsDrainingOnFailureSoRetryBackoffIsRespected() {
        worker = new ProductOutboxWorker(outbox, publisher, availability, new SimpleMeterRegistry(), 2, 8);
        when(outbox.claim(2)).thenReturn(claimed);
        when(publisher.publish(claimed)).thenReturn(List.of(first));

        worker.publishPending();

        verify(outbox).claim(2);
        verify(outbox).retry(List.of(second));
    }

    @Test
    void stopsBetweenBatchesWhenApplicationStopsAcceptingTraffic() {
        worker = new ProductOutboxWorker(outbox, publisher, availability, new SimpleMeterRegistry(), 2, 8);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC,
                ReadinessState.REFUSING_TRAFFIC);
        when(outbox.claim(2)).thenReturn(claimed);
        when(publisher.publish(claimed)).thenReturn(claimed);

        worker.publishPending();

        verify(outbox).claim(2);
        verify(publisher).publish(claimed);
    }
}
