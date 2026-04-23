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

public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxStore store;
    private final OutboxPublisher publisher;
    private final OutboxProperties properties;

    private Runnable onPublished    = () -> {};
    private Runnable onFailed       = () -> {};
    private Runnable onDeadLettered = () -> {};

    public OutboxPoller(OutboxStore store, OutboxPublisher publisher, OutboxProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
    }

    public void setOnPublished(Runnable r)    { this.onPublished    = r; }
    public void setOnFailed(Runnable r)       { this.onFailed       = r; }
    public void setOnDeadLettered(Runnable r) { this.onDeadLettered = r; }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:5000}")
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
                onPublished.run();
                published++;
            } catch (Exception e) {
                boolean isLastAttempt = event.attempts() + 1 >= properties.getMaxAttempts();
                store.markFailed(event.id(), e.getMessage(), properties.getMaxAttempts());
                onFailed.run();
                if (isLastAttempt) onDeadLettered.run();
                log.warn("Outbox: failed to publish event {} (type={}, attempt={}/{}): {}",
                        event.id(), event.eventType(),
                        event.attempts() + 1, properties.getMaxAttempts(), e.getMessage());
                failed++;
            }
        }

        if (published > 0 || failed > 0) {
            log.info("Outbox poll complete: {} published, {} failed", published, failed);
        }
    }
}
