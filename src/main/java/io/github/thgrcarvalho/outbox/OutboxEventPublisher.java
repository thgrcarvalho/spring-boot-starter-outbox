package io.github.thgrcarvalho.outbox;

import java.util.Map;

/**
 * Writes events to the outbox table within the caller's active transaction.
 *
 * <p>Call this inside a {@code @Transactional} service method alongside your
 * business writes. The event is written to the outbox in the same DB transaction,
 * so it is committed or rolled back together with your business data:</p>
 *
 * <pre>{@code
 * @Transactional
 * public Payment processPayment(ChargeRequest req) {
 *     Payment payment = paymentRepository.save(Payment.pending(req));
 *
 *     outboxPublisher.publish("payment.created",
 *         objectMapper.writeValueAsString(payment));
 *
 *     return payment;
 * }
 * }</pre>
 *
 * <p>A background poller will pick up the event and deliver it to the configured
 * {@link OutboxPublisher} (Kafka, RabbitMQ, HTTP webhook, etc.).</p>
 */
public interface OutboxEventPublisher {

    /**
     * Writes {@code eventType} + {@code payload} to the outbox table.
     *
     * @param eventType logical event name, e.g. {@code "payment.created"}
     * @param payload   event body, typically a JSON string
     */
    void publish(String eventType, String payload);

    /**
     * Writes an event with additional metadata headers.
     *
     * @param eventType logical event name
     * @param payload   event body
     * @param headers   key-value metadata (correlation IDs, routing hints, etc.)
     */
    void publish(String eventType, String payload, Map<String, String> headers);
}
