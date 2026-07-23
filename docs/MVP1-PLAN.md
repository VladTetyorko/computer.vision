# MVP1 — The Friends Demo

Companion to [CYCLES-PLAN.md](CYCLES-PLAN.md) (cycles C7–C9 execute this plan) and [ARCHITECTURE.md](../ARCHITECTURE.md) (this is the Phase-2 vertical slice, demo-first).

**The demo, verbatim:** on one machine, `docker compose up`, open `http://localhost:8080`, run the demo script with a couple of video clips → the **Wall** shows several sources streaming *simultaneously* (a file played directly, the same or another file transmitted over RTSP, one over MJPEG, one synthetic), the **Map** tab shows the simulated drones moving with trails, and the video itself carries **live YOLO detections** — boxes and labels burned into the stream wherever people/cars/objects appear in the clips. Stopping the CV service mid-demo degrades gracefully: boxes disappear, video and map keep running.

## What already exists (do not rebuild)

- Multi-source simultaneous streaming, wall/live/map UI, one-call simulation over `direct`/`rtsp` (C1–C4, C6), `mjpeg` module (C5a) — see CYCLES-PLAN.
- The full gRPC contract (`proto/vision/v1/cv.proto`, codegen both sides) and a **working echo round-trip**: `cv-service` `DetectStream` echo stub ↔ `vision-proto` stubs. `FrameRequest` already carries JPEG and BGR24 encodings — both frame formats the pipeline produces.
- `StreamPipeline` already samples frames at `inferenceFps`, bounds in-flight inferences (skip-not-queue), and persists `DetectionResult`s via `DetectionRepositoryPort`. `DetectionPort` is currently the no-op devsupport bean.

## Gap → cycles

### C7 — backend: real inference vertical (cv-service YOLO + adapter-cv-grpc + resilience)

1. **cv-service**: implement real inference in `InferenceServicer.DetectStream` — Ultralytics YOLO (`yolo11n`/`yolov8n`, CPU-first) behind the existing `cv` optional dependency group; decode `JPEG` (turbo path) and `BGR24` (reshape) per `FrameRequest.encoding`; map results to `Detection{label, confidence, normalized BoundingBox}`; `inference_millis` measured. Model loaded once per process, requested `model_id` honored later (registry is Phase 3) — log-and-serve-default for now. Echo behavior remains when the `cv` extra isn't installed? No — KISS: the service *requires* the model deps; the Dockerfile CPU variant installs them. Update `pyproject.toml`/Dockerfile; a small pytest with a generated image proving one detection cycle (skippable when model weights can't download).
2. **adapter-cv-grpc**: `GrpcDetectionPort implements DetectionPort` — managed channel to a configured endpoint; per-stream bidi `DetectStream` call (stub per stream id, lazily opened, closed on stream end via a `close(StreamId)` hook or idle eviction — document the choice); `detect()` returns a `CompletionStage` completed by the matching response (correlate by `sequence`); **never blocks**, and a transport failure completes exceptionally *fast* (deadline ~2s) — no internal queuing per the port contract. Map wire↔domain types. Unit tests with `grpc-inprocess` server (echo + failure servicers).
3. **Resilience (vision-application)**: change `StreamPipeline`'s detection-failure policy — a failed inference must **not** close the stream. New policy: log/emit `PIPELINE_ERROR` **once per outage** (not per frame), keep the video path untouched, drop detections, and retry inference on subsequent sampled frames with simple exponential backoff (cap ~10s). Source errors still close the stream as today. Tests for the outage/recovery transitions with a scripted flaky `DetectionPort`.
4. **Wiring**: `vision.cv.enabled` (default **false** → `NoopDetectionPort`, exactly today's behavior) + `vision.cv.endpoint`; when enabled, the gRPC port bean. Wiring tests both ways.

**Done when:** with `cv-service` running locally, a simulated stream's sampled frames come back with real detections persisted via `DetectionRepositoryPort`; killing the service mid-stream leaves video/telemetry running and logs one outage event; restart resumes detections. **Estimate: L — the biggest cycle; risks: bidi correlation and the outage policy (keep both boring).**

### C8 — user-visible detections: overlay burn-in + detections surfacing

1. **adapter-overlay**: `Java2DOverlayRenderer implements OverlayPort` — draw `Detection` boxes (normalized → pixel), label + confidence text, per-label stable colors; input `BGR24` drawn directly onto a copy, `JPEG` decoded→drawn→re-encoded (`ImageIO`, quality ~0.8); returns a distinct `VideoFrame`, never mutates (port contract). Optional telemetry OSD corner (config flag `PipelineConfig.overlayTelemetry`) — coordinates/battery text; the web OSD already exists, keep this minimal. Pure unit tests (pixel-probe the box corners).
2. **StreamPipeline**: wire the Phase-2 overlay seam — before publish, if latest detections are non-empty (or telemetry OSD is on), render via `OverlayPort`; overlay failure → publish the raw frame (never blocks/kills video). `latestDetections` already exists.
3. **vision-api**: `GET /api/streams/{streamId}/detections?limit=` over `DetectionRepositoryPort` (read-only port in a controller — same precedent as `TelemetryRepositoryPort`) → recent `DetectionResult`s. MockMvc tests.
4. **vision-web (small):** Live page gets a detections strip — last-N label chips with confidence (poll the new endpoint ~2s, silent-degrade), plus a "CV: on/off/degraded" hint derived from result recency. Wall stays untouched (boxes are burned in — the tiles already show them).

**Done when:** watching a simulated stream of a clip with people shows moving labeled boxes in Wall/Live/HLS, and the Live strip lists what's being seen. **Estimate: M.**

### C9 — demo assembly & hardening

1. **docker-compose**: add `cv-service` (build from repo root per its Dockerfile gotcha; CPU image; healthcheck), `vision.cv.enabled=true` + endpoint env for the app service; compose still works with the service scaled to 0 (resilience proves itself).
2. **Demo script** `scripts/demo.sh <clip1> [clip2 ...]`: waits for health, then via the API: simulates clip1 `direct`, clip2 (or clip1) `rtsp`, one `mjpeg` (C5b prerequisite), plus one synthetic device — prints the URLs to open (Wall, Map). Idempotent re-runs.
3. **README "Demo" section**: the exact three commands + a note on good demo clips (street/people footage → visible detections) and expected latency figures.
4. **CI-safe E2E**: docker-gated test — compose-style app + mediamtx + cv-service (echo mode is enough in CI: proves the full frame→gRPC→response→persist loop without model weights), simulate, assert detections endpoint returns rows. Full-YOLO E2E stays a documented manual step.

**Done when:** a fresh machine with docker + two mp4s reaches the full demo with exactly the commands in the README. **Estimate: S/M.**

## Order & prerequisites

`C5b` (mjpeg transport wiring, already specced in CYCLES-PLAN §5) → `C7` → `C8` → `C9`. C5b and C7 both touch vision-application/vision-app — strictly sequential. C6 (`/map`) is independent and already in flight. The UI-cadence rule is satisfied by C8's user-visible outcome (burned-in boxes + Live strip) and C9's demo UX; strict alternation bends to the MVP goal here by design.

## MVP1 exit criteria (the friends checklist)

1. ≥3 different source protocols streaming at once on the Wall (file, rtsp, mjpeg, sim).
2. Map tab: moving markers + trails for the streaming drones, offline ones visible.
3. Visible live detections burned into at least one stream; Live page lists labels.
4. Kill cv-service → demo keeps running minus boxes; restart → boxes return.
5. Everything reachable from `docker compose up` + `scripts/demo.sh` + a browser.
