# Implementation Cycles — Simulation TX/RX & Operator UI

Companion to [ARCHITECTURE.md](../ARCHITECTURE.md), [UX-DESIGN.md](UX-DESIGN.md), [WEB-PLAN.md](WEB-PLAN.md).

Work proceeds in **alternating cycles**: odd cycles are backend, **every 2nd cycle is UI-facing** and must *propose, estimate, and implement* a feature a field user needs. The field users are the personas of UX-DESIGN §2: the **drone operator**, their **referee/judge**, and their **crew members** — people at a flying field or competition, not at a desk.

**Milestone of this plan:** a user with zero hardware types one file path and gets a watchable, telemetry-emitting "drone" — first played directly, later transmitted over the real protocol — and the Live view shows where that drone *is* on a map, not just what it sees.

---

## 0. Doctrine: every protocol adapter has an RX and a TX half

| Half | Port | Purpose |
|---|---|---|
| **RX (receive)** | `VideoSourcePort` / `TelemetrySourcePort` | Ingest from real hardware — what all adapters do today |
| **TX (transmit)** | `FeedTransmitterPort` *(new, C3)* | Publish a user-supplied video file (or synthetic feed) **over the real protocol** to a real endpoint, so the platform ingests it through the exact same RX path as real hardware |

Why TX exists: zero-hardware demos, E2E tests of the *full* protocol path (encoder → wire → demuxer → pipeline), and operator rehearsal ("replay yesterday's flight as if it were live"). A TX half is **simulation infrastructure, not egress** — it is unrelated to `StreamPublisherPort` (viewer-facing egress).

**Entry-point principle (KISS):** simulating a drone must be *one call / one dialog*: a video file path (+ optional home lat/lon) → a registered, categorized, optionally already-streaming asset. No manual device/descriptor plumbing.

### Protocol matrix

| Protocol | RX today | TX | Notes |
|---|---|---|---|
| `sim` | ✅ `SimulatedVideoSource` / `SimulatedTelemetrySource` | *n/a* | synthetic, internal — there is no wire to transmit on |
| `file` | **C1** — FFmpeg decode of a local file, looped, real-time paced | *n/a* | the "direct" simulation path; no wire |
| `rtsp` | ✅ `RtspVideoSource` | **C3** — `RtspFeedTransmitter` pushes a file loop to mediamtx | first full TX/RX pair |
| `mjpeg`, `usb`, `udp-raw`, `webrtc`, `mavlink` | future phases | lands **with** its RX per this doctrine | e.g. mjpeg TX = tiny HTTP MJPEG server serving the file |

---

## 1. C1 — backend: `file` playback RX + one-call simulation

**Scope:** `adapters/adapter-rtsp/**`, `vision-application/**`, `vision-api/**`, `vision-app/**` (sequential tasks; see per-task scopes in §5).

### 1a. `file` protocol in adapter-rtsp

adapter-rtsp is really the *FFmpeg ingest* adapter — `resolveFilename(URI)` already special-cases `file:` for its test seam. Promote that to a first-class protocol:

- **Rename `RtspVideoSource` → `FfmpegVideoSource`** (module name stays `adapter-rtsp`; MODULE.md documents it as the FFmpeg ingest adapter covering `rtsp` + `file`). Chase usages with `git grep RtspVideoSource` (vision-app wiring + tests + MODULE.mds).
- `supports()`: protocol `"rtsp"` **or** `"file"`; URI must be a `file:` URI for the latter.
- New option `loop` (`"true"`/`"false"`, **default false**): on graceful EOF (`grab()` returns null) with `loop=true` and no stop requested, restart the grabber and keep the frame `sequence` monotonically increasing. Default stays false — a file source loops only when asked; the simulation layer asks explicitly.
- **Real-time pacing for `file` only:** a local file decodes as fast as the disk allows; a simulated drone must feed frames at the file's native FPS like a live camera. Throttle the grab loop by the media timestamps (`grabber.getTimestamp()` deltas, sleep the difference; clamp sleep ≥ 0). RTSP stays unpaced (network-paced already).
- Tests: `supports()` matrix; loop test (tiny generated fixture video, observe sequence exceed the file's frame count); pacing test (arrival cadence within a generous tolerance of native FPS — keep CI-safe margins).

### 1b. `SimulationService` (vision-application)

- `SimulationService` (interface) → `DefaultSimulationService` per house style. One method:
  `SimulatedAsset simulate(SimulationSpec spec, Ownership ownership, UserId actor)`
- `record SimulationSpec(String displayName /*nullable → derived from filename*/, String videoPath /*required, absolute*/, Double latitude, Double longitude, boolean autoStart)`
- `record SimulatedAsset(AssetId assetId, StreamId streamId /*nullable when !autoStart*/)`
- Behavior: validate the path (exists, readable, regular file → `IllegalArgumentException` otherwise); build an `AssetSpec` with category **`simulated`** (already seeded; missing → `IllegalStateException`, message says to seed it) and **two devices**: video (`protocol "file"`, `uri = path.toUri()`, options `loop=true`, `Capability.VIDEO`) and telemetry (`protocol "sim"`, `Capability.TELEMETRY`, `lat`/`lon` options when provided — drives `SimulatedTelemetrySource`'s circular track around the home point). Create via `AssetService.create`; `autoStart` → `assetService.startStream(assetId, null, PipelineConfig.defaults())`.
- Depends on `AssetService` + `CategoryRepositoryPort` only. Plain JUnit tests, ports mocked.

### 1c. API + wiring

- `SimulationController`: `POST /api/simulations` body `{displayName?, videoPath, latitude?, longitude?, autoStart?}` (autoStart default **true**) → `201 SimulationResponse(assetId, streamId?, viewUrl?)` (`viewUrl` via `StreamPublisherPort`, same as StreamController). Errors through the existing `ApiExceptionHandler` mapping (bad path → 400, missing category → 409). MockMvc tests per house style.
- vision-app: `simulationService` bean in `WiringConfiguration`; wiring test; an E2E smoke test in the `SimStreamSmokeTest` mould — generate a ~1s tiny video with JavaCV's `FFmpegFrameRecorder` into a temp dir, `POST /api/simulations`, assert frames flow and a usage opens with telemetry samples.

**Done when:** each touched module's scoped build is green, MODULE.mds updated, and `curl -X POST /api/simulations -d '{"videoPath":"/path/to/clip.mp4"}'` on a running app yields a watchable HLS stream. **Estimate: M — 2 sequential agent tasks, main risk is the pacing loop (keep it simple: timestamp-delta sleep).**

## 2. C2 — UI: live telemetry + map *(proposal & estimate)*

**Proposal (operator / referee / crew):** the Live cockpit answers "*where is my drone and how is it doing*", not just "what does it see":

- **OSD telemetry panel** on `/live/:deviceId` when the device's asset has a `TELEMETRY` device: coordinates, altitude, heading (compass rose), battery bar, sample age ("2 s ago" — staleness is safety-critical info).
- **Live map inset** (UX-DESIGN §5.2 drone layout): drone marker rotated to heading, breadcrumb trail of the *current usage*, start-point flag; inset expands to a full pane. Referee use: verify the machine is where the pilot claims; crew use: retrieve a downed drone at the last known position.
- Wall tiles gain a small battery/altitude chip when telemetry is present.

**No backend changes.** Data already exists: `GET /api/assets` + `GET /api/assets/{id}` (device→asset mapping, open usage = `recentUsages` entry without `endedAt`), `GET /api/usages/{usageId}/telemetry?limit=200` polled every 2 s while Live is open. This lands the asset DTO mirroring that `models.ts` deliberately deferred.

**Map library:** Leaflet 1.9 (~42 kB gz), imported **only** by the live chunk (route budget 120 kB gz holds). OSM raster tiles with attribution; **offline fallback** (field ops without internet): when tiles fail, render the trail on a dark grid canvas — relative track still visible. Marker rotation via CSS transform, no plugin.

**Done when:** a C1 simulated drone streaming on `/live` shows a moving, rotating marker with a growing trail and live OSD; Vitest specs for the telemetry-polling store and trail/derivation logic; bundle budgets green; MODULE.md updated. **Estimate: M — 1 agent task; risks: Leaflet under zoneless Angular (init map outside signals, imperatively), poll lifecycle on route leave.**

## 3. C3 — backend: `FeedTransmitterPort` + RTSP TX

- **vision-domain:** `record FeedId(UUID value)` (standard id pattern); `record FeedSpec(String protocol, URI source, Map<String,String> options)`; port:
  ```java
  interface FeedTransmitterPort {
      boolean supports(FeedSpec spec);
      StreamDescriptor start(FeedId id, FeedSpec spec); // descriptor the RX side can ingest from
      void stop(FeedId id);                             // idempotent
  }
  ```
- **adapter-rtsp:** `RtspFeedTransmitter` — grabber(file, loop, real-time paced; reuse C1's pacing approach) → `FFmpegFrameRecorder` H.264 push to `rtsp://<base>/feed-<id>` (target base constructor-injected by vision-app, same pattern as adapter-publish-hls's mediamtx config; adapters still never depend on each other). Docker-gated integration test in the `MediamtxDockerIntegrationTest` mould.
- **vision-application:** `SimulationSpec` gains `transport` (`DIRECT` | `RTSP`, default `DIRECT`). `RTSP` → `FeedTransmitterPort.start`, register the video device with the *returned* descriptor; `DefaultSimulationService` tracks `FeedId` per asset; new `void stop(AssetId)` stops stream + feed.
- **vision-api / vision-app:** `POST /api/simulations` gains `"transport"`; `DELETE /api/simulations/{assetId}`; wiring + properties + tests.

**Done when:** `transport=rtsp` simulation flows file → RTSP wire → mediamtx → `FfmpegVideoSource` RX → pipeline → HLS, and stop tears down both ends. **Estimate: M/L — encoder settings are the risk; copy the working ones from `MediamtxStreamPublisher`.**

## 4. C4 — UI: "Simulate a source" wizard *(proposal & estimate)*

**Proposal:** one button on the Devices tab — **Simulate** — opens a dialog: name, server-side video path, mode (**Play file directly** / **Transmit over RTSP** — "rehearse the real protocol path"), home lat/lon (reuse C2's map for click-to-place if shipped), auto-start toggle. Submit → `POST /api/simulations` → toast with a **Watch** action → `/live/:deviceId`. A third mode, **Synthetic pattern**, wraps the existing `sim` protocol quick-add so *all* zero-hardware entry points live in one place. List simulated assets distinctly (category chip "Simulated") with a stop/remove action wired to `DELETE /api/simulations/{assetId}`.

**Done when:** a first-time user goes file-path → watching in under a minute without reading docs. **Estimate: S/M — 1 agent task, mostly composition of existing pieces.**

---

## 5. C5 — backend: mjpeg TX/RX pair (`adapter-mjpeg`)

The second protocol under the §0 doctrine, pulled forward from Phase 4 (ESP32-CAM persona). New module `adapters/adapter-mjpeg`.

- **RX — `MjpegVideoSource implements VideoSourcePort`**: protocol `"mjpeg"`, `http(s)` URIs. Hand-rolled `multipart/x-mixed-replace` parser over `java.net.http` (transparent, dependency-free on the read path — an ESP32-CAM's stream is exactly this): read boundary from `Content-Type`, extract JPEG parts, emit `VideoFrame`s with `PixelFormat.JPEG` **passthrough** (the pipeline already handles JPEG — the sim source emits it). Per-open runtime + `SubmissionPublisher`, drop-newest capacity 4, idempotent close — the module idioms of adapter-rtsp/simulation.
- **TX — `MjpegFeedTransmitter implements FeedTransmitterPort`**: protocol `"mjpeg"`, `file:` source. JDK `com.sun.net.httpserver.HttpServer` on an ephemeral port serving `/feed-<id>` as `multipart/x-mixed-replace`; frames decoded from the file via JavaCV (`FFmpegFrameGrabber` → `Java2DFrameConverter` → `ImageIO` JPEG), looped + real-time paced (same approach as the rtsp pair). Returns `StreamDescriptor("mjpeg", http://127.0.0.1:<port>/feed-<id>)`.
- **Round-trip IT needs no docker**: TX serves a generated file → RX ingests it → frames observed, looping proven — the pair verifies itself in-process.
- Wiring + transport: `SimulationTransport` gains `MJPEG`; API accepts `"transport":"mjpeg"`; vision-app registers both beans (`MjpegVideoSource` into the source list, transmitter into a `List<FeedTransmitterPort>` — `DefaultSimulationService` picks by `supports()`, its `FeedTransmitterPort` dep generalizing to the list/registry per the `VideoSourceRegistry` precedent).

**Done when:** `POST /api/simulations {"transport":"mjpeg", ...}` flows file → HTTP MJPEG wire → RX → pipeline → HLS, DELETE tears both down; smoke test proves it without docker. **Estimate: M — two tasks (module, then transport/wiring); risk: multipart edge cases (partial reads) — keep the parser boundary-tolerant and tested with chunked delivery.**

## 6. C6 — UI: `/map` overview tab *(proposal & estimate)*

**Proposal (referee / crew):** a **Map** tab showing the whole fleet on one map — the tournament overview. Every asset with a known position appears: `STREAMING` assets as live markers (position updates from their open usage's telemetry, short recent trail), `OFFLINE` assets dimmed at `lastKnownPosition` (crew: where to retrieve it). Marker popup: display name, category, status, battery when live, and a **Watch** action → `/live/:deviceId`. Auto-fit bounds on load; manual pan disables follow until re-enabled. Empty state points at the Simulate wizard.

**No backend changes**: `GET /api/assets` (summaries carry `status` + `lastKnownPosition`) polled ~5 s, plus per-streaming-asset telemetry via the C2 store. Reuse the lazily-chunked Leaflet setup from C2 (shared lazy import — must stay out of the initial bundle; tab route idle-preload opt-out like Debug).

**Done when:** two simulated drones (one streaming, one stopped) appear correctly — one moving with a trail, one dimmed static; tab lands after Wall; specs for the marker/status derivation logic; budgets green. **Estimate: S/M — 1 task (vision-web); risk: many live markers polling — cap concurrent telemetry polls (only streaming assets).**

---

## 7. CT — configurable telemetry flight plans *(user-requested, pre-MVP)*

Today `SimulatedTelemetrySource` only flies a fixed circle around `lat`/`lon`. The user must be able to define the flight: speed, start position, end position, checkpoints.

### CT-a — backend: route engine + API

- **adapter-simulation** — new telemetry-device options (strings, the Tier-3 escape hatch; the circular `lat`/`lon` behavior stays as the back-compat default when no `route` is given):
  - `route` — `lat,lon[,altM];lat,lon[,altM];…`, ≥2 points: start → checkpoints → end.
  - `speedMps` — cruise speed, default `12.0` (> 0; lenient parse per the module idiom).
  - `routeMode` — `loop` (default; end→start closing leg) | `bounce` (retrace backwards) | `once` (hold at the end position, keep emitting).
  - `batteryDrainPerSecond` — overrides the existing constant.
  - Engine: piecewise-linear interpolation along the polyline at the existing 1 Hz tick using a local equirectangular approximation (KISS — segments are short); heading = current segment bearing; altitude interpolated when given. Extract a package-private pure `RoutePlan` (parse + `positionAt(distanceAlongRoute)`) so tests need no scheduler.
  - Tests: parse matrix (bad points → fallback to circle, per the module's lenient-option convention), interpolation positions/bearing/altitude, all three modes, speed honored (distance covered per tick).
- **vision-application** — `SimulationSpec` gains nullable `TelemetryPlan plan`; new record `TelemetryPlan(Double speedMps, RouteMode mode, List<Waypoint> route)` + `Waypoint(double latitude, double longitude, Double altitudeMeters)` (validated: route null-or-≥2, speed > 0), converted by `DefaultSimulationService` into the option strings above (plan wins over bare `lat`/`lon`).
- **vision-api** — `StartSimulationRequest` gains optional `telemetry` object `{speedMps?, routeMode?, route:[{latitude, longitude, altitudeMeters?}, …]}` with `toPlan()` validation → 400 on bad input; tests.
- **vision-app** — no new beans; extend a smoke test to pass a 3-point route and assert positions actually progress toward the checkpoints.

**Done when:** `POST /api/simulations` with a route flies the drone along it on the C2/C6 maps at the requested speed. **Estimate: M.**

### CT-b — UI: flight-plan editor in the Simulate wizard

Wizard gains a **Flight plan** section (file modes only): speed input, route-mode select, and a checkpoint editor — an embedded mini-map (reuse `ui/leaflet-loader.ts`) where clicking appends waypoints (markers + connecting line, remove buttons in a list, drag to adjust if cheap), with a manual `lat,lon` text fallback. Sends the `telemetry` object; bare lat/lon home-position fields remain when no route is drawn. Specs for route-building/serialization logic. **Estimate: M — the map picker is the bulk.**

---

## 8. CW — warehouse device inventory flow *(user-requested)*

The operator manages a fleet like a warehouse: add devices, name them, spread/assign them across assets, deactivate, archive (soft delete), restore. Domain + application services already support all lifecycle transitions (`LifecycleState`, `DeviceService`/`AssetService` update/setState/delete); this cycle exposes them over REST and builds the UI. **The API contract below is pinned — CW-a implements it server-side, CW-b codes against it verbatim.**

### Pinned REST contract (all errors via the standard `ApiExceptionHandler` mapping)

| Method | Path | Body | Success | Notes |
|---|---|---|---|---|
| PATCH | `/api/devices/{id}` | `{name?, protocol?, uri?, options?, capabilities?}` | 200 `DeviceResponse` | partial edit → `DeviceEdit`; stream fields only when any of protocol/uri/options present (then protocol+uri required together) |
| POST | `/api/devices/{id}/state` | `{state:"ACTIVE"\|"DEACTIVATED"}` | 200 `DeviceResponse` | idempotent; `DEACTIVATED` on a DELETED device = restore; DELETED→ACTIVE → 409 |
| DELETE | `/api/devices/{id}` | — | 200 `DeviceResponse` | soft delete (archive); idempotent |
| GET | `/api/devices?includeDeleted=true` | — | 200 | default false (today's behavior) |
| PATCH | `/api/assets/{id}` | `{displayName?, category?, attributes?}` | 200 `AssetDetailsResponse` | → `AssetEdit` |
| POST | `/api/assets/{id}/state` | `{state:"ACTIVE"\|"DEACTIVATED"}` | 200 `AssetDetailsResponse` | same restore semantics |
| DELETE | `/api/assets/{id}` | — | 200 `AssetDeletionResponse(assetId, displayName, devicesDeleted, usagesRetained, streamsStopped)` | soft; from `AssetDeletion` |
| GET | `/api/assets?includeDeleted=true` | — | 200 | default false |
| POST | `/api/assets/{id}/devices` | `{deviceId}` | 200 `AssetDetailsResponse` | assign an unowned device; owned by another asset → 409; unknown → 404 |
| DELETE | `/api/assets/{id}/devices/{deviceId}` | — | 200 `AssetDetailsResponse` | unassign; removing the LAST device → 409 (assets need ≥1) |

`DeviceResponse` gains a `state` field (`ACTIVE`/`DEACTIVATED`/`DELETED`) if not already exposed; `AssetSummaryResponse` likewise exposes lifecycle state as `lifecycle` alongside the derived streaming `status`.

### CW-a — backend (vision-application + vision-api + vision-app; runs after CT-a frees the modules)

- vision-application: `AssetService.assignDevice(AssetId, DeviceId, UserId)` / `unassignDevice(AssetId, DeviceId, UserId)` — validate ownership uniqueness (`AssetRepositoryPort.findByDeviceId`), the ≥1-device invariant, audit `UPDATED` with the change; everything else already exists.
- vision-api: the endpoints above (`DeviceController`/`AssetController` additions + small request DTOs), MockMvc tests per house style incl. every 409 case.
- vision-app: no new beans; wiring test untouched unless a gap appears.

**Done when:** every row of the contract table passes MockMvc tests and the modules' scoped builds are green. **Estimate: M.**

### CW-b — UI (vision-web; parallel-safe, codes to the pinned contract)

Devices tab becomes the warehouse: table gains lifecycle chips (Active / Deactivated / Archived) and a per-row action menu — Rename/Edit, Deactivate|Activate, Archive, Restore, Assign to asset… (dialog listing assets), Unassign; "show archived" toggle (drives `includeDeleted`); asset cards with rename/category edit and Archive asset (with confirmation naming what's retained). All mutations through `FleetStore.run()`-style funneling; archived rows visually muted; actions disabled with a tooltip until the backend answers (a 404 on these endpoints before CW-a lands must degrade to one toast, not break the page). Pure logic (menu availability per state machine, request building) gets specs.

**Done when:** full add → name → assign → deactivate → archive → restore journey works in the UI once CW-a is live; specs green regardless. **Estimate: M.**

---

## 9. Cycle order & status

Execution follows the repo's delegation model: per-task scopes are disjoint; every task ends with its scoped build green and MODULE.md updated.

| Cycle | Kind | Ships | Tasks (disjoint scopes) | Status |
|---|---|---|---|---|
| C1 | backend | `file` RX + `POST /api/simulations` | 1a (adapter-rtsp + rename ripple into vision-app), then 1b+1c (application, api, app) | ✅ done |
| C2 | **UI** | Live telemetry OSD + map | one task (vision-web) | ✅ done |
| C3 | backend | `FeedTransmitterPort` + RTSP TX | domain+adapter, then application+api+app | ✅ done |
| C4 | **UI** | Simulation wizard | one task (vision-web) | ✅ done |
| C5 | backend | mjpeg TX/RX pair | new module, then transport/wiring | ✅ done |
| C6 | **UI** | `/map` overview tab | one task (vision-web) | ✅ done |
| CT-a | backend | telemetry flight plans (route/speed/checkpoints) | §7, adapter-simulation → application/api/app | ✅ done |
| CT-b | **UI** | flight-plan editor (map picker) in wizard | §7, vision-web | pending |
| CW-a | backend | warehouse REST surface + assign/unassign | §8, after CT-a | pending |
| CW-b | **UI** | device warehouse UI (lifecycle, assign) | §8, vision-web | pending |
| C7 | backend | real YOLO inference + gRPC DetectionPort + outage resilience | [MVP1-PLAN.md](MVP1-PLAN.md) §C7 | pending |
| C8 | UI-facing | overlay burn-in + detections endpoint + Live strip | [MVP1-PLAN.md](MVP1-PLAN.md) §C8 | pending |
| C9 | demo | compose + demo script + E2E | [MVP1-PLAN.md](MVP1-PLAN.md) §C9 | pending |

C7–C9 execute **[MVP1-PLAN.md](MVP1-PLAN.md)** — the priority target ("the friends demo": simultaneous multi-protocol sources + map + live CV). Post-MVP candidates: WEB-PLAN W6 leftovers + W7 hardening (Playwright smoke in CI, keyboard, responsive); flight replay; MAVLink telemetry RX; geolocated detections on the map.
