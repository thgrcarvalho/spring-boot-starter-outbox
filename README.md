# spring-boot-starter-outbox

[![CI](https://github.com/thgrcarvalho/spring-boot-starter-outbox/actions/workflows/ci.yml/badge.svg)](https://github.com/thgrcarvalho/spring-boot-starter-outbox/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.thgrcarvalho/spring-boot-starter-outbox)](https://central.sonatype.com/artifact/io.github.thgrcarvalho/spring-boot-starter-outbox)
[![codecov](https://codecov.io/gh/thgrcarvalho/spring-boot-starter-outbox/branch/main/graph/badge.svg)](https://codecov.io/gh/thgrcarvalho/spring-boot-starter-outbox)

Spring Boot starter implementing the **transactional outbox pattern** — the standard technique for guaranteeing at-least-once event delivery without two-phase commit.

## The problem this solves

```java
// ❌ This can fail halfway: payment saved but event never delivered
payment = paymentRepository.save(payment);
kafka.send("payment.created", payment); // crashes here?
```

If your process dies between the DB write and the message publish, you have a ghost payment with no downstream notification. Two-phase commit across a database and a message broker is operationally painful and usually not an option.

## The solution

```java
// ✅ Both writes are in the same DB transaction — they succeed or fail together
@Transactional
public Payment processPayment(ChargeRequest req) {
    Payment payment = paymentRepository.save(Payment.pending(req));
    outboxPublisher.publish("payment.created", toJson(payment));
    return payment;
}
```

The event is written to an `outbox_event` table in the same transaction as the business data. A background poller then reads pending events and delivers them to Kafka, RabbitMQ, HTTP, or any other system you configure. If the poller dies mid-delivery, the event is retried on the next poll cycle.

## Installation

**Gradle:**
```groovy
dependencies {
    implementation 'io.github.thgrcarvalho:spring-boot-starter-outbox:0.1.0'
}
```

**Maven:**
```xml
<dependency>
    <groupId>io.github.thgrcarvalho</groupId>
    <artifactId>spring-boot-starter-outbox</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Setup

**1. Create the outbox table** (copy from `outbox-schema.sql` on the classpath):

```sql
CREATE TABLE outbox_event (
    id           BIGSERIAL    PRIMARY KEY,
    event_type   VARCHAR(255) NOT NULL,
    payload      TEXT         NOT NULL,
    headers      TEXT         NOT NULL DEFAULT '{}',
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT
);
CREATE INDEX idx_outbox_status_id ON outbox_event (status, id) WHERE status = 'PENDING';
```

**2. Implement `OutboxPublisher`** — this is where your events actually go:

```java
@Bean
OutboxPublisher kafkaOutboxPublisher(KafkaTemplate<String, String> kafka) {
    return event -> kafka.send(event.eventType(), event.payload()).get();
}
```

**3. Inject `OutboxEventPublisher` and publish within `@Transactional` methods:**

```java
@Autowired
private OutboxEventPublisher outboxPublisher;

@Transactional
public Payment processPayment(ChargeRequest req) {
    Payment payment = paymentRepository.save(Payment.pending(req));
    outboxPublisher.publish("payment.created", toJson(payment));
    return payment;
}
```

That's it. The poller starts automatically.

## Configuration

```yaml
outbox:
  poll-interval-ms: 5000   # how often to poll (default: 5s)
  batch-size: 100          # events per poll cycle (default: 100)
  max-attempts: 3          # retries before marking FAILED (default: 3)
  table-name: outbox_event # outbox table name (default: outbox_event)
```

## Delivery guarantees

**At-least-once** — if the poller delivers an event but crashes before committing the `markPublished` update, the event is delivered again on the next poll. Event consumers must be idempotent.

Consider pairing this starter with [spring-boot-starter-idempotency](https://github.com/thgrcarvalho/spring-boot-starter-idempotency) on the consumer side.

## `SELECT FOR UPDATE SKIP LOCKED`

The JDBC store uses this PostgreSQL primitive to claim batches atomically. Multiple poller instances can run concurrently — each claims a different batch of rows without coordination. Rows locked by one instance are invisible to others until the transaction commits.

## Running tests

```bash
./gradlew test
```

Integration tests run against H2 in-memory. The JDBC store is tested end-to-end including retry and failure scenarios.

## Tech

Java 21 · Spring Boot 3 (autoconfigure, jdbc) · H2 (test) · Gradle · JUnit 5
