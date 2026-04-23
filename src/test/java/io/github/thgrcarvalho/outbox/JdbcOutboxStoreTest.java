package io.github.thgrcarvalho.outbox;

import io.github.thgrcarvalho.outbox.internal.JdbcOutboxStore;
import io.github.thgrcarvalho.outbox.test.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestApplication.class,
        properties = {"spring.datasource.url=jdbc:h2:mem:outbox_test;DB_CLOSE_DELAY=-1",
                       "spring.datasource.driver-class-name=org.h2.Driver",
                       "spring.datasource.username=sa", "spring.datasource.password="})
@Transactional
class JdbcOutboxStoreTest {

    @Autowired
    JdbcTemplate jdbc;

    private JdbcOutboxStore store;

    @BeforeEach
    void setup() {
        store = new JdbcOutboxStore(jdbc, "outbox_event");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS outbox_event (
                id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                event_type   VARCHAR(255) NOT NULL,
                payload      TEXT NOT NULL,
                headers      TEXT NOT NULL DEFAULT '{}',
                status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
                created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                published_at TIMESTAMP,
                attempts     INTEGER      NOT NULL DEFAULT 0,
                last_error   TEXT
            )
            """);
        jdbc.execute("DELETE FROM outbox_event");
    }

    @Test
    void saveAndClaimReturnsEvent() {
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "payment.created", "{\"id\":42}", null, null, 0));

        assertNotNull(saved.id());
        assertEquals("payment.created", saved.eventType());

        List<OutboxEvent> claimed = store.claimBatch(10, 3);
        assertEquals(1, claimed.size());
        assertEquals("payment.created", claimed.get(0).eventType());
        assertEquals("{\"id\":42}", claimed.get(0).payload());
    }

    @Test
    void markPublished_removesEventFromPendingBatch() {
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "order.placed", "{}", null, null, 0));

        store.markPublished(saved.id());

        List<OutboxEvent> remaining = store.claimBatch(10, 3);
        assertTrue(remaining.isEmpty());
    }

    @Test
    void markFailed_incrementsAttemptsAndKeepsPending() {
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "event.type", "{}", null, null, 0));

        store.markFailed(saved.id(), "timeout", 3);

        List<OutboxEvent> batch = store.claimBatch(10, 3);
        assertEquals(1, batch.size());
        assertEquals(1, batch.get(0).attempts());
    }

    @Test
    void markFailed_afterMaxAttempts_excludedFromBatch() {
        OutboxEvent saved = store.save(
                new OutboxEvent(null, "event.type", "{}", null, null, 0));

        store.markFailed(saved.id(), "err", 1); // maxAttempts=1 → moves to FAILED

        List<OutboxEvent> batch = store.claimBatch(10, 1);
        assertTrue(batch.isEmpty());
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
    void claimBatch_respectsBatchSize() {
        for (int i = 0; i < 5; i++) {
            store.save(new OutboxEvent(null, "e", "{}", null, null, 0));
        }

        List<OutboxEvent> batch = store.claimBatch(3, 3);
        assertEquals(3, batch.size());
    }

}
