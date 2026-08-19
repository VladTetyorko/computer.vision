-- docs/plans/active/DRONE-ONBOARDING-PLAN.md O5: schema for AssetUsage.phase (D1/D2 -- AssetUsage
-- is extended with a phase, no parallel Flight entity; the phase enum/rule itself lives in
-- contexts/vision-flight's FlightPhase/FlightPhaseRule, already built by O3). Nullable, additive,
-- no backfill -- every existing row predates the phase concept and stays exactly as it is, same
-- "unknown, not fabricated" discipline as V6__telemetry_flight_state.sql's flight_state column.
--
-- UPDATE (still Wave O5, after O7 merged): O7 (contexts/vision-warehouse/vision-perception, a
-- concurrent worktree) landed AssetUsage's 9th component -- UsagePhase phase -- but did not add
-- firstArmedAt()/lastDisarmedAt() fields to the domain record (see UsagePhase's own javadoc: it
-- names only phase, not the other two). AssetUsageEntity/AssetUsageMapper are now wired for
-- `phase` (@Enumerated(EnumType.STRING) reusing UsagePhase directly, same convention as
-- GeofenceZoneEntity#kind), with a null column honestly defaulting to UsagePhase.PREFLIGHT via
-- AssetUsage's own pre-O7 convenience constructor -- see AssetUsageEntity/AssetUsageMapper's own
-- javadoc and PostgresDockerIntegrationTest.AssetUsageRepositoryTests' phase round-trip tests.
-- `first_armed_at`/`last_disarmed_at` remain unmapped: no domain field exists yet to map them to
-- or from, so wiring them stays deferred to whoever adds those two fields to AssetUsage next.
ALTER TABLE asset_usages
    ADD COLUMN phase             VARCHAR(20),
    ADD COLUMN first_armed_at    TIMESTAMPTZ,
    ADD COLUMN last_disarmed_at  TIMESTAMPTZ;
