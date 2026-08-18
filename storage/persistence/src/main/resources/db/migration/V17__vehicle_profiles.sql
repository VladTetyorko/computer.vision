-- docs/plans/active/DRONE-ONBOARDING-PLAN.md O5: one append-only row per PROBE observation (D5 --
-- VehicleProfile is its own append-only record, not folded into Asset.attributes, since it is
-- machine-observed and versioned per flight, not operator-authored -- see that decision's own
-- rationale in the plan). Keyed by device_id (assigned once the candidate is registered), mirroring
-- telemetry_samples' "key lives in the FK column, not a domain field" shape
-- (VehicleProfileRepositoryPort#save(DeviceId, VehicleProfile), same idiom as
-- TelemetryRepositoryPort#save(UsageId, Telemetry)).
--
-- capability_flags/messages/parameters are jsonb -- each row is only ever read back whole
-- (VehicleProfileRepositoryPort#findLatest returns the single newest VehicleProfile), never queried
-- into by individual message/parameter, same "jsonb over a normalized child table" convention as
-- detection_results/geofence_zones (see those tables' own migration comments).
CREATE TABLE vehicle_profiles (
    id                     UUID PRIMARY KEY,
    device_id              UUID NOT NULL,
    link_key               VARCHAR(255) NOT NULL,
    observed_at            TIMESTAMPTZ NOT NULL,
    sysid                  INTEGER,
    firmware               VARCHAR(32),
    firmware_version       VARCHAR(64),
    vehicle_kind           VARCHAR(64),
    capability_bitmask     BIGINT,
    capability_flags       JSONB NOT NULL DEFAULT '[]'::jsonb,
    messages               JSONB NOT NULL DEFAULT '[]'::jsonb,
    parameters             JSONB NOT NULL DEFAULT '[]'::jsonb,
    link_bytes_per_second  BIGINT,
    complete               BOOLEAN NOT NULL,
    incomplete_reason      VARCHAR(500)
);

-- findLatest(DeviceId) reads "the newest row for this device" -- index the FK together with the
-- ordering column, same shape as telemetry_samples'/detection_results' own (device/stream, time)
-- indexes.
CREATE INDEX idx_vehicle_profiles_device_observed ON vehicle_profiles (device_id, observed_at DESC);
