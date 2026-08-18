# LAYERING-REFACTOR-PLAN — consistent package shape + config extraction across every backend module

Status: **proposed** (2026-08-01). Owner: backend architecture. Scope: all Maven modules **except**
`vision-web` (already well-structured) and `vision-proto` (pure codegen — do not touch).

Goal: every backend module should be navigable by the same rules. Today they are not — `vision-api`
has 28 `@RestController`s sitting flat next to a JPEG encoder and a security seam; `vision-application`
is 105 files in one package; nine protocol adapters bake deployment facts (bind hosts, timeouts,
encoder bitrates, retry backoff) into `private static final` constants that cannot be changed without
a rebuild. This plan gives each module a **target package shape** and moves every environment/tunable
literal into a `@ConfigurationProperties`-backed value.

**This is a structural refactor. No behavior changes.** Every extracted property's default must equal
the literal it replaces, every REST path/JSON shape/gRPC message stays byte-identical, and tests move
with their classes (import updates only — never deleted, never rewritten to assert something new).

---

## 0. Current state (verified, not assumed)

| Module | Today | Verdict |
|---|---|---|
| `vision-domain` | `model/` (74), `port/out/` (35) | **Already correct.** No structural work — see §6. |
| `vision-application` | 105 files, one flat package, framework-free | Worst offender. Needs feature packages. |
| `vision-api` | 36 flat at root (28 controllers + 8 others); `dto/` (96), `live/` (5), `exceptions/` (3), `config/` (2); **`controller/` exists but is empty and untracked by git** | Half-migrated. Finish it. |
| `vision-app` | 17 flat at root (8 of them `Vision*Properties`); `config/` (9, incl. an 825-line `WiringConfiguration` with 44 `@Bean`s), `devsupport/` (23); **`events/` exists but is empty** | Config package needs splitting; root needs emptying. |
| `adapter-persistence` | `entity/` (16) + 17 flat (16 `Jpa*Repository` + `JpaOperations` + `PersistenceUnit`) | Mapping (`toDomain`/`toEntity`) is inlined per repository. Light work. |
| 9 protocol adapters | 1–11 classes each, single package, ~120 hardcoded literals | No package problem. **Config extraction + 6 class splits.** |
| `cv-service` | 6 flat modules; `server.py` is **1031 lines** carrying gRPC + training orchestration + zip IO + bootstrap; **no config module**, 5 env vars read ad-hoc (3 at import time) | Needs a `config.py` and a servicer/service split. |

Good news that makes this cheaper than it looks:

- **ArchUnit already tolerates subpackages.** Every rule in
  `vision-app/src/test/java/com/drones/vision/app/ArchitectureTest.java` matches on `..domain..`,
  `..application..`, `..adapter..`, `..app..` — wildcard-bounded on both sides. Nothing in this plan
  requires editing those five rules.
- **The `@ConfigurationProperties` pattern is already established and idiomatic**: eight `Vision*Properties`
  **records** with `@DefaultValue` and constructor binding, registered per-configuration via
  `@EnableConfigurationProperties`. New properties copy that shape exactly — no new mechanism.
- **The "inject the value, keep the adapter framework-free" pattern already exists**: `MediamtxStreamPublisher`
  takes four `URI`s, `GrpcDetectionPort` takes `detectWidth`/`jpegQuality`, `MavlinkHeartbeatScanner` takes
  `mavlinkPort`. Extraction is *extending* a working pattern, not introducing one.
- **`vision-api` has zero DTO leakage.** All 96 wire records are already in `dto/`; no root class declares
  an inline record. House rule 5 is already satisfied — this is a move job, not a redesign.

---

## 1. Global conventions (frozen)

### 1.1 Package shape is decided per module *category*, not repo-wide

Three categories, three templates (§3, §4, §5). The module boundaries **are** the hexagon; package
names inside a module describe roles within that module and nothing more.

### 1.2 Naming

- Services: `XService` (interface) + `DefaultXService` (impl). No `*UseCase`, no bare `*Impl`.
- Driven ports keep `*Port`. Adapters are named for their technology (`MediamtxStreamPublisher`,
  `JpaAssetRepository`) — never `*Impl`.
- Methods are plain verbs (`update`, `delete`, `promote`), never `execute`/`handle`/`perform`.
- Exception packages are singular: `exception/` **not** `exceptions/` (this renames the existing
  `vision-api/.../exceptions/` — three files, one wave).
- One type per file. No new interface unless a second implementation exists today (house rule 1).

### 1.3 The config-extraction rule

> **No literal that describes the environment or a tuning choice may live in a class.**

In scope: URLs, hosts, bind addresses, ports, timeouts, durations, intervals, retry counts, backoff
bounds, buffer/queue capacities, page/fetch limits, thread-pool sizes, frame rates, image dimensions,
JPEG quality, bitrates, GOP length, thresholds, file paths, geographic defaults.

Explicitly **out** of scope — these stay `static final` and must not be "externalized":

- Protocol/spec constants: MAVLink message & command ids (`MAV_CMD_DO_SET_MODE=176`, force-arm
  `21196.0f`), MAVLink unknown-value sentinels, JPEG markers (`0xFFD8`), WS-Discovery multicast
  `239.255.255.250:3702`, DNS-SD service types, FFmpeg AVOption *names*, RFC 2046 boundary syntax,
  ITU-R BT.601 luminance weights, `EARTH_RADIUS_METERS`.
- Registry dispatch keys: `PROTOCOL = "sim" | "rtsp" | "mjpeg" | "mavlink" | "v4l2"`, `METHOD = "mdns" | "onvif" | "v4l2"`.
- Format contracts shared with another process: the YOLO archive layout (`data.yaml`, `images/`,
  `labels/`), cv-service's `"active"` model stage, mediamtx's `/index.m3u8` and `/whep` URL suffixes.
- `Java2DOverlayRenderer.LABEL_PALETTE` — its length is baked into `floorMod(label.hashCode(), 8)`;
  making it configurable would destroy the stable label→color mapping the pixel-probe tests assert.

**Binding mechanism (frozen):**

1. The `@ConfigurationProperties` **record** lives in `vision-app` (`…app.config.properties`). Only
   `vision-app` may carry Spring configuration metadata.
2. A module that needs >2 tunables declares its own **plain settings record** in its own module —
   framework-free, compact-constructor validated, with a `static defaults()` factory. Example:
   `FfmpegSettings` in `adapter-rtsp`, `StreamPipelineSettings` in `vision-application`.
3. `vision-app` maps its properties record → the module's settings record and passes it as **one
   constructor argument**. This is what keeps house rule 3 (≤5 ctor deps, target 3) satisfiable for
   `FfmpegVideoSource`'s 14 tunables.
4. Modules with ≤2 tunables skip the settings record and take plain constructor params
   (`adapter-overlay`, `adapter-v4l2`, `adapter-discovery`).
5. **Adapters and `vision-application` never import a `Vision*Properties` type** — that would point a
   dependency at `vision-app` and break the hexagon.

**The behavior guardrail (non-negotiable):** each extracted property's `@DefaultValue` must be
**byte-identical to the literal it replaces**, and new keys are added to `application.yaml`
**commented out**, documenting the default rather than overriding it — matching the existing style of
`vision.discovery.mavlink-port` and `vision.rc.watchdog-timeout-ms`. Consequence: a freshly-built app
behaves exactly as today, and every existing test stays green without touching an assertion. A wave
that cannot keep a default identical must say so in its report instead of silently changing it.

### 1.4 Visibility

Package-private stays package-private wherever the split allows it (house rule 6). This is a real
constraint on §4: `vision-application` has 5 package-private top-level classes, ~25 package-private
constants and ~20 package-private test-seam constructors that same-package tests read. **Test packages
move in lockstep with main packages** — a wave that widens a type to `public` purely to satisfy a test
it stranded in the old package has done the refactor wrong.

---

## 2. Frozen contract — target package FQNs and property keys

These two tables are the contract between waves. An implementing agent may not invent a different
package name or property key; if one is wrong, escalate rather than diverge.

### 2.1 Package FQNs

| Module | Frozen packages |
|---|---|
| `vision-domain` | `…domain.model`, `…domain.port.out` *(unchanged)* |
| `vision-application` | `…application.<feature>` (13, see §4.1), `…application.pipeline`, `…application.scope`, `…application.exception` |
| `vision-api` | `…api.controller`, `…api.dto`, `…api.security`, `…api.ws`, `…api.proxy`, `…api.support`, `…api.live`, `…api.exception`, `…api.config` |
| `vision-app` | `…app.config`, `…app.config.properties`, `…app.wiring`, `…app.events`, `…app.security`, `…app.bootstrap`, `…app.devsupport` |
| `adapter-persistence` | `…persistence.repository`, `…persistence.entity`, `…persistence.mapper`, `…persistence.config` |
| 9 protocol adapters | unchanged single package + `…<adapter>.<Name>Settings` record |
| `cv-service` | `cv_service/config.py`, `cv_service/grpc/` (servicers), `cv_service/training/`, `cv_service/inference/` |

### 2.2 Property keys

Prefixes `vision.publish`, `vision.cv`, `vision.discovery`, `vision.persistence`, `vision.live`,
`vision.simulation`, `vision.rc`, `vision.training`, `vision.auth` exist today. New prefixes:
`vision.rtsp`, `vision.mjpeg`, `vision.mavlink`, `vision.v4l2`, `vision.overlay`, `vision.api`.

| Prefix → record | Owning wave | Representative keys (default = today's literal) |
|---|---|---|
| `vision.rtsp` → `VisionRtspProperties` | F1 | `transport=tcp`, `open-timeout=10s`, `probesize-bytes=32768`, `analyze-duration=1s`, `reorder-queue-size=0`, `max-delay=100ms`, `udp-timeout=5s`, `udp-fifo-size-packets=512`, `srt-latency=120ms`, `publisher-buffer-capacity=4`, `close-join-timeout=20s`, `transmit.gop-seconds=2`, `transmit.preset=ultrafast`, `transmit.fallback-fps=15.0` |
| `vision.mjpeg` → `VisionMjpegProperties` | F1 | `read-timeout=5s`, `publisher-buffer-capacity=4`, `close-join-timeout=20s`, `transmit.bind-host=127.0.0.1`, `transmit.max-viewer-threads`, `transmit.jpeg-quality=0.75`, `transmit.loop=true`, `transmit.viewer-join-timeout=5s` |
| `vision.v4l2` → `VisionV4l2Properties` | F1 | `publisher-buffer-capacity=4`, `close-join-timeout=20s`, `default-video-size`, `default-framerate`, `default-input-format` (last three: absent = driver default, today's behavior) |
| `vision.mavlink` → `VisionMavlinkProperties` | F2 | `bind-host=0.0.0.0`, `silence-window=30s`, `max-unclaimed-vehicles=32`, `close-join-timeout=5s`, `ack-timeout=2s`, `scan.*`, `transmit.tick=50ms`, `transmit.heartbeat-period=1s`, `transmit.default-speed-mps=12.0`, `transmit.default-position-rate-hz=5.0`, `transmit.default-failsafe-battery-percent=15.0`, `transmit.default-sysid=1` |
| `vision.rc` *(extend)* | F2 | `override-hz=33`, `min-override-hz=10`, `max-override-hz=50`, `release-frames=3` — **these replace the `VISION_RC_OVERRIDE_HZ` / `VISION_RC_RELEASE_FRAMES` env vars**, the only place in the repo that configures itself outside Spring |
| `vision.publish` *(extend)* → `VisionPublishProperties.Encoder`/`.Resilience` | F3 | `encoder.crf=21`, `encoder.maxrate-bps=6000000`, `encoder.bufsize-bits=12000000`, `encoder.preset=veryfast`, `encoder.gop-seconds=1`, `encoder.scenecut-threshold=0`, `resilience.initial-backoff=500ms`, `resilience.max-backoff=10s`, `cadence.*`, `replay.window=1s`, `replay.read-timeout=15s` |
| `vision.overlay` → `VisionOverlayProperties` | F3 | `jpeg-quality=0.8`, `min-stroke-width=2`, `stroke-divisor=200`, `min-font-size=12`, `font-divisor=45`, `osd-background-alpha=160`, `osd-margin=4` |
| `vision.cv` *(extend)* | F4 | `response-timeout=2s`, `keepalive-time=20s`, `keepalive-timeout=5s`, `keepalive-without-calls=true`, `channel-shutdown-timeout=5s`, `plaintext=true`, `upload.timeout=300s`, `upload.chunk-bytes=262144`, `registry.call-timeout=10s` |
| `vision.discovery` *(extend)* | F4 | `mdns.join-grace=150ms`, `mdns.safety-margin=50ms`, `mdns.min-list-window=50ms`, `v4l2.dev-base=/dev`, `v4l2.sys-base=/sys` |
| `vision.simulation` *(extend)* | F4 | `video.width=640`, `video.height=480`, `video.fps=15`, `telemetry.center-latitude=50.45`, `telemetry.center-longitude=30.52`, `telemetry.track-radius-meters=200`, `telemetry.period=1s`, `telemetry.battery-drain-percent-per-second=0.05` |
| `vision.api` → `VisionApiProperties` | B | `snapshot.max-width=480`, `snapshot.jpeg-quality=0.8`, `hls-proxy.connect-timeout=5s`, `hls-proxy.request-timeout=15s`, `live.coalesce=150ms`, `live.heartbeat=15s`, `live.telemetry-buffer=50`, `live.event-buffer=300`, `live.detection-buffer=300`, `live.marks-buffer=300`, `paging.default-limit=50`, `paging.max-limit=500`, `upload.max-image-bytes=2097152` |
| `vision.application` → `VisionApplicationProperties` | A | `discovery-grace=200ms`, `replay.default-max-points=500`, `replay.max-points-ceiling=2000`, `replay.fetch-limit=20000`, `stats.fetch-limit=10000`, `fleet.max-assets=500`, `fleet.open-events-scan-limit=2000`, `training.max-finished-jobs=50`, `training.jpeg-quality=0.9`, `pipeline.*` (fps/EWMA/warmup/backoff), `extrapolation.max=800ms`, `extrapolation.match-gate=0.15`, `simulation.mavlink-loopback-host=127.0.0.1`, `simulation.fallback-latitude=50.45`, `simulation.fallback-longitude=30.52` |
| `CV_*` env → `cv_service/config.py` | G | `CV_PORT=50051` *(new — today unreachable)*, `CV_MODEL`, `CV_IMGSZ=416`, `CV_DEVICE`, `CV_MAX_CONCURRENT_INFERENCES`, `CV_DATASET_DIR`, `CV_MODEL_DIR` *(new)*, `CV_MAX_UPLOAD_BYTES`, `CV_GRPC_WORKERS=10`, `CV_SHUTDOWN_GRACE=5` |

### 2.3 Duplicated defaults to collapse (each currently defined 2–3×, already drifting)

| Value | Sites today | Resolution |
|---|---|---|
| watchdog `300` ms | `VisionRcProperties`, `ManualControlWebSocketHandler` `@Value` default, `DefaultManualControlService.DEFAULT_WATCHDOG_TIMEOUT_MS` | single `vision.rc.watchdog-timeout-ms`; the handler injects `VisionRcProperties`, drops `@Value` |
| RC rate `33` Hz | `MavlinkManualControlSender.DEFAULT_OVERRIDE_HZ`, `dto/ManualControlEngagedFrame.DEFAULT_RATE_HZ` | `vision.rc.override-hz`, injected into the DTO's producer |
| cv keepalive `20`/`5` s | `GrpcDetectionPort` (package-private) + re-declared privately in `WiringConfiguration:408,411` | `vision.cv.keepalive-*`; delete the `WiringConfiguration` copies |
| detect width `640`, jpeg `0.8` | `GrpcDetectionPort` **and** `VisionCvProperties` | properties record is the single source; adapter constant deleted |
| `DEFAULT_MAX_POINTS = 500` | `DefaultReplayService`, `UsageTimelineController` | `vision.application.replay.default-max-points`; controller reads it |
| `CLOSE_JOIN_TIMEOUT_MILLIS` | 8 adapter classes, two values (20 000 / 5 000) | per-adapter property; **keep both values** — do not unify to one number |
| backoff `1s`/`30s` vs `1s`/`10s` | `SupervisedPublisher` vs `StreamPipeline`, same constant names | two distinct keys; **the divergence is preserved**, not "fixed" |
| `_parse_imgsz`, `CV_DEVICE` parse, id-sanitize, default model name | each duplicated across `inference.py`/`trainer.py`/`server.py` | one definition in `cv_service/config.py` |
| HLS prefix `"/hls/"` | `HlsProxyController.HLS_PREFIX` literal + `VisionPublishProperties.viewBase()` | derive the prefix from `viewBase()` |

---

## 3. Template (a) — heavy Spring/hexagonal modules

Applies to `vision-api`, `vision-app`, `adapter-persistence`. Each takes only the folders its **actual
role** justifies — no empty ceremony directories.

```
vision-api/           (driving adapter — HTTP/WS edge)
  controller/   @RestController use-case endpoints, one per resource
  dto/          request/response records ONLY (house rule 5)
  security/     PrincipalResolver, CurrentUser, SessionAuthenticator — the token→UserId edge
  ws/           WebSocket handler + handshake interceptor
  proxy/        pass-through edges that own no application service (HlsProxyController)
  support/      edge-local helpers (SnapshotJpegEncoder, LocalNetworkAddresses, CapabilityParsing)
  live/         SSE registry + ring buffers (unchanged)
  exception/    @RestControllerAdvice + api-local exceptions  [renamed from exceptions/]
  config/       @Configuration for MVC/WS/SPA concerns

vision-app/           (assembly — NO controllers, NO DTOs)
  config/properties/  every @ConfigurationProperties record
  wiring/             per-concern @Configuration bean factories (see §7 D)
  config/             cross-cutting @Configuration (SecurityConfig)
  security/           BcryptPasswordHasher, VisionUserDetails, resolvers/authenticators
  events/             outbound event/audit port bridges  [directory already exists, empty]
  bootstrap/          ApplicationRunners (SimulationResumeRunner) [AuthSeedRunner deleted,
                      docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1 — seeding moved to a Flyway migration]
  devsupport/         in-memory fallback repositories (unchanged)

adapter-persistence/  (driven adapter — NO controllers, NO DTOs)
  repository/   Jpa*Repository implementing a domain out-port
  entity/       @Entity classes (unchanged)
  mapper/       entity↔domain mapping extracted from the repositories
  config/       PersistenceUnit, JpaOperations
```

`vision-app` gets **no** `controller/`, `dto/`, `service/` or `repository/` package — it is the
assembly module. `adapter-persistence` gets no `controller/`, `dto/` or `service/`.

---

## 4. Template (b) — framework-free core

### 4.1 `vision-application`: feature-first, role-second

**Decision (confirm before delegating).** Pure layer packaging (`service/`, `command/`, `readmodel/`,
…) is the obvious reading of "separate Services from DTOs", but here it produces a `service/` package
with **48 files** — barely better than the 105-file flat package — and it strands every collaborator
away from its only caller, forcing 5 classes and ~25 constants to widen to `public` for no benefit.
Feature-first packages of 4–12 files each fix the navigability complaint, keep `TrainingFrameEncoder`
and `YoloDatasetWriter` package-private next to `DefaultLabelingService`, and match the
feature-responsibility folder doctrine `vision-web` already follows.

```
application/
  <feature>/    XService + DefaultXService + that feature's command records,
                read-model records, enums, and package-private collaborators
  pipeline/     shared runtime machinery (public by necessity — used across features)
  scope/        VisibilityScope, ScopeResolver, DefaultScopeResolver, AccessDeniedException
  exception/    ProbeFailedException, UnsupportedProtocolException
```

| Feature package | Contents |
|---|---|
| `asset` | `AssetService`+`Default`, `AssetSpec`, `AssetEdit`, `AssetSummary`, `AssetDetails`, `AssetDeletion`, `AssetAttention`, `AssetStatus`, `AssetStatsService`+`Default`, `AssetStats` |
| `category` | `CategoryService`+`Default`, `CategoryCounts` |
| `device` | `DeviceService`+`Default`, `DeviceEdit`, `DeviceRegistration`, `UpdateOutcome`, `ProbeService`+`Default`, `ProbeResult` |
| `discovery` | `DiscoveryService`+`Default`, `ScanRequest`→**`DiscoveryScanSpec`**, `ScanResult`→**`DiscoveryScanResult`** |
| `stream` | `StreamService`+`Default`, `ActiveStream`, `PipelineConfigPatch` |
| `fleet` | `FleetSummaryService`+`Default`, `FleetSummary` |
| `simulation` | `SimulationService`+`Default`, `SimulationSpec`, `SimulatedAsset`, `SimulationTransport`, `TelemetryTransport`, `TelemetryPlan`, `Waypoint`, `RouteMode` |
| `flight` | `FlightCommandService`+`Default`, `ManualControlService`+`Default`, `ManualControlSession`, `WatchdogListener` |
| `geofence` | `GeofenceService`+`Default`, `GeofenceZoneSpec`, `GeofenceMonitor` |
| `mark` | `MarkService`+`Default`, `MarkSpec`, `MarkPatch`, `GeolocateSpec` |
| `replay` | `ReplayService`+`Default`, `ReplayCaptureSpec`, `ReplaySources`, `UsageTimeline`, `UsageRecording` |
| `training` | `DatasetService`+`Default`, `DatasetSpec`, `LabelingService`+`Default`, `LabelSpec`, `CaptureSpec`, `TrainingJobService`+`Default`, `TrainingJobView`, `ModelRegistryService`+`Default`, `RegisteredModel`, `TrainingStores`, `TrainingFrameEncoder`*, `YoloDatasetWriter`* |
| `identity` | `UserService`+`Default`, `UserSpec`, `AuthService`+`Default`, `GroupService`+`Default`, `GroupSpec`, `AssignmentService`+`Default`, `ActivityService`+`Default` |
| `pipeline` | `StreamPipeline`, `SupervisedPublisher`*, `DetectionEventEngine`*, `DetectionExtrapolator`*, `UsageTracker`, `VideoSourceRegistry`, `FeedTransmitterRegistry` |

`*` = package-private today. Inside `training/` and `pipeline/` they **stay** package-private; the
three in `pipeline/` used by `DefaultStreamService`/`DefaultFleetSummaryService` must widen to
`public` — that is the only unavoidable visibility widening in this plan, and it is honest: they are
genuinely shared machinery.

### 4.2 `vision-domain`: no change

Verified: `model/` + `port/out/` is already the correct hexagonal shape, every type is already
`public`, and subpackaging 74 tightly-interlinked value types would churn imports across five modules
to buy nothing. **Declared a non-goal.** Two optional micro-fixes only (wave A, ~10 minutes):
`ManualControlLink` is a handle type, not a port, and sits in `port/out/` — either rename to make the
role obvious or leave with a javadoc note. No `port/in/` package is created: `vision-application`'s
service interfaces are the driving ports and moving them into the domain would invert the dependency.

---

## 5. Template (c) — protocol adapters and cv-service

### 5.1 The 9 protocol adapters

**Explicitly no `controller/`, `dto/`, `service/`, `repository/` or `entity/` folders.** These are
1–11 class modules implementing one or two out-ports; the folder vocabulary would be pure ceremony.
Their real problems are magic values (§2.2) and six classes doing several jobs at once. Target shape
is the current single package plus a settings record and the splits below.

| Adapter | Split (each new class package-private unless a test needs otherwise) |
|---|---|
| `adapter-rtsp` | `FfmpegVideoSource` (912) → `FfmpegGrabberOptions` (option/default config, ~450 lines incl. javadoc, already three static test seams) + `FfmpegGrabLoop` (promote nested `StreamRuntime`, zero enclosing-instance refs) + a ~120-line port class. Share a `RealtimePacer` with `RtspFeedTransmitter` (duplicated verbatim today). |
| `adapter-publish-hls` | `MediamtxStreamPublisher` (817) → `MediamtxUrls` + `H264RecorderFactory` + split `StreamState` into `PublishBackoff` / `CadenceEstimator` / `PublishDiagnostics`. `LagTracker` is the precedent. Delete dead `DEFAULT_PLAYBACK_PORT`/`derivePlaybackViewBase` (superseded by `vision.publish.mediamtx.playback-base`). |
| `adapter-cv-grpc` | `GrpcDetectionPort` (627) → `DetectionFrameCodec` (~110 lines of pure encode/decode) + `DetectionStreamSession` (promote the 150-line nested bidi state machine). Collapse the 4-constructor ladder to 2 once properties supply the defaults. |
| `adapter-mavlink` | `MavlinkSocketHub` (467, five jobs) → `VehicleClaimRegistry` + `CommandAckRegistry` + a ~180-line socket/dispatch class; `VehicleRegistration` becomes top-level. `MavlinkFeedTransmitter` (434) → extract stateless `SimulatedVehicleMessages`. `MavlinkTelemetryDecoder` (404, **35 mutable fields**) → `PositionAndPowerState` / `FlightStatusState` / `ArdupilotExtras`. `MavlinkManualControlSender`: delete the env-var block entirely. |
| `adapter-mjpeg` | `MjpegFeedTransmitter` (432, four jobs) → `MjpegHttpServerHost` (shared server lifecycle) + `MjpegViewerSession` (decode/encode/pace). |
| `adapter-overlay` | `Java2DOverlayRenderer` (307) → `FrameImageCodec` + `DetectionBoxPainter` + `TelemetryOsdPainter`. This is what makes the layout values injectable without threading config through `OverlayPort`. **Latent bug to fix while there:** the OSD reuses `MIN_FONT_SIZE` as an absolute size, so the OSD is fixed 12 px at any resolution — unreadable on 4K. Fix behind the new `font-divisor` property with the *current* value as default; changing the default is a separate decision. |
| `adapter-simulation` | Healthy. Extract pure `SyntheticFlightState` from `DeviceRuntime` (mirrors the existing `RoutePlan` precedent). |
| `adapter-v4l2` | Healthy. Config only. **Do not** "fix" its deliberate duplication of `adapter-rtsp`'s grab loop — adapters may not depend on each other. |
| `adapter-discovery` | Healthy. Config only; optional `ScanBudget` value object in `MdnsScanner`. **Do not change the timeout-budget arithmetic** — `MdnsScannerLoopbackTest` pins it, and `DefaultDiscoveryService`'s 200 ms caller grace is coupled to it. |

### 5.2 `cv-service` — the same separation in Python idioms

No Java package names. Target:

```
cv_service/
  config.py       frozen @dataclass Settings + a single from_env() — ONE place every CV_* is read,
                  resolved at startup, passed in (kills all import-time env reads and the three
                  duplicated parsers). No pydantic: the module has no pydantic dep and dataclasses
                  are already its idiom (3 frozen dataclasses in use).
  grpc/           servicers.py — wire↔domain translation ONLY (preserves MODULE.md's invariant that
                  server.py is the sole cv_pb2 translation point, now narrowed to this package)
                  server.py    — serve()/main(), signal handling, composition root
  training/       orchestrator.py (job lifecycle, the queue-poll state machine lifted out of the
                  120-line StartTraining), dataset.py (zip landing, path-safety guards,
                  _sanitize_dataset_id — one definition), trainer.py (unchanged ultralytics core),
                  marker.py (renamed from the misleading training.py — it only reads/writes
                  active_model.json)
  inference/      detector.py (from inference.py), registry.py, concurrency.py
```

Also in scope: type-annotate the four unannotated gRPC handlers; delete `_build_default_detector()`
(dead — no call site); reconcile the `yolo11n.pt` vs `yolo26n.pt` doc drift in `README.md`/`DEPLOY-GPU.md`.
Preserve: lazy `cv2`/`ultralytics` imports (`server` must import without the `cv` extra),
`inference`/`registry` never importing `cv_pb2`, and `Dockerfile`'s `CV_IMGSZ=416` ↔ default coupling.
Tests move alongside (`tests/` mirrors the new packages).

---

## 6. Hard guardrails — every implementing agent, every wave

1. **Dependency rule (ArchUnit-enforced).** domain ← application ← adapters ← app. Adapters never
   depend on each other — not even to share a duplicated pacer or JPEG encoder. Only `vision-app` may
   depend on an `..adapter..` package.
2. **No Spring in `vision-domain` or `vision-application`.** No `@ConfigurationProperties`, no
   `@Component`, no `@Value`. Config reaches them as plain constructor values or a plain settings
   record, supplied by `vision-app`.
3. **No adapter imports a `Vision*Properties` type.** Same reason.
4. `ArchitectureTest`'s five existing rules must stay green **unmodified** — they are wildcard-bounded
   and already tolerate subpackages. If a wave needs to edit them, it has broken the hexagon.
5. **No behavior change.** No new endpoint, field, status code, JSON property, gRPC message, log
   format, or default value. Extracted defaults are byte-identical to the literals they replace.
6. **Tests move, never die.** A moved class moves its test to the mirrored test package, with import
   updates only. Deleting or weakening a test to make a move compile is a failed wave.
7. **Scoped builds only.** `./mvnw -B -pl <path> test` (plus `-am` where needed). Never a
   reactor-wide build while another wave holds modules red.
8. **`MODULE.md` updated in the same task**, before the wave reports done.

---

## 7. Waves

Two tracks. The **spine** (A→D) is strictly sequential because each wave renames packages that the
next module imports. The **adapter track** runs in parallel from day 0 because internal class splits
touch only adapter files.

**Cross-module rule for the spine:** a wave that moves a type owns the *mechanical import fixes* in
every downstream module — and nothing else there. Touching a downstream class's logic is out of scope
for that wave.

| Wave | Scope | Depends on | Build gate |
|---|---|---|---|
| **A** | `vision-application` → 13 feature packages + `pipeline`/`scope`/`exception`; `VisionApplicationProperties` + per-service settings records; test tree moved in lockstep; import fixes in `vision-api` + `vision-app` | — | `-pl vision-application,vision-api,vision-app` |
| **B** | `vision-api` → `controller/`, `security/`, `ws/`, `proxy/`, `support/`; `exceptions/`→`exception/`; `CapabilityParsing` out of `dto/`; delete the stray empty `controller/` dir first; `VisionApiProperties` (makes `SnapshotJpegEncoder` an instance, not a static utility) | A | `-pl vision-api,vision-app` |
| **C** | `adapter-persistence` → `repository/`, `mapper/` (extract the inlined `toDomain`/`toEntity` per repo), `config/`; `PersistenceUnit`'s Hibernate settings → properties | B | `-pl adapters/adapter-persistence,vision-app` |
| **D** | `vision-app` → `config/properties/` (the 8 records off the root), **split the 825-line `WiringConfiguration`/44 beans into per-concern `wiring/` classes** (`VideoSourceWiring`, `TelemetryWiring`, `PublishWiring`, `CvWiring`, `ApplicationServiceWiring`, `FeedTransmitterWiring`, …), root classes → `security/` + `events/` + `bootstrap/` | A,B,C | `-pl vision-app` |
| **E1–E4** | Adapter **internal splits only**, public constructors unchanged: E1 rtsp+v4l2, E2 mavlink, E3 publish-hls+overlay, E4 cv-grpc+mjpeg+simulation+discovery | — (parallel with A–D) | `-pl adapters/<each>` |
| **F1–F4** | Adapter **config extraction**: settings record + `Vision*Properties` + its own `wiring/` class + its own `application.yaml` block. Pairs 1:1 with E1–E4 | D + matching E | `-pl adapters/<each>,vision-app` |
| **G** | `cv-service` restructure + `config.py` | — (parallel from day 0) | `cv-service/scripts/test.sh` |
| **H** | Final: add ArchUnit package-shape rules (controllers only in `..api.controller`, `@ConfigurationProperties` only in `..app.config.properties`, no `..application..` class outside its feature/pipeline/scope/exception packages); `MODULE.md` sweep; `./mvnw -B verify` | all | full `verify` |

**Why D splits `WiringConfiguration` — the key unblocking move.** Every adapter config wave needs to
change a bean definition. With one 825-line file that makes F1–F4 mutually conflicting; with
per-concern wiring classes each F-wave owns its own file and they parallelize cleanly. The 44-bean
file is also the single worst "no separation of responsibilities" instance in `vision-app`, so this
pays twice.

**The one genuinely shared file across F1–F4 is `application.yaml`.** Each wave appends only its
own commented block, in the existing `# --- Section ---` style, at the end. Conflicts are trivial to
resolve but waves should not reorder or reformat existing sections.

### Definition of done (per wave)

- Scoped `-pl` build green (or `scripts/test.sh` for G); no test deleted or weakened.
- No literal from §1.3's in-scope list left in the wave's classes; every new default byte-identical.
- New keys documented and **commented out** in `application.yaml`.
- Package-private visibility preserved except where §4.1 names an unavoidable widening.
- `MODULE.md` updated.

---

## 8. Non-goals / deferred (named, not silently dropped)

- **`vision-web` and `vision-proto`** — out of scope entirely.
- **`vision-domain` restructuring** — deliberately declared conformant (§4.2). Not "forgotten".
- **Splitting `vision-application` into Maven modules per feature** — the feature packages make it
  possible later; not doing it now.
- **Package-by-feature inside `vision-api`** — controllers stay layer-packaged; they map 1:1 to REST
  resources and the module is a thin edge.
- **`devsupport/` sub-structuring** (23 in-memory repos, flat) — cosmetic, low value, deferred.
- **Fixing the four application-layer wire-leakage smells beyond renaming**: `TrainingJobView`'s
  all-primitive fields, `UsageRecording` carrying a `java.net.URI`, `PipelineConfigPatch` using boxed
  nulls where `MarkPatch` uses `Optional`. Wave A renames `ScanRequest`/`ScanResult` (which forced the
  ugly `ScanRequestDto` name in `vision-api`); the other three are **behavior-adjacent** and deferred
  to a follow-up.
- **`IllegalStateException → 409` blanket mapping** in `ApiExceptionHandler`, duplicated by
  `ManualControlWebSocketHandler.mapIllegalState`'s substring matching. Fixing it needs a new typed
  application exception = a behavior change. Deferred, noted.
- **Changing any extracted default** — including the 12 px OSD font (§5.1), the divergent 10 s vs 30 s
  backoff caps, and the 20 s vs 5 s join timeouts. All preserved exactly; retuning is a separate,
  explicitly-requested change.
- **Adding validation annotations** (`@Validated`, `@Min`) to the new properties records — the existing
  eight don't use them; matching the house style now, revisiting uniformly later.
- **cv-service linting/typing gates** (ruff/mypy are gitignored but unconfigured) — out of scope.
