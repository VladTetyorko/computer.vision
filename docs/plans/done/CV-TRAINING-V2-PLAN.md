# CV-TRAINING-V2-PLAN — one-button training + capture from replay

Status: **SPEC — not started** (2026-08-01). A **delta** on top of
[`CV-TRAINING-PLAN.md`](CV-TRAINING-PLAN.md) (status *BUILT — the whole loop ships*). Owner: CV
training.

> **Read [`CV-TRAINING-PLAN.md`](CV-TRAINING-PLAN.md) first.** Everything it freezes stays frozen
> unless named here: the domain `Dataset`/`TrainingSample`/`Annotation`/`SampleImage` shapes (§1),
> dataset + capture + label REST (§3), the YOLO on-disk layout (§5), `Training.StartTraining`'s
> per-epoch streaming contract and `ListModels`/`PromoteModel` (§6/§7/§8), the `vision.training.enabled`
> default-off guardrail, and every scope/audit idiom. This document describes **only what changes**.

Two changes, both confirmed with the product owner:

1. **Delete the manual Export step.** Dataset delivery to cv-service becomes an implicit part of
   *Train*, over a new **gRPC upload RPC** — not a shared filesystem, not an automated rsync. Works
   whether cv-service is co-located or remote (GB4005: Intel-only, no CUDA — MEMORY:
   gb4005-inference-box).
2. **Capture a training frame from a recorded replay**, not just the live stream — an *"Add to
   dataset"* action inside the existing Replay page, beside *"Download clip"*.

## Goal, in the operator's terms

> "Two things annoy me. First: after I've labeled frames I have to press *Export*, then press
> *Train*, and the training host tells me the dataset isn't there — because somebody has to rsync a
> zip onto the box by hand. Just train it. Second: I only notice the model was wrong *after* the
> flight, watching the replay. Let me scrub to that moment and add *that* frame to the dataset,
> right there."

Made precise:

- **Train is the only button.** `POST /api/datasets/{id}/train` composes the YOLO dataset, ships it
  to cv-service over gRPC, and starts the fine-tune — one operator gesture, no artifact for a human
  to move. The `Export`/`Download zip` REST surface and its UI card are **deleted**, not hidden.
- **Delivery is transport, not storage.** No zip is written to the platform's disk at all; the
  archive is framed straight onto the wire. `vision.training.export-dir` disappears.
- **Re-training is idempotent.** Uploading dataset `X` again replaces whatever was there, so
  "label 40 more frames, train again" just works.
- **Replay capture produces the same `TrainingSample`** a live capture does — `PENDING`, with the
  model's own historical detections pre-filled as `MODEL` annotations — so the existing sample
  editor, labeling flow, and export path need zero changes.

---

## Current state (grounded)

| Concern | Today | Gap this delta closes |
|---|---|---|
| **Dataset delivery** | `POST /api/datasets/{id}/export` → `LabelingService#export` → `FilesystemDatasetExport` writes `<exportRoot>/<datasetId>/<exportId>.zip` (`FilesystemDatasetExport.java:50-64`); a human downloads it (`LabelingController.java:173-183`) and rsyncs it to `<CV_DATASET_DIR>/<dataset_id>/` unzipped. `trainer.py:16-23` documents exactly this manual step | one RPC; the platform never writes the archive to its own disk |
| **`POST /api/datasets/{id}/train`** | builds `TrainingJobSpec(baseModel, id, epochs)` from the raw path string and hands it to `TrainingPort` (`TrainingJobController.java:69-76`) — **zero reference to any export artifact**. Trains against whatever happens to be on the host | same endpoint also uploads first; a dataset that was never delivered can no longer 30-minutes-later `FAILED` with *"isn't on the training host"* |
| **`Training` service** | `StartTraining` / `ListModels` / `PromoteModel` (`cv.proto:112-117`); `TrainingServicer.StartTraining` resolves `<CV_DATASET_DIR>/<dataset_id>/` via `trainer.resolve_dataset_dir` (`trainer.py:112-144`), a missing dataset is a reported terminal `FAILED` (`server.py:596-601`) | one additive client-streaming RPC beside them; `StartTraining`/`trainer.py` are **not touched** |
| **gRPC channel** | one shared `ManagedChannel` for `DetectStream` + `ListModels`/`PromoteModel` + `StartTraining` (`WiringConfiguration.java:469-478`), plaintext + keepalive, **no message-size options**; cv-service server likewise (`server.py:100-105`) | nothing to change — see §D |
| **Live capture** | `DefaultLabelingService#capture` reads `streamService.latestRawFrame(streamId)` + `latestDetections(streamId)` (`DefaultLabelingService.java:112-118`) — the *current* frame only. No historical path exists | a second entry point sourced from a recorded usage |
| **Recording** | `ReplayService#recordingFor` → `StreamPublisherPort#playbackUrl` (default `Optional.empty()`, `StreamPublisherPort.java:126-128`), implemented by `MediamtxStreamPublisher#playbackUrl` as pure string formatting against mediamtx's `/get?path=&start=&duration=` (`MediamtxStreamPublisher.java:389-398`) — a byte-range-seekable MP4. Surfaced as `GET /api/usages/{usageId}/recording` → `UsageRecordingResponse{available,url?,start?,durationSeconds?}` | a server-side **single-frame** pull from that same endpoint, behind a new out-port |
| **Historical detections** | `DetectionRepositoryPort#query(DetectionQuery(streamId, from, to, label, limit))` — a real, time-bounded, adapter-side query (`DefaultReplayService.java:168-176`) | reused verbatim for the nearest-detection lookup; **no new repository surface** |
| **Playback base config** | `WiringConfiguration.java:362-369` builds `MediamtxStreamPublisher` with the **3-arg** overload, which *guesses* the playback base as `whepBase`'s host at port `19996` (`MediamtxStreamPublisher.java:201-219`); the 4-arg ctor and an explicit property were left as a named follow-up in that module's MODULE.md | the follow-up lands here — the frame extractor needs a real configured base |
| **Replay UI** | scrub bar + `<video>`, `nearestDetectionResult`/`isDetectionNear` (`replay-logic.ts:82-106`, display only), "Mark clip start/end → Download clip" rewriting the `/get` URL client-side (`replay-logic.ts:316-335`, `replay.html:74-85`) | an *"Add to dataset"* action in the same row, using `videoOffsetSeconds` (`replay-logic.ts:244-247`) — already exactly the offset the new endpoint wants |
| **Why capture wasn't already in replay** | `DatasetDetailFacade`'s own doc comment: *"Capture entry point lives here, not in Fly/Live/Replay — CV-TRAINING-PLAN Wave T5's own task brief flags the collision with parallel `features/fly/**` work"* (`dataset-detail-facade.ts:32-36`; echoed in `dataset-detail.ts:12-15`) | that parallel work landed long ago — the collision no longer exists, so replay is now the right home. (Note: this rationale lives in the **web facade**, not in `DefaultLabelingService`'s javadoc.) |

---

## Frozen contract

Everything below is frozen. All waves code against it and may parallelize. Names, JSON shapes,
status codes, property names, and proto field numbers are pinned exactly.

### 1. `cv.proto` — additive `Training.UploadDataset`

Appended to `proto/vision/v1/cv.proto`. **`TrainingJobSpec`, `TrainingProgress`, `JobState`,
`ModelInfo`, `ModelList`, `ModelRefMsg`, `Ack` and all three existing RPCs are unchanged.**

```proto
// One slice of an uploaded YOLO dataset archive (docs/plans/done/CV-TRAINING-V2-PLAN.md §1). The
// concatenation of every chunk's `content`, in stream order, is a ZIP file carrying exactly the
// layout docs/plans/done/CV-TRAINING-PLAN.md §5 freezes: `data.yaml`, `images/<name>`, `labels/<stem>.txt`.
message DatasetChunk {
  string dataset_id = 1;   // set on EVERY chunk; must be identical throughout the stream
  bytes  content    = 2;   // next slice of the archive
}

message UploadAck {
  bool   ok             = 1;
  string message        = 2;
  string dataset_id     = 3;
  int64  bytes_received = 4;
  int32  file_count     = 5;   // files extracted, incl. data.yaml
}

service Training {
  rpc StartTraining(TrainingJobSpec) returns (stream TrainingProgress);
  rpc UploadDataset(stream DatasetChunk) returns (UploadAck);          // NEW
  rpc ListModels(google.protobuf.Empty) returns (ModelList);
  rpc PromoteModel(ModelRefMsg) returns (Ack);
}
```

### 2. cv-service `UploadDataset` servicer (frozen behavior)

Implemented on the existing `TrainingServicer` (`cv_service/server.py:523+`), landing the archive
under the **same** `_DATASET_SEARCH_DIR` (`CV_DATASET_DIR`, `server.py:147-157`) `StartTraining`
already resolves against.

- **Destination**: `<CV_DATASET_DIR>/<dataset_id>/`. Written by extracting into a sibling temp dir,
  validating it with `trainer.resolve_dataset_dir` (unchanged, `trainer.py:112-144`), then
  **atomically replacing** any existing `<dataset_id>/` (remove-then-`os.replace`). A re-upload of
  the same id therefore fully supersedes the previous one — the idempotency the "label more, train
  again" loop needs.
- **`dataset_id` validation** (protocol-level → `context.abort(INVALID_ARGUMENT)`): blank; changed
  mid-stream; or containing a path separator / `..` (reuse `trainer.output_model_id`'s sanitize rule
  as the accept test — anything the sanitizer would rewrite is rejected outright rather than
  silently renamed).
- **Content problems are reported, never aborted** — the same posture `PromoteModel` already takes
  for an unknown model id (`server.py:733-775`): `UploadAck{ok:false, message}` for zero chunks
  received, a corrupt/unreadable zip, a zip entry outside `data.yaml`/`images/`/`labels/` (absolute
  path, `..`, or any other prefix), or an extracted tree `resolve_dataset_dir` rejects.
- **Size cap**: `_MAX_DATASET_UPLOAD_BYTES = 2 GiB`; exceeding it → `abort(RESOURCE_EXHAUSTED)`.
- **Success**: `UploadAck{ok:true, dataset_id, bytes_received, file_count, message:"dataset '<id>'
  ready (<n> files)"}`.
- **`StartTraining` and `trainer.py` are not modified.** The on-host contract after an upload is
  byte-identical to what a manual rsync produced, so the manual path keeps working as a fallback.

### 3. Domain delta (`vision-domain`)

**New** — `com.drones.vision.domain.port.out` / `...model`:

```java
/** Driven port: ship a YOLO dataset (docs/plans/done/CV-TRAINING-PLAN.md §5) to the training host, replacing
 *  any prior upload for the same datasetId. Framing/transport are the implementation's business;
 *  the application composes the (pure, in-memory) content. */
public interface DatasetUploadPort {

    DatasetUpload upload(DatasetId datasetId, String dataYaml, List<ExportEntry> entries);

    /** One image plus its YOLO label file content — the shape DatasetExportPort.ExportEntry had,
     *  verbatim (same three compact-constructor checks). */
    record ExportEntry(String imageName, byte[] imageBytes, String labelFileText) { }
}

/** What one completed upload delivered. */
public record DatasetUpload(DatasetId datasetId, Instant uploadedAt, int sampleCount, long sizeBytes) { }

/** Driven port: pull ONE decoded frame out of a stream's durable recording. Optional.empty() is
 *  honest absence — no recording configured, recording disabled on the media server, or nothing
 *  recorded at that instant — never an error, exactly StreamPublisherPort#playbackUrl's own posture.
 *  Implementations MUST stamp the returned VideoFrame's capturedAt with the requested `at` and its
 *  sequence with 0, so callers never have to reconcile two notions of "when". */
public interface ReplayFrameExtractionPort {
    Optional<VideoFrame> frameAt(StreamId streamId, Instant at);
}
```

**Deleted**: `DatasetExportPort`, `DatasetExport`, and the enum constant `DatasetStatus.EXPORTING`
(its javadoc names `DatasetExportPort.write`; **no code path ever set it** — the only references are
a persistence round-trip test and DTO javadoc, so no persisted row can carry it).

### 4. Application delta (`vision-application`)

```java
public interface LabelingService {
    TrainingSample capture(CaptureSpec spec, UserId actor, VisibilityScope scope);              // unchanged
    TrainingSample captureFromReplay(ReplayCaptureSpec spec, UserId actor, VisibilityScope scope); // NEW
    List<TrainingSample> samples(...);                                                          // unchanged
    SampleImage image(...);                                                                     // unchanged
    TrainingSample label(...);                                                                  // unchanged

    /** REPLACES `DatasetExport export(...)`. Composes every LABELED sample into the frozen §5 YOLO
     *  content and ships it via DatasetUploadPort. Not REST-exposed — the training kickoff is its
     *  only caller. Same scope gate + audit shape `export` had. */
    DatasetUpload uploadForTraining(DatasetId id, UserId actor, VisibilityScope scope);
}

/** captureFromReplay's command record. `atSeconds` is an offset from the usage's own startedAt —
 *  the same anchor `ReplayService#recordingFor` uses for the clip window and `replay-logic.ts`'s
 *  `videoOffsetSeconds` already produces client-side. Must be finite and >= 0. */
public record ReplayCaptureSpec(UsageId usageId, DatasetId datasetId, double atSeconds) { }

/** The three replay-sourced collaborators, bundled — same java-clean-code §3 reasoning
 *  TrainingStores' own javadoc gives (TrainingStores.java:10-24): each is a genuine,
 *  independently-substitutable port, but listing all three individually would push
 *  DefaultLabelingService's constructor past the five-parameter ceiling. */
public record ReplaySources(AssetUsageRepositoryPort usages, DetectionRepositoryPort detections,
                            ReplayFrameExtractionPort frames) { }
```

- `TrainingStores`'s fourth component changes type only: `DatasetExportPort exports` →
  `DatasetUploadPort uploads`.
- `DefaultLabelingService` constructor: `(TrainingStores stores, ReplaySources replay,
  StreamService streamService, AssetRepositoryPort assetRepository, AuditTrailPort auditTrail)`
  (+ the existing package-private clock seam).
- `YoloDatasetWriter` (pure, package-private) keeps `toEntry` — only its return type changes to
  `DatasetUploadPort.ExportEntry` — and **gains** `static String dataYaml(List<String> classes)`,
  moved verbatim from `FilesystemDatasetExport.java:95-100`. All §5 format composition is now in one
  pure, unit-tested place; the adapter frames bytes and nothing else.

**`captureFromReplay`, frozen algorithm** (audit action string `"CAPTURE_REPLAY"`):

1. Resolve + scope-gate the dataset exactly as `capture` does (`DefaultLabelingService.java:101-102`).
2. `replay.usages().findById(usageId)` → `NoSuchElementException` (404) if unknown.
3. `usage.streamId() == null` → `NoSuchElementException("Usage <id> has no recorded video stream")`
   (404). This is the same condition that makes `recordingFor` return empty
   (`DefaultReplayService.java:149-151`), *and* `TrainingSample` requires a non-null `streamId`
   (`TrainingSample.java:48-50`) — one check covers both.
4. Asset gate: `assetRepository.findById(usage.assetId())` + `scope.includes(asset)`, with the same
   `DENIED` audit + `AccessDeniedException` as `capture` (lines 104-110). Unlike live capture,
   `usage.assetId()` is **never null** (`AssetUsage.java:42-44`), so this gate always applies —
   strictly tighter than the live path, not looser.
5. `Instant at = usage.startedAt().plusMillis(Math.round(atSeconds * 1000))`. If `at` is after
   `usage.endedAt()` (or `Instant.now()` for a still-open usage) → `IllegalArgumentException` (400).
6. `replay.frames().frameAt(usage.streamId(), at)` → empty ⇒ `NoSuchElementException("No recorded
   frame at <at> for stream <id>")` (404).
7. Suggested annotations, **server-side** (see §F):
   `replay.detections().query(new DetectionQuery(streamId, at.minus(NEAREST_DETECTION_TOLERANCE),
   at.plus(NEAREST_DETECTION_TOLERANCE), null, NEAREST_DETECTION_FETCH_LIMIT))`, pick the
   `DetectionResult` whose `capturedAt` is nearest `at` (ties → earlier, deterministic), map its
   `Detection`s to `Annotation(label, box, AnnotationSource.MODEL)`. Nothing in the window ⇒ an empty
   annotation list (an honest "the model saw nothing here", never a fabricated box).
   `NEAREST_DETECTION_TOLERANCE = Duration.ofSeconds(2)` — the same threshold the replay UI already
   applies as "near enough to be *now*" (`replay-logic.ts:90`, `DETECTION_MATCH_TOLERANCE_MS`).
   `NEAREST_DETECTION_FETCH_LIMIT = 200`.
8. Build the `TrainingSample` exactly as `capture` does (`DefaultLabelingService.java:120-125`) —
   `capturedAt = frame.capturedAt()` (which the port guarantees equals `at`), `status = PENDING`,
   `labeledBy`/`labeledAt` null — and store `TrainingFrameEncoder.encode(frame)` as `image/jpeg`.

**`DefaultTrainingJobService`** — constructor becomes `(TrainingPort trainingPort, LabelingService
labelingService, AuditTrailPort auditTrail)` (+ the existing executor/clock test seam).

- `start(spec, actor, scope)`, after today's `canManageOrg` gate + audit (lines 139-146):
  parse `DatasetId datasetId = DatasetId.of(spec.datasetId())` (400 on malformed), then a **cheap
  synchronous pre-check** — `labelingService.samples(datasetId, LABELED, 1, actor, scope)` — so an
  unknown dataset (404), an out-of-scope one (403) and an empty one (`IllegalArgumentException`
  *"has no LABELED samples to train on"*, 400) are real HTTP statuses on the request thread, not a
  job that fails 200ms later. This makes the web's existing `sampleCounts.LABELED > 0` gate
  (`dataset-detail-logic.ts:45-47`) server-authoritative rather than decorative. Then register the
  job + submit, exactly as today.
- `runJob` (lines 164-170) becomes upload-then-train, inside the **existing**
  `catch (RuntimeException e) { recordFailure(jobId, e); }`:
  ```
  note(jobId, "Uploading dataset…");
  DatasetUpload upload = labelingService.uploadForTraining(datasetId, actor, scope);
  note(jobId, "Uploaded " + upload.sampleCount() + " sample(s), " + upload.sizeBytes()
              + " bytes; starting training…");
  trainingPort.startTraining(spec, progress -> updateJob(jobId, progress));
  ```
  `note` replaces only the tracked view's `message`, using `updateJob`'s own
  replace-never-mutate idiom (lines 172-179). **No new `JobState`** — the job is `RUNNING` from the
  moment `start` returns; `epoch`/`totalEpochs` stay `0` until the first epoch arrives, which is
  already what a just-started job reports (line 145).

### 5. REST delta (`vision-api`) — all under `vision.training.enabled`

**Removed** (routes, handlers, and the DTO):

```
POST   /api/datasets/{id}/export             (was 202 DatasetExportResponse)
GET    /api/datasets/{id}/export/{exportId}  (was 200 application/zip)
```

`LabelingController`'s constructor drops to `(LabelingService, CurrentUser)` — `DatasetService` and
`DatasetExportPort` were injected *only* for `downloadExport`'s scope check and zip resolution, as
that class's own javadoc states (`LabelingController.java:51-57`).

**Added** (on `LabelingController`, beside `POST /api/streams/{streamId}/samples`):

```
POST   /api/usages/{usageId}/samples   -> 201 SampleResponse
```

```jsonc
// request  (CaptureFromReplayRequest)
{ "datasetId": "<uuid>", "atSeconds": 412.5 }

// 201 response — the EXISTING SampleResponse (CV-TRAINING-PLAN.md §3), unchanged:
{ "id": "<uuid>", "datasetId": "<uuid>", "streamId": "<uuid>", "assetId": "<uuid>",
  "capturedAt": "2026-07-30T14:21:52.500Z", "width": 1920, "height": 1080, "status": "PENDING",
  "labeledBy": null, "labeledAt": null,
  "annotations": [ { "label": "building", "source": "MODEL",
                     "box": { "x": 0.10, "y": 0.20, "width": 0.30, "height": 0.25 } } ] }
```

- `atSeconds` is a JSON number, seconds from the usage's `startedAt`, `>= 0`, finite.
- `400` malformed uuid; missing/negative/non-finite `atSeconds`; `atSeconds` past the usage window.
- `403` dataset, or the usage's asset, outside scope.
- `404` unknown usage / usage with no recorded stream / unknown dataset / no recorded frame there.
- `annotations` may be `[]` — an honest "nothing was detected near that instant".

**Changed** — `POST /api/datasets/{id}/train` (`TrainingJobController.java:69-76`): body
(`{baseModel, epochs}`) and `202 TrainingJobResponse` shape are **unchanged**. The handler now
parses `DatasetId.of(id)` at the edge (400 on a malformed id) and threads
`datasetId.value().toString()` into `TrainingJobSpec` — the spec's plain-`String` wire shape
(`TrainingJobSpec.java:7-9`) is deliberately kept. New failure modes: `404` unknown dataset, `403`
dataset out of scope, `400` no `LABELED` samples. `TrainingJobResponse#message` now carries the
upload phase text; no shape change, so `features/training-jobs/**` needs no update.

### 6. Adapter contracts

**`GrpcDatasetUploadPort implements DatasetUploadPort`** (`adapters/adapter-cv-grpc`), built on the
**shared** `cvGrpcChannel` like its two siblings:

- **Async stub, not blocking** — grpc-java's blocking stubs support unary and server-streaming only;
  a client-streaming RPC needs `TrainingGrpc.newStub(channel)` + a `StreamObserver<DatasetChunk>`
  and a `CountDownLatch(1)` awaited by the calling thread. (`GrpcTrainingPort` uses
  `newBlockingStub` at line 103 because `StartTraining` is server-streaming — do not copy that here.)
- **Deadline**: `UPLOAD_TIMEOUT_SECONDS = 300`, via `withDeadlineAfter` — bounded work, unlike
  `StartTraining`, which deliberately arms no deadline (`GrpcTrainingPort.java:29-40`). Constant
  idiom mirrors `GrpcModelRegistryPort.CALL_TIMEOUT_SECONDS` (line 93).
- **Framing**: a `ZipOutputStream` over a small chunking `OutputStream` that emits a `DatasetChunk`
  every `CHUNK_BYTES = 262_144` (256 KiB) and on close — the zip is *never* fully materialized in
  memory or on disk. Entries, in order: `data.yaml` (the caller's `dataYaml` string, UTF-8), then
  per entry `images/<imageName>` and `labels/<stem>.txt` — the exact naming
  `FilesystemDatasetExport.java:76-106` produced.
- **Failure**: `ok=false` → `IllegalStateException("cv-service rejected the dataset upload: " +
  message)`; transport failure/deadline → the raw `StatusRuntimeException` propagates. Both land in
  `DefaultTrainingJobService#recordFailure` as an honest `FAILED` job message.
- Returns `new DatasetUpload(datasetId, Instant.now(), entries.size(), ack.getBytesReceived())`.

**`MediamtxReplayFrameExtractor implements ReplayFrameExtractionPort`**
(`adapters/adapter-publish-hls` — the module that already owns the playback-URL concept *and*
already depends on `javacv` + `ffmpeg-platform-gpl`, `pom.xml:22-27`; zero new dependencies, zero
new config source):

- **Seek is delegated to mediamtx, not to ffmpeg.** Request a *one-second window starting at the
  wanted instant* — `<playbackBase>/get?path=<streamId>&start=<ISO-8601>&duration=1` — so the
  returned MP4 already begins where we want and only ~1s of video is ever decoded. No whole-file
  open, no `setTimestamp` seek across a long recording.
- URL formatting moves to a new package-private `MediamtxPlaybackUrls` helper shared with
  `MediamtxStreamPublisher#playbackUrl` (`MediamtxStreamPublisher.java:389-398`, `PLAYBACK_GET_PATH`
  at line 161) — one place knows mediamtx's query shape.
- Decode: `new FFmpegFrameGrabber(url)`, `setFormat("mp4")`,
  `setPixelFormat(avutil.AV_PIX_FMT_BGR24)`, `start()`, `grabImage()` — `grabImage`, not `grab`,
  for the reason `FfmpegVideoSource.java:766-772` already documents. Then the module's own
  `FrameConverter` gains a `copyBgr24(Frame)` mirroring `adapter-rtsp`'s
  (`adapter-rtsp/.../FrameConverter.java:41-78`) — a deliberate, acknowledged duplicate, since
  adapters must never depend on each other (CLAUDE.md), the same trade-off `TrainingFrameEncoder`'s
  javadoc (lines 26-32) already documents one level up. Returns
  `new VideoFrame(streamId, 0, at, w, h, PixelFormat.BGR24, copy)`.
- **Read timeout**: default `rw_timeout` of 15s via `setOption` (implementer may tune the exact
  option name for the JavaCV/FFmpeg build; 15s is the pinned default).
- **Resilience**: nothing thrown by JavaCV/FFmpeg escapes — one `WARNING` naming the URL and the
  cause, then `Optional.empty()`, matching `MediamtxStreamPublisher`'s own "never let FFmpeg take
  down the caller" posture (class javadoc, "Resilience"). The grabber is always released.
- **Threading**: stateless, freely concurrent, one grabber per call — a separate class from
  `MediamtxStreamPublisher` precisely so that class's "calls for a single streamId are not
  concurrent" publish contract stays untouched.

### 7. Wiring delta (`vision-app`)

- `VisionPublishProperties.Mediamtx` gains `@DefaultValue("http://localhost:19996") URI playbackBase`
  — the value the 3-arg overload's heuristic already derives for the default `whepBase`
  (`http://localhost:8889` + `DEFAULT_PLAYBACK_PORT = 19996`,
  `MediamtxStreamPublisher.java:170`), so the default deployment is byte-identical.
  `WiringConfiguration#streamPublisherPort` switches to the **4-arg** constructor
  (`MediamtxStreamPublisher.java:193`) passing it, closing the follow-up that module's MODULE.md
  already names.
- New bean `ReplayFrameExtractionPort replayFrameExtractionPort(VisionPublishProperties)` —
  `MediamtxReplayFrameExtractor(mediamtx.playbackBase())` when `properties.enabled()`, else a
  `NoopReplayFrameExtractor` in `vision-app` `devsupport` returning `Optional.empty()`. Same if/else
  shape as `streamPublisherPort` (`WiringConfiguration.java:362-369`).
- `TrainingWiringConfiguration`: **delete** the `datasetExportPort` bean (lines 89-93); **add**
  `DatasetUploadPort datasetUploadPort(ManagedChannel cvGrpcChannel)` gated by
  `vision.training.enabled` (mirroring `trainingPort`, lines 173-177) and `ReplaySources
  replaySources(...)`; `trainingStores`/`labelingService`/`trainingJobService` take the new types.
- `VisionTrainingProperties` drops `exportDir` + `DEFAULT_EXPORT_DIR`, becoming
  `record VisionTrainingProperties(@DefaultValue("false") boolean enabled)`.

### 8. Web delta (`vision-web`)

`core/api/models.ts`: **delete** `interface DatasetExport` (line 1418); `DatasetStatus` becomes
`'OPEN' | 'ARCHIVED'` (line 1368).

`core/api/vision-api.ts`: **delete** `exportDataset` (lines 806-808); **add**

```ts
/** Captures a frame from a finished usage's recording at `atSeconds` past its start, with the
 *  nearest stored detections pre-filled as MODEL annotations. 404 = unknown usage / no recorded
 *  stream / nothing recorded at that instant; 403 = dataset or asset out of scope. */
captureReplaySample(usageId: string, datasetId: string, atSeconds: number): Promise<TrainingSample>
//   POST /api/usages/{usageId}/samples   body { datasetId, atSeconds }
```

`features/labeling`: delete the whole **Export** card (`dataset-detail.html:101-127`), the
`exporting`/`lastExport`/`canExport` signals and `exportDataset()`
(`dataset-detail-facade.ts:73-76, 115, 159-173`), `canExportDataset` + `formatBytes`
(`dataset-detail-logic.ts:22-26, 61-75`) and their spec blocks
(`dataset-detail-logic.spec.ts:22-32, 62-71`), and the `formatBytes` import/field
(`dataset-detail.ts:8, 35`). `canStartTrainingDataset` **keeps its existing behavior** but stops
delegating to the deleted predicate — it reads `(dataset?.sampleCounts.LABELED ?? 0) > 0` directly
(`dataset-detail-logic.ts:45-47`). The "Train a model" card's copy changes to say it uploads
(`dataset-detail.html:134-137`).

`features/replay`: an **"Add to dataset"** control in the existing clip-actions row
(`replay.html:74-85`) — a dataset `<select>` + button, using `videoOffsetSeconds(facade.atMs(),
facade.recordingStartMs())` (`replay-logic.ts:244-247`) as `atSeconds`. Datasets come from the
app-wide `TrainingStore` (`providedIn: 'root'`); the whole control is **hidden** when
`TrainingStore.disabled` is true (the store already turns a 404 on `listDatasets()` into that
honest signal) or when `!facade.videoAvailable()`. `ReplayPage` adds `FormsModule` to its `imports`
(`replay.ts:33`) for the select. New facade state: `datasetId`/`capturing` signals +
`addToDataset()`; success toasts and links to the dataset, failure toasts `describeHttpError`.

A short, always-visible hint under the control:

> Replay frames come from the recorded stream. If detection overlay burn-in was on during the
> flight, those boxes are baked into these pixels — turn burn-in off on streams you plan to capture
> training frames from.

See §G for why this is a hint and not a silent problem.

---

## Design decisions (with rationale)

### A. Export was a step, not a feature — delete it, don't hide it
Nothing consumed `DatasetExport` except a human with rsync. `POST /api/datasets/{id}/train` never
referenced an export artifact at all (`TrainingJobController.java:69-76`) — the coupling between
"press Export" and "press Train" existed only in the operator's head and in a README. Keeping
`DatasetExportPort`/`FilesystemDatasetExport`/`DatasetExport` alive as an "internal staging step"
would preserve a disk write, a config property, a zip on the platform's filesystem, and four types
for a REST surface that no longer exists — dead weight, and exactly the "fake capability" this repo's
doctrine forbids. They go. What survives is the part that was always the real asset: the **pure**
`YoloDatasetWriter` and the frozen §5 byte layout, now composed in one place and framed onto a
socket instead of onto a disk.

### B. gRPC upload, not a shared filesystem and not rsync
A shared filesystem assumes co-location, which GB4005 breaks. An app-driven rsync means shelling
out, distributing SSH keys, and a second transport with its own failure modes and its own auth story
— for a payload the platform already has an authenticated, keepalive-tuned, single-connection
channel to the very same host for (`WiringConfiguration.java:469-478`). Upload rides that channel.
One connection, one auth story, one failure surface, and it works identically whether cv-service is
`localhost` or `192.168.0.106`.

### C. Streamed **zip**, not file-by-file — because it changes nothing on the host
The decisive argument is not compression (JPEGs don't compress); it is that after extraction
`<CV_DATASET_DIR>/<dataset_id>/` is **byte-identical to what a manual rsync produced**, so
`trainer.resolve_dataset_dir` (`trainer.py:112-144`) and `StartTraining` need **zero** changes and the
manual path keeps working as a fallback. A zip also gives all-or-nothing integrity for free (a
truncated stream fails at extraction, before anything replaces a good dataset) and reduces the
per-message attack surface to one well-known guard — zip-entry path validation — instead of
validating a caller-supplied relative path on every message. It matches the precedent
`FilesystemDatasetExport` already set, so the format is not being invented twice.

### D. No gRPC message-size tuning is needed — and that is the point of streaming
A realistic dataset is hundreds of full-resolution JPEGs, low tens of MB. Both defaults that matter
are **4 MiB inbound** (grpc-java's channel default, and grpcio's `grpc.max_receive_message_length`);
neither side configures any size option today (`WiringConfiguration.java:471-478`;
`server.py:100-105`, keepalive only). At `CHUNK_BYTES = 256 KiB` per `DatasetChunk` and a tiny
`UploadAck`, no message ever approaches 4 MiB in either direction, so **nothing is tuned** — the
same reason `Inference/DetectStream` already pushes full JPEG frames over this channel untouched.
Raising a message-size limit to send one giant message would be the wrong fix for a problem
streaming already solves.

### E. Upload runs on the training job's own executor, with a cheap synchronous pre-check
`DefaultTrainingJobService.start` already returns immediately and submits the long work to its own
cached daemon pool (lines 144-149); putting a tens-of-MB upload there costs nothing new and keeps
`POST /api/datasets/{id}/train`'s 202 instant. But "the dataset doesn't exist / you can't see it /
it has nothing labeled" deserve real status codes, not a job that fails a moment later — so `start`
does one bounded `samples(datasetId, LABELED, 1, …)` read first, which runs the exact same
visibility gate. Fast failures are HTTP; slow failures are a `FAILED` job with an honest message.
No new `JobState` is invented for "uploading": `JobState` is cv-service's own proto enum, and the
upload happens platform-side — the phase is reported in the existing `message` field instead.

### F. Replay's suggested boxes are looked up server-side, not sent by the client
The client *could* post its already-computed `nearestDetectionResult`, but that is **more** wire
surface, not less: the request would carry an annotation array, and the server would have to accept
client-authored boxes labelled `source: "MODEL"` — a provenance claim it cannot verify. Server-side
costs **zero new port surface**: `DetectionRepositoryPort#query` is already a real, time-bounded,
adapter-side query (`DefaultReplayService.java:168-176`), so the lookup is one `DetectionQuery` with
a ±2s window. It is also strictly *more accurate*: the replay timeline the client derives from is
downsampled to `maxPoints` (`DefaultReplayService#thin`), so a client-derived "nearest" can be a
detection that survived thinning rather than the actual nearest one. The ±2s tolerance is not a new
number — it is `DETECTION_MATCH_TOLERANCE_MS` (`replay-logic.ts:90`), the same "near enough to be
*now*" threshold the replay UI already shows detections under.

### G. Say out loud that a recorded frame may carry burn-in
mediamtx records the **published** stream, and what `StreamPublisherPort#publish` receives is the
post-overlay frame whenever `overlayBurnIn` is on — which is the default, and which
`StreamPipeline` itself documents as the exact reason `latestRawFrame` was added for live capture
(`StreamPipeline.java:223-231`). So a replay-extracted frame is *not* guaranteed to be the clean
pixels CV-TRAINING-PLAN §D fought for. Options were: pretend otherwise (dishonest), refuse replay
capture entirely (throws away the feature the operator asked for), or record a second overlay-free
path (large, and a media-server concern, not this one). The honest choice is the third door: ship
it, and tell the operator — `overlayBurnIn` is **already per-stream settable live** via `PATCH
/api/streams/{id}/config` (`StreamControllerTest.java:211-229`), so the hint in §8 is actionable
advice, not a shrug. A clean, overlay-free recorded path is a named deferred item below.

### H. `adapter-publish-hls` is the frame extractor's home, in its own class
That module already owns mediamtx's playback URL (`MediamtxStreamPublisher.java:389-398`) and
already carries `javacv` + `ffmpeg-platform-gpl` (`pom.xml:22-27`) — so this is zero new
dependencies and zero new configuration source. It is a **separate class** rather than another
method on `MediamtxStreamPublisher` because that class's contract is explicitly per-stream,
non-concurrent, stateful egress; a blocking, freely-concurrent fetch-and-decode called from request
threads would poison it. Two classes, one shared package-private URL helper.

### I. Guardrails: the flag stays, the defaults stay, and one behavior change is named
Every new route lives under the existing `vision.training.enabled` (default **false**), so with it
off the app behaves exactly as today. `vision.publish.mediamtx.playback-base`'s default reproduces
today's derived value exactly, so no existing test or default deployment changes. The one genuine
behavior change: a deployment whose `whepBase` is **not** localhost previously got a *guessed*
playback base at that host; it now gets the property's default unless it sets the property. That is
a strict improvement (a guess became explicit), but it is a change, and it is called out in the
wave that makes it and in `adapter-publish-hls`/`vision-app` MODULE.md.

---

## What gets deleted (explicit)

**Files removed**

| File | Module |
|---|---|
| `port/out/DatasetExportPort.java` + `DatasetExportPortTest.java` | vision-domain |
| `model/DatasetExport.java` + `DatasetExportTest.java` | vision-domain |
| `FilesystemDatasetExport.java` + `FilesystemDatasetExportTest.java` | adapter-persistence |
| `dto/DatasetExportResponse.java` | vision-api |

**Members removed**

- `DatasetStatus.EXPORTING` (enum constant) — nothing ever set it.
- `LabelingController#export`, `#downloadExport`, `#readBytes`, and the `DatasetService` /
  `DatasetExportPort` constructor parameters.
- `LabelingService#export` / `DefaultLabelingService#export` (**replaced** by `uploadForTraining`,
  not merely renamed — its sink and return type both change).
- `VisionTrainingProperties#exportDir`, `DEFAULT_EXPORT_DIR`, and any
  `vision.training.export-dir` config/docs reference.
- `TrainingWiringConfiguration#datasetExportPort`.
- Web: `models.ts` `DatasetExport`; `vision-api.ts` `exportDataset`; `dataset-detail-logic.ts`
  `canExportDataset` + `formatBytes`; the export card + facade state listed in §8.

**Explicitly kept**: `YoloDatasetWriter` (pure; gains `dataYaml`), the §5 YOLO layout byte-for-byte,
`TrainingStores` (one component's type changes), every capture/label/dataset REST route,
`StartTraining`/`ListModels`/`PromoteModel`, `trainer.py`, and the manual rsync fallback.

---

## Implementation waves (disjoint file scopes)

Each wave ends **independently green** with its own scoped build and its `MODULE.md` updated. The
frozen contract above is the sole coupling. **Never run a reactor-wide build while another wave is
red — scope with `-pl`** (CLAUDE.md).

Sequencing: **(W0 ‖ W1) → (W2 ‖ W3 ‖ W4 ‖ W5) → W6 → W7**

**W0 — proto + generated stubs** — `proto/vision/v1/cv.proto`, `cv-service/cv_service/gen/**`
(agent: **general-purpose**). No deps.
Add `DatasetChunk`/`UploadAck`/`rpc UploadDataset` (§1) — additive only, existing messages and RPCs
untouched. Regenerate the Python tree with `cv-service/scripts/gen_proto.sh` and commit it (it is
checked in). Verify `./mvnw -B -pl vision-proto test` green (Java codegen compiles) and
`python -c "import cv_service.server"` still works; `vision-proto/MODULE.md` + `cv-service/MODULE.md`
proto sections updated.

**W1 — domain: new ports + records, deleted export types** — `vision-domain/**`
(agent: **domain-modeler**). No deps.
Add `DatasetUploadPort` (+ nested `ExportEntry`), `DatasetUpload`, `ReplayFrameExtractionPort` (§3).
Delete `DatasetExportPort`, `DatasetExport`, their tests, and `DatasetStatus.EXPORTING`.
Framework-free; compact-constructor validation; `ExportEntry`'s three checks carried over verbatim.
Tests: `DatasetUpload` validation (non-null id/instant, non-negative counts), `ExportEntry` rejects
blank name / empty bytes / null label text. `./mvnw -B -pl vision-domain test` green
(vision-domain compiles alone, so the deletions do not make this wave red); MODULE.md updated.

**W2 — cv-service `UploadDataset`** — `cv-service/cv_service/server.py`, `cv-service/tests/**`
(agent: **general-purpose**). Depends W0. ‖ W3, W4, W5.
Implement §2 on `TrainingServicer`: temp-file buffering, zip-entry path validation, extract →
`trainer.resolve_dataset_dir` validate → atomic replace, size cap, `UploadAck` shapes, abort cases.
**Do not touch `trainer.py` or `StartTraining`.** Tests in `tests/test_training.py`'s existing style
(fake context, real tmp dirs, no ultralytics): happy path incl. overwrite-a-previous-upload;
mismatched/blank/unsafe `dataset_id` aborts; corrupt zip, traversal entry, and malformed tree all
return `ok:false`; zero chunks. `cv-service/scripts/test.sh` green; MODULE.md updated.

**W3 — adapter-cv-grpc: `GrpcDatasetUploadPort`** — `adapters/adapter-cv-grpc/**`
(agent: **adapter-builder**). Depends W0 + W1. ‖ W2, W4, W5.
§6's first half: async stub + latch, 300s deadline, `ZipOutputStream` over a 256 KiB chunking
`OutputStream`, entry naming, failure mapping. Tests: an in-process gRPC server capturing the
chunks, asserting the reassembled bytes unzip to `data.yaml` + `images/` + `labels/` with the exact
expected names; `ok:false` → `IllegalStateException`; a server error → `StatusRuntimeException`.
`./mvnw -B -pl adapters/adapter-cv-grpc test` green; MODULE.md updated.

**W4 — adapter-publish-hls: `MediamtxReplayFrameExtractor`** — `adapters/adapter-publish-hls/**`
(agent: **adapter-builder**). Depends W1. ‖ W2, W3, W5.
§6's second half + the shared `MediamtxPlaybackUrls` helper + `FrameConverter#copyBgr24`. Tests:
URL formatting (path/start/`duration=1`, trailing-slash tolerance, null base → empty) as pure unit
tests; `copyBgr24` stride handling mirroring adapter-rtsp's own test; an unreachable URL yields
`Optional.empty()` and logs rather than throwing. A real end-to-end grab belongs in the existing
docker-gated `MediamtxDockerIntegrationTest` style (skips without docker), not the unit suite.
`./mvnw -B -pl adapters/adapter-publish-hls test` green; MODULE.md updated (including the playback-base
follow-up now being closed).

**W5 — application: fold export into train, add replay capture** — `vision-application/**`
(agent: **application-service**). Depends W1. ‖ W2, W3, W4.
§4 in full: `ReplayCaptureSpec`, `ReplaySources`, `LabelingService#captureFromReplay`,
`export` → `uploadForTraining`, `TrainingStores`'s changed component, `YoloDatasetWriter#dataYaml`
(moved verbatim), `DefaultTrainingJobService`'s new collaborator + pre-check + upload-then-train
`runJob`. Tests with hand-fakes for every port: replay capture builds a PENDING sample with MODEL
annotations from the nearest in-window detection; empty window → empty annotations; unknown usage /
null `streamId` / no frame → `NoSuchElementException`; `atSeconds` past the window → 400-shaped
`IllegalArgumentException`; out-of-scope asset → `AccessDeniedException` + `DENIED` audit;
`uploadForTraining` sends only LABELED samples and the right `data.yaml`; `start` 400s an
all-`PENDING` dataset before submitting; an upload failure inside `runJob` lands as a `FAILED` job.
`./mvnw -B -pl vision-application test` green; MODULE.md updated.

**W6 — REST + wiring + persistence cleanup** — `vision-api/**`, `vision-app/**`,
`adapters/adapter-persistence/**` (agent: **spring-integrator**). Depends W3 + W4 + W5.
§5 + §7: delete the two export routes, `DatasetExportResponse`, `FilesystemDatasetExport` (+ test),
`VisionTrainingProperties#exportDir`, the `datasetExportPort` bean; add
`CaptureFromReplayRequest` + `POST /api/usages/{usageId}/samples`; parse `DatasetId` in
`TrainingJobController#start`; add `playback-base`, the 4-arg publisher constructor, the
`ReplayFrameExtractionPort` bean + `NoopReplayFrameExtractor` devsupport, `datasetUploadPort`,
`replaySources`. Update `PostgresDockerIntegrationTest`'s `DatasetStatus.EXPORTING` usage. Tests:
MockMvc for the new endpoint (201/400/403/404) and for the removed routes now 404-ing; wiring smoke
tests with the flag on and off. `./mvnw -B -pl vision-api test` + `-pl vision-app test` +
`-pl adapters/adapter-persistence test` green; all three MODULE.md updated.

**W7 — web: drop Export, add replay "Add to dataset"** — `vision-web/**` (agent: **web-ui**).
Depends W6.
§8 in full. Tests: `dataset-detail-logic.spec.ts` loses its export blocks and keeps
`canStartTrainingDataset` covered directly; `replay-logic.spec.ts` gains a `videoOffsetSeconds`
boundary case (scrub before the recording start clamps to 0). `tsc` clean;
`npm run test:ci` + production build green; MODULE.md updated.

---

## Non-goals / deferred (named, not dropped)

- **A clean, overlay-free recorded path** for training-grade replay frames (§G). Deferred; the
  mitigation today is per-stream `overlayBurnIn: false`, which already exists.
- **Resumable / chunk-acknowledged upload.** One `UploadDataset` call is all-or-nothing; a dropped
  connection means re-uploading. Fine at tens of MB; revisit if datasets reach the hundreds.
- **Incremental / delta upload.** Every train re-uploads the whole labeled set. Simple and always
  correct; a content-hash skip is a later optimization the `UploadAck` already has room to report.
- **A dataset-inventory RPC** ("what's on the host?"). Not needed — upload is idempotent, so the
  platform never has to ask.
- **Replay capture from a live/still-open usage.** `atSeconds` resolves against a finished
  recording; the replay page already refuses an open usage (`replay.html:13-24`), and live capture
  already covers that case.
- **Batch replay capture** ("grab every 5s across this clip"). One operator gesture, one frame — the
  same operator-tap principle CV-TRAINING-PLAN §B set.
- **Capture from `features/fly`/`features/live`.** Live capture keeps its existing home in the
  dataset detail page's stream picker; this delta adds replay only.
- **Upload progress in the UI.** The job's `message` reports the phase; a byte-level progress bar is
  not worth a second polling surface.
- **Auto-train on upload**, and **auto-promote on success** — both stay deliberate operator actions,
  unchanged from CV-TRAINING-PLAN.

## Open questions / to confirm before delegating

1. **`POST /api/usages/{usageId}/samples` on `LabelingController`, not `UsageTimelineController`** —
   it is a training action behind `vision.training.enabled`, and `UsageTimelineController` is
   ungated. Confirm the `/api/usages/...` path living in the labeling controller reads fine.
2. **Deleting `DatasetStatus.EXPORTING`** is safe (nothing ever wrote it) but touches three modules.
   Confirm, or say the word and it stays as a harmless unused constant.
3. **The playback-base default change** (§I) is invisible for a localhost `whepBase` and a strict
   improvement otherwise — but any non-localhost deployment must now set
   `vision.publish.mediamtx.playback-base` explicitly. Confirm that is acceptable rather than
   keeping the guess as a fallback.
4. **300s upload deadline / 2 GiB server cap** — pinned defaults, not researched against a real
   dataset. Confirm, or supply a realistic worst-case dataset size to size them against.
