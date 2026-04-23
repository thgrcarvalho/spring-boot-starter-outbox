-- Outbox event table for spring-boot-starter-outbox.
-- Add this to your Flyway/Liquibase migrations before enabling the starter.
--
-- Tested with PostgreSQL 15+. For other databases:
--   - H2: replace BIGSERIAL with BIGINT AUTO_INCREMENT, TIMESTAMPTZ with TIMESTAMP
--   - MySQL: replace BIGSERIAL with BIGINT AUTO_INCREMENT, TIMESTAMPTZ with DATETIME(6)

CREATE TABLE outbox_event (
    id           BIGSERIAL    PRIMARY KEY,
    event_type   VARCHAR(255) NOT NULL,
    payload      TEXT         NOT NULL,
    headers      TEXT         NOT NULL DEFAULT '{}',
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING',  -- PENDING | PUBLISHED | FAILED
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT
);

-- Required for efficient polling
CREATE INDEX idx_outbox_status_id ON outbox_event (status, id)
    WHERE status = 'PENDING';
