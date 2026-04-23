package io.github.thgrcarvalho.outbox;

/**
 * Port interface for delivering outbox events to an external system.
 *
 * <p>Implement this and register it as a Spring bean — the autoconfiguration
 * wires it into the poller automatically. The implementation determines
 * <em>where</em> events go (Kafka, RabbitMQ, HTTP, etc.):</p>
 *
 * <pre>{@code
 * @Bean
 * OutboxPublisher kafkaOutboxPublisher(KafkaTemplate<String, String> kafka) {
 *     return event -> kafka.send(event.eventType(), event.payload())
 *                         .get(); // block to confirm delivery before commit
 * }
 * }</pre>
 *
 * <p><strong>At-least-once delivery:</strong> if this method succeeds but the
 * subsequent database commit fails, the event will be published again on the
 * next poll cycle. Event consumers must be idempotent.</p>
 *
 * <p>Throw any exception to signal delivery failure. The poller will retry
 * up to the configured {@code outbox.max-attempts}, after which the event is
 * moved to {@code FAILED} status.</p>
 */
@FunctionalInterface
public interface OutboxPublisher {

    /**
     * Delivers {@code event} to the external system.
     *
     * @param event the outbox event to deliver
     * @throws Exception if delivery fails (will be retried)
     */
    void publish(OutboxEvent event) throws Exception;
}
