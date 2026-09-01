# adapter-persistence

JPA/Postgres adapter implementing every repository port in the platform — the sole persistence
store. Postgres + Flyway is the **only** store: there is no in-memory fallback and no
`vision.persistence.enabled` toggle anywhere in the codebase.

**Depends on:** vision-kernel, vision-warehouse, vision-identity, vision-flight, vision-perception,
vision-map, vision-learning (the context modules whose ports it implements) · `org.hibernate.orm:hibernate-core`
· `com.zaxxer:HikariCP` (explicit compile scope — this module builds and owns its own pool, shared
with Flyway; `hibernate-hikaricp` is **not** a dependency, see Gotchas) · `org.postgresql:postgresql`
· `org.flywaydb:flyway-core`/`flyway-database-postgresql` · `tools.jackson.core:jackson-databind`
(Jackson 3, for jsonb columns). Test-only: `testcontainers-postgresql`/`testcontainers-junit-jupiter`,
`spring-security-crypto` + `spring-core` (BCrypt hash verification only — no runtime Spring dependency).
All version pins come from `spring-boot-dependencies` (this module's grandparent POM); no root-pom
`<dependencyManagement>` additions needed.

**Used by:** vision-app (`PersistenceWiringConfiguration`, unconditional).

**Build/test:** `./mvnw -B -pl storage/persistence test` — 267 tests (last measured, ZERO-CONFIG-ONBOARDING
Z2c; up from 260 before that wave — count from Maven's own summary line, see Gotchas), one shared `postgres:16`
Testcontainers container per test class. Requires a running Docker daemon — there is no non-Docker
path; tests skip cleanly (not fail) when Docker is unavailable. Use a **two-step** build when a
sibling context module is mid-flight elsewhere in the reactor: `./mvnw -B -pl
core/vision-kernel,core/vision-platform,contexts/vision-warehouse,contexts/vision-identity,contexts/vision-flight,contexts/vision-perception,contexts/vision-map,contexts/vision-events,contexts/vision-learning,contexts/vision-simulation
install -Dmaven.test.skip=true` (installs jars **without compiling their test sources**, so a
concurrent agent's broken test file elsewhere doesn't block this module — `-DskipTests` alone still
runs `testCompile` and is not enough) then `./mvnw -B -pl storage/persistence test` (no `-am`, so it
resolves the just-installed jars instead of recompiling upstream).

## API surface

Package layout: `repository/` (32 `Jpa*Repository`/`Jpa*Store` classes + `TelemetryBatchSettings`),
`mapper/` (30 mapper classes — one `toEntity`/`toDomain` pair per aggregate, `public static` methods
on a `public final class` with a private constructor), `config/` (`PersistenceUnit`, `JpaOperations`,
`PersistencePoolSettings`, `ClosingDatasourceConnectionProvider`), `entity/` (38 classes/records: 33
`@Entity` types, `AssignmentId`/`CvProfileBindingId`/`CvModelId` (`@IdClass`), `LayerGrantEmbeddable`
(`@Embeddable`), `DbAuditOperation` (plain enum)). No `controller/`, `dto/`, or `service/` package —
this module is a driven adapter only.

Every repository composes a `config.JpaOperations` (`write`/`read` transaction-boilerplate helper,
one constructor argument: the module's `EntityManagerFactory`) and calls its aggregate's `mapper`
class for entity↔domain conversion. Constructor is `(EntityManagerFactory)` unless noted.

### `repository` — port implemented, write semantics, one distinguishing note

| Class | Port | Note |
|---|---|---|
| `JpaCategoryRepository` | `CategoryRepositoryPort` | merge upsert, hard delete |
| `JpaDeviceRepository` | `DeviceRepositoryPort` | merge upsert, hard delete |
| `JpaAssetRepository` | `AssetRepositoryPort` | merge upsert, hard delete, `findByDeviceId`; `AssetMapper` flattens `Identity`/`Custody` onto the asset row directly (see WAREHOUSE-UX-PLAN.md D7, `V28`) |
| `JpaAssetUsageRepository` | `AssetUsageRepositoryPort` | merge upsert; `findByStream` is a deliberately unindexed scan (one-row-per-flight table, read once per stream lookup); `findRecent(int)` is the fleet-wide sibling of `findRecentByAsset`; `totalFlightSecondsByAsset()` (WAREHOUSE-UX W8) is this module's **first native SELECT-with-row-projection query** (every earlier native query was a batch `DELETE`/`executeUpdate`) — `em.createNativeQuery(...)` returning `List<Object[]>`, needed because plain JPQL cannot express `coalesce(ended_at, now())` |
| `JpaTelemetryRepository` | `TelemetryRepositoryPort` | always `persist` (append-only); prune-on-write retention (100k rows/usage default); optional batched-write mode via `TelemetryBatchSettings` (see Batching) — production wiring still uses the immediate-mode constructor; `findByUsage`'s `limit` returns the **earliest** samples, not the newest (see Gotchas) |
| `JpaDetectionRepository` | `DetectionRepositoryPort` | always `persist`; prune-on-write (100k rows/stream); `DetectionQuery#to` treated as inclusive despite the port's javadoc calling it exclusive (see Gotchas) |
| `JpaAssetImageRepository` | `AssetImageRepositoryPort` | keyed by `assetId` itself (no synthetic id — at most one image per asset); `data` plain `byte[]`/`bytea` |
| `JpaGeofenceRepository` | `GeofenceRepositoryPort` | merge upsert, hard delete; no soft-delete concept (`enabled=false` is just a column) |
| `JpaUserRepository` | `UserRepositoryPort` | merge upsert; `findByUsername` lower-cases the key then exact-matches the already-lower-cased stored value; `memberships` is jsonb |
| `JpaGroupRepository` | `GroupRepositoryPort` | merge upsert; `parentGroupId` a nullable UUID (null = root) |
| `JpaAssignmentRepository` | `AssignmentRepositoryPort` | composite-key (`pilot_user_id`,`asset_id`) `merge` upsert, idempotent unassign; **no mapper** — no domain aggregate to map to/from, only inline `UUID`↔id-wrapper conversions |
| `JpaMarkRepository` | `MarkRepositoryPort` | merge upsert, hard delete |
| `JpaMapLayerRepository` | `MapLayerRepositoryPort` | merge upsert; `save` **wholesale-replaces** the grant list (`map_layer_grants`); `deleteById` deliberately does **not** cascade to marks/drawings — that's `DefaultMapLayerService#delete`'s job (it must emit one `MapEvent` per removed row) |
| `JpaDrawingRepository` | `DrawingRepositoryPort` | merge upsert (a drawing mutates in place as its geometry is dragged), hard delete |
| `JpaDatasetRepository` | `DatasetRepositoryPort` | merge upsert, hard delete |
| `JpaTrainingSampleRepository` | `TrainingSampleRepositoryPort` | merge upsert (mutates over its review lifecycle); `findByDataset`/`countByDataset` share one JPQL shape with an optional status filter |
| `JpaSampleImageStore` | `SampleImageStorePort` | keyed by `sampleId`, same shape as `JpaAssetImageRepository` |
| `JpaAuditTrail` | `AuditTrailPort` | always `persist` (immutable historical facts); **no retention pruning** |
| `JpaDetectionEventRepository` | `DetectionEventRepositoryPort` | `merge` upsert (mutates over its own open lifetime — `lastSeen`/`peakConfidence` advance, then it closes); prune-on-write (100k rows/stream) |
| `JpaVehicleProfileRepository` | `VehicleProfileRepositoryPort` | always `persist` (append-only observation); `findLatest(DeviceId)` = newest row for that device; `save(DeviceId,UsageId,FlightPhase,VehicleProfile)`/`findByUsageAndPhase` tag a snapshot for the flight-passport read path |
| `JpaFeatureRequirementRepository` | `FeatureRequirementRepositoryPort` | **read-only** — the port has no `save`; every row is Flyway seed data |
| `JpaDbAuditLogRepository` | *(none)* | implements no port, deliberately — infrastructure, not a domain concept; read-only, every row written by a Postgres trigger, never by this class |
| `JpaCameraPoseRepository` | `CameraPoseRepositoryPort` | merge upsert by `assetId` (one calibrated pose per fixed asset); audited |
| `JpaTrackTrailRepository` | `TrackTrailRepositoryPort` | always `persist` (append-only breadcrumb trail); `trimToMostRecent`/`deleteOlderThan` are single bulk `DELETE`s, safe to call every tick; excluded from the audit log |
| `JpaTrackCorrectionRepository` | `TrackCorrectionRepositoryPort` | always `persist`; `deleteOlderThan`/`trimUsageToMostRecent` are bulk `DELETE`s, same shape as `JpaTrackTrailRepository`; excluded from the audit log |
| `JpaControlProfileRepository` | `ControlProfileRepositoryPort` | `merge` upsert; `activate` clears the owner's other active profiles for that vehicle kind then sets the flag, **in that order**, in one transaction — a partial unique index rejects the opposite order; `NoSuchElementException` for an unknown id **or** one belonging to another operator (deliberately indistinguishable, see Gotchas); audited |
| `JpaMaintenanceRepository` | `MaintenanceRepositoryPort` | `merge` upsert by `MaintenanceId` (the record mutates via `close()`, same shape as `JpaControlProfileRepository`); `MaintenanceRecordEntity#kind` reuses the domain `MaintenanceKind` enum directly; audited (WAREHOUSE-UX-PLAN.md D7/W3, `V28`); `findOpen()`/`findRecentlyClosed(limit)` (WAREHOUSE-UX W8) are `findOpenByAsset`/`findByAsset`'s fleet-wide counterparts — same `closed_at`/`opened_at` columns, no `asset_id` predicate, `GET /api/maintenance`'s backing queries |
| `JpaAssetNoteRepository` | `AssetNoteRepositoryPort` | always `persist` (append-only, same shape as `JpaAuditTrail`); no application service consumes this yet — wired ahead of a later wave's crew-notes UI; audited (WAREHOUSE-UX-PLAN.md D7/W3, `V28`) |
| `JpaCvProfileRepository` | `CvProfileRepositoryPort` | `merge` upsert for both `CvProfile` (by id) and `CvProfileBinding` (by composite `(scope_kind, scope_id)`); `findAllByGroup` needs no extra filtering to exclude built-ins — a built-in's `group_id` is always `NULL` and no `UUID` param ever matches `NULL` in JPQL; `delete`/`deleteBinding` real hard deletes, idempotent; audited (CV-SETTINGS-PLAN.md §5.3, `V29`) |
| `JpaCvModelRepository` | `CvModelRepositoryPort` | `merge` upsert on composite `(model_id, version)`; `findLive` does not itself enforce "exactly one LIVE row" — that invariant is `ModelRegistryService`'s job (W4-app); audited (CV-SETTINGS-PLAN.md §5.3, `V30`) |
| `JpaTrainingRunRepository` | `TrainingRunRepositoryPort` | `merge` upsert by `runId` (written once at start, again as `TrainingProgress` arrives); `findAll(limit)` orders newest-first by `started_at`, the column `idx_cv_training_runs_started_at` indexes; audited (CV-SETTINGS-PLAN.md §5.3, `V30`, fixes H7: "training metrics evaporate") |
| `JpaDiscoveryCandidateRepository` | `DiscoveryCandidateRepositoryPort` | `merge` upsert by `DiscoveryCandidateId` (a re-reported identity mutates `lastSeen`/`status` in place rather than inserting a new row); `findByIdentityKey` is the upsert-target lookup the inbox sweep uses every cycle — backed by the unique index on `identity_key`, not a table scan; `findAll` orders `lastSeen desc` (newest-reported first, the inbox's natural read order); audited (ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2c, `V31`) |

### `entity` — mapping conventions (not repeated per class)

- **jsonb columns hold domain records/enums directly** (Jackson 3, Hibernate's native
  `@JdbcTypeCode(SqlTypes.JSON)` + `FormatMapper`) — `Detection` lists, `FlightState`, `Membership`
  lists, `GeofenceZone#polygon`, `MapDrawing#points`, `ControlProfile`'s channel/action maps,
  `VehicleProfile`'s capability/message/parameter lists. No `AttributeConverter`, no `PGobject`.
- **Domain enums are reused directly** in `@Enumerated(EnumType.STRING)` fields rather than
  duplicated as adapter-local enums: `Capability`, `LifecycleState`, `ZoneKind`, `UsagePhase`,
  `UsageOrigin`, `DeviceOrigin`, `MarkKind`/`MarkStatus`/`MarkSource`, `DatasetStatus`,
  `DetectionEventState`, `CameraPoseSource`, `FlightPhase`, `InventoryState` (`AssetEntity`),
  `MaintenanceKind` (`MaintenanceRecordEntity`).
- **A single `GeoPosition` is flattened** to `latitude`/`longitude`/nullable `altitude_meters`
  columns (`Mark`, `CameraPose`, `TrackPoint`, `DetectionEvent#position`,
  `AssetUsage`'s start/last position) — the opposite of a `List<GeoPosition>`, which is stored whole
  as jsonb (`GeofenceZone#polygon`, `MapDrawing#points`).
- **`Ownership` is flattened** to `owner_id`/`group_id` columns (`Asset`, `Mark`, `Dataset`,
  `MapLayer`) rather than a separate table — there is no `OwnershipRepositoryPort`.
- **Id strategy varies by aggregate shape**: domain-owned id for real aggregates with identity
  (`User`, `Mark`, `Dataset`, `MapLayer`, `GeofenceZone`, `AuditEntry`, `DetectionEvent`,
  `ControlProfile`); a synthetic `UUID.randomUUID()` the adapter invents for append-only records with
  no domain identity (`Telemetry`, `DetectionResult`, `VehicleProfile`); the owning id doubling as the
  primary key for one-row-per-owner tables (`AssetImage` keyed by `assetId`, `CameraPose` keyed by
  `assetId`, `SampleImage` keyed by `sampleId`); and a database-generated `BIGINT GENERATED ALWAYS AS
  IDENTITY` for high-volume append-only rows never looked up by id (`DbAuditLogEntity`,
  `TrackPointEntity`, `TrackCorrectionEntity`).
- **`LayerGrantEmbeddable`** is the module's only persistence-local mirror of a domain record
  (`LayerGrant`) — a JPA `@Embeddable` must be a mutable, no-arg-constructor class, which a record
  cannot satisfy. Defines `equals`/`hashCode` (Hibernate needs them for element-collection change
  detection).
- **No cross-entity foreign keys**, with two exceptions: `categories.parent_id` (self-referencing)
  and `map_layer_grants.layer_id` (an owned `@ElementCollection`, Hibernate controls both sides). This
  traces back to parity with the now-deleted in-memory reference repositories, which performed zero
  referential checks; that parity target is gone, but the schema is frozen on this permissive shape
  regardless of why it was first chosen (no `V27+` without a dedicated task).
- **`AssetUsageEntity#phase`** (nullable, `V19`) falls back to `UsagePhase.PREFLIGHT` on `toDomain`
  only for a genuinely `null` column (a pre-`V19` row); **`AssetUsageEntity#origin`** (`V26`) is
  `NOT NULL` with a database default, so `AssetUsageMapper` has no legacy-null case for it at all.
- **`AssetEntity` flattens `Identity`/`Custody` onto its own columns** (`V28`) rather than nesting a
  jsonb blob — `serialNumber`/`make`/`model`/`registration`/`custodianId`/`location`/`custodySince`
  are plain nullable columns, `inventoryState` is `@Enumerated(EnumType.STRING)`, and
  `createdAt`/`updatedAt` are plain `Instant` columns; `AssetMapper#toEntity`/`#toDomain` do the
  flatten/reconstruct in both directions. `MaintenanceRecordEntity`/`AssetNoteEntity` keep their own
  domain id (`MaintenanceId`/`NoteId`) as primary key, same shape as every other domain-owned-id
  aggregate above — no synthetic id was invented for either.
- **`CvProfileEntity#model` is split into `modelId`/`modelVersion` columns** (`V29`), not the plan's
  single `model` column — `ModelRef`'s compact constructor requires both `id` and `version` non-blank,
  which cannot round-trip from one string alone without inventing an `"id@version"` encoding this
  schema uses nowhere else; flagged as a deliberate deviation in the migration's own header comment
  and in CV-SETTINGS-CONTEXT.md's W3 handoff. `CvProfileEntity#tracking`/`#eventRule` are each the
  whole `TrackingConfig`/`EventRuleConfig` record as jsonb (same "read back whole" convention as
  `ControlProfile`'s channel/action maps) — `event_rule` is itself a deviation, a column `V29` adds
  that CV-SETTINGS-PLAN.md §5.3's own column list omitted (the domain `CvProfile` carries an
  `EventRuleConfig`; without the column a profile could not round-trip through the port at all).
  `CvProfileBindingEntity` uses `CvProfileBindingId` (`@IdClass`, `scopeKind`/`scopeId` — `scopeKind`
  stored as `.name()`/`valueOf()` rather than `@Enumerated`, since it is part of the composite key, not
  a plain column).
- **`CvModelEntity` decomposes `ModelProvenance` into five flat, independently-nullable columns**
  (`dataset_id`/`training_run_id`/`base_model`/`epochs`/`trained_at`, `V30`) rather than nesting it as
  one jsonb object — every field really is independently nullable at the domain level (a
  hand-registered model may name a `baseModel` without a tracked `TrainingRunId`), which five plain
  columns represent exactly and a jsonb blob adds no real query capability over (CV-SETTINGS-CONTEXT.md
  W4-domain handoff). `metrics` (the whole `ModelMetrics` record) stays one nullable jsonb column
  instead — "no metrics reported yet" is a genuinely absent object, not several independently-nullable
  fields. `taskType`/`runtime`/`status` are `@Enumerated(EnumType.STRING)` reusing `ModelTaskType`/
  `ModelRuntime`/`ModelStatus` directly. `CvModelId` (`@IdClass`) is `(modelId, version)`, both plain
  `String`s — matching `ModelRef` staying a two-string pair rather than a typed id.
- **`TrainingRunEntity`** keeps its own domain id (`runId`) as primary key, same shape as every other
  domain-owned-id aggregate; `state` is `@Enumerated(EnumType.STRING)` reusing `JobState` directly.
- **`DiscoveryCandidateEntity` flattens `DiscoveredDevice#suggestedStream` (a `StreamDescriptor`) into
  three columns** (`suggested_stream_protocol`/`suggested_stream_uri`/`suggested_stream_options`, `V31`)
  rather than nesting it as jsonb — same reasoning as `AssetEntity`'s `Identity`/`Custody` flatten: the
  fields are independently meaningful (a candidate can be nameable/categorizable with no stream at all,
  the `Optional<StreamDescriptor>` case), and `suggested_stream_options` is itself the one jsonb column
  in the trio (a `Map<String,String>`, same `@JdbcTypeCode(SqlTypes.JSON)` convention as every other
  map-typed column in this module) — carrying the **full** options map is the module's one hard
  requirement here (ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2c: vision-api's `DiscoveryCandidateResponse`
  must not repeat `DiscoveredDeviceResponse`'s documented options-drop defect, so nothing between the
  domain record and the wire may drop it either). `details` (`Map<String,String>`, free-form
  protocol-specific facts) is `NOT NULL DEFAULT '{}'` rather than nullable — `DiscoveredDevice#details`
  is always a real (possibly empty) map, never absent, so there is no null case to represent.
  `status` is `@Enumerated(EnumType.STRING)` reusing `CandidateStatus` directly. `identity_key` is a
  plain unique-indexed `String` column, not a synthesized id — the port's `findByIdentityKey` is the
  sweep's upsert-target lookup, called once per discovered device every cycle.
- **`ControlProfileEntity` gained `stickMode`/`forwardIsUp`** (`V32`, CONTROLLER-SETUP-CONTEXT.md wave
  C15 — the owner's `TransmitterView`) as two plain scalar columns rather than folding them into the
  existing `channelMap`/`actionMap` jsonb, since they are two fixed, range-checked fields (`stickMode`
  1-4) rather than an open document; `NOT NULL` with defaults matching `TransmitterView.DEFAULT`
  (mode 2, forward-up), the same "every existing row already has an arrangement" reasoning
  `AssetUsageEntity#origin` (`V26`) uses.

## Schema (`src/main/resources/db/migration`) — migration ledger, V1 through V32

| Migration | What it does |
|---|---|
| `V1__baseline.sql` | `categories`, `devices`, `device_capabilities`, `assets`, `asset_devices` |
| `V2__seed_categories.sql` | seeds the default category set |
| `V3__history.sql` | `asset_usages`, `telemetry_samples`, `detection_results` |
| `V4__usage_stream_id.sql` | `asset_usages.stream_id` (nullable UUID, no backfill) |
| `V5__asset_images.sql` | `asset_images` table (keyed by `asset_id`) |
| `V6__telemetry_flight_state.sql` | `telemetry_samples.flight_state` (nullable jsonb) |
| `V7__geofence_zones.sql` | `geofence_zones` table |
| `V8__users_groups.sql` | `groups`, `users` (memberships as jsonb) |
| `V9__pilot_assignments.sql` | `pilot_assignments` (composite PK) |
| `V10__marks.sql` | `marks` table |
| `V11__training_datasets.sql` | `datasets`, `training_samples`, `sample_images` |
| `V12__map_layers.sql` | `map_layers`, `map_layer_grants`, `map_drawings`; grafts `layer_id`/`affiliation`/`verification_*` onto `marks`; seeds the fixed-id COP layer |
| `V13__identity_baseline.sql` | seeds the root group at the fixed id `DevPrincipal.GROUP_ID` uses |
| `V14__audit_trail.sql` | `audit_entries` table (`AuditTrailPort`) |
| `V15__detection_events.sql` | `detection_events` table (`DetectionEventRepositoryPort`) |
| `V16__adopt_fixed_root.sql` | data-only PL/pgSQL fix: reparents a pre-`V13` random-id root group under the fixed one, for databases upgraded from before `V13` existed |
| `V17__vehicle_profiles.sql` | `vehicle_profiles` table |
| `V18__feature_requirements.sql` | `feature_requirements` table; seeds 11 `firmware='ardupilot'` rows |
| `V19__asset_usage_phase.sql` | `asset_usages.phase`/`first_armed_at`/`last_disarmed_at` (nullable) — only `phase` is mapped by any entity today |
| `V20__vehicle_profile_usage_link.sql` | `vehicle_profiles.usage_id`/`phase` (nullable) — the flight-passport tagged-snapshot link |
| `V21__db_audit_log.sql` | `db_audit_log` table + `audit_row_change()` trigger function + 17 per-table triggers (see Database change audit) |
| `V22__fixed_camera_geo.sql` | `camera_poses` (audited), `projected_track_points` (excluded) |
| `V23__track_corrections.sql` | `track_corrections` table (excluded); widened in place with `cell_calibrated`/`sequence_converged` before it ever shipped to a deployed database |
| `V24__control_profiles.sql` | `control_profiles` table (audited) + partial unique index enforcing at most one active profile per owner+vehicle-kind |
| `V25__device_origin.sql` | `devices.origin` (`LIVE`/`SIMULATED`, `NOT NULL DEFAULT 'LIVE'`), backfilling `SIMULATED` for devices already on a `simulated`-category asset |
| `V26__asset_usage_origin.sql` | `asset_usages.origin` (`STREAM`/`TELEMETRY`/`OPERATOR`, `NOT NULL DEFAULT 'STREAM'`) — single statement, no follow-up `UPDATE` needed since every pre-existing row's correct value is the same one |
| `V27__rc_relay_readiness.sql` | `feature_requirements.required_parameter_value`/`forbidden_parameter_bits` (nullable); retires the single always-trivially-satisfied `id='ardupilot:rc-relay'` placeholder row V18 seeded and replaces it with two independent, value-aware rows under the same `rc-relay` feature key — a GCS-sysid value check (`SYSID_MYGCS` must equal `255`) and an `RC_OPTIONS` forbidden-bits check (bit 1 must be clear) — net row count for `firmware='ardupilot'` goes from 11 to 12 (FLEET-RADIO-PLAN.md R6) |
| `V28__asset_inventory.sql` | `assets` += `serial_number`/`make`/`model`/`registration` (from `Identity`), `custodian_id`/`location`/`custody_since` (from `Custody`), `inventory_state` (stored values only — `IN_STOCK`/`MAINTENANCE`/`RETIRED`, default `IN_STOCK`), `created_at`/`updated_at`; backfills `registration` from the pre-existing `attributes->>'registration'` key then removes that key (WAREHOUSE-UX-PLAN.md D8); `categories` += `connected BOOLEAN NOT NULL DEFAULT TRUE`, seeding three passive categories (`battery`/`spare`/`radio`) with `connected=false`; new `maintenance_records` (audited) and `asset_notes` (audited) tables; `asset_usages.pilot_id` (nullable UUID, schema-only — no domain field maps it yet, same status as `first_armed_at`/`last_disarmed_at`) |
| `V29__cv_profiles.sql` | `cv_profiles` (audited) + `cv_profile_bindings` (audited, composite PK `(scope_kind, scope_id)`); seeds the four built-in profiles (`people-vehicles`/`wide-search`/`military-vehicles`/`video-only`, fixed ids) with `built_in=true`/`group_id=NULL` and `tracking`/`event_rule` byte-identical to `TrackingConfig.defaults()`/`EventRuleConfig.defaults()`; **zero bindings seeded** (CV-SETTINGS-PLAN.md §3.1 rule 3 — no feature flag, every existing stream keeps resolving to `PipelineConfig.defaults()`); two deviations from §5.3's literal column list, both flagged in the migration's own header: `model_id`/`model_version` split (not one `model` column) and an added `event_rule` column (missing from §5.3 entirely) |
| `V30__cv_model_registry.sql` | `cv_models` (audited, composite PK `(model_id, version)`, every provenance column + `metrics` nullable) + `cv_training_runs` (audited, `idx_cv_training_runs_started_at` for `findAll(limit)`'s newest-first order); no seed rows — the config-seeded model roster merges with the worker's live `ListModels` response at the application layer, not baked into this schema (fixes H4/H7) |
| `V31__discovery_inbox.sql` | `discovery_candidates` (audited): domain-owned `id` PK, `identity_key` (the mDNS/ONVIF/MAVLink-derived stable key `DiscoveryCandidate` upserts on), `method`/`name`/`address`, flattened `suggested_category`/`suggested_stream_protocol`/`suggested_stream_uri`/`suggested_stream_options` (nullable as a group — a candidate with no offered stream), `details` jsonb `NOT NULL DEFAULT '{}'`, `first_seen`/`last_seen` `TIMESTAMPTZ`, `status`, nullable `registered_asset_id` (no FK — the same "no cross-aggregate FK" posture every other table in this schema takes, see Conventions); unique index on `identity_key` (the upsert target) + index on `last_seen` (the inbox list's sort column) |
| `V32__control_profile_transmitter_view.sql` | `control_profiles` += `stick_mode SMALLINT NOT NULL DEFAULT 2`, `forward_is_up BOOLEAN NOT NULL DEFAULT TRUE`, `ck_control_profiles_stick_mode CHECK (stick_mode BETWEEN 1 AND 4)` — how the owner's transmitter is arranged (CONTROLLER-SETUP-CONTEXT.md wave C15); defaults rather than nullable since every existing row already has an arrangement (the platform's); **renumbered from the branch's own `V25` during the `feat/controller-setup-c15` merge** — see the Gotchas entry below for the collision this replaced |

A second, conditional Flyway location, `src/main/resources/db/seed/dev`, holds
`V90001__dev_accounts.sql` (the `admin`/`manager`/`pilot` DEV-ONLY accounts) — it only joins Flyway's
`locations` when `PersistenceUnit.start`'s `seedDevUsers` argument is `true`. Version `90001` is a
deliberately reserved high band (always the highest resolved version, so it always applies next
regardless of how far `db/migration` has moved) — not "next free slot after V13".

## Database change audit

A Postgres-trigger-based record of every row change (`db_audit_log`, `V21`) — distinct from
`AuditTrailPort`/`audit_entries` (`V14`, domain-intent, written only where application code
remembers to call it) and never to be merged with it. One generic PL/pgSQL function,
`audit_row_change()`, attached per-table via `AFTER INSERT OR UPDATE OR DELETE ... FOR EACH ROW`;
it resolves each table's primary key from the catalog so one function covers both composite-key
join tables and single-UUID-PK tables.

**Secrets are redacted before the row image is written** — `password_hash` becomes `"[redacted]"`;
`changed_columns` is computed *before* redaction so a password change still shows as a change without
storing either hash. **Any future migration that adds a secret-bearing column to an audited table
must add that column name to the trigger's redaction list by hand — nothing enforces this
automatically.**

- **Audited**: `categories`, `devices`, `device_capabilities`, `assets`, `asset_devices`,
  `asset_usages`, `geofence_zones`, `groups`, `users`, `pilot_assignments`, `marks`, `datasets`,
  `map_layers`, `map_layer_grants`, `map_drawings`, `vehicle_profiles`, `feature_requirements`,
  `camera_poses`, `control_profiles`, `maintenance_records`, `asset_notes`, `cv_profiles`,
  `cv_profile_bindings`, `cv_models`, `cv_training_runs`, `discovery_candidates`. The four before last
  (`V29`/`V30`) join the audited set on the same "control-plane accountability" reasoning as
  `control_profiles`/`datasets` — `cv_profile_bindings` is a security/routing decision over a join row,
  the same reasoning that already put `pilot_assignments`/`map_layer_grants` in the audited set despite
  both being plain join tables too; `cv_training_runs` updates more often than most audited tables
  (roughly once per epoch) but its write volume is bounded by a job's epoch count, not the per-frame/
  per-sample character of the excluded set below. `discovery_candidates` (`V31`) joins for the same
  reason as `maintenance_records`/`asset_notes`: `register`/`dismiss` are operator decisions over
  auto-discovered hardware, and the audit trail is the record of who acted on which candidate — its
  write volume (one row per re-reported device per sweep, deduplicated by `identity_key`'s unique
  index) is bounded by the fleet's own device count, not per-frame/per-sample.
- **Excluded** (high-volume append-only, or a trigger would be actively wrong): `telemetry_samples`,
  `detection_results`, `detection_events`, `training_samples`, `sample_images`, `asset_images` (a
  `bytea` column would duplicate image bytes into every audit row), `audit_entries` (auditing an
  audit trail buys nothing), `db_audit_log` itself (would recurse), `flyway_schema_history`,
  `projected_track_points`, `track_corrections`.

Coverage is tested against the **live schema** (`information_schema.tables`/`pg_trigger`), not
trusted from a migration comment — `DbAuditLogCoverageTests` fails if a new table is added without
being classified into one of the two lists above. No `vision.persistence.*` flag governs any of
this (trigger installation is schema, not application config) and there is **no retention/purge
job** — `db_audit_log` grows unbounded, an explicitly open item, the same accepted tradeoff
`audit_entries` already makes.

## Bootstrap and connection pool

`PersistenceUnit.start` uses Hibernate's **native** bootstrap API
(`org.hibernate.cfg.Configuration#buildSessionFactory()`, which directly implements
`jakarta.persistence.EntityManagerFactory`) rather than JPA's `Persistence.createEntityManagerFactory`
or Spring Data JPA — every repository is a plain class constructed via `new` in vision-app's wiring,
never Spring-component-scanned, so Spring Data's proxy-generation model would have forced
`@EnableJpaRepositories` and its classpath-triggered autoconfiguration into the picture for no
benefit.

`start` builds one `HikariDataSource` (sized from `config.PersistencePoolSettings` —
`maximumPoolSize`/`minimumIdle`/`connectionTimeoutMillis`/`leakDetectionThresholdMillis`,
`defaults()` = `20`/`5`/`30_000`/`30_000`) and **shares it between Flyway and Hibernate**: Flyway
migrates through it first, then it is handed to Hibernate as the live `hibernate.connection.datasource`
object with `hibernate.connection.provider_class` = `ClosingDatasourceConnectionProvider` (a thin
subclass of `DatasourceConnectionProviderImpl` that also closes the pool when the
`EntityManagerFactory` closes). This is deliberately **not** Hibernate's own `HikariCPConnectionProvider`
(from `hibernate-hikaricp`) — that class always builds a second, independent `HikariDataSource`
internally, which cannot be handed to Flyway before the `EntityManagerFactory` exists; `hibernate-hikaricp`
is not even a declared dependency of this module. `hibernate.hbm2ddl.auto=validate` — Flyway owns all
schema creation/evolution, Hibernate only validates its mapping matches.

`PersistencePoolSettings`/telemetry batching (below) are constructor-argument opt-ins the module
defines and validates but does not read from Spring config itself — binding `vision.persistence.pool.*`
to a non-default `PersistencePoolSettings` is vision-app's job; today its wiring uses the argument-defaulted
overload.

## Conventions

- **`save()` is upsert-by-id** (`EntityManager#merge`) for aggregate-style ports (categories, devices,
  assets, usages, marks, layers, drawings, datasets, samples, users, groups, control profiles, camera
  poses, CV profiles/bindings, CV models by composite key, training runs by `runId`). **Append-only
  history tables always `persist`** a brand-new row instead — telemetry,
  detection results, audit entries, vehicle profiles, track points, track corrections — since none of
  those domain types carries an id to merge by. `DetectionEvent` is the one history-shaped table that
  `merge`s: unlike a `DetectionResult`/`AuditEntry`, it mutates over its own open lifetime.
- **`deleteById()` is a real hard delete, idempotent** (missing id ⇒ no-op) wherever a port exposes
  one — soft-delete states (e.g. `LifecycleState.DELETED`) are just column values round-tripped like
  any other field.
- **Tracks ride the existing `detection_results.detections` jsonb** — there is no `tracks` table and
  no dedicated migration. `Detection#track` is a nullable component of the record tree already stored
  whole, so it round-trips for free, but is **not SQL-queryable**: you cannot ask "where was track
  #7" without scanning and deserializing. Pre-tracking rows read back with `track == null`.
  `DetectionResult#tracking()` (per-frame duty-cycle telemetry, as opposed to per-detection
  `TrackRef`) has no column and deliberately does not persist.

## Retention and batching

`JpaTelemetryRepository`, `JpaDetectionRepository`, and `JpaDetectionEventRepository` each prune
their oldest rows **on every write**, inside the same transaction as the insert: `persist`/`merge` →
explicit `flush()` → a native `DELETE ... WHERE <key> NOT IN (... ORDER BY <timestamp> DESC LIMIT
<cap>)`. Grouping key is `usage_id` for telemetry, `stream_id` for detections/detection events;
default cap is 100,000 rows for each. The `flush()` is required — without it the delete would run
against the pre-insert view of the table and could prune the row just appended. `JpaAuditTrail` and
`JpaAssetUsageRepository` have no pruning at all (immutable facts / low write volume respectively).
Retention caps are constructor arguments, not yet bound to a `vision.persistence.*` Spring property.

`repository.TelemetryBatchSettings(int batchSizeSamples, long batchWindowMillis)` governs an optional
batched-write mode for `JpaTelemetryRepository`: **immediate mode** (`immediate()` → `(1, 0)`, the
one/two-argument constructors' implicit default, and what vision-app's wiring actually uses today)
reproduces the pre-batching behavior exactly. **Batched mode** buffers samples per `usage_id` in a
`ConcurrentHashMap`, flushing when `batchSizeSamples` (default 100) accumulate or `batchWindowMillis`
(default 200ms) elapse, whichever first — both flush paths *evict* their map entry so the buffer
cannot grow unbounded per usage over the JVM's life. A crash loses at most one window's worth of
buffered samples per open usage; `DEFAULT_BATCH_WINDOW_MILLIS` is non-zero on purpose (CLAUDE.md rule
9 — newest data wins over zero-loss-by-default), and `0` remains a fully supported zero-loss opt-out.

## Gotchas

- **`org.testcontainers.postgresql.PostgreSQLContainer` (Testcontainers 2.x) is a concrete,
  non-generic class** — `new PostgreSQLContainer("postgres:16")`, no diamond, unlike Testcontainers
  1.x's `PostgreSQLContainer<SELF>`.
- **Testcontainers 2.x renamed its per-database Maven artifacts** with a `testcontainers-` prefix
  (`org.testcontainers:testcontainers-postgresql`, `testcontainers-junit-jupiter`) — the core
  `org.testcontainers:testcontainers` artifact (`GenericContainer`/`DockerClientFactory`) was **not**
  renamed.
- **`JpaOperations` opens a fresh `EntityManager` per `write`/`read` call.** The connection pool
  bounds this cost (concurrent calls capped at `PersistencePoolSettings.maximumPoolSize()`), it does
  not remove it — there is no request-scoped or thread-bound `EntityManager`, because there is no
  Spring/servlet request here to scope one to.
- **`EntityManager#getTransaction().begin()` eagerly acquires the physical JDBC connection** — it
  does not defer to the first query. Relevant to any pool-exhaustion test written against this
  module: the `HibernateException` fires from `begin()`, not from the subsequent query.
- **A native query's `?N` positional parameter is supplied once per distinct index, not once per
  occurrence in the SQL text** — Hibernate's native-query binder resolves every occurrence of a given
  index from one `setParameter` call, unlike raw JDBC `?` placeholders.
- **`EntityManager#setParameter(int, UUID)` on a native query binds correctly as `uuid`** with no
  `stringtype=unspecified`/`PGobject` trick needed — Hibernate infers the JDBC type from the Java
  parameter's runtime class even for opaque native SQL.
- **`spring-security-crypto` (test scope) needs `spring-core` added explicitly too** — otherwise
  `new BCryptPasswordEncoder()` throws `NoClassDefFoundError: org/apache/commons/logging/LogFactory`.
  Neither creates a runtime Spring dependency; both are test-scoped and unused by production code.
- **An applied Flyway migration is frozen byte-for-byte, comments included** (CRC32 checksum, no
  whitespace/comment tolerance) — a repo-wide text rewrite that touches an already-shipped migration
  file breaks every database that ran it with `FlywayValidateException: Migration checksum mismatch`,
  even though the schema itself is untouched. This has already happened once (a doc-path reorg
  rewrote citations inside several migration files and had to be reverted). Migration comments citing
  `docs/plans/active/...` paths that have since moved to `docs/plans/done/` are deliberately left
  stale — a migration comment is a historical record, not fixed after the fact; find the plan through
  `docs/plans/README.md` instead.
- **Stale compiled Flyway resources on `target/classes` produce phantom/duplicate migrations that
  don't exist in the working tree.** This has bitten twice: an abandoned `V13.1__dev_accounts.sql`
  once caused an out-of-order failure until a `mvn clean`; and, as anticipated here ahead of time, the
  branch `feat/controller-setup-c15` also claimed version 25 (`V25__control_profile_transmitter_view.sql`,
  vs. this tree's own `V25__device_origin.sql`) — resolved at merge time by renumbering the branch's
  file to `V32__control_profile_transmitter_view.sql`, the next free slot once `V31__discovery_inbox.sql`
  is accounted for (see the ledger above). A stale copy of the old `V25` name left in `target/classes`
  from a pre-merge build of that branch will still make this module fail with `FlywayException: Found
  more than one migration with version 25` against a working tree that no longer contains such a file
  — `mvn clean` is the fix.
- **`JpaTelemetryRepository#findByUsage`'s `limit` returns the earliest samples, not the most
  recent** — a caller expecting "newest N" gets the flight's first N seconds instead.
- **`DetectionQuery#to` is treated as inclusive** by both the query and `JpaDetectionRepository`,
  despite the port's own javadoc calling it exclusive.
- **Count this module's tests from Maven's own summary line, not by summing
  `target/surefire-reports/TEST-*.xml`** — stale reports from renamed/deleted test classes inflate
  that sum, and `PostgresDockerIntegrationTest`'s many `@Nested` classes land in one aggregate XML
  that undercounts it.
- **`JpaControlProfileRepository#activate`/`#findById` throw the same `NoSuchElementException` for an
  unknown id and for an id belonging to another operator** — deliberately indistinguishable from
  outside (enumeration resistance).
- **`MapLayerRepositoryPort`/`DrawingRepositoryPort` have no `findByLayer`** — every caller filters
  `findAll()` in the application layer today; `idx_map_drawings_layer`/`idx_marks_layer` already
  exist for when that stops scaling.
- **"Exactly one COP map layer" is enforced by `LayerResolver`'s synchronized find-or-create plus a
  fixed-id migration seed, not by a schema constraint** — `map_layers` has no partial unique index
  for it.
- **A domain factory that stamps `Instant.now()` (e.g. `Asset.register`'s `createdAt`/`updatedAt`)
  round-trips lossy through `TIMESTAMPTZ`.** Postgres keeps microsecond precision and *rounds* — not
  truncates — on the way in, so a value ending e.g. `.xxx614510` can come back as `.xxx615000`; a raw
  `assertEquals` on the whole record is flaky. `PostgresDockerIntegrationTest` handles the pre-existing,
  test-supplied case by building the input `Instant` already truncated to millis (`NOW`); for a
  factory-internal timestamp the test cannot supply, it instead truncates **both sides** to millis right
  before comparing (`assertAssetRoundTrips`/`millisTruncated`) — same fix, applied after the fact instead
  of before.

## Status

Fully implements every repository port the platform currently defines (32 `Jpa*Repository`/`Jpa*Store`
classes; see API surface) against a schema migrated through `V32` (`feat/controller-setup-c15`'s
`stickMode`/`forwardIsUp` columns, reconciled here as `V32__control_profile_transmitter_view.sql` —
see the ledger and Gotchas above for the renumbering). Wired into vision-app unconditionally via
`PersistenceWiringConfiguration` — Postgres is the only store.

Open items, all deliberate rather than oversights:
- Connection pooling and telemetry write batching both exist but are constructor-argument opt-ins
  not yet bound to a `vision.persistence.pool.*`/`vision.persistence.telemetry.*` Spring property —
  vision-app's wiring still uses each class's default (pooled connections; immediate-mode writes).
- No retention/purge job for `db_audit_log` or `audit_entries` — both grow unbounded by design.
- `asset_usages.first_armed_at`/`last_disarmed_at` (added by `V19`) remain schema-only — no domain
  field exists yet to map them to/from.
- `DatasetExportPort`'s old filesystem-export implementation is gone; dataset delivery to the
  training host now rides a gRPC upload (`cv/grpc`'s `GrpcDatasetUploadPort`), not this module.

**WAREHOUSE-UX wave W8** added two new query methods against the existing `V28` schema — no new
migration, since `maintenance_records`/`asset_usages` already carried every column needed
(`closed_at`/`opened_at`, `asset_id`/`started_at`/`ended_at`). `PostgresDockerIntegrationTest` gained
a `MaintenanceRepositoryTests` nested class (previously untested against real Postgres) plus three
new `AssetUsageRepositoryTests` cases for `totalFlightSecondsByAsset` (closed-usage exact duration,
open-usage running-until-now, absent-asset no-entry).

**CV-SETTINGS wave W3** added `V29`/`V30` and their three adapters (`JpaCvProfileRepository`,
`JpaCvModelRepository`, `JpaTrainingRunRepository`) implementing the perception/learning domain ports
committed by W1/W2. Two deliberate deviations from CV-SETTINGS-PLAN.md §5.3's literal column list, both
flagged in-line in the migration files themselves: `cv_profiles.model_id`/`model_version` (split from
one `model` column — `ModelRef` requires both non-blank) and `cv_profiles.event_rule` (a column §5.3
omitted entirely; without it a `CvProfile` could not round-trip). `PostgresDockerIntegrationTest`
gained three nested classes (`CvProfileRepositoryTests` 11 cases, `CvModelRepositoryTests` 6,
`TrainingRunRepositoryTests` 4) plus two top-level migration-verification tests —
`v29MigrationSeedsFourBuiltInCvProfilesWithZeroBindings` reads the four seeded rows back through the
real adapter (not raw SQL) and asserts their `tracking`/`eventRule` jsonb decodes byte-identical to
`TrackingConfig.defaults()`/`EventRuleConfig.defaults()`, and that each ships with zero bindings;
`v30MigrationCreatesTheCvModelRegistryTablesOnTopOfV1ThroughV29` proves the composite PK and every
nullable provenance/metrics column via `information_schema`. 237 → 260 tests (Maven's own summary
line); `BUILD SUCCESS`, Docker ran (not skipped). See CV-SETTINGS-CONTEXT.md's W3→W5 handoff for the
adapter bean names and the four fixed built-in profile UUIDs vision-app's wiring needs.

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

See `docs/plans/README.md` for the plan-status authority behind the phase references throughout this
file (MVP2, POSTGRES-ONLY-CONTEXT, SCALE-100, FIXED-CAMERA-GEO, VISUAL-GEO-V2, DRONE-ONBOARDING,
CONTROLLER-SETUP-CONTEXT, ARCHITECTURE-AUDIT-2026-08-26, CV-SETTINGS, ZERO-CONFIG-ONBOARDING-CONTEXT).
