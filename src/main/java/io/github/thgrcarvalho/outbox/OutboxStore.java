package io.github.thgrcarvalho.outbox;

import java.util.List;

/**
 * Storage port for outbox entries. The default implementation uses JDBC.
 * Override to use a custom persistence strategy.
 */
public interface OutboxStore {

    /**
     * Persists a new outbox event. Must be called within an active transaction.
     *
     * @param event the event to persist (id will be set by the store)
     * @return the persisted event with the database-assigned id
     */
    OutboxEvent save(OutboxEvent event);

    /**
     * Atomically claims up to {@code batchSize} pending events for delivery,
     * skipping any rows already claimed by another poller instance.
     *
     * <p>The JDBC implementation uses {@code SELECT FOR UPDATE SKIP LOCKED},
     * making it safe to run multiple poller instances concurrently.</p>
     *
     * @param batchSize  maximum number of events to claim
     * @param maxAttempts events that have already failed this many times are excluded
     * @return claimed events, ready for delivery
     */
    List<OutboxEvent> claimBatch(int batchSize, int maxAttempts);

    /**
     * Marks an event as successfully published.
     *
     * @param id the event id
     */
    void markPublished(Long id);

    /**
     * Records a failed delivery attempt. If {@code attempts} reaches
     * {@code maxAttempts}, the event status transitions to {@code FAILED}.
     *
     * @param id          the event id
     * @param error       the error message to record
     * @param maxAttempts threshold at which the event is permanently failed
     */
    void markFailed(Long id, String error, int maxAttempts);
}
