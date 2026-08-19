# adapter-persistence

JPA/Postgres persistence adapter for every repository port the platform has: the fleet-side ports
— categories, devices, assets (docs/plans/done/MVP2-PLAN.md P-a) — the history ports — asset usages,
telemetry samples, detection results (docs/plans/done/MVP2-PLAN.md P-b) — geofence zones
(docs/plans/done/OPS-CORE-PLAN.md §G, G-b) — identity: users + groups (docs/plans/done/U-AUTH-PLAN.md wave 3) —
pilot→asset assignments (docs/plans/done/U-SCOPE-PLAN.md slice 2) — tactical marks, the shared
operational picture (docs/plans/done/TACTICAL-MARKS-PLAN.md M2) — and the map's Common Operational Picture:
layers with grantable access, and drawings (docs/plans/done/MAP-REWORK-PLAN.md Wave C).

**Depends on:** vision-domain, `org.hibernate.orm:hibernate-core`, `org.hibernate.orm:hibernate-hikaricp`
(docs/plans/active/SCALE-100-PLAN.md S3 — declared for its version pin only, see "Connection pool" below for why
its own `HikariCPConnectionProvider` is not what's actually wired), `com.zaxxer:HikariCP` (S3, explicit compile-scope
dependency — `hibernate-hikaricp` declares it `runtime`-scope only, which is not enough for `PersistenceUnit` to
reference `HikariConfig`/`HikariDataSource` directly), `org.postgresql:postgresql`,
`org.flywaydb:flyway-core`/`flyway-database-postgresql`, `tools.jackson.core:jackson-databind`
(Jackson 3, for jsonb columns — see Conventions) · **Used by:** vision-app
(`PersistenceWiringConfiguration` — unconditional since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b;
the `vision.persistence.enabled` flag is gone, Postgres is the only store)
**Build/test:** `./mvnw -B -pl storage/persistence test` — 148 tests (up from 137 — measured directly
via `./mvnw -B -pl storage/persistence clean test`, docker reachable, nothing skipped; docs/plans/active/SCALE-100-PLAN.md
S3: a real HikariCP-backed connection pool, shared with Flyway, replaces Hibernate's built-in unpooled
`DriverManagerConnectionProvider` — new `PersistencePoolSettingsTest` (7) + `ClosingDatasourceConnectionProviderTest`
(2) + two new `PostgresDockerIntegrationTest$ConnectionPoolTests` cases = +11; see "Connection pool" below), up from 133 — measured directly
via `./mvnw -B -pl storage/persistence clean test` immediately before this change; the "128" this
entry previously read already undercounted `DevAccountSeedMigrationTest`'s own 5 W1 scenarios —
docs/plans/active/POSTGRES-ONLY-CONTEXT.md **upgrade path**: this wave fixes the path for a database
that already ran the now-deleted `AuthSeedRunner` (every pre-this-fix `docker-compose.yml` deployment,
since it has always paired `VISION_PERSISTENCE_ENABLED=true` with `VISION_AUTH_ENABLED=true` against a
persistent volume). Two defects, two fixes: (1) `V90001__dev_accounts.sql`'s `ON CONFLICT (id) DO
NOTHING` didn't cover `users.username`'s own `UNIQUE` constraint, so migrating that seed against a
database that already had an `admin`/`manager`/`pilot` row at a *different* id raised "duplicate key
value violates unique constraint `users_username_key`" and aborted Flyway — reproduced against a real
Postgres before the fix, see that file's own header; fixed by replacing the single `INSERT ... VALUES
... ON CONFLICT (id)` with three `INSERT ... SELECT ... WHERE NOT EXISTS (id OR username)` statements,
one per account, since `ON CONFLICT` takes only one target and this needs two. (2) even once that
migration succeeds, a MANAGER whose membership still points at the *old* random root group still saw
an empty fleet — `V13`'s fixed-id root group is a second, unrelated parentless group, not a merge; see
`V16__adopt_fixed_root.sql` (Schema, below) for the non-destructive fix, adopting the fixed group as a
*child* of the pre-existing root when there is exactly one. New `UpgradePathMigrationTest` (+4,
sibling to `DevAccountSeedMigrationTest`) proves both fixes end-to-end against a real Postgres,
including reproducing `DefaultScopeResolver`'s own subtree walk to prove a pre-existing manager's
`VisibilityScope` now includes `DevPrincipal`'s group — see that class's own javadoc; up from 128,
docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3 — the two ports that previously had no Postgres
implementation at all, `AuditTrailPort`/`DetectionEventRepositoryPort`, now do: new
`JpaAuditTrail`/`JpaDetectionEventRepository`, `V14__audit_trail.sql`/`V15__detection_events.sql`,
+4 `AuditTrailRepositoryTests` +7 `DetectionEventRepositoryTests` +2 schema tests; see that
section below for the retention choice and both ports' wiring status (still unwired — a later
wave's job); up from 113,
docs/plans/done/TRACKING-PLAN.md wave T6 — **no migration, no entity change, no mapper change**: two new
`DetectionRepositoryTests` cases covering the jsonb's forward/backward compatibility, see the
tracks bullet under Conventions; up from 98,
docs/plans/done/MAP-REWORK-PLAN.md Wave C — new `MapLayerEntity`/`JpaMapLayerRepository`,
`MapDrawingEntity`/`JpaDrawingRepository`, `LayerGrantEmbeddable`, +7 layer round-trip tests, +6
drawing round-trip tests, +1 V12 schema test, and `MarkRepositoryTests` 6→7 for the reworked `Mark`;
up from 97,
docs/plans/done/CV-TRAINING-V2-PLAN.md W6 — `FilesystemDatasetExport`/`FilesystemDatasetExportTest` deleted, -4
pure-filesystem tests; delivery to the training host now rides a gRPC upload, `adapter-cv-grpc`'s
`GrpcDatasetUploadPort`, not a filesystem export — see that section below; up from 78, docs/plans/done/CV-TRAINING-PLAN.md
Wave T3 — new `DatasetEntity`/`JpaDatasetRepository`, `TrainingSampleEntity`/`JpaTrainingSampleRepository`,
`SampleImageEntity`/`JpaSampleImageStore`, +19 Postgres-backed round-trip/schema
tests; up from 71, docs/plans/done/TACTICAL-MARKS-PLAN.md
M2 — new `MarkEntity`/`JpaMarkRepository`, +6 round-trip tests +1 schema test; up from 66, docs/plans/done/U-SCOPE-PLAN.md
slice 2 — new `AssignmentEntity`/`AssignmentId`/`JpaAssignmentRepository`, +4 round-trip tests +1 schema test; up from 56, docs/plans/done/OPS-CORE-PLAN.md
G-b — new `GeofenceZoneEntity`/`JpaGeofenceRepository`, +6 round-trip tests +1 schema test; up from 46, docs/plans/done/FC-INTEGRATIONS-PLAN.md
F-b — `TelemetrySampleEntity` gained `flight_state`, +2 round-trip tests +1 schema test; up from 42, docs/plans/done/UX-REWORK-PLAN.md
§U-d item 3 — new `AssetImageEntity`/`JpaAssetImageRepository`, +4 tests; up from 39, docs/plans/done/MVP2-PLAN.md R-a2 —
`AssetUsageEntity`/`JpaAssetUsageRepository` gained `stream_id`)
(`PostgresDockerIntegrationTest` + 9 `@Nested` classes + 7 top-level retention/restart-survival/schema
tests), all Testcontainers-backed, docker-gated (skip cleanly without docker, see Tests below).

All version pins (Hibernate, Postgres driver, Flyway, Testcontainers, Jackson 3) come from
`spring-boot-dependencies` — this module's grandparent POM via `spring-boot-starter-parent` —
with **no root-pom `<dependencyManagement>` additions needed**, unlike some other adapters'
external dependencies (e.g. javacv/jmdns): every one of these libraries is already a managed
Spring Boot dependency at the versions this repo already runs (Spring Boot 4.1.0 → Hibernate
7.4.1.Final, Flyway 12.4.0, `testcontainers-bom` 2.0.5, Jackson 3.1.4).

## API surface

**Package layout** (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C, Wave C): `repository/` (22
`Jpa*Repository`/`Jpa*Store` classes, up from 21 — the database change audit wave added
`JpaDbAuditLogRepository`, the one class in this package that implements no port at all, see "Database
change audit" below; up from 19, docs/plans/active/DRONE-ONBOARDING-PLAN.md O5 added
`JpaVehicleProfileRepository`/`JpaFeatureRequirementRepository`), `mapper/` (20 entity↔domain mapper classes,
one per aggregate — extracted out of the repositories that used to inline `toEntity`/`toDomain` as
private static methods), `config/` (`PersistenceUnit`, `JpaOperations`), `entity/` (unchanged, see
below). This module gets no `controller/`, `dto/`, or `service/` package — it is a driven adapter.

### `com.drones.vision.adapter.persistence.repository`
- `final class JpaCategoryRepository implements CategoryRepositoryPort` — constructor `(EntityManagerFactory)`.
- `final class JpaDeviceRepository implements DeviceRepositoryPort` — constructor `(EntityManagerFactory)`.
- `final class JpaAssetRepository implements AssetRepositoryPort` — constructor `(EntityManagerFactory)`.
- `final class JpaAssetUsageRepository implements AssetUsageRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/MVP2-PLAN.md P-b. `findRecent(int limit)` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8 — the fleet-wide "replay library" list) is `findRecentByAsset`'s cross-asset counterpart: the same `order by started_at desc` query with no `asset_id` predicate.
- `final class JpaTelemetryRepository implements TelemetryRepositoryPort` — constructor `(EntityManagerFactory)` (production default retention cap, see Retention below), `(EntityManagerFactory, int retentionLimitPerUsage)` (test/override seam), or `(EntityManagerFactory, int retentionLimitPerUsage, TelemetryBatchSettings)` (docs/plans/active/SCALE-100-PLAN.md S4 — explicit batching; the two shorter constructors delegate to it with `TelemetryBatchSettings.immediate()`, so they keep every pre-S4 caller's synchronous-write behavior byte-identical). One method beyond the port: `int pendingBatchCount()` — usages currently holding buffered, not-yet-durable samples, `0` in immediate mode. docs/plans/done/MVP2-PLAN.md P-b; batching in "Batching (SCALE-100-PLAN S4)" below.
- `final class JpaDetectionRepository implements DetectionRepositoryPort` — constructor `(EntityManagerFactory)` or `(EntityManagerFactory, int retentionLimitPerStream)`, same shape as `JpaTelemetryRepository`. docs/plans/done/MVP2-PLAN.md P-b.
- `final class JpaAssetImageRepository implements AssetImageRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 — the asset image store (CONTRACT 2).
- `final class JpaGeofenceRepository implements GeofenceRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/OPS-CORE-PLAN.md §G, G-b — geofence zones; `save` is merge-by-id (upsert), `deleteById` a real hard delete (zones have no soft-delete concept — a disabled zone is just `enabled=false`, not a lifecycle state).
- `final class JpaUserRepository implements UserRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/U-AUTH-PLAN.md wave 3 — the identity aggregate; `save` is merge-by-id (upsert). `findByUsername` lower-cases its lookup key (`Locale.ROOT`) then exact-matches `users.username` (the domain already stores it lower-cased, so this *is* the case-insensitive lookup; a `NoResultException` from the single-result query maps to empty `Optional`). Memberships ride on the row as jsonb (see `UserEntity`).
- `final class JpaGroupRepository implements GroupRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/U-AUTH-PLAN.md wave 3 — org-chart nodes; `save` is merge-by-id (upsert); `parentGroupId` maps straight through as a nullable `UUID`.
- `final class JpaAssignmentRepository implements AssignmentRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/U-SCOPE-PLAN.md slice 2 — the pilot→asset join; `assign` is an idempotent upsert via `merge` on the composite (pilot, asset) key (no duplicate row, no error), `unassign` a delete-if-present (idempotent); `assetsForPilot`/`pilotsForAsset` are indexed JPQL queries returning `UUID`s mapped to `AssetId`/`UserId`, `isAssigned` a composite-PK `find`. Matches the port's set-semantics contract exactly (idempotent assign/unassign, no duplicates). **No mapper class** (see `mapper` package note below) — there is no domain aggregate to map to/from, only inline `UUID`↔id-wrapper conversions.
- `final class JpaMarkRepository implements MarkRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/TACTICAL-MARKS-PLAN.md §3/M2 — tactical marks (the shared operational picture); `save` is merge-by-id (upsert), `deleteById` a real hard delete (idempotent) — same shape as `JpaGeofenceRepository`, `Mark`'s own template.
- `final class JpaMapLayerRepository implements MapLayerRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.4 — the access-controlled surfaces marks/drawings live on; `save` is merge-by-id (upsert), which also **replaces the layer's grant list wholesale** (the `map_layer_grants` element collection rides on the aggregate), matching `MapLayerService#setGrants`'s own "wholesale, not a delta" contract. `deleteById` is a real hard delete, idempotent — and deliberately **non-cascading** to marks/drawings: that cascade is `DefaultMapLayerService#delete`'s job, since each removed row must also publish its own `MapEvent`.
- `final class JpaDrawingRepository implements DrawingRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.4 — lines/polygons/arrows/text; `save` is merge-by-id (a drawing mutates in place as its geometry is dragged), `deleteById` a real hard delete, idempotent — same shape as `JpaMarkRepository`.
- `final class JpaDatasetRepository implements DatasetRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — training datasets; `save` is merge-by-id (upsert), `delete` a real hard delete (idempotent), same shape as `JpaGeofenceRepository`/`JpaMarkRepository`. `targetCategory` maps a nullable `CategoryId` to/from a plain nullable varchar.
- `final class JpaTrainingSampleRepository implements TrainingSampleRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — captured frames + their evolving annotations; `save` is merge-by-id (upsert — a sample mutates over its own review lifecycle, unlike `JpaDetectionRepository`'s append-only rows). `findByDataset`/`countByDataset` share one JPQL-with-optional-clause shape for the `(datasetId, statusOrNull)` filter `idx_training_samples_dataset_status` indexes; `findByDataset` orders newest-captured-first before bounding to `limit`.
- `final class JpaSampleImageStore implements SampleImageStorePort` — constructor `(EntityManagerFactory)`. docs/plans/done/CV-TRAINING-PLAN.md §1/§C, Wave T3 — the `JpaAssetImageRepository` shape, verbatim, reused for training-sample frames; `save` is merge-by-`sampleId` (upsert).
- `final class JpaAuditTrail implements AuditTrailPort` — constructor `(EntityManagerFactory)`. docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3 — the durable audit trail; `record` always `persist`s a brand-new row (never `merge`s — entries are immutable historical facts per the port's own contract, and `id` is the domain's own `AuditId`, not synthetic). `findRecent`/`findByTarget`/`findByActor` share one JPQL shape (an optional `WHERE`, `order by occurredAt desc`, bounded by `limit`). No retention pruning — see Retention below. **Wired into `vision-app` since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b**: `ApplicationServiceWiring#auditTrailPort` builds this unconditionally (there is no `vision.persistence.enabled` branch left, and no `InMemoryAuditTrail` left to fall back to), wrapped in `LiveUpdateAuditTrail` when `vision.live.enabled=true`.
- `final class JpaDetectionEventRepository implements DetectionEventRepositoryPort` — constructor `(EntityManagerFactory)` or `(EntityManagerFactory, int retentionLimitPerStream)`, same shape as `JpaDetectionRepository`. docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3 — debounced detection events (docs/plans/done/MVP2-PLAN.md §E, E-a); unlike `JpaDetectionRepository`'s always-`persist` rows, `save` is a genuine upsert (`merge`-by-id) — a `DetectionEvent` mutates over its own open lifetime, matching the deleted `InMemoryDetectionEventRepository`'s remove-then-re-add-by-id semantics. **Wired into `vision-app` since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b**: `ApplicationServiceWiring#detectionEventRepositoryPort` builds this unconditionally, wrapped in `LiveUpdateDetectionEventRepository` when `vision.live.enabled=true` — same deferred-then-closed wiring status as `JpaAuditTrail` above.
- `final class JpaVehicleProfileRepository implements VehicleProfileRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/active/DRONE-ONBOARDING-PLAN.md O5 — `save` always `persist`s a brand-new row (append-only, `VehicleProfile` carries no id to merge by — see `VehicleProfileEntity`'s own javadoc); `findLatest(DeviceId)` is `order by observedAt desc` + `setMaxResults(1)`, same "newest row" shape as `JpaAssetRepository`/`JpaAssetUsageRepository`'s own single-row-latest queries. **O11 additions** (`V20__vehicle_profile_usage_link.sql`, the flight-passport wave): `save(DeviceId, UsageId, FlightPhase, VehicleProfile)` — same always-`persist` shape, tagged with the two new columns; `findByUsageAndPhase(UsageId, FlightPhase)` — same "newest row" query, filtered on `usage_id`/`phase` instead of `device_id`, backed by `idx_vehicle_profiles_usage_phase`.
- `final class JpaFeatureRequirementRepository implements FeatureRequirementRepositoryPort` — constructor `(EntityManagerFactory)`. docs/plans/active/DRONE-ONBOARDING-PLAN.md O5/D6 — **read-only**: the port has no `save` method at all, every row is Flyway seed data (`V18__feature_requirements.sql`), never written by application code. `findByFirmware`/`findAll` are plain JPQL selects.
- `final class JpaDbAuditLogRepository` — constructor `(EntityManagerFactory)`. **The one class in this package implementing no port** — see "Database change audit" below for why: every row in `db_audit_log` is written by a Postgres trigger (`V21__db_audit_log.sql`), never by this class, and there is deliberately no domain port for it (infrastructure, not a concept any context module should import). Two read methods, matching the table's two indexes: `findRecent(int limit)` (newest overall) and `findRecentForRow(String tableName, String rowId, int limit)` (newest for one row) — both order by `occurredAt desc, id desc`, the `id` tiebreaker because two rows written inside the same transaction can share a timestamp down to the column's own precision.

Every repository above composes a `com.drones.vision.adapter.persistence.config.JpaOperations`
(one constructor argument, the module's `EntityManagerFactory`) and, except
`JpaAssignmentRepository`, calls its aggregate's mapper (`com.drones.vision.adapter.persistence.mapper`,
below) for entity↔domain conversion instead of inlining `toEntity`/`toDomain` as private methods —
the mapping logic moved out (docs/plans/active/LAYERING-REFACTOR-PLAN.md Wave C), the query/transaction logic
did not.

### `com.drones.vision.adapter.persistence.mapper`

One mapper class per aggregate, each a `public final class` with a private constructor and two
`public static` methods (`toEntity`/`toDomain`) — extracted verbatim from the private static
methods every `Jpa*Repository` used to carry inline (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C;
this was the module's one piece of genuine "light work" per the plan's own §0 verdict, not
ceremony: 12 of the 15 repositories had real multi-line `toEntity`/`toDomain` logic — nested-record
flattening, nullable sub-object handling, synthetic-id generation — worth separating from the
JPA/transaction plumbing that stayed in `repository`).

`CategoryMapper`, `DeviceMapper`, `AssetMapper`, `AssetUsageMapper`, `TelemetryMapper`,
`DetectionResultMapper`, `AssetImageMapper`, `GeofenceZoneMapper`, `UserMapper`, `GroupMapper`,
`MarkMapper`, `DatasetMapper`, `TrainingSampleMapper`, `SampleImageMapper`, `MapLayerMapper`,
`DrawingMapper`, `AuditEntryMapper`, `DetectionEventMapper`, `VehicleProfileMapper`,
`FeatureRequirementMapper` — 20 mappers (up from 18, docs/plans/active/DRONE-ONBOARDING-PLAN.md O5),
one per aggregate the module's 21 repositories cover. `TelemetryMapper#toEntity`/`DetectionResultMapper#toEntity`
take the extra argument (`UsageId`, or none) their entities' synthetic id generation needs;
`AssetImageMapper#toEntity`/`SampleImageMapper#toEntity` each take the owning id (`AssetId`/
`TrainingSampleId`) plus the domain value object, matching the shape `JpaAssetImageRepository`/
`JpaSampleImageStore`'s `save(id, value)` port methods already have. `VehicleProfileMapper#toEntity`
takes the extra `DeviceId` argument its entity's FK column needs (same shape as `TelemetryMapper`);
`FeatureRequirementMapper#toEntity` derives the synthetic `"<firmware>:<featureKey>"` id inline, no
extra argument needed since both halves already live on the domain record. **O11**:
`VehicleProfileMapper` gained a `toEntity(DeviceId, UsageId, FlightPhase, VehicleProfile)` overload —
the untagged `toEntity(DeviceId, VehicleProfile)` now delegates to it with a `null` usage/phase
rather than duplicating the 13-argument entity-construction call; `toDomain` unchanged (`usageId`/
`phase` live at the port-call level, not on the `VehicleProfile` record itself — same idiom as
`deviceId`).

**No `AssignmentMapper`** — `JpaAssignmentRepository`, is deliberately
excluded. `AssignmentEntity` is a bare join row with no corresponding domain aggregate (there is no
`Assignment` record — the port deals directly in `UserId`/`AssetId` sets and booleans), so every
conversion is already a one-line `UUID`↔id-wrapper wrap inlined at its call site (e.g. `AssetId::new`
in `assetsForPilot`). A same-shaped `AssignmentMapper` would be a file with two one-line methods and
no logic behind them — exactly the "empty ceremony" the plan's own guardrail warns against
creating. Flagged here rather than silently decided, per this wave's brief.

### `com.drones.vision.adapter.persistence.config`
- `final class PersistenceUnit` — `static EntityManagerFactory start(String jdbcUrl, String username, String password[, boolean seedDevUsers[, PersistencePoolSettings poolSettings]])`: builds one pooled `HikariDataSource` (docs/plans/active/SCALE-100-PLAN.md S3), migrates the schema through it with Flyway (`classpath:db/migration`, plus `classpath:db/seed/dev` when `seedDevUsers` is `true` — see the `db/seed/dev` schema entry below), then opens a Hibernate-native `EntityManagerFactory` over that same `DataSource` mapping every entity below — twenty-two as of the database change audit wave (`DbAuditLogEntity` added, see "Database change audit" below; this line previously read "nineteen," already stale before this wave — the running count was not kept in sync every prior wave either) (see "Connection pool" below and the "W1 done" narrative section near the end of this file for the `ignoreMigrationPatterns` story). The 3-arg and 4-arg overloads (`seedDevUsers` implicitly `false`, and/or `poolSettings` implicitly `PersistencePoolSettings.defaults()`) are kept so pre-existing callers (e.g. `PostgresDockerIntegrationTest`) don't need to change. The 5-arg overload is the one vision-app's wiring should move to, once it can bind `vision.persistence.pool.*` — see "Connection pool" below for the exact keys.
- `public final class JpaOperations` — the `write(Function<EntityManager,T>)`/`read(Function<EntityManager,T>)` transaction-boilerplate helper every `Jpa*Repository` composes rather than extends (each opens/commits/closes its own short-lived `EntityManager` per call — see Gotchas). **Public, not package-private** (widened from the pre-refactor package-private): the `repository` package it now serves lives in a sibling package, so cross-package visibility is required — see Gotchas for the full visibility-widening note.
- `record PersistencePoolSettings(int maximumPoolSize, int minimumIdle, long connectionTimeoutMillis, long leakDetectionThresholdMillis)` (docs/plans/active/SCALE-100-PLAN.md S3) — the four HikariCP knobs `PersistenceUnit` needs, pulled out as a framework-free record so no magic number lives inline in `PersistenceUnit` itself (CLAUDE.md rule 1). Compact constructor validates `maximumPoolSize >= 1`, `0 <= minimumIdle <= maximumPoolSize`, `connectionTimeoutMillis > 0`, `leakDetectionThresholdMillis >= 0` (`0` means "disabled", Hikari's own convention). `static PersistencePoolSettings defaults()` returns `maximumPoolSize=20, minimumIdle=5, connectionTimeoutMillis=30_000, leakDetectionThresholdMillis=30_000` — sized for ~100 concurrent users on one instance, not a placeholder; each default's justification is on its own `DEFAULT_*` constant's javadoc. vision-app's `VisionPersistenceProperties` is the intended source of a non-default instance, via `vision.persistence.pool.*` (see "Connection pool" below) — this module never reads Spring config itself.
- `final class ClosingDatasourceConnectionProvider extends org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl` (docs/plans/active/SCALE-100-PLAN.md S3) — the one behavior it adds over its base class: overriding `stop()` to also close the configured `DataSource` if it is `Closeable` (which `HikariDataSource` is). The base class assumes a container-managed `DataSource` Hibernate never owns and must never close (its `stop()` is a no-op); that assumption is wrong here, since `PersistenceUnit.start` builds and *owns* the pool. Hibernate instantiates it via `hibernate.connection.provider_class` (a bare class name, reflection, no-arg constructor) and calls `configure(Map)` — never constructed directly by this module's own code outside tests.

### `com.drones.vision.adapter.persistence.entity`
- `CategoryEntity`, `DeviceEntity`, `AssetEntity`, `AssetUsageEntity`, `TelemetrySampleEntity`, `DetectionResultEntity`, `AssetImageEntity`, `GeofenceZoneEntity`, `UserEntity`, `GroupEntity`, `AssignmentEntity`, `MarkEntity`, `DatasetEntity`, `TrainingSampleEntity`, `SampleImageEntity`, `MapLayerEntity`, `MapDrawingEntity`, `AuditEntryEntity`, `DetectionEventEntity` (+ the `@Embeddable` `LayerGrantEmbeddable`) — plain JPA entities, field-annotated (protected no-arg ctor for JPA, a public all-args ctor and no-prefix accessors — e.g. `id()`, `name()` — for symmetry with the domain records they mirror). Never referenced outside this module. Entity↔domain mapping now lives one package over, in `mapper` (see above) — not inlined per repository as it was before docs/plans/active/LAYERING-REFACTOR-PLAN.md Wave C.
- `TelemetrySampleEntity`/`DetectionResultEntity` have a synthetic UUID `id` the adapter invents at save time (`UUID.randomUUID()` in each repository's `toEntity`) — `Telemetry`/`DetectionResult` themselves carry no identity of their own (append-only samples/results, not aggregates), so there is nothing domain-side to derive a primary key from; the id never surfaces back through the ports.
- `TelemetrySampleEntity#flightState` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b, `V6__telemetry_flight_state.sql`) is a nullable `FlightState` field, `@JdbcTypeCode(SqlTypes.JSON)`/`columnDefinition = "jsonb"` — the domain record stored **directly**, exactly the `DetectionResultEntity#detections` precedent noted in Conventions below (a plain immutable record tree, no persistence-local wrapper type needed). `null` covers both "sample pre-dates this column" and "device reported no flight-controller state at all"; both round-trip as `Telemetry#flightState() == null`, the same nullable-9th-component contract the domain record itself defines — there is no way to tell the two cases apart from this column alone, and nothing needs to.
- `AssetUsageEntity#streamId` (docs/plans/done/MVP2-PLAN.md R-a2, `V4__usage_stream_id.sql`) is a nullable `UUID` column, mapped straight through by `JpaAssetUsageRepository` (`streamId == null ? null : streamId.value()` / `new StreamId(...)`) exactly like every other nullable field on this entity — no special-casing beyond the null check.
- `AssetUsageEntity#phase` (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O5/O7, `V19__asset_usage_phase.sql`) reuses the domain `UsagePhase` enum directly in an `@Enumerated(EnumType.STRING)` field, same convention `GeofenceZoneEntity#kind`/`MarkEntity#kind` follow. `AssetUsageMapper` always writes a real value on `toEntity` (`AssetUsage#phase()` is non-null by construction) and, on `toDomain`, maps a `null` column (a row saved before this column existed) onto `AssetUsage`'s own pre-O7 8-arg convenience constructor, which defaults to `UsagePhase.PREFLIGHT` — the same "unknown, not fabricated" honesty `streamId` above already practices. **Fixed a real bug during this wave**: the column shipped schema-only in V19 with no entity field at all, so a phase `UsageTracker` (vision-perception) had actually computed and saved was silently discarded on every reload, always reading back `PREFLIGHT` regardless of what was saved — caught before merge by a cross-wave report from O7, not by this wave's own original test suite (see `AssetUsageRepositoryTests#savedUsageWithANonDefaultPhaseRoundTripsExactly`/`#saveIsAnUpsertThatCanTransitionPhase`/`#legacyRowWithNullPhaseColumnMapsToPreflightDefault`, Tests below). `first_armed_at`/`last_disarmed_at` (V19's other two additive columns) stay unmapped — `AssetUsage` does not carry those two fields as of Wave O7, only `phase`.
- `AssetImageEntity` (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, `V5__asset_images.sql`) is keyed by `assetId` itself, **not** a synthetic id like `TelemetrySampleEntity`/`DetectionResultEntity` above — there is at most one image per asset and `save` is always an upsert, so the primary key doubles as the "one row per asset" constraint with no separate unique index needed. `data` is a plain `byte[]` field (Hibernate's default mapping to Postgres `bytea`, no `@Lob`/converter needed) — the first non-jsonb, non-text binary column in this module's schema.
- `GeofenceZoneEntity` (docs/plans/done/OPS-CORE-PLAN.md §G, G-b, `V7__geofence_zones.sql`) mirrors `GeofenceZone` field-for-field: `id` is the domain's own `ZoneId` (not synthetic — a zone has real identity, unlike `Telemetry`/`DetectionResult`), `kind` reuses the domain `ZoneKind` enum directly in an `@Enumerated(EnumType.STRING)` field (same "domain enums reused directly" convention `Capability`/`LifecycleState` already follow), `polygon` stores the whole `List<GeoPosition>` as one jsonb column (same mechanism/rationale as `DetectionResultEntity#detections` — a plain immutable record list Jackson serializes natively, only ever read back whole), `maxAltitudeMeters` a nullable `Double`, `enabled` a plain `boolean`. No FK to any other table — zones are global reference data with no relationship to assets/devices.
- `AssignmentEntity` (docs/plans/done/U-SCOPE-PLAN.md slice 2, `V9__pilot_assignments.sql`) is a plain join row — a pilot→asset link with **no synthetic id**: its primary key is the composite (`pilot_user_id`, `asset_id`) via `@IdClass(AssignmentId.class)`. `AssignmentId` is a plain mutable class with a public no-arg ctor + matching field names (JPA's `@IdClass` contract — a record cannot satisfy it). The composite PK doubles as the uniqueness constraint that makes `assign` an idempotent upsert with no duplicate rows. The table also has an unmapped `assigned_at` bookkeeping column (Hibernate `validate` tolerates DB columns the entity does not map). No FK to `users`/`assets`, same convention as every other table here.
- `MarkEntity` (docs/plans/done/TACTICAL-MARKS-PLAN.md §3/M2, `V10__marks.sql`) mirrors `Mark` field-for-field: `id` is the domain's own `MarkId` (not synthetic — a mark has real identity); `kind`/`status`/`source` reuse the domain `MarkKind`/`MarkStatus`/`MarkSource` enums directly in `@Enumerated(EnumType.STRING)` fields (same convention `GeofenceZoneEntity#kind` follows); `position` (a single `GeoPosition`, not a polygon) is flattened to `latitude`/`longitude`/nullable `altitude_meters` columns rather than jsonb — same "flatten a small value type into columns" choice `AssetUsageEntity` makes for `GeoPosition`, not `GeofenceZoneEntity#polygon`'s jsonb choice for a whole vertex list; `ownership` is flattened to `owner_id`/`group_id`, same choice `AssetEntity` makes for `Ownership`. No FK to any other table. **Reworked by docs/plans/done/MAP-REWORK-PLAN.md §4.4 (`V12__map_layers.sql`) with five more columns**: `layer_id` (which layer the mark lives on — no FK, indexed), `affiliation` (the domain `Affiliation`: "whose it is", split out of what `kind` used to conflate), and the three-component `Verification` value flattened exactly the way `ownership` already is — `verification_state` plus a nullable `verified_by`/`verified_at` pair that is non-null precisely when the state is `CONFIRMED`/`REJECTED`. That last invariant is enforced by `Verification`'s own compact constructor on read-back, **not** by the schema, so a row violating it fails loudly in `MarkMapper#toDomain` rather than producing a silently-inconsistent domain object.
- `DatasetEntity` (docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3, `V11__training_datasets.sql`) mirrors `Dataset` field-for-field: `id` is the domain's own `DatasetId` (not synthetic); `classes` stores the whole ordered `List<String>` as jsonb, same mechanism/rationale as `GeofenceZoneEntity#polygon`; `ownership` is flattened to `owner_id`/`group_id`, same choice `AssetEntity`/`MarkEntity` make; `status` reuses the domain `DatasetStatus` enum directly (`@Enumerated(EnumType.STRING)`); `target_category` is a plain nullable varchar (the `CategoryId` slug, or `null`). No FK to any other table.
- `TrainingSampleEntity` (docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3, `V11__training_datasets.sql`) mirrors `TrainingSample` field-for-field: `id` is the domain's own `TrainingSampleId` (not synthetic — a sample mutates over its own review lifecycle, so it needs a stable key to upsert by, unlike `TelemetrySampleEntity`/`DetectionResultEntity`'s synthetic ids for append-only rows); `annotations` stores the whole `List<Annotation>` as jsonb, same mechanism as `DetectionResultEntity#detections`; `status` reuses the domain `SampleStatus` enum directly; `asset_id`/`labeled_by`/`labeled_at` are nullable columns, mirroring the domain record's own nullability. No FK to `datasets` or any other table.
- `SampleImageEntity` (docs/plans/done/CV-TRAINING-PLAN.md §1/§C, Wave T3, `V11__training_datasets.sql`) is the `AssetImageEntity` precedent applied to training frames instead of asset photos: `sampleId` is the primary key rather than a synthetic one (at most one image per sample, `save` always an upsert); `data` a plain `byte[]`/`bytea` column, no `@Lob`/converter needed. No JPA relationship to `TrainingSampleEntity`.
- `MapLayerEntity` (docs/plans/done/MAP-REWORK-PLAN.md §2.1/§4.4, `V12__map_layers.sql`) mirrors `MapLayer` field-for-field: `id` is the domain's own `LayerId` (not synthetic — a layer has real identity); `kind` reuses the domain `LayerKind` enum directly (`@Enumerated(EnumType.STRING)`, same convention as `GeofenceZoneEntity#kind`/`MarkEntity#kind`); `ownership` is flattened to `owner_user_id`/`group_id`, the same choice `AssetEntity`/`MarkEntity`/`DatasetEntity` make. **`grants` is the one collection in this module stored as an `@ElementCollection` join table (`map_layer_grants`) rather than jsonb** — every other collection here (`polygon`, `memberships`, `classes`, `annotations`, `points`) is jsonb, but grants are the one collection whose individual rows are a *security* decision, so having them queryable/auditable in SQL is worth a table; the composite PK `(layer_id, subject_type, subject_id)` also enforces "at most one grant per subject per layer" for free. `EAGER`, matching `DeviceEntity#capabilities`/`AssetEntity#deviceIds` — the repositories open a short-lived `EntityManager` per call, so a lazy collection would be unreadable by the time the caller sees the domain object.
- `LayerGrantEmbeddable` (docs/plans/done/MAP-REWORK-PLAN.md §4.4) — **the module's only persistence-local mirror of a domain record**, and unavoidably so: a JPA `@Embeddable` must be a mutable class with a no-arg constructor, which the `LayerGrant` record cannot satisfy (JPA 3.2 §2.5). Everywhere else this module references the domain record directly (jsonb via Hibernate's Jackson `FormatMapper`); this is the one place the element-collection choice forces a 1:1 adapter-local twin, converted in `MapLayerMapper`. Defines `equals`/`hashCode` deliberately — Hibernate needs them for element-collection change detection, and without them the whole grant list would be deleted and re-inserted on every save.
- `MapDrawingEntity` (docs/plans/done/MAP-REWORK-PLAN.md §2.1/§4.4, `V12__map_layers.sql`) mirrors `Drawing` field-for-field: `points` stores the whole ordered `List<GeoPosition>` as one jsonb column — same mechanism/rationale as `GeofenceZoneEntity#polygon`, and the deliberate opposite of `MarkEntity`, whose single `GeoPosition` is flattened into columns. `kind` reuses the domain `DrawKind` enum; `label`/`color_token` are nullable (a `TEXT` drawing's label is required by the domain, not the schema). `layer_id` carries **no FK** (see the Conventions note below) but is indexed, since listing a layer's drawings is the access path.
- `UserEntity`/`GroupEntity` (docs/plans/done/U-AUTH-PLAN.md wave 3, `V8__users_groups.sql`) mirror `User`/`Group` field-for-field. `UserEntity#id` is the domain's own `UserId` (not synthetic — a user has real identity); `username` carries a `UNIQUE` constraint and is stored already-lower-cased (the domain `User` normalizes it), so `findByUsername` is an exact match on the stored value after lower-casing the lookup key. **`UserEntity#memberships` stores the whole `List<Membership>` as one jsonb column** (`@JdbcTypeCode(SqlTypes.JSON)`) — same mechanism/rationale as `GeofenceZoneEntity#polygon`/`DetectionResultEntity#detections`: `Membership` (with its nested `GroupId`/`Role`) is a plain immutable record Jackson 3 serializes natively, and memberships are only ever read back whole with the aggregate, so no normalized join table (docs/plans/done/U-AUTH-PLAN.md picked jsonb over a join table for exactly this "saved whole with the User" reason). `GroupEntity#parentId` is a nullable `UUID` (null = root group). No FK on either table (not `groups.parent_id`, not any user→group link) — same "no cross-entity foreign keys" convention as every other table here, keeping parity with the in-memory reference repos that do no referential checks.
- `AuditEntryEntity` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3, `V14__audit_trail.sql`) mirrors `AuditEntry` field-for-field: `id` is the domain's own `AuditId` (not synthetic — every entry has real identity); `action`/`targetType` reuse the domain `AuditAction`/`AuditTargetType` enums directly (`@Enumerated(EnumType.STRING)`, same convention as `GeofenceZoneEntity#kind`); `details` stores the whole free-form `Map<String,String>` as jsonb, same mechanism as `CategoryEntity#attributeHints`. No FK to `users` or to any target table — more than convention here: an entry must stay resolvable even after its actor's account or its target row is gone (see the migration's own header comment).
- `DetectionEventEntity` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3, `V15__detection_events.sql`) mirrors `DetectionEvent` field-for-field: `id` is the domain's own `DetectionEventId` (not synthetic — unlike `DetectionResultEntity`, an event mutates over its own open lifetime and needs a stable key to upsert by). `position` is a single, entirely-optional `GeoPosition`, flattened to nullable `position_latitude`/`position_longitude`/`position_altitude_meters` columns exactly like `AssetUsageEntity#startPosition`/`#lastPosition` (a lat/lon pair is null together iff the position itself is null) — the deliberate opposite of `GeofenceZoneEntity#polygon`'s jsonb choice for a whole vertex list. `state` reuses the domain `DetectionEventState` enum directly. No FK to any other table; `(stream_id, last_seen)` and `last_seen` alone are both indexed (`findByStream`/the retention prune query, and `findRecent`, respectively).
- `VehicleProfileEntity` (docs/plans/active/DRONE-ONBOARDING-PLAN.md O5, `V17__vehicle_profiles.sql`) mirrors `VehicleProfile` field-for-field: `id` is a synthetic UUID (append-only observation, no domain identity — same rationale as `DetectionResultEntity`/`TelemetrySampleEntity`); `deviceId` is the FK the port's own `save(DeviceId, VehicleProfile)`/`findLatest(DeviceId)` key on, same "key lives in the port call, not a domain field" idiom as `TelemetrySampleEntity#usageId`. `capabilityFlags`/`messages`/`parameters` are jsonb — each row is only ever read back whole, never queried into by individual message/parameter, same convention as `DetectionResultEntity#detections`. Indexed on `(device_id, observed_at DESC)` for `findLatest`'s "newest row for this device" query. **O11 additions** (`V20__vehicle_profile_usage_link.sql`, the flight-passport wave): nullable `usageId`/`phase` fields — `phase` reuses the domain `FlightPhase` enum directly (`@Enumerated(EnumType.STRING)`, same `AssetUsageEntity#phase` convention); both are set together or neither (a row from the untagged `save(DeviceId, VehicleProfile)` — readiness's own ad hoc probes — has both `null`; a row from `save(DeviceId, UsageId, FlightPhase, VehicleProfile)` has both set). Indexed on `(usage_id, phase, observed_at DESC)` for `findByUsageAndPhase`'s "newest tagged row" query.
- `FeatureRequirementEntity` (docs/plans/active/DRONE-ONBOARDING-PLAN.md O5/D6, `V18__feature_requirements.sql`) mirrors `FeatureRequirement` field-for-field: `id` is a synthetic `"<firmware>:<featureKey>"` natural key this entity invents purely for a JPA primary key — the domain record carries no id field, but unlike `DetectionResultEntity`'s random-UUID precedent (a genuinely identity-less append-only record), a requirement row is reference data addressed by its own natural composite key, so a stable human-readable id lets the seed migration use `ON CONFLICT (id) DO NOTHING`, the same role `CategoryEntity`'s natural string id plays for `categories`. **Read-only**: the port has no `save` method — every row is seed data written by Flyway (`V18`), never by application code. No FK to any other table (reference/global data, same convention as `geofence_zones`/`categories`); indexed on `firmware` (`findByFirmware`'s access path).
- `DbAuditLogEntity` (`V21__db_audit_log.sql`) — the database change audit; see "Database change audit" below for the full picture. **Read-only from Hibernate's side**: every row is written by that migration's `audit_row_change()` trigger function, never by a `persist`/`merge` call anywhere in this module — mapped here purely so `JpaDbAuditLogRepository`'s reads go through the same `jakarta.persistence` API as every other repository. `id` is a synthetic `BIGINT GENERATED ALWAYS AS IDENTITY` column (`@GeneratedValue(strategy = GenerationType.IDENTITY)`) rather than a UUID this module invents in Java — the only entity in this schema whose id Postgres itself generates, since the trigger that creates each row runs entirely inside the database. `rowId` is `String`, not `UUID`, even though every primary key in this schema is in fact a UUID (or, for the composite-key join tables, several) — the trigger resolves it generically from the catalog and cannot assume one uniform key shape across all seventeen audited tables. `oldRow`/`newRow` map to `Map<String, Object>` and `changedColumns` to `List<String>`, both jsonb — unlike every other jsonb column in this module (which always has one fixed domain record/map shape on the far side), these have no fixed shape at all, since the row image can belong to any of the seventeen audited tables. `oldRow` is `null` for an `INSERT`, `newRow` is `null` for a `DELETE`, `changedColumns` is `null` (not an empty list) for anything but an `UPDATE`. `operation` reuses a small persistence-local enum, `DbAuditOperation` (`INSERT`/`UPDATE`/`DELETE`, mirroring Postgres's own `TG_OP`) — not a domain enum, since there is no domain concept of "a database row changed."

## Schema (`src/main/resources/db/migration`)

- `V1__baseline.sql` — `categories` (`id` varchar PK — the `CategoryId` slug, not a UUID, matching the domain's one non-UUID id type; `parent_id` self-referencing FK, nullable; `attribute_hints` jsonb), `devices` (`id` UUID PK; `stream_protocol`/`stream_uri`/`stream_options` — `StreamDescriptor` flattened; `state` varchar), `device_capabilities` (element-collection join table, PK `(device_id, capability)`), `assets` (`id` UUID PK; `category_id` varchar — no FK, see Gotchas; `owner_id`/`group_id` UUID — `Ownership` flattened; `attributes` jsonb; `state` varchar), `asset_devices` (element-collection join table, PK `(asset_id, device_id)`, indexed on `device_id` for `findByDeviceId`).
- `V2__seed_categories.sql` — the default category set (`drone`, `ip-camera`, `usb-camera`, `robot`, `simulated`, then `fpv-drone`→`drone`, `esp32-cam`→`ip-camera` in a second batch so the self-referencing FK is satisfied), `ON CONFLICT (id) DO NOTHING` so re-running is a no-op — the same set the deleted `InMemoryCategoryRepository` (vision-app devsupport, removed docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b) used to seed at construction, kept identical so removing the in-memory fallback changed no out-of-the-box category list.
- `V3__history.sql` (docs/plans/done/MVP2-PLAN.md P-b) — `asset_usages` (`id` UUID PK; `asset_id` UUID, indexed, no FK; `started_at`/`ended_at` timestamptz, the latter nullable; `start_latitude`/`start_longitude`/`start_altitude_meters` and the `last_*` triple — `GeoPosition` flattened to columns rather than jsonb, same "flatten a small value type" choice `AssetEntity` makes for `Ownership`; `sample_count` bigint), `telemetry_samples` (`id` UUID PK, synthetic; `usage_id` UUID + `at` timestamptz, **indexed together** as `(usage_id, at)`; `device_id` UUID; `latitude`/`longitude`/`altitude_meters`/`heading_degrees`/`battery_percent` all nullable doubles; `extra` jsonb), `detection_results` (`id` UUID PK, synthetic; `stream_id` UUID + `captured_at` timestamptz, **indexed together** as `(stream_id, captured_at)`; `frame_sequence` bigint; `detections` jsonb — the whole `List<Detection>`, see Conventions; `inference_latency_nanos` bigint). No FKs, same rationale as V1's tables (see Conventions).
- `V4__usage_stream_id.sql` (docs/plans/done/MVP2-PLAN.md R-a2) — `ALTER TABLE asset_usages ADD COLUMN stream_id UUID` (nullable, no FK, no backfill — a stream's id was never recorded anywhere before this migration, so pre-existing rows simply read back `null`, matching `AssetUsage#streamId`'s own honest "legacy usage" nullability). Purely additive on top of V1-V3; no other table changes.
- `V5__asset_images.sql` (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3) — `asset_images` (`asset_id` UUID PK — no FK, same convention as every other table; `content_type` varchar; `data` bytea; `updated_at` timestamptz default `now()`). New table, no changes to any existing one.
- `V6__telemetry_flight_state.sql` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b) — `ALTER TABLE telemetry_samples ADD COLUMN flight_state JSONB` (nullable, no FK, no backfill — purely additive on top of V1-V5, same shape as V4's `stream_id` addition). Pre-existing rows simply read back `null`.
- `V7__geofence_zones.sql` (docs/plans/done/OPS-CORE-PLAN.md §G, G-b) — `geofence_zones` (`id` UUID PK — the zone's own `ZoneId`, not synthetic; `name` varchar; `kind` varchar; `polygon` jsonb — the whole `List<GeoPosition>`, ≥3 vertices enforced application-side by `GeofenceZone`/`GeofenceZoneSpec`, not a database `CHECK`; `max_altitude_meters` nullable double precision; `enabled` boolean). New table, no changes to any existing one — no FK, same convention as every other table here.
- `V8__users_groups.sql` (docs/plans/done/U-AUTH-PLAN.md wave 3) — `groups` (`id` UUID PK; `name` varchar; `parent_id` UUID nullable — null = root, no self-FK) and `users` (`id` UUID PK; `username` varchar `NOT NULL UNIQUE` — stored lower-cased by the domain, so plain UNIQUE gives the case-insensitive uniqueness `findByUsername` relies on; `display_name`/`email`/`password_hash` varchar; `enabled` boolean; `memberships` jsonb `NOT NULL` — the whole `List<Membership>`). Two new tables, purely additive over V1-V7, no FK (not `groups.parent_id`, no user→group link) — same convention as every other table here.
- `V9__pilot_assignments.sql` (docs/plans/done/U-SCOPE-PLAN.md slice 2) — `pilot_assignments` (`pilot_user_id` UUID, `asset_id` UUID, `assigned_at` timestamptz default `now()`; **composite `PRIMARY KEY (pilot_user_id, asset_id)`** — the pair is the identity, and doubles as the uniqueness constraint making `assign` an idempotent upsert) plus a secondary index `idx_pilot_assignments_asset` on `asset_id` (the PK's leading column serves `assetsForPilot`; this index serves the `pilotsForAsset` direction). New table, purely additive over V1-V8, no FK — same convention as every other table here.
- `V10__marks.sql` (docs/plans/done/TACTICAL-MARKS-PLAN.md §3/M2) — `marks` (`id` UUID PK — the mark's own `MarkId`, not synthetic; `kind`/`status`/`source` varchar; `label` varchar `NOT NULL`; `note` nullable text; `latitude`/`longitude` required double precision, `altitude_meters` nullable double precision — a single flattened `GeoPosition`, not jsonb, unlike `geofence_zones.polygon`; `owner_id`/`group_id` UUID — `Ownership` flattened, same choice `assets` makes; `created_at` timestamptz). New table, purely additive over V1-V9, no FK — same convention as every other table here. Indexed on `group_id` (scope filtering, application-layer job) and `status` (active/cleared filtering).
- `V11__training_datasets.sql` (docs/plans/done/CV-TRAINING-PLAN.md §1/§3, Wave T3) — three new tables, purely additive over V1-V10, no FK between them or to any other table (same convention as the rest of this schema): `datasets` (`id` UUID PK — the dataset's own `DatasetId`; `target_category` nullable varchar; `classes` jsonb `NOT NULL` — the whole ordered `List<String>`; `owner_id`/`group_id` UUID — `Ownership` flattened; `status` varchar; `created_at` timestamptz), `training_samples` (`id` UUID PK — the sample's own `TrainingSampleId`, not synthetic; `dataset_id`/`stream_id` UUID `NOT NULL`; `asset_id` nullable UUID; `captured_at` timestamptz; `width`/`height` integer; `annotations` jsonb `NOT NULL` — the whole `List<Annotation>`; `status` varchar; `labeled_by` nullable UUID; `labeled_at` nullable timestamptz — plus a secondary index `idx_training_samples_dataset_status` on `(dataset_id, status)`, the exact filter `TrainingSampleRepositoryPort#findByDataset`/`#countByDataset` take), `sample_images` (`sample_id` UUID PK — mirrors `asset_images` exactly; `content_type` varchar; `data` bytea `NOT NULL`; `updated_at` timestamptz default `now()`).
- `V12__map_layers.sql` (docs/plans/done/MAP-REWORK-PLAN.md §4.4, Wave C) — three new tables plus five columns grafted onto `marks`, over V1-V11. **The plan's own sketch says `V11__map_layers.sql`; that was stale by the time this wave ran** (V11 went to `V11__training_datasets.sql`), so V12 is the next free number, confirmed by listing this directory first. Tables: `map_layers` (`id` UUID PK — the layer's own `LayerId`; `name` varchar(80); `kind` varchar; `owner_user_id`/`group_id` UUID — `Ownership` flattened; `created_at` timestamptz), `map_layer_grants` (`layer_id` UUID **FK → `map_layers(id)` `ON DELETE CASCADE`**, `subject_type`/`level` varchar, `subject_id` UUID; composite `PRIMARY KEY (layer_id, subject_type, subject_id)`), `map_drawings` (`id` UUID PK; `layer_id` UUID **no FK**, indexed; `kind` varchar; `label` varchar(120) nullable; `color_token` varchar(30) nullable; `points` jsonb `NOT NULL`; `owner_user_id`/`group_id` UUID; `created_at` timestamptz). `marks` gains `layer_id` UUID (indexed), `affiliation` varchar, `verification_state` varchar `NOT NULL DEFAULT 'UNVERIFIED'`, `verified_by` UUID nullable, `verified_at` timestamptz nullable.
  - **The COP layer is seeded by the migration itself**, at the fixed id `00000000-0000-0000-0000-000000000002`, owned by the system principal `UUID(0,0)`/`UUID(0,1)` — the exact pair `LayerResolver.SYSTEM_USER_ID`/`SYSTEM_GROUP_ID` and `DevPrincipal` stamp. A fixed (not random) id keeps the migration deterministic and re-readable; `...0002` simply follows the system user (`...0000`) and group (`...0001`). `ON CONFLICT DO NOTHING`, so re-running is a no-op. `LayerResolver#copLayerId()` then *finds* this row instead of lazily creating one — which is how the Postgres and in-memory modes converge on exactly one COP layer.
  - **Backfill order is load-bearing.** Existing marks are pointed at that COP layer, then `affiliation` is derived **from the old `kind`** per docs/plans/done/MAP-REWORK-PLAN.md §2.2's frozen table (`TARGET→HOSTILE`, `HAZARD→UNKNOWN`, `POI→NEUTRAL`, `FRIENDLY→FRIENDLY`), and only *then* is `kind = 'FRIENDLY'` rewritten to `'UNIT'` — running the rename first would destroy the information the affiliation is derived from. `layer_id`/`affiliation` are set `NOT NULL` after the backfill, not before.
  - **FK policy, a deliberate deviation from the plan's parenthetical** ("layer_id (FK…)" on all three): only `map_layer_grants` gets one. That table is an element collection of the `map_layers` aggregate — Hibernate owns both sides and never inserts a grant without its layer — so the FK is free correctness. `marks.layer_id` and `map_drawings.layer_id` get **no** FK, matching this schema's standing convention (see Conventions): a real constraint there would reject writes the in-memory reference repositories (what the default-config app actually runs) happily accept, breaking the round-trip parity this module is judged against. The layer→marks/drawings cascade is already performed in application code by `DefaultMapLayerService#delete`, which also has to emit one `MapEvent` per cascaded row — something `ON DELETE CASCADE` could not do anyway.
- `V13__identity_baseline.sql` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1) — seeds the root group at the
  **fixed** id `00000000-0000-0000-0000-000000000001`, matching `DevPrincipal.GROUP_ID`
  (`UUID(0,1)`) exactly, `ON CONFLICT (id) DO NOTHING`. This is a bug fix, not a feature: the
  now-deleted `AuthSeedRunner` used to create the root group with `GroupId.random()` at
  application-startup time, so every asset created while `vision.auth.enabled=false` (the default) was
  owned by the *fixed* `DevPrincipal.GROUP_ID`, while the seeded root group a persistence-enabled app
  actually had in its `groups` table was a *different*, random id — a MANAGER scoped to that random
  root group saw an empty fleet. Pinning the seed to the same fixed id `DevPrincipal` already hardcodes
  closes the gap from the schema side; `DevPrincipal` itself was not touched (this migration adapts to
  it, not the reverse). Safe to apply against a database that already has a randomly-seeded root group
  from a pre-W1 `AuthSeedRunner` run — `ON CONFLICT (id) DO NOTHING` only skips if id
  `...0001` itself already exists, so a stale random-id row would coexist rather than block the insert;
  a genuinely clean pre-W1 install has no such row and gets the fixed one immediately. No FK, same
  convention as every other table here.
- `V14__audit_trail.sql` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3) — `audit_entries` (`id` UUID PK — the entry's own `AuditId`, not synthetic; `occurred_at` timestamptz; `actor_id` UUID; `action`/`target_type` varchar(16); `target_id` varchar(255); `summary` text; `details` jsonb `NOT NULL DEFAULT '{}'::jsonb` — the whole free-form `Map<String,String>`). New table, purely additive over V1-V12 (the next free migration number — V13 was claimed concurrently by docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1's `V13__identity_baseline.sql`, a parallel wave). No FK to `users` or to any target table, deliberately: an audit entry must stay resolvable even after its actor's account or its target row is gone, which a referential constraint would actively break, not just diverge from parity with. Three indexes, one per read access path: `occurred_at` alone (`findRecent`), `(target_type, target_id, occurred_at)` (`findByTarget`), `(actor_id, occurred_at)` (`findByActor`) — each with `occurred_at` trailing so the newest-first `ORDER BY` can use the index directly.
- `V15__detection_events.sql` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3) — `detection_events` (`id` UUID PK — the event's own `DetectionEventId`, not synthetic; `stream_id` UUID; `asset_id` UUID nullable; `label` varchar(255); `peak_confidence` double precision; `first_seen`/`last_seen` timestamptz; `state` varchar(16); `position_latitude`/`position_longitude`/`position_altitude_meters` double precision, all nullable together). New table, purely additive over V1-V14, no FK — same convention as the rest of this schema. Two indexes: `(stream_id, last_seen)` (`findByStream`'s newest-first query and the retention prune query both use it) and `last_seen` alone (`findRecent`'s cross-stream newest-first query).
- `V16__adopt_fixed_root.sql` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md upgrade path, defect 2) —
  **data-only**, no DDL: a `DO $$ ... $$` block, the first PL/pgSQL in this schema (every earlier
  migration is plain SQL) — needed because the branch ("adopt, or don't") depends on a count read at
  migration time, not a fixed condition `WHERE`/`ON CONFLICT` can express. Counts groups where
  `parent_id IS NULL AND id <> '...0001'` (the fixed root `V13__identity_baseline.sql` seeds).
  Exactly one such "other" parentless group → `UPDATE groups SET parent_id = <that group's id>, name
  = 'Dev-Mode Assets' WHERE id = '...0001'`: this is the actual upgrade case — a database that ran
  the deleted `AuthSeedRunner` before `V13` existed has its own random-id "Root" as a second,
  unrelated parentless group once `V13` adds the fixed one. Adopting the *fixed* group as a *child*
  of the *pre-existing* one (never the reverse — reparenting the old root under the fixed group would
  not touch the subtree `DefaultScopeResolver` walks from a manager's existing, unmoved membership)
  puts `UUID(0,1)` inside that subtree, so a manager whose membership already points at the old root
  starts seeing `DevPrincipal`-owned assets — see this migration's own header, and
  `UpgradePathMigrationTest` (Tests, below) for the real Postgres proof, including the subtree walk
  itself. Renamed to **"Dev-Mode Assets"** on adoption (not left as "Root", which would be wrong two
  levels deep in an org chart): the honest description of what the group holds once it is no longer
  the root — everything `DevPrincipal` stamped while `vision.auth.enabled=false`. Zero other
  parentless groups (fresh install — `V13` is the only root there is) or two-or-more (ambiguous, no
  principled pick) → no-op either way, the first required so a plain fresh install is never touched,
  the second flagged in the migration's own header as needing an operator's manual reconciliation
  rather than a migration's guess. `max(uuid)`/`min(uuid)` do not exist in Postgres (measured, not
  assumed — an early draft using `max(id)` failed with "function max(uuid) does not exist"), so the
  "which one" lookup is a plain `SELECT id ... INTO`, safe because the branch already guarantees
  exactly one row. Touches only `groups.parent_id`/`groups.name` for the single fixed-id row —
  rewrites no `users` row, no `memberships` jsonb, no asset/mark/layer ownership column, deletes
  nothing.

- `V17__vehicle_profiles.sql` (docs/plans/active/DRONE-ONBOARDING-PLAN.md O5) — `vehicle_profiles` (`id` UUID PK, synthetic; `device_id` UUID `NOT NULL` — the FK `save(DeviceId, VehicleProfile)`/`findLatest(DeviceId)` key on, no actual foreign-key constraint, same convention as every other table here; `link_key` varchar `NOT NULL`; `observed_at` timestamptz `NOT NULL`; `sysid` nullable integer; `firmware`/`firmware_version`/`vehicle_kind` nullable varchar; `capability_bitmask` nullable bigint; `capability_flags`/`messages`/`parameters` jsonb `NOT NULL DEFAULT '[]'`; `link_bytes_per_second` nullable bigint; `complete` boolean `NOT NULL`; `incomplete_reason` nullable varchar(500)). New table, purely additive over V1-V16, no FK. Indexed on `(device_id, observed_at DESC)` for the "newest row for this device" query `findLatest` runs.
- `V18__feature_requirements.sql` (docs/plans/active/DRONE-ONBOARDING-PLAN.md O5/D6) — `feature_requirements` (`id` varchar(160) PK — the synthetic `"<firmware>:<featureKey>"` natural key; `feature_key`/`label`/`firmware` varchar `NOT NULL`; `required_message_id` nullable integer; `required_message_name` nullable varchar(64); `minimum_hz` nullable double precision; `required_parameter_name` nullable varchar(64)). New table, purely additive over V1-V17, no FK — reference/global data, same convention as `geofence_zones`/`categories`. Indexed on `firmware`. **Seeds eleven rows, `firmware='ardupilot'` only** (D13: PX4 is "generic MAVLink, unverified" until a PX4 SITL run proves otherwise — a firmware with zero rows is a correct answer, not an omission bug, per O3's own `ReadinessService` contract). Message ids verified against pymavlink's `common.xml`, not memory (`HEARTBEAT=0`, `SYS_STATUS=1`, `GPS_RAW_INT=24`, `ATTITUDE=30`, `GLOBAL_POSITION_INT=33`, `RC_CHANNELS=65`, `VFR_HUD=74`). Only two of the eleven rows carry a plan-given threshold (docs/conclusions/ANY-DRONE-PLAN.md §S1.2): `map-position` (`GLOBAL_POSITION_INT >= 2.0 Hz`) and `visual-geolocation` (`ATTITUDE >= 5.0 Hz`); the other five message-bearing rows (`preflight-checks`, `ground-speed`, `link-quality`, `failsafe-banners`, `battery`) got **no** plan-given number and were seeded at a **judgment-call 1.0 Hz floor** (the MAVLink stream-rate convention a healthy link clears easily; ArduPilot's own `SRx_*` defaults sit at or above it) — flagged here for whoever tunes it later. `fleet-identity` is parameter-only (`SYSID_THISMAV`); `command-tx`/`rc-relay`/`video-ingest` are "neither" rows (capability-bitmask- or non-MAVLink-driven, trivially satisfied once they exist, per `FeatureRequirement`'s own javadoc). **Two known representation gaps, deliberately left as gaps rather than smuggled into an existing column**: ANY-DRONE §S1.2 also lists `STATUSTEXT` as required for `preflight-checks` alongside `GPS_RAW_INT`, but this table models exactly one message per row (and the wire's own `{featureKey: status}` map has no room for two rows under one key either) — `GPS_RAW_INT` was kept as the modeled signal, `STATUSTEXT` is not independently checked. And the battery row only expresses "is `SYS_STATUS` arriving at all", not the 45%-low-battery bar ANY-DRONE §S8.1 also names — `FeatureRequirement` has no field to carry a percentage threshold (only `minimumHz`/`requiredParameterName`), so that number cannot be represented in this table as it stands today.
- `V19__asset_usage_phase.sql` (docs/plans/active/DRONE-ONBOARDING-PLAN.md O5, D1/D2) — `ALTER TABLE asset_usages ADD COLUMN phase VARCHAR(20), ADD COLUMN first_armed_at TIMESTAMPTZ, ADD COLUMN last_disarmed_at TIMESTAMPTZ` — all three nullable, no backfill, same "unknown, not fabricated" discipline as `V6__telemetry_flight_state.sql`'s `flight_state` column. **Updated after O7 merged**: the migration originally shipped schema-only, deferring `phase` to O7 (`contexts/vision-warehouse`, running concurrently in a separate worktree) per the plan's module-placement table. O7 landed `AssetUsage`'s 9th component — `UsagePhase phase()` — but not `firstArmedAt()`/`lastDisarmedAt()`; those two fields don't exist on the domain record yet. This wave (O5) ended up owning the wiring after all: `AssetUsageEntity#phase`/`AssetUsageMapper` now map `phase` in both directions (see the entity's own field-bullet above and Tests below) — a cross-wave report caught that the column had gone live with no reader/writer, silently reverting every reload to `PREFLIGHT`. `first_armed_at`/`last_disarmed_at` remain schema-only, deliberately, still waiting on their domain fields. **Still true as of O11** (below) — that wave's `VehicleProfile`-based passport design does not need these two columns either, so they remain untouched; closing this gap properly still needs a `vision-warehouse` domain-field change, out of every persistence wave's own file scope so far.
- `V20__vehicle_profile_usage_link.sql` (docs/plans/active/DRONE-ONBOARDING-PLAN.md O11 — "the flight passport") — `ALTER TABLE vehicle_profiles ADD COLUMN usage_id UUID, ADD COLUMN phase VARCHAR(20)` — both nullable, no backfill, same additive-only shape as V19; `vehicle_profiles` stays append-only (every `save` still inserts a new row, this migration only widens what a row may optionally carry). `phase` is deliberately **not** constrained to `PREFLIGHT`/`POSTFLIGHT` at the schema level — enforced once in `DefaultVehicleProfileService#captureSnapshot` (`contexts/vision-flight`), the same "the allowlist is domain code, not a UI/schema convention" precedent D9 already set for `ParameterTier`. New index `idx_vehicle_profiles_usage_phase (usage_id, phase, observed_at DESC)` backs `findByUsageAndPhase`'s "newest tagged row for this usage+phase" query.
- `V21__db_audit_log.sql` (the database change audit — see that section below for the full picture) — `db_audit_log` (`id` `BIGINT GENERATED ALWAYS AS IDENTITY` PK — the one Postgres-generated, not Java-generated, id in this schema; `occurred_at` timestamptz `NOT NULL DEFAULT now()`; `table_name`/`row_id` text `NOT NULL`; `operation` `VARCHAR(6) CHECK (operation IN ('INSERT','UPDATE','DELETE'))`; `db_user` text `NOT NULL` — `session_user`, stamped by the trigger, never by the application; `old_row`/`new_row` jsonb, nullable; `changed_columns` jsonb, nullable). Plus one generic PL/pgSQL trigger function, `audit_row_change()`, and seventeen `AFTER INSERT OR UPDATE OR DELETE ... FOR EACH ROW` triggers attached to the control-plane tables enumerated in that section. New table + function + triggers, purely additive over V1-V20 — no existing table's own DDL changes, only its write behavior gains a side effect. Two indexes matching the two real read paths: `occurred_at` alone (newest overall) and `(table_name, row_id, occurred_at)` (newest for one row).

### `src/main/resources/db/seed/dev` — a second, conditional Flyway location

- `V90001__dev_accounts.sql` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1) — the DEV-ONLY
  `admin`/`admin` (ADMIN), `manager`/`manager` (MANAGER), `pilot`/`pilot` (PILOT) accounts, moved out
  of Java entirely (the deleted `AuthSeedRunner`, see station/vision-app/MODULE.md) and into a Flyway
  seed gated by `vision.persistence.seed-dev-users` (default `false`). `admin` reuses
  `DevPrincipal.USER_ID` (`UUID(0,0)`); `manager`/`pilot` get fixed, readable ids `UUID(0,2)`/`UUID(0,3)`
  chosen only for readability (a PK is scoped to its own table, so no collision risk with e.g.
  `map_layers`' own `UUID(0,2)` COP layer). All three join `V13`'s fixed root group. Password hashes
  are real BCrypt output from the app's own `BcryptPasswordHasher`/`BCryptPasswordEncoder` (default
  strength), generated once and pasted in literally — `DevAccountSeedMigrationTest` asserts each one
  verifies against its plaintext, so a hash that silently stopped matching could not hide here.
  `memberships` is `[{"groupId":{"value":"<uuid>"},"role":"<ROLE>"}]` — a single-element jsonb array —
  because `GroupId` is itself a one-component record and Hibernate's Jackson-3-backed `FormatMapper`
  nests a one-component record as `{"value":...}`, not a bare string; verified empirically against a
  real round trip through `JpaUserRepository`, not assumed by inspection.
  **Guard shape (docs/plans/active/POSTGRES-ONLY-CONTEXT.md upgrade path, defect 1):** each account is
  a separate `INSERT INTO users (...) SELECT ... WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = ...
  OR username = ...)`, not the single multi-row `INSERT ... VALUES ... ON CONFLICT (id) DO NOTHING`
  this migration originally shipped with. `ON CONFLICT` accepts exactly one conflict target, and
  `users.username` carries its own `UNIQUE` constraint (`V8__users_groups.sql`) independent of `id` —
  a database that already ran the deleted `AuthSeedRunner` (every pre-this-fix `docker-compose.yml`
  deployment) has `admin`/`manager`/`pilot` rows at *different*, random ids, so `ON CONFLICT (id)`
  alone did not see them as conflicts and the `INSERT` raised "duplicate key value violates unique
  constraint `users_username_key`" — reproduced against a real Postgres with V8's exact DDL before
  this fix landed, not assumed. Three `WHERE NOT EXISTS` guards (id OR username) replace the one
  `ON CONFLICT`, and are the only change — ids, hashes, and the `memberships` jsonb are byte-for-byte
  the same literals as before.
- **This location is not `db/migration` because it must be entirely absent from Flyway's
  `locations` list when `seedDevUsers` is `false`** (`PersistenceUnit.start` only adds
  `classpath:db/seed/dev` conditionally) — a flag-gated `WHERE`/`CASE` inside an unconditionally-run
  migration cannot make a whole *migration* not exist, only make its effects conditional, and the
  W1 brief required the flag-off path to have **no trace** of these rows ever being considered, not
  just no rows.
- **Version `90001`, a deliberately reserved-high band, not "next free slot after V13".** A `V13.1`
  scheme (sorting right after `V13__identity_baseline.sql`) was tried first and **measured to fail**
  against a real Postgres: `classpath:db/migration` reaches V15 in the same working tree (W3's
  concurrent wave), so by the time an operator actually flips `seedDevUsers` on, applying a
  lower-versioned migration than the highest already-applied one is out-of-order, and Flyway refuses
  that by default (`-outOfOrder=true` was rejected as the fix — a global setting that would also let a
  genuinely-misordered `db/migration` change slip through silently, not something to trade for one
  seed migration's convenience). A reserved high band sidesteps the problem entirely: this migration's
  version is always the highest resolved one, so it always applies next regardless of how far
  `db/migration` has moved, no out-of-order behavior needed anywhere.
- **That same high-band choice is exactly why suppressing Flyway's validation on flag on→off needs
  `ignoreMigrationPatterns("*:future", "*:missing")`, not just `"*:missing"`.** Once `db/seed/dev`
  drops out of `locations`, Flyway must classify the now-orphaned `V90001` row in
  `flyway_schema_history` as either `MISSING_SUCCESS` (orphaned version *below* the highest still-
  resolvable one) or `FUTURE_SUCCESS` (orphaned version *at or above* it) —
  `BaseAppliedMigration#getMissingState` branches on exactly that comparison, confirmed by decompiling
  `flyway-core-12.4.0.jar` with `javap`, not assumed from the method's name. Because `V90001` is
  chosen to always sort above `db/migration`'s own highest version, the state it lands in once orphaned
  is *always* `FUTURE_SUCCESS`, never `MISSING_SUCCESS` — a bare `ignoreMigrationPatterns("*:missing")`
  (this migration's own first-reading guess) compiled cleanly and looked reasonable but silently
  matched nothing, and only failed loudly once tested against a live database rather than reasoned
  about. `"*:future"` is also Flyway's **own built-in default** ignore pattern (`FlywayModel`'s
  constructor sets it before any caller-supplied value; confirmed via `javap`, not the changelog) —
  calling `ignoreMigrationPatterns(...)` at all replaces that default outright, so it has to be
  restated explicitly here rather than assumed to still apply. See `PersistenceUnit`'s own javadoc and
  `DevAccountSeedMigrationTest` for the full account.

## Database change audit

A durable, unbypassable record of what actually changed in Postgres, row by row — **not** the same
thing as `AuditTrailPort`/`audit_entries` (`V14__audit_trail.sql`, `AuditEntryEntity`/`JpaAuditTrail`,
see that entry above), and the two must never be merged or made to duplicate each other:

- `AuditTrailPort`/`audit_entries` answers **"which user did what to the fleet"** — a domain-intent
  record, written only where an application service remembers to call `AuditTrailPort#record`. It has
  an `actorId`, an `AuditAction`, a human `summary`. It can be silently absent for any write path nobody
  wired it into.
- `db_audit_log` (`V21__db_audit_log.sql`) answers **"which rows in this database changed, when, and
  how"** — including a change made by a DBA typing SQL into `psql`, by a future migration, or by any
  application code path that forgot to call `AuditTrailPort`. It has no notion of "user" beyond
  `session_user`, no `AuditAction`, no summary — only the mechanical fact of an INSERT/UPDATE/DELETE and
  the row image(s) involved. It is infrastructure, not domain, and nothing outside this module should
  ever import a type from it.

**Mechanism: a Postgres trigger, not a Java/Hibernate interceptor.** An interceptor can always be
bypassed — manual SQL, a `psql` session, a future non-Hibernate writer — and an audit that can be
bypassed is not an audit. `PersistenceUnit` also bootstraps Hibernate natively with no Spring in front of
it (see "Bootstrap and connection pool" below), so there is no framework-level hook here that would be
any cleaner than a trigger anyway. One generic PL/pgSQL function, `audit_row_change()`, is attached to
every audited table below via a per-table `AFTER INSERT OR UPDATE OR DELETE ... FOR EACH ROW` trigger —
one function, not one per table: it resolves each table's own primary-key column(s) from the catalog
(`pg_index`/`pg_attribute`) at trigger time, so a composite-key join table (`device_capabilities`,
`asset_devices`, `pilot_assignments`, `map_layer_grants`) and a single-UUID-PK table (`assets`, `users`,
...) are both handled by the same function — `row_id` is always text, built by `:`-joining the
primary-key column values in their declared key order, even though every id in this schema is in fact a
UUID (CLAUDE.md rule 1: no per-table logic duplicated seventeen times where one generic rule already
covers it). For an `UPDATE`, the function also diffs `new_data`/`old_data` key by key and records exactly
which columns changed as `changed_columns` — the "what changed?" question answerable without the caller
diffing two JSON blobs itself.

**Included — the control-plane / configuration tables**, enumerated by reading every migration V1
through V20, not guessed:

| Table | Migration | Character |
|---|---|---|
| `categories` | V1 | fleet taxonomy |
| `devices` | V1 | device inventory |
| `device_capabilities` | V1 | device→capability join (element collection) |
| `assets` | V1 | asset inventory |
| `asset_devices` | V1 | asset→device join (element collection) |
| `asset_usages` | V3 | a flight session record — one row per session plus a handful of updates, not a per-sample event stream |
| `geofence_zones` | V7 | geofence configuration |
| `groups` | V8 | org-chart nodes |
| `users` | V8 | identity aggregate |
| `pilot_assignments` | V9 | pilot→asset join |
| `marks` | V10 | tactical marks (the shared operational picture) |
| `datasets` | V11 | training dataset definitions |
| `map_layers` | V12 | map layer definitions |
| `map_layer_grants` | V12 | layer access grants — explicitly called out in `MapLayerEntity`'s own note above as "the one collection whose individual rows are a security decision," worth its own trigger even though it is an `@ElementCollection` join table |
| `map_drawings` | V12 | map drawings |
| `vehicle_profiles` | V17 | vehicle capability/parameter observations |
| `feature_requirements` | V18 | seed-only today, but exactly the "someone edited it by hand" case this feature exists to catch |

**Excluded — the high-volume append-only event tables, plus tables where a trigger would be actively
wrong**: a trigger here would double the hottest write paths in the system and drown the log in rows
nobody will ever read.

| Table | Migration | Reason |
|---|---|---|
| `telemetry_samples` | V3 | per-sample event stream |
| `detection_results` | V3 | per-frame event stream |
| `detection_events` | V15 | debounced detection event stream |
| `training_samples` | V11 | one row per captured training frame — the same append-heavy character as `detection_results` |
| `sample_images` | V11 | training-frame image bytes, tied to the above |
| `asset_images` | V5 | grouped with `sample_images` rather than with the control-plane set it might otherwise resemble: `to_jsonb()` on a row with a `bytea` column duplicates the whole image into every audit row it writes, and a fleet photo is content, not a configuration value this feature needs to answer "who changed X" for |
| `audit_entries` | V14 | the existing domain audit trail; auditing an audit trail is not useful, and its own write character already matches this excluded set |
| `db_audit_log` | V21 | this table itself — a trigger on itself would recurse |
| `flyway_schema_history` | (Flyway) | schema-management bookkeeping, not application data |

**Coverage is tested against the live schema, not trusted from the migration's own comment.**
`DbAuditLogCoverageTests` (Tests below) reads `information_schema.tables`/`pg_trigger` directly and
asserts the live table set matches these two lists exactly, both directions — a future migration that
adds a table without updating either list fails that test until someone consciously classifies the new
table, and a stray trigger on an excluded table fails it too.

**No `vision.persistence.*` flag governs any of this, deliberately.** Trigger installation is schema, not
application configuration — a Spring property on the JVM side has no way to make a Postgres trigger
conditional, and pretending otherwise (e.g. an unused `vision.persistence.db-audit.enabled` nobody reads)
would be worse than no flag at all. If the audited/excluded table sets ever need to change, that is a new
migration (`V22` dropping/adding a trigger), not a config flip.

**Retention: an explicitly open item, not addressed by this migration.** `db_audit_log` grows unbounded —
the same accepted tradeoff `audit_entries` already makes (see "Retention" below and `JpaAuditTrail`'s own
javadoc: "an audit trail that can be edited is not an audit trail," the same reasoning extends to one that
evicts itself on a timer). No purge/rollup job exists. Whoever eventually needs one should decide (a) how
long a change record must survive before it is safe to summarize/drop, and (b) whether that decision
belongs in this module (a scheduled prune, mirroring nothing else this module does today — every existing
retention mechanism here prunes *on write*, not on a timer) or in an operational tool outside the JVM
entirely (e.g. a partition-and-drop strategy driven by `pg_partman` or a cron `DELETE`). Flagged here
rather than guessed at.

**Read side**: `DbAuditLogEntity`/`JpaDbAuditLogRepository` (see API surface above) — entirely inside this
module, no port, no domain type anywhere learns about a database row. If a genuine consumer outside this
module ever needs this data, that consumer's own wave should decide whether a port is warranted; it was
not added speculatively here.

## Bootstrap and connection pool

`PersistenceUnit.start` uses Hibernate's **native** bootstrap API (`org.hibernate.cfg.Configuration`) rather than JPA's `Persistence.createEntityManagerFactory` (which needs a `META-INF/persistence.xml` or a hand-built `PersistenceUnitInfo`) or Spring Data JPA (`@EnableJpaRepositories`, Spring Boot's `HibernateJpaAutoConfiguration`, etc.). `Configuration#buildSessionFactory()` returns `org.hibernate.SessionFactory`, which **implements `jakarta.persistence.EntityManagerFactory` directly** (same for `Session`/`EntityManager`) — so every `Jpa*Repository` still only ever calls plain `jakarta.persistence` API, and callers (vision-app) hold a completely standard `EntityManagerFactory` reference with no Hibernate-specific type leaking across the module boundary.

This was a deliberate choice over Spring Data JPA: this codebase's adapters are plain classes constructed via `new` in `ApplicationServiceWiring`/`PersistenceWiringConfiguration` (see vision-app's Bean inventory), never Spring-component-scanned — Spring Data repository interfaces are proxies the Spring Data repository factory generates at runtime and cannot be `new`'d, which would have forced `@EnableJpaRepositories` + Spring Boot's JPA autoconfiguration into the picture, and those autoconfigurations activate purely from classpath presence (`@ConditionalOnClass(DataSource.class)`, etc.) — meaning they would have needed to be pulled unconditionally into vision-app's `@SpringBootApplication`, a materially more complex (and more fragile) wiring story than the plain, straight-line `new Jpa*Repository(entityManagerFactory)` this module's plain-JPA approach allows (see station/vision-app/MODULE.md's `PersistenceWiringConfiguration` entry — unconditional since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b, so this argument no longer even turns on a toggle).

**Connection pool (docs/plans/active/SCALE-100-PLAN.md S3), correcting this section's own earlier claim.** This
section used to say the swap to a pooled provider was "a config-only change" whenever "concurrency
ever demands it" — verified false on 2026-08-17 (`mvn dependency:list` showed `com.zaxxer:HikariCP`
was never on this module's classpath, transitively or otherwise) and superseded by this wave: `start`
now builds one `HikariDataSource` (via `HikariConfig`, sized from `PersistencePoolSettings`, see API
surface above) and **shares it between Flyway and Hibernate** — `Flyway.configure().dataSource(...)`
migrates through it, then it is handed to Hibernate as the live `hibernate.connection.datasource`
object (not a JNDI name — `Configuration#getProperties()` is a raw `Hashtable`, so `.put(Object,
Object)` accepts a `DataSource` directly, bypassing `Properties#setProperty`'s String-only signature)
with `hibernate.connection.provider_class` pointed at `ClosingDatasourceConnectionProvider`. Two
things this rules out as *the* mechanism, deliberately: (1) Hibernate's own `HikariCPConnectionProvider`
(from `hibernate-hikaricp`, `hibernate.hikari.*` properties) was **not** used to build the pool, because
it always builds its own second, independent `HikariDataSource` internally — there would be no way to
hand that same instance to Flyway first, and Flyway must finish migrating before the
`EntityManagerFactory` (and therefore Hibernate's internal pool) exists at all; `hibernate-hikaricp` is
still a declared dependency (see "Depends on" above) purely for its Hibernate-version-matched
`com.zaxxer:HikariCP` version pin. (2) Hibernate's built-in `DriverManagerConnectionProvider` — the
actual pre-S3 default, one physical JDBC connection per `EntityManager`, logging `HHH10001002: Using
built-in connection pool (not intended for production use)` at startup — is gone; that warning no
longer appears (grepped a full `-pl storage/persistence test` log: zero occurrences, vs. 19
occurrences of `ClosingDatasourceConnectionProvider` being wired in its place, once per
`EntityManagerFactory` the suite builds). The pool closes when the caller closes the
`EntityManagerFactory` it came from (`entityManagerFactory.close()` → Hibernate's service registry
`stop()`s every `Stoppable` service, including the connection provider) — `ClosingDatasourceConnectionProvider#stop()`
closes the underlying `HikariDataSource` there, extending (not changing) the pre-existing "caller owns
the `EntityManagerFactory` lifecycle" contract to also mean "and therefore the pool." `PostgresDockerIntegrationTest$ConnectionPoolTests`
proves both halves: the wired-provider-class assertion, and a tiny two-connection pool actually
refusing a third concurrent `EntityManager` (via `HibernateException`, inside the pool's own
`connectionTimeout`, not a hang) — see Tests below. `JpaOperations`'s per-call `EntityManager` open/close
pattern (Gotchas, below) is unchanged by this wave and remains the residual concurrency cost the pool
now merely *bounds* rather than eliminates.

**The four `PersistencePoolSettings` values vision-app's wiring needs to expose**, none of them chosen by
this module (it only defines and validates the shape — see API surface above) — the orchestrator-owned
`VisionPersistenceProperties`/`PersistenceWiringConfiguration` binds `application.yaml` keys under
`vision.persistence.pool.*` (naming matches docs/plans/active/SCALE-100-PLAN.md §6) and constructs the record:

| YAML key | Default | Record field |
|---|---|---|
| `vision.persistence.pool.max-size` | `20` | `maximumPoolSize` |
| `vision.persistence.pool.min-idle` | `5` | `minimumIdle` |
| `vision.persistence.pool.connection-timeout-ms` | `30000` | `connectionTimeoutMillis` |
| `vision.persistence.pool.leak-detection-threshold-ms` | `30000` | `leakDetectionThresholdMillis` |

Every default above is `PersistencePoolSettings.defaults()`, so an app that does not set any of these
keys behaves exactly as it did before this wave (opt-in guardrail: unset config is a no-behavior-change
default, only the unpooled-vs-pooled connection mechanics change underneath it).

`hibernate.hbm2ddl.auto=validate`: Flyway owns schema creation/evolution end to end; Hibernate only ever validates its entity mapping matches what Flyway already created, never generates or alters DDL itself.

## Conventions

- **jsonb via Hibernate's native JSON support, not a hand-rolled converter.** `attribute_hints`/`stream_options`/`attributes`/`extra`/`detections`/`flight_state`/`polygon`/`memberships`/`points`/`details` (`audit_entries`, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3) are `@JdbcTypeCode(SqlTypes.JSON)` fields with `columnDefinition = "jsonb"` — Hibernate 7.4 auto-detects a Jackson `ObjectMapper` on the classpath via its `FormatMapper` SPI and ships `org.hibernate.type.format.jackson.Jackson3JsonFormatMapper` specifically for Jackson 3 (`tools.jackson.*`, this house's Jackson generation under Spring Boot 4) — confirmed present in the `hibernate-core-7.4.1.Final` jar. No `AttributeConverter`, no `PGobject` juggling, no `stringtype=unspecified` JDBC-URL trick.
- **Tracks ride the existing `detections` jsonb — there is no `tracks` table and no migration for them** (docs/plans/done/TRACKING-PLAN.md §4.C). `Detection` gained a nullable `TrackRef` component (track id, lifecycle state, source, velocities, age), and because the column already stores the whole record tree, it round-trips for free. **Consequences, stated rather than discovered later:** (1) tracks are **not SQL-queryable** — you cannot ask "where was track #7" without scanning and deserializing blobs, exactly as label filtering already scans in Java; a durable, indexed trajectory table is deferred to S2 (docs/main/TWO-TARGETS-PLAN.md), which is the first thing that would actually issue that query, and building the index now would be building it for nobody. (2) Rows written **before** the tracking wave still deserialize with `track` reading `null` — verified against a hand-written pre-tracking jsonb literal inserted through native SQL, not assumed (`preTrackingJsonbRowsStillDeserializeWithTrackReadingNull`). (3) `DetectionResult#tracking()` — the **per-frame** duty-cycle telemetry, as opposed to the per-detection `TrackRef` — has no column and is **deliberately not persisted**; it reads back `null`. That is a documented drop rather than the silent kind docs/extracts/TRACKING-ORCHESTRATION.md §6 rule 6 warns about: the counters it feeds are a live read model (`TrackingStatsWindow`, vision-application), and persisting them belongs with S2's trajectory table.
- **`DetectionResultEntity#detections`/`TelemetrySampleEntity#flightState` store the domain `Detection`/`FlightState` record trees directly** (`List<Detection>` with nested `BoundingBox`/`ModelRef`; a single nullable `FlightState` with its own `List<String> armingBlockers`, docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b) rather than a parallel adapter-local DTO shape — Jackson 3 serializes/deserializes Java records natively (canonical-constructor + component-name introspection, no annotations needed), proven by this module's own round-trip tests. Referencing a plain, framework-annotation-free domain record from an entity field is the same kind of "adapter depends on domain types" the enum reuse below already establishes; it's storage-format coupling to the domain's shape, not a framework leaking into the domain.
- **No cross-entity foreign keys beyond the join tables' own PKs**, deliberately: `categories.parent_id` is the one exception (self-referencing, satisfiable because `V2__seed_categories.sql` controls insert order), but `assets.category_id` has **no** FK to `categories.id`, `asset_devices.device_id` has **no** FK to `devices.id`, and none of `asset_usages`/`telemetry_samples`/`detection_results` (V3) has any FK at all. This traces back to a now-closed constraint: the in-memory reference repositories this adapter had to stay behavior-compatible with (`InMemory*Repository`, vision-app devsupport, deleted docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b) performed zero referential checks, and a real FK here would have rejected operations (e.g. saving an `Asset` whose category was never separately saved) that the in-memory port happily allowed. The in-memory parity target is gone, but the schema itself is frozen (no `V17+` migrations without a dedicated task) — so the permissive shape stands as today's actual contract regardless of why it was first chosen.
- **`save()` is upsert-by-id** (`EntityManager#merge`) on the three P-a ports and `JpaAssetUsageRepository`, matching each repository port's upsert-by-id contract exactly. **`JpaTelemetryRepository#save`/`JpaDetectionRepository#save` always `persist` a brand-new row** instead (never `merge`) — samples/results are immutable historical records per their ports' contracts, and neither `Telemetry` nor `DetectionResult` carries an id to merge by. `JpaAuditTrail#record` follows the same always-`persist` rule for the same reason (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3 — entries are immutable historical facts). `JpaDetectionEventRepository#save` is the one exception on the history side: it `merge`s (upsert-by-id), because unlike a `DetectionResult`/`AuditEntry`, a `DetectionEvent` genuinely mutates over its own open lifetime (`lastSeen`/`peakConfidence` advance, then it closes) and carries a stable id to upsert by. **`deleteById()` is a real hard delete, idempotent** (missing id ⇒ no-op) on `Device`/`Asset` — soft-delete (`LifecycleState.DELETED`) is just a column value round-tripped like any other field; nothing in this module treats it specially.
- **Domain enums (`Capability`, `LifecycleState`) are reused directly** in `@Enumerated(EnumType.STRING)` entity fields rather than duplicated as adapter-local enums kept in sync by hand — the framework annotation lives on the entity's *field*, not on the domain enum's *declaration*, so `vision-domain` stays annotation-free (`ArchitectureTest#domainAndApplicationAreSpringAnnotationFree` — note: JPA's `jakarta.persistence`/`org.hibernate.*` annotations aren't `org.springframework..` either way, but the same "domain must not import framework code" principle applies and is respected).

## Retention (docs/plans/done/MVP2-PLAN.md P-b)

`JpaTelemetryRepository`/`JpaDetectionRepository` each prune their oldest rows **on every write**, inside the same transaction as the insert — the simplest mechanism that is still correct, chosen over a scheduled/background sweep (one more moving part, one more thing to wire and test) or a database-side trigger (schema magic invisible to the Java code reading it):

1. `save` calls `EntityManager#persist` for the new row, then an explicit `EntityManager#flush()`.
2. A native `DELETE ... WHERE <key> = ? AND id NOT IN (SELECT id WHERE <key> = ? ORDER BY <timestamp> DESC LIMIT <cap>)` runs in the same transaction, keeping only the newest `<cap>` rows for that key.

The explicit `flush()` between `persist` and the native delete is required, not decorative: Hibernate has no way to know a hand-written native query touches `telemetry_samples`/`detection_results`, so without it the delete would run against the connection's pre-insert view of the table — once a usage/stream is already at capacity, that would prune the row just being appended instead of an older one.

`JpaTelemetryRepository`'s key is `usage_id` (matching `AssetUsageRepositoryPort`'s grouping); `JpaDetectionRepository`'s key is `stream_id` — the only grouping key `DetectionResult`/`DetectionQuery` actually carry (there is no `usageId` on a detection). Both default to **100,000 rows** (`DEFAULT_RETENTION_LIMIT_PER_USAGE`/`DEFAULT_RETENTION_LIMIT_PER_STREAM`) — generous (≈27h of continuous 1Hz telemetry for one usage; ≈2.75h of continuous 10fps detections for one stream) but finite, so a usage/stream nobody ever stops (e.g. a forgotten dev-mode stream) cannot grow either table unboundedly. Each repository also has a two-argument constructor (`EntityManagerFactory, int`) for overriding the cap — used by this module's own retention tests to exercise pruning without inserting six figures of rows first; **not currently wired to a `vision.persistence.*` Spring property** (see Status's honest gaps for why). `JpaTelemetryRepository`'s prune-per-write mechanism above describes its **immediate**-mode behavior; docs/plans/active/SCALE-100-PLAN.md S4's batching (below) changes "every write" to "every flushed batch" — see "Batching (SCALE-100-PLAN S4)".

`JpaAssetUsageRepository` has **no** retention pruning: a usage row is written once per start/stop plus a handful of position/sample-count updates in between, not once per incoming sample — it is not the "append-heavy" table docs/plans/done/MVP2-PLAN.md P-b's retention guard targets.

**`JpaDetectionEventRepository`** (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3) follows the identical mechanism and shape as `JpaDetectionRepository` above — same `stream_id` grouping key (the only one `DetectionEvent` carries), same `DEFAULT_RETENTION_LIMIT_PER_STREAM = 100_000`, same two-argument test-override constructor, same `merge` → `flush()` → native-delete-ordered-by-`last_seen`-desc sequence, one difference: the write being flushed is a `merge` (upsert), not a `persist`, since `save` here can be replacing an existing row rather than always adding one. **Deliberately not the in-memory ring's 500-per-stream cap** the now-deleted `InMemoryDetectionEventRepository` (vision-app devsupport, removed docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b) used — that cap existed to bound heap in a devsupport fallback, not to express a real retention policy; reproducing it verbatim in a durable table would evict events far sooner than the platform actually needs to. This was a choice between the two existing "constructor-argument row cap" patterns this module already has (`JpaDetectionRepository`/`JpaTelemetryRepository`) rather than a third mechanism — `JpaDetectionRepository`'s `stream_id` key is the closer match (detection events group by stream, not by usage), so that is the one followed.

**`JpaAuditTrail` has no retention pruning at all** — see its own entry above in "API surface": an audit trail that evicts its own oldest rows on a timer is not the durability guarantee the port exists to provide (`AuditTrailPort`'s own javadoc: "an audit trail that can be edited is not an audit trail" — the same reasoning extends to one that quietly forgets). Unbounded growth here is an accepted tradeoff at this platform's scale, the same posture `JpaAssetUsageRepository` already takes for its own low-write-volume table.

## Batching (docs/plans/active/SCALE-100-PLAN.md S4)

`JpaTelemetryRepository#save` no longer necessarily does one `persist` + `flush()` + prune per sample — the hot ingest path's per-sample round trip this wave targets. A new `repository.TelemetryBatchSettings(int batchSizeSamples, long batchWindowMillis)` record (compact-constructor-validated, `defaults()`/`immediate()` factories, same shape as `config.PersistencePoolSettings`) governs it:

- **Immediate mode** (`TelemetryBatchSettings.immediate()` → `(1, 0)`, `isImmediate()` true when `batchWindowMillis == 0`) reproduces the pre-S4 behavior exactly: every `save` persists, flushes, and prunes before returning. The one- and two-argument constructors both resolve to this, so **every pre-S4 caller and test keeps its synchronous read-after-write behavior byte-identical** — this wave changes nothing observable in default configuration.
- **Batched mode** (the three-argument constructor, non-immediate settings) buffers samples per `usage_id` in a `ConcurrentHashMap<UUID, PendingBatch>` and flushes (one `persist` per buffered sample, one `flush()`, one prune — the whole point) the instant `batchSizeSamples` accumulate for a usage, or `batchWindowMillis` have elapsed since the first still-buffered one for that usage, whichever comes first. The time bound is armed via a dedicated single-thread daemon `ScheduledExecutorService` (`"telemetry-batch-flush"`), created only when the settings are non-immediate — an immediate-mode instance starts none.
- **Both flush paths *evict* their map entry, they do not merely empty it.** Buffer mutation only ever happens inside a `ConcurrentHashMap#compute` on the usage key — which is what makes `PendingBatch`'s fields safe without their own lock (compute serializes every writer and the flusher on one key, so a batch cannot be appended to mid-drain) and what makes draining a *removal*: the lambda returns `null`. Leaving drained-but-present entries behind would grow this map for the life of the JVM, one per flight ever flown — the same unbounded-map defect SCALE-100 fact 2f describes in `LiveUpdateRegistry`, reintroduced by the change meant to relieve that pressure. `int pendingBatchCount()` exposes the map's size so that invariant is testable rather than assumed (`bothFlushPathsEvictTheirBufferSoTheMapDoesNotGrowPerUsage`); it doubles as the honest "how much would a `kill -9` lose right now" number, and is always `0` in immediate mode. The DB write itself deliberately runs *outside* the lambda — `compute` holds a bin lock, and a JDBC round trip under it would serialize unrelated usages.
- **The trade-off, stated plainly:** a sample buffered but not yet flushed exists only in that repository instance's heap. A crash (`kill -9`, OOM, unclean restart) loses whatever is still buffered per open usage, bounded to at most one `batchWindowMillis` window's worth. `findByUsage` only ever sees flushed rows, so a read shortly after a still-buffered write can also lag by up to the same window — a documented consequence of the same trade-off, not a bug. CLAUDE.md rule 9 ("newest data wins, even if previous is still available") is why `DEFAULT_BATCH_WINDOW_MILLIS` is a small non-zero number rather than defaulting to zero-loss: a deployment that wants zero loss over ingest throughput sets the window to `0` explicitly (which reads as `isImmediate()`).
- **Tunable constants** (both in `TelemetryBatchSettings`, `repository` package) — not yet wired to a Spring property, flagged for S7 to lift into `vision.persistence.telemetry.*`:
  - `DEFAULT_BATCH_SIZE_SAMPLES = 100` — safety ceiling for an unusually high-rate source; a typical ~1Hz flight-controller feed produces far fewer samples than this within one window, so in practice the time bound is what decides when a batch actually flushes.
  - `DEFAULT_BATCH_WINDOW_MILLIS = 200L` — comfortably under docs/plans/active/SCALE-100-PLAN.md S4's 250ms crash-loss ceiling.
- **Not wired into production by this wave**: `PersistenceWiringConfiguration`'s `JpaTelemetryRepository` bean still uses the one-argument (implicitly-immediate) constructor — batching only takes effect once a caller explicitly passes non-immediate `TelemetryBatchSettings`, e.g. via the new three-argument constructor. `UsageTracker` (`contexts/vision-perception`) has a structurally parallel `UsageSummaryBatchSettings` for its own coalesced `AssetUsage` summary write (docs/plans/active/SCALE-100-PLAN.md S4 item 3) — a separate type in a separate module (this module cannot depend on a context module), meant to be wired from the *same* `vision.persistence.telemetry.batch-size`/`batch-window` property values so one number governs both write paths; see that module's own MODULE.md.

## Tests

`PostgresDockerIntegrationTest` — `@Testcontainers` + `@EnabledIf("dockerAvailable")`, using
Testcontainers' own canonical Docker-availability probe (`DockerClientFactory.instance().isDockerAvailable()`)
rather than re-implementing the `docker info` CLI-shelling idiom `adapter-rtsp`/`adapter-publish-hls`'s
`MediamtxDockerIntegrationTest` use — those predate any Testcontainers dependency in this repo; this
is the first module to add one, so it uses Testcontainers' own mechanism instead. Same
skip-cleanly-not-a-failure contract either way. One `postgres:16` container shared across the whole
class (`@Container static final PostgreSQLContainer POSTGRES` — **not generic** in Testcontainers 2.x,
see Gotchas), one `EntityManagerFactory` opened in `@BeforeAll`/closed in `@AfterAll`:

- `@Nested CategoryRepositoryTests` (5), `DeviceRepositoryTests` (6 incl. capability-set +
  stream-descriptor + lifecycle-state round trip and idempotent delete), `AssetRepositoryTests` (7
  incl. ownership/attributes/devices round trip, `findByDeviceId` found/not-found, idempotent
  delete, lifecycle-state-preserving upsert) — every P-a port method, upsert semantics,
  empty-`Optional` contract for unknown ids.
- `@Nested AssetUsageRepositoryTests` (12, up from 9 — docs/plans/done/MVP2-PLAN.md R-a2,
  docs/plans/active/DRONE-ONBOARDING-PLAN.md O5) — open/closed usage
  round trip (incl. null-position open usage and full-position closed usage), a
  `null`-`streamId` usage round-tripping as `null` and a `streamId`-carrying usage round-tripping
  exactly, upsert-that-closes-an-open-usage (now also asserting the recorded `streamId` survives
  the close/upsert), `findRecentByAsset` newest-first + bounded by limit, `findOpenByAsset`
  found/not-found — plus three new `phase` cases:
  `savedUsageWithANonDefaultPhaseRoundTripsExactly` (`UsagePhase.IN_FLIGHT` round trip end to end),
  `saveIsAnUpsertThatCanTransitionPhase` (re-saving the same id with a different phase overwrites
  it, proving the upsert covers `phase` too, not just the fields P-a already had), and
  `legacyRowWithNullPhaseColumnMapsToPreflightDefault` (native SQL forces the `phase` column back
  to `null` — the mapper itself can never write `null` since `AssetUsage#phase()` is non-null by
  construction — then reloads through the repository port and asserts the fallback is
  `UsagePhase.PREFLIGHT`, guarding against the exact regression this wave shipped and then fixed:
  a phase `UsageTracker` had actually computed and saved being silently discarded on reload).
- `@Nested TelemetryRepositoryTests` (10, up from 7 — docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b) — round trip
  with every field populated and with only the required fields, per-usage isolation,
  `findByUsageReturnsEarliestSamplesFirstUpToLimit` — proves `findByUsage`'s limit selects the
  *earliest* samples (mirroring `InMemoryTelemetryRepository`'s actual behavior, see
  `JpaTelemetryRepository`'s javadoc), not the newest — plus two new `flightState` round-trip cases:
  a full `FlightState` (including nested nullable sub-fields and a non-empty `armingBlockers`) round
  trips exactly, and a sample built via `Telemetry`'s 8-arg convenience ctor (no `flightState` at
  all) reads back with `flightState() == null`, the same honest-null contract a real pre-V6 row
  would also satisfy — plus two new docs/plans/active/SCALE-100-PLAN.md S4 batching cases:
  `batchedSaveDefersWritesUntilTheSizeBoundThenFlushesTogether` (a 3-sample size bound against a huge
  window: `findByUsage` sees nothing after two saves, all three together after the third trips the
  bound) and `batchedSaveIsDurableWithinTheConfiguredWindowEvenBelowTheSizeBound` (a below-size-bound
  save is invisible immediately, then durable after sleeping past a 100ms window) — the loss-bound
  proof the S4 brief requires, using the new three-argument constructor with explicit
  `TelemetryBatchSettings` — and `bothFlushPathsEvictTheirBufferSoTheMapDoesNotGrowPerUsage`, which
  pins the map-eviction invariant via `pendingBatchCount()` on *both* drain paths (the size bound,
  which drains inline on the caller thread, and the window, which drains on the scheduler) because
  they evict independently.
- `@Nested DetectionRepositoryTests` (8, up from 6 — docs/plans/done/TRACKING-PLAN.md wave T6) — round trip of
  detections + inference latency, `streamId`
  filter, `queryTimeRangeIsInclusiveOnBothEndsMatchingInMemoryBehavior` (proves `to` is treated as
  inclusive, mirroring `InMemoryDetectionRepository`'s actual behavior despite `DetectionQuery#to`'s
  javadoc calling it exclusive — see `JpaDetectionRepository`'s javadoc), label filter, newest-first
  ordering + limit, plus the two tracking cases:
  `preTrackingJsonbRowsStillDeserializeWithTrackReadingNull` — **the regression the "no migration"
  decision rests on**: a row whose `detections` jsonb was written before tracking existed (a
  hand-written literal with no `track` key, inserted through native SQL precisely so it is genuinely
  yesterday's bytes rather than today's serializer producing an absent field) still deserializes,
  with `Detection#track()` reading `null` and every other component intact — and
  `aTrackedDetectionRoundTripsThroughTheJsonbBlobWithNoMigration`, which saves a `TrackRef`-carrying
  detection, reads back every component of it, and pins the deliberate omission that
  `DetectionResult#tracking()` (per-frame telemetry, no column) reads back `null`.
- `@Nested AssetImageRepositoryTests` (4, docs/plans/done/UX-REWORK-PLAN.md §U-d item 3) — unknown asset id → empty `Optional` + `existsByAssetId` false, round trip of bytes + content type + `existsByAssetId` true, upsert-replaces (a second `save` for the same asset id fully replaces the first — different bytes, different content type), idempotent delete (also verifying `existsByAssetId` flips back to false, and a second delete call doesn't throw).
- `@Nested GeofenceRepositoryTests` (6, docs/plans/done/OPS-CORE-PLAN.md §G, G-b) — unknown id → empty `Optional`, a `KEEP_OUT` zone with an altitude ceiling round trips exactly, a `KEEP_IN` zone with no ceiling round trips `maxAltitudeMeters()==null`/`enabled()==false`, `save` upserts by id (rename/re-kind/re-altitude/re-enable in place, same id), `findAll` returns every saved zone, `deleteById` is idempotent (a second call on an already-deleted id doesn't throw).
- `@Nested UserRepositoryTests` (5, docs/plans/done/U-AUTH-PLAN.md wave 3) — unknown id/username → empty `Optional`, a user with jsonb memberships round trips (asserting the username reads back lower-cased and the `List<Membership>` survives), `findByUsername` is case-insensitive (`CaseTest`/`CASETEST`/`casetest` all resolve the same user), `save` upserts by id (display name/hash/enabled/memberships all replaced in place), `findAll` returns every saved user.
- `@Nested GroupRepositoryTests` (4, docs/plans/done/U-AUTH-PLAN.md wave 3) — unknown id → empty `Optional`, a root + child group round trip (child's `parentGroupId` preserved), `save` upserts by id, `findAll` returns every saved group.
- `telemetryRetentionPrunesOldestSamplesOnceCapExceeded`/`detectionRetentionPrunesOldestResultsOnceCapExceeded`
  — use each repository's small-cap constructor overload (cap 3) to insert 5 rows and assert exactly
  the 3 newest survive.
- `historyOfAFinishedUsageSurvivesAFreshEntityManagerFactory` — docs/plans/done/MVP2-PLAN.md P-b's done
  criterion in test form: writes a closed `AssetUsage` (now also carrying the same `streamId` as
  its detection, docs/plans/done/MVP2-PLAN.md R-a2 — the realistic shape) plus one telemetry sample and one
  detection result through the shared `EntityManagerFactory`, opens a fresh one against the same
  still-running container, and asserts all three round-trip.
- `assetSurvivesAFreshEntityManagerFactoryAgainstTheSameDatabase` — docs/plans/done/MVP2-PLAN.md P-a's done
  criterion in test form: writes an asset through the shared `EntityManagerFactory`, opens a
  **second, independent** one via `PersistenceUnit.start` against the same still-running container
  (Flyway's own history table makes the re-migration a no-op), reads the asset back through it, and
  asserts full equality — proving data survives a fresh application context, not just a fresh query
  within the same one.
- `v4MigrationAddsANullableStreamIdColumnOnTopOfV1ThroughV3` (docs/plans/done/MVP2-PLAN.md R-a2) —
  queries `information_schema.columns` directly for `asset_usages.stream_id` and asserts it is a
  nullable `uuid` column, proving `V4__usage_stream_id.sql` applied cleanly on top of the V1-V3
  schema every other test in this class already depends on (rather than only inferring the
  migration ran from a null-`streamId` round trip elsewhere).
- `v6MigrationAddsANullableFlightStateColumnOnTopOfV1ThroughV5` (new, docs/plans/done/FC-INTEGRATIONS-PLAN.md
  F-b) — same shape as the V4 test above, for `telemetry_samples.flight_state` (asserts nullable
  `jsonb`), proving `V6__telemetry_flight_state.sql` applied cleanly on top of V1-V5.
- `v7MigrationCreatesTheGeofenceZonesTableOnTopOfV1ThroughV6` (new, docs/plans/done/OPS-CORE-PLAN.md §G, G-b) —
  same shape as the V4/V6 tests above, for the brand-new `geofence_zones` table: asserts `polygon`
  is a required (`NOT NULL`) `jsonb` column and `max_altitude_meters` stays nullable, proving
  `V7__geofence_zones.sql` applied cleanly on top of V1-V6.

- `v8MigrationCreatesUsersAndGroupsOnTopOfV1ThroughV7` (docs/plans/done/U-AUTH-PLAN.md wave 3) — same shape
  as the V4/V6/V7 schema tests: asserts `users.memberships` is a required (`NOT NULL`) `jsonb` column
  and `groups.parent_id` is a nullable `uuid`, proving `V8__users_groups.sql` applied cleanly on top
  of V1-V7.
- `@Nested AssignmentRepositoryTests` (4, docs/plans/done/U-SCOPE-PLAN.md slice 2) — idempotent-upsert `assign`
  queryable both directions, idempotent-delete `unassign`, multiple pilots/assets tracked
  independently, unknown pilot/asset → empty sets / `isAssigned` false.
- `v9MigrationCreatesThePilotAssignmentsTableOnTopOfV1ThroughV8` (docs/plans/done/U-SCOPE-PLAN.md slice 2) —
  same shape as the V8 schema test: asserts `pilot_user_id`/`asset_id` are both required (`NOT NULL`)
  `uuid` columns and that the primary key is the **composite** of exactly those two columns
  (`information_schema` PK column count = 2), proving `V9__pilot_assignments.sql` applied on top of
  V1-V8.
- `@Nested MarkRepositoryTests` (6, docs/plans/done/TACTICAL-MARKS-PLAN.md §3/M2) — unknown id → empty
  `Optional`, a `MANUAL` mark with an altitude and a note round trips exactly, a `DETECTION`-sourced
  mark with no altitude/no note round trips `position().altitudeMeters()==null`/`note()==null`,
  `save` upserts by id (rename/re-kind/re-note/re-status/re-position in place, same id), `findAll`
  returns every saved mark, `deleteById` is idempotent (a second call on an already-deleted id
  doesn't throw).
- `v10MigrationCreatesTheMarksTableOnTopOfV1ThroughV9` (docs/plans/done/TACTICAL-MARKS-PLAN.md §3/M2) — same
  shape as the V7/V8/V9 schema tests, for the brand-new `marks` table: asserts `altitude_meters`/
  `note` stay nullable while `latitude` is required, proving `V10__marks.sql` applied cleanly on top
  of V1-V9.
- `@Nested DatasetRepositoryTests` (6, docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3) — unknown id → empty
  `Optional`, a dataset with a target category and classes round trips exactly, a dataset with no
  target category and no classes round trips `targetCategory()==null`/`classes().isEmpty()`, `save`
  upserts by id (rename/re-status/re-classes in place, same id), `findAll` returns every saved
  dataset, `delete` is idempotent (a second call on an already-deleted id doesn't throw).
- `@Nested TrainingSampleRepositoryTests` (8, docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3) — unknown id →
  empty `Optional`, a sample with annotations round trips exactly, a sample with no asset id and no
  annotations round trips `assetId()==null`/`annotations().isEmpty()`, `save` upserts by id
  (PENDING→LABELED, annotations replaced, `labeledBy`/`labeledAt` stamped, same id),
  `findByDataset` filters by status and bounds by limit, `countByDataset` matches
  `findByDataset`'s own filter without loading rows, both are empty/zero for an unknown dataset,
  `delete` is idempotent.
- `@Nested SampleImageStoreTests` (4, docs/plans/done/CV-TRAINING-PLAN.md §1/§C, Wave T3) — unknown sample id
  → empty `Optional`, bytes + content type round trip exactly, `save` upserts (a second `save`
  fully replaces the first), `delete` is idempotent.
- `v11MigrationCreatesTheTrainingDatasetsTablesOnTopOfV1ThroughV10` (docs/plans/done/CV-TRAINING-PLAN.md §1,
  Wave T3) — same shape as the V7/V8/V9/V10 schema tests, for the three brand-new training-pipeline
  tables: asserts `datasets.target_category` and `training_samples.asset_id` stay nullable while
  `training_samples.stream_id` and `sample_images.data` (`bytea`) are required, proving
  `V11__training_datasets.sql` applied cleanly on top of V1-V10.
- `@Nested MapLayerRepositoryTests` (7, docs/plans/done/MAP-REWORK-PLAN.md Wave C) — unknown id → empty
  `Optional`, a layer with both a USER and a GROUP grant round trips exactly (grants compared as a
  `Set`, since the element-collection table imposes no row order), a COP layer with no grants round
  trips with an empty grant list, **`save` replaces the grant list wholesale rather than merging it**
  (a dropped grant is gone, and a surviving subject's *level* is the new one — the property
  `MapLayerService#setGrants` depends on), `save` upserts by id, `findAll` returns every saved layer,
  and `deleteById` is idempotent **and leaves no orphan `map_layer_grants` row** (asserted with a
  direct native `count(*)`, since the FK cascade is the only thing standing between a deleted layer
  and stale grant rows).
- `@Nested DrawingRepositoryTests` (6, docs/plans/done/MAP-REWORK-PLAN.md Wave C) — unknown id → empty
  `Optional`, a POLYGON with label + colorToken + a mixed-altitude vertex list round trips exactly,
  a LINE with neither label nor colorToken round trips both as `null`, `save` upserts by id and
  **replaces geometry wholesale** (2 points → 3, not appended), `findAll` returns every saved
  drawing, `deleteById` is idempotent.
- `MarkRepositoryTests` grew 6→7 for the reworked `Mark`: every fixture now carries a `LayerId`, an
  `Affiliation` and a `Verification`; a new `confirmedMarkRoundTripsItsReviewerAndReviewInstant`
  covers the `CONFIRMED` branch of the flattened verification triple; and the upsert case now also
  asserts that **promotion (`withLayer`) and review both survive the upsert**, since those are the
  two mutations that go through `save`-over-the-same-id.
- `v12MigrationCreatesTheMapTablesAndBackfillsMarksOnTopOfV1ThroughV11` (docs/plans/done/MAP-REWORK-PLAN.md
  Wave C) — same `information_schema` shape as the V7-V11 schema tests: `map_drawings.points` is a
  required `jsonb` and `color_token` stays nullable; `map_layer_grants`' primary key is the
  **composite of exactly three columns**; `marks.layer_id`/`marks.affiliation` are `NOT NULL` (i.e.
  the backfill ran) while `verified_by` stays nullable. It also asserts the in-migration COP layer
  row exists at its fixed id with `kind='COP'` and the system `UUID(0,0)`/`UUID(0,1)` ownership —
  the row every pre-existing mark was backfilled onto, and the one `LayerResolver#copLayerId()`
  must find rather than duplicate.
- `@Nested AuditTrailRepositoryTests` (4, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3) — a recorded entry
  round trips every field (including jsonb `details`) via `findByTarget`, `findRecent` spans every
  target newest-first bounded by limit (own-rows-within-a-large-fetch technique, same as
  `AssetUsageRepositoryTests#findRecentReturnsNewestFirstAcrossEveryAssetBoundedByLimit`),
  `findByTarget` returns only that target's entries newest-first bounded by limit (an unrelated
  target's entry is excluded), `findByActor` returns only that actor's entries newest-first (another
  actor's entry on the same targets is excluded).
- `@Nested DetectionEventRepositoryTests` (7, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3) — an open event
  with a position round trips every field via `findByStream`, an event with no asset and no position
  round trips both as `null`, **`save` is a genuine upsert**
  (`saveIsAnUpsertThatAdvancesLastSeenAndPeakConfidenceThenCloses` — the same id saved three times
  as it advances then closes leaves exactly one row, with the final closed values), `findByStream`
  on an unknown stream is empty, `findByStream` is newest-first bounded by limit, `findRecent` spans
  every stream newest-first bounded by limit (same own-rows technique as `AuditTrailRepositoryTests`
  above), and `findRecent`'s `sinceInclusive` excludes strictly-before events while including the
  boundary instant itself.
- `v14MigrationCreatesTheAuditEntriesTableOnTopOfV1ThroughV13` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3) —
  same `information_schema` shape as the V7-V12 schema tests: `audit_entries.details` is a required
  `jsonb` column, `target_id` is required, proving `V14__audit_trail.sql` applied cleanly.
- `v15MigrationCreatesTheDetectionEventsTableOnTopOfV1ThroughV14` (docs/plans/active/POSTGRES-ONLY-CONTEXT.md
  W3) — same shape as the V14 test above: `detection_events.asset_id`/`position_latitude` stay
  nullable while `last_seen` is required, proving `V15__detection_events.sql` applied cleanly.
- `v19MigrationAddsNullablePhaseColumnsOnTopOfV1ThroughV18` (docs/plans/active/DRONE-ONBOARDING-PLAN.md
  O5, pre-existing, not added by O11) and `v20MigrationAddsUsageIdAndPhaseColumnsOnTopOfV1ThroughV19`
  (O11, new) — same `information_schema.columns.is_nullable` shape as the V7-V15 tests above, proving
  `usage_id`/`phase` on `vehicle_profiles` are nullable, additive, no-backfill columns. (`V16`-`V18`
  have no dedicated top-level schema test in this file — a pre-existing gap in this section, not one
  this wave introduced or was in scope to backfill; their round trips are proven instead through
  `VehicleProfileRepositoryTests`/`FeatureRequirementRepositoryTests` below.)

`@Nested VehicleProfileRepositoryTests` (10, up from 5 — docs/plans/active/DRONE-ONBOARDING-PLAN.md O5/O11):
the original 5 (`findLatestReturnsEmptyForUnknownDevice`, complete/incomplete round trip,
append-only-plus-newest-wins, per-device isolation) plus 5 new O11 cases for the tagged
`save(DeviceId, UsageId, FlightPhase, VehicleProfile)`/`findByUsageAndPhase(UsageId, FlightPhase)`
pair: empty-when-neither-captured, a full-field tagged round trip, not-found-under-a-different-phase-
of-the-same-usage, not-found-under-a-different-usage-with-the-same-phase, and
`findByUsageAndPhaseDistinguishesAllFourCellsAcrossTwoFlights` (two flights of one asset, each with
its own PREFLIGHT/POSTFLIGHT pair, proving all four usage×phase cells resolve independently — the
exact lookup `driftFromPreviousFlight` (`contexts/vision-flight`) depends on).

`@Nested DbAuditLogRepositoryTests` (2, "Database change audit" below) — proves the trigger fires end to
end through a real `Jpa*Repository`, not a hand-crafted native-SQL write (the whole point is that the
trigger fires no matter *how* a row changes):
`insertUpdateAndDeleteThroughAnExistingRepositoryEachLeaveTheirOwnAuditRowNewestFirst` saves, renames
(and flips `enabled`), then deletes one `geofence_zones` row through `JpaGeofenceRepository`, and asserts
`findRecentForRow("geofence_zones", <id>, 10)` returns exactly three rows, newest-first
(DELETE/UPDATE/INSERT) — the INSERT row has a `null` `oldRow` and a populated `newRow`, the DELETE row
the reverse, and the UPDATE row's `changedColumns` contains `name`/`enabled` but **not** `id` (the
unchanged primary key must not be reported as changed), plus asserts `dbUser` equals the Testcontainers
Postgres username (`session_user`, not anything the application supplies).
`findRecentSpansEveryAuditedTableNewestFirstBoundedByLimit` saves two more zones and asserts the more
recently saved one's row sorts before the other's in `findRecent`'s cross-table feed — the "own rows
within a large fetch" technique `AuditTrailRepositoryTests`/`DetectionEventRepositoryTests` already use,
since the shared container accumulates rows across every test in the class.

`@Nested DbAuditLogCoverageTests` (2, "Database change audit" below) — reads the *live* schema rather
than trusting the migration's own header comment:
`everyPublicBaseTableIsEitherAuditedOrExplicitlyExcluded` queries `information_schema.tables` for every
`public` base table and asserts it is a member of exactly one of this test class's own `AUDITED_TABLES`/
`EXCLUDED_TABLES` constants (both directions — an unclassified live table fails loudly, and so does a
classified name that no longer exists), so a future migration that adds a table without updating either
constant fails this test until someone consciously classifies it.
`everyAuditedTableCarriesExactlyTheAuditTriggerAndNoExcludedTableDoes` queries `pg_trigger`/`pg_class`
directly and asserts the live set of triggered tables equals `AUDITED_TABLES` exactly — catching both a
table the migration forgot to attach a trigger to and a stray trigger that should not exist.

`v21MigrationCreatesTheDbAuditLogTableOnTopOfV1ThroughV20` — same `information_schema.columns` shape as
the V19/V20 schema tests above: `table_name`/`row_id` are `NOT NULL`, `old_row`/`new_row` stay nullable
(an INSERT has no `old_row`, a DELETE no `new_row`), proving `V21__db_audit_log.sql` applied cleanly.

`DevAccountSeedMigrationTest` (5, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1) — its own `@Testcontainers`
class, deliberately **not** a `@Nested` class inside `PostgresDockerIntegrationTest`: a **non-static**
`@Container` field (a fresh `PostgreSQLContainer` per test method, not one shared across the class)
because several scenarios need to observe Flyway's `flyway_schema_history` transition through specific
states (unmigrated → flag-off → flag-on, flag-on → flag-off) that a container already carrying every
other test's migration history cannot give cleanly. One method per contract clause from the W1 brief:
`flagFalseSeedsNoDevAccountsButStillSeedsTheRootGroupAndMigrationSucceeds`,
`flagTrueSeedsAllThreeAccountsWithVerifiedBcryptHashesAndTheReadableMembershipShape` (each hash
verified via `BCryptPasswordEncoder.matches` against its own username as plaintext, and each
`memberships` value equals the exact `List.of(new Membership(rootGroupId, role))` the port returns —
not just "some jsonb landed"), `applyingTwiceIsANoOp` (a second, independent
`PersistenceUnit.start(..., true)` call against the same database — a fresh `Flyway.migrate()` call,
not just re-reading the same `EntityManagerFactory` — confirms Flyway's own history table is what
makes re-application a no-op, not merely the migration's own `ON CONFLICT`),
`flippingFalseThenTrueOnAnAlreadyMigratedDatabaseStillSeedsTheAccountsRetroactively`, and
`flippingTrueThenFalseDoesNotBreakSubsequentMigrationsAndLeavesTheSeededAccountsInPlace` (the one that
required decompiling `flyway-core` to get green — see the "W1 done" narrative section near the end of
this file).

`UpgradePathMigrationTest` (4, docs/plans/active/POSTGRES-ONLY-CONTEXT.md upgrade path) — sibling to
`DevAccountSeedMigrationTest`, same non-static-`@Container`-per-test-method shape, but a materially
different setup: each test first drives a real `Flyway` handle directly (not `PersistenceUnit.start`,
which always migrates to the latest resolvable version) with `.target("12")`, then hand-inserts a
group + three users via plain JDBC shaped exactly like the deleted `AuthSeedRunner` used to leave them
— a `"Legacy Root"` group at a random id, `admin`/`manager`/`pilot` at random ids whose `memberships`
point at it — before finally calling `PersistenceUnit.start(..., true)`, the real upgrade path an
operator's next `docker compose up` actually takes. `upgradeSucceedsAndLeavesPreExistingAccountsUntouched`
— migration completes (defect 1 fixed) and the three hand-inserted accounts keep their original ids
*and* their original (deliberately distinguishable, `"legacy-*-hash"`) password hashes — `V90001`'s
guard skipped all three inserts on the username check, not the id check, and did not silently replace
anyone. `upgradeRestoresManagerVisibilityOfDevPrincipalOwnedAssets` — **the assertion that proves
defect 2 is actually fixed**: asserts the fixed group's `parentGroupId()` is the legacy root's id and
its name is now `"Dev-Mode Assets"`, then builds a `VisibilityScope` for the legacy `manager` user
through a real `DefaultScopeResolver` wired to `JpaGroupRepository`/`JpaAssignmentRepository` (the
actual subtree walk, not a hand-simulated approximation) and asserts it `includes` an `Ownership`
whose group is `UUID(0,1)` — restated locally as a constant (see the test's own javadoc) since this
module must not depend on `vision-app`'s `DevPrincipal`. `freshInstallLeavesFixedGroupParentlessWithV13NameAndSeedsTheThreeDevAccounts`
— `V16` must be a genuine no-op with zero other parentless groups: the fixed group stays root, keeps
the `"Root"` name `V13` gave it, and all three dev accounts still land at their fixed ids.
`runningTheFullMigrationSetTwiceChangesNothing` — a second, independent `PersistenceUnit.start(...,
true)` call (a fresh `Flyway.migrate()`, same idiom as `DevAccountSeedMigrationTest#applyingTwiceIsANoOp`)
against an already-fully-migrated fresh-install database settles into the same fixed point: still one
parentless `"Root"` group, still exactly 3 users.

`@Nested ConnectionPoolTests` inside `PostgresDockerIntegrationTest` (2, docs/plans/active/SCALE-100-PLAN.md
S3) — `hibernateUsesTheSharedClosingProviderNotTheBuiltInUnpooledOne` unwraps the shared
`EntityManagerFactory`'s `ConnectionProvider` service and asserts it is a
`ClosingDatasourceConnectionProvider`, not Hibernate's built-in `DriverManagerConnectionProvider`.
`poolCapsConcurrentPhysicalConnectionsAtItsConfiguredMaximum` opens a second, independent
`EntityManagerFactory` via the new 5-arg `PersistenceUnit.start(..., PersistencePoolSettings)` overload
with a tiny 2-connection pool, holds both connections open across live transactions, then asserts a
third concurrent `EntityManager#getTransaction().begin()` is refused with a `HibernateException` inside
the pool's own `connectionTimeout` (not a hang) — the end-to-end, real-Postgres proof that the pool is
actually bounding concurrency, not just configured and unused.

`PersistencePoolSettingsTest` (7, docs/plans/active/SCALE-100-PLAN.md S3, docker-free) — `defaults()` matches
its own documented `DEFAULT_*` constants, plus one rejection case per compact-constructor invariant
(`maximumPoolSize < 1`, `minimumIdle` negative or above `maximumPoolSize`, `connectionTimeoutMillis <=
0`, `leakDetectionThresholdMillis < 0`) and one case confirming `leakDetectionThresholdMillis == 0` is
accepted as "disabled," not rejected.

`ClosingDatasourceConnectionProviderTest` (2, docs/plans/active/SCALE-100-PLAN.md S3, docker-free) — hand-rolled
fake `DataSource`s (no HikariCP, no container) prove `stop()` closes a `Closeable` `DataSource` and
tolerates one that is not `Closeable`, isolating the narrow shutdown-doesn't-leak claim from
`ConnectionPoolTests`' end-to-end proof above.

187 tests total (up from 182, the database change audit wave: new `DbAuditLogRepositoryTests` (2) +
`DbAuditLogCoverageTests` (2) + `v21MigrationCreatesTheDbAuditLogTableOnTopOfV1ThroughV20` (1) = +5),
measured directly with `./mvnw -B -pl storage/persistence -am test` immediately before (182, `git stash`
of this wave's changes against the same `feat/drone-onboarding` tip) and after (187) — both runs docker-
reachable, every case ran, none skipped (`skipped="0"` in both `TEST-...PostgresDockerIntegrationTest.xml`
and `TEST-...UpgradePathMigrationTest.xml`). The "148" entry directly below already predates the O5/O11
onboarding waves that pushed the pre-this-wave count to 182 — see "Database change audit" below and the
O5/O11 entries near the end of this file for what those added; the running "up from N" chain below this
point was not kept in sync every wave in between (a gap this file itself already flags a few paragraphs
down), so the itemized per-wave sections remain the source of truth over any one summary line's older links.

148 tests total (up from 137, docs/plans/active/SCALE-100-PLAN.md S3: new `PersistencePoolSettingsTest` (7) +
`ClosingDatasourceConnectionProviderTest` (2) + `ConnectionPoolTests` (2) = +11), run against a real
`postgres:16` Testcontainers instance, docker reachable in this environment (`docker --version` →
`Docker version 28.3.3`) — every new case actually ran, none skipped.

137 tests total (up from 133, docs/plans/active/POSTGRES-ONLY-CONTEXT.md upgrade path: new
`UpgradePathMigrationTest` = +4), run against a real `postgres:16` Testcontainers instance, docker
reachable in this environment — every new case actually ran, none skipped.

133 tests total (up from 128, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1: new
`DevAccountSeedMigrationTest` = +5), run against a real `postgres:16` Testcontainers instance, docker
reachable in this environment — every new case actually ran, none skipped.

128 tests total (up from 115, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3: the two ports with no Postgres
implementation at all now have one — `AuditTrailRepositoryTests` (4) + `DetectionEventRepositoryTests`
(7) + the V14/V15 schema tests (2) = +13), run against a real `postgres:16` Testcontainers instance,
docker reachable in this environment — every new case actually ran, none skipped. Older history below
was accurate as of its own wave but the running "up from N" chain was not kept in sync every wave in
between (this module's own T6 entry further down independently confirms 115/115 immediately prior to
this one); treat the itemized per-wave sections as the source of truth over this summary line's older
links. All green in this environment (`docker info` reachable) — up from 98
(docs/plans/done/MAP-REWORK-PLAN.md Wave C: new `MapLayerRepositoryTests` (7) + `DrawingRepositoryTests` (6) +
the V12 schema test (1) + one more `MarkRepositoryTests` case (1), = +15); up from 97 at the
NAV-IA/F8 entry; down from 101
(docs/plans/done/CV-TRAINING-V2-PLAN.md W6: `FilesystemDatasetExport`/`FilesystemDatasetExportTest` (4, pure
filesystem) deleted — dataset delivery to the training host is now a gRPC upload, `adapter-cv-grpc`'s
`GrpcDatasetUploadPort`, not a filesystem export this module writes); up from 78
(docs/plans/done/CV-TRAINING-PLAN.md Wave T3: new `DatasetRepositoryTests` (6) + `TrainingSampleRepositoryTests`
(8) + `SampleImageStoreTests` (4) + the V11 schema test (1) = +19 Postgres-backed, plus
`FilesystemDatasetExportTest` (4, pure filesystem, always runs) = +23 total); up from 71
(docs/plans/done/TACTICAL-MARKS-PLAN.md M2: new `MarkRepositoryTests` (6) + the V10 schema test).

## Gotchas

- **`org.testcontainers.postgresql.PostgreSQLContainer` (Testcontainers 2.x's package — note: distinct from the legacy `org.testcontainers.containers.PostgreSQLContainer` shim, both present in the jar) is a concrete, non-generic class**, not `PostgreSQLContainer<SELF extends PostgreSQLContainer<SELF>>` like Testcontainers 1.x — `new PostgreSQLContainer<>("postgres:16")` does not compile here; it's `new PostgreSQLContainer("postgres:16")` (raw type, no diamond).
- **Testcontainers 2.x renamed its Maven artifacts** with a `testcontainers-` prefix: it's `org.testcontainers:testcontainers-postgresql` and `org.testcontainers:testcontainers-junit-jupiter`, not `org.testcontainers:postgresql`/`org.testcontainers:junit-jupiter` (which don't exist at `testcontainers-bom` 2.0.5 — resolving them fails with a plain "could not find artifact" error that gives no hint the fix is just the artifact name). The un-prefixed core artifact (`org.testcontainers:testcontainers`, for `GenericContainer`/`DockerClientFactory`) did **not** get renamed — only the per-database/per-technology modules did.
- **`JpaOperations` still opens a fresh `EntityManager` per `write`/`read` call — docs/plans/active/SCALE-100-PLAN.md S3 deliberately did not touch this.** Before S3, each such open/close pair also opened/closed its own unpooled physical JDBC connection; since S3 (see "Bootstrap and connection pool" above) that connection now comes from a real, bounded pool, so concurrent calls are capped at `PersistencePoolSettings.maximumPoolSize()` instead of each spawning an unbounded new physical connection. The pool **bounds** the cost, it does not **remove** it: every `write`/`read` still pays a full borrow-from-pool/begin-transaction/commit/return-to-pool cycle per call rather than reusing one `EntityManager` across a logical unit of work (e.g. one HTTP request). No request-scoped or thread-bound `EntityManager`, because there is no Spring/servlet request here to scope one to. Turning that into a real request-scoped (or otherwise batched) unit of work is a separate, larger refactor touching all 19 `Jpa*Repository` classes' call sites — out of scope for S3, flagged here as the next thing to revisit if this pattern shows up in latency/throughput measurements.
- **Hibernate's `HHH10001002: Using built-in connection pool (not intended for production use)` startup warning is gone as of docs/plans/active/SCALE-100-PLAN.md S3** — it only ever came from the unpooled `DriverManagerConnectionProvider` S3 replaced (see "Bootstrap and connection pool" above); verified absent via a full-suite log grep (0 occurrences), not just inferred from the code change. `HHH90000025: PostgreSQLDialect does not need to be specified explicitly` is unrelated and still expected: this module sets `hibernate.dialect` explicitly anyway, to skip Hibernate's own connection-metadata-based auto-detection round trip at startup — a minor, deliberate speed/explicitness tradeoff, not an oversight.
- **`EntityManager#getTransaction().begin()` eagerly acquires the physical JDBC connection for a resource-local transaction — it does not defer to the first query**, contrary to a common assumption (docs/plans/active/SCALE-100-PLAN.md S3, discovered empirically while writing `ConnectionPoolTests#poolCapsConcurrentPhysicalConnectionsAtItsConfiguredMaximum`: the pool-exhaustion `HibernateException` was thrown from `begin()` itself, not from the subsequent `createNativeQuery(...)` call the test originally expected to be the trigger). Relevant to anyone writing a similar concurrency-bound test against this module later.
- **A native query's `?N` positional parameters must be re-supplied per occurrence, not per distinct value** — `JpaTelemetryRepository`/`JpaDetectionRepository`'s prune queries reference `?1` (the grouping key) twice in the SQL text (once in the outer `WHERE`, once in the subquery's `WHERE`) but call `setParameter(1, value)` only **once**; Hibernate's native-query parameter binder resolves every occurrence of a given positional index from the same single `setParameter` call (unlike raw JDBC `?` placeholders, which are positional *per occurrence* and would need the value bound twice) — this is standard JPA `Query#setParameter(int, Object)` behavior, not something either class over-thinks with parameter-index bookkeeping.
- **`EntityManager#setParameter(int, UUID)` on a native query binds correctly as `uuid`, not `varchar`/`bytea`**, with no `stringtype=unspecified` JDBC-URL trick and no `PGobject` wrapping needed — Hibernate infers the correct JDBC type from the Java parameter's runtime class (`UUID.class` → `StandardBasicTypes.UUID` → Postgres `uuid`) the same way it does for typed JPQL/Criteria parameters, even though the query text itself is opaque native SQL to Hibernate.
- **`org.springframework.security:spring-security-crypto` (test scope only, added docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1) marks its own `spring-core` dependency `optional`, and has zero other transitive dependencies** — `new BCryptPasswordEncoder()` (used by `DevAccountSeedMigrationTest` to verify `db/seed/dev`'s hardcoded hashes against their plaintexts, the same class production `BcryptPasswordHasher` in vision-app wraps) throws `NoClassDefFoundError: org/apache/commons/logging/LogFactory` unless `org.springframework:spring-core` is *also* added as a test dependency (it bundles its own `spring-jcl` commons-logging bridge). This does **not** create a runtime Spring dependency for this module — both are `<scope>test</scope>`, and the module's own production code never imports either.
- **Two unavoidable visibility widenings from docs/plans/active/LAYERING-REFACTOR-PLAN.md Wave C's package split**, both mechanical consequences of `repository`/`config` being sibling packages rather than one flat package (§1.4's "package-private wherever the split allows it" — this split doesn't allow it here): `JpaOperations` and its `write`/`read` methods went from package-private to `public` (every `Jpa*Repository` composing it now lives one package over); every mapper's `toEntity`/`toDomain` went from `private static` (on the repository itself) to `public static` (on its own class in `mapper`), for the same cross-package reason. Nothing else in the module widened — `JpaOperations`'s constructor and the mapper classes' own constructors stay `private`/package-scoped where nothing outside needs them.

## Status

Fully implements docs/plans/done/MVP2-PLAN.md **P-a** (`CategoryRepositoryPort`/`DeviceRepositoryPort`/`AssetRepositoryPort`) **and P-b** (`AssetUsageRepositoryPort`/`TelemetryRepositoryPort`/`DetectionRepositoryPort`): JPA implementations for all six, a Flyway-migrated schema (V1–V4), and every round-trip/upsert/idempotent-delete/lifecycle-state/ordering/limit/retention/restart-survival test described above passing. Wired into vision-app behind `vision.persistence.enabled` (default `false`) — see station/vision-app/MODULE.md's `PersistenceWiringConfiguration` entry for the toggle itself.

docs/plans/done/MVP2-PLAN.md **R-a2** ("usage→stream link, persistence half") is closed: `AssetUsageEntity`/`JpaAssetUsageRepository` gained a nullable `stream_id` column (`V4__usage_stream_id.sql`, additive over V1-V3 — no backfill possible or attempted, see the migration's own comment), mapped exactly like every other nullable field on the entity. This is what makes `vision-application`'s `DefaultReplayService` able to query `DetectionRepositoryPort` by a usage's real `streamId` instead of always returning empty detections — see contexts/vision-events/MODULE.md's `ReplayService`/Gotchas entries for the read side.

docs/plans/done/UX-REWORK-PLAN.md **§U-d item 3 done** (asset image, persistence half — CONTRACT 2's storage): a new sixth-plus-one repository port, `AssetImageEntity`/`JpaAssetImageRepository` (`V5__asset_images.sql`, purely additive — a new table, nothing else changed). Keyed by `assetId` itself rather than a synthetic id (see the entity's own note above), `data` a plain `byte[]`/`bytea` column (Hibernate's default mapping, no `@Lob`/converter/jsonb needed — the first genuinely binary, non-JSON column in this schema). `existsByAssetId` is a `count(a)` JPQL query, never fetching the `data` column, specifically so a fleet/asset list populating `hasImage` for many rows doesn't pay for loading image bytes it doesn't need. `./mvnw -B -pl storage/persistence test`: **46/46 green** (was 42) — new `AssetImageRepositoryTests` (4, see Tests above). See vision-domain/vision-application/vision-api/vision-app's own MODULE.mds for the port/domain type, the probe endpoint, the REST controller, and the wiring (`PersistenceWiringConfiguration#assetImageRepositoryPort`, gated by `vision.persistence.enabled` exactly like the other six port beans).

## docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b done (flight-controller-aware telemetry, persistence half)

`TelemetrySampleEntity` gained a nullable `flight_state` jsonb column (`V6__telemetry_flight_state.sql`, purely additive over V1-V5 — same shape as V4's `stream_id` addition, no backfill). Storage choice: the domain `FlightState` record is stored **directly**, no persistence-local wrapper type — following `DetectionResultEntity#detections`' own precedent of letting Hibernate's Jackson-3-backed `FormatMapper` serialize a plain, framework-annotation-free domain record tree straight into jsonb (see Conventions). `JpaTelemetryRepository#toEntity`/`#toDomain` both grew one more positional argument (`telemetry.flightState()` / `entity.flightState()`), no branching needed — Hibernate/Jackson already treat every field of `FlightState` (including its nullable sub-fields and non-null `armingBlockers`) as optional-if-absent the same way `extra`'s `Map<String,Double>` was already handled.

**Old-row compatibility, proven, not just claimed**: `savedSampleWithoutFlightStateRoundTripsAsNull` saves a `Telemetry` built via the domain's 8-arg convenience ctor (no `flightState` argument at all — exactly the shape every pre-F-b row in this table has) and asserts it reads back with `flightState() == null`; `v6MigrationAddsANullableFlightStateColumnOnTopOfV1ThroughV5` separately proves the column itself is nullable at the schema level (`information_schema.columns`), the same two-pronged proof `V4__usage_stream_id.sql`'s own R-a2 entry above used for `stream_id`.

`./mvnw -B -pl storage/persistence test`: **49/49 green** (was 46), run against a real `postgres:16` Testcontainers instance (not skipped) — `TelemetryRepositoryTests` 5→7 (+2, see Tests above), one new top-level schema test (see Tests above). See vision-domain/vision-application/vision-api/vision-app's own MODULE.mds for the domain record/decoder, the `AssetAttention`/DTO ripple, and (zero) wiring change.

**Deviations from the brief**: none.

## docs/plans/done/OPS-CORE-PLAN.md G-b done (geofence zones, persistence half)

A new seventh-plus-one repository port: `GeofenceZoneEntity`/`JpaGeofenceRepository` (`V7__geofence_zones.sql`, purely additive — a new table, nothing else changed). `id` is the domain's own `ZoneId` rather than a synthetic one (a zone has real identity, unlike `Telemetry`/`DetectionResult`); `kind` reuses the domain `ZoneKind` enum directly (`@Enumerated(EnumType.STRING)`, same convention as `Capability`/`LifecycleState`); `polygon` stores the whole `List<GeoPosition>` as jsonb, same mechanism as `DetectionResultEntity#detections`. `save` is merge-by-id (upsert); `deleteById` is a real hard delete, idempotent — zones have no soft-delete concept of their own (a disabled zone is just a row with `enabled=false`).

`./mvnw -B -pl storage/persistence test`: **56/56 green** (was 49), run against a real `postgres:16` Testcontainers instance (not skipped) — new `GeofenceRepositoryTests` (6, see Tests above), one new top-level schema test (`v7MigrationCreatesTheGeofenceZonesTableOnTopOfV1ThroughV6`). See vision-domain/vision-application/vision-api/vision-app's own MODULE.mds for the domain type/port, the `GeofenceMonitor`/`GeofenceService`, the `GeofenceController` REST surface, and the wiring (`PersistenceWiringConfiguration#geofenceRepositoryPort`, gated by `vision.persistence.enabled` exactly like the other seven port beans; `InMemoryGeofenceRepository`, vision-app devsupport, is the disabled-branch fallback).

**Deviations from the brief**: none.

## docs/plans/done/U-AUTH-PLAN.md slice 1 wave 3 done (identity persistence: users + groups)

Two new repository ports: `UserEntity`/`JpaUserRepository` (`UserRepositoryPort`) and
`GroupEntity`/`JpaGroupRepository` (`GroupRepositoryPort`), plus `V8__users_groups.sql` (purely
additive — two new tables, nothing else changed). **Memberships are jsonb on the user row**, not a
join table (the plan offered either; jsonb matches the `flight_state`/`detections`/`polygon`
precedent and the "saved whole with the User aggregate" port contract — a user's memberships are
never queried into individually in slice 1). `findByUsername` lower-cases its key then exact-matches
the already-lower-cased stored `username` (the domain `User` normalizes it), so a plain `UNIQUE`
constraint gives the case-insensitive uniqueness. No FK on either table (not `groups.parent_id`, no
user→group link) — same "no cross-entity foreign keys / stay parity-compatible with the in-memory
reference repos" convention as every other table here.

`./mvnw -B -pl storage/persistence test`: **66/66 green** (was 56), run against a real
`postgres:16` Testcontainers instance (not skipped) — new `UserRepositoryTests` (5) +
`GroupRepositoryTests` (4) + `v8MigrationCreatesUsersAndGroupsOnTopOfV1ThroughV7`. See
station/vision-app/vision-api's own MODULE.mds for the in-memory fallbacks, the wiring
(`PersistenceWiringConfiguration#userRepositoryPort`/`#groupRepositoryPort`, gated by
`vision.persistence.enabled` exactly like the other nine port beans), the application services,
Spring Security, and the `/api/auth/*` surface.

**Deviations from the brief**: none.

## docs/plans/done/U-SCOPE-PLAN.md slice 2 done (pilot→asset assignments, persistence half)

A new twelfth repository port: `AssignmentEntity`/`AssignmentId`/`JpaAssignmentRepository`
(`AssignmentRepositoryPort`), plus `V9__pilot_assignments.sql` (purely additive — one new join
table, nothing else changed). The primary key is the **composite** (`pilot_user_id`, `asset_id`)
via `@IdClass` — a plain join row with no synthetic id, the pair *being* the identity; the composite
PK doubles as the uniqueness constraint that makes `assign` an idempotent `merge` upsert with no
duplicate rows. A secondary index on `asset_id` serves the `pilotsForAsset` direction (the PK's
leading column already serves `assetsForPilot`). No FK to `users`/`assets` — same "no cross-entity
foreign keys / stay parity-compatible with the in-memory reference repo" convention as every other
table here (`InMemoryAssignmentRepository`, vision-app devsupport, is the disabled-branch fallback).

`./mvnw -B -pl storage/persistence test`: **71/71 green** (was 66), run against a real
`postgres:16` Testcontainers instance (not skipped) — new `AssignmentRepositoryTests` (4) +
`v9MigrationCreatesThePilotAssignmentsTableOnTopOfV1ThroughV8`. See station/vision-app/vision-api's own
MODULE.mds for the in-memory fallback, the wiring
(`PersistenceWiringConfiguration#assignmentRepositoryPort`, gated by `vision.persistence.enabled`
exactly like the other eleven port beans), the `AssignmentService`/`ScopeResolver`, and the REST
surface (`AssignmentController`).

**Deviations from the brief**: none.

## docs/plans/done/TACTICAL-MARKS-PLAN.md M2 done (tactical marks, persistence half)

A new thirteenth repository port: `MarkEntity`/`JpaMarkRepository` (`MarkRepositoryPort`), plus
`V10__marks.sql` (purely additive — one new table, nothing else changed; next free migration
number after `V9__pilot_assignments.sql` — the plan's own placeholder guess was `V8`, stale by the
time this wave ran since `V8`/`V9` were since claimed by U-AUTH/U-SCOPE). `Mark` is structurally
the point sibling of `GeofenceZone`, so `MarkEntity` mirrors `GeofenceZoneEntity` field-for-field
with the two divergences the plan calls out: `position` is a single `GeoPosition`, so it is
flattened to `latitude`/`longitude`/nullable `altitude_meters` columns instead of a jsonb polygon,
and `ownership` is flattened to `owner_id`/`group_id` (mirroring `AssetEntity`'s choice for
`Ownership`, since a mark — unlike a zone — is owned and group-scoped). `kind`/`status`/`source`
reuse the domain enums directly (`@Enumerated(EnumType.STRING)`), same convention as
`GeofenceZoneEntity#kind`. `save` is merge-by-id (upsert); `deleteById` is a real hard delete,
idempotent — marks have no soft-delete concept of their own at this layer (clearing a mark, i.e.
`MarkStatus.CLEARED`, is just a column value round-tripped like any other field; that lifecycle
transition is `MarkService`'s job, not this repository's — deferred to M3).

`InMemoryMarkRepository` (`vision-app` devsupport, a plain `ConcurrentHashMap<MarkId, Mark>`, no
eviction/cap) is the disabled-branch fallback, wired via `PersistenceWiringConfiguration#markRepositoryPort`
— the same `VisionPersistenceProperties#enabled()` branch every other repository port here already
uses (default `false` ⇒ in-memory, leaving every existing test/IDE run unchanged). No `MarkService`
or `MarksController` bean exists yet — this wave wires only the repository port; the application
service (docs/plans/done/TACTICAL-MARKS-PLAN.md M3) and REST surface (M4) are separate, disjoint waves.

`./mvnw -B -pl storage/persistence test`: **78/78 green** (was 71), run against a real
`postgres:16` Testcontainers instance (not skipped) — new `MarkRepositoryTests` (6, see Tests
above), one new top-level schema test (`v10MigrationCreatesTheMarksTableOnTopOfV1ThroughV9`).
`./mvnw -B -pl station/vision-app test -DskipWeb`: **149/149 green** (was 142) — `InMemoryMarkRepositoryTest`
(5, new, vision-app devsupport), `PersistenceWiringTest` gained one test method
(`defaultConfigurationKeepsInMemoryMarkRepository`), `PersistenceWiringConfigurationTest` gained one
test method (`enabledSelectsJpaMarkRepository`, plus the existing
`disabledSelectsInMemoryRepositoriesWithoutTouchingTheProvider` extended with one more assertion).
`AssetWiringTest` deliberately **not** touched — it only asserts full-stack (repository + service +
controller) bean triples, and `MarkService`/`MarksController` don't exist yet (M3/M4). `ArchitectureTest`'s
5 rules stayed green with no changes needed.

**Deviations from the brief**: none — the plan's `V8__marks.sql` guess is corrected to the actual
next-free `V10__marks.sql` (noted above and in the migration's own context), exactly as the plan
asked ("verify the next free migration number... note it in your report").

## docs/plans/done/CV-TRAINING-PLAN.md Wave T3 done (CV model-improvement loop, persistence half)

Four new out-ports (docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T1 — frozen, implemented here unmodified),
three new tables, `V11__training_datasets.sql` (purely additive over V1-V10 — the next free
migration number, confirmed by listing this module's `db/migration` directory before writing it):

- `DatasetEntity`/`JpaDatasetRepository` (`DatasetRepositoryPort`) — `datasets`, upsert-by-id,
  `classes` jsonb, `ownership` flattened to `owner_id`/`group_id` (same choice `AssetEntity`/
  `MarkEntity` make), `status` the domain `DatasetStatus` enum directly.
- `TrainingSampleEntity`/`JpaTrainingSampleRepository` (`TrainingSampleRepositoryPort`) —
  `training_samples`, upsert-by-id (a sample mutates over its review lifecycle: capture seeds
  PENDING, labeling replaces `annotations` and moves to LABELED/DISCARDED), `annotations` jsonb
  (the whole `List<Annotation>`, same mechanism as `DetectionResultEntity#detections`),
  `idx_training_samples_dataset_status` serving `findByDataset`/`countByDataset`'s
  `(datasetId, statusOrNull)` filter with one JPQL string carrying an optional `AND status = :status`
  clause rather than two near-duplicate queries.
- `SampleImageEntity`/`JpaSampleImageStore` (`SampleImageStorePort`) — `sample_images`, the
  `AssetImageEntity`/`JpaAssetImageRepository` precedent (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3)
  applied bit-for-bit to training frames instead of asset photos, per the plan's own explicit
  instruction (§C): `sample_id` PK, `data bytea NOT NULL`, upsert-by-id.
- `FilesystemDatasetExport` (`DatasetExportPort`) — see its own "module home" writeup below.

**`FilesystemDatasetExport`'s module home**: the plan's Open Questions §1 recommends
adapter-persistence ("the module that already owns every other user-data-storage concern") with an
alternative of a small vision-app infra bean; this task followed the recommendation. It takes one
constructor argument, `Path exportRoot` — no Spring, no `vision.training.*` property read inside
this module (adapter-persistence has no Spring dependency at all; see this MODULE.md's Bootstrap
section) — so wiring a real `exportRoot` from `vision.training.export-dir` is entirely T4's job
(`VisionTrainingProperties` + one `@Bean`, the same `ObjectProvider`-free pattern
`streamPublisherPort`/`overlayRenderer` already use for a plain, always-real bean with no
persistence-toggle branch). The pure YOLO string-serialization (which sample maps to which
`ExportEntry`, top-left→center box math, `data.yaml`'s class-index lookup) stays in
`vision-application`'s `YoloDatasetWriter` (Wave T2, built in parallel) — this class only sinks the
bytes an already-built `ExportEntry` list carries, per `DatasetExportPort`'s own javadoc split.

**One design decision beyond the plan's letter**: `write` produces a single zip file directly via
`ZipOutputStream`, never a loose unzipped directory first. The plan's prose says "writes a
directory (zipped for download)"; this task judged that literally writing an unzipped tree to disk
*and then* zipping it would double on-disk size per export for a copy nothing in the frozen REST
surface (§3: only `GET .../export/{exportId} -> 200 application/zip`) ever reads — `resolve` only
ever needs to hand back the zip itself. The zip's internal entry layout (`data.yaml`,
`images/<name>`, `labels/<name>`) still matches the frozen YOLO tree exactly; only the "also keep a
loose copy on disk" half was judged unnecessary. Documented in the class's own javadoc.

**Not done here (explicitly T4's scope, not a gap)**: no Spring `@Bean` for any of the four new
ports in `vision-app`'s `PersistenceWiringConfiguration` beyond the three repository ports — wait,
those three (`datasetRepositoryPort`/`trainingSampleRepositoryPort`/`sampleImageStorePort`) **are**
wired there now (gated by the existing `vision.persistence.enabled`, no new flag needed, same
precedent `markRepositoryPort` set before any `MarkService`/`MarksController` existed — see
station/vision-app/MODULE.md). `DatasetExportPort`/`FilesystemDatasetExport` is the one port left
completely unwired: it needs `VisionTrainingProperties#exportDir()` (T4) before a bean can be
constructed, and no consumer (`LabelingService.export`, T2) is wired to call it yet either.

`./mvnw -B -pl storage/persistence test`: **101/101 green** (was 78), run against a real
`postgres:16` Testcontainers instance (docker reachable, not skipped) — new `DatasetRepositoryTests`
(6) + `TrainingSampleRepositoryTests` (8) + `SampleImageStoreTests` (4) + the V11 schema test (1),
plus `FilesystemDatasetExportTest` (4, pure filesystem, unconditional — no Docker/Postgres
involvement for this one port). See contexts/vision-learning/MODULE.md for the four frozen ports/domain types
(Wave T1) and station/vision-app/MODULE.md for the in-memory fallbacks + wiring.

**Deviations from the brief**: none against the frozen T1 contract. The one judgment call
(single-zip-file vs. loose-directory-then-zip) is called out above.

**Honest gaps / explicitly out of scope:**
- **`AuditTrailPort` now has a JPA implementation** (`JpaAuditTrail`, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3 — see that
  section further down) — this was the original gap noted here; it is closed on the adapter side.
  **Not yet wired**: `vision-app`'s `ApplicationServiceWiring#auditTrailPort` still constructs
  `InMemoryAuditTrail` unconditionally, with no `vision.persistence.enabled` branch for this port at
  all (same for `DetectionEventRepositoryPort`/`InMemoryDetectionEventRepository`) — wiring both in
  is explicitly a later wave's job per W3's own brief, not an oversight of this one.
- **`findByUsage`'s `limit` selects the earliest samples, not the most recent** — inherited unchanged from `InMemoryTelemetryRepository`'s actual behavior (`list.stream().limit(n)` over an append-ordered list) per this task's "match the reference implementation's exact semantics" brief, not fixed here. For a long flight with more samples than `AssetController`'s `GET /api/usages/{id}/telemetry?limit=100` default, this returns the flight's *first* 100 seconds, not its most recent — worth a deliberate look (newest-first-then-reverse, or a proper time-window parameter) whenever R-a's replay API design settles, since replay is the actual consumer this ordering matters for.
- **`DetectionQuery#to` is treated as inclusive, not exclusive** — same "match the in-memory implementation's real behavior over its javadoc" call, mirroring `InMemoryDetectionRepository`'s `!capturedAt.isAfter(to)`. The domain javadoc and the only two implementations of the port now disagree; worth reconciling (fix the javadoc, or fix both implementations) in whichever future task next touches `DetectionQuery`.
- **Retention caps are constructor arguments, not a `vision.persistence.*` Spring property** — see Retention above; `PersistenceWiringConfiguration` uses each `Jpa*Repository`'s one-argument (default-cap) constructor. No UI/ops surface has asked for a tunable cap yet; wiring one through is a small, isolated follow-up whenever one does.
- **No connection pool** (see "Bootstrap"/Gotchas) — a config-only follow-up, not a structural one.
- **No referential integrity between categories/devices/assets/usages/telemetry/detections** (see Conventions) — a deliberate parity choice against the in-memory contract, not an oversight; revisit only if the in-memory reference implementations themselves ever grow those checks.
- **Ownership has no separate port/table** — `Asset.ownership` (`ownerId`/`groupId`) is just two columns on `assets`, matching the domain model (`Ownership` is a value type embedded in `Asset`, not its own aggregate) — there is no `OwnershipRepositoryPort` to implement.
- **`telemetry_samples`/`detection_results` have no batched-insert path** — docs/plans/done/MVP2-PLAN.md P-b's bullet mentions "batched inserts" alongside the append-heavy framing; `save` here is one row per call (matching the ports' one-sample/one-result-at-a-time method signatures exactly — there is no `saveAll`/`saveBatch` on either port to implement), same per-call `EntityManager` cost as every other write in this module (see `JpaOperations`'s Gotcha). Not a correctness gap against the port contracts, but worth flagging: a genuinely high-rate telemetry/detection source (e.g. 10fps CV on several concurrent streams) would see this module's per-write JDBC-connection-open cost before it saw any query-side limit.

## docs/plans/done/CV-TRAINING-V2-PLAN.md W6 done (export step deleted)

**Deleted**: `FilesystemDatasetExport.java` + `FilesystemDatasetExportTest.java` (docs/plans/done/CV-TRAINING-V2-PLAN.md
§A) — the manual filesystem export step this class implemented (`DatasetExportPort`, itself deleted
in `vision-domain` W1) is gone. Dataset delivery to the training host is now an implicit part of
`POST /api/datasets/{id}/train`, over a gRPC client-streaming upload straight onto the wire —
`adapter-cv-grpc`'s `GrpcDatasetUploadPort implements DatasetUploadPort`, framing the same frozen §5
YOLO layout (`data.yaml`, `images/<name>`, `labels/<name>`) as a streamed zip instead of a file this
module ever wrote to disk. No replacement class lives in this module — there is nothing left for
adapter-persistence to own here; see cv/grpc/MODULE.md for the new port
implementation.

**`PostgresDockerIntegrationTest`**: `DatasetRepositoryTests#findAllReturnsEverySavedDataset` used
`DatasetStatus.EXPORTING` as its second fixture's status — that enum constant was deleted in
`vision-domain` W1 (nothing ever set it in production; the only two references anywhere in the repo
were this test and a DTO javadoc, per that wave's own report). Fixed by using `DatasetStatus.ARCHIVED`
instead — the test's actual intent ("two datasets with different statuses both round-trip through
`findAll`") is unchanged, just now exercised with a status that still exists.

`./mvnw -B -pl storage/persistence test`: **97/97 green** (was 101, -4 — see "Build/test"
above), run against a real `postgres:16` Testcontainers instance (docker reachable, not skipped).

**Deviations from the brief**: none.

## docs/plans/active/LAYERING-REFACTOR-PLAN.md Wave C done (package split: repository/mapper/config/entity)

Pure structural refactor, no behavior change: the module's flat root package (15 `Jpa*Repository`/
`Jpa*Store` classes + `PersistenceUnit` + `JpaOperations`, plus the pre-existing `entity/`) is now
`repository/` + `mapper/` + `config/` + `entity/`, matching docs/plans/active/LAYERING-REFACTOR-PLAN.md §3's
template (b) for `adapter-persistence`. See "API surface" above for the per-package inventory; this
section records what moved, the one judgment call, and the "no magic values to extract" finding.

**What moved:**
- `repository/` — all 15 `Jpa*Repository`/`Jpa*Store` classes, package declaration changed, no
  logic changed. Each now imports its aggregate's mapper from `mapper` and `JpaOperations` from
  `config` instead of declaring private static `toEntity`/`toDomain` methods inline.
- `mapper/` — 14 new classes (`CategoryMapper` … `SampleImageMapper`, see "API surface" above),
  each holding the exact `toEntity`/`toDomain` method bodies lifted verbatim out of its repository
  — no logic rewritten, only relocated and widened from `private static` to `public static`.
- `config/` — `PersistenceUnit` (unchanged except package + FQN entity imports) and `JpaOperations`
  (unchanged except package + widened from package-private to `public`, see Gotchas).
- `entity/` — untouched, per the brief (already correctly placed).

**The one judgment call**: whether `JpaAssignmentRepository` gets an `AssignmentMapper`. It does
not — see the `mapper` package note in "API surface" above for the reasoning (no domain aggregate
to map to/from, only inline `UUID`↔id-wrapper wraps). Flagging it explicitly here rather than
silently either extracting a no-op mapper or silently skipping the question.

**`PersistenceUnit`'s Hibernate settings: no magic values found to extract** (the brief's item 5).
Every literal `PersistenceUnit#start` sets is either a caller-supplied argument (`jdbcUrl`/
`username`/`password`, sourced from vision-app's `VisionPersistenceProperties`, itself backed by
Spring datasource-shaped config) or a fixed protocol/correctness constant with no legitimate
per-environment variation: the JDBC driver class name, the Postgres dialect class name, and
`hibernate.hbm2ddl.auto=validate` (a design invariant — "Flyway owns the schema, Hibernate only
validates" — not a tuning knob). There is no connection-pool sizing, batch size, fetch size, or
timeout hardcoded anywhere in this class to externalize into a plain settings record; inventing one
would be exactly the "empty ceremony" the plan's own guardrail warns against. (The retention caps in
`JpaTelemetryRepository`/`JpaDetectionRepository`, `DEFAULT_RETENTION_LIMIT_PER_USAGE`/`_PER_STREAM`
= 100,000, are a separate, already-documented, deliberately-deferred gap — see "Honest gaps" above
under docs/plans/done/CV-TRAINING-PLAN.md's entry — and out of this wave's scope, which named only
`PersistenceUnit`.)

**Two unavoidable visibility widenings** (documented in Gotchas above): `JpaOperations` and its
`write`/`read` methods, package-private → `public`; every mapper's `toEntity`/`toDomain`,
`private static` → `public static`. Both are mechanical consequences of the package split — nothing
widened that didn't have to.

**Downstream mechanical import fix** (docs/plans/active/LAYERING-REFACTOR-PLAN.md's cross-module rule: the wave
that moves a type owns the mechanical import fix in every downstream module, nothing else there):
`vision-app`'s `PersistenceWiringConfiguration` (wildcard-imported `com.drones.vision.adapter.persistence.*`,
now `com.drones.vision.adapter.persistence.config.PersistenceUnit` +
`com.drones.vision.adapter.persistence.repository.*`) and `PersistenceWiringConfigurationTest`
(explicit per-class imports, same package rename) both had their imports updated — no other line in
either file touched. **Not verified to compile**: `vision-app` is currently red for reasons entirely
outside this wave's scope (a parallel adapter track changed constructor signatures
`WiringConfiguration` hasn't caught up with yet, per this task's own brief) — a later consolidated
wave (docs/plans/active/LAYERING-REFACTOR-PLAN.md Wave D) fixes that and will need to confirm these two files
compile once it does.

**Test changes**: none deleted or weakened. `PostgresDockerIntegrationTest` stayed in the root test
package `com.drones.vision.adapter.persistence` (it is one whole-module integration test spanning
every repository + `PersistenceUnit`, not a per-class unit test — there is nothing to "move" as a
separate file per class) and gained import statements for the now-cross-package `repository`/
`config` classes it references by simple name. No assertion changed.

`./mvnw -B -pl storage/persistence test`: **97/97 green (unchanged)** — same test count as
before this wave, run against a real `postgres:16` Testcontainers instance (docker reachable, not
skipped), proving the package split changed no behavior.

**Deviations from the brief**: none. Ambiguity flagged rather than resolved silently: the
`AssignmentMapper` non-extraction and the `PersistenceUnit` no-magic-values finding, both above.

## docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8 done (replay library, persistence half)

`AssetUsageRepositoryPort` gained one method, `findRecent(int limit)` — `findRecentByAsset`'s
fleet-wide counterpart, backing `GET /api/usages` (the "replay library" list; see
vision-application/vision-api's own MODULE.mds for the read model/controller). `JpaAssetUsageRepository`
implements it with the same `AssetUsageEntity` JPQL query `findRecentByAsset` already uses, minus the
`asset_id` predicate (`order by started_at desc`, `setMaxResults(limit)`) — no new index, no new
migration: this table has no per-asset-only index to begin with (see the entity's own javadoc — no
FK/index beyond `asset_id`), so dropping the predicate doesn't lose one. `./mvnw -B -pl
storage/persistence test`: **98/98 green** (up from 97; docker ran, not skipped) — one new
`AssetUsageRepositoryTests#findRecentReturnsNewestFirstAcrossEveryAssetBoundedByLimit`, written to
assert relative order among its own rows (by id) within a large fetch rather than assuming they are
the only/topmost rows in the whole suite, since this table is shared, unpolled, across every other
nested test class's own inserts in the same run. `vision.persistence.enabled` stays `false` by
default, so this implementation is not exercised at runtime by the default-config app — the
in-memory devsupport fallback (`vision-app`'s `InMemoryAssetUsageRepository`) is what the running
dev server actually uses; see that module's MODULE.md.

## docs/plans/done/MAP-REWORK-PLAN.md Wave C done (the map as a COP, persistence half)

Two new repository ports (the sixteenth and seventeenth), three new tables, five new columns on
`marks`, and `V12__map_layers.sql` — the persistence third of Wave C (the other two thirds, REST +
scoped SSE and the wiring/devsupport, are in station/vision-api/vision-app's own MODULE.mds).

- `MapLayerEntity` + `LayerGrantEmbeddable` / `JpaMapLayerRepository` (`MapLayerRepositoryPort`) —
  `map_layers` + `map_layer_grants`, upsert-by-id with **wholesale grant replacement**.
- `MapDrawingEntity` / `JpaDrawingRepository` (`DrawingRepositoryPort`) — `map_drawings`,
  upsert-by-id, `points` as jsonb.
- `MarkEntity`/`MarkMapper` reworked for the Wave A `Mark`: `layer_id`, `affiliation`, and the
  flattened `Verification` triple (`verification_state`/`verified_by`/`verified_at`).

**Three judgment calls, flagged rather than silently made:**

1. **Migration number.** The plan says `V11__map_layers.sql`; V11 was already taken by
   `V11__training_datasets.sql`, so this is `V12__map_layers.sql`. Same correction the M2 wave had to
   make to this plan's `V8__marks.sql` guess.
2. **Partial FK policy** — `map_layer_grants` has one, `marks.layer_id`/`map_drawings.layer_id` do
   not, against the plan's parenthetical "FK cascade". Reasoning in the `V12__map_layers.sql` entry
   under Schema above, and in the migration's own header comment: an FK on those two would reject
   writes the in-memory reference repos accept, breaking the round-trip parity this module is judged
   against, and the cascade it would provide is already done in application code (which must emit a
   `MapEvent` per row regardless).
3. **Grants as a table, not jsonb** — the one collection in this module that is not jsonb. It is the
   one whose rows are a security decision; the composite PK also gives "one grant per subject per
   layer" for free. The cost is `LayerGrantEmbeddable`, this module's only persistence-local mirror
   of a domain record (a JPA `@Embeddable` cannot be a record).

`./mvnw -B -pl storage/persistence test`: **113/113 green** (was 98), run against a real
`postgres:16` Testcontainers instance — **docker was reachable, so these ran rather than skipped**.

**Deviations from the brief**: the FK policy above (documented, deliberate). Nothing else.

**Honest gaps / explicitly out of scope:**
- **No `findByLayer` on either new port.** Both ports are `save`/`findById`/`findAll`/`deleteById`
  per the frozen §2.3 contract, and every caller filters `findAll()` in the application layer — fine
  at this deployment's scale (the same call the plan makes for `MarkRepositoryPort`), but the first
  deployment with thousands of drawings will want an indexed `findByLayer` on both. The
  `idx_map_drawings_layer`/`idx_marks_layer` indexes are already in place for when it does.
- **`map_layers` has no partial unique index enforcing "exactly one COP layer".** The invariant is
  held by `LayerResolver#copLayerId()`'s `synchronized` find-or-create plus the migration's fixed-id
  seed, not by the schema. A `CREATE UNIQUE INDEX ... WHERE kind = 'COP'` would make it structural;
  it was not added because the in-memory reference repository could not enforce the same thing, which
  is the parity rule the rest of this schema follows.

## docs/plans/done/TRACKING-PLAN.md wave T6 done (tracking rides the jsonb — no migration)

`./mvnw -B -pl storage/persistence test`: **115/115 green** (was 113), **docker present, so
every Testcontainers case actually ran** against a real `postgres:16` — including both new ones; this
is a verified result, not a skipped-and-assumed one.

**Nothing in `src/main` changed.** No Flyway migration (the next free version stays `V13`), no entity
field, no mapper line: `Detection`'s new nullable `TrackRef` component rides inside the existing
`detection_results.detections` jsonb because Hibernate's Jackson 3 `FormatMapper` serializes the whole
record tree (docs/plans/done/TRACKING-PLAN.md §4.C). The wave is therefore two tests and two doc paragraphs —
which is the honest size of it.

See the tracks bullet under Conventions for the three consequences that are now written down: tracks
are not SQL-queryable (deferred to S2, which is the first query that needs them), pre-tracking rows
read back untracked, and per-frame `TrackingTelemetry` is deliberately not persisted.

## docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1 done (the auth fix, standalone)

**The bug**: the now-deleted `AuthSeedRunner` (station/vision-app) created the root group at
application-startup with `GroupId.random()`, while `DevPrincipal.GROUP_ID` — the fixed group every
asset gets stamped with while `vision.auth.enabled=false` (the default) — is the fixed
`UUID(0,1)`. Every asset created under the default config was owned by the fixed group; the group a
persistence-enabled app's `AuthSeedRunner` actually seeded was a *different*, random one. A MANAGER
scoped to that random root group saw an empty fleet. `V13__identity_baseline.sql` (see Schema above)
fixes this at the schema level — the root group is now always seeded at `DevPrincipal.GROUP_ID`
itself, `ON CONFLICT (id) DO NOTHING` so a database already carrying a stale random-id row from a
pre-W1 run is not blocked, just left with an extra (now-orphaned, ownerless) group row alongside the
correct fixed one. `DevPrincipal` was not touched — the migration adapts to it, per the brief.

**Dev accounts moved out of Java entirely.** The three DEV-ONLY accounts (`admin`/`admin` ADMIN,
`manager`/`manager` MANAGER, `pilot`/`pilot` PILOT) that `AuthSeedRunner` used to create
unconditionally on every startup now live in `V90001__dev_accounts.sql`, a Flyway migration in a
*second*, conditional location (`classpath:db/seed/dev`) that only ever joins Flyway's `locations`
when the new `vision.persistence.seed-dev-users` property (`VisionPersistenceProperties#seedDevUsers`,
default `false`) is `true` — see the `db/seed/dev` schema entry above for the full account of why a
second location, why version `90001`, and why `ignoreMigrationPatterns("*:future", "*:missing")`
rather than the `"*:missing"` a first reading suggests. `AuthSeedRunner.java`/`AuthSeedRunnerTest.java`
are deleted; station/vision-app/MODULE.md documents the replacement on that side (`DevAccountSeeder`,
a test-only helper four `@SpringBootTest` classes now call from `@BeforeEach`, since those tests still
need the accounts to exist and there is no in-memory equivalent of a Flyway seed).

**The `ignoreMigrationPatterns` bug, found empirically, not reasoned about** — the brief's own explicit
demand ("verify which actually holds against a real Postgres — do not reason about it, test it") paid
off here. A first-reading guess, `ignoreMigrationPatterns("*:missing")`, compiled, read plausibly in
review, and **silently failed to suppress Flyway's validation error** in the flag-on→off integration
test, with no indication why. Decompiling `flyway-core-12.4.0.jar` (`javap` on `MigrationState`,
`ValidatePatternUtils`, `MigrationInfoImpl`, and `BaseAppliedMigration`, none of them public API) showed
the actual mechanism: an applied migration whose location has disappeared is classified `MISSING_SUCCESS`
only if its version is *below* the highest version Flyway can still resolve, and `FUTURE_SUCCESS`
if it's *at or above* it (`BaseAppliedMigration#getMissingState`) — and `V90001`'s whole reason for
existing (see the `db/seed/dev` schema entry) is to always sort above `db/migration`'s real versions,
which means it is *always* `FUTURE_SUCCESS` once orphaned, never `MISSING_SUCCESS`. The fix,
`ignoreMigrationPatterns("*:future", "*:missing")`, also turned up a second fact worth recording:
`"*:future"` is Flyway's **own built-in default** ignore pattern (`FlywayModel`'s constructor sets it
before any caller-supplied value — confirmed by decompiling, not the changelog); calling
`ignoreMigrationPatterns(...)` at all replaces that default outright rather than adding to it, so a
caller that only wanted to *add* a pattern for its own case (as this one initially believed it was
doing) had in fact also silently dropped a default protection. Full bytecode-level account left in
`PersistenceUnit`'s javadoc and `DevAccountSeedMigrationTest`'s own comments, not just in this file.

`./mvnw -B -pl storage/persistence clean test`: **133/133 green** (was 128 immediately before this
wave — see the Tests section above for the breakdown), run against a real `postgres:16`
Testcontainers instance, **docker reachable in this environment — every new case, including all five
`DevAccountSeedMigrationTest` scenarios, actually ran, none skipped.** `clean test`, not plain `test`,
matters here: a stale `target/classes/db/seed/dev/V13.1__dev_accounts.sql` left over from an earlier,
abandoned version-numbering attempt (see the `db/seed/dev` schema entry's "reserved-high band" bullet)
sat on the classpath alongside the renamed `V90001__dev_accounts.sql` and reproduced exactly the
out-of-order failure the rename was meant to fix, until a clean rebuild removed it — a reminder that a
green run after a mid-task file rename should be re-verified with `clean test` before being trusted.

`./mvnw -B -pl station/vision-app test -DskipWeb`: **238/238 green** (was 240 — `AuthSeedRunnerTest`'s
2 `@Test` methods deleted with the class; no other test method was added or removed, only `@BeforeEach`
seeding calls and constructor-argument fixups — see station/vision-app/MODULE.md for the full list of
touched test classes).

**Deviations from the brief, and the empirically-discovered facts to flag**: the brief asked for the
Flyway-mechanism choice (second location vs. placeholder-guarded statement) to be *tested*, not
reasoned about, and it was — the second-location route was chosen, and it did trip validation on
removal exactly as the brief anticipated, requiring `ignoreMigrationPatterns` — but the specific
pattern the brief's own phrasing implied (`"*:missing"`, since the scenario is "the migration is
missing") was wrong, for the version-numbering reason above; `"*:future"` is the pattern that actually
fires, discovered only by decompiling Flyway's internals rather than its public Javadoc, which does not
document this branch. A `V13.1`-style "next free slot" version scheme was also tried first, per the
brief's implicit assumption that dev-seed content would sit right after `V13`, and was abandoned only
after it measured out-of-order against the concurrently-landing W3 wave's `V14`/`V15` — a genuine,
not hypothetical, interaction between two waves running in the same working tree at once. No other
deviation: `DevPrincipal` untouched, no cross-entity FKs added, `PostgresDockerIntegrationTest`'s
existing content untouched (only `PersistenceUnit`'s 4-arg overload and the two new files below it),
scope held to the files listed in the brief plus the minimum ripple a growing
`VisionPersistenceProperties` record and a deleted `AuthSeedRunner` forced elsewhere (2 test files'
positional-constructor calls, `DemoPeople.java`'s javadoc, `storage/persistence/pom.xml`'s two new test
dependencies) — all disclosed here and in station/vision-app/MODULE.md rather than left implicit.

## docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3 done (the two missing adapters)

Of 17 `Jpa*Repository` classes against 19 `InMemory*` classes, two ports had **no Postgres
implementation at all**, wired unconditionally to RAM in `vision-app`'s `ApplicationServiceWiring`
regardless of `vision.persistence.enabled`: `AuditTrailPort` and `DetectionEventRepositoryPort`. A
later wave (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2) deletes every `InMemory*` class and cannot delete these
two until their replacements exist — that is the whole of this wave's job, and only that: **the
adapters exist, are registered with the persistence unit, and are proven to round-trip.** Neither
is wired into `vision-app` here (see "Honest gaps" above) — that is explicitly a later wave's task
per this wave's own brief, not an oversight.

- `AuditEntryEntity`/`JpaAuditTrail` (`AuditTrailPort`) — `audit_entries`, `V14__audit_trail.sql`.
  `id` is the domain's own `AuditId` (real identity, not synthetic); `record` always `persist`s
  (never `merge`s — entries are immutable historical facts per the port's own contract);
  `findRecent`/`findByTarget`/`findByActor` are one JPQL shape each (optional `WHERE`, `order by
  occurredAt desc`, `setMaxResults(limit)`). **No retention pruning at all** — see Retention above
  for why an audit trail that evicts its own oldest rows defeats the point of the port.
- `DetectionEventEntity`/`JpaDetectionEventRepository` (`DetectionEventRepositoryPort`) —
  `detection_events`, `V15__detection_events.sql`. `id` is the domain's own `DetectionEventId`
  (real identity — an event mutates over its own open lifetime, so it needs a stable key to upsert
  by); `save` is a genuine `merge`-by-id upsert, matching `InMemoryDetectionEventRepository`'s
  remove-then-re-add-by-id semantics exactly — the one repository in this module's history-table
  family that upserts rather than always `persist`s. `position` (a single, entirely-optional
  `GeoPosition`) is flattened to nullable `position_latitude`/`position_longitude`/
  `position_altitude_meters` columns, the `AssetUsageEntity#startPosition`/`#lastPosition` pattern.

**Retention decision** (the brief's one open design question): reuse the JPA-established
"constructor-argument row cap, prune-on-write" pattern `JpaDetectionRepository`/
`JpaTelemetryRepository` already use, keyed by `stream_id` (the closer of the two — detection
events group by stream, matching `JpaDetectionRepository` exactly, not by usage), default
100,000 rows per stream — **not** the in-memory ring's 500-per-stream cap, which is a
devsupport-heap-bounding number, not a real retention policy. No third mechanism invented. Full
reasoning in the Retention section above.

**Schema convention followed as-is**: no cross-entity foreign keys on either new table (C2/OQ1 —
the open question about adding them is explicitly not this wave's to resolve), matching every
other table in this schema. For `audit_entries` this is more than convention: an entry must stay
resolvable even after its actor's account or its target row is gone, so a referential constraint
here would be actively wrong, not merely inconsistent with the in-memory reference repository.

**`PersistenceUnit`**: only the `addAnnotatedClass` block was touched, adding
`AuditEntryEntity.class`/`DetectionEventEntity.class` — per this wave's explicit instruction not to
touch the method signature or Flyway configuration, both owned by a parallel wave
(docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1, which landed concurrently in the same working tree while this task ran:
it added the `seedDevUsers` overload, `V13__identity_baseline.sql`, and `db/seed/dev`). No
conflict arose — both waves' changes compile and test together (see the test run below, executed
*after* W1's concurrent edit landed).

`./mvnw -B -pl storage/persistence test`: **128/128 green** (up from 115), run against a real
`postgres:16` Testcontainers instance — **docker was reachable in this environment, so every new
case actually ran, none skipped**. New: `AuditTrailRepositoryTests` (4) + `DetectionEventRepositoryTests`
(7) + `v14MigrationCreatesTheAuditEntriesTableOnTopOfV1ThroughV13` +
`v15MigrationCreatesTheDetectionEventsTableOnTopOfV1ThroughV14` (2 schema tests) = +13. Re-run a
second time after W1's concurrent `PersistenceUnit`/migration changes landed, confirming both waves'
work is compatible: still 128/128 green.

`./mvnw -B -pl station/vision-app -am -DskipTests compile`: **BUILD SUCCESS**, all 26 reactor
modules — proves this wave did not break `vision-app`'s compile, including after W1's concurrent
changes to that module landed mid-task.

**Deviations from the brief**: none. `PostgresDockerIntegrationTest`'s existing content was not
modified, only appended to, per the brief's "a parallel agent owns that file's existing content"
note (that note in fact describes `V13__identity_baseline.sql`/`db/seed/**`, owned by W1, not this
file — this file is this wave's own to extend, and was).

## docs/plans/active/POSTGRES-ONLY-CONTEXT.md upgrade path done (fixing W1 for a database that already ran `AuthSeedRunner`)

W1 (above) is correct for a fresh database. This wave fixes the path for a database that is not
fresh — every `docker-compose.yml` deployment that existed before W1 landed, since Compose has
always paired `VISION_PERSISTENCE_ENABLED=true` with `VISION_AUTH_ENABLED=true` against a
persistent `postgres-data` volume, so every one of them already ran the now-deleted
`AuthSeedRunner` and has a "Root" group at a random id plus `admin`/`manager`/`pilot` at random
ids pointing at it.

**Defect 1 (proven, fixed first): the seed migration aborted startup on any upgraded database.**
`V90001__dev_accounts.sql`'s `ON CONFLICT (id) DO NOTHING` only covers a conflict on `id`;
`users.username` carries its own, independent `UNIQUE` constraint (`V8__users_groups.sql`), and
Postgres allows exactly one conflict target per `INSERT`. Reproduced directly against a real
`postgres:16` with V8's exact DDL and one pre-existing `admin` row at a random id before the fix:
`ERROR: duplicate key value violates unique constraint "users_username_key"` — a failed migration
aborts `PersistenceUnit.start`, so the application does not boot at all. Fixed by replacing the
single multi-row `INSERT ... VALUES ... ON CONFLICT (id)` with three `INSERT ... SELECT ... WHERE
NOT EXISTS (id OR username)` statements, one per account (see the `db/seed/dev` schema entry
above) — this was strictly worse than the bug the whole plan set out to fix, since it would have
fired on every operator's very next `docker compose up`.

**Defect 2: an upgraded database still had the original bug even once the migration succeeded.**
`V13`'s `ON CONFLICT (id) DO NOTHING` adds the fixed-id root group *alongside* a pre-existing
random-id one, not merged with it — a MANAGER whose membership still points at the old random root
(nothing rewrites `memberships` jsonb, deliberately — see below) still has a `VisibilityScope`
built from that root's subtree, which does not contain the fixed group `DevPrincipal`-owned assets
are stamped with. Fixed by `V16__adopt_fixed_root.sql` (see Schema above): when there is exactly
one *other* parentless group, the fixed group is adopted as its *child* (never the reverse — see
that migration's own header for why direction is load-bearing) and renamed from `"Root"` to
`"Dev-Mode Assets"`, an honest label for a non-root node that now holds everything stamped by
`DevPrincipal` before the database had real identity. Zero or 2+ other parentless groups are both
no-ops (fresh install; ambiguous multi-root state respectively), each stated in the migration's own
header rather than silently decided.

**Verification, against a real Postgres, not reasoned about**: `UpgradePathMigrationTest` (new,
sibling to `DevAccountSeedMigrationTest` — see Tests above) migrates a container to `V12` with a
raw `Flyway` handle, hand-inserts an `AuthSeedRunner`-shaped state via plain JDBC, then runs the
real `PersistenceUnit.start(..., true)` upgrade path an operator's Compose restart actually takes.
Its `upgradeRestoresManagerVisibilityOfDevPrincipalOwnedAssets` scenario is the one that matters:
it reproduces `DefaultScopeResolver#scopeFor`'s own subtree walk (via a real `JpaGroupRepository`
+ `DefaultScopeResolver`, not a hand-simulated approximation) for the legacy `manager` user and
asserts the resulting `VisibilityScope.includes(...)` an `Ownership` in `DevPrincipal`'s group
(`UUID(0,1)`, restated as a local constant — this module must not depend on `vision-app`). This
is the first end-to-end check of the bug described in POSTGRES-ONLY-CONTEXT.md §2.1/§2.2 for an
*upgraded* database (as opposed to a fresh one, which W1's own tests already covered) — it
confirmed the analysis in the plan without contradiction; nothing here needed to be reported back
as a surprise.

`./mvnw -B -pl storage/persistence clean test`: **137/137 green** (was 133 immediately before this
task — see the Tests section above; that in turn corrects this file's own previously-stale "128"
summary line, which had not accounted for `DevAccountSeedMigrationTest`'s 5 pre-existing W1
scenarios), run against a real `postgres:16` Testcontainers instance, docker reachable in this
environment — every new case, all four `UpgradePathMigrationTest` scenarios included, actually
ran, none skipped.

`./mvnw -B -pl station/vision-app test -DskipWeb`: **238/238 green**, unchanged — this task touched
no file in `vision-app` (its scope was `storage/persistence` only), so the default-config bar was
never at risk; re-run anyway per the exit criteria, not assumed from "no files changed there."

**Deviations from the brief, and one thing worth flagging**: none of substance. `V16` needed a
`DO $$ ... $$` PL/pgSQL block rather than a plain `UPDATE ... WHERE` — the earliest draft tried
`SELECT count(*), max(id) INTO ...` to get both the count and the candidate parent id in one
query, which fails against a real Postgres (`function max(uuid) does not exist` — `uuid` has no
default aggregate ordering); replaced with a plain `SELECT id INTO ...` guarded by the count check,
safe because the branch already guarantees exactly one row. No `V17` created, `V13`/`V14`/`V15`/
`PersistenceUnit` untouched, per the brief's file-ownership constraint.

## docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b done (JpaAuditTrail/JpaDetectionEventRepository wired; documentation catch-up — no source change in this module)

W3 (above) built `JpaAuditTrail`/`JpaDetectionEventRepository` but left both unwired, "a later wave's
job." This wave is that later wave, but the work happened entirely in `vision-app`
(`ApplicationServiceWiring#auditTrailPort`/`#detectionEventRepositoryPort` now build both classes
unconditionally, wrapped in their `LiveUpdate*` decorators exactly as before — see that module's own
MODULE.md for the wiring) and in `vision-app`'s `PersistenceWiringConfiguration` (collapsed to
straight-line — no `vision.persistence.enabled`, no `@ConditionalOnProperty`, no `ObjectProvider`).
**No file under `storage/persistence/src/main/**` changed for this wave** — every `Jpa*Repository`,
entity, and migration was already correct; only the caller changed. This module's own doc entries
that had gone stale as a side effect of the wiring change were corrected here: the "**Not yet wired
into `vision-app`**" notes on `JpaAuditTrail`/`JpaDetectionEventRepository` (API surface, above) now
say wired-and-unconditional; the top summary line's "opt-in via `vision.persistence.enabled`" now
says unconditional; a handful of Conventions/Retention/Schema paragraphs that justified a design
choice by pointing at a now-deleted `InMemory*Repository` (vision-app devsupport, 19 classes removed
this same wave) were reworded to describe the choice on its own terms rather than by parity with code
that no longer exists.

**Left alone, deliberately:** the `Build/test` line's own historical count narrative (the "up from
133"/"up from 128" chain, including its one mention of `VISION_PERSISTENCE_ENABLED=true` describing
a pre-W1-fix `docker-compose.yml` deployment shape) and every dated `## docs/plans/...` section above
this one — both are this file's established append-only history, accurate for what was true when
written, not a live description of today's config surface. Two test-method identifiers
(`queryTimeRangeIsInclusiveOnBothEndsMatchingInMemoryBehavior` in `PostgresDockerIntegrationTest`,
and its neighboring prose about mirroring `InMemoryTelemetryRepository`/`InMemoryDetectionRepository`
behavior) were also left as-is: they name real, unmodified, still-passing test code in this module
that this task's brief did not ask to be touched, and renaming the doc's prose without renaming the
actual `@Test` method would just trade one inconsistency for another.

**Tests:** `./mvnw -B -pl storage/persistence clean test` — **137/137 green**, unchanged from W3
(re-measured directly, not assumed unaffected). Docker reachable throughout; `PostgresDockerIntegrationTest`
and `UpgradePathMigrationTest` both ran, none skipped.

**Deviations from the brief:** none. `db/migration/**` was not touched (frozen, no `V17+`, per this
task's own constraint) — every fix here is `vision-app` wiring plus this module's own documentation.

## docs/plans/active/SCALE-100-PLAN.md S3 done (a real HikariCP connection pool, shared with Flyway)

Every `Jpa*Repository` call before this wave opened its own physical JDBC connection through
Hibernate's built-in, explicitly-not-for-production `DriverManagerConnectionProvider` — accepted at
this platform's earlier single-instance/friends-demo scale, wrong once SCALE-100-PLAN.md's ~100
concurrent users become the target. This wave gives `PersistenceUnit` a real pool: `start` now builds
one `HikariDataSource` (sized from a new `PersistencePoolSettings` record, `config` package, four
fields — `maximumPoolSize`/`minimumIdle`/`connectionTimeoutMillis`/`leakDetectionThresholdMillis` —
each with a documented, ~100-concurrent-user-justified default, no magic numbers inline per CLAUDE.md
rule 1) and hands that same `DataSource` to **both** Flyway (`Flyway.configure().dataSource(...)`,
replacing the old URL/username/password overload) and Hibernate (`hibernate.connection.datasource`
plus a new `hibernate.connection.provider_class`: `ClosingDatasourceConnectionProvider`, a thin
subclass of Hibernate's own `DatasourceConnectionProviderImpl` that also closes the pool on `stop()`).
See "Bootstrap and connection pool" above for the full mechanism, including why Hibernate's own
`HikariCPConnectionProvider`/`hibernate.hikari.*` route was deliberately **not** used — it always
builds a second, unshareable pool, which is incompatible with "Flyway migrates before the EMF exists."

**Corrected, not just added:** the "Bootstrap" section (renamed "Bootstrap and connection pool") used
to claim HikariCP was "already on the classpath transitively via Hibernate's own dependencies" and that
wiring it in was "a config-only change" — both checked and found false on 2026-08-17 (`mvn
dependency:list` showed no `com.zaxxer:HikariCP` anywhere in this module's classpath before this wave),
now corrected in place with the real mechanism and an explicit pointer to this section as the
correction's source. `JpaOperations`'s per-call `EntityManager` pattern (Gotchas, above) was
deliberately **not** touched — task 4 of this wave's brief scoped that out as a separate,
19-repository-wide refactor; the pool now bounds the concurrent cost of that pattern (a hard cap at
`maximumPoolSize`) without eliminating the per-call open/close overhead itself.

**New dependencies:** `org.hibernate.orm:hibernate-hikaricp` (declared for its Hibernate-version-matched
`com.zaxxer:HikariCP` version pin only — its own `HikariCPConnectionProvider` is not what's wired, see
above) and `com.zaxxer:HikariCP` itself at explicit compile scope (`hibernate-hikaricp`'s own `pom.xml`
declares it `runtime`-scope, which is not enough for `PersistenceUnit` to reference `HikariConfig`/
`HikariDataSource` directly). Both versions resolve from `spring-boot-dependencies` (this module's
grandparent POM), no root-pom `<dependencyManagement>` pin needed, same as every other dependency here.

**Tests:** `./mvnw -B -pl storage/persistence test` — **148/148 green** (up from 137: new
`PersistencePoolSettingsTest` (7, docker-free compact-constructor validation) +
`ClosingDatasourceConnectionProviderTest` (2, docker-free, hand-rolled fake `DataSource`s) +
`PostgresDockerIntegrationTest$ConnectionPoolTests` (2, real Postgres — wired-provider-class assertion
+ a 2-connection pool actually refusing a third concurrent `EntityManager`) — see Tests above for each
class's own breakdown). Docker confirmed available and used throughout (`docker --version` → `Docker
version 28.3.3, build 980b856`); every `PostgresDockerIntegrationTest` nested class, including the two
new ones, actually ran — nothing skipped. Verified via log grep, not just code inspection, that
Hibernate's `HHH10001002: Using built-in connection pool (not intended for production use)` warning no
longer appears anywhere in a full test-suite run (0 occurrences), while `ClosingDatasourceConnectionProvider`
being wired in its place appears 19 times — once per `EntityManagerFactory` the suite builds.

**Deviations from the brief:** one real design deviation, called out rather than silently decided. The
brief named `hibernate.hikari.*` properties (i.e. Hibernate's own `HikariCPConnectionProvider`) as the
configuration surface; that provider was not used as-is because it cannot share a `DataSource` with
Flyway (see above) — `PersistencePoolSettings`' fields still map onto the same conceptual knobs the
brief asked for (`maximumPoolSize`, `minimumIdle`, `connectionTimeout`, `leakDetectionThreshold`), just
applied to a hand-built `HikariConfig`/`HikariDataSource` instead. Nothing else deviated: `JpaOperations`
untouched (task 4, explicitly out of scope), no `db/migration/**` change (this wave adds a pool, not a
schema change), no file outside `storage/persistence/**` touched — `station/vision-app/src/main/resources/application.yaml`,
every file under `.../config/wiring/`, `station/vision-api/**`, and `VisionPersistenceProperties.java`
were all left alone per the brief's exclusive-scope constraint. The exact `vision.persistence.pool.*`
keys, defaults, and the `PersistenceWiringConfiguration`/`VisionPersistenceProperties` changes needed to
actually bind them are reported to the orchestrator, not applied here — see "Bootstrap and connection
pool" above for the table.

## docs/plans/active/SCALE-100-PLAN.md S4 done (telemetry write path — batching)

Three items from the brief: (1) drop `JpaTelemetryRepository`'s per-`save` `em.flush()` where the
retention delete doesn't need it, (2) batch samples per usage behind a size-or-time bound, both
configurable, (3) coalesce `UsageTracker`'s second write (the `AssetUsage` summary-counter update,
`contexts/vision-perception`) onto the same batch boundary. See "Batching (SCALE-100-PLAN S4)" above
for the full `JpaTelemetryRepository`/`TelemetryBatchSettings` mechanism; `contexts/vision-perception`'s
own MODULE.md documents `UsageTracker`/`UsageSummaryBatchSettings`'s structurally parallel side.

**Design constraint that shaped everything:** no existing constructor signature could change (an
existing 9-argument `UsageTracker` test-seam constructor is called positionally by
`UsageTrackerTest`, and `JpaTelemetryRepository`'s existing two constructors are called throughout
this module's own tests) and no existing assertion could be edited. Every new capability therefore
arrived as a **new trailing-argument constructor overload** that the shorter, pre-existing ones now
delegate into with an explicit `.immediate()`/synchronous default — so production behavior does not
change until something actually calls the new overload with non-immediate settings. Concretely:
`JpaTelemetryRepository` gained a third constructor `(EntityManagerFactory, int, TelemetryBatchSettings)`;
`UsageTracker` gained a new 8-argument public constructor (the 7-argument one's params plus
`UsageSummaryBatchSettings`) and a new 10-argument package-private test-seam constructor (the
9-argument one's params plus the same) — full detail in that module's MODULE.md.

**The durability window:** `TelemetryBatchSettings`/`UsageSummaryBatchSettings` both default
`batchWindowMillis` to **200ms** (`DEFAULT_BATCH_WINDOW_MILLIS`), comfortably under the brief's 250ms
crash-loss ceiling, rather than defaulting to 0 (zero loss). CLAUDE.md rule 9 ("newest data wins, even
if previous is still available") is why: the brief's own acceptance criterion — "telemetry loss on a
`kill -9` is bounded by the configured window and is covered by a test" — only makes sense as a
requirement if loss is actually possible by default. `0` remains a fully supported, explicit opt-out
(`isImmediate()` reads `true`) for a deployment that wants zero loss over ingest throughput; both
settings records validate `batchWindowMillis >= 0` and `batchSizeSamples >= 1` in their compact
constructors, matching this module's usual validation idiom.

**Not wired into production by this task** — deliberately, since wiring is reserved to the
orchestrator/S7, not this task's file scope (`station/vision-app/src/main/resources/application.yaml`,
anything under `.../config/wiring/`, and `station/vision-api/**` were not touched):

- `PersistenceWiringConfiguration`'s `JpaTelemetryRepository` bean (currently the one-argument
  constructor) needs to move to the three-argument constructor with a `TelemetryBatchSettings`
  bound from a new `vision.persistence.telemetry.batch-size`/`batch-window` property pair (defaulted
  to `TelemetryBatchSettings.defaults()`'s own numbers, per the "opt-in guardrail" — though here the
  *code* default is already non-immediate, so no config default swap is actually needed to preserve
  today's default-config behavior, since nothing calls the new constructor yet).
- `ApplicationServiceWiring`'s `UsageTracker` bean (currently the 7-argument constructor) needs to
  move to the new 8-argument one with a `UsageSummaryBatchSettings` bound from the **same** property
  pair — the plan's intent is one number governing both write paths, even though they are two
  separate settings types in two separate modules (a context module cannot depend on the adapter
  module to share one type).
- A new `VisionPersistenceProperties` field (or nested record) for the two numbers, following this
  module's existing property-binding precedent for `PersistencePoolSettings`.

**Tunable constants for S7** (name — value — meaning):

| Constant | Value | Meaning |
|---|---|---|
| `TelemetryBatchSettings.DEFAULT_BATCH_SIZE_SAMPLES` | `100` | Samples buffered per usage before a flush is forced regardless of the time bound — a safety ceiling, rarely the binding constraint at typical telemetry rates. |
| `TelemetryBatchSettings.DEFAULT_BATCH_WINDOW_MILLIS` | `200L` | Max crash-loss window per open usage for the telemetry write path; under the plan's 250ms ceiling. |
| `UsageSummaryBatchSettings.DEFAULT_BATCH_SIZE_SAMPLES` | `100` | Same role as above, for the coalesced `AssetUsage` summary write (`contexts/vision-perception`). |
| `UsageSummaryBatchSettings.DEFAULT_BATCH_WINDOW_MILLIS` | `200L` | Same role as above; meant to be wired from the same property as the telemetry one so the two stay in lockstep. |

**Tests:** `./mvnw -B -pl storage/persistence test` — **157/157 green** (up from 148: new
`TelemetryBatchSettingsTest`, 6, docker-free compact-constructor/`defaults()`/`immediate()` validation,
mirroring `PersistencePoolSettingsTest`'s own shape; `TelemetryRepositoryTests` 7→10, +3 — see Tests
above for all three). Docker confirmed available (real `postgres:16` Testcontainers instance, not
skipped); every nested class, including the three new batching cases, actually ran.
`contexts/vision-perception` side: `./mvnw -B -pl contexts/vision-perception test` — **494/494 green**
(up from 492) — `UsageTrackerTest` 20→22, +2 (see that module's own MODULE.md).

> Count these from Maven's own summary line, never by summing `target/surefire-reports/TEST-*.xml`.
> That sum is wrong in both directions: reports for renamed or deleted classes linger and inflate it,
> and `PostgresDockerIntegrationTest`'s `@Nested` classes — which Maven counts one by one — land in a
> single aggregate XML that undercounts them (138 by that sum, 157 by Maven, for this same run).

**Deferred / left for the orchestrator:** the wiring bullets above (`PersistenceWiringConfiguration`,
`ApplicationServiceWiring`, `VisionPersistenceProperties`), all outside this task's file scope. Nothing
else from the S4 brief was left undone.

## docs/plans/active/DRONE-ONBOARDING-PLAN.md O5 done (vehicle profile, readiness, remediation — persistence half)

Two brand-new repository ports (thirteenth/fourteenth): `VehicleProfileEntity`/`JpaVehicleProfileRepository`
(`VehicleProfileRepositoryPort`, append-only, `V17__vehicle_profiles.sql`) and `FeatureRequirementEntity`/
`JpaFeatureRequirementRepository` (`FeatureRequirementRepositoryPort`, read-only reference data seeded by
`V18__feature_requirements.sql`), plus `V19__asset_usage_phase.sql` adding `phase`/`first_armed_at`/
`last_disarmed_at` to `asset_usages` (see the Schema section entries above for all three migrations'
full column-level detail, and the `entity`/`Tests` sections above for `VehicleProfileEntity`/
`FeatureRequirementEntity`'s own bullets and `VehicleProfileRepositoryTests`/`FeatureRequirementRepositoryTests`).
Registered in `PersistenceUnit`; wired into vision-app behind `vision.persistence.enabled` exactly like
every other port here (see station/vision-app/MODULE.md for `PersistenceWiringConfiguration#vehicleProfileRepositoryPort`/
`#featureRequirementRepositoryPort` and station/vision-api/MODULE.md for the REST surface).

**A real bug caught and fixed mid-wave, not by this wave's own original tests**: `V19` originally
shipped schema-only, on the (correct at the time) assumption that O7 (`contexts/vision-warehouse`,
a concurrent worktree) would land `AssetUsage#phase()`/`#firstArmedAt()`/`#lastDisarmedAt()` and own
wiring the column. O7 landed `phase` only, then reported that `AssetUsageEntity`/`AssetUsageMapper`
mapped nothing to it — every reload silently reverted a `UsageTracker`-computed phase back to
`PREFLIGHT`. Fixed in this module: `AssetUsageEntity` gained an `@Enumerated(EnumType.STRING) phase`
field reusing the domain `UsagePhase` enum directly (`GeofenceZoneEntity#kind` convention);
`AssetUsageMapper` now writes it on `toEntity` and reads it on `toDomain`, falling back through
`AssetUsage`'s pre-O7 8-arg constructor (defaults to `PREFLIGHT`) only for a genuinely `null` column
(a pre-V19 row) — never for a value the mapper itself wrote. Three new tests prove the round trip,
the upsert-transitions-phase case, and the legacy-null-defaults-to-PREFLIGHT fallback (see
`AssetUsageRepositoryTests`, Tests above, for all three names). `first_armed_at`/`last_disarmed_at`
stay unmapped — no domain field exists for either yet.

`./mvnw -B -pl storage/persistence -am test`: **176/176 green**, run three consecutive times in the
foreground (not backgrounded — a backgrounded run from earlier in this task died when the invoking
turn ended, taking its result with it) against a real `postgres:16` Testcontainers instance every
time, docker confirmed available, zero `[ERROR]`-prefixed lines in any of the three logs. `./mvnw -B
-pl station/vision-api -am test`: **633/633 green** ×3. `./mvnw -B -pl station/vision-app -am test
-DskipWeb`: **208/208 green** ×3, including `ArchitectureTest` 14/14, `ContextArchitectureTest` 4/4,
and `OnboardingWiringTest` 3/3 — the flag-off guardrail: every pre-existing api/app test still passes
unchanged with `vision.onboarding.probe.enabled` at its default `false`, and no bean of the real
`VehicleConfigPort` shape exists when the flag flips `true` (a deliberate fail-fast until O4's
implementation is wired — see station/vision-app/MODULE.md).

**Plan defects found**: the frozen wire contract (§8.1) names `firstArmedAt`/`lastDisarmedAt` as
`AssetUsage` fields; O7's actual implementation added only `phase`. The two DB columns exist
(additive, harmless idle) but have no domain field to map to/from yet — deferred, not a bug in this
wave's own scope. Also: the plan's module-placement table assigned `AssetUsage#phase` wiring to O7;
in practice O5 (this wave) ended up owning the entity/mapper fix after O7 flagged the gap — worth
correcting in the plan doc for future readers reconstructing wave ownership.

**Also present post-merge, deliberately not wired by this wave**: `git merge feat/drone-onboarding`
brought O4's `MavlinkVehicleConfigurator` (a real `VehicleConfigPort` implementation,
`drone-link/mavlink`) into this worktree's tree. `OnboardingWiringConfiguration`'s
`@ConditionalOnProperty(havingValue = "true")` branch still has no bean — wiring O4's real
implementation in was outside this wave's ask and is flagged here as a follow-up, not silently done.

**Deviations from the brief**: none, beyond the phase-mapper fix and merge described above, both
explicitly requested mid-task.

## docs/plans/active/DRONE-ONBOARDING-PLAN.md O11 done (the flight passport — persistence half)

Strictly `contexts/vision-flight`/`storage/persistence` file scope (a concurrent `station/vision-web`
wave elsewhere covers the UI). `V20__vehicle_profile_usage_link.sql` widens `vehicle_profiles`
(V17) with nullable `usage_id`/`phase`, plus `idx_vehicle_profiles_usage_phase` — see the Schema
section above for the full column-level detail. `VehicleProfileEntity` gained the matching two
nullable fields (`phase` reusing the domain `FlightPhase` enum directly, `AssetUsageEntity#phase`'s
own convention); `VehicleProfileMapper` gained a tagged `toEntity(DeviceId, UsageId, FlightPhase,
VehicleProfile)` overload the untagged one now delegates to; `JpaVehicleProfileRepository` gained
`save(DeviceId, UsageId, FlightPhase, VehicleProfile)`/`findByUsageAndPhase(UsageId, FlightPhase)` —
see the `repository`/`mapper`/`entity` sections above for each type's own updated bullet. No new
Flyway location, no new repository port, no new mapper file — this wave extends three existing
types rather than adding new ones (the append-only `vehicle_profiles` table already fit the "one
snapshot capture = one new row" shape a tagged save needed).

`contexts/vision-flight`'s own new domain types (`ParameterDrift`, `ConfigDriftCalculator`,
`FlightPassport`) and the three new `VehicleProfileService` methods (`captureSnapshot`/`passport`/
`driftFromPreviousFlight`) live in that module, not this one — see `contexts/vision-flight/MODULE.md`
for their full contract, the exit-criterion drift scenario, and the two judgment calls flagged there
(which snapshot pair `driftFromPreviousFlight` compares, and why `first_armed_at`/`last_disarmed_at`
stay out of scope for this wave too — see next paragraph).

**`first_armed_at`/`last_disarmed_at`, revisited**: still schema-only, unchanged by this wave. O11's
brief explicitly asked whether the passport needs them; it does not — `FlightPassport`/
`ConfigDriftCalculator` key entirely off `VehicleProfile.observedAt()` (an actual capture timestamp)
and `AssetUsage.startedAt()` (already mapped by O5/O7), never off arm/disarm timestamps. Closing this
gap properly (a `firstArmedAt`/`lastDisarmedAt` domain field, `AssetUsageEntity` columns, `AssetUsageMapper`
both directions, a round-trip test) is a `vision-warehouse`/`storage/persistence` `AssetUsage` change
this wave's `contexts/vision-flight`-plus-`storage/persistence` file scope does not include touching
`vision-warehouse` for — flagged again rather than silently worked around a second time.

`./mvnw -B -pl contexts/vision-flight -am test`: **240/240 green** ×3 (216 pre-O11 + 24 new — 4
`ParameterDriftTest` + 5 `ConfigDriftCalculatorTest` + 3 `FlightPassportTest` + 12 in
`DefaultVehicleProfileServiceTest`). `./mvnw -B -pl storage/persistence -am test`: **182/182 green**
×3 (176 pre-O11 + 6 new — 5 in `VehicleProfileRepositoryTests` + 1 new top-level schema test),
against a real `postgres:16` Testcontainers instance every time, docker confirmed available, zero
`[ERROR]`-prefixed lines and zero `<failure>`/`<error>` entries in the surefire XML in any run.

**Post-merge-review fix, entirely inside `contexts/vision-flight`, no persistence-layer change**:
review caught that `passport`'s original usage-ownership check went through `AssetDetails#recentUsages()`,
capped to the 20 most recent flights — a passport for an older flight 404'd even though it genuinely
belonged to the asset. Fixed by adding `AssetUsageRepositoryPort` (already implemented here,
`JpaAssetUsageRepository` — see the `repository` section above, unchanged by this fix) as a 5th
constructor parameter on `DefaultVehicleProfileService` and resolving the usage via its uncapped
`findById` instead. Nothing in this module needed to change: no new port, no new query, no schema
change — see `contexts/vision-flight/MODULE.md`'s own Status/Gotchas entries for the full story. The
counts above already include this fix's 2 additional tests and re-run numbers.

**Out-of-module call sites this wave leaves open** (not touched, per this wave's own strict file
scope): `vision-perception`'s `UsageTracker` needs to actually call `VehicleProfileService#captureSnapshot`
at the `PREFLIGHT`/`POSTFLIGHT` phase transitions — nothing calls it automatically today, this wave
only builds the capability; `vision-api`/`vision-app` need controllers + DTOs + Spring wiring for
`passport`/`driftFromPreviousFlight` (and `captureSnapshot` if it should also be manually
triggerable) — `PersistenceWiringConfiguration`'s existing `VehicleProfileRepositoryPort`/
`AssetUsageRepositoryPort` beans need no change (same ports, `AssetUsageRepositoryPort` was already
exposed for other consumers); **`OnboardingWiringConfiguration`'s `new DefaultVehicleProfileService(...)`
call site does need updating** — the post-review fix added a 5th constructor argument, so that call
site (currently 4 arguments) will not compile until it passes the already-available
`AssetUsageRepositoryPort` bean.

**Deviations from the brief**: none. `VehicleProfileService` was extended rather than a new
`FlightPassportService` interface created — see `contexts/vision-flight/MODULE.md`'s own note on
this, a java-clean-code §5 call, not a deviation from what was asked.

## Database change audit done (V21, persistence-only wave)

Adds a durable, unbypassable, database-level record of what actually changed in Postgres, row by row —
see "Database change audit" above for the full mechanism, the included/excluded table lists (and why),
the deliberate absence of a `vision.persistence.*` flag, and the open retention question. New:
`V21__db_audit_log.sql` (a table, a generic `audit_row_change()` PL/pgSQL trigger function, and
seventeen per-table triggers), `DbAuditLogEntity`/`DbAuditOperation` (`entity` package), and
`JpaDbAuditLogRepository` (`repository` package — the one class there implementing no port, by design;
see its own javadoc and "Database change audit" above for why no port was added).

`./mvnw -B -pl storage/persistence -am test`: **187/187 green** (up from 182, measured directly —
`git stash` of this wave's changes against the same branch tip, run, then `git stash pop` and re-run,
both against a real `postgres:16` Testcontainers instance, docker confirmed available both times, zero
`[ERROR]`-prefixed lines and `skipped="0"` in every surefire XML in either run): new
`DbAuditLogRepositoryTests` (2) + `DbAuditLogCoverageTests` (2) + one new top-level schema test
(`v21MigrationCreatesTheDbAuditLogTableOnTopOfV1ThroughV20`) = +5. See "Tests" above for what each
proves — in particular, `DbAuditLogRepositoryTests` proves the trigger end to end through a real
`Jpa*Repository` write (insert/update/delete), including that an UPDATE names its changed columns, and
`DbAuditLogCoverageTests` proves the included/excluded table lists match the live schema rather than
just the migration's own comment.

`./mvnw -B -pl station/vision-app -am test`: **214/214 green, unchanged** — this was also the count
before this wave (confirmed by the coordinator's own before-measurement of the same branch tip), because
no file inside `station/vision-app`'s scope for this task (`VisionPersistenceProperties`,
`PersistenceWiringConfiguration`, the `vision.persistence` block of `application.yaml`) needed to
change: there is genuinely no runtime-tunable value this feature introduces (see "no flag,
deliberately" above) and no bean to wire (this class is read-only infrastructure with no port, per the
brief). Zero `[ERROR]`-prefixed lines, `skipped="0"` throughout.

**Deviations from the brief**: none that change behavior. One classification judgment call, flagged
rather than silently decided: `asset_images` is not named in either the brief's "include" or "exclude"
examples, and was placed in the excluded set (grouped with `sample_images`) rather than the included
one — see "Database change audit" above for the reasoning (a `bytea` blob duplicated into every audit
row is a different cost profile than a small config row, and an asset photo is content, not the kind of
configuration value this feature exists to answer "who changed X" for).

**Deliberately not done, per the brief**: no retention/purge job for `db_audit_log` (documented above as
an open item, matching `audit_entries`'s own accepted unbounded-growth posture); no `vision.persistence.*`
flag for trigger installation (schema-governed, not application-configurable — documented above rather
than faked); no port/domain type for the read side (kept entirely inside this module, per the brief's
own instruction to stop and report rather than add one speculatively — reported here: no port is
warranted today, since there is no consumer yet).
