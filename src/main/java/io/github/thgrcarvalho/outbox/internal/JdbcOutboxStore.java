package io.github.thgrcarvalho.outbox.internal;

import io.github.thgrcarvalho.outbox.OutboxEvent;
import io.github.thgrcarvalho.outbox.OutboxStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * JDBC-backed outbox store. Requires a {@code outbox_event} table (or the name
 * configured via {@code outbox.table-name}). See the schema in the project README.
 *
 * <p>Uses {@code SELECT FOR UPDATE SKIP LOCKED} for safe multi-instance polling —
 * multiple poller threads or pods can run concurrently without processing the same
 * event twice.</p>
 */
public final class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbc;
    private final String table;

    public JdbcOutboxStore(JdbcTemplate jdbc, String tableName) {
        this.jdbc = jdbc;
        this.table = tableName;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        String sql = "INSERT INTO " + table +
                " (event_type, payload, headers, status, created_at, attempts) " +
                "VALUES (?, ?, ?, 'PENDING', ?, 0)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, event.eventType());
            ps.setString(2, event.payload());
            ps.setString(3, headersToJson(event.headers()));
            ps.setTimestamp(4, Timestamp.from(event.createdAt()));
            return ps;
        }, keyHolder);

        Number id = keyHolder.getKey();
        return new OutboxEvent(
                id != null ? id.longValue() : null,
                event.eventType(),
                event.payload(),
                event.headers(),
                event.createdAt(),
                0
        );
    }

    @Override
    public List<OutboxEvent> claimBatch(int batchSize, int maxAttempts) {
        // SELECT FOR UPDATE SKIP LOCKED: atomically claims rows, skipping any
        // already locked by another poller instance — safe for concurrent polling.
        String sql = "SELECT id, event_type, payload, headers, created_at, attempts " +
                "FROM " + table + " " +
                "WHERE status = 'PENDING' AND attempts < ? " +
                "ORDER BY id ASC " +
                "LIMIT ? " +
                "FOR UPDATE SKIP LOCKED";

        return jdbc.query(sql, rowMapper(), maxAttempts, batchSize);
    }

    @Override
    public void markPublished(Long id) {
        jdbc.update(
                "UPDATE " + table + " SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), id
        );
    }

    @Override
    public void markFailed(Long id, String error, int maxAttempts) {
        jdbc.update(
                "UPDATE " + table +
                " SET attempts = attempts + 1, last_error = ?, " +
                "    status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END " +
                "WHERE id = ?",
                truncate(error, 1000), maxAttempts, id
        );
    }

    private static RowMapper<OutboxEvent> rowMapper() {
        return (rs, rowNum) -> new OutboxEvent(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                jsonToHeaders(rs.getString("headers")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("attempts")
        );
    }

    // Minimal JSON for headers — avoids pulling in Jackson as a dependency.
    static String headersToJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escape(e.getKey())).append('"')
              .append(':')
              .append('"').append(escape(e.getValue())).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    static Map<String, String> jsonToHeaders(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return Map.of();
        // Simple key-value JSON parser — sufficient for flat string maps.
        Map<String, String> result = new java.util.LinkedHashMap<>();
        String inner = json.trim().replaceAll("^\\{|\\}$", "");
        for (String pair : inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String[] kv = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().replaceAll("^\"|\"$", "");
                String v = kv[1].trim().replaceAll("^\"|\"$", "");
                result.put(unescape(k), unescape(v));
            }
        }
        return Map.copyOf(result);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", "\n")
                .replace("\\r", "\r").replace("\\\\", "\\");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
