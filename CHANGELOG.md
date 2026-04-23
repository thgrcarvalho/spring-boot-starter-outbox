# Changelog

## [0.2.0] — 2026-04-23

### Added
- **`RedisOutboxStore`** — Redis-backed store for deployments without a relational database.
  Uses sorted sets for insertion-order delivery and `ZPOPMIN` for atomic batch claiming.
  Supports back-off retry (attempt × 5s delay) and dead-letter tracking.
- **Micrometer metrics** — when `micrometer-core` is on the classpath, the poller registers:
  - `outbox.events.published` counter — successful deliveries
  - `outbox.events.failed` counter — transient failures (will retry)
  - `outbox.events.dead_lettered` counter — events moved to FAILED after max attempts

### Changed
- `OutboxPoller` now exposes `setOnPublished`, `setOnFailed`, `setOnDeadLettered` callbacks
  so metrics are wired without importing Micrometer in the poller itself.

### Note
`RedisOutboxStore` does not participate in Spring `@Transactional` boundaries — events are
saved to Redis independently of any surrounding database transaction. Use `JdbcOutboxStore`
when you need the outbox write to be atomically co-committed with business data.

## [0.1.0] — 2026-04-23

Initial release — transactional outbox pattern with JDBC store and in-memory fallback.
