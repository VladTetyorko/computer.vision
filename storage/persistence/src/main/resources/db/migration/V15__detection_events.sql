-- docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3: debounced detection events (docs/plans/done/MVP2-PLAN.md §E, E-a),
-- durable. DetectionEventRepositoryPort was wired unconditionally to InMemoryDetectionEventRepository
-- with no Postgres option at all -- this is the first migration that gives it one.
--
-- Unlike detection_results (V3, append-only, one immutable row per completed inference), a
-- detection event mutates over its own open lifetime (last_seen/peak_confidence advance while it
-- stays open, then it closes) -- id is the domain's own DetectionEventId, not synthetic, and
-- JpaDetectionEventRepository#save is a genuine upsert (merge-by-id), never a plain insert.
--
-- position is flattened to nullable position_latitude/position_longitude/position_altitude_meters
-- columns, same "flatten a small, genuinely-optional value type into columns" choice asset_usages
-- (V3) makes for its own start/last GeoPosition -- a latitude/longitude pair is null together iff
-- the position itself is null.
--
-- No FK to any other table, same "no cross-entity foreign keys" convention as the rest of this
-- schema (see MODULE.md's Conventions).
--
-- Two access paths, two indexes: findByStream orders one stream's events newest-first by
-- last_seen (also the column the retention prune query groups/orders by, see
-- JpaDetectionEventRepository's javadoc); findRecent does the same across every stream, so
-- last_seen alone is indexed too.
CREATE TABLE detection_events (
    id                        UUID             PRIMARY KEY,
    stream_id                 UUID             NOT NULL,
    asset_id                  UUID,
    label                     VARCHAR(255)     NOT NULL,
    peak_confidence           DOUBLE PRECISION NOT NULL,
    first_seen                TIMESTAMPTZ      NOT NULL,
    last_seen                 TIMESTAMPTZ      NOT NULL,
    state                     VARCHAR(16)      NOT NULL,
    position_latitude         DOUBLE PRECISION,
    position_longitude        DOUBLE PRECISION,
    position_altitude_meters  DOUBLE PRECISION
);

CREATE INDEX idx_detection_events_stream_id_last_seen ON detection_events (stream_id, last_seen);
CREATE INDEX idx_detection_events_last_seen ON detection_events (last_seen);
