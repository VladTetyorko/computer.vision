# Phase 1 Implementation Plan — RTSP in, HLS out, REST control

Companion to [ARCHITECTURE.md](../../../ARCHITECTURE.md) and successor of [PHASE0-PLAN.md](PHASE0-PLAN.md). Authoritative spec for Phase 1 tasks. **Milestone:** register a device (RTSP camera *or* the `sim` source) via REST, start its stream, and watch it in a browser.

---

## 0. Design decisions

1. **Media distribution is offloaded to mediamtx.** `adapter-publish-hls` implements `StreamPublisherPort` by re-encoding frames to H.264 and *pushing RTSP* to the mediamtx sidecar (`rtsp://…:8554/<streamId>`); browsers play mediamtx's HLS output (`http://…:8888/<streamId>/index.m3u8`). The JVM never serves media segments — that's what scales, and mediamtx gives us WebRTC egress in Phase 7 for free. Re-encoding is required anyway once Phase 2 burns overlays into frames.
2. **Decoded BGR24 frames are the internal currency** for RTSP sources (CV + overlay need pixels). The simulation source stays JPEG; the publisher accepts both (`JPEG` → decode via ImageIO).
3. **Publisher resilience:** a broken/absent mediamtx must never kill a pipeline. The publisher catches its own errors, drops frames while disconnected, and retries connecting with backoff. `PIPELINE_ERROR` is reserved for source/pipeline failures.
4. **One dedicated platform thread per RTSP grab loop** (decoding is CPU-bound; virtual threads buy nothing there). Latest-wins drop for slow subscribers, per the `VideoSourcePort` contract.
5. **JavaCV/FFmpeg pins:** `javacv.version=1.5.10`, FFmpeg artifact `org.bytedeco:ffmpeg-platform-gpl:6.1.1-1.5.10` (the `-gpl` classifier build ships libx264, needed for H.264 encode; plain `ffmpeg-platform` does not). Property `ffmpeg.version=6.1.1-1.5.10` in the parent pom. Big first download — acceptable; per-OS slimming is a later optimization.
6. **Reconnect/watchdog for sources stays out of Phase 1** (backlog item): on unrecoverable grab error the source signals `onError`, pipeline emits `PIPELINE_ERROR` and stops.

---

## 1. Task U1 — Cross-cutting prep (poms + small domain/application additions)

**Scope:** all `pom.xml` files; `vision-domain/src/**`; `vision-application/src/**`.

- Parent pom: add `javacv.version` / `ffmpeg.version` properties; dependencyManagement for `org.bytedeco:javacv` and `org.bytedeco:ffmpeg-platform-gpl`.
- `adapter-rtsp` pom: + `javacv`, `ffmpeg-platform-gpl`; test: junit.
- `adapter-publish-hls` pom: + `javacv`, `ffmpeg-platform-gpl`; test: junit.
- `vision-api` pom: + test deps `spring-boot-starter-test` (MockMvc).
- `vision-app` pom: + `adapter-rtsp`, `adapter-publish-hls` (vision-api already present).
- Domain additions (keep style/validation conventions of existing code):
  - `port.in.ListStreamsUseCase` — `List<ActiveStream> streams();` nested `record ActiveStream(StreamId streamId, DeviceId deviceId, Instant startedAt)` with null-validation.
  - `port.out.StreamPublisherPort` — add `default Optional<URI> viewUrl(StreamId id) { return Optional.empty(); }` with javadoc: where a viewer can watch the published stream, empty if the publisher has no viewing endpoint.
- `vision-application.StreamService`: implement `ListStreamsUseCase` (track `startedAt` per active stream; thread-safe). Unit test for it.

**Done when:** `./mvnw -q -B install -DskipTests` succeeds and `./mvnw -B -pl vision-domain,vision-application test` is green.

## 2. Task U2 — `adapter-rtsp`

**Scope:** `adapters/adapter-rtsp/src/**` (main + test). Package `com.drones.vision.adapter.rtsp`. Plain classes, no Spring.

- `RtspVideoSource implements VideoSourcePort`:
  - `supports()` → protocol `"rtsp"`.
  - `open(streamId, descriptor)`: starts one platform thread running an `FFmpegFrameGrabber` loop over `descriptor.uri()`. Grabber config: `setPixelFormat(AV_PIX_FMT_BGR24)`, option `rtsp_transport` from `options` (default `tcp`), socket timeout option (`timeout` µs, default 10 s), `setOption("rw_timeout", …)` as applicable.
  - Each grabbed image frame → **copied** into a heap `ByteBuffer` (grabber reuses native buffers) → `VideoFrame(streamId, seq++, Instant.now(), w, h, BGR24, buf)` → `SubmissionPublisher.offer(…, drop handler)` (latest-wins; small buffer, e.g. 4).
  - Unrecoverable grab failure → `closeExceptionally` (subscriber `onError`), clean grabber release. `close(streamId)`: stop flag → thread joins → grabber + publisher released; idempotent.
- `FrameConverter` (package-private): JavaCV `Frame` → BGR24 `ByteBuffer` copy (unit-testable without network).
- **Tests (no live camera):**
  - Unit: `supports()` matrix; converter correctness on a synthetic `Frame`.
  - File-based integration: generate a tiny mp4 in `@TempDir` with `FFmpegFrameRecorder` (test-only), then run the *same grab loop* against the `file:` URI via a package-private seam (e.g. constructor/factory accepting any URI without protocol check); assert ≥N frames, BGR24, correct dimensions, monotonic sequence, clean close. This exercises the real FFmpeg path in CI.
  - A `@Disabled("needs live RTSP server")` test documenting how to run against `rtsp://localhost:8554` manually.

**Done when:** `./mvnw -B -pl adapters/adapter-rtsp test` green.

## 3. Task U3 — `adapter-publish-hls`

**Scope:** `adapters/adapter-publish-hls/src/**` (main + test). Package `com.drones.vision.adapter.publishhls`. Plain classes, no Spring.

- `MediamtxStreamPublisher implements StreamPublisherPort`:
  - Constructor: `(URI rtspPushBase, URI hlsViewBase)` e.g. `rtsp://localhost:8554`, `http://localhost:8888`.
  - `streamStarted`: register stream state; recorder is **lazily created on first `publish`** (width/height unknown until then).
  - `publish`: convert `VideoFrame` → JavaCV `Frame` (`BGR24` direct; `JPEG` → `ImageIO.read` → BGR). `FFmpegFrameRecorder(rtspPushBase + "/" + streamId.value(), w, h)`: format `rtsp`, `rtsp_transport=tcp`, videoCodec H.264 (libx264), `preset=ultrafast`, `tune=zerolatency`, gop ~2 s, frame rate from observed cadence or default 15, pixel format `AV_PIX_FMT_YUV420P`; set `recorder.setTimestamp(µs)` from `capturedAt` relative to first frame (monotonic, never backwards).
  - **Resilience (§0.3):** all recorder failures are caught inside the adapter — log (`java.lang.System.Logger` or slf4j-api if already on classpath; check the pom — do not add deps beyond U1's) at WARN once per outage, drop frames, retry (re)connect with exponential backoff (cap ~10 s). Never throw out of `publish`.
  - `streamEnded`: stop/release recorder, forget state; idempotent.
  - `viewUrl(streamId)`: `Optional.of(hlsViewBase + "/" + streamId.value() + "/index.m3u8")`.
- **Tests:** JPEG→Frame and BGR24→Frame conversion unit tests; viewUrl formatting; resilience test — publish frames with an unreachable push URL and assert no exception escapes and `streamEnded` still cleans up. If docker is available, an optional integration test (`@EnabledIf` docker present): run `bluenviron/mediamtx` container, publish ~30 synthetic frames, then assert the HLS playlist `http://…:8888/<id>/index.m3u8` becomes fetchable. Skip cleanly when docker is absent.

**Done when:** `./mvnw -B -pl adapters/adapter-publish-hls test` green (integration test may skip).

## 4. Task U4 — `vision-api` REST + demo page

**Scope:** `vision-api/src/**` (main + test). Package `com.drones.vision.api`. Depends only on domain + application (ports/use cases) + spring-web — no adapter imports (ArchUnit enforces this).

Controllers (constructor injection of use-case interfaces + `StreamPublisherPort` for `viewUrl`):

| Endpoint | Behavior |
|---|---|
| `POST /api/devices` | body `RegisterDeviceRequest{name, type, protocol, uri, options?}` → 201 `DeviceResponse` |
| `GET /api/devices` | 200 list of `DeviceResponse` |
| `POST /api/devices/{deviceId}/stream` | optional body `StartStreamRequest{confidenceThreshold?, inferenceFps?}` merged onto `PipelineConfig.defaults()` → 201 `StartStreamResponse{streamId, viewUrl?}` |
| `GET /api/streams` | 200 list `ActiveStreamResponse{streamId, deviceId, startedAt, viewUrl?}` |
| `DELETE /api/streams/{streamId}` | 204 |

- DTOs are records in `…api.dto`; mapping in controllers (KISS, no mapper lib).
- `ApiExceptionHandler` (`@RestControllerAdvice`): `IllegalArgumentException`/`UnsupportedProtocolException` → 400; `NoSuchElementException` → 404; `IllegalStateException` → 409. JSON body `{error, message}`.
- Note: `StreamService.start` signatures — read the actual application/domain sources first and match them; if "device not found"/"already streaming" surface as different exception types than assumed, map what actually exists.
- Demo page `vision-api/src/main/resources/static/index.html` (+ tiny `app.js`): device list with register form (name, type dropdown, protocol, uri), start/stop buttons per device/stream, and an HLS player (`hls.js` from CDN, with native-HLS fallback for Safari) that opens `viewUrl` of a started stream. Plain vanilla JS/CSS, one page, no build step. Banner note that this is a dev console (auth arrives in Phase 6).
- **Tests:** MockMvc standalone (`MockMvcBuilders.standaloneSetup`) with Mockito-mocked use cases: happy paths + 400/404/409 mappings + config-merge behavior.

**Done when:** `./mvnw -B -pl vision-api test` green.

## 5. Task U5 — Wiring, config, E2E verification

**Scope:** `vision-app/src/**`, `application.properties`, `README.md` (quickstart section), full-build + E2E run.

- `WiringConfiguration`: add `RtspVideoSource` bean (joins the `List<VideoSourcePort>`); publisher selection — `vision.publish.enabled` (default `true`) → `MediamtxStreamPublisher` from properties `vision.publish.mediamtx.rtsp-base` (default `rtsp://localhost:8554`) and `vision.publish.mediamtx.hls-base` (default `http://localhost:8888`); when `false` → existing `NoopStreamPublisher`. Use a `@ConfigurationProperties` record `VisionPublishProperties`.
- `application.properties`: the above defaults, commented.
- Existing tests: `SimStreamSmokeTest` and context test must stay green **without** mediamtx running (publisher resilience already guarantees this; set `vision.publish.enabled=false` in their test properties anyway for determinism — the smoke test's recording publisher is `@Primary`).
- New context test: with `vision.publish.enabled=true` (default), assert the `StreamPublisherPort` bean is the mediamtx implementation and both video sources (`sim`, `rtsp`) are registered.
- README: "Quickstart (Phase 1)" — `docker compose up -d mediamtx`, `./mvnw spring-boot:run -pl vision-app`, open `http://localhost:8080`, register a `sim` device, start stream, watch. Note where HLS latency comes from (~5–10 s with default mediamtx settings).
- **E2E verification (required if docker available, and docker IS available in this environment):**
  1. `docker compose up -d mediamtx`
  2. run the packaged app (`java -jar vision-app/target/…jar` after `./mvnw -B install -DskipTests`) in the background
  3. `curl` register sim device → start stream → poll `http://localhost:8888/<streamId>/index.m3u8` until it returns segments (≤30 s)
  4. stop stream via API, verify 204 and playlist eventually 404s
  5. tear everything down (kill app, `docker compose down`)
  Report each step's actual result.
- Full `./mvnw -B verify` at the end — everything green.

---

## Execution order

```
U1 ──▶ { U2, U3, U4 in parallel } ──▶ U5
```
