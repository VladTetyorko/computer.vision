-- History schema for docs/MVP2-PLAN.md P-b: asset usages, telemetry samples,
-- detection results. Telemetry/detections are append-heavy (one row per
-- sample/result while a usage/stream is active), so both are indexed on
-- (usage_id|stream_id, timestamp) for the read pattern this task and the
-- upcoming R-a replay API both need: "recent/bounded slice for one
-- usage/stream, time-ordered". Same conventions as V1: UUID primary keys,
-- no foreign keys to categories/devices/assets/asset_usages (see V1's own
-- comment and each entity's javadoc for why — the in-memory reference
-- repositories this adapter must stay behavior-compatible with perform no
-- referential checks either).
--
-- telemetry_samples/detection_results rows have no domain-level identity of
-- their own (Telemetry/DetectionResult carry no id field) — their UUID `id`
-- is synthesized by the adapter at save time purely to give each row a
-- primary key, and is never surfaced back through the ports.

CREATE TABLE asset_usages (
    id                    UUID             PRIMARY KEY,
    asset_id              UUID             NOT NULL,
    started_at            TIMESTAMPTZ      NOT NULL,
    ended_at              TIMESTAMPTZ,
    start_latitude        DOUBLE PRECISION,
    start_longitude       DOUBLE PRECISION,
    start_altitude_meters DOUBLE PRECISION,
    last_latitude         DOUBLE PRECISION,
    last_longitude        DOUBLE PRECISION,
    last_altitude_meters  DOUBLE PRECISION,
    sample_count          BIGINT           NOT NULL DEFAULT 0
);

CREATE INDEX idx_asset_usages_asset_id ON asset_usages (asset_id);

CREATE TABLE telemetry_samples (
    id              UUID             PRIMARY KEY,
    usage_id        UUID             NOT NULL,
    device_id       UUID             NOT NULL,
    at              TIMESTAMPTZ      NOT NULL,
    latitude        DOUBLE PRECISION,
    longitude       DOUBLE PRECISION,
    altitude_meters DOUBLE PRECISION,
    heading_degrees DOUBLE PRECISION,
    battery_percent DOUBLE PRECISION,
    extra           JSONB            NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_telemetry_samples_usage_id_at ON telemetry_samples (usage_id, at);

CREATE TABLE detection_results (
    id                      UUID        PRIMARY KEY,
    stream_id               UUID        NOT NULL,
    frame_sequence          BIGINT      NOT NULL,
    captured_at             TIMESTAMPTZ NOT NULL,
    detections              JSONB       NOT NULL DEFAULT '[]'::jsonb,
    inference_latency_nanos BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_detection_results_stream_id_captured_at ON detection_results (stream_id, captured_at);
