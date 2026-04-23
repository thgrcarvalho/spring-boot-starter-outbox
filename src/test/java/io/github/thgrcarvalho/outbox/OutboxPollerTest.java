package io.github.thgrcarvalho.outbox;

import io.github.thgrcarvalho.outbox.internal.InMemoryOutboxStore;
import io.github.thgrcarvalho.outbox.internal.OutboxPoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutboxPollerTest {

    private InMemoryOutboxStore store;
    private List<OutboxEvent> delivered;
    private OutboxPublisher successPublisher;
    private OutboxProperties properties;

    @BeforeEach
    void setup() {
        store = new InMemoryOutboxStore();
        delivered = new ArrayList<>();
        successPublisher = event -> delivered.add(event);
        properties = new OutboxProperties();
    }

    @Test
    void poll_deliversPendingEventsAndMarksPublished() {
        store.save(new OutboxEvent(null, "payment.created", "{\"id\":1}", null, null, 0));
        store.save(new OutboxEvent(null, "payment.created", "{\"id\":2}", null, null, 0));

        OutboxPoller poller = new OutboxPoller(store, successPublisher, properties);
        poller.poll();

        assertEquals(2, delivered.size());
        assertEquals(List.of(1L, 2L), store.publishedIds());
    }

    @Test
    void poll_whenPublisherFails_recordsAttemptAndRetries() {
        store.save(new OutboxEvent(null, "order.placed", "{}", null, null, 0));

        OutboxPublisher failPublisher = event -> { throw new RuntimeException("broker down"); };
        OutboxPoller poller = new OutboxPoller(store, failPublisher, properties);

        poller.poll();

        assertTrue(store.publishedIds().isEmpty());
        assertTrue(store.failedIds().isEmpty(), "should still be PENDING after 1 failure (maxAttempts=3)");
    }

    @Test
    void poll_afterMaxAttempts_movesEventToFailed() {
        properties.setMaxAttempts(2);
        store.save(new OutboxEvent(null, "order.placed", "{}", null, null, 0));

        OutboxPublisher failPublisher = event -> { throw new RuntimeException("broker down"); };
        OutboxPoller poller = new OutboxPoller(store, failPublisher, properties);

        poller.poll(); // attempt 1 → still PENDING
        poller.poll(); // attempt 2 → FAILED

        assertEquals(List.of(1L), store.failedIds());
    }

    @Test
    void poll_whenNoPendingEvents_doesNothing() {
        OutboxPoller poller = new OutboxPoller(store, successPublisher, properties);
        assertDoesNotThrow(poller::poll);
        assertTrue(delivered.isEmpty());
    }

    @Test
    void poll_oneFailureDoesNotBlockOthers() {
        store.save(new OutboxEvent(null, "good.event", "{\"n\":1}", null, null, 0));
        store.save(new OutboxEvent(null, "bad.event",  "{\"n\":2}", null, null, 0));
        store.save(new OutboxEvent(null, "good.event", "{\"n\":3}", null, null, 0));

        OutboxPublisher mixedPublisher = event -> {
            if ("bad.event".equals(event.eventType())) throw new RuntimeException("bad event");
            delivered.add(event);
        };

        OutboxPoller poller = new OutboxPoller(store, mixedPublisher, properties);
        poller.poll();

        assertEquals(2, delivered.size(), "good events should be delivered despite one failure");
        assertEquals(List.of(1L, 3L), store.publishedIds());
    }

    @Test
    void poll_respectsBatchSize() {
        properties.setBatchSize(2);
        for (int i = 0; i < 5; i++) {
            store.save(new OutboxEvent(null, "e", "{}", null, null, 0));
        }

        OutboxPoller poller = new OutboxPoller(store, successPublisher, properties);
        poller.poll();

        assertEquals(2, delivered.size(), "only batchSize events should be delivered per poll");
        assertEquals(2, store.publishedIds().size());
    }
}
