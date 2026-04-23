package io.github.thgrcarvalho.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the outbox starter.
 *
 * <pre>{@code
 * outbox:
 *   poll-interval-ms: 5000    # how often to poll for new events (default: 5s)
 *   batch-size: 100           # events claimed per poll cycle (default: 100)
 *   max-attempts: 3           # retries before marking an event FAILED (default: 3)
 *   table-name: outbox_event  # name of the outbox table (default: outbox_event)
 * }</pre>
 */
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    private long pollIntervalMs = 5_000;
    private int batchSize = 100;
    private int maxAttempts = 3;
    private String tableName = "outbox_event";

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
}
