-- docs/plans/active/WAREHOUSE-UX-PLAN.md D7: the "dedicated migration task" the persistence
-- MODULE.md freeze on `assets`/`categories` was waiting for. One task, one file, per that freeze's
-- own instruction ("no V27+ without one").
--
-- No FKs anywhere below, per this module's standing convention (see storage/persistence MODULE.md
-- Gotchas) -- asset_id/custodian_id/opened_by/author columns below are plain UUIDs, the same shape
-- camera_poses.asset_id and projected_track_points.asset_id already use.

-- D1/D2: Identity (serial/make/model/registration) and Custody (custodian/location/since) fold onto
-- the asset row itself -- both are 1:1 with an asset, never queried independently of it. D6:
-- inventory_state stores only the three states an operator or manager actually sets by hand
-- (IN_STOCK/MAINTENANCE/RETIRED); ISSUED/IN_FIELD are derived at read time from custody/open-usage
-- (InventoryStates.effective) and never written here.
ALTER TABLE assets
    ADD COLUMN serial_number   VARCHAR(120),
    ADD COLUMN make            VARCHAR(120),
    ADD COLUMN model           VARCHAR(120),
    ADD COLUMN registration    VARCHAR(120),
    ADD COLUMN custodian_id    UUID,
    ADD COLUMN location        VARCHAR(255),
    ADD COLUMN custody_since   TIMESTAMPTZ,
    ADD COLUMN inventory_state VARCHAR(32)  NOT NULL DEFAULT 'IN_STOCK',
    ADD COLUMN created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ADD COLUMN updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now();

-- D8: the tail number used to live inside the free-form `attributes` bag under the key
-- `registrationNumber` (UX-REWORK-PLAN §U-d item 3, `core/fleet/asset-attributes.ts`); a few
-- early rows used `registration`. Promote either onto the first-class column (the canonical key
-- wins), then drop both keys so nobody reads a stale duplicate of `identity.registration`.
UPDATE assets
   SET registration = COALESCE(attributes ->> 'registrationNumber', attributes ->> 'registration')
 WHERE attributes ? 'registrationNumber' OR attributes ? 'registration';

UPDATE assets
   SET attributes = attributes - 'registrationNumber' - 'registration'
 WHERE attributes ? 'registrationNumber' OR attributes ? 'registration';

-- D4: DeviceCategory.connected -- true for every category that already exists (drones, cameras,
-- robots: Asset.devices stays non-empty for these, unchanged behavior), so the default keeps every
-- pre-W3 row meaning exactly what it always meant.
ALTER TABLE categories
    ADD COLUMN connected BOOLEAN NOT NULL DEFAULT TRUE;

-- D4's three passive (connected=false) categories -- equipment that is never wrapped around a
-- Device/StreamDescriptor (E7): a battery, a spare part, a radio. Top-level like drone/ip-camera/
-- robot; ON CONFLICT DO NOTHING so a server that already seeded these manually is left alone.
INSERT INTO categories (id, name, parent_id, attribute_hints, connected) VALUES
    ('battery', 'Battery', NULL, '["capacity-mah","chemistry","cycles"]'::jsonb, FALSE),
    ('spare', 'Spare part', NULL, '["part-type"]'::jsonb, FALSE),
    ('radio', 'Radio', NULL, '["frequency-mhz","protocol"]'::jsonb, FALSE)
ON CONFLICT (id) DO NOTHING;

-- MaintenanceRecord's 8 fields verbatim. kind/summary/flight_seconds_at mirror the domain record;
-- closed_at IS NULL is "open" (MaintenanceRecord#isOpen). Control-plane accountability history --
-- "who grounded this asset, and why" is exactly the question V21's audited set exists to answer --
-- so this table joins AUDITED_TABLES, not the telemetry-character excluded set.
CREATE TABLE maintenance_records (
    id                UUID         PRIMARY KEY,
    asset_id          UUID         NOT NULL,
    kind              VARCHAR(32)  NOT NULL,
    opened_at         TIMESTAMPTZ  NOT NULL,
    closed_at         TIMESTAMPTZ,
    opened_by         UUID         NOT NULL,
    summary           VARCHAR(2000),
    flight_seconds_at BIGINT
);

-- findByAsset/findOpenByAsset (MaintenanceRepositoryPort) both read "this asset's records,
-- newest-opened first" -- same (fk, ordering-column) shape as vehicle_profiles'/control_profiles'
-- own indexes. The partial index serves findOpenByAsset and the readiness blocker scan
-- (MaintenanceService#openBlockers) directly, without scanning closed history.
CREATE INDEX idx_maintenance_records_asset ON maintenance_records (asset_id, opened_at DESC);
CREATE INDEX idx_maintenance_records_open ON maintenance_records (asset_id) WHERE closed_at IS NULL;

CREATE TRIGGER trg_audit_maintenance_records AFTER INSERT OR UPDATE OR DELETE ON maintenance_records
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();

-- AssetNote's 5 fields verbatim -- append-only (AssetNoteRepositoryPort's own javadoc: "never
-- edited or deleted"), so no updated_at/closed_at. Crew commentary about an asset is the same
-- "operator-authored, low-volume, worth an audit trail" character as marks/map_drawings, not the
-- machine-output character of telemetry_samples/detection_results.
CREATE TABLE asset_notes (
    id       UUID        PRIMARY KEY,
    asset_id UUID        NOT NULL,
    author   UUID        NOT NULL,
    at       TIMESTAMPTZ NOT NULL,
    text     VARCHAR(4000) NOT NULL
);

CREATE INDEX idx_asset_notes_asset ON asset_notes (asset_id, at DESC);

CREATE TRIGGER trg_audit_asset_notes AFTER INSERT OR UPDATE OR DELETE ON asset_notes
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();

-- Bundled into this migration per the plan (D7) though unrelated to W2's own AssetUsage model
-- change: closes README §3 row 6. Schema-only for now, deliberately unmapped in AssetUsageEntity/
-- AssetUsageMapper until a domain field exists to carry it -- the same "column exists ahead of the
-- domain" precedent V19's first_armed_at/last_disarmed_at set.
ALTER TABLE asset_usages
    ADD COLUMN pilot_id UUID;
