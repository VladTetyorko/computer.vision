# Phase 0 Implementation Plan — Skeleton, Domain, Contracts

Companion to [ARCHITECTURE.md](../ARCHITECTURE.md). This file is the **authoritative spec** for Phase 0 code generation. Every task below is executed by a dedicated agent; file scopes are disjoint per task.

---

## 0. Scalability decisions (bind the domain design)

These decisions are made **before** any model is written and must be reflected in the code:

1. **Streams are the unit of horizontal scale.** A stream pipeline is pinned to one JVM instance; scaling out = distributing streams across instances. Therefore: no static/global mutable state anywhere; all pipeline state lives in per-stream objects; shared state (devices, detections, models) is only reachable through repository/registry **ports** so it can be externalized later without touching core.
2. **Typed identities.** `DeviceId`, `StreamId` are value records (not bare `String`) — they are future sharding/partition keys and prevent id mix-ups at compile time.
3. **Backpressure is part of the port contract.** Video sources expose `java.util.concurrent.Flow.Publisher<VideoFrame>` (JDK Flow — no reactive framework dependency in domain). Slow consumers request less; the *source adapter* decides drop policy for live feeds (latest-wins). Inference is asynchronous (`CompletionStage`) with a bounded in-flight count enforced by the application layer — a slow CV service can never stall the video path.
4. **Frames are immutable metadata + zero-copy-friendly payload.** `VideoFrame` carries a **read-only `ByteBuffer`** so adapters may use direct/pooled buffers at high FPS without GC pressure; consumers never mutate frame data. Frames carry a per-stream monotonic `sequence` and capture `Instant` — detections reference `(streamId, sequence, capturedAt)` instead of holding frames, so results can be persisted/replayed and frames can be released early.
5. **Detection is decoupled from rendering.** CV runs on sampled frames; the overlay interpolates from the *latest known* detections. This is what lets inference FPS scale independently of video FPS.
6. **Events are the integration seam.** Everything notable emits a domain `Event` through `EventPublisherPort`. Local now (in-process), later swappable to MQTT/Kafka for multi-instance — no core change.
7. **Models are versioned references.** `ModelRef(id, version)` is immutable; hot-swap = pipeline config update, rollback = version switch.
8. **Framework-free core.** `vision-domain` depends on `java.base` only. `vision-application` depends only on `vision-domain` + JDK. No Spring annotations outside `vision-app`/adapters — wiring happens in `vision-app` `@Configuration` classes. Enforced by ArchUnit.

---

## 1. Module & file layout produced in Phase 0

```
vision/                          parent pom (packaging=pom)
├── proto/vision/v1/cv.proto     shared Java↔Python contract (single source of truth)
├── vision-domain/               models + ports (no deps)
├── vision-application/          use-case services + StreamPipeline (deps: domain)
├── vision-proto/                Java codegen from /proto (grpc-java)
├── adapters/                    aggregator pom
│   ├── adapter-simulation/      synthetic video source — makes everything testable without hardware
│   ├── adapter-rtsp/            placeholder (Phase 1)
│   ├── adapter-publish-hls/     placeholder (Phase 1)
│   ├── adapter-cv-grpc/         placeholder (Phase 2; deps: domain, vision-proto)
│   ├── adapter-overlay/         placeholder (Phase 2)
│   └── adapter-persistence/     placeholder (Phase 2)
├── vision-api/                  placeholder (Phase 1; REST/WebSocket driving adapter)
├── vision-app/                  Spring Boot assembly + wiring + ArchUnit tests
├── cv-service/                  Python skeleton (gRPC server stub, pyproject, Dockerfile)
├── docker-compose.yml
└── .github/workflows/ci.yml
```

Remaining adapters from ARCHITECTURE.md §2 (mjpeg, usb, webrtc, udp-raw, mavlink, onvif, recording, notify) are added in their phases — no empty-module clutter now (KISS).

### Version pins (parent pom `<properties>`)

| Property | Value |
|---|---|
| java.version | 21 |
| grpc.version | 1.64.0 |
| protobuf.version | 3.25.5 |
| archunit.version | 1.3.0 |
| protobuf-maven-plugin (xolstice) | 0.6.1 |
| os-maven-plugin | 1.7.1 |
| javax.annotation-api | 1.3.2 (grpc generated code) |

Internal module deps use `${project.version}`. Spring Boot 4.1.0 stays the parent of the root pom.

---

## 2. Task T1 — Maven multi-module skeleton

**Scope:** all `pom.xml` files; move existing `src/` into `vision-app/`.

- Root pom: `packaging=pom`, keep `spring-boot-starter-parent` 4.1.0 parent, declare `<modules>`, move version pins into `<properties>`, add `<dependencyManagement>` entries for all internal modules via `${project.version}`. Remove application dependencies/spring-boot-maven-plugin from root (they belong to `vision-app`).
- Child poms: minimal — parent = root pom, artifactId, deps per module table below. Only `vision-app` applies `spring-boot-maven-plugin`.
- Move `src/main/java/com/drones/vision/VisionApplication.java`, `src/test/java/...VisionApplicationTests.java`, `application.properties` into `vision-app/src/...`; delete root `src/`.
- Placeholder modules get only a pom + `src/main/java/com/drones/vision/adapter/<name>/package-info.java` with a one-line javadoc stating the phase it's implemented in.

| Module | Dependencies |
|---|---|
| vision-domain | none (JUnit test scope only) |
| vision-application | vision-domain; test: JUnit, Mockito |
| vision-proto | grpc-netty-shaded, grpc-protobuf, grpc-stub, javax.annotation-api |
| adapter-simulation | vision-domain; test: JUnit |
| adapter-rtsp / publish-hls / overlay | vision-domain |
| adapter-cv-grpc | vision-domain, vision-proto |
| adapter-persistence | vision-domain |
| vision-api | vision-domain, vision-application, spring-boot-starter-web |
| vision-app | everything above + spring-boot-starter, archunit-junit5 (test) |

**Done when:** `./mvnw -q compile` succeeds; app still starts (`VisionApplicationTests` passes under `vision-app`).

---

## 3. Task T2 — `vision-domain`

**Scope:** `vision-domain/src/**` only. Package root `com.drones.vision.domain`. Depends on `java.base` ONLY.

All types are records unless stated; validate invariants in compact constructors (throw `IllegalArgumentException`). Javadoc on every port: contract, threading, backpressure expectations.

### `...domain.model`

| Type | Shape |
|---|---|
| `DeviceId` | `record(String value)` — non-blank; static `DeviceId random()` using `UUID` |
| `StreamId` | `record(String value)` — non-blank; static `StreamId random()` |
| `DeviceType` | enum: `IP_CAMERA, ESP32_CAM, DIY_DRONE, FPV_DRONE, ROBOT, USB_CAMERA, SIMULATED` |
| `Capability` | enum: `VIDEO, TELEMETRY, PTZ, AUDIO` |
| `StreamDescriptor` | `record(String protocol, URI uri, Map<String,String> options)` — protocol non-blank lower-case key that selects the adapter (e.g. `"rtsp"`, `"mjpeg"`, `"sim"`); options defensively copied to immutable |
| `Device` | `record(DeviceId id, String name, DeviceType type, Set<Capability> capabilities, StreamDescriptor stream)` — defensive immutable copies |
| `PixelFormat` | enum: `BGR24, RGB24, YUV420P, JPEG, H264_PACKET, UNKNOWN` |
| `VideoFrame` | `record(StreamId streamId, long sequence, Instant capturedAt, int width, int height, PixelFormat format, ByteBuffer data)` — compact ctor stores `data.asReadOnlyBuffer()`; accessor returns a duplicate so readers can't disturb position (document this) |
| `Telemetry` | `record(DeviceId deviceId, Instant at, Double latitude, Double longitude, Double altitudeMeters, Double headingDegrees, Double batteryPercent, Map<String,Double> extra)` — nullable value fields allowed, `extra` immutable copy |
| `ModelRef` | `record(String id, String version)` |
| `BoundingBox` | `record(double x, double y, double width, double height)` — normalized [0,1]; validate ranges |
| `Detection` | `record(String label, double confidence, BoundingBox box, ModelRef model)` — confidence [0,1] |
| `DetectionResult` | `record(StreamId streamId, long frameSequence, Instant capturedAt, List<Detection> detections, Duration inferenceLatency)` — immutable list copy |
| `TrackedObject` | `record(long trackId, Detection detection, Instant firstSeen, Instant lastSeen)` |
| `AnnotatedFrame` | `record(VideoFrame frame, List<Detection> detections, Telemetry telemetry)` — telemetry nullable |
| `EventType` | enum: `DETECTION, DEVICE_ONLINE, DEVICE_OFFLINE, STREAM_STARTED, STREAM_STOPPED, PIPELINE_ERROR, TRAINING` |
| `Event` | `record(String id, StreamId streamId, Instant at, EventType type, String message, Map<String,String> attributes)` — streamId nullable (device-level events); static factory `Event.of(StreamId, EventType, String message)` |
| `PipelineConfig` | `record(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences, boolean overlayTelemetry, Set<String> labelFilter)` — static `PipelineConfig defaults()` → (model `("yolo", "latest")`, 0.4, 5, 2, true, empty=all labels) |

### `...domain.port.in` (driving)

- `RegisterDeviceUseCase` — `Device register(Registration r)`; nested `record Registration(String name, DeviceType type, Set<Capability> capabilities, StreamDescriptor stream)`
- `ListDevicesUseCase` — `List<Device> devices()`
- `StartStreamUseCase` — `StreamId start(DeviceId deviceId, PipelineConfig config)`
- `StopStreamUseCase` — `void stop(StreamId streamId)`
- `QueryDetectionsUseCase` — `List<DetectionResult> query(DetectionQuery q)`; nested `record DetectionQuery(StreamId streamId, Instant from, Instant to, String label, int limit)` (nullable filters except limit)
- `TrainModelUseCase` — `String startTraining(TrainingSpec spec)` returns job id; nested `record TrainingSpec(String baseModel, String datasetId, int epochs)`

### `...domain.port.out` (driven)

- `VideoSourcePort` — `boolean supports(StreamDescriptor d)`, `Flow.Publisher<VideoFrame> open(StreamId id, StreamDescriptor d)`, `void close(StreamId id)`. Javadoc: publisher is per-open, hot, live; on unrecoverable source failure call `onError`; drop policy for slow subscribers is latest-wins.
- `TelemetrySourcePort` — `boolean supports(Device device)`, `Flow.Publisher<Telemetry> open(Device device)`, `void close(DeviceId id)`
- `DetectionPort` — `CompletionStage<DetectionResult> detect(VideoFrame frame, PipelineConfig config)`. Javadoc: implementations must be non-blocking; caller bounds in-flight requests.
- `OverlayPort` — `VideoFrame render(AnnotatedFrame annotated)`
- `StreamPublisherPort` — `void streamStarted(StreamId id, Device device)`, `void publish(StreamId id, VideoFrame frame)`, `void streamEnded(StreamId id)`
- `RecordingPort` — same three-method shape as StreamPublisherPort
- `EventPublisherPort` — `void publish(Event event)`
- `DeviceRepositoryPort` — `Device save(Device d)`, `Optional<Device> findById(DeviceId id)`, `List<Device> findAll()`, `void deleteById(DeviceId id)`
- `DetectionRepositoryPort` — `void save(DetectionResult r)`, `List<DetectionResult> query(QueryDetectionsUseCase.DetectionQuery q)`
- `ModelRegistryPort` — `List<ModelRef> models()`, `void promote(ModelRef ref)`

### Tests (plain JUnit 5)
Invariant tests: read-only frame buffer, defensive copies, validation failures, `PipelineConfig.defaults()`.

---

## 4. Task T3 — Proto contract + `vision-proto` + `cv-service` skeleton

**Scope:** `proto/**`, `vision-proto/**` (incl. its pom), `cv-service/**`.

### `proto/vision/v1/cv.proto`
`syntax = "proto3"`, package `vision.v1`, `option java_multiple_files = true; option java_package = "com.drones.vision.proto.v1";`

Messages: `FrameRequest{stream_id, sequence, timestamp_millis, width, height, ImageEncoding encoding, bytes data, string model_id, string model_version, float confidence_threshold}`; `enum ImageEncoding{IMAGE_ENCODING_UNSPECIFIED, IMAGE_ENCODING_JPEG, IMAGE_ENCODING_BGR24}`; `BoundingBox{x,y,width,height}` (floats, normalized, top-left origin — comment this); `Detection{label, confidence, box}`; `DetectionResponse{stream_id, sequence, timestamp_millis, model_id, model_version, repeated Detection detections, inference_millis}`; `TrainingJobSpec{base_model, dataset_id, epochs}`; `enum JobState{JOB_STATE_UNSPECIFIED, RUNNING, SUCCEEDED, FAILED}`; `TrainingProgress{job_id, epoch, total_epochs, loss, map50, JobState state, message}`; `ModelInfo{id, version, stage, map<string,float> metrics}`; `ModelList{repeated ModelInfo models}`; `ModelRefMsg{id, version}`; `Ack{ok, message}`; `google.protobuf.Empty` for empty inputs.

Services: `Inference{ rpc DetectStream(stream FrameRequest) returns (stream DetectionResponse); }` and `Training{ rpc StartTraining(TrainingJobSpec) returns (stream TrainingProgress); rpc ListModels(google.protobuf.Empty) returns (ModelList); rpc PromoteModel(ModelRefMsg) returns (Ack); }`

### `vision-proto` codegen
xolstice `protobuf-maven-plugin` 0.6.1 + `os-maven-plugin` 1.7.1 extension; `<protoSourceRoot>${project.basedir}/../proto</protoSourceRoot>`; protoc + grpc-java plugin artifacts via `com.google.protobuf:protoc:${protobuf.version}` / `io.grpc:protoc-gen-grpc-java:${grpc.version}`.

### `cv-service` Python skeleton
- `pyproject.toml` (name `cv-service`, python ≥3.11, deps: grpcio, grpcio-tools, protobuf; `[project.optional-dependencies] cv = ["ultralytics", "opencv-python-headless"]` for Phase 2)
- `cv_service/server.py` — gRPC server on `:50051` implementing `Inference.DetectStream` as an echo stub (empty detections, correct ids/timestamps) and `Training` returning `UNIMPLEMENTED`; clean shutdown on SIGTERM
- `scripts/gen_proto.sh` — `python -m grpc_tools.protoc -I ../proto --python_out --grpc_python_out cv_service/gen/`
- `cv_service/gen/` gitignored; generation documented in `cv-service/README.md`
- `Dockerfile` — `python:3.12-slim`, runs gen script, starts server
- Do NOT commit generated code (either side)

**Done when:** `./mvnw -q -pl vision-proto compile` generates and compiles stubs.

---

## 5. Task T4 — Infra skeleton

**Scope:** `docker-compose.yml`, `.github/workflows/ci.yml`, `.gitignore` (append), `README.md` (new, short: what this is + links to ARCHITECTURE.md + how to build).

- Compose: `postgres:16` (env from `.env`, healthcheck), `bluenviron/mediamtx:latest` (RTSP 8554, HLS 8888), `cv-service` (build `./cv-service`), `vision-app` service present but commented out with a note (Phase 1 image). Named volume for postgres.
- CI: GitHub Actions — job 1: temurin 21, cache maven, `./mvnw -B verify`; job 2: python 3.12, `pip install grpcio-tools`, run `gen_proto.sh`, `python -m compileall cv_service`.
- `.gitignore`: append python section (`__pycache__/`, `*.pyc`, `.venv/`, `cv_service/gen/`) and `.env`.

---

## 6. Task T5 — Application layer + simulation adapter + wiring + ArchUnit

**Scope:** `vision-application/src/**`, `adapters/adapter-simulation/src/**`, `vision-app/src/**`.

### `vision-application` (`com.drones.vision.application`) — NO Spring imports
- `VideoSourceRegistry` — holds `List<VideoSourcePort>`, `sourceFor(StreamDescriptor)` picks first `supports()==true` or throws `UnsupportedProtocolException` (custom unchecked, in this package).
- `DeviceService` — implements `RegisterDeviceUseCase`, `ListDevicesUseCase`; publishes `DEVICE_ONLINE` event on register.
- `StreamPipeline` — per-stream runtime: subscribes to the source publisher (`Flow.Subscriber`, `request(1)` at a time — latest-wins is the source's job); every frame → `StreamPublisherPort.publish`; every Nth frame (derived from `inferenceFps`, assume source ~30fps for now, TODO Phase 2: measure actual fps) → `DetectionPort.detect` IF in-flight < `maxInFlightInferences` (else skip — never queue); completed detections stored as `volatile` latest and forwarded to `DetectionRepositoryPort` + `DETECTION` event (only when non-empty). Overlay integration is Phase 2 — leave a clearly marked seam. Errors → `PIPELINE_ERROR` event + pipeline stops cleanly. `close()` idempotent.
- `StreamService` — implements Start/Stop use cases; owns `ConcurrentHashMap<StreamId, StreamPipeline>`; start: resolve device, resolve source, create pipeline, `STREAM_STARTED` event; stop: close pipeline, `close` source, `STREAM_STOPPED`. Rejects double-start per device (one active stream per device in Phase 0, KISS).
- Unit tests (JUnit + Mockito): registry selection, device register/list, start/stop lifecycle with mocked ports, pipeline sampling + in-flight-bound behavior using a scripted `Flow.Publisher` (use `SubmissionPublisher` in tests).

### `adapter-simulation` (`com.drones.vision.adapter.simulation`)
- `SimulatedVideoSource implements VideoSourcePort`; `supports()` → protocol `"sim"`; options: `width` (def 640), `height` (def 480), `fps` (def 15). Renders Java2D frames (moving filled circle + frame counter + wall-clock text), JPEG-encodes via `ImageIO`, publishes as `PixelFormat.JPEG` through a `SubmissionPublisher` fed by a single-threaded `ScheduledExecutorService` per open stream; `close(streamId)` stops the executor and closes the publisher. No Spring deps except an optional `@Component`-free design: plain class, wired in vision-app.
- Test: open a `sim` stream, collect ≥3 frames, assert dimensions/format/monotonic sequence, close cleanly.

### `vision-app`
- `WiringConfiguration` — `@Bean`s: `SimulatedVideoSource`, `VideoSourceRegistry`, `DeviceService`, `StreamService`, plus dev fallbacks implementing not-yet-built ports: `InMemoryDeviceRepository`, `InMemoryDetectionRepository`, `LoggingEventPublisher`, `NoopStreamPublisher`, `NoopDetectionPort` (completes with empty result) — small classes under `com.drones.vision.app.devsupport`, each with a javadoc note naming the adapter that replaces it and in which phase.
- `ArchitectureTest` (ArchUnit, test scope): (1) `..domain..` depends only on `..domain..` + `java..`; (2) `..application..` depends only on itself + domain + `java..`; (3) no adapter package depends on another adapter package; (4) only `..app..` may depend on adapter packages; (5) `..domain..` and `..application..` are Spring-annotation-free.
- Smoke test: Spring context loads; a `sim` device can be registered and started via use-case beans, frames observed, stopped (via an integration test using the beans directly).

---

## 7. Task T6 — Full verification

Run `./mvnw -B verify`; fix anything broken (compile errors, test failures, plugin issues) with minimal diffs. Then run compose config validation (`docker compose config -q`) if docker is available. Report results honestly.

---

## Execution order

```
T1 ──▶ { T2, T3, T4 in parallel } ──▶ T5 ──▶ T6
```

T5 waits for T2 (needs domain types on classpath). T6 last, over everything.
