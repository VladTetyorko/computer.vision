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

**Build/test:** `./mvnw -B -pl storage/persistence -am test` — 224 tests (last measured), one shared
`postgres:16` Testcontainers container per test class. Requires a running Docker daemon — there is no
non-Docker path; tests skip cleanly (not fail) when Docker is unavailable.

## API surface

Package layout: `repository/` (26 `Jpa*Repository`/`Jpa*Store` classes + `TelemetryBatchSettings`),
`mapper/` (24 mapper classes — one `toEntity`/`toDomain` pair per aggregate, `public static` methods
on a `public final class` with a private constructor), `config/` (`PersistenceUnit`, `JpaOperations`,
`PersistencePoolSettings`, `ClosingDatasourceConnectionProvider`), `entity/` (29 classes/records: 26
`@Entity` types, `AssignmentId` (`@IdClass`), `LayerGrantEmbeddable` (`@Embeddable`), `DbAuditOperation`
(plain enum)). No `controller/`, `dto/`, or `service/` package — this module is a driven adapter only.

Every repository composes a `config.JpaOperations` (`write`/`read` transaction-boilerplate helper,
one constructor argument: the module's `EntityManagerFactory`) and calls its aggregate's `mapper`
class for entity↔domain conversion. Constructor is `(EntityManagerFactory)` unless noted.

### `repository` — port implemented, write semantics, one distinguishing note

| Class | Port | Note |
|---|---|---|
| `JpaCategoryRepository` | `CategoryRepositoryPort` | merge upsert, hard delete |
| `JpaDeviceRepository` | `DeviceRepositoryPort` | merge upsert, hard delete |
| `JpaAssetRepository` | `AssetRepositoryPort` | merge upsert, hard delete, `findByDeviceId` |
| `JpaAssetUsageRepository` | `AssetUsageRepositoryPort` | merge upsert; `findByStream` is a deliberately unindexed scan (one-row-per-flight table, read once per stream lookup); `findRecent(int)` is the fleet-wide sibling of `findRecentByAsset` |
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

### `entity` — mapping conventions (not repeated per class)

- **jsonb columns hold domain records/enums directly** (Jackson 3, Hibernate's native
  `@JdbcTypeCode(SqlTypes.JSON)` + `FormatMapper`) — `Detection` lists, `FlightState`, `Membership`
  lists, `GeofenceZone#polygon`, `MapDrawing#points`, `ControlProfile`'s channel/action maps,
  `VehicleProfile`'s capability/message/parameter lists. No `AttributeConverter`, no `PGobject`.
- **Domain enums are reused directly** in `@Enumerated(EnumType.STRING)` fields rather than
  duplicated as adapter-local enums: `Capability`, `LifecycleState`, `ZoneKind`, `UsagePhase`,
  `UsageOrigin`, `DeviceOrigin`, `MarkKind`/`MarkStatus`/`MarkSource`, `DatasetStatus`,
  `DetectionEventState`, `CameraPoseSource`, `FlightPhase`.
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

## Schema (`src/main/resources/db/migration`) — migration ledger, V1 through V26

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
  `camera_poses`, `control_profiles`.
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
  poses). **Append-only history tables always `persist`** a brand-new row instead — telemetry,
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
  once caused an out-of-order failure until a `mvn clean`; and **currently**, the unmerged branch
  `feat/controller-setup-c15` also claims version 25 (`V25__control_profile_transmitter_view.sql`,
  vs. this branch's `V25__device_origin.sql`) — if that branch was ever built in the same checkout, a
  stale copy left in `target/classes` makes this module fail with `FlywayException: Found more than
  one migration with version 25` against a working tree that contains no such file. `mvn clean` is
  the fix. Whichever of the two branches merges second must renumber its `V25`; `refactor/audit-remediation`
  merges first, so `V25__device_origin.sql`/`V26__asset_usage_origin.sql` stand as-is.
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

## Status

Fully implements every repository port the platform currently defines (26 `Jpa*Repository`/`Jpa*Store`
classes; see API surface) against a schema migrated through `V26`. Wired into vision-app
unconditionally via `PersistenceWiringConfiguration` — Postgres is the only store.

Open items, all deliberate rather than oversights:
- Connection pooling and telemetry write batching both exist but are constructor-argument opt-ins
  not yet bound to a `vision.persistence.pool.*`/`vision.persistence.telemetry.*` Spring property —
  vision-app's wiring still uses each class's default (pooled connections; immediate-mode writes).
- No retention/purge job for `db_audit_log` or `audit_entries` — both grow unbounded by design.
- `asset_usages.first_armed_at`/`last_disarmed_at` (added by `V19`) remain schema-only — no domain
  field exists yet to map them to/from.
- `DatasetExportPort`'s old filesystem-export implementation is gone; dataset delivery to the
  training host now rides a gRPC upload (`cv/grpc`'s `GrpcDatasetUploadPort`), not this module.

See `docs/plans/README.md` for the plan-status authority behind the phase references throughout this
file (MVP2, POSTGRES-ONLY-CONTEXT, SCALE-100, FIXED-CAMERA-GEO, VISUAL-GEO-V2, DRONE-ONBOARDING,
CONTROLLER-SETUP-CONTEXT, ARCHITECTURE-AUDIT-2026-08-26).
