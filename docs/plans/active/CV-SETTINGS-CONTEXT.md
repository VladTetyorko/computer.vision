# CV-SETTINGS — working context

Spec: [CV-SETTINGS-PLAN.md](CV-SETTINGS-PLAN.md). Branch `feat/cv-settings`, cut 2026-08-30 from `feat/warehouse-ux` head `09f1cbba` (needs V28 + the plan; merge after warehouse-ux).

## Decisions taken (user, 2026-08-30)
- No feature flag: V29 seeds built-in profiles and **zero bindings**; unbound asset ⇒ byte-identical `PipelineConfig.defaults()`.
- `POST /api/datasets/{id}/train` relaxes `canAdminister` → `canManageOrg` (security-gate change, accepted). Promotion stays `canAdminister`.
- Vision rail 3 → 4 entries (`/vision/profiles`); `/settings/detection` deleted + redirected.
- All §8 recommended defaults accepted.

## Wave ledger
| Wave | Agent | Status | Commit | Notes |
|---|---|---|---|---|
| W1 perception domain | domain-modeler | built, uncommitted | | `CvProfile`/`CvProfileId`/`BindingScope`/`CvProfileBinding`/`CvProfileRepositoryPort` + tests, `contexts/vision-perception` green (see Handoffs) |
| W4 learning domain+app | domain-modeler → application-service | built, uncommitted | | Domain: `CvModelRecord`/`ModelStatus`/`ModelTaskType`/`ModelRuntime`/`ModelAvailability`/`ModelMetrics`/`MetricsKind`/`ModelProvenance`/`TrainingRunId`/`TrainingRunRecord` + `CvModelRepositoryPort`/`TrainingRunRepositoryPort`. App: `CvModelView`/`CvModelCatalog`/`CatalogSource`/`ConfigModelCatalog`/`PromotionResult`/`TrainingRunStores`; `ModelRegistryService`/`DefaultModelRegistryService` evolved (merge+promote+rollback, `RegisteredModel` deleted); `DefaultTrainingJobService` evolved (run persistence, CANDIDATE registration, `runs`/`run`, gate relaxed to `canManageOrg`). `contexts/vision-learning` green, 234 tests total (see Handoffs). **Nothing wired**: no adapter implements the two new ports (W3), and `vision-app`/`vision-api` still call the old constructors/types (W5). |
| W2 perception application | application-service | built, uncommitted | | `application/profile/**` — `CvProfileService`/`DefaultCvProfileService`, `CvProfileResolver`, `CvProfileCache`, `CvProfileCacheSettings`, `EffectiveProfile`, `CoverageRow`, `CvProfileSpec`, `ProfileSource`; `DefaultStreamService.start` now folds the resolver in (new 8th ctor param `CvProfileResolver`). `contexts/vision-perception` green, 641 tests total, up from 605 (see Handoffs). **Nothing wired in `vision-app` yet** — no `CvProfileRepositoryPort`/`CvProfileCache`/`CvProfileResolver` bean exists, and `DefaultStreamService`'s 7-arg construction site there will not compile until W5 adds one and passes it through. |
| W3 persistence | spring-integrator | built, uncommitted | | `V29__cv_profiles.sql`/`V30__cv_model_registry.sql` + `JpaCvProfileRepository`/`JpaCvModelRepository`/`JpaTrainingRunRepository` implementing W1's/W4-domain's ports; `storage/persistence` green, 260 tests total, up from 237 (see Handoffs) |
| W5 | | pending W2+W4 | | |
| W6 | web-ui | built, uncommitted | | `/vision/profiles` page (list/editor/bindings/coverage) + `CvProfile*`/`EffectiveCvProfile`/`CvCoverage*`/`TrainingRun*` TS types + 8 new `VisionApi` methods; `detection-settings.*` deleted, `/settings/detection` redirects; nav rail gained Profiles (see Handoffs) |
| W7 | web-ui | built, uncommitted | | Fly/Live/Wall CV dual-write removed; Vision drawer "From profile" line + `canManageOrg`-gated "Save to this asset's profile"; H6 stream-config readback; H12 one shared `declutterLevel`; H8 dead `LiveFacade.onConfidence/onFps/onModel` deleted (see Handoffs) |
| W8 | web-ui | built, uncommitted | | Model registry rewrite (status/runtime/availability/metrics/provenance chips, source-honesty notice, `canAdministerRegistry`-gated Promote/Roll back); 3 new `VisionApi` methods (`promoteModel` widened, `rollbackModel`, `getTrainingRuns`/`getTrainingRun`); new `RunHistoryPage`/`RunDetailPage` (`manage/training/runs[/:runId]`, `orgGuard`); shared `<vision-cv-subnav>` across Models/Labeling/Training (see Handoffs) |

## Handoffs
(filled per wave: port signatures, deviations from the plan)

### W1 → W2/W3

Built in `contexts/vision-perception/src/main/java/com/drones/vision/perception/domain/{model,port}/`
(package `com.drones.vision.perception.domain.model` unless noted). All framework-free, compact-ctor
validated, `./mvnw -B -pl contexts/vision-perception test` green (605 tests total in the module, 34 new).
**Nothing wired yet** — `DefaultStreamService`/`StreamService` are untouched; this wave is domain-only.

**Types**
```java
public record CvProfileId(UUID value) {
    public static CvProfileId random();
    public static CvProfileId of(String value); // throws IllegalArgumentException
}

public enum BindingScope { ORGANIZATION, CATEGORY, ASSET }

public record CvProfile(
    CvProfileId id, String name, String description, boolean builtIn, GroupId groupId, // kernel GroupId
    ModelRef model, double confidenceThreshold, int inferenceFps,
    List<String> labelFilter, List<String> labelDenyFilter, boolean detectionEnabled,
    TrackingConfig tracking, EventRuleConfig eventRule, Instant createdAt, Instant updatedAt) {

    public PipelineConfig toPipelineConfig(PipelineConfig defaults);
}

public record CvProfileBinding(
    BindingScope scopeKind, String scopeId, CvProfileId profileId, Instant createdAt) {}
```

**Port** (`domain.port`):
```java
public interface CvProfileRepositoryPort {
    Optional<CvProfile> findById(CvProfileId id);
    List<CvProfile> findAll();
    List<CvProfile> findAllByGroup(GroupId groupId); // excludes built-ins (groupId==null)
    CvProfile save(CvProfile profile);               // upsert by id
    void delete(CvProfileId id);                      // idempotent

    Optional<CvProfileBinding> findBinding(BindingScope scopeKind, String scopeId);
    List<CvProfileBinding> findAllBindings();
    CvProfileBinding saveBinding(CvProfileBinding binding); // upsert by (scopeKind, scopeId)
    void deleteBinding(BindingScope scopeKind, String scopeId); // idempotent

    int countBindingsFor(CvProfileId profileId); // the delete-profile 409-still-bound check
}
```
No implementation exists (that's W3). W2's `CvProfileService`/`CvProfileResolver`/`CvProfileCache` and
`DefaultStreamService.start`'s consultation of the resolver are the next things this port needs to
be useful for anything.

**Deviations from the plan text, both deliberate — flag if you disagree:**
1. **§6 row W1 says `List<String>` isn't mentioned for `CvProfile`'s label fields**, but the plan's own
   §5.1 JSON shows them as arrays. Built them as `List<String>` (order-preserving, matches the wire
   shape and an operator's typed/arranged order) rather than reusing `PipelineConfig`'s `Set<String>`.
   `toPipelineConfig` converts list→set at the fold (`Set.copyOf(labelFilter)`), so `PipelineConfig`'s
   own containment-only semantics are unaffected. If W2/W5 want `Set` instead for symmetry with
   `PipelineConfig`, that's a one-file change here — say so and I'll adjust before it's built on.
2. **`CvProfile.groupId` nullability is validated bidirectionally**: `builtIn==true` requires
   `groupId==null`, and `builtIn==false` requires `groupId!=null` — not just "nullable when built-in"
   but "null if and only if built-in". This is stricter than the plan's prose ("groupId nullable only
   if the plan's built-ins are global") but matches §3.4's "seeded... not editable, forkable": a fork
   produces a new non-built-in, group-owned profile, so no code path should ever want a built-in with
   an owner or an owned profile with no owner. If a future built-in needs a group scope, this record
   needs a deliberate change, not a validation relaxation (noted in perception's MODULE.md Gotchas).
3. **`CvProfileBinding.scopeId` stayed a plain `String`**, not a typed sealed scope — the plan offered
   both. Reason: `CategoryId` is a kebab slug, not a UUID, so a union type still needs a per-`BindingScope`
   format check at the point that resolves it against a real kernel id; that point is naturally the
   resolver (W2), which already imports `AssetId`/`CategoryId`/`GroupId`. This record deliberately
   imports none of them.
4. **`updatedAt` must not be before `createdAt`** — an invariant the plan didn't spell out for this
   record but that every other timestamp-pair invariant in this module (`DetectionEvent.lastSeen`≥
   `firstSeen`) follows. Flag if W3's persistence layer needs to relax this for some migration/backfill
   reason.
5. **No `priority`/tier field** — §3.4 confirms tiers (T0–T3) stay a pure client heuristic, not modeled
   here. Confirmed nothing in §5.1's JSON contract needs it either.

**Test count:** 34 new tests (`CvProfileIdTest` 6, `CvProfileBindingTest` 5, `CvProfileTest` 23),
module total 605 (all green).

### W2 → W5

Built in `contexts/vision-perception/src/main/java/com/drones/vision/perception/application/profile/`
(new package) plus the minimal necessary change to `application/stream/DefaultStreamService.java`/
`StreamService.java`. Framework-free, `./mvnw -B -pl contexts/vision-perception test` green (**641
tests total in the module, up from 605** — +22 `DefaultCvProfileServiceTest`, +6 `CvProfileCacheTest`,
+5 `CvProfileResolverTest`, +3 new tests appended to the existing `DefaultStreamServiceTest`; the two
pre-existing `DefaultStreamService*Test` files needed only a mechanical 8th-constructor-arg patch —
every pre-existing assertion is byte-identical, confirmed because `assetDirectory.findByDevice`
is never stubbed in their `setUp()`, so Mockito's default `Optional.empty()` skips profile resolution
entirely for every test that doesn't opt in).

**New/changed signatures**
```java
// application.profile — new package

public enum ProfileSource { ASSET, CATEGORY, ORGANIZATION, PLATFORM }

public record EffectiveProfile(AssetId assetId, CvProfileId profileId, String profileName,
    ProfileSource source, PipelineConfig config) {
    // profileId/profileName non-null iff source != PLATFORM (compact ctor enforces both directions)
}

public record CoverageRow(AssetId assetId, String name, CategoryId categoryId, CvProfileId profileId,
    String profileName, ProfileSource source, boolean detectionEnabled, ModelRef model,
    List<String> labelFilter, List<String> labelDenyFilter) {}

public record CvProfileSpec(String name, String description, ModelRef model,
    double confidenceThreshold, int inferenceFps, List<String> labelFilter,
    List<String> labelDenyFilter, boolean detectionEnabled, TrackingConfig tracking,
    EventRuleConfig eventRule) {} // create/update input; every CvProfile field but id/builtIn/groupId/timestamps

public record CvProfileCacheSettings(Duration ttl) {} // positive; vision.cv.profiles.cache-ttl default 60s is W5's job

public final class CvProfileCache {
    public CvProfileCache(CvProfileRepositoryPort repository, CvProfileCacheSettings settings);
    public Snapshot snapshot();                              // TTL-lazy reload; own writes are always fresh, never waits on TTL
    public CvProfile save(CvProfile profile);                // write-through: repository.save then reload
    public void delete(CvProfileId id);                      // write-through
    public CvProfileBinding saveBinding(CvProfileBinding b); // write-through
    public void deleteBinding(BindingScope scopeKind, String scopeId); // write-through
    public int countBindingsFor(CvProfileId profileId);      // NEVER cached -- live pass-through, the 409 check needs it fresh
    public record Snapshot(List<CvProfile> profiles, List<CvProfileBinding> bindings, Instant loadedAt) {
        public Optional<CvProfile> findById(CvProfileId id);
        public Optional<CvProfileBinding> findBinding(BindingScope scopeKind, String scopeId);
    }
}

public final class CvProfileResolver {
    public CvProfileResolver(CvProfileCache cache);
    public EffectiveProfile resolve(AssetId assetId, CategoryId categoryId, GroupId groupId,
                                     PipelineConfig platformDefault);
    // asset binding -> category binding -> organization binding -> platform default, first match wins;
    // no match returns platformDefault unchanged, the SAME instance (source=PLATFORM, profileId/Name=null)
}

public interface CvProfileService {
    List<CvProfile> list(UserId actor, VisibilityScope scope);                       // never throws; built-ins always visible
    CvProfile get(CvProfileId id, UserId actor, VisibilityScope scope);              // 404 unknown/out-of-scope non-built-in
    CvProfile create(CvProfileSpec spec, GroupId groupId, UserId actor, VisibilityScope scope); // 403 !canManageOrg
    CvProfile update(CvProfileId id, CvProfileSpec spec, UserId actor, VisibilityScope scope);  // 404/403/409 built-in
    void delete(CvProfileId id, UserId actor, VisibilityScope scope);                // 404/403/409 built-in or still-bound
    CvProfile fork(CvProfileId builtInId, String newName, GroupId groupId, UserId actor, VisibilityScope scope); // 404/403/400 not-built-in
    CvProfileBinding bind(BindingScope scopeKind, String scopeId, CvProfileId profileId, UserId actor, VisibilityScope scope); // 404/403/400 bad scopeId
    void unbind(BindingScope scopeKind, String scopeId, UserId actor, VisibilityScope scope); // idempotent; 403/400
    EffectiveProfile effective(AssetId assetId, PipelineConfig platformDefault, UserId actor, VisibilityScope scope); // 404
    List<CoverageRow> coverage(PipelineConfig platformDefault, UserId actor, VisibilityScope scope);
}
// DefaultCvProfileService(CvProfileCache, CvProfileResolver, AssetService, AuditTrailPort) -- one public ctor

// application.stream -- changed

// DefaultStreamService gained an 8th, required constructor parameter:
public DefaultStreamService(AssetDirectoryService, VideoSourceRegistry, DetectionPort, StreamPublisherPort,
    DetectionRepositoryPort, EventPublisherPort, DefaultStreamServiceSettings,
    CvProfileResolver cvProfileResolver); // NEW
```

**How `start()` now uses the resolver**: before the existing tracking fold, `requestedConfig` is
itself folded against the device's owning asset's bound `CvProfile` (`assetDirectory.findByDevice`
→ `CvProfileResolver#resolve(asset.id(), asset.category(), asset.ownership().groupId(),
requestedConfig)`, using `requestedConfig` itself as the platform-default fallback) — a device with
no owning asset skips resolution and uses `requestedConfig` unchanged, and an unbound asset also
folds to `requestedConfig` unchanged (`PipelineConfig.defaults()` byte-identical per the "no feature
flag" decision above). Resolved exactly once per `start()` call, never re-resolved for the stream's
life.

**Exception → HTTP mapping intended (all already generic in `ApiExceptionHandler` today — no new
exception type was added this wave):**
| Thrown by | Exception | Meaning | Suggested HTTP |
|---|---|---|---|
| `create`/`update`/`delete`/`fork`/`bind`/`unbind` | `AccessDeniedException` | scope lacks `canManageOrg()` | 403 |
| `get`/`effective` | `NoSuchElementException` | unknown id, or non-built-in outside scope | 404 |
| `update`/`delete` | `IllegalStateException` | profile is built-in | 409 |
| `delete` | `IllegalStateException` | profile still bound (`countBindingsFor > 0`) | 409 |
| `fork` | `NoSuchElementException` | unknown `builtInId` | 404 |
| `fork` | `IllegalArgumentException` | source profile is not built-in, or blank `newName` | 400 |
| `bind`/`unbind` | `IllegalArgumentException` | `scopeId` doesn't parse for `scopeKind` | 400 |
| `bind` | `NoSuchElementException` | unknown `profileId` | 404 |

**Deviations / judgment calls made where the task brief left a gap — flag if you disagree:**
1. **`AuditTargetType` has no `CV_PROFILE` constant** (`core/vision-platform`, out of this wave's write
   scope) — every audit entry `DefaultCvProfileService` writes reuses `AuditTargetType.MODEL`,
   documented in the class javadoc, mirroring `DefaultModelRegistryService`'s own precedent for
   reusing `AuditAction.UPDATED` when no dedicated action exists. A future `vision-platform` wave
   adding a real constant is a one-enum-value change plus swapping this one reference; not urgent.
2. **`effective`/`coverage` take `PipelineConfig platformDefault` as an explicit method parameter, not
   a wire query param** — this module's application layer cannot read Spring `@ConfigurationProperties`
   (no framework imports allowed). `GET /api/cv/profiles/effective?assetId=` and `GET /api/cv/coverage`
   have no `platformDefault` param in the frozen §5.2 contract, and none is needed — **W5's controller
   must assemble `platformDefault` server-side**, exactly the way `StreamDetectionSupport.defaultConfig()`
   already assembles `requestedConfig` for `start()` today, then pass it through.
3. **`DefaultStreamService#start`'s "explicit per-call override wins over a bound profile" is NOT fully
   achieved end-to-end by this wave alone.** `requestedConfig` is a single, fully-resolved
   `PipelineConfig`, unlike the patch-shaped `requestedTracking` — it carries no way to tell "the
   caller explicitly asked for confidence=0.6" from "confidence=0.6 just happens to be today's
   default." When a profile is bound, its fields replace `requestedConfig` wholesale (every field
   except `maxInFlightInferences`, always host capacity). Two ways W5 could close this gap, neither
   attempted here: (a) have the caller resolve `CvProfileService#effective` first and fold its own
   explicit fields on top before calling `start` (this method's own resolution then becomes a
   same-answer, defense-in-depth check, safe as long as nothing rebinds between the two calls), or
   (b) a future wave changing `start`'s signature to accept a `PipelineConfigPatch` instead of a
   fully-resolved `PipelineConfig`. Full detail in `DefaultStreamService#start`'s own javadoc.
4. **`CvProfileService#fork` has no dedicated endpoint in the frozen §5.2 wire contract** — built
   anyway because the W2 task brief asked for it explicitly as a service method (built-in profiles are
   "not editable, forkable" per plan §3.4). W5 must decide: add `POST /api/cv/profiles/fork`, or have
   the web client do a plain read-then-`POST /api/cv/profiles` copy and never call this method at all
   (in which case `fork` stays reachable only from a future admin tool/test, not dead — still worth
   keeping, since the built-in→group-owned copy semantics live here, not duplicated client-side).
5. **`CvProfileCache` owns both reads and writes over `CvProfileRepositoryPort`, entirely** — chosen so
   `DefaultCvProfileService`'s constructor stays at 4 real collaborators (`cache`, `resolver`,
   `assetService`, `auditTrail`) rather than taking the raw repository port as a 5th, separate
   parameter alongside the cache that wraps it. `CvProfileCache` is therefore the port's sole caller
   today; W3's adapter never needs its own separate access path.
6. **`bind`/`unbind`'s `scopeId` validation reuses each kernel id type's own parser purely for its
   format check** (`AssetId.of`/`GroupId.of`/`new CategoryId(...)`), discarding the parsed value —
   deliberately not resolving whether the id actually exists (an unknown-but-well-formed asset/category/
   group id binds successfully; existence isn't this wave's concern and `AssetService` has no
   `existsById`-style check to reuse without an extra read per bind).

**Test count:** 36 new tests (`DefaultCvProfileServiceTest` 22, `CvProfileCacheTest` 6,
`CvProfileResolverTest` 5, 3 new tests appended to `DefaultStreamServiceTest`), module total 641 (all
green), up from 605 at the W1 checkpoint.

**Exact out-of-module call site that will not compile until W5 fixes it:**
`station/vision-app` — the Spring wiring class constructing `DefaultStreamService` (7-arg call site,
one bean definition) needs a `CvProfileResolver` bean threaded through as the 8th argument. That bean
in turn needs a `CvProfileRepositoryPort` implementation (W3's `JpaCvProfileRepository`, per the W3→W5
handoff above) and a `CvProfileCacheSettings` (reading `vision.cv.profiles.cache-ttl`, default 60s, not
yet defined in any `application.properties`). Not touched by this wave, per the task's explicit
instruction to leave `station/**` alone.

### W4-domain → W4-app/W3/W5

Built in `contexts/vision-learning/src/main/java/com/drones/vision/learning/domain/{model,port}/`
(package `com.drones.vision.learning.domain.model` unless noted). All framework-free, compact-ctor
validated, `./mvnw -B -pl contexts/vision-learning test` green (210 tests total in the module, 50 new).
**Nothing wired yet**: no adapter implements either new port, and no service reads/writes through
them — the existing `ModelRegistryPort`/`ModelRegistryService`/`RegisteredModel` (worker-only, no
persistence) are untouched and still the *only* reachable path today. Per §3.2's table, that port
stays the "worker truth" source (`id`/`version` from `ListModels`) and this wave's
`CvModelRepositoryPort` is the new "platform row" source — the future merge service (W4-app) reads
both and joins them; this wave does not replace or extend `ModelRegistryPort`.

**Types**
```java
public enum ModelStatus { DRAFT, CANDIDATE, LIVE, RETIRED }
public enum ModelTaskType { DETECT, SEGMENT, OPEN_VOCAB }
public enum ModelRuntime { PYTORCH, OPENVINO }
public enum ModelAvailability { PRESENT, MISSING_ON_WORKER } // defined here, attached to nothing yet — see deviation 3
public enum MetricsKind { TRAINING, WORKER }

public record ModelMetrics(Double map50, MetricsKind kind) {} // kind required, map50 nullable

public record ModelProvenance(
    DatasetId datasetId, TrainingRunId trainingRunId, String baseModel, Integer epochs, Instant trainedAt) {
    public static ModelProvenance none(); // every field null — the "nothing to report" sentinel
}

public record TrainingRunId(UUID value) {
    public static TrainingRunId random();
    public static TrainingRunId of(String value); // throws IllegalArgumentException
}

public record CvModelRecord(
    String modelId, String version, String displayName, String kind, boolean openVocab,
    List<String> defaultLabelFilter, ModelTaskType taskType, ModelRuntime runtime, List<String> classes,
    ModelStatus status, ModelMetrics metrics, ModelProvenance provenance,
    UserId promotedBy, Instant promotedAt, Instant createdAt) {

    public CvModelRecord promote(UserId promotedBy, Instant promotedAt); // -> LIVE copy
    public CvModelRecord retire();                                       // -> RETIRED copy, keeps promotedBy/At
}

public record TrainingRunRecord(
    TrainingRunId runId, DatasetId datasetId, String baseModel, int epochs, JobState state,
    int epoch, int totalEpochs, double loss, double map50, String outputModelId,
    UserId startedBy, Instant startedAt, Instant finishedAt, String message) {}
```

**Ports** (`domain.port`):
```java
public interface CvModelRepositoryPort {
    Optional<CvModelRecord> findByIdAndVersion(String modelId, String version);
    List<CvModelRecord> findAll();
    Optional<CvModelRecord> findLive();      // does NOT enforce exactly-one-LIVE — service's job
    CvModelRecord save(CvModelRecord model); // upsert by (modelId, version)
}

public interface TrainingRunRepositoryPort {
    Optional<TrainingRunRecord> findById(TrainingRunId id);
    List<TrainingRunRecord> findAll(int limit); // newest-first by startedAt
    TrainingRunRecord save(TrainingRunRecord run); // upsert by runId
}
```

**Deviations from the plan text / judgment calls made where the plan left a gap — flag if you
disagree:**
1. **`CvModelRecord.metrics` is a plain nullable field, not wrapped in a sentinel** — unlike
   `provenance`, which the task brief explicitly said to give a `.none()` sentinel for. The plan's
   own §5.3 backs this: `metrics` is one `jsonb` column that can itself be `SQL NULL`, while
   provenance is five flat, independently-nullable columns with no "whole thing is null" state at
   the schema level — which is exactly why `provenance` needed a sentinel (a row always exists to
   reconstruct from those five columns) and `metrics` didn't (the whole object is legitimately
   absent). W3: map a null `metrics` column to a null `ModelMetrics`, not to some default value.
2. **`CvModelRecord.promotedBy`/`promotedAt` must both be null or both be set** — a cross-field
   invariant the plan didn't spell out but that `promote()`/`retire()` rely on being true (`retire()`
   keeps whatever promotion pair the row already had, never a half-set one). If W3's persistence
   layer ever needs to construct a row outside `promote()`/`retire()` (e.g. rehydrating from a row
   where only one of the two columns somehow got set), that reconstruction must fail loudly, not
   silently null out the other — this is deliberate, not an oversight to relax.
3. **`ModelAvailability` is defined but attached to nothing** — the task brief was explicit ("keep it
   out of the persisted record but define the enum here"), so it exists standalone in
   `domain.model` with no field carrying it anywhere yet. **W4-app needs its own read-model record**
   (the `CvModelRecord` + `ModelAvailability` pair, e.g. mirroring how `RegisteredModel` already
   pairs a `ModelRef` with a computed `active` flag) — that record does not exist and is this
   domain wave's one deliberately-left gap.
4. **`TrainingRunRecord.datasetId` is a typed `DatasetId`, not a plain `String`** — unlike
   `TrainingJobSpec`/`TrainingProgress`/`TrainingJobView`, which mirror the gRPC wire message
   field-for-field and so keep `datasetId` a raw string. `TrainingRunRecord` is a persisted domain
   row, not a wire mirror, and this context already owns `DatasetId`, so it uses it. When W4-app
   builds a `TrainingRunRecord` off a `TrainingJobSpec`/the job's own state, remember to
   `DatasetId.of(spec.datasetId())` at that boundary.
5. **`TrainingRunRecord.loss`/`map50` stayed primitive `double`** (mirroring `TrainingProgress`/
   `TrainingJobView`), **deliberately unlike `ModelMetrics.map50`'s boxed `Double`** — a run always
   has a numeric value once it exists (starts at `0.0` before the first progress message, same as
   `TrainingJobView`), whereas a catalogue row's `ModelMetrics` can genuinely have no figure yet.
   Don't conflate the two "map50" fields' nullability when wiring the merge.
6. **`CvModelRecord.version`/`kind` both require non-blank**, matching `ModelRef.id`/`.version`'s own
   "must not be blank" convention. The plan notes worker `ModelInfo.version` arrives as `""` today,
   mapped to the `"latest"` sentinel — that mapping is an **application/adapter-boundary** concern
   (W4-app or W3), not this record's; a `CvModelRecord` row itself is never persisted with a blank
   version.
7. **No cross-field invariant between `state`/`finishedAt`/`outputModelId`** on `TrainingRunRecord`
   (e.g. "finishedAt non-null iff state is terminal") — matches the existing "field-level validation
   only" convention `TrainingProgress`/`TrainingJobView` already follow in this module; not enforcing
   it here is consistency, not an omission.

**For W3 (persistence adapter), field-to-column mapping is 1:1 with §5.3** — `CvModelRecord`'s
constructor parameter order matches `cv_models`'s column order exactly except `provenance`, which
decomposes into `dataset_id, training_run_id, base_model, epochs, trained_at` (five flat columns,
not a nested jsonb); `TrainingRunRecord`'s parameter order matches `cv_training_runs`'s column order
exactly, no decomposition needed.

**Test count:** 50 new tests (`ModelMetricsTest` 4, `ModelProvenanceTest` 6, `TrainingRunIdTest` 6,
`CvModelRecordTest` 20, `TrainingRunRecordTest` 14), module total 210 (all green). `ModelStatus`/
`ModelTaskType`/`ModelRuntime`/`ModelAvailability`/`MetricsKind` are pure markers with no dedicated
test, matching `JobState`'s own precedent in this module.

### W3 → W5

Built in `storage/persistence/src/main/{java/com/drones/vision/adapter/persistence/{entity,mapper,repository},resources/db/migration}/`.
Framework: plain JPA (Hibernate native bootstrap, no Spring Data), same shape as every other adapter
in this module. `./mvnw -B -pl storage/persistence test` green, **260 tests total in the module — up
from 237 before this wave** (Maven's own summary line; do not sum `target/surefire-reports/*.xml`,
see storage/persistence MODULE.md Gotchas). Docker ran (not skipped) — `postgres:16` via Testcontainers.
`contexts/vision-learning`/`contexts/vision-perception` were briefly red mid-wave from W2's/W4-app's own
concurrent in-flight edits (unrelated to this wave's files); both cleared before the final run above.
Build note for whoever runs this module next while a sibling wave is still in flight: use
`./mvnw -B -pl <context modules> install -Dmaven.test.skip=true` (not `-DskipTests`, which still runs
`testCompile` and will fail on a concurrent agent's mid-edit test file) before `./mvnw -B -pl
storage/persistence test` (no `-am`).

**Adapter beans W5 needs to wire** (constructor is `(EntityManagerFactory)` for all three, matching
every other `Jpa*Repository` in this module):
```java
new JpaCvProfileRepository(entityManagerFactory)   // implements CvProfileRepositoryPort (W1)
new JpaCvModelRepository(entityManagerFactory)     // implements CvModelRepositoryPort (W4-domain)
new JpaTrainingRunRepository(entityManagerFactory) // implements TrainingRunRepositoryPort (W4-domain)
```
`TrainingRunStores(trainingRunRepositoryPort, cvModelRepositoryPort)` (W4-app's bundle record) takes
the latter two directly — no adapter-side wrapper needed.

**The four seeded built-in `CvProfile` ids** (`V29`, fixed across every deployment — useful for a UI
default/test fixture that needs to reference one without a lookup):
| Name | id | model | confidence | fps | detectionEnabled |
|---|---|---|---|---|---|
| `people-vehicles` | `f8fb1ff5-2dd8-4cb6-b0f1-5a5f4c5c056f` | `yolo26n.pt`/`latest` | 0.40 | 10 | true |
| `wide-search` | `a42e5d7c-b977-4099-980c-b14e94518e6a` | `yoloe-26s-seg-pf.pt`/`latest` | 0.30 | 4 | true |
| `military-vehicles` | `0ca952cf-284a-4a33-b04c-0c9da6a64602` | `orion12l.pt`/`latest` | 0.45 | 5 | true |
| `video-only` | `5e0cd997-743f-4867-82c7-e2be176c23ad` | `yolo26n.pt`/`latest` | 0.40 | 10 | false |

All four ship `tracking`/`eventRule` byte-identical to `TrackingConfig.defaults()`/`EventRuleConfig.defaults()`,
empty `labelFilter`/`labelDenyFilter`, `built_in=true`, `group_id=NULL`, and **zero bindings** (§3.1
rule 3 — no feature flag). `video-only`'s model/confidence/fps are carried but unused by construction
(`detectionEnabled=false`).

**Deviations from CV-SETTINGS-PLAN.md §5.3's literal column list — both flagged in the migration files'
own header comments too, flag here if you disagree:**
1. **`cv_profiles.model_id`/`model_version` (two columns), not one `model` column.** `ModelRef`'s
   compact constructor requires both `id` and `version` non-blank — a single string column could not
   round-trip that without inventing an `"id@version"` encoding this schema uses nowhere else.
   `CvProfileMapper` reassembles `new ModelRef(entity.getModelId(), entity.getModelVersion())`.
2. **`cv_profiles.event_rule` (jsonb, `NOT NULL`) — a column §5.3's list omits entirely.** The domain
   `CvProfile` carries an `EventRuleConfig` (W1's own record, H9's fix); without this column a profile
   could not round-trip through `CvProfileRepositoryPort#save`/`#findById` at all. Whole-record jsonb,
   same convention as `tracking`.
3. **`cv_profiles`/`cv_profile_bindings`/`cv_models`/`cv_training_runs` all joined the audited set**
   (not excluded) — `CvProfileRepositoryPort`/`CvModelRepositoryPort`/`TrainingRunRepositoryPort` are
   all `canManageOrg`-or-`canAdminister`-gated admin config, the same "who changed X" character as
   `control_profiles`/`datasets`, not machine-output telemetry. `cv_profile_bindings` is a plain join
   table but joins the audited set anyway, matching `pilot_assignments`/`map_layer_grants`'s existing
   precedent (a routing/security decision, not raw telemetry).
4. **`ModelProvenance` decomposes into five flat, independently-nullable columns**
   (`dataset_id`/`training_run_id`/`base_model`/`epochs`/`trained_at`) rather than one nested jsonb
   object — this was W4-domain's own instruction to W3 (see "For W3" note under W4-domain's handoff
   above), followed as specified. `metrics` (the whole `ModelMetrics` record) stayed one nullable jsonb
   column, also as specified — "no metrics yet" is a genuinely absent object, not several
   independently-nullable fields.

**Test count:** 260 tests total in `storage/persistence` (up from 237) — `CvProfileRepositoryTests` (11:
findById empty, full round-trip incl. tracking/eventRule, upsert, findAll, findAllByGroup excludes
built-ins+other groups, delete idempotent, binding round-trip/find-empty/upsert-by-scope/delete-idempotent,
countBindingsFor across scope kinds), `CvModelRepositoryTests` (6: findByIdAndVersion empty, round-trip
with/without metrics+provenance, upsert by composite key, findAll, findLive among a RETIRED row),
`TrainingRunRepositoryTests` (4: findById empty, round-trip, upsert-in-place, findAll(limit) newest-first),
plus two top-level migration-verification tests (`v29MigrationSeedsFourBuiltInCvProfilesWithZeroBindings`
reads all four seeded rows back through the real adapter and asserts `tracking`/`eventRule` decode
byte-identical to the domain defaults, `v30MigrationCreatesTheCvModelRegistryTablesOnTopOfV1ThroughV29`
proves the composite PK and every nullable column via `information_schema`).

### W4-app → W5

Built in `contexts/vision-learning/src/main/java/com/drones/vision/learning/application/`. Framework-free,
`./mvnw -B -pl contexts/vision-learning test` green (234 tests total in the module — up from 210 at the
W4-domain checkpoint, net +24: `DefaultModelRegistryServiceTest` (17) and `DefaultTrainingJobServiceTest` (29)
were both fully rewritten against the evolved services, not purely additive). `RegisteredModel.java` is
**deleted** — `ModelRegistryService#models()` no longer returns `List<RegisteredModel>`.

**No adapter implements `CvModelRepositoryPort`/`TrainingRunRepositoryPort` yet** — that's W3, running
concurrently. Until it lands, wiring either service needs a stand-in (in-memory) implementation, or W5
should wait for W3.

**New/changed service signatures**
```java
// ModelRegistryService — evolved, same interface name
public interface ModelRegistryService {
    CvModelCatalog models(); // was: List<RegisteredModel> models()
    PromotionResult promote(String modelId, String version, UserId actor, VisibilityScope scope);
    // was: void promote(ModelRef ref, UserId actor, VisibilityScope scope)
    PromotionResult rollback(UserId actor, VisibilityScope scope); // new
}

// DefaultModelRegistryService — evolved constructor
public DefaultModelRegistryService(ModelRegistryPort modelRegistryPort, CvModelRepositoryPort cvModelRepositoryPort,
                                    ConfigModelCatalog configCatalog, AuditTrailPort auditTrail);
// was: DefaultModelRegistryService(ModelRegistryPort, AuditTrailPort)

// TrainingJobService — evolved, same interface name, start()'s gate relaxed
public interface TrainingJobService {
    String start(TrainingJobSpec spec, UserId actor, VisibilityScope scope); // now canManageOrg(), was canAdminister()
    List<TrainingJobView> jobs();
    Optional<TrainingJobView> job(String jobId);
    List<TrainingRunRecord> runs(int limit, UserId actor, VisibilityScope scope); // new, canManageOrg()
    TrainingRunRecord run(TrainingRunId runId, UserId actor, VisibilityScope scope); // new, canManageOrg()
}

// DefaultTrainingJobService — evolved constructor (one public ctor now, no overload chain)
public DefaultTrainingJobService(TrainingPort trainingPort, LabelingService labelingService,
                                  AuditTrailPort auditTrail, TrainingRunStores trainingRunStores,
                                  int maxFinishedJobs);
// was: DefaultTrainingJobService(TrainingPort, LabelingService, AuditTrailPort) [+ withdrawn overloads]

// New bundle/read-model/result types (application package root)
public record TrainingRunStores(TrainingRunRepositoryPort trainingRuns, CvModelRepositoryPort models) {}
public record ConfigModelCatalog(List<CvModelRecord> models) {}
public enum CatalogSource { REGISTRY, CONFIG }
public record CvModelView(String modelId, String version, String displayName, String kind, boolean openVocab,
    List<String> defaultLabelFilter, ModelTaskType taskType, ModelRuntime runtime, List<String> classes,
    ModelStatus status, ModelAvailability availability, ModelMetrics metrics, ModelProvenance provenance) {
    public static CvModelView of(CvModelRecord record, ModelAvailability availability);
    public static CvModelView synthesize(ModelRef ref, boolean workerReportsLive);
}
public record CvModelCatalog(List<CvModelView> models, CatalogSource source) {}
public record PromotionResult(String modelId, String version, ModelStatus status,
    String previousModelId, String previousVersion) {} // previousModelId/Version both null or both set
```

**Wiring W5 needs to build** (both currently missing — construction will not compile until supplied):
- A `CvModelRepositoryPort`/`TrainingRunRepositoryPort` bean pair — from W3 once it lands (`storage/persistence`);
  until then, an in-memory stand-in if W5 must proceed first.
- A `ConfigModelCatalog` bean — build it from `CvWiring#cvModelRoster()`'s three-entry literal
  (`station/vision-app/.../wiring/CvWiring.java:273-279`), converting each `CvModelResponse` into a
  `CvModelRecord` (`status=DRAFT`, `provenance=ModelProvenance.none()`, `metrics=null`,
  `promotedBy`/`promotedAt=null`, pick a `createdAt`/`taskType`/`runtime`/`classes`/`version` — the
  frozen wire `CvModelResponse` has no `taskType`/`runtime`/`classes`/`version` fields at all, so W5
  must decide reasonable stand-ins, e.g. `version="latest"`, `taskType=DETECT`, `runtime=PYTORCH`,
  `classes=List.of()`). `TrainingRunStores(trainingRunRepositoryPort, cvModelRepositoryPort)` bundles
  the pair for `DefaultTrainingJobService`.

**Exact call sites that will not compile until W5 rewires them:**
- `station/vision-app/src/main/java/com/drones/vision/app/config/wiring/TrainingWiringConfiguration.java:210-213`
  (`modelRegistryService` bean — `new DefaultModelRegistryService(modelRegistryPort, auditTrailPort)`, needs the
  two new constructor args) and `:240-247` (`trainingJobService` bean — `new DefaultTrainingJobService(trainingPort,
  labelingService, auditTrailPort, applicationProperties.training().maxFinishedJobs())`, needs a `TrainingRunStores`
  inserted before `maxFinishedJobs`).
- `station/vision-api/src/main/java/com/drones/vision/api/controller/ModelRegistryController.java` — `models()`
  maps `List<RegisteredModelResponse>` off `modelRegistryService.models()` (now `CvModelCatalog`, not a `List`);
  `promote()` calls `modelRegistryService.promote(ModelRef, actor, scope)` returning `void` (now
  `promote(String modelId, String version, actor, scope)` returning `PromotionResult`). Needs a new response
  shape matching the frozen §5.2 `CvModel`/promote-response contract, and a new route for `rollback`.
- `station/vision-api/src/main/java/com/drones/vision/api/dto/RegisteredModelResponse.java`/`RegisteredModelsResponse.java`
  — both reference the deleted `RegisteredModel`; replace with DTOs mapping `CvModelView`/`CvModelCatalog`/`PromotionResult`
  per §5.2's frozen wire shape.
- `station/vision-api/src/main/java/com/drones/vision/api/controller/CvModelsController.java`/`dto/CvModelResponse.java`
  — currently backed by `CvWiring#cvModelRoster()`'s static literal, not `ModelRegistryService` at all; §1.3/§5.2
  imply `GET /api/cv/models` should now read through `ModelRegistryService#models()` instead (never-throws, real
  availability) — confirm against the plan before changing this controller's route, since two controllers
  (`ModelRegistryController` at `/api/cv/registry/**`, `CvModelsController` at `/api/cv/models`) currently overlap
  in purpose.
- Any `*WiringTest.java`/`*ControllerTest.java` in `vision-app`/`vision-api` that construct
  `DefaultModelRegistryService`/`DefaultTrainingJobService` or reference `RegisteredModel*` directly.

**Exception → HTTP mapping** (mirrors this module's existing `NoSuchElementException`→404/`AccessDeniedException`→403
convention; new cases this wave adds):
| Thrown by | Exception | Meaning | Suggested HTTP |
|---|---|---|---|
| `promote`/`rollback` | `AccessDeniedException` | scope lacks `canAdminister()` | 403 |
| `promote` | `IllegalArgumentException` | blank `modelId`/`version` | 400 |
| `promote` | `IllegalStateException` (message from cv-service) | worker refused (unknown id, unreachable, ...) | 409 |
| `rollback` | `IllegalStateException("No previous model to roll back to")` | nothing RETIRED to restore | 409 |
| `start` | `AccessDeniedException` | scope lacks `canManageOrg()` (relaxed from `canAdminister()`) | 403 |
| `start` | `NoSuchElementException`/`AccessDeniedException`/`IllegalArgumentException` | dataset pre-check (unchanged from before this wave) | 404/403/400 |
| `runs` | `AccessDeniedException` | scope lacks `canManageOrg()` | 403 |
| `runs` | `IllegalArgumentException` | `limit <= 0` | 400 |
| `run` | `AccessDeniedException` | scope lacks `canManageOrg()` | 403 |
| `run` | `NoSuchElementException` | unknown `runId` | 404 |
| `models` | *(never throws)* | worker unreachable → `source=CONFIG` fallback | 200 always |

**Deviations from the task brief, judgment calls made where it left a gap — flag if you disagree:**
1. **Literal signatures `promote(modelId, version, UserId, Instant)`/`rollback(UserId, Instant)` from the
   task brief were read as shorthand, not literal** — built as `promote(String, String, UserId, VisibilityScope)`/
   `rollback(UserId, VisibilityScope)` with time supplied by an injected `Supplier<Instant> clock` constructor
   seam instead, matching every other service in this module (`DefaultDatasetService`, `DefaultLabelingService`,
   `DefaultTrainingJobService`) and SKILL.md's "acting user/scope is a method parameter, time is a clock seam"
   convention. No caller-visible `Instant` parameter exists on either public method.
2. **`promote`/`CvModelView.synthesize` fabricate a minimal row for a worker-only model with no `CvModelRecord`
   yet** (`kind="unregistered"`, `taskType=DETECT`, `runtime=PYTORCH`, empty `classes`/`defaultLabelFilter`) —
   required because `cv_models` (§5.3) seeds no rows at all, so every built-in checkpoint starts row-less on a
   fresh deployment; without this, promoting a built-in through the new service would fail where the old
   `ModelRegistryController.promote` (pure `ModelRef`) just worked. Both synthesis sites share the same
   constants (`CvModelView.SYNTHESIZED_KIND`/`SYNTHESIZED_TASK_TYPE`/`SYNTHESIZED_RUNTIME`).
3. **Config-fallback rows report `ModelAvailability.PRESENT` unconditionally** (not `MISSING_ON_WORKER`) — with
   the worker unreachable there is nothing to check a config row's presence against; `CatalogSource.CONFIG`
   itself is the "not verified live" honesty signal, not the per-row availability. Don't read a `CONFIG` catalog's
   `PRESENT` rows as "confirmed live".
4. **"Previous" for rollback is derived, not a stored field** — the RETIRED row with the latest `promotedAt`
   (`CvModelRecord#retire()` keeps that stamp specifically so this works). No new column/field was added to
   any record; this relies on exactly-one-promotion-history-per-model-id being enough to disambiguate in
   practice (two different models retired at the exact same instant would tie-break arbitrarily — considered
   acceptable, flag if not).
5. **A real, deferred gap, not worked around**: `ModelRegistryPort` cannot surface worker-reported `stage`/`metrics`
   at all today (only `models()`/`active()`/`promote(ModelRef)` exist) — `cv.proto`'s `ModelInfo.stage`/`.metrics`
   are dropped by both the port and `GrpcModelRegistryPort` (the adapter). `CvModelView.synthesize` can only
   honestly report `LIVE`/`DRAFT` (the wire's `stage` is binary anyway — `"active"`/`"available"` per
   `cv_service/grpc/servicers.py`), and no row in a `CvModelCatalog` ever carries worker-reported metrics — only
   `DefaultTrainingJobService`'s own `MetricsKind.TRAINING` write ever populates `ModelMetrics`. If per-model
   worker metrics need to reach the API, `ModelRegistryPort` needs widening first — a domain change out of this
   wave's write scope, reported rather than made.
6. **`TrainingRunRecord`'s `message` field is never populated from the upload-phase `note()` calls** —
   `note()` only ever touches the in-memory `TrainingJobView`; the persisted run's `message` stays `""` until
   the first real `TrainingProgress` (or a terminal failure) arrives. A poller watching only `run()`/`runs()`
   (not `job()`) will not see "Uploading dataset…"/"Uploaded N samples…" text. Flag if W5's UI needs that text
   surfaced through the persisted-run read path too — it would need a deliberate change here, not a UI workaround.

### W6 → W7/W8

Built entirely in `station/vision-web` against §5's frozen wire contract — **no backend for any of
these endpoints exists yet** (W2/W3/W4 above land the domain/persistence/app-service pieces this
wave's calls will eventually hit; W5's own controller wiring is still open). Full detail in
`station/vision-web/MODULE.md`'s `vision-profiles/` bullet (features section) and its own dated
Status entry — this section only carries what W7/W8 need to build against.

**New TS types (`core/api/models.ts`)** — mirror §5.1/§5.2 exactly, field names verbatim:
`BindingScope`, `CvProfile`/`CvProfileTracking`/`CvProfileEventRule`/`CvProfilesResponse`/
`CvProfileRequest`, `CvProfileBinding`/`CvProfileBindingRequest`, `EffectiveCvProfile`,
`CvCoverageRow`/`CvCoverageResponse`, `TrainingRun`/`TrainingRunsResponse` (typed for W8, not yet
consumed by any component). Widened `CvModel` with optional `version`/`taskType`/`runtime`/`classes`/
`status`/`availability`/`metrics`/`provenance`/`source` (+ new `CvModelMetrics`/`CvModelProvenance`) —
every addition is `?:`, chosen specifically so `features/fly/cv-control-panel-logic.spec.ts`'s existing
object-literal `CvModel` fixture (out of this wave's scope) keeps compiling unchanged; W8 populating
these fields for real does not need another type change.

**New `VisionApi` methods (`core/api/vision-api.ts`)**: `getCvProfiles`/`createCvProfile`/
`updateCvProfile`/`deleteCvProfile`, `setCvProfileBinding`/`deleteCvProfileBinding` (DELETE with a
JSON body via `{body:...}`), `getEffectiveCvProfile`, `getCvCoverage`. **Deliberately not built here**
— the plan's registry promote/rollback/training-runs endpoints (`§5.2`'s `/api/cv/registry/**`,
matching W4-app's `ModelRegistryController`/`TrainingJobService#runs`/`#run` handoff above): those are
W8's scope, both on the Java side (W5's still-open controller rewrite) and the TS client side.

**`GET /api/cv/bindings` was never in the frozen contract** — the Profiles page derives "what's bound
to profile X" client-side from `GET /api/cv/coverage` instead (`vision-profiles-logic.ts#summarizeProfileBindings`,
grouped by `profileId`). If W7/W8 (or a future wave) ever add a real bindings-list endpoint, this
derivation becomes redundant but not wrong — coverage will still agree with it as long as both read
the same underlying binding rows.

**`SettingsStore` (`core/settings/settings-store.ts`) is untouched this wave** and still imported by:
`shared/player/detections-strip.ts`, `shared/map/tactical-map/tactical-map.ts`,
`shared/map/tile-cache/leaflet-loader.ts`, `shared/map/fleet-plan-dialog/flight-plan-dialog.ts`,
`features/wall/wall.ts`, `features/wall/wall-facade.ts`, `features/replay/replay-map.ts`,
`features/inventory/inventory-facade.ts`, `features/command/geofence-zone-dialog.ts`,
`features/fly/cv-control-panel.ts`, `features/fly/stream-state-logic.ts`,
`core/events/events-store.spec.ts`, `features/asset-detail/asset-detail-facade.ts`,
`features/live/live-facade.ts`, `features/onboarding/onboarding-facade.ts`,
`features/settings/account-settings-facade.ts`, `features/devices/devices-facade.ts`,
`features/fly/cockpit-facade.ts`, `features/fly/cv-setup-modal.ts`, `features/fly/fly-logic.ts`,
`features/fly/fly-redirect-guard.ts`, `core/events/events-store.ts`, `core/events/events-logic.ts`,
`core/settings/settings-store.spec.ts`. **This is W7's scope** (fly/live/wall dual-write onto the new
`CvProfile` model per CV-SETTINGS-PLAN.md) — none of these files were read or edited this wave beyond
what grep needed to compile this list.

**Rail/route**: `nav-entries.ts`'s VISION group gained **Profiles** as its first entry
(`/vision/profiles`, `managerOnly: true`); `nav-entries.spec.ts`'s manager-visible-entry regression
count moved 18→19. `app.routes.ts` imports `VISION_PROFILES_ROUTES`; the route is `orgGuard`-guarded
(confirmed by reading `core/org/org-guard.ts` directly — it resolves `AuthStore.ready` then is exactly
`canManageOrg(auth.user()?.topRole)`, redirecting to `/fly` otherwise). `features/settings/detection-settings.*`
(6 files: `.css`/`.ts`/`.html`/`-facade.ts`/`-logic.ts`/`-logic.spec.ts`) **deleted outright**;
`settings.routes.ts`'s `/settings/detection` is now a plain-string `redirectTo: 'vision/profiles'`
(`pathMatch: 'full'`, matching `hubs.routes.ts`'s `/manage/health` precedent); `account-settings.html`'s
link list dropped its "Detection defaults" `<li>`; `core/ui/architecture.spec.ts`'s `ROUTED_PAGES`
swapped `settings/detection-settings` → `vision-profiles/vision-profiles`.

**Verify chain, all green**: `npx tsc --noEmit` clean on both configs, `npm run test:ci` **160/160
files, 3086/3086 tests**, `npx ng build --configuration production` green (only the pre-existing
initial-bundle budget warning, unrelated to this wave). Bundle delta (measured via a pathspec-scoped
`git stash` limited to this wave's own 15 files, to avoid disturbing W2/W3/W4's concurrent uncommitted
work on this same tree): initial bundle **+0.89 kB raw / +0.18 kB transfer**; new lazy chunk
**`vision-profiles` 32.35 kB raw / 7.45 kB transfer**. Not committed, per this task's own instruction.

### W7 → W8

Built entirely inside `station/vision-web`: `src/app/features/fly/**`, `src/app/shared/player/**`,
`src/app/features/live/live-facade.ts`, `src/app/features/wall/wall-tile.ts`,
`src/app/core/settings/settings-store.{ts,spec.ts}`, `src/app/core/api/{models.ts,vision-api.ts}`
(doc-comment fix only — no new wire type needed, W6's `EffectiveCvProfile`/`CvProfile*` types and
`getEffectiveCvProfile`/`getStreamConfig`/`createCvProfile`/`updateCvProfile`/`setCvProfileBinding`
already covered everything this wave needed to call), plus one out-of-scope collateral fix,
`src/app/features/devices/devices-facade.ts` (see below). Built on a shared tree with the still-running
Java W5 agent (`station/vision-api`/`station/vision-app`) — never touched, confirmed disjoint via
`git status` throughout.

**1. Dual-write stopped.** `SettingsStore`'s CV-defaults slice (`model`/`confidenceThreshold`/
`inferenceFps`/`labelFilter`/`labelDenyFilter`/`detectionEnabled`/`tracking`, `effective()`/`adjust()`,
`PipelineSettings`) is deleted outright — no in-flight knob writes it or `localStorage` any more.
`SettingsStore` now carries only genuinely personal view state: `flyAssetId`, `advancedMode`,
`autoTts`, and the new `declutterLevel` (H12, below). Every hot-knob PATCH (`cv-control-panel.ts`,
`cv-setup-modal.ts`, `detections-strip.ts`) now builds its patch from a `ResolvedCvConfig` — either
the freshly-read-back stream config or the effective profile — never a browser-local draft.
`StartStreamRequest` sends no CV body at all any more; the server resolves the initial config from
the profile hierarchy (PLATFORM→ORGANIZATION→CATEGORY→ASSET), matching CV-SETTINGS-PLAN.md's
"resolved once at stream start" rule for the first time on the client side too. Dead code deleted
alongside: the old `SettingsStore.spec.ts` CV-defaults describe blocks, `DEFAULT_DECLUTTER_LEVEL`'s
old three call sites (see H12 below).

**2. "From profile" line + explicit save, in `CvControlPanel`/`CvSetupModal`.** `CockpitFacade` gained
two new signals — `effectiveProfile` (`GET /api/cv/profiles/effective?assetId=`, re-fetched on asset
change) and `streamConfig` (`GET /api/streams/{id}/config`, re-fetched on stream change and after every
successful PATCH via the new `refreshStreamConfig()`/`refreshEffectiveProfile()` methods) — folded into
one `resolvedCvConfig` the panel/modal render from. `cv-control-panel-logic.ts#effectiveProfileLine`
renders `From profile "<name>" (<source>)` when the stream has an asset with a resolved profile, or
`Platform defaults` otherwise — one line, above the "Looking for" summary in `CvControlPanel` and
mirrored in `CvSetupModal`'s header. A new `canManage`-gated (`canManageOrg(topRole)`, `CockpitFacade`'s
own computed, threaded into `CvSetupModal` as an `input<boolean>` rather than re-derived — one source
of truth) **"Save to this asset's profile"** button in the setup modal's footer: `buildProfileRequestFromConfig`
turns the modal's current live config into a `CvProfileRequest`, then either `updateCvProfile` (if the
effective profile's `source === 'ASSET'`, i.e. this asset already owns one) or `createCvProfile` +
`setCvProfileBinding({scopeKind:'ASSET', scopeId, profileId})` (first save). Explicit only — never a
side effect of any hot-knob edit — and states once, in the footer's own hint text, "Applies to this
asset's next stream start — never a side effect of the edits above."

**3. H6 — tracking read-back from `GET /api/streams/{id}/config`.** `StreamConfigResponse`/
`StreamTrackingConfigResponse` (added earlier this session, before this wave's own work began) +
`VisionApi#getStreamConfig` are now actually consumed: `CvSetupModal`'s `capabilityLevel`/
`verifyEveryMillis`/`followFps` are set from a constructor `effect()` reading `config()?.tracking`
(the fresh readback), not assumed from whatever the last PATCH sent. The pre-existing tracking-mode/
tracking-engine-id readback (sourced from `detections.tracks()?.stats`, a faster/more-live signal) was
deliberately left untouched — only the three fields the wire genuinely had no readback for before
gained one.

**4. H12 — one shared, persisted declutter level.** `SettingsStore.declutterLevel` (a `WritableSignal<BoxesMode>`,
persisted at `vision.settings.declutterLevel` via `core/panel-state.ts`, validated on restore with a new
`isBoxesMode` guard) replaces the three previously-unshared in-memory signals on `CockpitFacade`,
`LiveFacade`, and `WallTile` — all three now alias the exact same store signal
(`readonly boxesMode = this.settings.declutterLevel;`), so **every wall tile app-wide now shares one
declutter level**, not one per tile. This is the plan's literal wording ("one shared… replacing the
three"), confirmed intentional, not a regression. Labelled "View · Boxes (shortcut: B)" in
`cv-control-panel.html` per §3.5's "View" group.

**5. H8 — dead `LiveFacade` methods deleted.** `onConfidence`/`onFps`/`onModel` are gone; grepped for
template bindings first (zero hits in `live.html`) before deleting — they were the unused duplicates
the plan described, writing `SettingsStore` with no reader.

**Deviations from the pre-implementation plan, both reasoned in the code's own doc comments:**
- **`CvSetupModal#onModelChange` sends two sequential PATCHes, not one combined `model`+`labelFilter`
  PATCH.** `UpdateStreamConfigRequest`'s own doc comment (`core/api/models.ts`) freezes a wire-contract
  invariant — a hot-knob edit and a model change must never share one PATCH, so a hot-knob drag can
  never accidentally trigger a model re-arm. The model-change PATCH goes first, then a second
  hot-knob-shaped PATCH carrying the seeded `labelFilter`, once the first succeeds.
- **`cv-setup-modal.html`'s controls stay enabled pre-start**, not disabled as originally planned. A new
  component-local `pendingEdits`/`liveConfig` overlay (`signal<Partial<ResolvedCvConfig>>({})` merged
  onto the `config` input, reset by an `effect()` whenever `config()` itself changes) makes pre-start
  edits genuinely meaningful — they stage values "Save to this asset's profile" can write — unlike
  `CvControlPanel`'s Detect switch, which is a true no-op pre-start and stays disabled. The overlay also
  fixes a real correctness gap the naive "read `config()` directly" approach would have had: `debounce()`'s
  last-call-wins semantics would otherwise silently drop an earlier field's edit if two fields are
  touched inside one debounce window.
- **`features/devices/devices-facade.ts` needed a 3-line collateral fix** (outside the declared write
  scope) — it called `SettingsStore.effective()` for `FleetStore.start()`'s second argument; deleting
  the CV-defaults slice (build requirement 1) would otherwise have left the build red. Removed the
  `SettingsStore` injection/import and dropped the now-nonexistent second argument; confirmed via
  `git status` the file was untouched by any other agent before this fix.

**Dev parity**: `vision.auth.enabled=false`'s dev admin resolves `canManageOrg` exactly as before (ADMIN/
unbounded), so the "Save to this asset's profile" action is visible in dev exactly as it will be for a
real manager. **Degrades honestly**: a failed `getEffectiveCvProfile`/`getStreamConfig` read leaves the
respective signal `undefined` — the "From profile" line and the H6 tracking fields simply don't update
rather than showing a stale or fabricated value (both logged via `console.warn` with the file's
`LOG_PREFIX`, never thrown/toasted, since these are background enrichment reads, not the primary act).

**Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json` — 0 errors. `npx tsc --noEmit -p
tsconfig.spec.json` — 0 errors. `npm run test:ci` — **160/160 files, 3079/3079 tests**. `npx ng build
--configuration production` — green, same two pre-existing budget warnings only (initial bundle over its
390 kB budget; `tactical-map.css` over its 8 kB budget, both untouched by this wave). **Bundle delta**,
measured via a pathspec-scoped `git stash push -u -- station/vision-web` baseline (chosen for the same
reason W6 used it — three-plus concurrent agents, Java W5 included, on this shared tree; verified via
`git status` before/after): initial bundle **414.69 kB → 419.78 kB raw (+5.09 kB), 116.09 kB → 118.20 kB
transfer (+2.11 kB)** — from the new `resolvedCvConfig`/`effectiveProfile`/`streamConfig` plumbing and
the "Save to this asset's profile" action, all inside the eagerly-loaded `cockpit`/`fly` surface. Not
committed, per this task's own instruction.

**What W8 should know:** `SettingsStore` is now fully clean of CV-defaults — no more grep-and-avoid
needed for that slice. `ResolvedCvConfig` (`cv-control-panel-logic.ts`) is the one shape every fly-time
CV control renders from (`{...effectiveProfile fields..., ...live stream config overrides...}`) — a
natural model for W8's own registry/training-run UI to follow if it needs a similar "what's actually
running vs. what's configured" merge. `EffectiveCvProfile`/`CvProfile*` types (W6) are now genuinely
consumed by two call sites (`CockpitFacade#loadEffectiveProfile`, `CvSetupModal#saveToAssetProfile`) —
any wire-shape drift W8 introduces there will now be caught by `tsc`, not just by W6's own unconsumed
fixtures. `TrainingRun`/`TrainingRunsResponse` (W6) remain untouched and unconsumed — still W8's own.

### W8 → (closing)

Built entirely inside `station/vision-web`, write scope exactly as launched: `src/app/features/models/**`,
`src/app/features/labeling/**`, `src/app/features/training-jobs/**`, `src/app/core/api/{models.ts,vision-api.ts}`
(three missing endpoints only), a new shared sub-nav under `src/app/shared/ui/`, this file, and
`station/vision-web/MODULE.md`. Never touched `src/app/features/vision-profiles/**` or
`src/app/features/fly/**`. Built on a shared tree with the still-running Java W5 agent
(`station/vision-api`/`station/vision-app`) — confirmed disjoint via `git status` throughout; every
`station/vision-web/**` file the final `git status` shows changed/untracked is one this task touched,
nothing else.

**1. Three `VisionApi` endpoints.** `promoteModel(id, version)` now returns `Promise<PromotionResultResponse>`
(`{id, version, status:'LIVE', previousModelId?, previousVersion?}`) instead of `void`; new
`rollbackModel(): Promise<PromotionResultResponse>` (`POST /api/cv/registry/rollback`, no body); new
`getTrainingRuns(): Promise<TrainingRunsResponse>` / `getTrainingRun(runId): Promise<TrainingRun>`
(`GET /api/cv/training/runs[/:runId]`, W6's own `TrainingRun`/`TrainingRunsResponse` types, unconsumed
until now). **Coded directly against the real W5 Java DTOs**, read off this shared tree's own
uncommitted `CvModelResponse.java`/`CvModelMetricsResponse.java`/`CvModelProvenanceResponse.java`/
`PromotionResultResponse.java` (all new/modified files on the shared tree, not yet committed by W5 at
the time this task read them) rather than trusting §5.2's prose alone — this caught a real drift: W6's
`CvModel.status` TS union was `'LIVE'|'CANDIDATE'|'ARCHIVED'`, a guess at the shape before the domain
model existed; the real backend enum (`ModelStatus`, W4) is `DRAFT`/`CANDIDATE`/`LIVE`/`RETIRED` — fixed
in `core/api/models.ts`. `RegisteredModel`/`RegisteredModelsResponse` (the old wire types `promoteModel`'s
pre-widened signature and the deleted `registryModels()` method used) are removed outright, along with
`VisionApi#registryModels()` itself; its one caller, `DatasetDetailFacade#loadBaseModelOptions`, now
calls `getCvModels()` like every other reader of the roster.

**2. Models page rewrite (`features/models/**`).** `ModelsFacade`/`models-logic.ts`/`models.html`
rewritten wholesale around the joined read model §3.2 describes — worker truth (`ModelInfo`, live/not,
id/version) merged with platform governance (`CvModelRecord` — status/runtime/metrics/provenance/
availability). Table columns: Model/Version/Runtime/Status (one chip)/Availability (`MISSING_ON_WORKER`
→ a plain `.availability-warn` line, never hidden or silently substituted)/Training mAP50 (labelled
that exact way — `metricsKindLabel` — never presented as held-out evaluation)/Provenance (dataset→run
links via `model.provenance.datasetId`/`.trainingRunId` when present, `'—'` otherwise)/Actions. A
`source: 'config'` roster (registry off or its worker unreachable — `ModelRegistryService#models()`'s
own documented fallback path) still renders every configured row, plus one `vision-notice warn`
("Showing the configured model catalogue…") — `GET /api/cv/models` never errors server-side by
contract (`CvModelsController` is `@OpenByDesign`, unconditional), so this page has **no** "feature
disabled" empty state any more, unlike its pre-W8 shape.

**Promote** (row-level, `canPromoteModel` gates on not-already-`LIVE` + `canAdministerRegistry` + no
other row currently promoting) and a new page-level **Roll back** button both require
`models-logic.ts#canAdministerRegistry(topRole)` — **`ModelRegistryController#promote`/`#rollback` both
require `canAdminister()`** (confirmed by reading the controller: ADMIN topRole *or* `VisibilityScope`
kind `UNBOUNDED`), which is **narrower** than `canManageOrg` (ADMIN|MANAGER) — the route's own
`orgGuard` still lets a MANAGER *see* the page, but `canAdministerRegistry` hides both mutation
affordances from one. This predicate had to be file-local to `models-logic.ts` rather than added to
`core/org/org-logic.ts`, since that file is outside this task's write scope and the two predicates
answer genuinely different questions (`canManageOrg` = "may administer this organization's fleet",
`canAdministerRegistry` = "may mutate the platform-wide model registry" — the latter reads
`VisibilityScope`, a different axis than role alone). Roll back opens a `ConfirmDialog` (via a small
page-local `UiStore` group, `ROLLBACK_DIALOG_ID`) whose message names only the model currently `LIVE`
(`rollbackConfirmMessage(liveModel)`) — **deliberately not** a guess at which model gets restored,
since `PromotionResultResponse` only reveals `previousModelId`/`previousVersion` *after* the call
succeeds, and the wire gives no way to know it beforehand; a `409` ("nothing to roll back to") is
folded into `rollbackError` as its own honest sentence, never a raw error dump, mirroring the same
`409`/`404` handling `promote` already had.

**3. Persisted training-run history (`features/training-jobs/**`).** New `RunHistoryPage`
(`/manage/training/runs`) and `RunDetailPage` (`/manage/training/runs/:runId`), both `orgGuard`-gated
(`TrainingJobService#runs`/`#run` are `canManageOrg`-gated server-side per W4's own ledger note — a
403 for a non-manager the route guard already prevents from being issued). **Distinct from the
pre-existing `TrainingJobPage`** (`/manage/training/jobs/:jobId`, the live in-flight poll view over the
separate, in-memory `TrainingJobResponse`) — `TrainingRun` is the **persisted** record
(`cv_training_runs`, W3), survives a restart, and this pair of pages never polls it; a run still
actively training is watched live at the job page instead. One shared `run-history-logic.ts` (+spec,
10 cases) serves both pages — `sortRunsNewestFirst`, `runStateLabel`/`runProgressLabel` (reusing
`training-job-logic.ts#jobStateLabel`/`#formatMetric`, same feature folder, not a third near-identical
copy), `formatRunMetric` (null-safe — a persisted run's `loss`/`map50` are `number | null`, unlike the
in-flight job's `0`-until-reported sentinel), `runDatasetLabel`, `hasProducedModel`. **No separate
`run-detail-logic.ts` exists** — a deviation from the launching task's own working plan, reasoned in
both facades' doc comments: once the list page's own logic file existed, the detail page needed
nothing further of its own.

`RunHistoryPage` renders every run as a full-row link (state chip/progress/loss/mAP50/dataset name/
started-by+at) — one large touch target per row, the same idiom `dataset-detail.html`'s pre-existing
"Training jobs" list already uses, rather than a table + trailing "Open" action column (a nested
`<a>` inside a table row's own row-link is invalid HTML, and the task's own touch-target/responsive
rule favors the bigger target anyway). `RunDetailPage` is the dataset → run → produced model walk's
middle link: a "Dataset" action link to `DatasetDetailPage`, and — only once `hasProducedModel(run)` —
an "open the model registry" link forward to `ModelsPage` (whose own Provenance column links back here
from the model side). A `RUNNING` run with no model yet reads a quiet "still training" notice; it
**deliberately does not** link to `/manage/training/jobs/:runId` on the assumption `TrainingRun#runId`
equals `TrainingJobResponse#jobId` — nothing on the frozen §5.2 wire contract documents that
equivalence (they're separate id namespaces on two separate persistence stores, W3 vs. the in-memory
job map), and CLAUDE.md's "degrade honestly" rule rules out fabricating a link the wire can't back up.

`training-jobs.routes.ts`'s `TRAINING_JOB_ROUTES` array gained `manage/training/runs` and
`manage/training/runs/:runId`, both `orgGuard`. `manage/training/runs` is a static 3-segment path that
would be swallowed by `LABELING_ROUTES`'s `manage/training/:datasetId` param route if registered after
it in `app.routes.ts`'s merged array — the identical hazard `MODELS_ROUTES`'s own doc comment already
documents for `manage/training/models` — but `TRAINING_JOB_ROUTES` already precedes `LABELING_ROUTES`
there (true before this wave, verified by reading `app.routes.ts`), so **no edit to `app.routes.ts`
was needed**, only to `training-jobs.routes.ts` itself (inside this task's write scope).
`manage/training/runs/:runId` (4 segments) faces no such hazard regardless of order.

**4. Shared `<vision-cv-subnav>` (`shared/ui/cv-subnav.*`, +spec, 5 cases).** One `active` input
(`'models'|'labeling'|'training'`, explicit rather than `routerLinkActive` — `/manage/training`,
`/manage/training/models`, and `/manage/training/runs` all share the literal string prefix
`/manage/training`, so a non-exact `routerLinkActive` would misfire onto Labeling everywhere) rendering
three `.segmented` tab links, one per tree root. Wired into `ModelsPage`, `DatasetsPage` (replacing its
old header-actions "Models" link outright — now always visible, not gated by `!disabled()` the way
that old link was, since jumping to another CV-settings tree is meaningful even when this deployment's
training feature is off), and the new `RunHistoryPage`. **Deliberately not added to any drill-in page**
(`DatasetDetailPage`, `SampleEditorPage`, `TrainingJobPage`, `RunDetailPage`) — those already carry
their own "Back to …"/cross-link actions, so a second always-visible nav row there would only repeat
one. **The Vision rail entries themselves are unchanged** (`nav-entries.ts`, out of this task's write
scope and untouched) — this is a page-level lateral jump between the three route trees, not a rail
change.

**Role-gating summary**: Promote/Roll back → `canAdministerRegistry` (ADMIN/unbounded only). Training
tree (`manage/training/runs[/:runId]`) → `orgGuard` (`canManageOrg`, ADMIN|MANAGER). Models roster read
and all of Labeling → open to any signed-in user, unchanged from before this wave. **Dev parity**:
`vision.auth.enabled=false`'s dev admin resolves ADMIN/unbounded, so both gates above pass exactly as
they do for a real admin — nothing in this wave behaves differently under dev-parity than it will in
production for the matching real role.

**Verify chain, all green**: `npx tsc --noEmit -p tsconfig.app.json`/`-p tsconfig.spec.json` — 0 errors
both. `npm run test:ci` — **162/162 files, 3121/3121 tests** (+42 over W7's 3079/3079). `npx ng build
--configuration production` — green, same two pre-existing budget warnings only (initial bundle over
its 390 kB budget; `tactical-map.css` over its own 8 kB budget — neither new nor worsened this wave).
**Bundle delta**, measured via a pathspec-scoped `git stash push -u -- station/vision-web` baseline
(same isolation technique W6/W7 used, for the same shared-tree reason): initial bundle **419.78 kB →
420.28 kB raw (+0.50 kB), 118.20 kB → 118.37 kB transfer (+0.17 kB)** — effectively flat, since every
substantial addition this wave lands inside already-lazy or newly-lazy chunks rather than the eager
shell. Changed/new lazy chunks (measured by content-grep directly against `dist/`, since `ng build`'s
own summary table truncates past its top 15 and none of these four chunks carry a route `title`-derived
name): `models` chunk ~11.14 kB raw, `labeling`/`datasets` chunk ~8.83 kB raw, new `run-history` chunk
~5.34 kB raw, new `run-detail` chunk ~5.88 kB raw. No separate `cv-subnav` chunk was split out — small
enough that esbuild inlined it into each of the three consuming chunks. Not committed, per this task's
own instruction — every file is staged-ready.

**Plan status**: docs/plans/active/CV-SETTINGS-PLAN.md's client side is now fully implemented, W1–W8 all
built on this shared tree. What remains open is entirely backend/integration: W5 (still mid-flight on
this same tree at the time this wave finished) wiring `vision-app`/`vision-api` to the new domain/
persistence/application-service ports, and then a live end-to-end verification of every endpoint this
wave (and W6/W7) called against a real server — nothing here has been exercised against a running
backend; every response shape is coded against the plan's own frozen §5.2 contract plus the real DTOs
read directly off W5's uncommitted files on this shared tree.
