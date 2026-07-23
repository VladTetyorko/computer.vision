# adapter-persistence

JPA/Postgres persistence adapter for every repository port the platform has: the fleet-side ports
— categories, devices, assets (docs/MVP2-PLAN.md P-a) — and the history ports — asset usages,
telemetry samples, detection results (docs/MVP2-PLAN.md P-b).

**Depends on:** vision-domain, `org.hibernate.orm:hibernate-core`, `org.postgresql:postgresql`,
`org.flywaydb:flyway-core`/`flyway-database-postgresql`, `tools.jackson.core:jackson-databind`
(Jackson 3, for jsonb columns — see Conventions) · **Used by:** vision-app
(`PersistenceWiringConfiguration`, opt-in via `vision.persistence.enabled`)
**Build/test:** `./mvnw -B -pl adapters/adapter-persistence test` — 42 tests (up from 39, docs/MVP2-PLAN.md R-a2 — `AssetUsageEntity`/`JpaAssetUsageRepository` gained `stream_id`)
(`PostgresDockerIntegrationTest` + 6 `@Nested` classes + 4 top-level retention/restart-survival/schema
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
- package-private `final class JpaOperations` — the `write(Function<EntityManager,T>)`/`read(Function<EntityManager,T>)` transaction-boilerplate helper every `Jpa*Repository` composes rather than extends (each opens/commits/closes its own short-lived `EntityManager` per call — see Gotchas).

### `com.drones.vision.adapter.persistence.entity`
- `CategoryEntity`, `DeviceEntity`, `AssetEntity`, `AssetUsageEntity`, `TelemetrySampleEntity`, `DetectionResultEntity` — plain JPA entities, field-annotated (protected no-arg ctor for JPA, a public all-args ctor and no-prefix accessors — e.g. `id()`, `name()` — for symmetry with the domain records they mirror). Never referenced outside this module; each `Jpa*Repository` owns its entity↔domain mapping as private static `toEntity`/`toDomain` methods, so the mapping logic lives right next to the port it serves rather than in separate mapper classes (each mapper is used by exactly one class — per `.claude/skills/java-clean-code/SKILL.md`, a dedicated `Mapper` type for a 1:1 relationship is unneeded ceremony).
- `TelemetrySampleEntity`/`DetectionResultEntity` have a synthetic UUID `id` the adapter invents at save time (`UUID.randomUUID()` in each repository's `toEntity`) — `Telemetry`/`DetectionResult` themselves carry no identity of their own (append-only samples/results, not aggregates), so there is nothing domain-side to derive a primary key from; the id never surfaces back through the ports.
- `AssetUsageEntity#streamId` (docs/MVP2-PLAN.md R-a2, `V4__usage_stream_id.sql`) is a nullable `UUID` column, mapped straight through by `JpaAssetUsageRepository` (`streamId == null ? null : streamId.value()` / `new StreamId(...)`) exactly like every other nullable field on this entity — no special-casing beyond the null check.

## Schema (`src/main/resources/db/migration`)

- `V1__baseline.sql` — `categories` (`id` varchar PK — the `CategoryId` slug, not a UUID, matching the domain's one non-UUID id type; `parent_id` self-referencing FK, nullable; `attribute_hints` jsonb), `devices` (`id` UUID PK; `stream_protocol`/`stream_uri`/`stream_options` — `StreamDescriptor` flattened; `state` varchar), `device_capabilities` (element-collection join table, PK `(device_id, capability)`), `assets` (`id` UUID PK; `category_id` varchar — no FK, see Gotchas; `owner_id`/`group_id` UUID — `Ownership` flattened; `attributes` jsonb; `state` varchar), `asset_devices` (element-collection join table, PK `(asset_id, device_id)`, indexed on `device_id` for `findByDeviceId`).
- `V2__seed_categories.sql` — the same default category set `InMemoryCategoryRepository` seeds in its constructor (`drone`, `ip-camera`, `usb-camera`, `robot`, `simulated`, then `fpv-drone`→`drone`, `esp32-cam`→`ip-camera` in a second batch so the self-referencing FK is satisfied), `ON CONFLICT (id) DO NOTHING` so re-running is a no-op. Keeps a persistence-enabled app's out-of-the-box category list identical to the in-memory fallback's.
- `V3__history.sql` (docs/MVP2-PLAN.md P-b) — `asset_usages` (`id` UUID PK; `asset_id` UUID, indexed, no FK; `started_at`/`ended_at` timestamptz, the latter nullable; `start_latitude`/`start_longitude`/`start_altitude_meters` and the `last_*` triple — `GeoPosition` flattened to columns rather than jsonb, same "flatten a small value type" choice `AssetEntity` makes for `Ownership`; `sample_count` bigint), `telemetry_samples` (`id` UUID PK, synthetic; `usage_id` UUID + `at` timestamptz, **indexed together** as `(usage_id, at)`; `device_id` UUID; `latitude`/`longitude`/`altitude_meters`/`heading_degrees`/`battery_percent` all nullable doubles; `extra` jsonb), `detection_results` (`id` UUID PK, synthetic; `stream_id` UUID + `captured_at` timestamptz, **indexed together** as `(stream_id, captured_at)`; `frame_sequence` bigint; `detections` jsonb — the whole `List<Detection>`, see Conventions; `inference_latency_nanos` bigint). No FKs, same rationale as V1's tables (see Conventions).
- `V4__usage_stream_id.sql` (docs/MVP2-PLAN.md R-a2) — `ALTER TABLE asset_usages ADD COLUMN stream_id UUID` (nullable, no FK, no backfill — a stream's id was never recorded anywhere before this migration, so pre-existing rows simply read back `null`, matching `AssetUsage#streamId`'s own honest "legacy usage" nullability). Purely additive on top of V1-V3; no other table changes.

## Bootstrap (no Spring, no connection pool)

`PersistenceUnit.start` uses Hibernate's **native** bootstrap API (`org.hibernate.cfg.Configuration`) rather than JPA's `Persistence.createEntityManagerFactory` (which needs a `META-INF/persistence.xml` or a hand-built `PersistenceUnitInfo`) or Spring Data JPA (`@EnableJpaRepositories`, Spring Boot's `HibernateJpaAutoConfiguration`, etc.). `Configuration#buildSessionFactory()` returns `org.hibernate.SessionFactory`, which **implements `jakarta.persistence.EntityManagerFactory` directly** (same for `Session`/`EntityManager`) — so every `Jpa*Repository` still only ever calls plain `jakarta.persistence` API, and callers (vision-app) hold a completely standard `EntityManagerFactory` reference with no Hibernate-specific type leaking across the module boundary.

This was a deliberate choice over Spring Data JPA: this codebase's adapters are plain classes constructed via `new` in `WiringConfiguration`/`PersistenceWiringConfiguration` (see vision-app's Bean inventory), never Spring-component-scanned — Spring Data repository interfaces are proxies the Spring Data repository factory generates at runtime and cannot be `new`'d, which would have forced `@EnableJpaRepositories` + Spring Boot's JPA autoconfiguration into the picture, and those autoconfigurations activate purely from classpath presence (`@ConditionalOnClass(DataSource.class)`, etc.) — meaning they would have needed to be **unconditionally excluded** from vision-app's `@SpringBootApplication` and then *conditionally re-enabled* per `vision.persistence.enabled`, a materially more complex (and more fragile) wiring story than the `@ConditionalOnProperty`-gated single bean this module's plain-JPA approach allows (see vision-app/MODULE.md's `PersistenceWiringConfiguration` entry).

No connection pool: Hibernate's default `DriverManagerConnectionProvider` (one physical JDBC connection per `EntityManager`, opened/closed by `JpaOperations` per call — see Gotchas) is what's wired, logging `HHH10001002: Using built-in connection pool (not intended for production use)` at startup — an accepted, documented tradeoff at this platform's single-instance/friends-demo scale, not a placeholder. Swapping in a pooled provider (e.g. HikariCP, `org.hibernate.orm:hibernate-hikaricp`, itself a Spring-Boot-managed dependency so no version pin would be needed) is a config-only change in `PersistenceUnit.start` if concurrency ever demands it — no repository or entity code would change.

`hibernate.hbm2ddl.auto=validate`: Flyway owns schema creation/evolution end to end; Hibernate only ever validates its entity mapping matches what Flyway already created, never generates or alters DDL itself.

## Conventions

- **jsonb via Hibernate's native JSON support, not a hand-rolled converter.** `attribute_hints`/`stream_options`/`attributes`/`extra`/`detections` are `@JdbcTypeCode(SqlTypes.JSON)` fields with `columnDefinition = "jsonb"` — Hibernate 7.4 auto-detects a Jackson `ObjectMapper` on the classpath via its `FormatMapper` SPI and ships `org.hibernate.type.format.jackson.Jackson3JsonFormatMapper` specifically for Jackson 3 (`tools.jackson.*`, this house's Jackson generation under Spring Boot 4) — confirmed present in the `hibernate-core-7.4.1.Final` jar. No `AttributeConverter`, no `PGobject` juggling, no `stringtype=unspecified` JDBC-URL trick.
- **`DetectionResultEntity#detections` stores the domain `Detection` record tree directly** (`List<Detection>`, each with nested `BoundingBox`/`ModelRef`) rather than a parallel adapter-local DTO shape — Jackson 3 serializes/deserializes Java records natively (canonical-constructor + component-name introspection, no annotations needed), proven by this module's own round-trip tests. Referencing a plain, framework-annotation-free domain record from an entity field is the same kind of "adapter depends on domain types" the enum reuse below already establishes; it's storage-format coupling to the domain's shape, not a framework leaking into the domain.
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
- `@Nested TelemetryRepositoryTests` (5) — round trip with every field populated and with only the
  required fields, per-usage isolation, and `findByUsageReturnsEarliestSamplesFirstUpToLimit` —
  proves `findByUsage`'s limit selects the *earliest* samples (mirroring
  `InMemoryTelemetryRepository`'s actual behavior, see `JpaTelemetryRepository`'s javadoc), not the
  newest.
- `@Nested DetectionRepositoryTests` (6) — round trip of detections + inference latency, `streamId`
  filter, `queryTimeRangeIsInclusiveOnBothEndsMatchingInMemoryBehavior` (proves `to` is treated as
  inclusive, mirroring `InMemoryDetectionRepository`'s actual behavior despite `DetectionQuery#to`'s
  javadoc calling it exclusive — see `JpaDetectionRepository`'s javadoc), label filter, newest-first
  ordering + limit.
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
- `v4MigrationAddsANullableStreamIdColumnOnTopOfV1ThroughV3` (new, docs/MVP2-PLAN.md R-a2) —
  queries `information_schema.columns` directly for `asset_usages.stream_id` and asserts it is a
  nullable `uuid` column, proving `V4__usage_stream_id.sql` applied cleanly on top of the V1-V3
  schema every other test in this class already depends on (rather than only inferring the
  migration ran from a null-`streamId` round trip elsewhere).

42 tests total, all green in this environment (`docker info` reachable).

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

**Honest gaps / explicitly out of scope:**
- **`AuditTrailPort` has no JPA implementation.** It was never in either P-a's or P-b's scope (docs/MVP2-PLAN.md doesn't mention it); `InMemoryAuditTrail` still backs it unconditionally in vision-app regardless of `vision.persistence.enabled` — the fleet-change audit trail does not survive a restart.
- **`findByUsage`'s `limit` selects the earliest samples, not the most recent** — inherited unchanged from `InMemoryTelemetryRepository`'s actual behavior (`list.stream().limit(n)` over an append-ordered list) per this task's "match the reference implementation's exact semantics" brief, not fixed here. For a long flight with more samples than `AssetController`'s `GET /api/usages/{id}/telemetry?limit=100` default, this returns the flight's *first* 100 seconds, not its most recent — worth a deliberate look (newest-first-then-reverse, or a proper time-window parameter) whenever R-a's replay API design settles, since replay is the actual consumer this ordering matters for.
- **`DetectionQuery#to` is treated as inclusive, not exclusive** — same "match the in-memory implementation's real behavior over its javadoc" call, mirroring `InMemoryDetectionRepository`'s `!capturedAt.isAfter(to)`. The domain javadoc and the only two implementations of the port now disagree; worth reconciling (fix the javadoc, or fix both implementations) in whichever future task next touches `DetectionQuery`.
- **Retention caps are constructor arguments, not a `vision.persistence.*` Spring property** — see Retention above; `PersistenceWiringConfiguration` uses each `Jpa*Repository`'s one-argument (default-cap) constructor. No UI/ops surface has asked for a tunable cap yet; wiring one through is a small, isolated follow-up whenever one does.
- **No connection pool** (see "Bootstrap"/Gotchas) — a config-only follow-up, not a structural one.
- **No referential integrity between categories/devices/assets/usages/telemetry/detections** (see Conventions) — a deliberate parity choice against the in-memory contract, not an oversight; revisit only if the in-memory reference implementations themselves ever grow those checks.
- **Ownership has no separate port/table** — `Asset.ownership` (`ownerId`/`groupId`) is just two columns on `assets`, matching the domain model (`Ownership` is a value type embedded in `Asset`, not its own aggregate) — there is no `OwnershipRepositoryPort` to implement.
- **`telemetry_samples`/`detection_results` have no batched-insert path** — docs/MVP2-PLAN.md P-b's bullet mentions "batched inserts" alongside the append-heavy framing; `save` here is one row per call (matching the ports' one-sample/one-result-at-a-time method signatures exactly — there is no `saveAll`/`saveBatch` on either port to implement), same per-call `EntityManager` cost as every other write in this module (see `JpaOperations`'s Gotcha). Not a correctness gap against the port contracts, but worth flagging: a genuinely high-rate telemetry/detection source (e.g. 10fps CV on several concurrent streams) would see this module's per-write JDBC-connection-open cost before it saw any query-side limit.
