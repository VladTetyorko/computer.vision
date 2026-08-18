-- docs/plans/active/DRONE-ONBOARDING-PLAN.md O5: schema for AssetUsage.phase (D1/D2 -- AssetUsage
-- is extended with a phase, no parallel Flight entity; the phase enum/rule itself lives in
-- contexts/vision-flight's FlightPhase/FlightPhaseRule, already built by O3). Nullable, additive,
-- no backfill -- every existing row predates the phase concept and stays exactly as it is, same
-- "unknown, not fabricated" discipline as V6__telemetry_flight_state.sql's flight_state column.
--
-- Schema only, deliberately: AssetUsageEntity/AssetUsageMapper/JpaAssetUsageRepository are NOT
-- touched by this migration or by this wave. Wiring this column up needs the domain
-- AssetUsage#phase()/firstArmedAt()/lastDisarmedAt() fields, which O7 (contexts/vision-warehouse,
-- running concurrently in a separate worktree at the time this migration was written) owns per the
-- plan's own module-placement table (SS7) -- this wave's file scope is storage/persistence,
-- station/vision-api, station/vision-app only, and contexts/vision-warehouse is explicitly
-- out of bounds to avoid colliding with that concurrent work. The column exists ahead of its
-- reader/writer on purpose (additive, backward compatible, costs nothing idle) so O7 lands against
-- a schema that is already there instead of also needing a migration of its own.
ALTER TABLE asset_usages
    ADD COLUMN phase             VARCHAR(20),
    ADD COLUMN first_armed_at    TIMESTAMPTZ,
    ADD COLUMN last_disarmed_at  TIMESTAMPTZ;
