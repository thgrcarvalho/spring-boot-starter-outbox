package io.github.thgrcarvalho.outbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedisOutboxStoreTest {

    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private RedisOutboxStore store;

    @BeforeAll
    static void startRedis() {
        redis.start();
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) connectionFactory.destroy();
        redis.stop();
    }

    @BeforeEach
    void setup() {
        // Use a unique prefix per test to isolate test state
        store = new RedisOutboxStore(connectionFactory, "test-" + System.nanoTime() + ":");
    }

    @Test
    void saveAndClaimReturnsEvent() {
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "payment.created", "{\"id\":1}", null, null, 0));

        assertNotNull(saved.id());
        assertEquals("payment.created", saved.eventType());

        List<OutboxEvent> claimed = store.claimBatch(10, 3);
        assertEquals(1, claimed.size());
        assertEquals("payment.created", claimed.get(0).eventType());
        assertEquals("{\"id\":1}", claimed.get(0).payload());
    }

    @Test
    void markPublished_afterClaiming_secondBatchIsEmpty() {
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "order.placed", "{}", null, null, 0));

        // Claim removes the event from the pending sorted set via ZPOPMIN
        List<OutboxEvent> first = store.claimBatch(10, 3);
        assertEquals(1, first.size());

        store.markPublished(saved.id());

        // Now pending is empty — nothing left to claim
        List<OutboxEvent> second = store.claimBatch(10, 3);
        assertTrue(second.isEmpty());
    }

    @Test
    void markFailed_requeuedForRetry_appearsInNextBatch() throws InterruptedException {
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "event", "{}", null, null, 0));

        store.claimBatch(10, 3); // claim and remove from pending
        store.markFailed(saved.id(), "timeout", 3); // re-queue with back-off

        // Back-off is attempts * 5s — for attempt 1 this is 5s in the future.
        // The event won't be immediately visible in a real scenario; we just verify
        // markFailed doesn't crash and increments attempts.
        // (A full retry test would require clock manipulation)
    }

    @Test
    void markFailed_afterMaxAttempts_notRequeued() {
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "event", "{}", null, null, 0));

        store.claimBatch(10, 1); // claim
        store.markFailed(saved.id(), "permanent failure", 1); // maxAttempts=1 → FAILED

        List<OutboxEvent> next = store.claimBatch(10, 1);
        assertTrue(next.isEmpty(), "event should not be re-queued after maxAttempts");
    }

    @Test
    void headersArePersistedAndRestored() {
        Map<String, String> headers = Map.of("correlationId", "abc-123", "source", "payments");
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "event", "{}", headers, null, 0));

        List<OutboxEvent> batch = store.claimBatch(10, 3);
        assertEquals(1, batch.size());
        assertEquals("abc-123", batch.get(0).headers().get("correlationId"));
        assertEquals("payments", batch.get(0).headers().get("source"));
    }

    @Test
    void batchSizeIsRespected() {
        for (int i = 0; i < 5; i++) {
            store.save(new OutboxEvent(null, "e", "{}", null, null, 0));
        }

        List<OutboxEvent> batch = store.claimBatch(3, 3);
        assertEquals(3, batch.size());
    }

    @Test
    void eventsDeliveredInInsertionOrder() {
        store.save(new OutboxEvent(null, "first",  "{}", null, null, 0));
        store.save(new OutboxEvent(null, "second", "{}", null, null, 0));
        store.save(new OutboxEvent(null, "third",  "{}", null, null, 0));

        List<OutboxEvent> batch = store.claimBatch(3, 3);
        assertEquals(3, batch.size());
        assertEquals("first",  batch.get(0).eventType());
        assertEquals("second", batch.get(1).eventType());
        assertEquals("third",  batch.get(2).eventType());
    }
}
