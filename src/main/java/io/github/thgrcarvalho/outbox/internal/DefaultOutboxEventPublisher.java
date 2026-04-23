package io.github.thgrcarvalho.outbox.internal;

import io.github.thgrcarvalho.outbox.OutboxEvent;
import io.github.thgrcarvalho.outbox.OutboxEventPublisher;
import io.github.thgrcarvalho.outbox.OutboxStore;

import java.util.Map;

public final class DefaultOutboxEventPublisher implements OutboxEventPublisher {

    private final OutboxStore store;

    public DefaultOutboxEventPublisher(OutboxStore store) {
        this.store = store;
    }

    @Override
    public void publish(String eventType, String payload) {
        publish(eventType, payload, Map.of());
    }

    @Override
    public void publish(String eventType, String payload, Map<String, String> headers) {
        store.save(new OutboxEvent(null, eventType, payload, headers, null, 0));
    }
}
