package io.github.thgrcarvalho.outbox.internal;

import io.github.thgrcarvalho.outbox.OutboxEvent;
import io.github.thgrcarvalho.outbox.OutboxProperties;
import io.github.thgrcarvalho.outbox.OutboxPublisher;
import io.github.thgrcarvalho.outbox.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background poller that claims pending outbox events and delivers them.
 *
 * <p>Each poll cycle runs inside a single transaction:
 * <ol>
 *   <li>Claim a batch via {@code SELECT FOR UPDATE SKIP LOCKED} — safe for concurrent polling.</li>
 *   <li>For each event: call {@link OutboxPublisher#publish} then {@code markPublished}.</li>
 *   <li>If publishing fails: call {@code markFailed} (which tracks attempts and eventually
 *       moves the event to {@code FAILED} status after {@code maxAttempts}).</li>
 *   <li>Commit — all markPublished/markFailed writes are durable.</li>
 * </ol>
 *
 * <p><strong>At-least-once:</strong> if the process crashes after publishing but before
 * committing, the event will be re-delivered on the next poll. Consumers must be idempotent.</p>
 */
public final class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxStore store;
    private final OutboxPublisher publisher;
    private final OutboxProperties properties;

    public OutboxPoller(OutboxStore store, OutboxPublisher publisher, OutboxProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{@outboxProperties.pollIntervalMs}")
    @Transactional
    public void poll() {
        List<OutboxEvent> batch = store.claimBatch(
                properties.getBatchSize(), properties.getMaxAttempts());

        if (batch.isEmpty()) return;

        log.debug("Outbox poll: processing {} event(s)", batch.size());

        int published = 0;
        int failed = 0;
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                store.markPublished(event.id());
                published++;
            } catch (Exception e) {
                store.markFailed(event.id(), e.getMessage(), properties.getMaxAttempts());
                log.warn("Outbox: failed to publish event {} (type={}, attempt={}): {}",
                        event.id(), event.eventType(), event.attempts() + 1, e.getMessage());
                failed++;
            }
        }

        if (published > 0 || failed > 0) {
            log.info("Outbox poll complete: {} published, {} failed", published, failed);
        }
    }
}
