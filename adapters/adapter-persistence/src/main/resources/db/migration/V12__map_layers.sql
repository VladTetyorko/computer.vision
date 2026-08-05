-- docs/MAP-REWORK-PLAN.md §4.4: the map as a Common Operational Picture -- named layers with
-- grantable access, drawings on those layers, and the mark columns that turn a flat, deployment-wide
-- mark list into a scoped, affiliation-aware, verifiable one.
--
-- Migration number: the plan's own sketch says "V11__map_layers.sql", which was stale by the time
-- this wave ran -- V11 was already taken by V11__training_datasets.sql (docs/CV-TRAINING-PLAN.md
-- Wave T3). V12 is the next free number, confirmed by listing this directory first.
--
-- Foreign keys, deliberately partial (a documented deviation from the plan's parenthetical "FK
-- cascade" on all three): only map_layer_grants gets one. That table is an element collection of
-- the map_layers aggregate -- Hibernate owns both sides, writes them in one transaction, and never
-- inserts a grant without its layer -- so the FK is free correctness. marks.layer_id and
-- map_drawings.layer_id get NO foreign key, matching this schema's standing convention (see
-- MODULE.md's Conventions): a real constraint there would reject writes the in-memory reference
-- repositories (vision-app devsupport, what the default-config app actually runs) happily accept,
-- breaking the round-trip parity this module is judged against. The layer -> marks/drawings cascade
-- on delete is already performed in application code by DefaultMapLayerService#delete, which also
-- has to emit one MapEvent per cascaded row -- something ON DELETE CASCADE could not do anyway.

-- The layers marks and drawings live on. Ownership is flattened to owner_user_id/group_id, the same
-- choice assets/marks/datasets already make for Ownership.
CREATE TABLE map_layers (
    id              UUID PRIMARY KEY,
    name            VARCHAR(80)  NOT NULL,
    kind            VARCHAR(16)  NOT NULL,
    owner_user_id   UUID         NOT NULL,
    group_id        UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

-- A layer's access list, as an element-collection child table rather than a jsonb column on
-- map_layers. Grants differ from every other collection this schema stores as jsonb (polygon,
-- memberships, classes, annotations): they are the one collection whose individual rows are a
-- security decision, so having them queryable/auditable in SQL is worth a table. The composite
-- primary key also enforces "at most one grant per subject per layer" for free, which is exactly
-- MapLayer's own contract.
CREATE TABLE map_layer_grants (
    layer_id        UUID        NOT NULL REFERENCES map_layers (id) ON DELETE CASCADE,
    subject_type    VARCHAR(16) NOT NULL,
    subject_id      UUID        NOT NULL,
    level           VARCHAR(16) NOT NULL,
    PRIMARY KEY (layer_id, subject_type, subject_id)
);

-- Lines, polygons, arrows and text annotations. points is the whole ordered List<GeoPosition> as
-- one jsonb column -- same mechanism/rationale as geofence_zones.polygon (read back whole, never
-- queried into), and the opposite choice from marks' single flattened GeoPosition.
CREATE TABLE map_drawings (
    id              UUID PRIMARY KEY,
    layer_id        UUID         NOT NULL,
    kind            VARCHAR(16)  NOT NULL,
    label           VARCHAR(120),
    color_token     VARCHAR(30),
    points          JSONB        NOT NULL,
    owner_user_id   UUID         NOT NULL,
    group_id        UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_map_drawings_layer ON map_drawings (layer_id);

-- The COP layer this deployment's existing marks are backfilled onto, and the one
-- LayerResolver#copLayerId() will find instead of lazily creating another. Its ownership is the
-- system principal -- UUID(0,0)/UUID(0,1), the same pair LayerResolver.SYSTEM_USER_ID/
-- SYSTEM_GROUP_ID and DevPrincipal stamp -- because no real user owns the shared picture. The id is
-- fixed rather than random so this migration is deterministic and re-readable; ...0002 simply
-- follows the system user (...0000) and group (...0001).
INSERT INTO map_layers (id, name, kind, owner_user_id, group_id, created_at)
VALUES ('00000000-0000-0000-0000-000000000002',
        'Common picture',
        'COP',
        '00000000-0000-0000-0000-000000000000',
        '00000000-0000-0000-0000-000000000001',
        now())
ON CONFLICT (id) DO NOTHING;

-- Marks gain their layer, their affiliation and their review state.
ALTER TABLE marks ADD COLUMN layer_id           UUID;
ALTER TABLE marks ADD COLUMN affiliation        VARCHAR(16);
ALTER TABLE marks ADD COLUMN verification_state VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE marks ADD COLUMN verified_by        UUID;
ALTER TABLE marks ADD COLUMN verified_at        TIMESTAMPTZ;

-- Backfill, in the order the columns' NOT NULL constraints below require.
--
-- Affiliation is derived from the OLD kind, per docs/MAP-REWORK-PLAN.md §2.2's frozen mapping table
-- (TARGET->HOSTILE, HAZARD->UNKNOWN, POI->NEUTRAL, FRIENDLY->FRIENDLY), so this UPDATE must run
-- BEFORE the kind rename below -- once FRIENDLY has become UNIT the information is gone. The ELSE
-- branch is defensive only: no other value can exist, since the old MarkKind had exactly these four.
UPDATE marks SET layer_id = '00000000-0000-0000-0000-000000000002' WHERE layer_id IS NULL;

UPDATE marks
SET affiliation = CASE kind
                      WHEN 'TARGET'   THEN 'HOSTILE'
                      WHEN 'HAZARD'   THEN 'UNKNOWN'
                      WHEN 'POI'      THEN 'NEUTRAL'
                      WHEN 'FRIENDLY' THEN 'FRIENDLY'
                      ELSE 'UNKNOWN'
                  END
WHERE affiliation IS NULL;

-- The one renamed kind: "whose it is" moved out of MarkKind into Affiliation, so the old FRIENDLY
-- kind becomes UNIT ("what it is") with the FRIENDLY affiliation already stamped above.
UPDATE marks SET kind = 'UNIT' WHERE kind = 'FRIENDLY';

ALTER TABLE marks ALTER COLUMN layer_id    SET NOT NULL;
ALTER TABLE marks ALTER COLUMN affiliation SET NOT NULL;

CREATE INDEX idx_marks_layer ON marks (layer_id);
