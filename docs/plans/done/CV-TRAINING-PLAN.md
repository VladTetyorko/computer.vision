# CV-TRAINING-PLAN — operator-in-the-loop model improvement loop

Status: **BUILT — the whole loop ships** (2026-08-01). Capture → correct → export → **train** →
promote is live end to end. Owner: CV training.

> **Reconciliation — what actually shipped vs. this doc's original sketch** (the sections below are
> the original spec, kept for context; where they differ, reality wins):
> - **Phase 1** (capture → correct → dataset export): built as specified — `Dataset`/`TrainingSample`/
>   `Annotation`/`SampleImage` + the four ports, `DatasetService`/`LabelingService`, JPA + `V11` +
>   in-memory fallbacks, `FilesystemDatasetExport`, REST gated by `vision.training.enabled`, and the
>   `/manage/training` labeling UI.
> - **Phase 2 model registry** (list/promote): `cv-service` `ListModels`/`PromoteModel`, domain
>   `ModelRegistryPort` (with `active()` added — the "which is live" flag, kept off `ModelRef`), the
>   `GrpcModelRegistryPort` adapter, `ModelRegistryService`, REST, and the `/manage/training/models`
>   promote UI.
> - **Phase 2 training** (`StartTraining`): built **device-agnostic** (ultralytics uses CUDA if
>   present, else CPU — slow, flagged), **not** the GPU-host-only/offline-manual-default this doc
>   sketched. The domain shape landed as **`TrainingPort`** (callback-streaming) + `TrainingJobSpec`/
>   `TrainingProgress`/`JobState` and an application **`TrainingJobService`** (local jobId, off-thread,
>   pollable `TrainingJobView`) — **not** the `ModelTrainingPort`/`TrainingJob(poll)` shape §7 sketched.
>   Dataset delivery to the training host is via a configured `CV_DATASET_DIR` (rsync-consistent).
>   REST `POST /api/datasets/{id}/train` + `GET /api/training/jobs[/{id}]`; a "Train a model" card +
>   live progress page in the web. Known accepted seam: cv-service reports `version=""` (no per-model
>   versioning) so the promote UI sends a `'latest'` sentinel.
>
> Original spec follows.

Authoritative spec for closing the CV improvement loop: capture
frames + their detections during ops → operator confirms/corrects → a labeled dataset accumulates →
export a YOLO dataset → (offline) fine-tune → ingest + promote the new model live. Owner: CV
training. Freezes the wire/type contract and disjoint waves. Builds on the shipped CV control plane
([`CV-CONTROL-PLAN.md`](CV-CONTROL-PLAN.md), [`REMOTE-CV-PLAN.md`](REMOTE-CV-PLAN.md),
[`CV-SCALE-PLAN.md`](../active/CV-SCALE-PLAN.md)) and the remote cv-service registry.

> **The bulk of the value is Phase 1, and Phase 1 has zero flight risk.** Capturing what the
> operator actually sees, letting them correct it, and accumulating a clean YOLO dataset is fully
> buildable *here*, on the platform, today. Training a model is a *separate*, offline concern — see
> the constraint below. This plan ships the data pipeline first and treats training as a seam.

## Goal, in the operator's terms

> "This deployment keeps looking at buildings (or tanks, or whatever this mission's real targets
> are) and the general model half-misses them. While I'm watching the feed, let me grab a frame,
> fix the boxes the model drew — drag one, add the one it missed, delete a wrong one — and drop it
> into a dataset. After we've collected enough, export it, train a better model somewhere with a
> GPU, and load that model back in so the next flight detects better."

Made precise:
- **Capture is operator-triggered**, not automatic sampling: the operator taps *"Add to dataset"*
  on a live stream (or a replayed detection), and the platform snapshots the current frame + the
  model's current detections as **suggested** annotations. (Automatic/active-learning sampling is a
  named non-goal — see §Non-goals.)
- **Correction is the point.** The suggested boxes are pre-filled from the live detections; the
  operator confirms/edits them into **ground-truth annotations** (label + box), then marks the
  sample `LABELED`. The stored sample is the corrected truth, not the model's guess.
- **A dataset accumulates** LABELED samples, owned + scoped like every other user datum.
- **Export produces a real YOLO-format dataset** (an `images/` + `labels/` tree + `data.yaml`) —
  the artifact a fine-tune actually consumes.
- **Training runs offline** (GPU box / cloud); the platform exports the dataset and later **ingests**
  the produced model artifact into the registry and **promotes** it — the swap-live machinery
  (`ModelRegistryPort`, `CvModelsController`, per-stream `model` PATCH) already exists.

## The constraint that shapes everything: where training runs

The production cv-service runs on **GB4005** (`vlad@192.168.0.106`) — **Intel-only, NO CUDA**,
OpenVINO CPU inference, rsync deploys (MEMORY: gb4005-inference-box; `cv-service/Dockerfile` pulls
CPU-only torch and exports OpenVINO IR). **Fine-tuning a YOLO model on that box is impractical** —
it is an inference appliance, not a trainer. A plan that assumes GPU training on the inference box
is simply wrong.

So training location is designed honestly, in two phases:

- **Phase 1 — capture → label/correct → dataset export (buildable now, zero risk).** This is the
  entire in-platform contribution and the bulk of the value. It ends at a **YOLO dataset artifact**
  on disk. No model is trained by the platform.
- **Phase 2 — train + ingest + promote (seam, mostly out-of-platform).** The default path is
  **offline/manual**: a human takes the exported dataset to a GPU box or cloud, runs the fine-tune
  (`ultralytics` `yolo train`), producing a new `.pt` (and, for GB4005, an exported
  `*_openvino_model/` IR), rsyncs it into cv-service's model directory (the existing deploy
  mechanism), and the platform **ingests** it (registry `ListModels`) and **promotes** it
  (`PromoteModel` → the roster's default). The `ModelTrainingPort` seam *optionally* wraps a gRPC
  `Training.StartTraining` call **only for a GPU-equipped training host** (never GB4005). If anyone
  ever wires a CPU fine-tune on the Intel box, it must be labelled as the slow, tiny-run,
  demo-only path it is — not presented as production training.

`proto/vision/v1/cv.proto` **already declares the `Training` service** (`StartTraining`,
`ListModels`, `PromoteModel`) — today every RPC `context.abort(UNIMPLEMENTED, "Phase 3")`
(`cv_service/server.py` `TrainingServicer`). That is the exact, pre-designed ingest/promote seam
Phase 2 fills.

---

## Current state (grounded)

| Concern | Today | Gap this plan closes |
|---|---|---|
| **Run/swap models** | `GET /api/cv/models` (`CvModelsController`, static `cvModelRoster` bean of `CvModelResponse`); per-stream `PATCH /api/streams/{id}/config` `{model}` live-swaps; `StreamPipeline.updateConfig` re-arms on model change | keep verbatim — the promotion/swap target Phase 2 re-enters |
| **`ModelRegistryPort`** | Declared (`domain.port.out`, `models()`/`promote(ModelRef)`) but **dormant — no implementation anywhere**; `CvModelsController` deliberately uses the static roster, not this port | give it its first real impl (adapter-cv-grpc → `Training.ListModels`/`PromoteModel`) in Phase 2 |
| **cv-service `Training` RPCs** | `TrainingServicer.{StartTraining,ListModels,PromoteModel}` all `abort(UNIMPLEMENTED)`; `ModelRegistry.discover_roster` globs `*.pt` + `*_openvino_model/` in the model dir at startup (a dropped-in artifact is routable after a rescan/restart) | implement `ListModels`/`PromoteModel` + a rescan hook (Phase 2) |
| **Detection data** | `DetectionResult(streamId, frameSequence, capturedAt, List<Detection>, inferenceLatency)`; `Detection(label, confidence, BoundingBox box, ModelRef model)`; `BoundingBox` normalized `[0,1]`; persisted **metadata-only** via `DetectionRepositoryPort`/`detection_results` (jsonb) | reuse `Detection`/`BoundingBox` as the shape of a *suggested* annotation; add a captured-**frame** store (net-new) |
| **Frame image bytes** | `VideoFrame` (live `ByteBuffer`) is streaming-only, never persisted. **The only binary column in the whole schema is `asset_images.data BYTEA`** (`AssetImage`/`AssetImageRepositoryPort`, one still per asset). `StreamPipeline.latestFrame()` + `SnapshotJpegEncoder` back `GET /api/streams/{id}/snapshot` (JPEG of the latest **published** frame, post-overlay, downscaled) | new `TrainingSample` image store, modelled exactly on the `AssetImage`/bytea precedent; capture reuses the snapshot seam (full-res, **raw** frame — see §D) |
| **Detection → UI** | SSE `EventSource` `GET /api/live` (`detections:<assetId>`, latest-frame only) + 2s poll `GET /api/streams/{id}/detections`; overlay drawn by `vision-player` canvas (`redrawOverlay`/`letterboxRect`/`drawBox`, normalized→px); `DetectionsStore` | reuse the overlay + `letterboxRect` (inverted, px→normalized) for an **editable** labeling overlay |
| **Persistence** | Opt-in `vision.persistence.enabled` (default false); JPA repos in adapter-persistence, in-memory fallbacks in vision-app `devsupport`; Flyway `V1..V10` (next: **V11**); history repos persist immutable rows + prune (cap 100k); jsonb via `@JdbcTypeCode(SqlTypes.JSON)`; **`AuditTrailPort` has no JPA impl** (audit is in-memory) | new dataset/sample/annotation tables + a sample-image bytea store + V11 |
| **Scope / audit** | `VisibilityScope` (application; `includes(Asset)`, `includesGroup(GroupId)`); `Ownership(ownerId, groupId)`; `AuditEntry.of(actor, AuditAction, AuditTargetType, targetId, summary, details)`; `AuditTargetType {ASSET, DEVICE}` (opaque id string, javadoc pre-blesses "trained models" as a future kind); `EventType.TRAINING` **already exists** | datasets carry `Ownership`, scoped by `includesGroup`; add `AuditTargetType.DATASET`/`MODEL`; surface training-job progress as `TRAINING` events |
| **Training feature** | none — no capture, annotation, dataset, export, train, or promote endpoint; no `features/labeling`/`training`; web CV surface is read-roster + config PATCH | the whole loop, gated behind a new `vision.training.enabled` (default false) |

---

## Frozen contract

Everything below is frozen. All waves code against it and may parallelize. Names, JSON shapes,
status codes, property names, and the YOLO layout are pinned exactly. **Phase 2 items (§6–§8) are
frozen enough to build Phase 1 against but are not implemented until Phase 1 lands.**

### 1. Domain — ids, value models, ports (`vision-domain`) — Phase 1

Package `com.drones.vision.domain.model` (values) / `com.drones.vision.domain.port.out` (ports).
Framework-free; validate in compact constructors with manual `if (…) throw new
IllegalArgumentException(…)` (domain idiom). Ids wrap `UUID` with `random()`/`of(String)`.

```java
public record DatasetId(UUID value)        { static random(); static of(String); }
public record TrainingSampleId(UUID value) { static random(); static of(String); }

/** One ground-truth box on a captured frame. Reuses BoundingBox; NO confidence (this is truth,
 *  not a guess). `source` records whether it came from a model detection the operator kept, or was
 *  drawn/edited by the operator. `label` must not be blank. */
public record Annotation(String label, BoundingBox box, AnnotationSource source) { }
public enum AnnotationSource { MODEL, OPERATOR }   // provenance only, no behavior

/** A named accumulation of labeled frames aimed at improving detection of one target.
 *  `classes` is the ORDERED YOLO class list (index == its position); every Annotation.label in a
 *  sample must be a member (enforced in the application layer, not here). `targetCategory` is the
 *  optional CategoryId the dataset improves (e.g. "building"); nullable. */
public record Dataset(DatasetId id, String name, CategoryId targetCategory, List<String> classes,
                      Ownership ownership, DatasetStatus status, Instant createdAt) { }
public enum DatasetStatus { OPEN, EXPORTING, ARCHIVED }

/** A captured frame + its (evolving) annotations. Image BYTES are NOT here — stored separately by
 *  SampleImageStorePort keyed by id, exactly as AssetImage bytes are keyed by AssetId. */
public record TrainingSample(TrainingSampleId id, DatasetId datasetId, StreamId streamId,
                             AssetId assetId, Instant capturedAt, int width, int height,
                             List<Annotation> annotations, SampleStatus status,
                             UserId labeledBy, Instant labeledAt) {
    // width/height positive; annotations defensively copied; assetId/labeledBy/labeledAt nullable
    // (nullable until reviewed); status transitions are an application concern.
}
public enum SampleStatus { PENDING, LABELED, DISCARDED }
//   PENDING   = captured, annotations are model suggestions, not yet reviewed
//   LABELED   = operator confirmed/corrected — the only status export includes
//   DISCARDED = operator rejected the frame (kept for provenance, never exported)

/** Image bytes for one sample. Mirrors AssetImage's defensive-copy discipline (clone in + out). */
public record SampleImage(byte[] data, String contentType) { }   // contentType e.g. "image/jpeg"

/** The manifest of one completed dataset export — what the export produced, for download/train. */
public record DatasetExport(DatasetId datasetId, String exportId, Instant exportedAt,
                            List<String> classes, int sampleCount, long sizeBytes, String location) {}
```

New out-ports (Phase 1):

```java
public interface DatasetRepositoryPort {
    Dataset save(Dataset dataset);                 // upsert-by-id
    Optional<Dataset> findById(DatasetId id);
    List<Dataset> findAll();                        // scope-filtered by the caller (application)
    void delete(DatasetId id);                      // idempotent
}

public interface TrainingSampleRepositoryPort {
    TrainingSample save(TrainingSample sample);     // upsert-by-id (annotations + status evolve)
    Optional<TrainingSample> findById(TrainingSampleId id);
    List<TrainingSample> findByDataset(DatasetId datasetId, SampleStatus statusOrNull, int limit);
    int countByDataset(DatasetId datasetId, SampleStatus statusOrNull);
    void delete(TrainingSampleId id);
}

/** Image bytes keyed by sample id — the AssetImageRepositoryPort shape, verbatim. */
public interface SampleImageStorePort {
    void save(TrainingSampleId id, SampleImage image);   // upsert
    Optional<SampleImage> findById(TrainingSampleId id);
    void delete(TrainingSampleId id);                    // idempotent
}

/** Writes a YOLO-format dataset (see §5) to a durable location and returns its manifest. The
 *  application composes the entries (pure, testable); this port only sinks bytes + returns a handle. */
public interface DatasetExportPort {
    DatasetExport write(DatasetId datasetId, List<String> classes, List<ExportEntry> entries);
    Optional<Path> resolve(DatasetId datasetId, String exportId);   // for the download endpoint
    /** One image + its YOLO label file content. `imageName` is the basename (e.g. "<sampleId>.jpg"). */
    record ExportEntry(String imageName, byte[] imageBytes, String labelFileText) {}
}
```

### 2. Application — services (`vision-application`) — Phase 1

Spring-annotation-free. `VisibilityScope`/`AccessDeniedException` are application types, as in
`DefaultFlightCommandService`/`DefaultAssetService`. Reuse the scope-gate + audit idioms verbatim.

```java
public interface DatasetService {
    Dataset create(DatasetSpec spec, UserId actor, VisibilityScope scope);          // audits CREATED
    List<Dataset> list(UserId actor, VisibilityScope scope);                        // scope-filtered
    Dataset get(DatasetId id, UserId actor, VisibilityScope scope);                 // 403 out of scope
    void delete(DatasetId id, UserId actor, VisibilityScope scope);                 // audits DELETED
}

public interface LabelingService {
    /** Capture: reads the stream's latest RAW frame (full-res JPEG) + latest detections, creates a
     *  PENDING sample whose annotations are the detections mapped to AnnotationSource.MODEL, in the
     *  target dataset. Requires the source asset AND the dataset in scope. Audits UPDATED on DATASET. */
    TrainingSample capture(CaptureSpec spec, UserId actor, VisibilityScope scope);
    List<TrainingSample> samples(DatasetId id, SampleStatus statusOrNull, int limit,
                                 UserId actor, VisibilityScope scope);
    SampleImage image(TrainingSampleId id, UserId actor, VisibilityScope scope);
    /** Confirm/correct: replaces annotations, sets status LABELED or DISCARDED, stamps labeledBy/At.
     *  Rejects any annotation label not in the dataset's `classes`. Audits UPDATED on DATASET. */
    TrainingSample label(TrainingSampleId id, LabelSpec spec, UserId actor, VisibilityScope scope);
    /** Exports every LABELED sample as a YOLO dataset via DatasetExportPort. Audits UPDATED. */
    DatasetExport export(DatasetId id, UserId actor, VisibilityScope scope);
}
```

- Command records (top-level, application): `DatasetSpec(String name, CategoryId targetCategory,
  List<String> classes)`; `CaptureSpec(StreamId streamId, DatasetId datasetId)`;
  `LabelSpec(List<Annotation> annotations, SampleStatus status)` (status ∈ {LABELED, DISCARDED}).
- **Scope gate** — reuse `DefaultFlightCommandService`/read-path idioms: dataset ops filter/gate on
  `scope.includesGroup(dataset.ownership().groupId())`; capture *also* gates the source asset on
  `scope.includes(asset)` (via `AssetService.details`). Out-of-scope → `AccessDeniedException` (→ 403)
  + audit `DENIED`.
- **Ownership** stamped like other creates: from the acting user / constant dev principal
  (`Ownership(actor, actor's group)`), the same way assets are owned today.
- **YOLO serialization is pure + in the application** (`YoloDatasetWriter`, package-private): maps
  each LABELED sample → one `ExportEntry` (image basename `<sampleId>.jpg`, label text per §5), plus
  the `data.yaml` from `Dataset.classes`. Fully unit-testable with no IO; `DatasetExportPort` does
  the sinking. Class index = position of the annotation's label in `Dataset.classes`.
- **Latest-raw-frame** — `StreamPipeline` gains a `latestRawFrame()` (the pre-overlay `frame` in
  `onNext`, kept in a sibling `volatile`, no per-frame copy) + `StreamService.latestRawFrame(StreamId)`
  so capture gets **clean pixels** (see §D). Additive; existing constructors/tests untouched.
- **Audit** — `auditTrail.record(AuditEntry.of(actor, action, AuditTargetType.DATASET,
  datasetId.value().toString(), summary, attrs))`. Actions: `CREATED` (dataset), `UPDATED` (capture,
  label, export), `DELETED` (dataset). One audit line per mutating call.

### 3. REST surface (`vision-api`) — Phase 1

All under the existing same-origin session-cookie auth + `SecurityConfig` secured `/api/**` chain,
and all **gated by `vision.training.enabled`** (`@ConditionalOnProperty`, default false → routes
404, exactly like `LiveController` under `vision.live.enabled`). DTOs in
`com.drones.vision.api.dto`. Errors map through the existing `ApiExceptionHandler`
(`AccessDeniedException`→403, `NoSuchElementException`→404, `IllegalArgumentException`→400).

```
POST   /api/datasets                          -> 201 DatasetResponse
GET    /api/datasets                          -> 200 DatasetsResponse           (scope-filtered)
GET    /api/datasets/{id}                      -> 200 DatasetResponse | 404
DELETE /api/datasets/{id}                      -> 204 | 404 | 403
POST   /api/streams/{streamId}/samples         -> 201 SampleResponse   body: {datasetId}
GET    /api/datasets/{id}/samples?status=PENDING&limit=50 -> 200 SamplesResponse
GET    /api/samples/{id}/image                 -> 200 image/jpeg (Cache-Control: no-store) | 404
PUT    /api/samples/{id}/annotations           -> 200 SampleResponse   (confirm/correct)
POST   /api/datasets/{id}/export               -> 202 DatasetExportResponse
GET    /api/datasets/{id}/export/{exportId}    -> 200 application/zip | 404
```

Frozen JSON (property names exact; `box` is normalized `[0,1]`, top-left origin — same as
`BoundingBoxResponse` everywhere else):

```jsonc
// POST /api/datasets  request
{ "name": "Buildings — site A", "targetCategory": "building", "classes": ["building", "tower"] }

// DatasetResponse
{ "id": "<uuid>", "name": "Buildings — site A", "targetCategory": "building",
  "classes": ["building", "tower"], "status": "OPEN", "createdAt": "2026-08-01T10:00:00Z",
  "sampleCounts": { "PENDING": 12, "LABELED": 40, "DISCARDED": 3 } }

// POST /api/streams/{streamId}/samples  request
{ "datasetId": "<uuid>" }

// SampleResponse   (annotations pre-filled from live detections on capture, source="MODEL")
{ "id": "<uuid>", "datasetId": "<uuid>", "streamId": "<uuid>", "assetId": "<uuid>",
  "capturedAt": "2026-08-01T10:00:01Z", "width": 1920, "height": 1080, "status": "PENDING",
  "labeledBy": null, "labeledAt": null,
  "annotations": [ { "label": "building", "source": "MODEL",
                     "box": { "x": 0.10, "y": 0.20, "width": 0.30, "height": 0.25 } } ] }

// PUT /api/samples/{id}/annotations  request   (status ∈ LABELED|DISCARDED)
{ "status": "LABELED",
  "annotations": [ { "label": "building", "source": "OPERATOR",
                     "box": { "x": 0.11, "y": 0.19, "width": 0.32, "height": 0.27 } } ] }

// DatasetExportResponse
{ "datasetId": "<uuid>", "exportId": "<uuid>", "exportedAt": "2026-08-01T10:05:00Z",
  "classes": ["building", "tower"], "sampleCount": 40, "sizeBytes": 18234123,
  "downloadUrl": "/api/datasets/<uuid>/export/<exportId>" }
```

- **Capture** reuses the snapshot seam: `StreamService.latestRawFrame(streamId)` →
  `SnapshotJpegEncoder.encode(frame)` at **full resolution** (a `encodeFull`/no-downscale variant —
  training wants real pixels, not the ≤`MAX_SNAPSHOT_WIDTH` thumbnail the dashboard uses), stored via
  `SampleImageStorePort`; detections from `StreamService.latestDetections(streamId)` become the
  `MODEL` annotations. Stream unknown / no frame yet → 404.
- **Wiring** (`vision-app`): a `VisionTrainingProperties` (`vision.training.*`: `enabled`,
  `export-dir`); service beans; JPA-vs-in-memory repo selection follows the `vision.persistence.enabled`
  `ObjectProvider` pattern already used for every other repo.

### 4. Web data shape (`vision-web`) — Phase 1

New `core/api/models.ts` interfaces (mirror the DTOs above; `readonly`, camelCase):

```ts
export interface Annotation { readonly label: string; readonly source: 'MODEL' | 'OPERATOR';
                              readonly box: BoundingBox; }        // BoundingBox already exists
export type SampleStatus = 'PENDING' | 'LABELED' | 'DISCARDED';
export interface TrainingSample {
  readonly id: string; readonly datasetId: string; readonly streamId: string;
  readonly assetId?: string; readonly capturedAt: string; readonly width: number;
  readonly height: number; readonly status: SampleStatus; readonly labeledBy?: string;
  readonly labeledAt?: string; readonly annotations: readonly Annotation[];
}
export interface Dataset {
  readonly id: string; readonly name: string; readonly targetCategory?: string;
  readonly classes: readonly string[]; readonly status: 'OPEN'|'EXPORTING'|'ARCHIVED';
  readonly createdAt: string; readonly sampleCounts: Record<SampleStatus, number>;
}
export interface DatasetExport {
  readonly datasetId: string; readonly exportId: string; readonly exportedAt: string;
  readonly classes: readonly string[]; readonly sampleCount: number; readonly sizeBytes: number;
  readonly downloadUrl: string;
}
```

- New `VisionApi` methods: `listDatasets`, `createDataset`, `getDataset`, `deleteDataset`,
  `captureSample(streamId, {datasetId})`, `datasetSamples(id, status?, limit?)`,
  `sampleImageUrl(id)` (a plain `/api/samples/{id}/image` URL for `<img>`),
  `putSampleAnnotations(id, {status, annotations})`, `exportDataset(id)`.
- **Editable overlay** — a `shared/player` widget reusing `letterboxRect` **inverted** (screen px →
  normalized `[0,1]`) so drag-create / drag-resize / delete emit corrected `BoundingBox`es; the
  existing `DrawnBox` hit-testing in `player.ts` is the starting point (fork into an editable variant
  rather than complicating the live player).

### 5. YOLO dataset export format (frozen data)

`DatasetExportPort.write` produces a directory (zipped for download) with the Ultralytics YOLO layout:

```
<dataset>/
  data.yaml            # names: [<classes in order>]; nc: <len>; train: images; val: images
  images/<sampleId>.jpg
  labels/<sampleId>.txt
```

Each `labels/<sampleId>.txt` has one line per annotation:
`<class_index> <cx> <cy> <w> <h>` — all normalized `[0,1]`, **center-based** (the YOLO convention),
converted from the platform's top-left `BoundingBox`:
`class_index = classes.indexOf(label)`, `cx = x + width/2`, `cy = y + height/2`, `w = width`,
`h = height`. Only `LABELED` samples are exported; `PENDING`/`DISCARDED` are skipped. A sample with
zero annotations exports an empty label file (a valid YOLO "negative"/background image).

### 6. cv-service `Training` servicer — Phase 2 (frozen against existing `cv.proto`)

`cv.proto` is unchanged (its `Training` service already fits). Implement `TrainingServicer`
(`cv_service/server.py`, `cv_service/training.py` new):
- **`ListModels(Empty) -> ModelList`** — from `ModelRegistry.roster` + `discover_roster`; each
  `ModelInfo{id, version, stage, metrics}` (`stage="production"` for the default id, else `"available"`;
  `metrics` empty until a job records `map50`). Add a registry **rescan** so an artifact rsync'd in
  after startup appears without a full restart.
- **`PromoteModel(ModelRefMsg) -> Ack`** — re-point the registry default to `id` (the roster already
  routes per-request; "promote" = change the default id the roster/UI leads with). `ok=false` +
  message for an unknown id.
- **`StartTraining(TrainingJobSpec) -> stream TrainingProgress`** — **optional, GPU-host only.**
  Default GB4005 deployment leaves it `UNIMPLEMENTED`/refusing; a training-host build may implement
  `ultralytics` `yolo train` streaming `TrainingProgress{epoch,total_epochs,loss,map50,state}`. Any
  CPU path is demo-only and must say so.

### 7. Registry + training ports — Phase 2 (`vision-domain` / `vision-application`)

- **`ModelRegistryPort` gets its first impl**: `GrpcModelRegistryPort implements ModelRegistryPort`
  (adapter-cv-grpc) → `Training.ListModels`/`PromoteModel`. `ModelRef(id, version)` reused as-is.
- **`ModelTrainingPort` (new, out)** — `TrainingJob start(DatasetExport export, String baseModel,
  int epochs)` + `TrainingJob poll(TrainingJobId)`; default impl is the **offline/manual** flow
  (records a `TrainingJob` the operator advances by hand once they've trained + dropped the artifact
  in); an optional gRPC impl wraps `StartTraining` for a GPU host.
- **`TrainingJob(TrainingJobId, DatasetId, String baseModel, int epochs, TrainingJobState,
  Instant startedAt, Instant finishedAt, Double map50, String producedModelId, String message)`**;
  `TrainingJobState { PENDING, RUNNING, SUCCEEDED, FAILED }` (mirrors proto `JobState`). Progress is
  surfaced as `EventType.TRAINING` events (reuse — no new event type).

### 8. Registry + training REST — Phase 2 (`vision-api`)

```
GET  /api/cv/registry/models            -> 200 (ModelRegistryPort.models() + stage/metrics)
POST /api/cv/registry/models/{id}/promote -> 200   (audited, AuditTargetType.MODEL)
POST /api/datasets/{id}/train           -> 202 TrainingJobResponse   body: {baseModel, epochs}
GET  /api/training/jobs/{jobId}          -> 200 TrainingJobResponse
```

The existing static `GET /api/cv/models` roster stays the picker; `/api/cv/registry/models` is the
live registry view once Phase 2 lands.

---

## Design decisions (with rationale)

### A. Phase the loop by risk, not by feature completeness
The whole thing sounds like "train a model," but 80% of the value and 100% of the risk-free work is
**capture → correct → dataset**. Shipping Phase 1 alone already improves every deployment (a clean,
growing, deployment-specific YOLO dataset is valuable even before the first fine-tune) and never
touches the inference box's compute budget. Phase 2 is a thin ingest/promote layer over machinery
that already exists.

### B. Operator-tap capture, not automatic sampling
The operator is the label source, so capture is an explicit gesture. This (a) keeps the hot
`StreamPipeline` path untouched (capture reads the existing `latest*Frame` snapshot seam, it does
not hook inference), (b) guarantees every captured frame is one a human chose as worth labeling
(higher signal than random sampling), and (c) makes storage bounded by operator effort, not frame
rate. Active-learning / uncertainty sampling is a named non-goal (§Non-goals) — a clean later add on
the same `TrainingSample` model.

### C. Reuse the `AssetImage`/bytea precedent for frame bytes — honestly
The platform has exactly one binary store today (`asset_images.data BYTEA`) and no object store /
filesystem blob store. `SampleImage`/`SampleImageStorePort` copy that precedent bit-for-bit (bytea,
one row per sample, defensive-copy discipline, no referential integrity). This is honest and
buildable now; it is **not** what you'd run at 100k-frame scale. The open question "image storage
backend" (object store / filesystem + path) is named, not pretended-solved — the port boundary means
swapping the backend later is one adapter, zero application/domain change.

### D. Capture the **raw** frame at full resolution
`GET /api/streams/{id}/snapshot` returns the **post-overlay**, **downscaled** frame — right for a
dashboard thumbnail, wrong for training truth (burned-in boxes contaminate the pixels; downscaling
loses detail). Capture therefore uses a new `latestRawFrame()` (pre-overlay `frame`, one extra
`volatile`, no copy) encoded at full resolution. Small, additive, existing snapshot untouched.

### E. Suggested-then-corrected annotations are the loop
Pre-filling the operator's canvas with the model's current detections (`AnnotationSource.MODEL`) is
what makes labeling fast *and* is literally the improvement signal: where the operator edits, the
model was wrong. Storing `source` per annotation preserves that signal for later analysis. The stored
sample is always the corrected truth (`LABELED`), never the raw guess.

### F. Fill the pre-designed seams, don't invent parallel ones
`cv.proto`'s `Training` service, the dormant `ModelRegistryPort`, `ModelRef`, `EventType.TRAINING`,
and `AuditTargetType`'s "future kinds" javadoc were all left as deliberate seams. Phase 2 fills
exactly those — no new proto messages, no second model-management path beside `CvModelsController`.

### G. Guardrail: default-off flag leaves every existing test green
`vision.training.enabled` (default **false**) gates all new controllers/wiring — with it off, the
app behaves exactly as today (routes 404, no new beans require persistence). Storage follows the
existing `vision.persistence.enabled` opt-in with in-memory `devsupport` fallbacks, so the default
build needs no database. Everything is additive: new domain types, new ports, a new service, a new
V11 migration, new controllers, a new web feature. No existing endpoint, entity, or test changes.

---

## Implementation waves (disjoint file scopes)

Each wave ends **independently green** with its scoped build and its `MODULE.md` updated. The frozen
contract is the sole coupling.

### Phase 1 — data pipeline (buildable now, zero flight risk)

Sequencing: **T1 → (T2 ‖ T3) → T4 → T5**.

**T1 — domain: ids, value models, ports** — `vision-domain/**` (agent: **domain-modeler**). No deps.
- Add `DatasetId`, `TrainingSampleId`, `Annotation`, `AnnotationSource`, `Dataset`, `DatasetStatus`,
  `TrainingSample`, `SampleStatus`, `SampleImage`, `DatasetExport` (§1) + `AuditTargetType.DATASET`,
  `AuditTargetType.MODEL`. Ports `DatasetRepositoryPort`, `TrainingSampleRepositoryPort`,
  `SampleImageStorePort`, `DatasetExportPort`. Framework-free; compact-ctor validation; `SampleImage`
  clones in+out like `AssetImage`.
- Tests: validation (blank label, non-positive dims, status/enum), defensive copies, `SampleImage`
  clone-in/out. Verify `-pl vision-domain test` green; MODULE.md updated.

**T2 — application: dataset + labeling services + YOLO writer** — `vision-application/**`
(agent: **application-service**). Depends T1.
- `DatasetService`/`DefaultDatasetService`, `LabelingService`/`DefaultLabelingService`, command
  records (§2), pure `YoloDatasetWriter` (sample → `ExportEntry`, `data.yaml`, top-left→center box
  math §5, class-index lookup, label-not-in-`classes` rejection). Scope gate + audit reusing the
  `DefaultFlightCommandService`/read-path idioms. `StreamPipeline.latestRawFrame()` +
  `StreamService.latestRawFrame(StreamId)` (additive).
- Tests (hand-fakes for every port + `AssetService` + `AuditTrailPort`): create/list/delete scoped +
  audited; capture builds a PENDING sample with MODEL annotations from fake latest-frame+detections;
  label rejects out-of-vocab labels, sets LABELED/DISCARDED, audits; export includes only LABELED and
  produces correct YOLO text; out-of-scope → `AccessDeniedException` + DENIED audit. Verify
  `-pl vision-application test` green; MODULE.md updated.

**T3 — persistence: JPA repos + sample-image bytea + V11 + in-memory fallbacks** —
`adapters/adapter-persistence/**` + `vision-app/.../devsupport/**` (agent: **spring-integrator**).
Depends T1.
- JPA entities + `Jpa{Dataset,TrainingSample}Repository` + `JpaSampleImageStore` (bytea, `COUNT` for
  counts, no bytes-load on list — the `AssetImageRepository` pattern), `toEntity`/`toDomain` statics,
  jsonb for `annotations`/`classes`. **Flyway `V11__training_datasets.sql`** (`datasets`,
  `training_samples`, `sample_images` — `data BYTEA`; additive, no FKs, per module convention).
  In-memory `devsupport` fallbacks for the three repos. `DatasetExportPort` filesystem sink
  (`FilesystemDatasetExport` writing under `vision.training.export-dir`, zipped) — see Open Questions
  for its exact module home.
- Tests: Testcontainers Postgres round-trips (dataset upsert, sample status/annotation evolution,
  image bytea in/out, counts without loading bytes); in-memory fallback parity. Verify
  `-pl adapters/adapter-persistence test` + `-pl vision-app test` green; both MODULE.md updated.

**T4 — REST + wiring** — `vision-api/**`, `vision-app/**` (agent: **spring-integrator**). Depends T2+T3.
- Controllers + DTOs (§3), all under `@ConditionalOnProperty(vision.training.enabled)`; capture reuses
  `latestRawFrame` + full-res `SnapshotJpegEncoder`; `image` endpoint streams bytes (`no-store`);
  export returns 202 + zip download. `VisionTrainingProperties`; service + repo-selection beans
  (`ObjectProvider` persistence pattern); `ApiExceptionHandler` mappings cover the new flows.
- Tests: MockMvc per endpoint (201/200/204/403/404/400), capture-from-fake-stream, annotation PUT,
  export happy path; a wiring smoke test with the flag on. Verify `-pl vision-api test` +
  `-pl vision-app test` green; both MODULE.md updated.

**T5 — web: labeling feature + editable overlay + capture button** — `vision-web/**`
(agent: **web-ui**). Depends T4.
- `core/api/models.ts` types + `VisionApi` methods (§4); `core/training/` store; `features/labeling/`
  page (dataset list → PENDING sample grid → sample editor with the editable overlay → confirm/correct
  → LABELED/DISCARDED → export/download); an **"Add to dataset"** capture button in `features/fly`
  (and, where detections are shown in `features/replay`, on a replayed frame); a `features/hubs` entry
  under **Manage**. Editable overlay in `shared/player` (inverted `letterboxRect`).
- Tests: vitest for the store (capture/label/export flows, box px↔normalized round-trip) + overlay
  logic; `tsc` clean; production build green. Verify `npm run test:ci` + build green; MODULE.md updated.

### Phase 2 — training + promotion (mostly offline; do not start until Phase 1 lands)

Sequencing: **T6 ‖ T7 → T8 → T9 → T10**. Gated on explicit user go (the training-compute environment
is an open question — §Open questions).

**T6 — cv-service `Training` servicer** — `cv-service/**` (agent: **general-purpose**/python). Implement
`ListModels` + `PromoteModel` + rescan hook (§6); leave `StartTraining` GPU-host-only/refusing on
GB4005. Tests mirror `test_registry.py` (fake registry, no weights). Verify `cv-service` tests green;
MODULE.md updated.

**T7 — adapter-cv-grpc: `ModelRegistryPort` impl** — `adapters/adapter-cv-grpc/**`
(agent: **adapter-builder**). `GrpcModelRegistryPort implements ModelRegistryPort` → `ListModels`/
`PromoteModel`. Verify `-pl adapters/adapter-cv-grpc test` green; MODULE.md updated.

**T8 — application: `ModelTrainingPort` + `TrainingJob` service** — `vision-application/**` /
`vision-domain/**` (agents: **domain-modeler** then **application-service**). `ModelTrainingPort`,
`TrainingJob`/`TrainingJobState` (§7), offline-default training service surfacing `TRAINING` events.

**T9 — REST: registry + train/promote endpoints** — `vision-api/**` + `vision-app/**`
(agent: **spring-integrator**), §8, audited on `AuditTargetType.MODEL`.

**T10 — web: model management + training-job UI** — `vision-web/**` (agent: **web-ui**): registry model
list, promote action, start-training + job-progress (via `TRAINING` events), under the Manage hub.

---

## Storage, addressed honestly

- **Frame bytes** live in `sample_images.data BYTEA` (Postgres), one row per captured sample — the
  only mechanism the platform has today, reused deliberately. At real scale (thousands of frames) this
  wants an object store / filesystem-with-path backend; the `SampleImageStorePort` boundary makes that
  a single-adapter swap. **Named, not solved.**
- **Datasets survive restart only with `vision.persistence.enabled=true`** (in-memory fallback loses
  them, same as every other repo). The exported YOLO artifact on disk (`vision.training.export-dir`) is
  the durable, portable output regardless.
- **Audit** for training actions inherits the platform's current limitation — `AuditTrailPort` has no
  JPA impl, so audit is in-memory until that separate gap is closed (out of scope here).

## Non-goals / deferred (named, not dropped)

- **Automatic / active-learning sampling** — capture is operator-tap only in Phase 1. The
  `TrainingSample` model supports adding an auto-sampler later with no schema change.
- **On-device (GB4005) training** — explicitly out. Training is offline/GPU (§constraint). Any CPU
  fine-tune is demo-only and must be labelled as such.
- **Dataset versioning / train-val split policy** — v1 datasets are append-only; export uses the whole
  LABELED set as `train` (and mirrors it to `val` in `data.yaml` as a placeholder). Real
  split/versioning is deferred.
- **Object/filesystem blob backend** — deferred (bytea for v1, §Storage).
- **Segmentation masks / keypoints** — v1 is bounding boxes only (the platform's `Detection` shape).
  YOLOE open-vocab produces masks; v1 exports boxes.
- **In-platform model upload endpoint** — artifacts land in cv-service via the existing rsync deploy,
  not an HTTP upload (`cv.proto` has no model-bytes RPC by design).
- **Auto-retrain / continuous learning triggers** — manual export→train→promote in v1.

## Open questions / to confirm before implementation

1. **`DatasetExportPort` filesystem impl — module home.** Recommended: a thin
   `FilesystemDatasetExport` in adapter-persistence (the "user-data storage" module) writing under
   `vision.training.export-dir`; alternative is a small `vision-app` infra bean. Confirm placement (the
   pure YOLO serialization stays in `vision-application` either way).
2. **Image storage backend at scale** — bytea now; when do we need filesystem/object-store? (Port
   isolates the change.) Needs a rough target sample-count to decide urgency.
3. **The Phase-2 training compute environment** — is there a GPU box/cloud target, or is offline-manual
   the only path for now? This decides whether `ModelTrainingPort`'s gRPC `StartTraining` impl is built
   at all. **Needs the user's call before Phase 2 starts.**
4. **Dataset scope model** — datasets scoped by owning group (`includesGroup`), matching assets. Confirm
   pilots (ASSIGNED_ASSETS scope) may *capture* into a dataset they can see but not *create/delete* one
   (proposed default: create/delete = `canManageOrg`, capture/label = anyone who can see the dataset
   and the source asset).
5. **Class list vs `CategoryId`** — a dataset's `classes` are free-form YOLO label strings (matching
   `Detection.label`), with an optional `targetCategory` `CategoryId` for organization. Confirm we don't
   want to force classes to be `CategoryId` slugs (detection labels like `"skyscraper"` aren't
   categories — keeping them free-form is the honest choice, mirroring the open-vocab label reality
   `cvModelRoster` already documents).
