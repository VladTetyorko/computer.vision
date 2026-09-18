-- docs/plans/active/LINK-PAIRING-PLAN.md §3.3 (L2): a vehicle's persisted identity, distinct from
-- the devices row it answers through. PairingRepositoryPort/PairingService/DefaultPairingService
-- (contexts/vision-warehouse) exist against no adapter yet -- this migration is that adapter's table.
--
-- No FK to devices (device_id) -- this schema's standing "no cross-entity foreign keys" convention
-- (storage/persistence MODULE.md Gotchas), same reasoning as discovery_candidates.registered_asset_id
-- (V31): a pairing must never fail to write because of an unrelated device row's state, and the
-- Device/Asset rows keep their own independent soft-delete lifecycle untouched by pairing/forget.
--
-- vehicle_key is exactly the 32 raw bytes VehicleKey wraps -- BYTEA, no encoding, same as
-- asset_images.data (V28) for image bytes; never selected by anything that logs its result. hardware_uid
-- is NUMERIC(20,0): AUTOPILOT_VERSION.uid is a uint64 (max ~1.8e19, 20 digits), decoded to a
-- BigInteger (drone-link/mavlink-core CapabilityReport, §3.6) -- nullable, "not yet read from a
-- capability probe" per Pairing's own javadoc. radio_bind_attributes is JSONB NOT NULL DEFAULT '{}',
-- same convention as discovery_candidates.details -- RadioBind's own compact constructor never lets
-- its map be null, only ever empty (RadioBind.NONE).
--
-- sysid is a MAVLink system id, 1-255 on the wire but never 1 (ArduPilot/rover factory default) or
-- 251-255 (GCS reserved) once *assigned* by PairingService.pair -- enforced in the application layer
-- (vision.pairing.sysid-range), not by a CHECK constraint here, so a future policy change never needs
-- a migration. The unique index below is the safety net that makes "at most one pairing per sysid"
-- hold across a restart or a second app instance, exactly the reasoning
-- uq_discovery_candidates_identity_key (V31) already applies to identity_key.
CREATE TABLE pairings (
    id                    UUID          PRIMARY KEY,
    device_id             UUID          NOT NULL,
    sysid                 INTEGER       NOT NULL,
    vehicle_key           BYTEA         NOT NULL,
    hardware_uid          NUMERIC(20,0),
    radio_bind_attributes JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ   NOT NULL,
    replaced_at           TIMESTAMPTZ
);

-- PairingRepositoryPort#findByDeviceId is the primary lookup (a pairing is a fact about the device,
-- §3.3's own frozen decision) and also enforces "at most one pairing per device" across restarts.
CREATE UNIQUE INDEX uq_pairings_device_id ON pairings (device_id);

-- PairingRepositoryPort#findBySysid backs every sysid-collision check DefaultPairingService.pair
-- makes before keeping a heard sysid or assigning a free one.
CREATE UNIQUE INDEX uq_pairings_sysid ON pairings (sysid);

-- Control-plane accountability: who paired/replaced-hardware/forgot which vehicle, and when -- the
-- same question db_audit_log answers for control_profiles (V24)/discovery_candidates (V31)/
-- maintenance_records (V28). Write volume is operator-paced (pairing is a one-time onboarding
-- action per device), nothing like a telemetry-shaped table.
CREATE TRIGGER trg_audit_pairings AFTER INSERT OR UPDATE OR DELETE ON pairings
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
