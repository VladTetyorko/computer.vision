-- docs/plans/active/DRONE-ONBOARDING-PLAN.md O11: the flight passport -- a VehicleProfile snapshot
-- taken at PREFLIGHT and again at POSTFLIGHT, attached to the AssetUsage it belongs to.
--
-- Both columns are nullable and additive on top of vehicle_profiles (V17), which stays append-only:
-- every save() still inserts a brand-new row (VehicleProfileRepositoryPort's own "never overwrites"
-- contract), this migration only widens what a row may optionally carry. A row saved through the
-- pre-O11 untagged save(DeviceId, VehicleProfile) -- readiness's own ad hoc probes, the
-- pre-registration candidate probe -- simply has usage_id/phase NULL, same "unknown, not fabricated"
-- discipline as V19's own additive columns.
--
-- phase is intentionally NOT constrained to PREFLIGHT/POSTFLIGHT at the schema level -- that
-- restriction is enforced once, in DefaultVehicleProfileService#captureSnapshot (the application
-- layer), the same place every other authority/shape rule in this plan lives (D9's own "the
-- allowlist is domain code, not a UI convention" precedent, applied to a schema instead of a screen).
ALTER TABLE vehicle_profiles
    ADD COLUMN usage_id UUID,
    ADD COLUMN phase    VARCHAR(20);

-- findByUsageAndPhase(UsageId, FlightPhase) reads "the newest tagged row for this usage+phase" --
-- index the two predicate columns together, same "index the FK together with the query's own
-- ordering/filter columns" convention as idx_vehicle_profiles_device_observed (V17) and
-- idx_telemetry_samples_usage_at (V3).
CREATE INDEX idx_vehicle_profiles_usage_phase ON vehicle_profiles (usage_id, phase, observed_at DESC);
