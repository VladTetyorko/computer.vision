# vision-learning

The operator-in-the-loop CV model-improvement context (docs/plans/done/CV-TRAINING-PLAN.md,
docs/plans/done/CV-TRAINING-V2-PLAN.md): datasets, captured training samples, human labeling/
correction, YOLO dataset upload, remote fine-tune training jobs, and the model registry (list/promote
which checkpoint is live). Owns the whole capture→correct→upload→train→promote loop. Deliberately does
**not** own live detection/inference (perception's `DetectionPort`/`StreamPipeline`), model *serving*
(perception's `ModelRef`, `PipelineConfig#model`), or anything about a live video pipeline beyond
pulling a single frame out of one, live or replayed.

Extracted from the flat `vision-domain`/`vision-application` modules in **W1.7b**
(docs/plans/active/DOMAIN-SEPARATION-W1.md §16) — domain and application layers now live together
under one Maven module, package-rooted by context, so the extraction was a directory move
(`com/drones/vision/learning/**`) rather than a repackage. Both layers stay ArchUnit-enforced one-way.

**Depends on:**
- `vision-kernel` — every typed id, `Ownership`, `BoundingBox` (an `Annotation`'s ground-truth box)
- `vision-platform` — `AuditTrailPort`/`Audit*` (every mutation here is audited), `VisibilityScope`/`AccessDeniedException`
- `vision-warehouse` — `Asset`/`AssetUsage`, `AssetRepositoryPort` (resolving a captured frame's owning
  asset; a closed usage's window for replay capture)
- `vision-perception` — `Detection`/`DetectionResult`/`DetectionQuery`/`VideoFrame`,
  `perception.application.stream.StreamService`/`ActiveStream` (capture a frame from a **live** stream —
  `LabelingService#capture` reads `StreamService#latestRawFrame`/`#latestDetections`)
- `vision-events` — `events.application.ReplaySources` (capture a frame from a **finished flight's**
  replay — `LabelingService#captureFromReplay` reads it; see Gotchas, this is the one edge any context
  reads back out of `events`)

**Used by:** `adapter-cv-grpc` (`DatasetUploadPort`, `TrainingPort`, `ModelRegistryPort`
implementations — see that module's `MODULE.md`), `adapter-persistence` (JPA repositories for the
domain ports), `vision-api` (`/api/datasets/**`, `/api/training/**`, `/api/cv/registry/**`),
`vision-app` (wiring, gated behind `vision.training.enabled`)
**Build/test:** `./mvnw -B -pl contexts/vision-learning test` — **158/158 green** as of the W1.7b
extraction (unchanged test count from the pre-extraction combined suite; a pure move)

## Package shape

```
com.drones.vision.learning.domain.model   — records/enums this context owns
com.drones.vision.learning.domain.port    — driven ports (".out" suffix dropped)
com.drones.vision.learning.application     — every service, command/read-model record and
                                              package-private helper at the package root (the
                                              context's only feature — collapses per the same
                                              "single-feature context" rule `events`/`flight`/
                                              `simulation` follow, no `learning.application.training`)
```

## API surface

### `com.drones.vision.learning.domain.model`
- `record Dataset(DatasetId id, String name, CategoryId targetCategory, List<String> classes, Ownership ownership, DatasetStatus status, Instant createdAt)`
  — a named accumulation of labeled `TrainingSample`s aimed at improving detection of one target.
  `targetCategory` nullable (the optional `CategoryId`, e.g. `"building"`, this dataset improves —
  `CategoryId` itself is a `vision-warehouse` concept, referenced here by value only).
  `classes` is the **ordered** YOLO class list (an annotation's export class index is its position
  here), free-form strings — not `CategoryId` slugs, matching `Annotation#label`'s open-vocab
  reasoning. `ownership` scopes it like `Asset#ownership`, same "no scope filtering baked into the
  repository" convention.
- `record DatasetId(UUID value)` — `static random()`, `static of(String)`.
- `enum DatasetStatus` — `OPEN`, `ARCHIVED`. (`EXPORTING` existed once, removed docs/plans/done/CV-TRAINING-V2-PLAN.md §3 — nothing ever set it, it was a leftover from the deleted manual-export step.)
- `record DatasetUpload(DatasetId datasetId, Instant uploadedAt, int sampleCount, long sizeBytes)` —
  what one completed `DatasetUploadPort.upload` run delivered to the training host; a delivery
  receipt for a gRPC upload rather than a manifest for a filesystem artifact (the replacement for a
  deleted `DatasetExport`).
- `enum JobState` — `RUNNING`, `SUCCEEDED`, `FAILED`; mirrors `cv.proto`'s `JobState` minus
  `JOB_STATE_UNSPECIFIED` (mapped at the adapter boundary).
- `record SampleImage(byte[] data, String contentType)` — image bytes for one `TrainingSample`, stored
  via `SampleImageStorePort` keyed by `TrainingSampleId`; mirrors `warehouse`'s `AssetImage`
  bit-for-bit — `data` non-empty, `contentType` non-blank, clone-in via the compact ctor, `data()`
  clone-out on every access.
- `enum SampleStatus` — `PENDING` (captured, annotations are unreviewed model suggestions),
  `LABELED` (operator confirmed/corrected — the only status a dataset upload includes), `DISCARDED`
  (operator rejected the frame, kept for provenance, never uploaded).
- `record TrainingJobSpec(String baseModel, String datasetId, int epochs)` — a request to fine-tune a
  CV model; the Java-side shape of `cv.proto`'s `Training.StartTraining` request message.
  `baseModel`/`datasetId` non-blank, `epochs` positive. `datasetId` is deliberately a plain string,
  not a typed `DatasetId` — it crosses a gRPC boundary to a training host with no notion of the
  platform's id types.
- `record TrainingProgress(String jobId, int epoch, int totalEpochs, double loss, double map50, JobState state, String message)`
  — one message in a training job's progress stream; mirrors `cv.proto`'s `TrainingProgress` response.
  cv-service's own contract: one `RUNNING` per epoch, then exactly one terminal `SUCCEEDED`/`FAILED`;
  cancellation ends the stream with no terminal message.
- `record TrainingSample(TrainingSampleId id, DatasetId datasetId, StreamId streamId, AssetId assetId, Instant capturedAt, int width, int height, List<Annotation> annotations, SampleStatus status, UserId labeledBy, Instant labeledAt)`
  — a captured frame plus its evolving annotations, one row of a `Dataset`; image bytes are **not**
  carried here (see `SampleImage`). `streamId` non-nullable, `assetId`/`labeledBy`/`labeledAt` nullable
  (asset unresolved at capture time, or not yet reviewed). Status/annotation transition rules
  (capture seeds `PENDING`+`MODEL` annotations, labeling moves to `LABELED`/`DISCARDED`+`OPERATOR`
  provenance, label-vs-`classes` membership) are all an **application-layer** concern
  (`LabelingService`), not this record's — it permits any status/annotation combination its own
  component-level validation allows.
- `record TrainingSampleId(UUID value)` — `static random()`, `static of(String)`.
- `record Annotation(String label, BoundingBox box, AnnotationSource source)` — one ground-truth box
  on a captured `TrainingSample`; reuses kernel's `BoundingBox`, deliberately **no confidence**
  (human-confirmed truth, not a model's guess), unlike perception's `Detection`. `label` free-form
  (matches `Detection#label`), not a `CategoryId`. Whether `label` is a member of its sample's
  dataset's `classes` is `LabelingService.label`'s job, not this record's.
- `enum AnnotationSource` — `MODEL`, `OPERATOR`; provenance only, no behavior.

### `com.drones.vision.learning.domain.port` (driven — implemented by adapters)
- `DatasetRepositoryPort` — `save`/`findById`/`findAll()` (no ownership/group filter baked in —
  scoped visibility is this context's own `DatasetService`/`LabelingService`'s job)/`delete(DatasetId)`
  idempotent, does not cascade to samples/images.
- `TrainingSampleRepositoryPort` — `TrainingSample save(TrainingSample)` **upsert** by
  `TrainingSampleId` (a sample mutates over its own review lifecycle, unlike perception's append-only
  `DetectionRepositoryPort`); `Optional<TrainingSample> findById(TrainingSampleId)`;
  `List<TrainingSample> findByDataset(DatasetId, SampleStatus statusOrNull, int limit)`;
  `int countByDataset(DatasetId, SampleStatus statusOrNull)`; `void delete(TrainingSampleId)`
  idempotent, no cascade to the stored image.
- `SampleImageStorePort` — the `AssetImageRepositoryPort` shape, verbatim, reused for training-sample
  frames: `save(TrainingSampleId, SampleImage)` upsert; `findById(TrainingSampleId)` empty for no
  image (never distinguishing "unknown sample" from "known sample, no image"); `delete` idempotent.
- `DatasetUploadPort` — **replaces a deleted `DatasetExportPort`** (export was a manual filesystem
  step; upload is an implicit part of Train, over gRPC): `DatasetUpload upload(DatasetId, String dataYaml, List<ExportEntry> entries)`
  — ships a YOLO-format dataset to the training host, **replacing any prior upload for the same
  `datasetId`** (idempotent — "label more, train again" just re-uploads and supersedes); nested
  `record ExportEntry(String imageName, byte[] imageBytes, String labelFileText)`.
- `ModelRegistryPort` — `List<ModelRef> models()`; `void promote(ModelRef)` — must apply atomically
  (no partial-promotion reads). `ModelRef` is perception's type, referenced here by value.
- `TrainingPort` — `void startTraining(TrainingJobSpec spec, Consumer<TrainingProgress> onProgress)` —
  the Java side of `cv.proto`'s `Training.StartTraining` server-streaming RPC; GPU-training-host-only
  in production. **Contract**: blocks, invoking `onProgress` for every message until the stream
  terminates (a terminal `SUCCEEDED`/`FAILED`, or the call returns/throws when the transport itself
  ends). **Threading**: blocks for the job's lifetime — the caller runs it on its own executor, not
  the calling thread; `onProgress` runs synchronously from the consuming thread; implementations must
  be safe to call concurrently for different jobs.

### `com.drones.vision.learning.application`
- **Command records**: `DatasetSpec(name, targetCategory, classes)` (`DatasetService#create`'s
  command; duplicates `Dataset`'s blank-name check); `CaptureSpec(streamId, datasetId)`
  (`LabelingService#capture`'s command, both fields `requireNonNull`'d); `LabelSpec(annotations, status)`
  (`LabelingService#label`'s command; `status` must be `LABELED` or `DISCARDED` — rejecting `PENDING`
  at the record's own compact ctor); `ReplayCaptureSpec(usageId, datasetId, atSeconds)`
  (`LabelingService#captureFromReplay`'s command; `atSeconds` — offset from the usage's own
  `startedAt` — must be finite and `>= 0`; **this record names `DatasetId`**, which is why it lives
  here and not in `vision-events`, even though it captures *from* a replay — filed by consumer, not
  by owner, W1.6b).
- **`TrainingStores(datasets, samples, images, uploads)`** — the four domain training ports
  (`DatasetRepositoryPort`/`TrainingSampleRepositoryPort`/`SampleImageStorePort`/`DatasetUploadPort`),
  bundled into one constructor parameter for `DefaultLabelingService` per
  `.claude/skills/java-clean-code/SKILL.md` §3 ("bundle collaborators rather than sprawl") — each port
  is genuinely independently substitutable, but `DefaultLabelingService` also needs `StreamService`,
  `AssetRepositoryPort`, `ReplaySources` and `AuditTrailPort`, which would push its constructor past
  the five-parameter ceiling if all seven were listed individually. `DefaultDatasetService` does
  **not** take this bundle — it only ever touches `datasets()`, so it takes a plain
  `DatasetRepositoryPort` directly.
- **`DatasetService`** (interface) → **`DefaultDatasetService`** — CRUD over `Dataset`s; the
  labeling/capture side lives in `LabelingService`.
  - `DefaultDatasetService(DatasetRepositoryPort, AuditTrailPort)` — a package-private 3-arg test seam
    adds an explicit `Supplier<Instant> clock`.
  - `Dataset create(DatasetSpec, Ownership, UserId actor, VisibilityScope scope)` — note the 4th
    parameter: `ownership` is threaded in explicitly by the caller (mirrors `warehouse`'s
    `AssetService#create`), rather than derived from `scope` internally — deriving a single owning
    `GroupId` from a `GROUPS`-kind scope's `groups()` would be wrong for a manager who manages more
    than one group. Gated on `scope.canManageOrg()` (`AccessDeniedException` + audited denial
    otherwise — create/delete = `canManageOrg`, not further restricted to a group).
  - `List<Dataset> list(UserId actor, VisibilityScope scope)` — `findAll()` filtered to
    `scope.includesGroup(dataset.ownership().groupId())`; never audited.
  - `Dataset get(DatasetId id, UserId actor, VisibilityScope scope)` — `NoSuchElementException` for
    an unknown id; **`AccessDeniedException` (403), not the usual hiding 404**, when the dataset
    exists but is outside scope — this feature's own frozen contract deliberately does not follow the
    "hide existence" convention most other scoped reads in this codebase use.
  - `void delete(DatasetId id, UserId actor, VisibilityScope scope)` — 404 wins over 403;
    `!scope.canManageOrg()` → `AccessDeniedException` + audited denial; else deletes (no cascade to
    samples/images) and audits `DELETED`.
- **`LabelingService`** (interface) → **`DefaultLabelingService`** — capture (live + replay),
  correction/labeling and YOLO upload of `TrainingSample`s, the operator-in-the-loop half of the loop.
  - `DefaultLabelingService(TrainingStores, ReplaySources, StreamService, AssetRepositoryPort, AuditTrailPort)`
    — 5-arg production ctor, exactly at the ceiling (`ReplaySources` is what keeps it there instead of
    growing to seven); a 6th public overload adds an explicit `float jpegQuality`
    (`vision.application.training.jpeg-quality`, docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3,
    threaded into `TrainingFrameEncoder`); a package-private 6-arg test seam instead adds an explicit
    `Supplier<Instant> clock`.
  - `TrainingSample capture(CaptureSpec, UserId actor, VisibilityScope scope)` — resolves+scope-gates
    the dataset first, then resolves the stream's source asset (via `streamService.streams()`'s live
    snapshot for the device, then `AssetRepositoryPort#findByDeviceId`), gates it too if resolved
    (an **unresolved** asset — stream not running, or its device owns no asset — leaves `assetId`
    `null` and is silently permitted). Reads `streamService.latestRawFrame(streamId)`
    (`NoSuchElementException` if absent) and `streamService.latestDetections(streamId)`, maps every
    detection to a `MODEL` annotation (confidence dropped), delegates to a shared private
    `saveCapturedSample` helper.
  - `TrainingSample captureFromReplay(ReplayCaptureSpec, UserId actor, VisibilityScope scope)` — the
    replay counterpart: resolves+gates the dataset the same way, then
    `replay.usages().findById(spec.usageId())` (`NoSuchElementException` if unknown, or if
    `usage.streamId()` is `null`), gates the usage's asset (always fires — `usage.assetId()` is never
    `null`, unlike `capture`'s optional resolution). Computes `at = usage.startedAt() + spec.atSeconds()`,
    rejects it if past the usage's recorded window (`usage.endedAt()`, or the injected clock if still
    open). Pulls the frame via `replay.frames().frameAt(streamId, at)` (`NoSuchElementException` if
    empty), computes suggested annotations server-side via a private `nearestModelAnnotations` (nearest
    `DetectionResult` within `NEAREST_DETECTION_TOLERANCE = 2s`, ties broken toward the earlier one —
    a client-computed "nearest detection" was deliberately not trusted, since the replay UI's timeline
    is downsampled for display), delegates to the same `saveCapturedSample` helper.
  - `List<TrainingSample> samples(DatasetId, SampleStatus statusOrNull, int limit, UserId, VisibilityScope)`
    / `SampleImage image(TrainingSampleId, UserId, VisibilityScope)` — both resolve+gate their dataset
    then thin-delegate to the repository port. `samples` is also `DefaultTrainingJobService#start`'s
    synchronous "does this dataset have anything LABELED" pre-check.
  - `TrainingSample label(TrainingSampleId, LabelSpec, UserId actor, VisibilityScope scope)` —
    resolves the sample, its dataset, gates dataset visibility, and (when `assetId` is known)
    additionally gates that asset, before touching anything. Enforces label-vs-`Dataset#classes()`
    membership **only when `spec.status() == LABELED`** — a `DISCARDED` submission skips the check
    entirely (an out-of-vocab MODEL suggestion must be discardable without first being edited into
    vocabulary). May be called more than once on the same sample (re-correcting, or reviving a
    `DISCARDED` one). Replaces `annotations`, sets `status`, stamps `labeledBy`/`labeledAt`, audits `UPDATED`.
  - `DatasetUpload uploadForTraining(DatasetId, UserId actor, VisibilityScope scope)` — **replaces a
    deleted `#export`** (sink and return type both changed, not merely renamed): resolves+gates the
    dataset, fetches every `LABELED` sample, resolves each one's stored image
    (`IllegalStateException` if a `LABELED` sample has no image — should never occur), builds one
    `ExportEntry` per sample via `YoloDatasetWriter`, calls `stores.uploads().upload(...)`, audits
    `UPLOADED:<count>`. **Not REST-exposed** — `DefaultTrainingJobService#runJob` is its only caller;
    re-uploading the same dataset replaces whatever the training host had before.
  - **Scope, the deliberate relaxation**: a private `canSeeDataset(dataset, scope)` returns
    `scope.kind() != GROUPS || scope.includesGroup(...)` — `UNBOUNDED` **and, deliberately,
    `ASSIGNED_ASSETS` (pilot) both see every dataset**; only `GROUPS` (manager) is actually restricted
    to its visible subtree. This is *not* `DatasetService`'s own stricter gate — because
    `VisibilityScope#includesGroup` is hard-`false` for `ASSIGNED_ASSETS`, gating capture/label on
    dataset group-ownership would make the feature structurally unreachable for the FPV-operator
    persona it exists for. The source **asset**'s own `scope.includes(asset)` gate is what actually
    protects a pilot's reach: they may capture/label into any dataset, but only from a stream/sample
    whose asset they can see. The identical trap and fix as `vision-map`'s `MarkService`
    (see that module's `MODULE.md`) — resolved the same way, independently, in this context.
- **`YoloDatasetWriter`** (package-private, no interface) — pure, in-memory composition of one labeled
  sample's `ExportEntry` plus a dataset's `data.yaml` content; no I/O, `DatasetUploadPort` only
  frames/transports the bytes this class produces. `static ExportEntry toEntry(TrainingSample, SampleImage, List<String> classes)`
  — image basename `<sampleId>.jpg`; label file text one `<class_index> <cx> <cy> <w> <h>` line per
  annotation (`class_index = classes.indexOf(label)`, `IllegalStateException` if not found — should
  never happen, `label` already passed `label`'s membership check), or empty for a zero-annotation
  sample (a valid YOLO "negative"/background image). `static String dataYaml(List<String> classes)` —
  `names:`/`nc:`/`train: images`/`val: images`.
- **`TrainingFrameEncoder`** (package-private, no interface) — encodes a raw `VideoFrame` to
  **full-resolution** JPEG bytes for a captured training sample: an already-`JPEG` frame passes
  through unchanged, `BGR24` is wrapped/encoded via `javax.imageio` (no downscale — unlike
  `vision-api`'s `SnapshotJpegEncoder`, which downscales for a dashboard thumbnail); any other
  `PixelFormat` throws `IllegalStateException` (unreachable with today's adapters). An independent,
  focused duplicate of `SnapshotJpegEncoder`'s `BGR24`/JPEG idiom rather than a shared dependency —
  this context cannot depend on `vision-api` (ArchUnit-enforced dependency direction).
- **`RegisteredModel(ModelRef ref, boolean active)`** — one row of `ModelRegistryService#models()`:
  a known `ModelRef` plus whether it is the registry's current active/production model. `active` is
  computed here, never carried by `ModelRef` itself.
- **`ModelRegistryService`** (interface) → **`DefaultModelRegistryService`** — the "list models, see
  which is live, promote one" control plane over `ModelRegistryPort` — deliberately thin, the port
  already does the real work.
  - `DefaultModelRegistryService(ModelRegistryPort, AuditTrailPort)` — 2-arg.
  - `List<RegisteredModel> models()` — unscoped, unaudited read: any authenticated caller may see the
    roster. Marks every ref whose `equals` matches `port.active()` (`Optional.empty()` active → every
    row `false`, e.g. an empty/unreachable registry).
  - `void promote(ModelRef, UserId actor, VisibilityScope scope)` — gated on `scope.canAdminister()`
    (docs/plans/active/OPS-UX-PLAN.md §1/C4 — was `canManageOrg()`; promoting the live model is
    deployment-global, not team-scoped, so a MANAGER's own-subtree `GROUPS` authority is no longer
    enough, only `UNBOUNDED`/ADMIN may). `AccessDeniedException` + audited `DENIED:out of scope`
    otherwise, no per-model ownership to restrict against. A cv-service refusal
    (`IllegalStateException`) is audited `REFUSED:<message>` and rethrown unchanged; success is
    audited `PROMOTED`.
- **`TrainingJobView(String jobId, String baseModel, String datasetId, int epochs, int epoch, int totalEpochs, double loss, double map50, JobState state, String message, Instant startedAt)`**
  — one row of `TrainingJobService#jobs()`/`#job(jobId)`: the pollable state of one training job.
  `jobId` is the **locally generated** id `TrainingJobService#start` returns, never the wire
  `TrainingProgress#jobId()` cv-service's own stream carries (see `DefaultTrainingJobService`).
  `baseModel`/`datasetId`/`epochs` copied once from the job's `TrainingJobSpec`; the rest is the
  latest `TrainingProgress` observed so far.
- **`TrainingJobService`** (interface) → **`DefaultTrainingJobService`** — starts a CV model fine-tune
  job off-thread (uploading its dataset first) and holds its pollable state.
  - `DefaultTrainingJobService(TrainingPort, LabelingService, AuditTrailPort)` — production
    convenience ctor: a cached daemon-thread pool + `Instant::now` + `MAX_FINISHED_JOBS` (50); a
    4th public overload adds an explicit `int maxFinishedJobs` (`vision.application.training.max-finished-jobs`,
    docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3); two package-private test/wiring seams
    (5-arg: explicit `ExecutorService`+`Supplier<Instant> clock`; 6-arg: same plus `maxFinishedJobs`
    too) both delegate to the one 6-arg canonical constructor.
  - `String start(TrainingJobSpec, UserId actor, VisibilityScope scope)` — gated on
    `scope.canAdminister()` (docs/plans/active/OPS-UX-PLAN.md §1/C4 — was `canManageOrg()`; claiming
    the shared training host is deployment-global, same reasoning as `promote` above — only
    `UNBOUNDED`/ADMIN may). Right after the gate: parses `DatasetId.of(spec.datasetId())` and runs one
    cheap, bounded synchronous pre-check — `labelingService.samples(datasetId, LABELED, 1, actor, scope)`
    — **before** the job is registered, so an unknown dataset, an out-of-scope one, or one with
    nothing `LABELED` are real synchronous failures on the calling thread (none of the three touches
    `jobs` or records a second audit entry — `LabelingService` audits its own denial). **Never blocks
    on the training stream**: mints a fresh `UUID` locally, registers an initial
    `RUNNING`/all-zero `TrainingJobView` under it, submits the run to the injected `ExecutorService`,
    returns the local id immediately. The wire `jobId` a `TrainingProgress` carries is read nowhere in
    this class — every update is keyed by the closed-over local id, so the two may legitimately differ.
    The submitted task **uploads, then trains**: `labelingService.uploadForTraining(...)` first
    (noting the phase in the job's `message`), then `trainingPort.startTraining(spec, progress -> updateJob(...))`.
    Either phase's failure is recorded as a terminal `FAILED` job rather than escaping. **No rejection
    of concurrent jobs** — each is independent. Audited once per attempt (`DENIED:out of scope` or
    `STARTED`), `AuditAction.CREATED` (a new job resource — unlike `promote`'s update-shaped
    `AuditAction.UPDATED`).
  - `List<TrainingJobView> jobs()` / `Optional<TrainingJobView> job(String jobId)` — unscoped,
    unaudited reads; `jobs()` newest-first by `startedAt`. **Bounded retention**: finished jobs
    (terminal) tracked oldest-first, evicted past `MAX_FINISHED_JOBS` (package-private, 50) — a
    still-`RUNNING` job is never evicted, only completions count toward the cap (best-effort, not a
    hard invariant).

## Conventions
- Every domain record validates in its compact constructor with manual `if (…) throw new IllegalArgumentException(…)`.
- The acting user is a method parameter (`UserId actor`, `VisibilityScope scope`), never a constructor
  dependency, matching the rest of `vision-application`'s pre-extraction convention.
- Every service constructor's collaborators are `Objects.requireNonNull`'d.
- Deterministic time via an injected `Supplier<Instant> clock` test seam on every service that
  timestamps something (`DefaultDatasetService`, `DefaultLabelingService`, `DefaultTrainingJobService`)
  — production always defaults to `Instant::now`, tests never race the real clock.
- **Audit shape**: `AuditEntry.of(actor, action, targetType, targetId, summary, attrs)` — `result` is
  a short token (`CREATED`/`DELETED`/`DENIED:out of scope`/`REFUSED:<message>`/`PROMOTED`/`STARTED`/
  `UPLOADED:<count>`), `summary` a human sentence — the "one string does both jobs" shortcut is
  deliberately avoided. `AuditAction.UPDATED` is the fallback for every denial and for `promote`
  (no dedicated "denied"/"promoted" action value exists).
- Hand-faked ports in tests (`FakeDatasetRepositoryPort`, `FakeTrainingSampleRepositoryPort`, etc.,
  nested per test class) are this module's dominant style; `StreamService`/`AssetLiveStatePort`-shaped
  large interfaces are mocked with Mockito instead, matching the rest of the pre-extraction codebase's
  precedent.

## Gotchas
- **`DefaultLabelingService` is the only place any context reads back out of `vision-events`** — its
  `ReplaySources` collaborator (`AssetUsageRepositoryPort`/`DetectionRepositoryPort`/
  `ReplayFrameExtractionPort`, bundled in `vision-events`) is a single, deliberate edge:
  `events` is designed as a pure downstream reader that nothing reads back into, except this one case
  — capturing a training frame from a *finished flight's* replay is legitimately a read of history,
  not a write to it. Do not treat this as license for other contexts to start reading `events` too.
- **`ReplayCaptureSpec` lives here, not in `vision-events`, even though it names a replay concept** —
  it carries a `DatasetId` (this context's own type), so W1.6b filed it by consumer rather than by the
  context that captures the frame. If you go looking for it under `events.application`, it isn't there.
- **`LabelingService`'s scope relaxation is intentionally more permissive than `DatasetService`'s own
  gate** — see the "Scope, the deliberate relaxation" note in the API surface above. Do not "fix" this
  by unifying the two gates; doing so silently breaks pilot capture/label access.
- **`DatasetService#get`/`#delete` return `AccessDeniedException` (403), not the hiding-404 convention
  most other scoped reads in this codebase use** — a deliberate frozen-contract choice
  (docs/plans/done/CV-TRAINING-PLAN.md), not an inconsistency to "fix" toward `AssetService#details`'s pattern.
- **`TrainingJobService#start`'s local job id and cv-service's own wire `jobId` may legitimately
  differ** — `TrainingPort#startTraining` blocks for the job's entire lifetime, so `start` cannot wait
  for cv-service to assign its own id before answering. Every poll (`job(jobId)`) is keyed by the
  locally-generated `UUID`; the wire id is informational only and read nowhere in this class.
- **`uploadForTraining` is not REST-exposed** — its only caller is `DefaultTrainingJobService#runJob`.
  Do not add a controller route expecting to call it directly; a manual "upload without training" flow
  does not exist by design (upload was folded into "one-button training", replacing the old manual
  export step).
- **A model-id re-arm on the *serving* side (perception's `PipelineConfig`/`StreamPipeline`) is not
  this context's concern** — `ModelRegistryService#promote` only flips which checkpoint is "active" in
  the registry; it does not itself cause any running stream to switch models. That is
  `perception.application.stream`'s `updateConfig`/re-arm mechanism — see `vision-perception`'s
  `MODULE.md`.

## Status

**W1.7b extraction** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16): this context moved out of the
flat `vision-domain`/`vision-application` modules into its own Maven module, `contexts/vision-learning`
— a directory move (`com/drones/vision/learning/**`), no package rename, no behavior change. Depends
on `vision-kernel`, `vision-platform`, `vision-warehouse`, `vision-perception`, `vision-events`.
**158/158 green**, unchanged from the pre-extraction combined count.

docs/plans/done/CV-TRAINING-PLAN.md **Wave T1 done** (domain half — capture/label/dataset value models
+ ports): the nine domain types and four ports listed in the API surface above, plus (at the time) a
now-deleted `DatasetExport`/`DatasetExportPort` pair. No change to any pre-existing type.

docs/plans/done/CV-TRAINING-PLAN.md **Wave T2 done** (application half — capture/label/dataset
services + YOLO export): `DatasetService`/`DefaultDatasetService`, `LabelingService`/
`DefaultLabelingService` (capture/samples/image/label/export — export later replaced by upload, see
Wave W5 below), `DatasetSpec`/`CaptureSpec`/`LabelSpec`, `TrainingStores`, `YoloDatasetWriter`,
`TrainingFrameEncoder`. Plus one deliberate touch to perception's `StreamPipeline`/`StreamService`
(now in `vision-perception`): a `latestRawFrame` field/accessor this context's `capture` reads.
**Two design calls flagged and resolved by this wave**: `DatasetService#create` takes an explicit 4th
`Ownership` parameter (see the API surface entry above); `LabelingService`'s five methods use a more
permissive dataset-visibility rule than `DatasetService#get`/`#list` (see Gotchas).

docs/plans/done/CV-TRAINING-PLAN.md **Phase 2 §6/§7 done** (domain half — training-job seam):
`TrainingJobSpec`/`TrainingProgress`/`JobState`, `TrainingPort`.

docs/plans/done/CV-TRAINING-PLAN.md **Phase 2 T8 done, application half — model registry control
plane** (the unblocked "list/promote" half; `StartTraining`/`TrainingJob` stayed gated until the wave
below): `ModelRegistryService`/`DefaultModelRegistryService`, `RegisteredModel`. Built against the
already-frozen, already-implemented `ModelRegistryPort` and its first real implementation
`GrpcModelRegistryPort` (`adapter-cv-grpc`) without touching either.

docs/plans/done/CV-TRAINING-PLAN.md **Phase 2 — `TrainingJobService` done** (application half — start +
off-thread run + pollable job state): `TrainingJobService`/`DefaultTrainingJobService`,
`TrainingJobView`. **Central design call**: the locally-generated job id vs. the wire job id (see
Gotchas). **Retention**: `MAX_FINISHED_JOBS = 50`. **Audit action choice**: `start` uses
`AuditAction.CREATED`, not `UPDATED` — starting a job is create-shaped, unlike `promote`'s mutation of
an existing model reference.

docs/plans/done/CV-TRAINING-V2-PLAN.md **Wave W1 done** (domain half — one-button training + replay
capture delta): a delta on top of Wave T1 — deleted the manual-export surface
(`DatasetExportPort`/`DatasetExport`, and the `DatasetStatus.EXPORTING` constant nothing ever set),
added `DatasetUpload`/`DatasetUploadPort` and (at the time, in `events`; moved here in W1.6b)
`ReplayFrameExtractionPort`.

docs/plans/done/CV-TRAINING-V2-PLAN.md **Wave W5 done** (application half — one-button training +
capture-from-replay delta): deleted `LabelingService#export`, added `ReplayCaptureSpec`,
`ReplaySources` (a bundle of ports then in `events`, now that module's own type — see Gotchas for why
this context still reads it), `LabelingService#captureFromReplay`, `LabelingService#uploadForTraining`
(export's replacement), `YoloDatasetWriter#dataYaml` (moved verbatim from a deleted
`FilesystemDatasetExport` in `adapter-persistence`, so all format composition lives in this one pure,
unit-tested place). `TrainingStores`'s fourth component retyped (`DatasetExportPort` → `DatasetUploadPort`).
`DefaultTrainingJobService#start` gained the synchronous "has LABELED samples" pre-check;
`DefaultTrainingJobService#runJob` became upload-then-train.

**Judgement calls made assembling this document** (W1.7c, docs/plans/active/DOMAIN-SEPARATION-W1.md
§16): the CV-TRAINING-PLAN Phase 2 T8 (model registry) and TrainingJobService entries, and both
CV-TRAINING-V2-PLAN waves, were split out of `vision-application/MODULE.md`'s old flat "Status" prose
(which mixed learning material with perception/flight/warehouse waves in the same paragraphs) and
condensed into the learning-only material above; the domain-side T1 details were similarly pulled
from `vision-domain/MODULE.md`. The `LAYERING-REFACTOR-PLAN.md` Wave A entry (feature-first packaging
+ config extraction) was **not** brought across as its own Status entry — it is genuinely cross-cutting
(it touched all eight contexts' worth of services in one commit, most of them owned by sibling
contexts), so duplicating it three times across map/learning/simulation's docs seemed worse than
citing it once here: it is the wave that gave `DefaultLabelingService`/`TrainingFrameEncoder` their
`jpegQuality` constructor parameter and `DefaultTrainingJobService` its `maxFinishedJobs` one (both
now folded into the constructor signatures documented above), and moved this context's classes from a
flat `com.drones.vision.application.training` package into `com.drones.vision.learning.application`.

**docs/plans/active/OPS-UX-PLAN.md Wave C (C4) done**: `DefaultModelRegistryService#promote` and
`DefaultTrainingJobService#start` moved their gate from `scope.canManageOrg()` to
`scope.canAdminister()` — both are deployment-global actions (swap the model every stream uses; claim
the one shared training host), so a MANAGER's team-scoped authority is no longer enough, matching
docs/conclusions/OPS-UX-REVIEW.md §A1. Both already audited denials before this wave (`DENIED:out of
scope`) and still do — only the predicate deciding the outcome changed, the audit shape is untouched.
Existing tests that had asserted a `groups()` (MANAGER) scope could `promote`/`start` were the
finding, not a regression: `promoteSucceeds...` was re-targeted from a manager scope to
`VisibilityScope.unbounded()`, and the manager case became its own `promoteDeniedForAManagerScope...`
assertion; likewise a `startDeniedForAManagerScope...` test was added and every pre-existing
happy-path/pre-check test in `DefaultTrainingJobServiceTest` was re-targeted from `managerScope` to a
new `adminScope = VisibilityScope.unbounded()` field. 158 → 160 tests, `./mvnw -B -pl
contexts/vision-learning test` green. With `vision.auth.enabled=false` every caller is `unbounded()`,
so `canAdminister()` is always `true` and behavior is unchanged from before this wave.
`DatasetService`/`LabelingService` are explicitly out of scope for this wave and remain on
`canManageOrg()` — dataset lifecycle is team-scoped management, not deployment-global.
