# adapter-persistence — wave history

`MODULE.md` is the contract (current state only, no dates, no test counts). This file is the
wave-by-wave narrative — read it only to learn *why*. Newest first.

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W7.2 — CvProfile becomes a patch

**CV-ORCHESTRATION wave W7.2 done** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, decision E22 —
"a profile is a patch"). `V36__cv_profile_patch.sql` (see the migration ledger in `MODULE.md`) +
`CvProfileEntity` widened to mirror `contexts/vision-perception`'s W7.0 domain rewrite: every knob but
identity/timestamps now nullable, a new nullable `intent` column, `tracking` retyped `TrackingKnobPatch`
(5 fields, not the 10-field `TrackingConfig` it held before). One real defect found and fixed, not just
a mechanical follow-through: pre-existing `tracking` jsonb rows failed to deserialize against the
narrower type (`UnrecognizedPropertyException` — see `MODULE.md`'s Gotchas for the fix, a scoped
Jackson mix-in registered in `PersistenceUnit#start`). `./mvnw -B -pl storage/persistence -am test
-DskipWeb` — **286** tests, `BUILD SUCCESS`, Docker ran (not skipped — real `postgres:16`, Flyway
migrated through `V36`).

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W2.1 — DetectionResult's 9th component

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` wave W2.1 (mechanical, folded into this module's file
scope by `DetectionResult`'s domain constructor growing a 9th component, `Optional<FrameLedger>
ledger`) touched exactly one call site here: `DetectionResultMapper#toDomain` now passes
`Optional.empty()` as the trailing argument — this mapper never round-trips a ledger (the warm trace
tier is never persisted, by design, `contexts/vision-perception/MODULE.md`'s own `FrameLedger`
bullet), so `Optional.empty()` is the permanently-correct value here, not a placeholder awaiting a
later wave (now stated as a standing fact in `MODULE.md`'s Conventions section). `PostgresDockerIntegrationTest`
needed the same mechanical update at its own `DetectionResult` construction sites. No schema change, no
new migration, no behavior change — noted here only because CLAUDE.md's module-docs rule calls for
every touched module's doc to reflect its own change, however small.

## docs/plans/active/ALWAYS-ON-FLOW-PLAN.md — wave B3 — event_history

**ALWAYS-ON-FLOW wave B3 done.** New `V35__event_history.sql` + `EventHistoryEntity`/
`mapper.EventHistoryMapper`/`repository.JpaEventHistory` implementing `vision-platform`'s new
`EventHistoryPort` — the durable home for platform `Event`s the notification bell/`/manage/system`
never had (own API-surface/ledger/retention entries in `MODULE.md`). `PostgresDockerIntegrationTest`
gained one `EventHistoryRepositoryTests` nested class (6 cases: round-trip including a null `streamId`,
round-trip with a real `StreamId`, `findRecent` newest-first across every type, `findSince`'s cursor
inclusivity, `findSince` with a null cursor applying no lower bound, and the table-wide retention
prune) plus `event_history` added to `EXCLUDED_TABLES`. One test-design defect found and fixed during
this wave, not a production defect: the first draft of three of those six cases assumed the table held
only that test's own rows (asserting on `findRecent`'s full result / its first element / an exact
`List.of(...)` equality) — wrong, since `entityManagerFactory` and therefore `event_history` are
shared static state across every `@Nested` class and test method in this one file, exactly the
constraint `AuditTrailRepositoryTests` already documents and defends against. Fixed by filtering
`findRecent`/`findSince` results down to each test's own inserted ids before asserting (the established
precedent) and, for the retention-prune case specifically, using timestamps far enough in the future
that this test's own rows are unambiguously the newest in the *entire* shared table regardless of
execution order — a per-key cap could tolerate sharing the table loosely, but a genuinely table-wide
cap cannot. (This pitfall is now a standing Gotcha in `MODULE.md`.)

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` — this
module: **283** tests (277 immediately before this wave, +6), `BUILD SUCCESS`, Docker ran (not skipped
— real Testcontainers `postgres:16`, Flyway migrated through `V35`). Full three-module command green;
see `station/vision-app/MODULE.md`'s own wave B3 section for the cross-module report (wiring,
retention/scoping reasoning, per-module before/after counts).

Test-count provenance (recorded at the top of `MODULE.md` as of this wave): 283 tests, up from 277
immediately before this wave (+6 for `EventHistoryRepositoryTests`); the branch this wave landed on had
already moved past the 276 recorded at AUTH-ROLES wave B5 via other concurrent work, so 277 — not 276 —
was this wave's own true baseline. Full chain across the waves below: 260 (CV-SETTINGS W3) → 267
(ZERO-CONFIG-ONBOARDING Z2c) → 269 (ASSET-FLOWS BK4) → 274 (COMMAND-MAP-FLOW B1) → 276 (AUTH-ROLES B3)
→ 277 (AUTH-ROLES B5 baseline reconfirmed) → 283 (this wave) → 286 (CV-ORCHESTRATION W7.2, above).
Count from Maven's own summary line, not by summing `target/surefire-reports/TEST-*.xml` — stale
reports from renamed/deleted test classes inflate that sum, and `PostgresDockerIntegrationTest`'s many
`@Nested` classes land in one aggregate XML that undercounts it (also a standing Gotcha in `MODULE.md`).

## docs/plans/active/AUTH-ROLES-PLAN.md — wave B5 — Spring Session JDBC

**AUTH-ROLES wave B5 done.** New `V34__spring_session.sql` (byte-for-byte official Spring Session JDBC
4.1.0 Postgres schema) so sessions survive an app restart — see `MODULE.md`'s ledger row and its
Bootstrap-and-connection-pool section for the shared-pool seam this wave added (`buildDataSource` now
public, new `start(DataSource, boolean)` overload). `PostgresDockerIntegrationTest`'s `EXCLUDED_TABLES`
gained `spring_session`/`spring_session_attributes` (this table's own infrastructure, same
classification as `flyway_schema_history`) — added proactively, before the live-schema
`DbAuditLogCoverageTests` test would otherwise have failed against the two new unclassified tables. No
entity, no mapper, no repository added — Spring Session's own `JdbcIndexedSessionRepository`
(vision-app) reads/writes these tables directly; this module only supplies the schema and the shared
connection pool.

`./mvnw -B -pl storage/persistence test` — **276** tests, unchanged from the AUTH-ROLES B3 baseline (no
new test method this wave — the migration and the `EXCLUDED_TABLES` fix are exercised by the
pre-existing `DbAuditLogCoverageTests` methods, both reconfirmed green against the live schema,
including `everyPublicBaseTableIsEitherAuditedOrExplicitlyExcluded` and
`everyAuditedTableCarriesExactlyTheAuditTriggerAndNoExcludedTableDoes`). `BUILD SUCCESS`, Docker ran
(not skipped — Testcontainers started a real `postgres:16`, Flyway migrated through `V34`, all 230
nested-class test methods inside `PostgresDockerIntegrationTest` executed and passed).

## docs/plans/active/AUTH-ROLES-PLAN.md — wave B3 — assignment roles + forced password change

**AUTH-ROLES wave B3 done.** `V33__assignment_roles.sql` adds `pilot_assignments.role` and
`users.must_change_password` (both additive, defaulted at the column level, no trigger changes).
`UserEntity`/`UserMapper` widened to round-trip `mustChangePassword`; `AssignmentEntity` gained
`role`, and `JpaAssignmentRepository` gained `roleFor(UserId, AssetId)` (most-recently-assigned seat)
and `assignmentsForAsset(AssetId)` (full roster with seats) — `AssignmentRepositoryPort`'s two new
read methods behind `GET /api/assets/{id}/pilots` and `GET /api/me/assignments`'s seat enrichment
(vision-api, same wave). `PostgresDockerIntegrationTest` gained two new `AssignmentRepositoryTests`
cases (`roleForReflectsTheMostRecentlyAssignedSeat`, `assignmentsForAssetListsEveryLinkWithItsSeat`)
plus mechanical fixes to every pre-existing `new User(...)`/`repository.assign(...)` call site for
the two widened constructors. 274 → 276 tests (Maven's own summary line); `BUILD SUCCESS`, Docker ran
(not skipped — Testcontainers started a real `postgres:16`, Flyway migrated through `V33`).

## docs/plans/active/COMMAND-MAP-FLOW-PLAN.md — wave B1 — findLatestByUsage (D1 fix)

**COMMAND-MAP-FLOW-PLAN.md B1 (D1 fix)** added `JpaTelemetryRepository#findLatestByUsage` — the
`/command` fleet map polls `GET /api/usages/{usageId}/telemetry` and treated the last element of
`findByUsage`'s earliest-first window as "latest position", so the map froze once a flight passed
`limit` samples. Additive only: `findByUsage`/`DefaultReplayService` untouched, `TelemetryRepositoryPort`
gained one method, `AssetController#telemetry` now calls it instead — same path, same param, same
DTO, same ascending order, same `200 []` on an unknown usage. `PostgresDockerIntegrationTest`'s
`TelemetryRepositoryTests` gained three cases (latest-window selection, ascending order preserved,
empty case). 271 → 274 tests (Maven's own summary line); `BUILD SUCCESS`, Docker ran (not skipped).
(The resulting method contract is now stated as a standing fact in `MODULE.md`'s API surface and
Gotchas.)

## docs/plans/active/ASSET-FLOWS-PLAN.md — wave BK4 (D1p) — pilot_id finally mapped

**ASSET-FLOWS wave BK4 (D1p) done.** `AssetUsageEntity#pilotId`/`AssetUsageMapper` now map the
`pilot_id` column `V28__asset_inventory.sql` added schema-only, closing `docs/plans/active/PLATFORM-AUDIT-DB.md`
gap #4/T4 (the 2026-08-21 audit's DB lane; umbrella verdict in `docs/plans/active/PLATFORM-AUDIT-FINDINGS.md`) ("every flight record is anonymous") on the persistence side. **No new migration** —
confirmed against the ground-truth Flyway slot ledger mid-wave (master's next free slot was `V33`,
not `V31`/`V32` as this wave's original brief assumed); moot for this wave regardless, since `V28`
already carried the column and nothing here needed a schema change. `PostgresDockerIntegrationTest`
gained two new `AssetUsageRepositoryTests` cases (`savedUsageWithAKnownPilotRoundTripsExactly`,
`savedUsageWithNoPilotRoundTripsAsNull`). 267 → 269 tests (Maven's own summary line); `BUILD SUCCESS`,
Docker ran (not skipped — Testcontainers started a real `postgres:16`).

## docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md — wave Z2c — discovery inbox

**ZERO-CONFIG-ONBOARDING wave Z2c done.** New `V31__discovery_inbox.sql` + `DiscoveryCandidateEntity`/
`mapper.DiscoveryCandidateMapper`/`repository.JpaDiscoveryCandidateRepository` implementing
`warehouse`'s `DiscoveryCandidateRepositoryPort` (built by an earlier, disjoint domain/application
wave in this same Z2c task — this module's write scope was persistence only). `PostgresDockerIntegrationTest`
gained one `DiscoveryCandidateRepositoryTests` nested class (7 cases: empty-lookup by id and by
identity key, a full stream round-trip, a no-stream round-trip proving the trio of stream columns is
genuinely absent rather than empty-string, the identity-key upsert path preserving the row's id across
a re-report, `DISMISSED`/`REGISTERED` status round-tripping, and newest-first ordering). 260 → 267
tests (Maven's own summary line); `BUILD SUCCESS`, Docker ran (not skipped — Testcontainers started a
real `postgres:16`, Flyway migrated through `V31`, every nested class in the table above executed).

## docs/plans/active/CV-SETTINGS-PLAN.md — wave W3 — profile/model/training-run registry

**CV-SETTINGS wave W3** added `V29`/`V30` and their three adapters (`JpaCvProfileRepository`,
`JpaCvModelRepository`, `JpaTrainingRunRepository`) implementing the perception/learning domain ports
committed by W1/W2. Two deliberate deviations from CV-SETTINGS-PLAN.md §5.3's literal column list, both
flagged in-line in the migration files themselves: `cv_profiles.model_id`/`model_version` (split from
one `model` column — `ModelRef` requires both non-blank) and `cv_profiles.event_rule` (a column §5.3
omitted entirely; without it a `CvProfile` could not round-trip) — both now stated as standing facts in
`MODULE.md`. `PostgresDockerIntegrationTest` gained three nested classes (`CvProfileRepositoryTests` 11
cases, `CvModelRepositoryTests` 6, `TrainingRunRepositoryTests` 4) plus two top-level
migration-verification tests — `v29MigrationSeedsFourBuiltInCvProfilesWithZeroBindings` reads the four
seeded rows back through the real adapter (not raw SQL) and asserts their `tracking`/`eventRule` jsonb
decodes byte-identical to `TrackingConfig.defaults()`/`EventRuleConfig.defaults()`, and that each ships
with zero bindings; `v30MigrationCreatesTheCvModelRegistryTablesOnTopOfV1ThroughV29` proves the
composite PK and every nullable provenance/metrics column via `information_schema`. 237 → 260 tests
(Maven's own summary line); `BUILD SUCCESS`, Docker ran (not skipped). See CV-SETTINGS-CONTEXT.md's
W3→W5 handoff for the adapter bean names and the four fixed built-in profile UUIDs vision-app's wiring
needs.

## docs/plans/active/WAREHOUSE-UX-PLAN.md — wave W8 — fleet-wide maintenance/flight-time queries

**WAREHOUSE-UX wave W8** added two new query methods against the existing `V28` schema — no new
migration, since `maintenance_records`/`asset_usages` already carried every column needed
(`closed_at`/`opened_at`, `asset_id`/`started_at`/`ended_at`). `PostgresDockerIntegrationTest` gained
a `MaintenanceRepositoryTests` nested class (previously untested against real Postgres) plus three
new `AssetUsageRepositoryTests` cases for `totalFlightSecondsByAsset` (closed-usage exact duration,
open-usage running-until-now, absent-asset no-entry).
