package io.github.thgrcarvalho.outbox;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed {@link OutboxStore} for deployments without a relational database.
 *
 * <p>Data model:</p>
 * <ul>
 *   <li>Each event is stored in a Redis hash at {@code {prefix}event:{id}}</li>
 *   <li>Pending event IDs are tracked in a sorted set {@code {prefix}pending}
 *       scored by insertion timestamp (milliseconds) for insertion-order delivery</li>
 *   <li>Failed event IDs are tracked in a sorted set {@code {prefix}failed}</li>
 * </ul>
 *
 * <p>Batch claiming uses {@code ZPOPMIN} — Redis's atomic operation for removing
 * the N lowest-scored elements. This is safe for concurrent polling without Lua scripts
 * because {@code ZPOPMIN} is guaranteed atomic: each event is claimed by exactly one
 * poller instance.</p>
 *
 * <p><strong>Durability note:</strong> unlike the JDBC store, {@code RedisOutboxStore}
 * does not participate in database transactions. Events written via
 * {@link OutboxEventPublisher#publish} are saved to Redis independently of any
 * surrounding {@code @Transactional} boundary. Use the JDBC store when you need
 * events to be atomically co-committed with business data.</p>
 *
 * <p>Wire it up to activate:</p>
 * <pre>{@code
 * @Bean
 * OutboxStore redisOutboxStore(RedisConnectionFactory connectionFactory) {
 *     return new RedisOutboxStore(connectionFactory);
 * }
 * }</pre>
 */
public final class RedisOutboxStore implements OutboxStore {

    private static final String DEFAULT_PREFIX = "outbox:";
    private static final long PUBLISHED_TTL_SECONDS = 86_400; // 24h retention for published events

    private final RedisConnectionFactory connectionFactory;
    private final String prefix;
    private long idSequence = System.currentTimeMillis();

    public RedisOutboxStore(RedisConnectionFactory connectionFactory) {
        this(connectionFactory, DEFAULT_PREFIX);
    }

    public RedisOutboxStore(RedisConnectionFactory connectionFactory, String prefix) {
        this.connectionFactory = connectionFactory;
        this.prefix = prefix;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        long id = nextId();
        Instant now = event.createdAt() != null ? event.createdAt() : Instant.now();

        try (RedisConnection conn = connectionFactory.getConnection()) {
            byte[] hashKey = eventHashKey(id);
            conn.hashCommands().hSet(hashKey, b("eventType"),  b(event.eventType()));
            conn.hashCommands().hSet(hashKey, b("payload"),    b(event.payload()));
            conn.hashCommands().hSet(hashKey, b("headers"),    b(headersToString(event.headers())));
            conn.hashCommands().hSet(hashKey, b("createdAt"),  b(String.valueOf(now.toEpochMilli())));
            conn.hashCommands().hSet(hashKey, b("attempts"),   b("0"));

            // Add to pending sorted set, scored by insertion time for ordering
            conn.zSetCommands().zAdd(pendingKey(), now.toEpochMilli(), b(String.valueOf(id)));
        }

        return new OutboxEvent(id, event.eventType(), event.payload(),
                event.headers(), now, 0);
    }

    @Override
    public List<OutboxEvent> claimBatch(int batchSize, int maxAttempts) {
        List<OutboxEvent> result = new ArrayList<>();
        try (RedisConnection conn = connectionFactory.getConnection()) {
            // ZPOPMIN atomically removes and returns up to batchSize lowest-scored members
            var popped = conn.zSetCommands().zPopMin(pendingKey(), batchSize);
            if (popped == null || popped.isEmpty()) return result;

            for (var tuple : popped) {
                if (tuple.getValue() == null) continue;
                String idStr = new String(tuple.getValue(), StandardCharsets.UTF_8);
                long id = Long.parseLong(idStr);
                OutboxEvent event = loadEvent(conn, id);
                if (event != null && event.attempts() < maxAttempts) {
                    result.add(event);
                }
                // Skip events already at max attempts (shouldn't be in pending, but guard anyway)
            }
        }
        return result;
    }

    @Override
    public void markPublished(Long id) {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            byte[] hashKey = eventHashKey(id);
            conn.hashCommands().hSet(hashKey, b("status"), b("PUBLISHED"));
            conn.hashCommands().hSet(hashKey, b("publishedAt"), b(String.valueOf(Instant.now().toEpochMilli())));
            conn.keyCommands().expire(hashKey, PUBLISHED_TTL_SECONDS);
        }
    }

    @Override
    public void markFailed(Long id, String error, int maxAttempts) {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            byte[] hashKey = eventHashKey(id);

            byte[] attemptsBytes = conn.hashCommands().hGet(hashKey, b("attempts"));
            int currentAttempts = attemptsBytes != null
                    ? Integer.parseInt(new String(attemptsBytes, StandardCharsets.UTF_8)) : 0;
            int newAttempts = currentAttempts + 1;

            conn.hashCommands().hSet(hashKey, b("attempts"), b(String.valueOf(newAttempts)));
            if (error != null) {
                conn.hashCommands().hSet(hashKey, b("lastError"),
                        b(error.length() > 1000 ? error.substring(0, 1000) : error));
            }

            if (newAttempts >= maxAttempts) {
                conn.hashCommands().hSet(hashKey, b("status"), b("FAILED"));
                double score = Instant.now().toEpochMilli();
                conn.zSetCommands().zAdd(failedKey(), score, b(String.valueOf(id)));
            } else {
                // Re-queue with a slight delay (score = now + attempts * 5s back-off)
                double retryScore = Instant.now().toEpochMilli() + (long) newAttempts * 5_000;
                conn.zSetCommands().zAdd(pendingKey(), retryScore, b(String.valueOf(id)));
            }
        }
    }

    private OutboxEvent loadEvent(RedisConnection conn, long id) {
        Map<byte[], byte[]> fields = conn.hashCommands().hGetAll(eventHashKey(id));
        if (fields == null || fields.isEmpty()) return null;

        Map<String, String> strFields = new HashMap<>();
        fields.forEach((k, v) -> {
            if (k != null && v != null)
                strFields.put(new String(k, StandardCharsets.UTF_8),
                              new String(v, StandardCharsets.UTF_8));
        });

        return new OutboxEvent(
                id,
                strFields.get("eventType"),
                strFields.get("payload"),
                stringToHeaders(strFields.getOrDefault("headers", "")),
                parseInstant(strFields.get("createdAt")),
                Integer.parseInt(strFields.getOrDefault("attempts", "0"))
        );
    }

    // ── Key helpers ──────────────────────────────────────────────────────────

    private byte[] eventHashKey(long id) { return b(prefix + "event:" + id); }
    private byte[] pendingKey()           { return b(prefix + "pending"); }
    private byte[] failedKey()            { return b(prefix + "failed"); }

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static Instant parseInstant(String epochMillis) {
        if (epochMillis == null) return Instant.now();
        try { return Instant.ofEpochMilli(Long.parseLong(epochMillis)); }
        catch (NumberFormatException e) { return Instant.now(); }
    }

    // Reuse the same minimal JSON helpers as JdbcOutboxStore logic
    private static String headersToString(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('\n');
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private static Map<String, String> stringToHeaders(String s) {
        if (s == null || s.isBlank()) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) result.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return Map.copyOf(result);
    }

    private synchronized long nextId() {
        long now = System.currentTimeMillis();
        idSequence = Math.max(idSequence + 1, now);
        return idSequence;
    }
}
