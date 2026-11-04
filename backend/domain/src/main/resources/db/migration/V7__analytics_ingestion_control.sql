-- Phase 2 (2.9): the delivery control plane for the request-event report.
--
-- Kafka offsets are committed only after ClickHouse has the rows and this table records the
-- contiguous position that the reader may trust. The hourly table is the work queue of the rollup:
-- a new event marks its own hour dirty, and a recomputed hour is published under a new version.

CREATE TABLE analytics_ingest_positions (
    kafka_partition INTEGER PRIMARY KEY CHECK (kafka_partition >= 0),
    next_offset     BIGINT NOT NULL DEFAULT 0 CHECK (next_offset >= 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE analytics_hours (
    hour_start        TIMESTAMPTZ PRIMARY KEY,
    dirty             BOOLEAN NOT NULL DEFAULT TRUE,
    published_version BIGINT NULL,
    last_rolled_at    TIMESTAMPTZ NULL
);

CREATE INDEX idx_analytics_hours_dirty
    ON analytics_hours (hour_start)
    WHERE dirty;

-- Every rollup batch gets one version. Facts carry the versions that published them, so a reader
-- can select exactly the rows of the current publication of each hour.
CREATE SEQUENCE analytics_rollup_version_seq START WITH 1;

CREATE TABLE analytics_rejects (
    id              UUID PRIMARY KEY,
    kafka_partition INTEGER NOT NULL,
    kafka_offset    BIGINT NOT NULL,
    request_id      VARCHAR(64) NULL,
    error_class     VARCHAR(64) NOT NULL,
    detail          VARCHAR(255) NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_analytics_rejects_position UNIQUE (kafka_partition, kafka_offset)
);
