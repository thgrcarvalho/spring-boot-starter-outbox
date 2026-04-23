package io.github.thgrcarvalho.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * An event entry in the outbox table, pending delivery to an external system.
 *
 * @param id          database-assigned identifier; {@code null} before the first save
 * @param eventType   logical event name, e.g. {@code "payment.created"}
 * @param payload     the event body, typically a JSON string
 * @param headers     optional key-value metadata (correlation IDs, routing hints, etc.)
 * @param createdAt   when the event was written to the outbox
 * @param attempts    number of delivery attempts made so far
 */
public record OutboxEvent(
        Long id,
        String eventType,
        String payload,
        Map<String, String> headers,
        Instant createdAt,
        int attempts
) {
    public OutboxEvent {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
