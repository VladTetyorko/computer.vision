# Vision

A modular platform that ingests video/telemetry from heterogeneous devices (DIY drones, FPV drones, IP cameras, ESP32-CAM, robots), runs computer vision on the frames, overlays CV judgements + telemetry on the video, and re-streams / records / alerts. The Java core (hexagonal, ports & adapters) never knows which protocol a frame came from or how inference is implemented; a Python service handles CV via gRPC.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full system design and [docs/PHASE0-PLAN.md](docs/PHASE0-PLAN.md) for the current implementation plan.

## Build

```
./mvnw verify
```

## Run infra

```
cp .env.example .env
docker compose up
```

This brings up Postgres, the [mediamtx](https://github.com/bluenviron/mediamtx) RTSP/HLS sidecar, the `cv-service` (real YOLO inference), and `vision-app` itself (built from the repo-root `Dockerfile` — run `./mvnw -B package` first so `vision-app/target/vision-app-*.jar` exists for it to copy in). See "Demo" below for the full one-command walkthrough, or run `vision-app` with Maven/`java -jar` instead per the Quickstart below (e.g. while iterating on it locally without rebuilding the image each time).

## Quickstart (Phase 1)

End-to-end vertical slice: register a device (the built-in `sim` source, or a real RTSP camera) via REST, start its stream, and watch it in a browser — video flows `VideoSourcePort` → `StreamPipeline` → `StreamPublisherPort` (mediamtx, over pushed RTSP) → HLS in the browser.

1. **Start mediamtx** (the RTSP/HLS sidecar `adapter-publish-hls` pushes to):
   ```
   cp .env.example .env   # first time only
   docker compose up -d mediamtx
   ```
2. **Run the app**:
   ```
   ./mvnw spring-boot:run -pl vision-app
   ```
   By default (`vision.publish.enabled=true`) it publishes to mediamtx at `rtsp://localhost:8554` (push) / `http://localhost:8888` (HLS view) — see `vision-app/src/main/resources/application.properties`. Without mediamtx running, the publisher just drops frames and retries with backoff (see `docs/PHASE1-PLAN.md` §0.3) rather than failing the app; set `vision.publish.enabled=false` to use the no-op publisher instead.
3. **Open the dev console**: [http://localhost:8080](http://localhost:8080).
4. **Register a `sim` device** in the form (protocol `sim`, e.g. uri `sim://demo` — no camera needed), then **start** its stream.
5. **Watch**: the console opens the returned `viewUrl` (`http://localhost:8888/<streamId>/index.m3u8`) in its built-in `hls.js` player. Expect **~5–10 s of latency** before video appears — mediamtx's default HLS muxer buffers a few segments (segment duration × segment count) before the playlist is servable, and `hls.js`/native HLS add their own start-up buffering on top; this is inherent to HLS, not a bug.

To point at a real IP camera/drone instead, register a device with protocol `rtsp` and a `rtsp://...` uri (`adapter-rtsp`, JavaCV/FFmpeg-backed).

## Demo (MVP1 — "the friends demo")

The full multi-source + live-YOLO + map demo (docs/MVP1-PLAN.md), in three commands:

```
./mvnw -B package
docker compose up -d
scripts/demo.sh ~/Videos/your-clip.mp4
```

Then open [http://localhost:8080](http://localhost:8080) (the Wall tab).

1. **`./mvnw -B package`** builds `vision-app/target/vision-app-*.jar` — both `docker compose`'s `vision-app` image (`./Dockerfile`, repo root) and the host-run alternative below need it built first; this repo doesn't run Maven inside the image.
2. **`docker compose up -d`** brings up postgres, mediamtx, `cv-service` (real YOLO11n inference), and `vision-app` (`vision.cv.enabled=true`, pointed at `cv-service:50051`). First run builds both `cv-service` (pulls torch/ultralytics — several GB, be patient) and `vision-app` images.
   - **Host-run alternative** (skip the app/cv-service containers): `docker compose up -d mediamtx` + `cd cv-service && python -m cv_service.server` (own venv, `pip install -e '.[cv]'` first) + `./mvnw spring-boot:run -pl vision-app` — the app's default properties already point at `localhost:8554`/`localhost:18888`/`localhost:50051`, matching this mode.
3. **`scripts/demo.sh <clip>`** creates the demo sources over the REST API and prints every URL to open (Wall/Map/Live, per source) — see the script's own header comment for the full option list and `--stop` to tear everything down again.

### Picking a clip

Street/people footage works best — the default model (YOLO11n) only draws boxes around what it was trained to recognize (person, car, etc.); an empty room or abstract footage won't show any. In compose mode, `scripts/demo.sh`'s clip path must be reachable *inside* the `vision-app` container: drop your clip(s) into `./clips/` (bind-mounted read-only to `/clips`, see `docker-compose.yml`) and pass the container-side path, e.g. `scripts/demo.sh /clips/your-clip.mp4`. Running the jar directly on the host, any absolute host path works.

### What to expect

- **~5-10s of HLS start-up buffering** per stream (see Quickstart above) — inherent to HLS, not a bug.
- **CPU inference latency**: sampling runs at 5fps (`inferenceFps` default) and each sampled frame's `predict()` call costs roughly 0.5-1s on a typical laptop CPU (see cv-service/MODULE.md) — boxes visibly update a couple of times a second, not every frame; that's expected.
- **Kill `cv-service` mid-demo** to see the resilience story: `docker compose stop cv-service` (or start the whole stack with it absent from the outset via `docker compose up -d --scale cv-service=0`) — video and telemetry keep running on every source, detection boxes just stop appearing (`StreamPipeline`'s outage policy logs one `PIPELINE_ERROR` per outage, not per frame — docs/MVP1-PLAN.md §C7 bullet 3). `docker compose start cv-service` brings boxes back within a few retries (exponential backoff, capped ~10s).

### Tear down

```
scripts/demo.sh --stop
```

Stops every source `scripts/demo.sh` started. The underlying assets/devices stay registered (just offline) rather than being deleted, so re-running the script afterwards reuses them instead of piling up duplicates.

### MVP1 exit criteria

- [x] ≥3 different source protocols streaming at once on the Wall (file/direct, rtsp, mjpeg, sim)
- [x] Map tab: moving markers + trails for the streaming drones, offline ones visible
- [x] Visible live detections burned into at least one stream; Live page lists labels
- [x] Kill cv-service → demo keeps running minus boxes; restart → boxes return
- [x] Everything reachable from `docker compose up` + `scripts/demo.sh` + a browser

### Test coverage note

A full docker-compose E2E test (real postgres+mediamtx+cv-service containers) would need the heavy `cv-service` image (torch/ultralytics layers) built in CI/dev — out of scope wherever disk is tight (docs/MVP1-PLAN.md §C9 bullet 4). Instead:

- `docker compose config` validates the compose file's syntax/interpolation without building anything.
- `scripts/demo.sh` proves the REST-level orchestration (create, idempotent skip, teardown) end to end against a live instance.
- The frame → gRPC → `DetectionResult` → REST loop is already covered by `vision-app`'s `CvDetectionEndpointE2ETest` (an in-process/in-JVM gRPC server, no docker, no model weights needed) — see vision-app/MODULE.md's Test inventory.

## Status

Phase 1 (first light: one source, end to end) is implemented — RTSP ingest, HLS egress via mediamtx, and the REST control plane described above. See `docs/PHASE1-PLAN.md` for the task breakdown and `ARCHITECTURE.md` for the full roadmap.
