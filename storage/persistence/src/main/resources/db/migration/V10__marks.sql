-- docs/plans/done/TACTICAL-MARKS-PLAN.md §3: geolocated tactical marks (the shared operational picture) --
-- structurally a point version of geofence_zones (V7), with ownership/lifecycle columns instead
-- of the zone's jsonb polygon.
--
-- position is flattened to latitude/longitude/altitude_meters columns (a single GeoPosition, not
-- a polygon, so no jsonb column is warranted here -- same "flatten a small value type into
-- columns" choice asset_usages already makes for GeoPosition, not geofence_zones' jsonb choice
-- for a whole polygon). ownership is flattened to owner_id/group_id, same choice assets makes for
-- Ownership. kind/status/source are the MarkKind/MarkStatus/MarkSource enum names.
--
-- No FK to any other table, same "no cross-entity foreign keys" convention as the rest of this
-- schema (see MODULE.md's Conventions) -- owner_id/group_id are plain UUID columns with no
-- referential check, matching the in-memory reference repository.
CREATE TABLE marks (
    id                  UUID PRIMARY KEY,
    kind                VARCHAR(16)      NOT NULL,
    label               VARCHAR(255)     NOT NULL,
    note                TEXT,
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    altitude_meters     DOUBLE PRECISION,
    owner_id            UUID             NOT NULL,
    group_id            UUID             NOT NULL,
    created_at          TIMESTAMPTZ      NOT NULL,
    status              VARCHAR(16)      NOT NULL,
    source              VARCHAR(16)      NOT NULL
);

CREATE INDEX idx_marks_group_id ON marks (group_id);
CREATE INDEX idx_marks_status ON marks (status);
