# adapter-persistence

JPA/Postgres persistence adapter for every repository port the platform has: the fleet-side ports
— categories, devices, assets (docs/MVP2-PLAN.md P-a) — the history ports — asset usages,
telemetry samples, detection results (docs/MVP2-PLAN.md P-b) — geofence zones
(docs/OPS-CORE-PLAN.md §G, G-b) — identity: users + groups (docs/U-AUTH-PLAN.md wave 3) —
pilot→asset assignments (docs/U-SCOPE-PLAN.md slice 2) — and tactical marks, the shared
operational picture (docs/TACTICAL-MARKS-PLAN.md M2).

**Depends on:** vision-domain, `org.hibernate.orm:hibernate-core`, `org.postgresql:postgresql`,
`org.flywaydb:flyway-core`/`flyway-database-postgresql`, `tools.jackson.core:jackson-databind`
(Jackson 3, for jsonb columns — see Conventions) · **Used by:** vision-app
(`PersistenceWiringConfiguration`, opt-in via `vision.persistence.enabled`)
**Build/test:** `./mvnw -B -pl adapters/adapter-persistence test` — 101 tests (up from 78, docs/CV-TRAINING-PLAN.md
Wave T3 — new `DatasetEntity`/`JpaDatasetRepository`, `TrainingSampleEntity`/`JpaTrainingSampleRepository`,
`SampleImageEntity`/`JpaSampleImageStore`, and `FilesystemDatasetExport`, +19 Postgres-backed round-trip/schema
tests +4 pure-filesystem tests; up from 71, docs/TACTICAL-MARKS-PLAN.md
M2 — new `MarkEntity`/`JpaMarkRepository`, +6 round-trip tests +1 schema test; up from 66, docs/U-SCOPE-PLAN.md
slice 2 — new `AssignmentEntity`/`AssignmentId`/`JpaAssignmentRepository`, +4 round-trip tests +1 schema test; up from 56, docs/OPS-CORE-PLAN.md
G-b — new `GeofenceZoneEntity`/`JpaGeofenceRepository`, +6 round-trip tests +1 schema test; up from 46, docs/FC-INTEGRATIONS-PLAN.md
F-b — `TelemetrySampleEntity` gained `flight_state`, +2 round-trip tests +1 schema test; up from 42, docs/UX-REWORK-PLAN.md
§U-d item 3 — new `AssetImageEntity`/`JpaAssetImageRepository`, +4 tests; up from 39, docs/MVP2-PLAN.md R-a2 —
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

### `com.drones.vision.adapter.persistence`
- `final class PersistenceUnit` — `static EntityManagerFactory start(String jdbcUrl, String username, String password)`: migrates the schema with Flyway (`classpath:db/migration`) then opens a Hibernate-native `EntityManagerFactory` mapping all six entities below (see "Bootstrap" below). The one public entry point vision-app's wiring needs.
- `final class JpaCategoryRepository implements CategoryRepositoryPort` — constructor `(EntityManagerFactory)`.
- `final class JpaDeviceRepository implements DeviceRepositoryPort` — constructor `(EntityManagerFactory)`.
- `final class JpaAssetRepository implements AssetRepositoryPort` — constructor `(EntityManagerFactory)`.
- `final class JpaAssetUsageRepository implements AssetUsageRepositoryPort` — constructor `(EntityManagerFactory)`. docs/MVP2-PLAN.md P-b.
- `final class JpaTelemetryRepository implements TelemetryRepositoryPort` — constructor `(EntityManagerFactory)` (production default retention cap, see Retention below) or `(EntityManagerFactory, int retentionLimitPerUsage)` (test/override seam). docs/MVP2-PLAN.md P-b.
- `final class JpaDetectionRepository implements DetectionRepositoryPort` — constructor `(EntityManagerFactory)` or `(EntityManagerFactory, int retentionLimitPerStream)`, same shape as `JpaTelemetryRepository`. docs/MVP2-PLAN.md P-b.
- `final class JpaAssetImageRepository implements AssetImageRepositoryPort` — constructor `(EntityManagerFactory)`. docs/UX-REWORK-PLAN.md §U-d item 3 — the asset image store (CONTRACT 2).
- `final class JpaGeofenceRepository implements GeofenceRepositoryPort` — constructor `(EntityManagerFactory)`. docs/OPS-CORE-PLAN.md §G, G-b — geofence zones; `save` is merge-by-id (upsert), `deleteById` a real hard delete (zones have no soft-delete concept — a disabled zone is just `enabled=false`, not a lifecycle state).
- `final class JpaUserRepository implements UserRepositoryPort` — constructor `(EntityManagerFactory)`. docs/U-AUTH-PLAN.md wave 3 — the identity aggregate; `save` is merge-by-id (upsert). `findByUsername` lower-cases its lookup key (`Locale.ROOT`) then exact-matches `users.username` (the domain already stores it lower-cased, so this *is* the case-insensitive lookup; a `NoResultException` from the single-result query maps to empty `Optional`). Memberships ride on the row as jsonb (see `UserEntity`).
- `final class JpaGroupRepository implements GroupRepositoryPort` — constructor `(EntityManagerFactory)`. docs/U-AUTH-PLAN.md wave 3 — org-chart nodes; `save` is merge-by-id (upsert); `parentGroupId` maps straight through as a nullable `UUID`.
- `final class JpaAssignmentRepository implements AssignmentRepositoryPort` — constructor `(EntityManagerFactory)`. docs/U-SCOPE-PLAN.md slice 2 — the pilot→asset join; `assign` is an idempotent upsert via `merge` on the composite (pilot, asset) key (no duplicate row, no error), `unassign` a delete-if-present (idempotent); `assetsForPilot`/`pilotsForAsset` are indexed JPQL queries returning `UUID`s mapped to `AssetId`/`UserId`, `isAssigned` a composite-PK `find`. Matches `InMemoryAssignmentRepository`'s set-semantics exactly.
- `final class JpaMarkRepository implements MarkRepositoryPort` — constructor `(EntityManagerFactory)`. docs/TACTICAL-MARKS-PLAN.md §3/M2 — tactical marks (the shared operational picture); `save` is merge-by-id (upsert), `deleteById` a real hard delete (idempotent) — same shape as `JpaGeofenceRepository`, `Mark`'s own template.
- `final class JpaDatasetRepository implements DatasetRepositoryPort` — constructor `(EntityManagerFactory)`. docs/CV-TRAINING-PLAN.md §1, Wave T3 — training datasets; `save` is merge-by-id (upsert), `delete` a real hard delete (idempotent), same shape as `JpaGeofenceRepository`/`JpaMarkRepository`. `targetCategory` maps a nullable `CategoryId` to/from a plain nullable varchar.
- `final class JpaTrainingSampleRepository implements TrainingSampleRepositoryPort` — constructor `(EntityManagerFactory)`. docs/CV-TRAINING-PLAN.md §1, Wave T3 — captured frames + their evolving annotations; `save` is merge-by-id (upsert — a sample mutates over its own review lifecycle, unlike `JpaDetectionRepository`'s append-only rows). `findByDataset`/`countByDataset` share one JPQL-with-optional-clause shape for the `(datasetId, statusOrNull)` filter `idx_training_samples_dataset_status` indexes; `findByDataset` orders newest-captured-first before bounding to `limit`.
- `final class JpaSampleImageStore implements SampleImageStorePort` — constructor `(EntityManagerFactory)`. docs/CV-TRAINING-PLAN.md §1/§C, Wave T3 — the `JpaAssetImageRepository` shape, verbatim, reused for training-sample frames; `save` is merge-by-`sampleId` (upsert).
- `final class FilesystemDatasetExport implements DatasetExportPort` — constructor `(Path exportRoot)`. docs/CV-TRAINING-PLAN.md §1/§5, Wave T3 — writes one zip file per export (`<exportRoot>/<datasetId>/<exportId>.zip`) containing the frozen YOLO layout (`data.yaml`, `images/<name>`, `labels/<name>`) built directly with `ZipOutputStream` — no loose unzipped directory is ever written (see the class's own javadoc for why). Not wired to a Spring bean yet — `vision.training.export-dir` and its `@Bean` land with T4's `VisionTrainingProperties`.
- package-private `final class JpaOperations` — the `write(Function<EntityManager,T>)`/`read(Function<EntityManager,T>)` transaction-boilerplate helper every `Jpa*Repository` composes rather than extends (each opens/commits/closes its own short-lived `EntityManager` per call — see Gotchas).

### `com.drones.vision.adapter.persistence.entity`
- `CategoryEntity`, `DeviceEntity`, `AssetEntity`, `AssetUsageEntity`, `TelemetrySampleEntity`, `DetectionResultEntity`, `AssetImageEntity`, `GeofenceZoneEntity`, `UserEntity`, `GroupEntity`, `AssignmentEntity`, `MarkEntity`, `DatasetEntity`, `TrainingSampleEntity`, `SampleImageEntity` — plain JPA entities, field-annotated (protected no-arg ctor for JPA, a public all-args ctor and no-prefix accessors — e.g. `id()`, `name()` — for symmetry with the domain records they mirror). Never referenced outside this module; each `Jpa*Repository` owns its entity↔domain mapping as private static `toEntity`/`toDomain` methods, so the mapping logic lives right next to the port it serves rather than in separate mapper classes (each mapper is used by exactly one class — per `.claude/skills/java-clean-code/SKILL.md`, a dedicated `Mapper` type for a 1:1 relationship is unneeded ceremony).
- `TelemetrySampleEntity`/`DetectionResultEntity` have a synthetic UUID `id` the adapter invents at save time (`UUID.randomUUID()` in each repository's `toEntity`) — `Telemetry`/`DetectionResult` themselves carry no identity of their own (append-only samples/results, not aggregates), so there is nothing domain-side to derive a primary key from; the id never surfaces back through the ports.
- `TelemetrySampleEntity#flightState` (docs/FC-INTEGRATIONS-PLAN.md F-b, `V6__telemetry_flight_state.sql`) is a nullable `FlightState` field, `@JdbcTypeCode(SqlTypes.JSON)`/`columnDefinition = "jsonb"` — the domain record stored **directly**, exactly the `DetectionResultEntity#detections` precedent noted in Conventions below (a plain immutable record tree, no persistence-local wrapper type needed). `null` covers both "sample pre-dates this column" and "device reported no flight-controller state at all"; both round-trip as `Telemetry#flightState() == null`, the same nullable-9th-component contract the domain record itself defines — there is no way to tell the two cases apart from this column alone, and nothing needs to.
- `AssetUsageEntity#streamId` (docs/MVP2-PLAN.md R-a2, `V4__usage_stream_id.sql`) is a nullable `UUID` column, mapped straight through by `JpaAssetUsageRepository` (`streamId == null ? null : streamId.value()` / `new StreamId(...)`) exactly like every other nullable field on this entity — no special-casing beyond the null check.
- `AssetImageEntity` (docs/UX-REWORK-PLAN.md §U-d item 3, `V5__asset_images.sql`) is keyed by `assetId` itself, **not** a synthetic id like `TelemetrySampleEntity`/`DetectionResultEntity` above — there is at most one image per asset and `save` is always an upsert, so the primary key doubles as the "one row per asset" constraint with no separate unique index needed. `data` is a plain `byte[]` field (Hibernate's default mapping to Postgres `bytea`, no `@Lob`/converter needed) — the first non-jsonb, non-text binary column in this module's schema.
- `GeofenceZoneEntity` (docs/OPS-CORE-PLAN.md §G, G-b, `V7__geofence_zones.sql`) mirrors `GeofenceZone` field-for-field: `id` is the domain's own `ZoneId` (not synthetic — a zone has real identity, unlike `Telemetry`/`DetectionResult`), `kind` reuses the domain `ZoneKind` enum directly in an `@Enumerated(EnumType.STRING)` field (same "domain enums reused directly" convention `Capability`/`LifecycleState` already follow), `polygon` stores the whole `List<GeoPosition>` as one jsonb column (same mechanism/rationale as `DetectionResultEntity#detections` — a plain immutable record list Jackson serializes natively, only ever read back whole), `maxAltitudeMeters` a nullable `Double`, `enabled` a plain `boolean`. No FK to any other table — zones are global reference data with no relationship to assets/devices.
- `AssignmentEntity` (docs/U-SCOPE-PLAN.md slice 2, `V9__pilot_assignments.sql`) is a plain join row — a pilot→asset link with **no synthetic id**: its primary key is the composite (`pilot_user_id`, `asset_id`) via `@IdClass(AssignmentId.class)`. `AssignmentId` is a plain mutable class with a public no-arg ctor + matching field names (JPA's `@IdClass` contract — a record cannot satisfy it). The composite PK doubles as the uniqueness constraint that makes `assign` an idempotent upsert with no duplicate rows. The table also has an unmapped `assigned_at` bookkeeping column (Hibernate `validate` tolerates DB columns the entity does not map). No FK to `users`/`assets`, same convention as every other table here.
- `MarkEntity` (docs/TACTICAL-MARKS-PLAN.md §3/M2, `V10__marks.sql`) mirrors `Mark` field-for-field: `id` is the domain's own `MarkId` (not synthetic — a mark has real identity); `kind`/`status`/`source` reuse the domain `MarkKind`/`MarkStatus`/`MarkSource` enums directly in `@Enumerated(EnumType.STRING)` fields (same convention `GeofenceZoneEntity#kind` follows); `position` (a single `GeoPosition`, not a polygon) is flattened to `latitude`/`longitude`/nullable `altitude_meters` columns rather than jsonb — same "flatten a small value type into columns" choice `AssetUsageEntity` makes for `GeoPosition`, not `GeofenceZoneEntity#polygon`'s jsonb choice for a whole vertex list; `ownership` is flattened to `owner_id`/`group_id`, same choice `AssetEntity` makes for `Ownership`. No FK to any other table.
- `DatasetEntity` (docs/CV-TRAINING-PLAN.md §1, Wave T3, `V11__training_datasets.sql`) mirrors `Dataset` field-for-field: `id` is the domain's own `DatasetId` (not synthetic); `classes` stores the whole ordered `List<String>` as jsonb, same mechanism/rationale as `GeofenceZoneEntity#polygon`; `ownership` is flattened to `owner_id`/`group_id`, same choice `AssetEntity`/`MarkEntity` make; `status` reuses the domain `DatasetStatus` enum directly (`@Enumerated(EnumType.STRING)`); `target_category` is a plain nullable varchar (the `CategoryId` slug, or `null`). No FK to any other table.
- `TrainingSampleEntity` (docs/CV-TRAINING-PLAN.md §1, Wave T3, `V11__training_datasets.sql`) mirrors `TrainingSample` field-for-field: `id` is the domain's own `TrainingSampleId` (not synthetic — a sample mutates over its own review lifecycle, so it needs a stable key to upsert by, unlike `TelemetrySampleEntity`/`DetectionResultEntity`'s synthetic ids for append-only rows); `annotations` stores the whole `List<Annotation>` as jsonb, same mechanism as `DetectionResultEntity#detections`; `status` reuses the domain `SampleStatus` enum directly; `asset_id`/`labeled_by`/`labeled_at` are nullable columns, mirroring the domain record's own nullability. No FK to `datasets` or any other table.
- `SampleImageEntity` (docs/CV-TRAINING-PLAN.md §1/§C, Wave T3, `V11__training_datasets.sql`) is the `AssetImageEntity` precedent applied to training frames instead of asset photos: `sampleId` is the primary key rather than a synthetic one (at most one image per sample, `save` always an upsert); `data` a plain `byte[]`/`bytea` column, no `@Lob`/converter needed. No JPA relationship to `TrainingSampleEntity`.
- `UserEntity`/`GroupEntity` (docs/U-AUTH-PLAN.md wave 3, `V8__users_groups.sql`) mirror `User`/`Group` field-for-field. `UserEntity#id` is the domain's own `UserId` (not synthetic — a user has real identity); `username` carries a `UNIQUE` constraint and is stored already-lower-cased (the domain `User` normalizes it), so `findByUsername` is an exact match on the stored value after lower-casing the lookup key. **`UserEntity#memberships` stores the whole `List<Membership>` as one jsonb column** (`@JdbcTypeCode(SqlTypes.JSON)`) — same mechanism/rationale as `GeofenceZoneEntity#polygon`/`DetectionResultEntity#detections`: `Membership` (with its nested `GroupId`/`Role`) is a plain immutable record Jackson 3 serializes natively, and memberships are only ever read back whole with the aggregate, so no normalized join table (docs/U-AUTH-PLAN.md picked jsonb over a join table for exactly this "saved whole with the User" reason). `GroupEntity#parentId` is a nullable `UUID` (null = root group). No FK on either table (not `groups.parent_id`, not any user→group link) — same "no cross-entity foreign keys" convention as every other table here, keeping parity with the in-memory reference repos that do no referential checks.

## Schema (`src/main/resources/db/migration`)

- `V1__baseline.sql` — `categories` (`id` varchar PK — the `CategoryId` slug, not a UUID, matching the domain's one non-UUID id type; `parent_id` self-referencing FK, nullable; `attribute_hints` jsonb), `devices` (`id` UUID PK; `stream_protocol`/`stream_uri`/`stream_options` — `StreamDescriptor` flattened; `state` varchar), `device_capabilities` (element-collection join table, PK `(device_id, capability)`), `assets` (`id` UUID PK; `category_id` varchar — no FK, see Gotchas; `owner_id`/`group_id` UUID — `Ownership` flattened; `attributes` jsonb; `state` varchar), `asset_devices` (element-collection join table, PK `(asset_id, device_id)`, indexed on `device_id` for `findByDeviceId`).
- `V2__seed_categories.sql` — the same default category set `InMemoryCategoryRepository` seeds in its constructor (`drone`, `ip-camera`, `usb-camera`, `robot`, `simulated`, then `fpv-drone`→`drone`, `esp32-cam`→`ip-camera` in a second batch so the self-referencing FK is satisfied), `ON CONFLICT (id) DO NOTHING` so re-running is a no-op. Keeps a persistence-enabled app's out-of-the-box category list identical to the in-memory fallback's.
- `V3__history.sql` (docs/MVP2-PLAN.md P-b) — `asset_usages` (`id` UUID PK; `asset_id` UUID, indexed, no FK; `started_at`/`ended_at` timestamptz, the latter nullable; `start_latitude`/`start_longitude`/`start_altitude_meters` and the `last_*` triple — `GeoPosition` flattened to columns rather than jsonb, same "flatten a small value type" choice `AssetEntity` makes for `Ownership`; `sample_count` bigint), `telemetry_samples` (`id` UUID PK, synthetic; `usage_id` UUID + `at` timestamptz, **indexed together** as `(usage_id, at)`; `device_id` UUID; `latitude`/`longitude`/`altitude_meters`/`heading_degrees`/`battery_percent` all nullable doubles; `extra` jsonb), `detection_results` (`id` UUID PK, synthetic; `stream_id` UUID + `captured_at` timestamptz, **indexed together** as `(stream_id, captured_at)`; `frame_sequence` bigint; `detections` jsonb — the whole `List<Detection>`, see Conventions; `inference_latency_nanos` bigint). No FKs, same rationale as V1's tables (see Conventions).
- `V4__usage_stream_id.sql` (docs/MVP2-PLAN.md R-a2) — `ALTER TABLE asset_usages ADD COLUMN stream_id UUID` (nullable, no FK, no backfill — a stream's id was never recorded anywhere before this migration, so pre-existing rows simply read back `null`, matching `AssetUsage#streamId`'s own honest "legacy usage" nullability). Purely additive on top of V1-V3; no other table changes.
- `V5__asset_images.sql` (docs/UX-REWORK-PLAN.md §U-d item 3) — `asset_images` (`asset_id` UUID PK — no FK, same convention as every other table; `content_type` varchar; `data` bytea; `updated_at` timestamptz default `now()`). New table, no changes to any existing one.
- `V6__telemetry_flight_state.sql` (docs/FC-INTEGRATIONS-PLAN.md F-b) — `ALTER TABLE telemetry_samples ADD COLUMN flight_state JSONB` (nullable, no FK, no backfill — purely additive on top of V1-V5, same shape as V4's `stream_id` addition). Pre-existing rows simply read back `null`.
- `V7__geofence_zones.sql` (docs/OPS-CORE-PLAN.md §G, G-b) — `geofence_zones` (`id` UUID PK — the zone's own `ZoneId`, not synthetic; `name` varchar; `kind` varchar; `polygon` jsonb — the whole `List<GeoPosition>`, ≥3 vertices enforced application-side by `GeofenceZone`/`GeofenceZoneSpec`, not a database `CHECK`; `max_altitude_meters` nullable double precision; `enabled` boolean). New table, no changes to any existing one — no FK, same convention as every other table here.
- `V8__users_groups.sql` (docs/U-AUTH-PLAN.md wave 3) — `groups` (`id` UUID PK; `name` varchar; `parent_id` UUID nullable — null = root, no self-FK) and `users` (`id` UUID PK; `username` varchar `NOT NULL UNIQUE` — stored lower-cased by the domain, so plain UNIQUE gives the case-insensitive uniqueness `findByUsername` relies on; `display_name`/`email`/`password_hash` varchar; `enabled` boolean; `memberships` jsonb `NOT NULL` — the whole `List<Membership>`). Two new tables, purely additive over V1-V7, no FK (not `groups.parent_id`, no user→group link) — same convention as every other table here.
- `V9__pilot_assignments.sql` (docs/U-SCOPE-PLAN.md slice 2) — `pilot_assignments` (`pilot_user_id` UUID, `asset_id` UUID, `assigned_at` timestamptz default `now()`; **composite `PRIMARY KEY (pilot_user_id, asset_id)`** — the pair is the identity, and doubles as the uniqueness constraint making `assign` an idempotent upsert) plus a secondary index `idx_pilot_assignments_asset` on `asset_id` (the PK's leading column serves `assetsForPilot`; this index serves the `pilotsForAsset` direction). New table, purely additive over V1-V8, no FK — same convention as every other table here.
- `V10__marks.sql` (docs/TACTICAL-MARKS-PLAN.md §3/M2) — `marks` (`id` UUID PK — the mark's own `MarkId`, not synthetic; `kind`/`status`/`source` varchar; `label` varchar `NOT NULL`; `note` nullable text; `latitude`/`longitude` required double precision, `altitude_meters` nullable double precision — a single flattened `GeoPosition`, not jsonb, unlike `geofence_zones.polygon`; `owner_id`/`group_id` UUID — `Ownership` flattened, same choice `assets` makes; `created_at` timestamptz). New table, purely additive over V1-V9, no FK — same convention as every other table here. Indexed on `group_id` (scope filtering, application-layer job) and `status` (active/cleared filtering).
- `V11__training_datasets.sql` (docs/CV-TRAINING-PLAN.md §1/§3, Wave T3) — three new tables, purely additive over V1-V10, no FK between them or to any other table (same convention as the rest of this schema): `datasets` (`id` UUID PK — the dataset's own `DatasetId`; `target_category` nullable varchar; `classes` jsonb `NOT NULL` — the whole ordered `List<String>`; `owner_id`/`group_id` UUID — `Ownership` flattened; `status` varchar; `created_at` timestamptz), `training_samples` (`id` UUID PK — the sample's own `TrainingSampleId`, not synthetic; `dataset_id`/`stream_id` UUID `NOT NULL`; `asset_id` nullable UUID; `captured_at` timestamptz; `width`/`height` integer; `annotations` jsonb `NOT NULL` — the whole `List<Annotation>`; `status` varchar; `labeled_by` nullable UUID; `labeled_at` nullable timestamptz — plus a secondary index `idx_training_samples_dataset_status` on `(dataset_id, status)`, the exact filter `TrainingSampleRepositoryPort#findByDataset`/`#countByDataset` take), `sample_images` (`sample_id` UUID PK — mirrors `asset_images` exactly; `content_type` varchar; `data` bytea `NOT NULL`; `updated_at` timestamptz default `now()`).

## Bootstrap (no Spring, no connection pool)

`PersistenceUnit.start` uses Hibernate's **native** bootstrap API (`org.hibernate.cfg.Configuration`) rather than JPA's `Persistence.createEntityManagerFactory` (which needs a `META-INF/persistence.xml` or a hand-built `PersistenceUnitInfo`) or Spring Data JPA (`@EnableJpaRepositories`, Spring Boot's `HibernateJpaAutoConfiguration`, etc.). `Configuration#buildSessionFactory()` returns `org.hibernate.SessionFactory`, which **implements `jakarta.persistence.EntityManagerFactory` directly** (same for `Session`/`EntityManager`) — so every `Jpa*Repository` still only ever calls plain `jakarta.persistence` API, and callers (vision-app) hold a completely standard `EntityManagerFactory` reference with no Hibernate-specific type leaking across the module boundary.

This was a deliberate choice over Spring Data JPA: this codebase's adapters are plain classes constructed via `new` in `WiringConfiguration`/`PersistenceWiringConfiguration` (see vision-app's Bean inventory), never Spring-component-scanned — Spring Data repository interfaces are proxies the Spring Data repository factory generates at runtime and cannot be `new`'d, which would have forced `@EnableJpaRepositories` + Spring Boot's JPA autoconfiguration into the picture, and those autoconfigurations activate purely from classpath presence (`@ConditionalOnClass(DataSource.class)`, etc.) — meaning they would have needed to be **unconditionally excluded** from vision-app's `@SpringBootApplication` and then *conditionally re-enabled* per `vision.persistence.enabled`, a materially more complex (and more fragile) wiring story than the `@ConditionalOnProperty`-gated single bean this module's plain-JPA approach allows (see vision-app/MODULE.md's `PersistenceWiringConfiguration` entry).

No connection pool: Hibernate's default `DriverManagerConnectionProvider` (one physical JDBC connection per `EntityManager`, opened/closed by `JpaOperations` per call — see Gotchas) is what's wired, logging `HHH10001002: Using built-in connection pool (not intended for production use)` at startup — an accepted, documented tradeoff at this platform's single-instance/friends-demo scale, not a placeholder. Swapping in a pooled provider (e.g. HikariCP, `org.hibernate.orm:hibernate-hikaricp`, itself a Spring-Boot-managed dependency so no version pin would be needed) is a config-only change in `PersistenceUnit.start` if concurrency ever demands it — no repository or entity code would change.

`hibernate.hbm2ddl.auto=validate`: Flyway owns schema creation/evolution end to end; Hibernate only ever validates its entity mapping matches what Flyway already created, never generates or alters DDL itself.

## Conventions

- **jsonb via Hibernate's native JSON support, not a hand-rolled converter.** `attribute_hints`/`stream_options`/`attributes`/`extra`/`detections`/`flight_state`/`polygon`/`memberships` are `@JdbcTypeCode(SqlTypes.JSON)` fields with `columnDefinition = "jsonb"` — Hibernate 7.4 auto-detects a Jackson `ObjectMapper` on the classpath via its `FormatMapper` SPI and ships `org.hibernate.type.format.jackson.Jackson3JsonFormatMapper` specifically for Jackson 3 (`tools.jackson.*`, this house's Jackson generation under Spring Boot 4) — confirmed present in the `hibernate-core-7.4.1.Final` jar. No `AttributeConverter`, no `PGobject` juggling, no `stringtype=unspecified` JDBC-URL trick.
- **`DetectionResultEntity#detections`/`TelemetrySampleEntity#flightState` store the domain `Detection`/`FlightState` record trees directly** (`List<Detection>` with nested `BoundingBox`/`ModelRef`; a single nullable `FlightState` with its own `List<String> armingBlockers`, docs/FC-INTEGRATIONS-PLAN.md F-b) rather than a parallel adapter-local DTO shape — Jackson 3 serializes/deserializes Java records natively (canonical-constructor + component-name introspection, no annotations needed), proven by this module's own round-trip tests. Referencing a plain, framework-annotation-free domain record from an entity field is the same kind of "adapter depends on domain types" the enum reuse below already establishes; it's storage-format coupling to the domain's shape, not a framework leaking into the domain.
- **No cross-entity foreign keys beyond the join tables' own PKs**, deliberately: `categories.parent_id` is the one exception (self-referencing, satisfiable because `V2__seed_categories.sql` controls insert order), but `assets.category_id` has **no** FK to `categories.id`, `asset_devices.device_id` has **no** FK to `devices.id`, and none of `asset_usages`/`telemetry_samples`/`detection_results` (V3) has any FK at all. The in-memory reference repositories this adapter must stay behavior-compatible with (`InMemory*Repository`, vision-app devsupport) perform zero referential checks — a real constraint here would reject operations (e.g. saving an `Asset` whose category was never separately saved) that the in-memory port happily allows, breaking parity for exactly the "round-trip every port method the same way the in-memory impl does" contract this module is judged against.
- **`save()` is upsert-by-id** (`EntityManager#merge`) on the three P-a ports and `JpaAssetUsageRepository`, matching each in-memory repository's `Map#put` exactly. **`JpaTelemetryRepository#save`/`JpaDetectionRepository#save` always `persist` a brand-new row** instead (never `merge`) — samples/results are immutable historical records per their ports' contracts, and neither `Telemetry` nor `DetectionResult` carries an id to merge by. **`deleteById()` is a real hard delete, idempotent** (missing id ⇒ no-op) on `Device`/`Asset`, matching `Map#remove` exactly — soft-delete (`LifecycleState.DELETED`) is just a column value round-tripped like any other field; nothing in this module treats it specially, the same as the in-memory fallbacks.
- **Domain enums (`Capability`, `LifecycleState`) are reused directly** in `@Enumerated(EnumType.STRING)` entity fields rather than duplicated as adapter-local enums kept in sync by hand — the framework annotation lives on the entity's *field*, not on the domain enum's *declaration*, so `vision-domain` stays annotation-free (`ArchitectureTest#domainAndApplicationAreSpringAnnotationFree` — note: JPA's `jakarta.persistence`/`org.hibernate.*` annotations aren't `org.springframework..` either way, but the same "domain must not import framework code" principle applies and is respected).

## Retention (docs/MVP2-PLAN.md P-b)

`JpaTelemetryRepository`/`JpaDetectionRepository` each prune their oldest rows **on every write**, inside the same transaction as the insert — the simplest mechanism that is still correct, chosen over a scheduled/background sweep (one more moving part, one more thing to wire and test) or a database-side trigger (schema magic invisible to the Java code reading it):

1. `save` calls `EntityManager#persist` for the new row, then an explicit `EntityManager#flush()`.
2. A native `DELETE ... WHERE <key> = ? AND id NOT IN (SELECT id WHERE <key> = ? ORDER BY <timestamp> DESC LIMIT <cap>)` runs in the same transaction, keeping only the newest `<cap>` rows for that key.

The explicit `flush()` between `persist` and the native delete is required, not decorative: Hibernate has no way to know a hand-written native query touches `telemetry_samples`/`detection_results`, so without it the delete would run against the connection's pre-insert view of the table — once a usage/stream is already at capacity, that would prune the row just being appended instead of an older one.

`JpaTelemetryRepository`'s key is `usage_id` (matching `AssetUsageRepositoryPort`'s grouping); `JpaDetectionRepository`'s key is `stream_id` — the only grouping key `DetectionResult`/`DetectionQuery` actually carry (there is no `usageId` on a detection). Both default to **100,000 rows** (`DEFAULT_RETENTION_LIMIT_PER_USAGE`/`DEFAULT_RETENTION_LIMIT_PER_STREAM`) — generous (≈27h of continuous 1Hz telemetry for one usage; ≈2.75h of continuous 10fps detections for one stream) but finite, so a usage/stream nobody ever stops (e.g. a forgotten dev-mode stream) cannot grow either table unboundedly. Each repository also has a two-argument constructor (`EntityManagerFactory, int`) for overriding the cap — used by this module's own retention tests to exercise pruning without inserting six figures of rows first; **not currently wired to a `vision.persistence.*` Spring property** (see Status's honest gaps for why).

`JpaAssetUsageRepository` has **no** retention pruning: a usage row is written once per start/stop plus a handful of position/sample-count updates in between, not once per incoming sample — it is not the "append-heavy" table docs/MVP2-PLAN.md P-b's retention guard targets.

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
- `@Nested AssetUsageRepositoryTests` (9, up from 7 — docs/MVP2-PLAN.md R-a2) — open/closed usage
  round trip (incl. null-position open usage and full-position closed usage), a
  `null`-`streamId` usage round-tripping as `null` and a `streamId`-carrying usage round-tripping
  exactly, upsert-that-closes-an-open-usage (now also asserting the recorded `streamId` survives
  the close/upsert), `findRecentByAsset` newest-first + bounded by limit, `findOpenByAsset`
  found/not-found.
- `@Nested TelemetryRepositoryTests` (7, up from 5 — docs/FC-INTEGRATIONS-PLAN.md F-b) — round trip
  with every field populated and with only the required fields, per-usage isolation,
  `findByUsageReturnsEarliestSamplesFirstUpToLimit` — proves `findByUsage`'s limit selects the
  *earliest* samples (mirroring `InMemoryTelemetryRepository`'s actual behavior, see
  `JpaTelemetryRepository`'s javadoc), not the newest — plus two new `flightState` round-trip cases:
  a full `FlightState` (including nested nullable sub-fields and a non-empty `armingBlockers`) round
  trips exactly, and a sample built via `Telemetry`'s 8-arg convenience ctor (no `flightState` at
  all) reads back with `flightState() == null`, the same honest-null contract a real pre-V6 row
  would also satisfy.
- `@Nested DetectionRepositoryTests` (6) — round trip of detections + inference latency, `streamId`
  filter, `queryTimeRangeIsInclusiveOnBothEndsMatchingInMemoryBehavior` (proves `to` is treated as
  inclusive, mirroring `InMemoryDetectionRepository`'s actual behavior despite `DetectionQuery#to`'s
  javadoc calling it exclusive — see `JpaDetectionRepository`'s javadoc), label filter, newest-first
  ordering + limit.
- `@Nested AssetImageRepositoryTests` (4, docs/UX-REWORK-PLAN.md §U-d item 3) — unknown asset id → empty `Optional` + `existsByAssetId` false, round trip of bytes + content type + `existsByAssetId` true, upsert-replaces (a second `save` for the same asset id fully replaces the first — different bytes, different content type), idempotent delete (also verifying `existsByAssetId` flips back to false, and a second delete call doesn't throw).
- `@Nested GeofenceRepositoryTests` (6, docs/OPS-CORE-PLAN.md §G, G-b) — unknown id → empty `Optional`, a `KEEP_OUT` zone with an altitude ceiling round trips exactly, a `KEEP_IN` zone with no ceiling round trips `maxAltitudeMeters()==null`/`enabled()==false`, `save` upserts by id (rename/re-kind/re-altitude/re-enable in place, same id), `findAll` returns every saved zone, `deleteById` is idempotent (a second call on an already-deleted id doesn't throw).
- `@Nested UserRepositoryTests` (5, docs/U-AUTH-PLAN.md wave 3) — unknown id/username → empty `Optional`, a user with jsonb memberships round trips (asserting the username reads back lower-cased and the `List<Membership>` survives), `findByUsername` is case-insensitive (`CaseTest`/`CASETEST`/`casetest` all resolve the same user), `save` upserts by id (display name/hash/enabled/memberships all replaced in place), `findAll` returns every saved user.
- `@Nested GroupRepositoryTests` (4, docs/U-AUTH-PLAN.md wave 3) — unknown id → empty `Optional`, a root + child group round trip (child's `parentGroupId` preserved), `save` upserts by id, `findAll` returns every saved group.
- `telemetryRetentionPrunesOldestSamplesOnceCapExceeded`/`detectionRetentionPrunesOldestResultsOnceCapExceeded`
  — use each repository's small-cap constructor overload (cap 3) to insert 5 rows and assert exactly
  the 3 newest survive.
- `historyOfAFinishedUsageSurvivesAFreshEntityManagerFactory` — docs/MVP2-PLAN.md P-b's done
  criterion in test form: writes a closed `AssetUsage` (now also carrying the same `streamId` as
  its detection, docs/MVP2-PLAN.md R-a2 — the realistic shape) plus one telemetry sample and one
  detection result through the shared `EntityManagerFactory`, opens a fresh one against the same
  still-running container, and asserts all three round-trip.
- `assetSurvivesAFreshEntityManagerFactoryAgainstTheSameDatabase` — docs/MVP2-PLAN.md P-a's done
  criterion in test form: writes an asset through the shared `EntityManagerFactory`, opens a
  **second, independent** one via `PersistenceUnit.start` against the same still-running container
  (Flyway's own history table makes the re-migration a no-op), reads the asset back through it, and
  asserts full equality — proving data survives a fresh application context, not just a fresh query
  within the same one.
- `v4MigrationAddsANullableStreamIdColumnOnTopOfV1ThroughV3` (docs/MVP2-PLAN.md R-a2) —
  queries `information_schema.columns` directly for `asset_usages.stream_id` and asserts it is a
  nullable `uuid` column, proving `V4__usage_stream_id.sql` applied cleanly on top of the V1-V3
  schema every other test in this class already depends on (rather than only inferring the
  migration ran from a null-`streamId` round trip elsewhere).
- `v6MigrationAddsANullableFlightStateColumnOnTopOfV1ThroughV5` (new, docs/FC-INTEGRATIONS-PLAN.md
  F-b) — same shape as the V4 test above, for `telemetry_samples.flight_state` (asserts nullable
  `jsonb`), proving `V6__telemetry_flight_state.sql` applied cleanly on top of V1-V5.
- `v7MigrationCreatesTheGeofenceZonesTableOnTopOfV1ThroughV6` (new, docs/OPS-CORE-PLAN.md §G, G-b) —
  same shape as the V4/V6 tests above, for the brand-new `geofence_zones` table: asserts `polygon`
  is a required (`NOT NULL`) `jsonb` column and `max_altitude_meters` stays nullable, proving
  `V7__geofence_zones.sql` applied cleanly on top of V1-V6.

- `v8MigrationCreatesUsersAndGroupsOnTopOfV1ThroughV7` (docs/U-AUTH-PLAN.md wave 3) — same shape
  as the V4/V6/V7 schema tests: asserts `users.memberships` is a required (`NOT NULL`) `jsonb` column
  and `groups.parent_id` is a nullable `uuid`, proving `V8__users_groups.sql` applied cleanly on top
  of V1-V7.
- `@Nested AssignmentRepositoryTests` (4, docs/U-SCOPE-PLAN.md slice 2) — idempotent-upsert `assign`
  queryable both directions, idempotent-delete `unassign`, multiple pilots/assets tracked
  independently, unknown pilot/asset → empty sets / `isAssigned` false.
- `v9MigrationCreatesThePilotAssignmentsTableOnTopOfV1ThroughV8` (docs/U-SCOPE-PLAN.md slice 2) —
  same shape as the V8 schema test: asserts `pilot_user_id`/`asset_id` are both required (`NOT NULL`)
  `uuid` columns and that the primary key is the **composite** of exactly those two columns
  (`information_schema` PK column count = 2), proving `V9__pilot_assignments.sql` applied on top of
  V1-V8.
- `@Nested MarkRepositoryTests` (6, docs/TACTICAL-MARKS-PLAN.md §3/M2) — unknown id → empty
  `Optional`, a `MANUAL` mark with an altitude and a note round trips exactly, a `DETECTION`-sourced
  mark with no altitude/no note round trips `position().altitudeMeters()==null`/`note()==null`,
  `save` upserts by id (rename/re-kind/re-note/re-status/re-position in place, same id), `findAll`
  returns every saved mark, `deleteById` is idempotent (a second call on an already-deleted id
  doesn't throw).
- `v10MigrationCreatesTheMarksTableOnTopOfV1ThroughV9` (docs/TACTICAL-MARKS-PLAN.md §3/M2) — same
  shape as the V7/V8/V9 schema tests, for the brand-new `marks` table: asserts `altitude_meters`/
  `note` stay nullable while `latitude` is required, proving `V10__marks.sql` applied cleanly on top
  of V1-V9.
- `@Nested DatasetRepositoryTests` (6, docs/CV-TRAINING-PLAN.md §1, Wave T3) — unknown id → empty
  `Optional`, a dataset with a target category and classes round trips exactly, a dataset with no
  target category and no classes round trips `targetCategory()==null`/`classes().isEmpty()`, `save`
  upserts by id (rename/re-status/re-classes in place, same id), `findAll` returns every saved
  dataset, `delete` is idempotent (a second call on an already-deleted id doesn't throw).
- `@Nested TrainingSampleRepositoryTests` (8, docs/CV-TRAINING-PLAN.md §1, Wave T3) — unknown id →
  empty `Optional`, a sample with annotations round trips exactly, a sample with no asset id and no
  annotations round trips `assetId()==null`/`annotations().isEmpty()`, `save` upserts by id
  (PENDING→LABELED, annotations replaced, `labeledBy`/`labeledAt` stamped, same id),
  `findByDataset` filters by status and bounds by limit, `countByDataset` matches
  `findByDataset`'s own filter without loading rows, both are empty/zero for an unknown dataset,
  `delete` is idempotent.
- `@Nested SampleImageStoreTests` (4, docs/CV-TRAINING-PLAN.md §1/§C, Wave T3) — unknown sample id
  → empty `Optional`, bytes + content type round trip exactly, `save` upserts (a second `save`
  fully replaces the first), `delete` is idempotent.
- `v11MigrationCreatesTheTrainingDatasetsTablesOnTopOfV1ThroughV10` (docs/CV-TRAINING-PLAN.md §1,
  Wave T3) — same shape as the V7/V8/V9/V10 schema tests, for the three brand-new training-pipeline
  tables: asserts `datasets.target_category` and `training_samples.asset_id` stay nullable while
  `training_samples.stream_id` and `sample_images.data` (`bytea`) are required, proving
  `V11__training_datasets.sql` applied cleanly on top of V1-V10.
- `FilesystemDatasetExportTest` (4, docs/CV-TRAINING-PLAN.md §1/§5, Wave T3, top-level, **not**
  Docker-gated — this port has nothing to do with Postgres) — `resolve` on an unknown
  dataset/export pair is empty; `write` produces a zip whose entries match the frozen layout
  exactly (`data.yaml` with the right `names`/`nc`/`train`/`val`, `images/<name>` bytes verbatim,
  `labels/<name>.txt` text verbatim, same stem as its image) and whose manifest's `location`
  resolves back to the same file via `resolve`; a zero-entry export still produces a valid archive
  (just `data.yaml`); repeated `write` calls for the same dataset produce independent exports
  (different `exportId`s, both resolvable).

101 tests total, all green in this environment (`docker info` reachable) — up from 78
(docs/CV-TRAINING-PLAN.md Wave T3: new `DatasetRepositoryTests` (6) + `TrainingSampleRepositoryTests`
(8) + `SampleImageStoreTests` (4) + the V11 schema test (1) = +19 Postgres-backed, plus
`FilesystemDatasetExportTest` (4, pure filesystem, always runs) = +23 total); up from 71
(docs/TACTICAL-MARKS-PLAN.md M2: new `MarkRepositoryTests` (6) + the V10 schema test).

## Gotchas

- **`org.testcontainers.postgresql.PostgreSQLContainer` (Testcontainers 2.x's package — note: distinct from the legacy `org.testcontainers.containers.PostgreSQLContainer` shim, both present in the jar) is a concrete, non-generic class**, not `PostgreSQLContainer<SELF extends PostgreSQLContainer<SELF>>` like Testcontainers 1.x — `new PostgreSQLContainer<>("postgres:16")` does not compile here; it's `new PostgreSQLContainer("postgres:16")` (raw type, no diamond).
- **Testcontainers 2.x renamed its Maven artifacts** with a `testcontainers-` prefix: it's `org.testcontainers:testcontainers-postgresql` and `org.testcontainers:testcontainers-junit-jupiter`, not `org.testcontainers:postgresql`/`org.testcontainers:junit-jupiter` (which don't exist at `testcontainers-bom` 2.0.5 — resolving them fails with a plain "could not find artifact" error that gives no hint the fix is just the artifact name). The un-prefixed core artifact (`org.testcontainers:testcontainers`, for `GenericContainer`/`DockerClientFactory`) did **not** get renamed — only the per-database/per-technology modules did.
- **`JpaOperations` opens a fresh `EntityManager` (and therefore a fresh physical JDBC connection, given the unpooled connection provider — see "Bootstrap" above) per `write`/`read` call.** No request-scoped or thread-bound `EntityManager`, because there is no Spring/servlet request here to scope one to. Fine at this platform's call volume; would need revisiting (most likely: adding the pooled connection provider first) before this adapter could serve meaningfully concurrent load.
- **Hibernate logs two startup warnings that are expected, not bugs**: `HHH10001002: Using built-in connection pool (not intended for production use)` (see "Bootstrap") and `HHH90000025: PostgreSQLDialect does not need to be specified explicitly` (this module sets `hibernate.dialect` explicitly anyway, to skip Hibernate's own connection-metadata-based auto-detection round trip at startup — a minor, deliberate speed/explicitness tradeoff, not an oversight).
- **A native query's `?N` positional parameters must be re-supplied per occurrence, not per distinct value** — `JpaTelemetryRepository`/`JpaDetectionRepository`'s prune queries reference `?1` (the grouping key) twice in the SQL text (once in the outer `WHERE`, once in the subquery's `WHERE`) but call `setParameter(1, value)` only **once**; Hibernate's native-query parameter binder resolves every occurrence of a given positional index from the same single `setParameter` call (unlike raw JDBC `?` placeholders, which are positional *per occurrence* and would need the value bound twice) — this is standard JPA `Query#setParameter(int, Object)` behavior, not something either class over-thinks with parameter-index bookkeeping.
- **`EntityManager#setParameter(int, UUID)` on a native query binds correctly as `uuid`, not `varchar`/`bytea`**, with no `stringtype=unspecified` JDBC-URL trick and no `PGobject` wrapping needed — Hibernate infers the correct JDBC type from the Java parameter's runtime class (`UUID.class` → `StandardBasicTypes.UUID` → Postgres `uuid`) the same way it does for typed JPQL/Criteria parameters, even though the query text itself is opaque native SQL to Hibernate.

## Status

Fully implements docs/MVP2-PLAN.md **P-a** (`CategoryRepositoryPort`/`DeviceRepositoryPort`/`AssetRepositoryPort`) **and P-b** (`AssetUsageRepositoryPort`/`TelemetryRepositoryPort`/`DetectionRepositoryPort`): JPA implementations for all six, a Flyway-migrated schema (V1–V4), and every round-trip/upsert/idempotent-delete/lifecycle-state/ordering/limit/retention/restart-survival test described above passing. Wired into vision-app behind `vision.persistence.enabled` (default `false`) — see vision-app/MODULE.md's `PersistenceWiringConfiguration` entry for the toggle itself.

docs/MVP2-PLAN.md **R-a2** ("usage→stream link, persistence half") is closed: `AssetUsageEntity`/`JpaAssetUsageRepository` gained a nullable `stream_id` column (`V4__usage_stream_id.sql`, additive over V1-V3 — no backfill possible or attempted, see the migration's own comment), mapped exactly like every other nullable field on the entity. This is what makes `vision-application`'s `DefaultReplayService` able to query `DetectionRepositoryPort` by a usage's real `streamId` instead of always returning empty detections — see vision-application/MODULE.md's `ReplayService`/Gotchas entries for the read side.

docs/UX-REWORK-PLAN.md **§U-d item 3 done** (asset image, persistence half — CONTRACT 2's storage): a new sixth-plus-one repository port, `AssetImageEntity`/`JpaAssetImageRepository` (`V5__asset_images.sql`, purely additive — a new table, nothing else changed). Keyed by `assetId` itself rather than a synthetic id (see the entity's own note above), `data` a plain `byte[]`/`bytea` column (Hibernate's default mapping, no `@Lob`/converter/jsonb needed — the first genuinely binary, non-JSON column in this schema). `existsByAssetId` is a `count(a)` JPQL query, never fetching the `data` column, specifically so a fleet/asset list populating `hasImage` for many rows doesn't pay for loading image bytes it doesn't need. `./mvnw -B -pl adapters/adapter-persistence test`: **46/46 green** (was 42) — new `AssetImageRepositoryTests` (4, see Tests above). See vision-domain/vision-application/vision-api/vision-app's own MODULE.mds for the port/domain type, the probe endpoint, the REST controller, and the wiring (`PersistenceWiringConfiguration#assetImageRepositoryPort`, gated by `vision.persistence.enabled` exactly like the other six port beans).

## docs/FC-INTEGRATIONS-PLAN.md F-b done (flight-controller-aware telemetry, persistence half)

`TelemetrySampleEntity` gained a nullable `flight_state` jsonb column (`V6__telemetry_flight_state.sql`, purely additive over V1-V5 — same shape as V4's `stream_id` addition, no backfill). Storage choice: the domain `FlightState` record is stored **directly**, no persistence-local wrapper type — following `DetectionResultEntity#detections`' own precedent of letting Hibernate's Jackson-3-backed `FormatMapper` serialize a plain, framework-annotation-free domain record tree straight into jsonb (see Conventions). `JpaTelemetryRepository#toEntity`/`#toDomain` both grew one more positional argument (`telemetry.flightState()` / `entity.flightState()`), no branching needed — Hibernate/Jackson already treat every field of `FlightState` (including its nullable sub-fields and non-null `armingBlockers`) as optional-if-absent the same way `extra`'s `Map<String,Double>` was already handled.

**Old-row compatibility, proven, not just claimed**: `savedSampleWithoutFlightStateRoundTripsAsNull` saves a `Telemetry` built via the domain's 8-arg convenience ctor (no `flightState` argument at all — exactly the shape every pre-F-b row in this table has) and asserts it reads back with `flightState() == null`; `v6MigrationAddsANullableFlightStateColumnOnTopOfV1ThroughV5` separately proves the column itself is nullable at the schema level (`information_schema.columns`), the same two-pronged proof `V4__usage_stream_id.sql`'s own R-a2 entry above used for `stream_id`.

`./mvnw -B -pl adapters/adapter-persistence test`: **49/49 green** (was 46), run against a real `postgres:16` Testcontainers instance (not skipped) — `TelemetryRepositoryTests` 5→7 (+2, see Tests above), one new top-level schema test (see Tests above). See vision-domain/vision-application/vision-api/vision-app's own MODULE.mds for the domain record/decoder, the `AssetAttention`/DTO ripple, and (zero) wiring change.

**Deviations from the brief**: none.

## docs/OPS-CORE-PLAN.md G-b done (geofence zones, persistence half)

A new seventh-plus-one repository port: `GeofenceZoneEntity`/`JpaGeofenceRepository` (`V7__geofence_zones.sql`, purely additive — a new table, nothing else changed). `id` is the domain's own `ZoneId` rather than a synthetic one (a zone has real identity, unlike `Telemetry`/`DetectionResult`); `kind` reuses the domain `ZoneKind` enum directly (`@Enumerated(EnumType.STRING)`, same convention as `Capability`/`LifecycleState`); `polygon` stores the whole `List<GeoPosition>` as jsonb, same mechanism as `DetectionResultEntity#detections`. `save` is merge-by-id (upsert); `deleteById` is a real hard delete, idempotent — zones have no soft-delete concept of their own (a disabled zone is just a row with `enabled=false`).

`./mvnw -B -pl adapters/adapter-persistence test`: **56/56 green** (was 49), run against a real `postgres:16` Testcontainers instance (not skipped) — new `GeofenceRepositoryTests` (6, see Tests above), one new top-level schema test (`v7MigrationCreatesTheGeofenceZonesTableOnTopOfV1ThroughV6`). See vision-domain/vision-application/vision-api/vision-app's own MODULE.mds for the domain type/port, the `GeofenceMonitor`/`GeofenceService`, the `GeofenceController` REST surface, and the wiring (`PersistenceWiringConfiguration#geofenceRepositoryPort`, gated by `vision.persistence.enabled` exactly like the other seven port beans; `InMemoryGeofenceRepository`, vision-app devsupport, is the disabled-branch fallback).

**Deviations from the brief**: none.

## docs/U-AUTH-PLAN.md slice 1 wave 3 done (identity persistence: users + groups)

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

`./mvnw -B -pl adapters/adapter-persistence test`: **66/66 green** (was 56), run against a real
`postgres:16` Testcontainers instance (not skipped) — new `UserRepositoryTests` (5) +
`GroupRepositoryTests` (4) + `v8MigrationCreatesUsersAndGroupsOnTopOfV1ThroughV7`. See
vision-app/vision-api's own MODULE.mds for the in-memory fallbacks, the wiring
(`PersistenceWiringConfiguration#userRepositoryPort`/`#groupRepositoryPort`, gated by
`vision.persistence.enabled` exactly like the other nine port beans), the application services,
Spring Security, and the `/api/auth/*` surface.

**Deviations from the brief**: none.

## docs/U-SCOPE-PLAN.md slice 2 done (pilot→asset assignments, persistence half)

A new twelfth repository port: `AssignmentEntity`/`AssignmentId`/`JpaAssignmentRepository`
(`AssignmentRepositoryPort`), plus `V9__pilot_assignments.sql` (purely additive — one new join
table, nothing else changed). The primary key is the **composite** (`pilot_user_id`, `asset_id`)
via `@IdClass` — a plain join row with no synthetic id, the pair *being* the identity; the composite
PK doubles as the uniqueness constraint that makes `assign` an idempotent `merge` upsert with no
duplicate rows. A secondary index on `asset_id` serves the `pilotsForAsset` direction (the PK's
leading column already serves `assetsForPilot`). No FK to `users`/`assets` — same "no cross-entity
foreign keys / stay parity-compatible with the in-memory reference repo" convention as every other
table here (`InMemoryAssignmentRepository`, vision-app devsupport, is the disabled-branch fallback).

`./mvnw -B -pl adapters/adapter-persistence test`: **71/71 green** (was 66), run against a real
`postgres:16` Testcontainers instance (not skipped) — new `AssignmentRepositoryTests` (4) +
`v9MigrationCreatesThePilotAssignmentsTableOnTopOfV1ThroughV8`. See vision-app/vision-api's own
MODULE.mds for the in-memory fallback, the wiring
(`PersistenceWiringConfiguration#assignmentRepositoryPort`, gated by `vision.persistence.enabled`
exactly like the other eleven port beans), the `AssignmentService`/`ScopeResolver`, and the REST
surface (`AssignmentController`).

**Deviations from the brief**: none.

## docs/TACTICAL-MARKS-PLAN.md M2 done (tactical marks, persistence half)

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
service (docs/TACTICAL-MARKS-PLAN.md M3) and REST surface (M4) are separate, disjoint waves.

`./mvnw -B -pl adapters/adapter-persistence test`: **78/78 green** (was 71), run against a real
`postgres:16` Testcontainers instance (not skipped) — new `MarkRepositoryTests` (6, see Tests
above), one new top-level schema test (`v10MigrationCreatesTheMarksTableOnTopOfV1ThroughV9`).
`./mvnw -B -pl vision-app test -DskipWeb`: **149/149 green** (was 142) — `InMemoryMarkRepositoryTest`
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

## docs/CV-TRAINING-PLAN.md Wave T3 done (CV model-improvement loop, persistence half)

Four new out-ports (docs/CV-TRAINING-PLAN.md §1, Wave T1 — frozen, implemented here unmodified),
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
  `AssetImageEntity`/`JpaAssetImageRepository` precedent (docs/UX-REWORK-PLAN.md §U-d item 3)
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
vision-app/MODULE.md). `DatasetExportPort`/`FilesystemDatasetExport` is the one port left
completely unwired: it needs `VisionTrainingProperties#exportDir()` (T4) before a bean can be
constructed, and no consumer (`LabelingService.export`, T2) is wired to call it yet either.

`./mvnw -B -pl adapters/adapter-persistence test`: **101/101 green** (was 78), run against a real
`postgres:16` Testcontainers instance (docker reachable, not skipped) — new `DatasetRepositoryTests`
(6) + `TrainingSampleRepositoryTests` (8) + `SampleImageStoreTests` (4) + the V11 schema test (1),
plus `FilesystemDatasetExportTest` (4, pure filesystem, unconditional — no Docker/Postgres
involvement for this one port). See vision-domain/MODULE.md for the four frozen ports/domain types
(Wave T1) and vision-app/MODULE.md for the in-memory fallbacks + wiring.

**Deviations from the brief**: none against the frozen T1 contract. The one judgment call
(single-zip-file vs. loose-directory-then-zip) is called out above.

**Honest gaps / explicitly out of scope:**
- **`AuditTrailPort` has no JPA implementation.** It was never in either P-a's or P-b's scope (docs/MVP2-PLAN.md doesn't mention it); `InMemoryAuditTrail` still backs it unconditionally in vision-app regardless of `vision.persistence.enabled` — the fleet-change audit trail does not survive a restart.
- **`findByUsage`'s `limit` selects the earliest samples, not the most recent** — inherited unchanged from `InMemoryTelemetryRepository`'s actual behavior (`list.stream().limit(n)` over an append-ordered list) per this task's "match the reference implementation's exact semantics" brief, not fixed here. For a long flight with more samples than `AssetController`'s `GET /api/usages/{id}/telemetry?limit=100` default, this returns the flight's *first* 100 seconds, not its most recent — worth a deliberate look (newest-first-then-reverse, or a proper time-window parameter) whenever R-a's replay API design settles, since replay is the actual consumer this ordering matters for.
- **`DetectionQuery#to` is treated as inclusive, not exclusive** — same "match the in-memory implementation's real behavior over its javadoc" call, mirroring `InMemoryDetectionRepository`'s `!capturedAt.isAfter(to)`. The domain javadoc and the only two implementations of the port now disagree; worth reconciling (fix the javadoc, or fix both implementations) in whichever future task next touches `DetectionQuery`.
- **Retention caps are constructor arguments, not a `vision.persistence.*` Spring property** — see Retention above; `PersistenceWiringConfiguration` uses each `Jpa*Repository`'s one-argument (default-cap) constructor. No UI/ops surface has asked for a tunable cap yet; wiring one through is a small, isolated follow-up whenever one does.
- **No connection pool** (see "Bootstrap"/Gotchas) — a config-only follow-up, not a structural one.
- **No referential integrity between categories/devices/assets/usages/telemetry/detections** (see Conventions) — a deliberate parity choice against the in-memory contract, not an oversight; revisit only if the in-memory reference implementations themselves ever grow those checks.
- **Ownership has no separate port/table** — `Asset.ownership` (`ownerId`/`groupId`) is just two columns on `assets`, matching the domain model (`Ownership` is a value type embedded in `Asset`, not its own aggregate) — there is no `OwnershipRepositoryPort` to implement.
- **`FilesystemDatasetExport` has no Spring bean yet** (docs/CV-TRAINING-PLAN.md Wave T3) — it
  needs `VisionTrainingProperties#exportDir()`, which doesn't exist until T4 wires
  `vision.training.*`; `DatasetExportPort` is otherwise unimplemented-in-context until then. Every
  other Wave-T1 port has a wired bean (Jpa-or-InMemory, gated by the existing
  `vision.persistence.enabled`) even though no `DatasetService`/`LabelingService` consumer bean
  exists yet either — same "wire the port ahead of its consumer" precedent `markRepositoryPort` set.
- **`telemetry_samples`/`detection_results` have no batched-insert path** — docs/MVP2-PLAN.md P-b's bullet mentions "batched inserts" alongside the append-heavy framing; `save` here is one row per call (matching the ports' one-sample/one-result-at-a-time method signatures exactly — there is no `saveAll`/`saveBatch` on either port to implement), same per-call `EntityManager` cost as every other write in this module (see `JpaOperations`'s Gotcha). Not a correctness gap against the port contracts, but worth flagging: a genuinely high-rate telemetry/detection source (e.g. 10fps CV on several concurrent streams) would see this module's per-write JDBC-connection-open cost before it saw any query-side limit.
