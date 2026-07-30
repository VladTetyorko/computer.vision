-- docs/U-SCOPE-PLAN.md, U-e slice 2, feature 2: the pilot->asset assignment roster ("this pilot
-- flies that aircraft"). Purely additive on top of V1-V8 -- one brand-new table, nothing else
-- changed.
--
-- The primary key is the composite (pilot_user_id, asset_id): the pair IS the identity of an
-- assignment (a plain join row, not an aggregate with its own synthetic id), and the PK doubles as
-- the uniqueness constraint that makes assign() an idempotent upsert with no duplicate rows
-- possible.
--
-- No foreign keys to users/assets, same convention as every other table in this schema (see
-- MODULE.md's Conventions): the in-memory reference repository does zero referential checks, and a
-- real constraint here would break parity for the "round-trip every port method the same way the
-- in-memory impl does" contract this module is judged against.
--
-- assigned_at is bookkeeping only (never surfaced through AssignmentRepositoryPort); the entity does
-- not map it, which Hibernate's hbm2ddl=validate tolerates (it validates mapped columns exist, not
-- the reverse).

CREATE TABLE pilot_assignments (
    pilot_user_id UUID        NOT NULL,
    asset_id      UUID        NOT NULL,
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pilot_user_id, asset_id)
);

-- pilotsForAsset(asset) queries by asset_id, which the composite PK's leading column (pilot_user_id)
-- does not serve; a secondary index keeps that direction indexed too. assetsForPilot(pilot) is
-- already served by the PK's leading column.
CREATE INDEX idx_pilot_assignments_asset ON pilot_assignments (asset_id);
