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

## 5. Cycle order & status

Execution follows the repo's delegation model: per-task scopes are disjoint; every task ends with its scoped build green and MODULE.md updated.

| Cycle | Kind | Ships | Tasks (disjoint scopes) | Status |
|---|---|---|---|---|
| C1 | backend | `file` RX + `POST /api/simulations` | 1a (adapter-rtsp + rename ripple into vision-app), then 1b+1c (application, api, app) | pending |
| C2 | **UI** | Live telemetry OSD + map | one task (vision-web) | ✅ done |
| C3 | backend | `FeedTransmitterPort` + RTSP TX | domain+adapter, then application+api+app | pending |
| C4 | **UI** | Simulation wizard | one task (vision-web) | pending |

**C5+ candidates** (re-propose when C4 closes): WEB-PLAN W6 leftovers + W7 hardening; `/map` overview tab (all assets on one map — referee's tournament view; geolocated detections join in Phase 2); mjpeg TX/RX pair (ESP32-CAM, Phase 4 pull-forward); flight replay (play a closed usage's trail + recording).
