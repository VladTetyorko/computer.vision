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
| W4 learning domain+app | domain-modeler → application-service | domain built, uncommitted | | `CvModelRecord`/`ModelStatus`/`ModelTaskType`/`ModelRuntime`/`ModelAvailability`/`ModelMetrics`/`MetricsKind`/`ModelProvenance`/`TrainingRunId`/`TrainingRunRecord` + `CvModelRepositoryPort`/`TrainingRunRepositoryPort` + tests, `contexts/vision-learning` green (see Handoffs). App half (merge/promote/rollback service, `DefaultTrainingJobService` persistence) not started. |
| W2 | | pending W1 | | |
| W3 | | pending W1+W4 | | |
| W5 | | pending W2+W4 | | |
| W6 | | pending (contract frozen, can start any time) | | |
| W7 / W8 | | after W6 | | |

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
