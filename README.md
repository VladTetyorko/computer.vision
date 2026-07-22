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

This brings up Postgres, the [mediamtx](https://github.com/bluenviron/mediamtx) RTSP/HLS sidecar, and the `cv-service` skeleton. The `vision-app` service is present in `docker-compose.yml` but commented out — it isn't packaged as a container image yet; run it with Maven/`java -jar` per the Quickstart below.

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

## Status

Phase 1 (first light: one source, end to end) is implemented — RTSP ingest, HLS egress via mediamtx, and the REST control plane described above. See `docs/PHASE1-PLAN.md` for the task breakdown and `ARCHITECTURE.md` for the full roadmap.
