-- docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave B3: platform Events, durable. EventPublisherPort's
-- delivery was fire-and-forget (logging + the `event` SSE topic) with no store behind it at all --
-- the notification bell and /manage/system were a pure `computed` over the live feed's in-memory
-- log, so they started empty on every page load and lost everything on an SSE reconnect. This is
-- the first migration that gives platform Events a durable home.
--
-- id is the domain's own event id (Event#id(), a String -- see EventHistoryEntity's own javadoc for
-- why this is VARCHAR rather than UUID). No FK to any other table (not stream_id to anything) --
-- same "no cross-entity foreign keys" convention as the rest of this schema (see MODULE.md's
-- Conventions); a stream can be long gone by the time its history is read back.
--
-- attributes is jsonb (the whole free-form Map<String,String>), same mechanism as
-- audit_entries.details.
--
-- Two read access paths, two indexes: findRecent (occurred_at alone, also carries the pruning
-- query's own ORDER BY) and findSince (occurred_at again, since it is both the filter and the sort
-- key -- one index serves both).
--
-- Excluded from db_audit_log (see PostgresDockerIntegrationTest#EXCLUDED_TABLES): this table is
-- machine-generated, append-only, historical output -- the same character as
-- detection_events/telemetry_samples, not the operator-authored control-plane configuration
-- audit_entries itself, ironically, is the one thing in this migration that would NOT belong here if
-- it did not already have its own dedicated store.
CREATE TABLE event_history (
    id          VARCHAR(64) PRIMARY KEY,
    stream_id   UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    type        VARCHAR(32) NOT NULL,
    message     TEXT        NOT NULL,
    attributes  JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_event_history_occurred_at ON event_history (occurred_at DESC);
