package io.github.thgrcarvalho.outbox.internal;

import io.github.thgrcarvalho.outbox.OutboxEvent;
import io.github.thgrcarvalho.outbox.OutboxStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class InMemoryOutboxStore implements OutboxStore {

    private enum Status { PENDING, PUBLISHED, FAILED }

    private record Entry(OutboxEvent event, Status status, String lastError) {}

    private final ConcurrentHashMap<Long, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    @Override
    public OutboxEvent save(OutboxEvent event) {
        long id = idSequence.getAndIncrement();
        OutboxEvent saved = new OutboxEvent(id, event.eventType(), event.payload(),
                event.headers(), event.createdAt(), 0);
        store.put(id, new Entry(saved, Status.PENDING, null));
        return saved;
    }

    @Override
    public synchronized List<OutboxEvent> claimBatch(int batchSize, int maxAttempts) {
        return store.values().stream()
                .filter(e -> e.status() == Status.PENDING && e.event().attempts() < maxAttempts)
                .sorted((a, b) -> Long.compare(a.event().id(), b.event().id()))
                .limit(batchSize)
                .map(Entry::event)
                .collect(Collectors.toList());
    }

    @Override
    public void markPublished(Long id) {
        store.computeIfPresent(id, (k, e) -> new Entry(e.event(), Status.PUBLISHED, null));
    }

    @Override
    public void markFailed(Long id, String error, int maxAttempts) {
        store.computeIfPresent(id, (k, e) -> {
            int newAttempts = e.event().attempts() + 1;
            OutboxEvent updated = new OutboxEvent(e.event().id(), e.event().eventType(),
                    e.event().payload(), e.event().headers(), e.event().createdAt(), newAttempts);
            Status newStatus = newAttempts >= maxAttempts ? Status.FAILED : Status.PENDING;
            return new Entry(updated, newStatus, error);
        });
    }

    /** Clears all entries — useful in tests. */
    public void clear() {
        store.clear();
    }

    /** Returns all published event ids — useful in tests. */
    public List<Long> publishedIds() {
        return store.entrySet().stream()
                .filter(e -> e.getValue().status() == Status.PUBLISHED)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
    }

    /** Returns all failed event ids — useful in tests. */
    public List<Long> failedIds() {
        return store.entrySet().stream()
                .filter(e -> e.getValue().status() == Status.FAILED)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
    }
}
