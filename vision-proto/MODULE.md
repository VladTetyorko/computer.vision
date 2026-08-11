# vision-proto

Java gRPC codegen (grpc-java) from the shared `proto/vision/v1/cv.proto` contract — the Java↔Python CV service wire format.

**Depends on:** io.grpc:grpc-{netty-shaded,protobuf,stub}, com.google.protobuf:protobuf-java, javax.annotation-api, org.junit.jupiter:junit-jupiter (test) · **Used by:** adapter-cv-grpc
**Build/test:** `./mvnw -B -pl vision-proto test` (codegen + compile, plus one hand-written wire-additivity test class — see below)

## API surface
No hand-written production Java — everything under `com.drones.vision.proto.v1` is generated into `target/generated-sources/protobuf/{java,grpc-java}` from `proto/vision/v1/cv.proto` (`java_multiple_files = true`, `java_package = "com.drones.vision.proto.v1"`):
- Messages: `FrameRequest`, `BoundingBox`, `Detection`, `DetectionResponse`, `TrainingJobSpec`, `TrainingProgress`, `ModelInfo`, `ModelList`, `ModelRefMsg`, `Ack`, `DatasetChunk`, `UploadAck`, **`TargetLock`, `TrackingConfig`** (each with a generated `*OrBuilder` and `.Builder`).
- Enums: `ImageEncoding` (`UNSPECIFIED`/`JPEG`/`BGR24`), `JobState` (`UNSPECIFIED`/`RUNNING`/`SUCCEEDED`/`FAILED`), **`TrackingMode`** (`UNSPECIFIED`/`OFF`/`ASSOCIATE`/`FOLLOW`), **`TrackState`** (`UNSPECIFIED`/`TENTATIVE`/`CONFIRMED`/`COASTING`/`LOST`), **`DetectionSource`** (`UNSPECIFIED`/`DETECTOR`/`TRACKER`), **`DetectorReason`** (`UNSPECIFIED`/`ALWAYS`/`CADENCE`/`TRACKER_FAILED`/`NO_LOCK`/`BOX_INVALID`/`COASTED_OUT`).
- gRPC stubs: `InferenceGrpc` and `TrainingGrpc`, each with `*ImplBase`, `*Stub`, `*BlockingStub`, `*FutureStub`, and an `*.AsyncService` interface (grpc-java 1.64's async-service pattern).
- Proto services: `Inference.DetectStream(stream FrameRequest) returns (stream DetectionResponse)`; `Training.StartTraining(TrainingJobSpec) returns (stream TrainingProgress)`, `Training.UploadDataset(stream DatasetChunk) returns (UploadAck)`, `Training.ListModels(google.protobuf.Empty) returns (ModelList)`, `Training.PromoteModel(ModelRefMsg) returns (Ack)`.
- `DatasetChunk{dataset_id, content}` — one slice of a client-streamed YOLO dataset archive (`docs/CV-TRAINING-V2-PLAN.md` §1); the concatenation of every chunk's `content`, in stream order, is a ZIP carrying the `docs/CV-TRAINING-PLAN.md` §5 layout (`data.yaml`, `images/<name>`, `labels/<stem>.txt`). `dataset_id` is set on every chunk and must not change mid-stream. `UploadAck{ok, message, dataset_id, bytes_received, file_count}` is the single response once the client half-closes. `UploadDataset` is **additive** — `StartTraining`/`ListModels`/`PromoteModel` and every other message/RPC are unchanged (CV-TRAINING-V2 Wave W0; the Java client (`adapter-cv-grpc` W3) and Python servicer (`cv-service` W2) land in later, disjoint waves — this module only carries the generated shapes).

### Tracking (TRACKING-PLAN wave T0) — additive wire contract for the detect-then-track duty cycle

`docs/TRACKING-PLAN.md` §4.A, applied verbatim. All additive — no existing field number, name or type changed.

- **`FrameRequest.tracking = 11`** (`TrackingConfig`, NEW) — declarative per-stream tracking desired-state, restated on **every** `FrameRequest` (never a one-shot control message — see `cv-service/MODULE.md`'s `LatestOnlyMailbox` drop behavior). Absent → `TRACKING_MODE_OFF`, byte-identical to pre-tracking behavior.
- **`Detection` fields 4–9** (NEW): `track_id` (int64), `track_state` (`TrackState`), `source` (`DetectionSource`), `velocity_x`/`velocity_y` (float, normalized frame-widths/heights per second), `track_age_frames` (int32).
- **`DetectionResponse` fields 8–12** (NEW): `tracker_millis` (int64), `detector_ran` (bool), `tracker_engine_id` (string), `locked_track_id` (int64), `detector_reason` (`DetectorReason`).
- **`TargetLock`** (NEW message) — which object `FOLLOW` mode should hold, keyed by a monotonic per-stream `lock_seq` (cv-service applies a lock only when strictly greater than the last applied — makes restating idempotent). One of `track_id` / `point_x,point_y` / `release`.
- **`TrackingConfig`** (NEW message) — `mode`, `engine_id` (`""` = server default), `verify_every_millis`, `redetect_iou_threshold`, `max_age_frames`, `min_hits` (each `<=0` = server default), `lock`.

**Sentinels, spelled out once:**
- **`Detection.track_id == 0` means "untracked"** and must never reach the Java domain as a real track. cv-service allocates ids starting at **1** per stream.
- **`DetectionResponse.detector_reason` is `DETECTOR_REASON_UNSPECIFIED`** whenever no detector pass ran on that frame (i.e. whenever `detector_ran == false`) — it is the *why* beside `detector_ran`'s *whether* (docs/TRACKING-ORCHESTRATION.md §5.1).
- Every new enum's `0` value is its own `*_UNSPECIFIED` — old client / old server / "tracking never touched this message" all read the same way: the zero value.

## Conventions
- `protoSourceRoot` points at `../proto` (repo-root `proto/vision/v1/cv.proto`) — **not** a copy under this module; it is the single source of truth shared with `cv-service`'s Python codegen (`scripts/gen_proto.sh`). Never duplicate or fork the `.proto` file.
- Codegen via `org.xolstice.maven.plugins:protobuf-maven-plugin` (`${protobuf-maven-plugin.version}`=0.6.1), goals `compile` + `compile-custom` (the latter drives the `grpc-java` plugin, `pluginId=grpc-java`). `os-maven-plugin` (`${os-maven-plugin.version}`=1.7.1) resolves `${os.detected.classifier}` to pick the right native `protoc`/`protoc-gen-grpc-java` executables.
- Version pins (root `pom.xml` properties): `protobuf.version`=3.25.5 (both `protoc` and `protobuf-java`), `grpc.version`=1.64.0.
- `src/test/java/com/drones/vision/proto/v1/TrackingProtoAdditivityTest.java` is the **one** hand-written source in this module (added T0). It exists specifically to make proto3's additive guarantee a checked fact rather than a claim: each "expected" byte payload is built field-by-field with the raw protobuf wire format (`CodedOutputStream`, tag = `(fieldNumber << 3) | wireType`) covering only the fields that existed before this wave, independent of the generated classes' own serialization — so the test would catch e.g. a message field accidentally defaulting to a non-empty instance. Covers `FrameRequest` (no `tracking`), `Detection` (no track fields), and `DetectionResponse` (no tracking telemetry), plus one round-trip sanity test that the new fields decode correctly once populated.

## Gotchas
- Regenerate by rebuilding this module (any goal that runs `generate-sources`, e.g. `./mvnw -pl vision-proto compile`) — nothing under `target/generated-sources` is hand-maintained; never edit generated files directly.
- Java's protobuf runtime stays on the `3.25.x` version line while Python's (`cv-service/pyproject.toml`) pins `protobuf>=5.26.1,<6` — different-looking major versions for the **same** underlying release generation (protobuf unified its cross-language version numbering; Java/C++ kept the legacy `3.25.x` line for that generation while Python jumped to `5.26.x`). Not a real skew, and not something to "fix" — both consume the same `.proto` and produce the same wire format.
- This module previously had **no** test dependency declared (`no hand-written sources or tests` was literal). T0 added `org.junit.jupiter:junit-jupiter` (test scope) to `pom.xml` — version comes from the `spring-boot-starter-parent` BOM, same as every other module, so no explicit version pin was needed.
- `cv-service/scripts/gen_proto.sh`'s output (`cv-service/cv_service/gen/`) is gitignored and regenerated on demand from the same `proto/vision/v1/cv.proto` — confirmed re-running clean after the T0 diff (`vision/v1/cv_pb2.pyi` picks up `TrackingMode`, `DetectorReason`, etc.); never commit it, never hand-edit it.

## Status
Fully implemented (Task T3) — codegen wiring, plus one hand-written additivity-guard test class (T0). CV-TRAINING-V2 Wave W0 added `Training.UploadDataset` + `DatasetChunk`/`UploadAck` to `cv.proto`, additively. **TRACKING-PLAN wave T0** (docs/TRACKING-PLAN.md §4.A) added the tracking wire contract described above, also purely additively (`git diff` on `.proto` is insertion plus three widened-but-unchanged messages' field lists — zero existing field numbers/names/types touched). `./mvnw -B -pl vision-proto test` regenerates, compiles, and runs `TrackingProtoAdditivityTest` (4 tests) green; `cv-service/scripts/gen_proto.sh` regenerates the Python side clean. No hand-written *production* code lives in this module — the codegen output is the entire API surface, and the one hand-written test class exists purely to pin the additivity guarantee other modules' waves (T1–T8) depend on.
