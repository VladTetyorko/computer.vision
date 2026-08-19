# adapter-publish-hls

H.264 RTSP push to a mediamtx sidecar so browsers can watch live streams via mediamtx's HLS egress.

**Depends on:** vision-domain, vision-platform (explicit, since docs/plans/active/SYSTEM-STATUS-PLAN.md wave S2 — for `SubsystemStatusPort`/`SubsystemStatus`/`Health`), org.bytedeco:javacv, org.bytedeco:ffmpeg-platform-gpl (plus JDK-only `java.net.http.HttpClient`/`com.sun.net.httpserver.HttpServer` for the mediamtx Control API client and its tests — no new Maven dependency) · **Used by:** vision-app
**Build/test:** `./mvnw -B -pl video-output/publish-hls test` — green (111 tests as of docs/plans/active/MEDIA-SOT-PLAN.md M6, see "M6: mediamtx proxy publisher + live frame grab" below; use `-am` if the local `vision-domain` artifact predates a port this module now depends on, e.g. `ReplayFrameExtractionPort`). Still 111/111 after wave S2 (`PublishStatusProvider`, see API surface/Status below) — no new test file, see that Status entry for why.

## API surface
### `com.drones.vision.adapter.publishhls`
- `final class MediamtxStreamPublisher implements StreamPublisherPort` — `MediamtxStreamPublisher(URI rtspPushBase, URI hlsViewBase, URI whepViewBase, URI playbackViewBase)`, the first three non-null, `playbackViewBase` nullable. This is now the **only** constructor (docs/plans/active/LAYERING-REFACTOR-PLAN.md E3 deleted the old 3-arg convenience overload — see "E3: layering-refactor split" below for why). `streamStarted(StreamId, Device)`, `publish(StreamId, VideoFrame)`, `streamEnded(StreamId)`, `viewUrl(StreamId): Optional<URI>` → `{hlsViewBase}/{streamId.value()}/index.m3u8`, `whepUrl(StreamId): Optional<URI>` → `{whepViewBase}/{streamId.value()}/whep` — mediamtx serves WHEP for every published RTSP path with zero extra per-stream setup, so this is pure string formatting (same trailing-slash tolerance as `viewUrl`, same `id == null` → `Optional.empty()`), not a second live session to manage; both delegate to `MediamtxUrls` (see below). **Unlike `hlsViewBase`, `whepViewBase` is never wired as an app-relative proxy path** (see `StreamPublisherPort#whepUrl`'s javadoc) — `vision-app` always hands this class mediamtx's real address, and `whepUrl` returns it to the caller verbatim.
  - **`playbackUrl(StreamId, Instant, Duration): Optional<URI>`** (docs/plans/done/OPS-CORE-PLAN.md §R — see "R-a: recording playback" below for the full writeup) → `{playbackViewBase}/get?path={streamId.value()}&start={start}&duration={roundedSeconds}` (delegates to `MediamtxPlaybackUrls`, unchanged by E3), `playbackViewBase` being a **4th, genuinely-optional (nullable) constructor argument** unlike the first three: `null` (unconfigured) or a `null`/absent `streamId` both make this return `Optional.empty()` — honest "no recording available", not an error. `start` uses `Instant#toString()` verbatim (RFC3339/ISO-8601 with trailing `Z`, exactly what mediamtx v1.19.3's playback server parses with — verified against its own `internal/playback/on_get.go` source, `time.Parse(time.RFC3339, ...)`); `duration` is rounded to the nearest whole second (`Math.round(duration.toMillis() / 1000.0)`) since callers only ever have second-granularity usage windows (docs/plans/done/OPS-CORE-PLAN.md's `AssetUsage` join) — sub-second precision would be false precision. The `path` value is exactly `streamId.value()`, the same name `viewUrl`/`whepUrl` already use — recording is keyed on the same mediamtx path every stream already publishes to, there is no separate "recording path" concept. **Never proxied**, same reasoning and same treatment as `whepUrl` (see its own note just above): mediamtx's playback origin is handed to the caller verbatim, not routed through an app-relative path — a byte-range-seekable clip fetch isn't something a simple reverse proxy adds value forwarding, so there is no `HlsProxyController`-style proxy for it.
  - Recorder creation/config is delegated to `H264RecorderFactory` (see below) — format `"rtsp"`, `rtsp_transport=tcp`, `timeout`=`CONNECT_TIMEOUT_MICROS`="5000000" (5s), codec `AV_CODEC_ID_H264`/`"libx264"`, video options `preset=veryfast`, `tune=zerolatency`, `crf=21` (`X264_CRF`) with VBV cap `maxrate=6000000`/`bufsize=12000000` (`X264_MAXRATE_BITS_PER_SECOND`/`X264_BUFSIZE_BITS`), `sc_threshold=0` (`X264_SCENECUT_THRESHOLD`, docs/plans/done/MVP2-PLAN.md V-a — see "V-a: latency" below), `setMaxBFrames(0)`, pixel format `AV_PIX_FMT_YUV420P`. **CRF rate control is deliberate**: without it `FFmpegFrameRecorder` encodes at its ~400 kbps default, which macroblocks 720p footage and the burned-in detection boxes (observed live; regression test `configureRecorderUsesCrfRateControlNotTheDefault400kbps`, now in `H264RecorderFactoryTest`); `veryfast` not `ultrafast` because at constant quality the preset trades CPU for bits and `ultrafast` needs ~2× the bitrate. Frame rate and GOP are **measured, not fixed** — measurement itself is delegated to `CadenceEstimator` (see below): the first `CADENCE_MEASUREMENT_FRAMES`=5 published frames are consumed to measure the source cadence (median of the 4 `capturedAt` deltas, clamped to [`MIN_MEASURED_FRAME_RATE_FPS`=1, `MAX_MEASURED_FRAME_RATE_FPS`=120]; degenerate deltas fall back to `DEFAULT_FRAME_RATE_FPS`=15) and are dropped, then the recorder starts with `setFrameRate(measured)` and GOP = `round(measured * GOP_SECONDS)` (`GOP_SECONDS`=1, docs/plans/done/MVP2-PLAN.md V-a — was 2, see below). A fixed 15fps assumption made any faster source play in slow motion (a 30fps clip stretched 2×) because timestamps quantize onto the recorder's frame grid — the docker IT `thirtyFpsSourcePlaysBackAtWallClockSpeedNotSlowMotion` is the regression test.
  - Sustained mid-stream drift of the source rate away from the measured one (EWMA ratio outside [1/1.5, 1.5] for `SUSTAINED_DRIFT_WINDOW`=2s, `CadenceEstimator.observeSustainedDrift`) logs one INFO per stream; the encoder is deliberately NOT restarted mid-stream (known limitation — playback speed stays off until the stream restarts).
  - Backoff (`PublishBackoff`, see below): `INITIAL_BACKOFF_MS`=500, doubles every failure, capped at `MAX_BACKOFF_MS`=10_000.
  - **docs/plans/done/MVP2-PLAN.md V-c: capture→encode lag measurement.** Every `writeFrame` (i.e. every actual `FFmpegFrameRecorder.record` call) measures `now - videoFrame.capturedAt()` (millis, primitive subtraction — no `Duration` allocation) and feeds it into the stream's `PublishDiagnostics.lagTracker` (a `LagTracker`, see below). Logged at `DEBUG` per frame (lazy `System.Logger` supplier — free when DEBUG is disabled, the default) and at `INFO` as a p50/p95 summary at most once per `LAG_LOG_INTERVAL_MILLIS`=30s per stream (`PublishDiagnostics.shouldLogLag`, mirrors the backoff fields' millis-epoch timing idiom — never fires on the very first call, which only establishes the baseline). See "V-c: latency measurement" below for the full design and how to read these logs together with the player's own behind-live estimate.
  - nested `private static final class StreamState` — per-stream mutable bookkeeping, intentionally not thread-safe (port contract guarantees no concurrent calls per `streamId`): now a thin holder for the lazy `volatile FFmpegFrameRecorder recorder` plus three collaborators, one per concern (docs/plans/active/LAYERING-REFACTOR-PLAN.md E3 split, see below): `final PublishBackoff backoff`, `final CadenceEstimator cadence`, `final PublishDiagnostics diagnostics` (all package-private fields, no getter ceremony, mirroring `recorder`'s own visibility).
  - **`public record PublishSnapshot(int total, List<StreamId> inOutage)`** / **`public PublishSnapshot streamsInOutage()`** (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2, wave S2) — `total` is the count of streams this publisher currently tracks state for; `inOutage` names exactly which of them have `StreamState.backoff.inOutage() == true`. Backs `PublishStatusProvider` (below), which needs the actual stream ids (not just a count) to name them in `Health.DEGRADED`'s `detail` per the plan's wording.
- `final class MediamtxUrls` (package-private, stateless, pure string formatting; new in E3) — every mediamtx URL this module builds *except* the playback `/get` query (which stays in `MediamtxPlaybackUrls`, unchanged): `static String pushUrl(URI, StreamId)` → `{rtspPushBase}/{streamId}`, `static String viewUrl(URI, StreamId)` → `{hlsViewBase}/{streamId}/index.m3u8`, `static String whepUrl(URI, StreamId)` → `{whepViewBase}/{streamId}/whep`. Trailing slash on the base tolerated (own private `withoutTrailingSlash`, same idiom as `MediamtxPlaybackUrls`'s).
- `final class H264RecorderFactory` (package-private, stateless; new in E3) — `static FFmpegFrameRecorder create(String pushUrl, int width, int height, double frameRateFps) throws Exception` (new + configure + `start()`, releasing on a failed start so it never leaks a native recorder) and the package-private test seam `static void configureRecorder(FFmpegFrameRecorder, double frameRateFps)` (assigns fields only, no I/O — see `H264RecorderFactoryTest`). Owns `GOP_SECONDS`, `CONNECT_TIMEOUT_MICROS`, `X264_CRF`, `X264_MAXRATE_BITS_PER_SECOND`, `X264_BUFSIZE_BITS`, `X264_SCENECUT_THRESHOLD` — moved verbatim (same values) out of `MediamtxStreamPublisher`.
- `final class PublishBackoff` (package-private, mutable, not thread-safe; new in E3) — `readyToRetry(): boolean`, `scheduleRetry()`, `beginOutage(): boolean`, `endOutage(): boolean`. Owns `INITIAL_BACKOFF_MS`=500, `MAX_BACKOFF_MS`=10_000 — moved verbatim out of `MediamtxStreamPublisher.StreamState`. `boolean inOutage()` (package-private, docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2, wave S2) — a pure read of the existing `outage` flag, no new state; backs `MediamtxStreamPublisher#streamsInOutage`.
- `final class CadenceEstimator` (package-private, mutable, not thread-safe; new in E3) — `nextTimestampMicros(Instant, double): long` (PTS bookkeeping, see Gotchas), `recordMeasurementSample(Instant): boolean`, `measuredFrameRateFps(): double`, `observeSustainedDrift(Instant): boolean`. Owns `DEFAULT_FRAME_RATE_FPS`=15.0, `CADENCE_MEASUREMENT_FRAMES`=5, `MIN_MEASURED_FRAME_RATE_FPS`=1, `MAX_MEASURED_FRAME_RATE_FPS`=120, `DRIFT_RATIO_HIGH`/`DRIFT_RATIO_LOW`, `DRIFT_EWMA_ALPHA`=0.2, `SUSTAINED_DRIFT_WINDOW`=2s — moved verbatim out of `MediamtxStreamPublisher.StreamState`. Direct unit tests: `CadenceEstimatorTest` (12, moved from `StreamStateTest`).
- `final class PublishDiagnostics` (package-private, mutable, not thread-safe; new in E3) — `final LagTracker lagTracker` (package-private field, no getter, mirrors `StreamState.recorder`'s own visibility), `shouldLogLag(long nowEpochMs): boolean`. Owns `LAG_TRACKER_WINDOW_SIZE`=150, `LAG_LOG_INTERVAL_MILLIS`=30s — moved verbatim out of `MediamtxStreamPublisher.StreamState`. Direct unit tests: `PublishDiagnosticsTest` (4, moved from `StreamStateTest`).
- `final class FrameConverter` (package-private, stateless) — two directions. **Into JavaCV** (for `MediamtxStreamPublisher`): `static Frame toFrame(VideoFrame) throws IOException` dispatches on `PixelFormat`; `static Frame bgr24ToFrame(VideoFrame)` (packed → JavaCV-padded-stride row copy); `static Frame jpegToFrame(VideoFrame) throws IOException` (`ImageIO` decode + repack as BGR). Only `BGR24` and `JPEG` are supported; anything else throws `IllegalArgumentException`. **Out of JavaCV** (for `MediamtxReplayFrameExtractor`, docs/plans/done/CV-TRAINING-V2-PLAN.md §6): `static ByteBuffer copyBgr24(Frame): ByteBuffer` — a decoded grabber `Frame` → a tightly packed heap buffer (stride padding stripped), a near-verbatim duplicate of `adapter-rtsp`'s own `FrameConverter.copyBgr24` (deliberate, see "Replay frame extraction" below and this class's own javadoc — adapters must not depend on each other).
- `final class LagTracker` (package-private, pure, no I/O) — docs/plans/done/MVP2-PLAN.md V-c. `LagTracker(int capacity)`; `void record(long lagMillis)` (`O(1)`, no allocation — drop-oldest ring buffer); `long p50()`/`long p95()` (nearest-rank percentile over a sorted defensive copy — `O(n log n)`, only meant to be called occasionally, e.g. once per periodic summary log, never per-frame); `int sampleCount()`. Returns `0` for both percentiles when empty. Constructor rejects `capacity <= 0`.
- `final class MediamtxPlaybackUrls` (package-private, stateless, pure string formatting) — the one place that knows mediamtx's playback `/get` query shape: `static String getUrl(URI playbackBase, String pathName, Instant start, long durationSeconds)` → `{playbackBase}/get?path={pathName}&start={start}&duration={durationSeconds}` (trailing-slash on `playbackBase` tolerated). Shared by `MediamtxStreamPublisher#playbackUrl` and `MediamtxReplayFrameExtractor#frameAt` — see "Replay frame extraction" below.
- `final class MediamtxReplayFrameExtractor implements ReplayFrameExtractionPort` (public; docs/plans/done/CV-TRAINING-V2-PLAN.md §6) — `MediamtxReplayFrameExtractor(URI playbackBase)`, `playbackBase` nullable (unconfigured playback ⇒ every call returns `Optional.empty()`). `frameAt(StreamId, Instant): Optional<VideoFrame>` pulls one decoded BGR24 frame out of a stream's mediamtx recording. See "Replay frame extraction" below for the full design.
- `final class MediamtxControlApi` (package-private; docs/plans/active/MEDIA-SOT-PLAN.md §5.3, M6) — thin client for mediamtx's v3 Control API, used only by `MediamtxProxyPublisher`. `MediamtxControlApi(URI apiBase, String apiUser, String apiPassword)` — credentials nullable (no `Authorization` header sent when absent, today's default). `void createOrUpdatePath(String pathName, String sourceUrl, boolean sourceOnDemand, String rtspTransport)` — `POST /v3/config/paths/add/{name}`, and on an HTTP 400 whose body is mediamtx's `"path already exists"` error, falls through to `PATCH /v3/config/paths/patch/{name}` with the same body (the idempotent-start fallback). `boolean isReady(String pathName)` — `GET /v3/paths/get/{name}`; HTTP 404 is reported as `false` (not-ready, not an error — callers poll in a loop), any other non-200 throws. `void deletePath(String pathName)` — `DELETE /v3/config/paths/delete/{name}`; HTTP 200 or 404 (already gone) both succeed (idempotent stop). Every non-2xx/404 outcome throws `MediamtxControlApiException`; an HTTP 401 gets a message that explicitly names "authentication" and the fix (configure `vision.publish.mediamtx.api-user`/`api-password`, or mount a widened `mediamtx.yml`) rather than a bare status code. Package-private test seams `static boolean bodyIndicatesPathAlreadyExists(String)` / `static boolean extractReadyField(String)` — small regex-based JSON field extraction, not a JSON library dependency (the four response shapes are fixed and version-pinned, verified against 1.19.3 by M0's transcript).
- `final class MediamtxControlApiException extends RuntimeException` (public; M6) — thrown by `MediamtxControlApi` and (for a readiness timeout) `MediamtxProxyPublisher#streamStarted`. Deliberately allowed to escape `streamStarted` (unlike this module's usual "nothing escapes" posture, see `MediamtxStreamPublisher`'s own javadoc) — a proxied start call that cannot reach a ready path must fail loud, not hand back a URL that plays nothing; `streamEnded` still catches and logs it at `WARNING`, since teardown must not block a caller.
- `public record MediamtxProxySettings(String rtspTransport, Duration readyTimeout, boolean sourceOnDemand, String apiUser, String apiPassword)` (M6) — `MediamtxProxyPublisher`'s tunables, mirrors `PublishSettings`'s "vision-app maps `application.yaml` onto this record" shape. `static defaults()` = `("automatic", 10s, false, null, null)` — D1: reproduces today's (proxy-disabled) behaviour exactly. Compact ctor requires `apiPassword` whenever `apiUser` is set (fails fast at construction, not at the first 401).
- `final class MediamtxProxyPublisher implements StreamPublisherPort` (public; docs/plans/active/MEDIA-SOT-PLAN.md D3, M6) — `MediamtxProxyPublisher(URI apiBase, URI hlsViewBase, URI whepViewBase, URI playbackViewBase, MediamtxProxySettings settings)`. `streamStarted(StreamId, Device)` creates/idempotently re-points a mediamtx path at `device.stream().uri()` via `MediamtxControlApi`, then — unless `settings.sourceOnDemand()` — polls readiness (100ms interval, not configurable) up to `settings.readyTimeout()` before returning; a path that never becomes ready throws `MediamtxControlApiException` rather than returning silently. `publish(StreamId, VideoFrame)` is an intentional no-op — mediamtx, not this JVM, holds the frames. `streamEnded(StreamId)` deletes the path and swallows `MediamtxControlApiException` (logs `WARNING`). `viewUrl`/`whepUrl`/`playbackUrl` delegate to the same `MediamtxUrls`/`MediamtxPlaybackUrls` helpers `MediamtxStreamPublisher` uses (D2: the path name is `streamId.value()` regardless of which publisher created it). `proxiesSource(Device)` always returns `true`.
- `final class PublisherRouter implements StreamPublisherPort` (public; docs/plans/active/MEDIA-SOT-PLAN.md §3 switch A, M6) — `PublisherRouter(StreamPublisherPort directPublisher, StreamPublisherPort proxyPublisher, boolean sourceProxyEnabled)`. Routes to `proxyPublisher` iff `sourceProxyEnabled && device.stream().protocol().equals("rtsp")`, else `directPublisher` — the one `StreamPublisherPort` `vision-app` wires; neither `StreamPipeline` nor `DefaultStreamService` needs to know two publishers exist. `proxiesSource(Device)` evaluates the same rule (pure, no side effect — the application layer calls it *before* `streamStarted`). `streamStarted` remembers which publisher a `StreamId` routed to (a `ConcurrentHashMap`) so later calls carrying only a `StreamId` (`publish`/`streamEnded`/the URL methods) stay on the same publisher; an unrouted `StreamId` falls back to `directPublisher`.
- `final class PublishStatusProvider implements SubsystemStatusPort` (`vision-platform`, docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2, **new**, wave S2) — `video-publish`'s health self-report backing `GET /api/system/status`. Constructor takes the **concrete** `MediamtxStreamPublisher` directly, not the `StreamPublisherPort` interface — `streamsInOutage()` isn't on that port, and no other `StreamPublisherPort` implementation in this module (`MediamtxProxyPublisher`, `PublisherRouter`) tracks per-stream outage state the same way, so there is nothing generic to abstract yet. `total == 0` or no stream in outage → `Health.OK`; otherwise **always `Health.DEGRADED`, never `Health.DOWN`** (a deliberate plan call, §4.2 — losing the publish path for a stream is degraded video quality/availability for that one stream, not a platform-wide outage) — `detail` names the actual `StreamId`s in outage, joined by `", "`.
- `final class MediamtxLiveFrameGrabber` (public; docs/plans/active/MEDIA-SOT-PLAN.md M6) — `MediamtxLiveFrameGrabber(URI rtspBase)` / `MediamtxLiveFrameGrabber(URI rtspBase, Duration connectTimeout, Duration readTimeout)`. `grab(StreamId): Optional<VideoFrame>` opens `{rtspBase}/{streamId}` as an RTSP **read** client (the same address/path `MediamtxStreamPublisher` pushes to, and the same address a proxied path is reachable at) and decodes exactly one frame — `grabImage()` not `grab()`, `rtsp_transport=tcp` (fixed, not configurable — reliability matters more than latency for a one-shot grab), BGR24, `FrameConverter.copyBgr24`. Never throws; an unreachable/malformed base or no decodable video returns `Optional.empty()`, logged once at `WARNING`. `capturedAt` is stamped at grab time (`Instant.now()`), not derived from the RTSP PTS — a live grab has no RTCP-anchored wallclock without lower-level access neither this class nor `cv2`-equivalent grabbing provides (docs/plans/active/MEDIA-SOT-PLAN.md §6). Exists to fill the gap proxy mode opens: nothing in the JVM decodes a proxied stream's video, so `StreamPipeline`'s own cached `latestFrame`/`latestRawFrame` (vision-application) would otherwise sit empty for a proxied stream — **not yet wired into `StreamService`**, that is a future wave's job (out of this module's own scope), this class only provides the capability. Reuses `MediamtxStreamPublisher.ensureQuietLogging()` and `MediamtxUrls.pushUrl` (as a read address, not a push one) — no new machinery duplicated.

## Conventions
- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- **`System.Logger`, not slf4j** — `private static final System.Logger LOG = System.getLogger(...)`.
- `FFmpegFrameRecorder` is created lazily on the first `publish()` call for a stream, once frame dimensions are known — not in `streamStarted`.
- Different `StreamId`s are tracked independently in a `ConcurrentHashMap`; per-stream state needs no locking because the port contract serializes calls per stream.

## Gotchas
- **Native FFmpeg log level pinned to ERROR** (was WARNING; raised because swscale emits a benign per-frame `deprecated pixel format used` warning for yuvj-tagged inputs that JavaCV cannot suppress per-context). `MediamtxStreamPublisher`'s constructor calls the idempotent package-private `static void ensureQuietLogging()`, which calls `avutil.av_log_set_level(avutil.AV_LOG_ERROR)` once per JVM (a `synchronized` static-boolean guard makes every call after the first a no-op). Without this, every `FFmpegFrameRecorder.start()`/`stop()` dumps AV_LOG_INFO noise straight to stdout/stderr — the libx264 config banner, the muxer's `Output #0 ...` header, and per-stream encoding stats on stop — easily mistaken for errors when interleaved with application logs. Deliberately duplicated (not shared) in `adapter-rtsp`'s `RtspVideoSource` — adapters must not depend on each other (`CLAUDE.md`'s dependency rule), so this ~5-line block is copy-pasted rather than factored into a shared utility.
  - **Chosen: plain `av_log_set_level(AV_LOG_WARNING)`, not `org.bytedeco.javacv.FFmpegLogCallback.set()`.** Decided from an empirical check (a standalone `FFmpegFrameRecorder` harness against this module's pinned FFmpeg 6.1.1/JavaCV 1.5.10, plus the real `MediamtxDockerIntegrationTest`), not from documentation alone:
    - The callback does **no level filtering of its own** — it still requires the exact same `av_log_set_level(WARNING)` call to suppress anything (verified: `FFmpegLogCallback.set()` alone, with the default `AV_LOG_INFO` threshold untouched, let the full x264 banner through, just reformatted).
    - Once active, the callback **fragments FFmpeg's own multi-part log lines** (e.g. the muxer's `Output #0 ...` block, which FFmpeg emits as several `av_log()` calls that the *default* callback buffers into coherent lines) into a stream of separately-prefixed partial fragments — objectively worse output, not better.
    - Its default backend (`org.bytedeco.javacpp.tools.Logger`) writes straight to `System.err` with an `"Info: "/"Warning: "` prefix by default — it only becomes slf4j-backed if the `org.bytedeco.javacpp.logger=slf4j` system property is set, which this codebase does not set. There is also no JUL-to-Spring bridge configured anywhere in this codebase, and this class already logs via `System.Logger`, not `java.util.logging` — so routing native logs through a third, differently-formatted channel bought no integration benefit.
    - No deadlock was observed in the empirical check, but the callback's added complexity (fragmented output, no filtering benefit, no format integration) wasn't worth the risk described in JavaCV's own issue tracker. Plain `av_log_set_level` keeps FFmpeg's coherent default formatting and only changes the threshold — genuine warnings/errors (e.g. `[tcp @ ...] Connection ... failed`, observed verbatim on stderr from `MediamtxStreamPublisherTest`'s unreachable-mediamtx test) still print exactly as before; only INFO/VERBOSE/DEBUG/TRACE are silenced. The Java-side WARN-once-per-outage logging (`PublishBackoff.beginOutage()`, `System.Logger`) is a separate channel entirely and is unaffected either way.
- **Nothing escapes.** `publish`/`streamStarted`/`streamEnded` swallow every exception from JavaCV/FFmpeg/frame-conversion — a broken or absent mediamtx must never take down the owning pipeline. Failures are logged at `WARNING` **once per outage** (`PublishBackoff.beginOutage()` gates it, via `StreamState.backoff`), not once per dropped frame; recovery logs once at `INFO` (`endOutage()`).
- **PTS quantization bug and its fix**: `FFmpegFrameRecorder.setTimestamp(long)` doesn't use the microsecond value verbatim — it rounds down to a whole frame number via `round(timestampMicros * frameRateFps / 1_000_000)` before handing that integer PTS/DTS to the muxer. Two frames whose `capturedAt` land under one frame period apart (routine with bursty `SubmissionPublisher` delivery, e.g. a scheduler catching up after a stall) used to round to the *same* frame number → the muxer rejects the second write with `av_interleaved_write_frame() error -22` (EINVAL, non-monotonic/duplicate DTS). Bumping the microsecond value by 1 does **not** fix it — still rounds to the same frame number. The actual fix in `CadenceEstimator.nextTimestampMicros` (moved from `StreamState` in docs/plans/active/LAYERING-REFACTOR-PLAN.md E3, same code): track the last *frame number* actually emitted (`lastFrameNumber`); if the naturally-computed frame number would collide (`<= lastFrameNumber`), advance to `lastFrameNumber + 1` and convert that back to microseconds (`round(frameNumber * 1_000_000 / fps)`) before returning — frame numbers are always strictly increasing, so they never collide regardless of burst timing.
- **No epoch reset on reconnect**: `firstCapturedAt`/`lastFrameNumber` live on `StreamState.cadence` (a `CadenceEstimator`) and are set once, lazily, on the *first* `nextTimestampMicros` call ever made for that instance. `onPublishFailed` nulls out `state.recorder` on a failed publish/reconnect but does **not** reset those fields — only `streamStarted` (which installs a brand-new `StreamState`, and therefore a brand-new `CadenceEstimator`) resets the PTS epoch. A reconnect mid-stream therefore keeps counting frame numbers from the original epoch, which is exactly what keeps the new recorder's PTS monotonic relative to what mediamtx already received before the drop.
- `viewUrl` strips any trailing slash from `hlsViewBase` before appending — don't double up `//`.
- **`whepUrl` is pure string formatting — this class does nothing to make WHEP actually reachable.** mediamtx's own defaults serve WHEP signaling on `:8889` and gather WebRTC/ICE media candidates on UDP `:8189` (`webrtcLocalUDPAddress`), with `webrtcIPsFromInterfaces: true` — meaning a containerized mediamtx (docker-compose.yml, owned by `vision-app`) advertises its *own* container-network IP in ICE candidates by default, which a browser on the host cannot reach. Getting a real browser WHEP session working through Docker needs both a 1:1 (not remapped) host↔container UDP port mapping for the ICE port and `MTX_WEBRTCADDITIONALHOSTS` (mediamtx env var) set to an address the browser can reach (e.g. `127.0.0.1` for same-host demo use) — see `docker-compose.yml`'s comments and `station/vision-app/MODULE.md`'s WHEP section for the actual wiring and its localhost-only limitation. None of that is this module's concern or tested here: this class only formats a URL string from whatever base it's given, and its own tests (`whepUrl*` in `MediamtxStreamPublisherTest`) verify only that formatting, not end-to-end WebRTC reachability.
- **mediamtx's CORS default is permissive** (`webrtcAllowOrigins: ["*"]`, verified against `mediamtx.yml`'s upstream default at the time this feature was written) — a browser can call a WHEP endpoint cross-origin (e.g. from `vision-web`'s own origin to mediamtx's `:18889`) without this module or `vision-app` needing to add any CORS handling of its own, unlike HLS's proxy (`HlsProxyController`, vision-api), which exists to dodge browser-reachability/port issues, not a CORS problem.
- **`playbackUrl` is never proxied, same as `whepUrl`** (docs/plans/done/OPS-CORE-PLAN.md §R): the URL it returns points straight at mediamtx's own playback server address, not an app-relative path. A `GET /get?...` response is a byte-range-seekable MP4 clip — the same kind of payload `HlsProxyController` already exists to work around browser-reachability/port issues for HLS segments, but there's no equivalent proxy for playback, so whatever `playbackViewBase` this class is constructed with must already be reachable from the *viewer's* browser, not just this app's own JVM (same requirement as `whepViewBase`).
- **`hlsViewBase` may be a relative URI**, e.g. `URI.create("/hls")` — this is exactly how `vision-app` wires it as of the HLS-proxy feature (`VisionPublishProperties#viewBase()`, default `/hls`, routed through `com.drones.vision.api.proxy.HlsProxyController`): `URI.create` and the string-concatenation formatting `viewUrl` uses both work identically for a relative reference as for an absolute one, so `viewUrl(id)` returns `/hls/<id>/index.m3u8`, no production-code change needed. See `viewUrlSupportsRelativeHlsViewBaseForAppProxiedUrls` in `MediamtxStreamPublisherTest`.
- **docs/plans/done/MVP2-PLAN.md V-a — GOP must track mediamtx's `hlsSegmentDuration` by hand.** `GOP_SECONDS`=1 (was 2) is a Java-side constant; `docker-compose.yml`'s `MTX_HLSSEGMENTDURATION` is a separate, independent YAML/env value on the mediamtx side. Nothing enforces they stay equal — an HLS segment can never be *shorter* than the keyframe interval it's cut on (mediamtx cuts at the first keyframe at-or-after its configured segment duration), so if `GOP_SECONDS` is ever raised again without also raising `MTX_HLSSEGMENTDURATION` (or vice versa lowered), segments silently widen back out to the GOP's duration regardless of what mediamtx is configured to want. If either changes, change both, and re-read this file's "V-a: latency" section below.
- **mediamtx's own defaults already were Low-Latency HLS** before this task added anything: verified against the actual `bluenviron/mediamtx:latest` image pulled in this environment (`v1.19.2`, confirmed via `docker run ... --version` and by extracting its baked-in `/mediamtx.yml`) — `hlsVariant: lowLatency`, `hlsSegmentDuration: 1s`, `hlsPartDuration: 200ms`, `hlsSegmentCount: 7`, exactly matching upstream's `internal/conf/conf.go` defaults on `bluenviron/mediamtx`'s `main` branch at the time of writing. The compose `MTX_HLS*` env vars added by this task (see docker-compose.yml) don't change today's behavior — they pin it, so a future image update changing these defaults can't silently regress this stack back to multi-second HLS. **Env var naming, verified from mediamtx's own source** (`internal/conf/env/env.go`): `Load("MTX", conf)` builds each var as `MTX_` + the struct field's `json` tag uppercased with no other transformation — i.e. `hlsVariant`'s tag `hlsVariant` becomes `MTX_HLSVARIANT`, not e.g. `MTX_HLS_VARIANT`; same rule already visible in this compose file's pre-existing `MTX_WEBRTCADDITIONALHOSTS` (from `webrtcAdditionalHosts`).
- **`MTX_HLSSEGMENTCOUNT` has a hard floor of 7 under `lowLatency`.** Verified against `gohlslib` (the library mediamtx's HLS server is built on top of), `Muxer.Start()`: `case MuxerVariantLowLatency: if m.SegmentCount < 7 { return fmt.Errorf(...) }`. This isn't a suggestion — a per-path HLS muxer instance fails to start at all below 7 once `lowLatency` is selected. Don't "optimize" segment count down to shrink the live buffer window; it doesn't reduce latency (stock hls.js's own `liveSyncDurationCount`, default 3, governs how far behind the live edge it buffers, independent of how many total segments the playlist happens to retain) and it will break the stream instead.

## V-a: latency (docs/plans/done/MVP2-PLAN.md §V, user directive 2026-07-23)

**What changed, mechanically:**
1. `GOP_SECONDS` 2 → 1 (`configureRecorder`): the encoder's keyframe interval now matches mediamtx's 1s `hlsSegmentDuration` exactly, instead of forcing every segment to be at least 2s regardless of mediamtx's own configuration — this was the single largest contributor to the pre-fix multi-second lag.
2. `sc_threshold=0` (new `X264_SCENECUT_THRESHOLD`): disables x264's adaptive scene-cut keyframe insertion (`veryfast`'s inherited default, threshold 40) so the GOP is closed and strictly periodic — segment boundaries land at predictable, uniform ~1s intervals instead of being perturbed early by scene changes in the source footage.
3. `setMaxBFrames(0)` (redundant with `tune=zerolatency`, added anyway as explicit, independently-testable documentation): `tune=zerolatency` was already present before this task and, verified against x264's own source (`x264.c`'s tune-option help text), already expands to `--bframes 0 --no-mbtree --sync-lookahead 0 --rc-lookahead 0 --force-cfr` — zero B-frame reordering delay and zero rate-control lookahead buffering were **already true**, just not independently asserted by a test. `maxrate`/`bufsize` (the CRF fix, commit a963521) are deliberately untouched — VBV `bufsize` bounds instantaneous bitrate *variance* for the rate controller, it is not a frame-reordering/output-delay buffer, so it doesn't regress latency; quality (CRF 21) is unchanged.
4. `docker-compose.yml`'s `mediamtx` service gained `MTX_HLSVARIANT=lowLatency`, `MTX_HLSSEGMENTDURATION=1s`, `MTX_HLSPARTDURATION=200ms`, `MTX_HLSSEGMENTCOUNT=7` — pinning mediamtx's own already-equal defaults (see Gotchas) so they can't silently drift.
5. `HlsProxyController` (vision-api) now forwards the upstream `Cache-Control` header instead of silently dropping it — see station/vision-api/MODULE.md for the full proxy-audit writeup; summarized under "Proxy audit" below since it's this task's finding, even though the file lives outside this module.

**Proxy audit (vision-api's `HlsProxyController`), verdict: mostly clean, one real gap fixed.**
- **Not full-body buffering in a way that matters**: it does buffer the whole response (`HttpResponse.BodyHandlers.ofByteArray()`) before writing it back, rather than truly streaming byte-for-byte — but HLS playlists are tiny (sub-KB) and, post-V-a, 1s fMP4 segments at a 6 Mbps VBV cap top out around 750 KB — buffering that adds single-digit milliseconds, nowhere near the multi-second problem this task solves. Left as-is: this was already a deliberate, documented tradeoff (see the class's own javadoc), not a regression, and rewriting it as a true streaming proxy is out of this task's scope (nothing was "broken" here).
- **Caching: was silently dropping mediamtx's own `Cache-Control`, not adding one of its own.** The literal audit criterion ("adds no caching to live playlists") was technically already satisfied — the controller never *set* a caching header itself — but it also never *forwarded* the one mediamtx sends (`no-cache` on every LL-HLS live media playlist response, verified against `gohlslib`'s `muxerStream.mediaPlaylistMaxAge()`). Relying on "browsers don't usually cache a response with zero cache/validator headers at all" instead of an explicit `no-cache` is exactly the kind of accidental-not-guaranteed behavior this audit exists to close — a stock (pre-V-b, non-`lowLatencyMode`) hls.js re-polls the *same* `index.m3u8` URL on a timer, which a spec-compliant browser cache is legally allowed to serve stale without an explicit no-cache directive, silently freezing the live edge. **Fixed**: `Cache-Control` is now forwarded verbatim alongside `Content-Type` (same pattern, same file). Query strings (LL-HLS's `_HLS_msn`/`_HLS_part`/`_HLS_skip` blocking-reload protocol) were already forwarded correctly via `request.getQueryString()` — added a regression test (`llHlsBlockingReloadQueryParametersAreForwardedUntouched`) since nothing previously asserted it explicitly.
- **Does not defeat LL-HLS's blocking reload.** `buildUpstreamUri` already passed the raw query string through untouched (no fix needed, just verified + tested, see above). `HlsProxyController`'s own `REQUEST_TIMEOUT`=15s is a hard cap on how long a single proxied fetch can take; mediamtx's own blocking-wait (`gohlslib`'s `muxerStream.handleMediaPlaylist`, a `sync.Cond.Wait()` loop) has no independent timeout of its own beyond the initial "too-far-ahead" 400 check, so a genuinely stalled stream could in principle hold a blocking request open past 15s — the proxy would then surface that as a 502 rather than hanging the browser forever, a reasonable failure mode, not a bug. Not relevant to *today's* player (stock hls.js doesn't send `_HLS_msn` at all pre-V-b, so no blocking requests are made yet) — flagged here for V-b's benefit: if V-b's `liveSyncDuration`/live-edge tuning ever needs a longer blocking wait than 15s, this timeout is the place to look.

**Honest latency numbers per path, and how they were estimated** (no packet-capture tooling in this environment — these are component-additive estimates from each stage's own configured/observed timing, not an end-to-end stopwatch measurement against a real browser):

| Path | Estimated glass-to-glass | How estimated |
|---|---|---|
| **WHEP** | Sub-second (~200–500ms), unaffected by this task | Unchanged by V-a (no encoder/mediamtx-HLS-specific setting touched affects the WebRTC egress path at all — WHEP takes frames from the same RTP/media pipeline before HLS muxing). Estimate carried over from L-a's own reasoning: WebRTC's normal LAN/localhost glass-to-glass range for a live encode, no HLS segmenting/playlisting involved. |
| **LL-HLS (post-V-a, a LL-HLS-aware player — V-b, not yet shipped)** | ~1–1.5s | Sum of: ≤1 GOP (1s, worst case — the encoder buffers nothing internally per the zerolatency/no-B-frame audit above, so a frame reaches the muxer boundary within one GOP of being captured) + 1 part interval (200ms, LL-HLS's actual delivery granularity once a part-aware player fast-follows `#EXT-X-PART`) + network/proxy overhead (proxy audit above: buffering is sub-10ms at these payload sizes; LAN/localhost RTT is single-digit ms). **Caveat, stated honestly**: this number assumes a player that speaks LL-HLS's blocking-reload protocol, which V-b (not yet done) is what wires up in this codebase's `hls.js` usage — today's player gets the *stock HLS* number below despite mediamtx already serving LL-HLS-capable playlists. |
| **Stock/legacy HLS (today's actual player behavior, pre-V-b)** | ~2–3s | Sum of: 1 GOP/segment (1s, was 2s pre-V-a) + stock hls.js's live-sync buffering (`liveSyncDurationCount` default 3 segments-worth of playlist lag before it starts playing, not yet tuned down — that's V-b) ≈ another 1–2s + proxy/network overhead (sub-100ms). This is the number the "Done when" criterion in docs/plans/done/MVP2-PLAN.md V-a (~1–2s) is graded against for *this task's own scope* (encoder+mediamtx+proxy only, no player change) — landing at the low end of "multi-second" rather than the pre-fix 5–15s, with the full ~1–2s target reachable only once V-b also tunes the player side. |
| **Stock/legacy HLS, pre-V-a (for comparison)** | ~5–10s (README's own Quickstart estimate, and docs/plans/done/MVP2-PLAN.md's "5–15s typical") | 2s GOP/segment (old `GOP_SECONDS`=2) × up to `hlsSegmentCount`=7 segments' worth of playlist history a client *could* buffer into, though realistically closer to 3 segments × 2s = 6s for hls.js's actual default live-sync depth, plus the encoder's larger effective segment granularity before mediamtx could cut a new one. |

**What V-a did NOT change, on purpose**: `X264_CRF`/`X264_MAXRATE_BITS_PER_SECOND`/`X264_BUFSIZE_BITS` (commit a963521's fix — regressing these was an explicit non-goal), `preset=veryfast` (already latency-appropriate), the RTSP push transport/timeout, and anything in `vision-web`'s player (out of this task's scope — see V-b in docs/plans/done/MVP2-PLAN.md).

**Host-mode equivalents for a non-compose mediamtx** (running the bare `mediamtx` binary, or `docker run bluenviron/mediamtx` directly, outside this repo's `docker-compose.yml` entirely): **no flags or config-file edits are needed to match this task's compose settings** — mediamtx's own shipped defaults (verified against the real `v1.19.2` image, see Gotchas above) are already `hlsVariant: lowLatency`, `hlsSegmentDuration: 1s`, `hlsPartDuration: 200ms`, `hlsSegmentCount: 7`, i.e. identical to the `MTX_HLS*` env vars this task added to `docker-compose.yml`. Those env vars exist purely to *pin* today's defaults against future drift (see Gotchas), not to opt into behavior a host-mode user would otherwise miss — unlike WHEP's `MTX_WEBRTCADDITIONALHOSTS` (L-a), which genuinely does need a real, host-specific value to work at all through Docker's network isolation, there is no equivalent gap here. A host-mode user who wants to *deviate* (e.g. force legacy MPEG-TS-style HLS for an old client) sets `mediamtx.yml`'s `hlsVariant: mpegts` (or the `MTX_HLSVARIANT=mpegts` env var) themselves; nothing in this codebase requires that.

## V-c: latency measurement (docs/plans/done/MVP2-PLAN.md §V-c)

**What it measures.** `MediamtxStreamPublisher.writeFrame` — the one place every frame is actually handed to `FFmpegFrameRecorder.record` — computes `now - videoFrame.capturedAt()` (millis) right before that call. This is **capture→encode lag**: everything upstream of the encoder handoff (source capture, ingest decode — including adapter-rtsp's own V-c demuxer tuning below, since a slower/jitterier RTSP connect or decode shows up here — `StreamPipeline`, overlay burn-in, and this class's own measurement/backoff bookkeeping). It is deliberately **not** end-to-end glass-to-glass latency: everything downstream of the encoder (mediamtx's own segment/part cadence, HLS or WHEP transport, the browser player's buffering) is invisible to this number by construction.

**How it's tracked.** Each `StreamState` (one per active stream) owns one `PublishDiagnostics`, which in turn owns one `LagTracker` (docs/plans/active/LAYERING-REFACTOR-PLAN.md E3 moved the field from `StreamState` directly to `PublishDiagnostics`, same object graph shape one level deeper) — a fixed-capacity (`LAG_TRACKER_WINDOW_SIZE`=150) drop-oldest ring buffer of recent lag samples in milliseconds. `record()` is an `O(1)` array write with no allocation, so it costs nothing measurable per frame; `p50()`/`p95()` sort a defensive copy (`O(n log n)`) and are only ever called from the periodic summary path below, never per-frame.

**Logging.**
- **DEBUG, per frame**: `"Stream <id>: capture→encode lag <N>ms (frame <seq>)"`. Uses `System.Logger`'s lazy-`Supplier` overload (the same idiom this class already uses for its INFO/WARNING logs), so the message is never built when DEBUG is disabled (the default) — zero string-formatting overhead in the common case.
- **INFO, at most once per 30s per stream** (`LAG_LOG_INTERVAL_MILLIS`, gated by `PublishDiagnostics.shouldLogLag`, via `StreamState.diagnostics`): `"Stream <id>: capture→encode lag p50/p95 ~<X>/<Y>ms (n=<count>)"`. The very first call only establishes the 30s baseline and never logs — a single-sample "p50/p95" immediately at stream start would be noise, not signal.

**Reading these logs together with the player's behind-live chip (V-b).** V-b's `ui/live-edge-logic.ts` surfaces a "…s behind" chip on the live tile, estimated from hls.js's own live-edge/buffer state — that number is the *browser's* view of total glass-to-glass lag (capture→encode→mediamtx→transport→player buffer, all of it, from the client's clock). This module's own INFO line is only the *capture→encode* slice of that same span, measured at the source (this process's clock, not the browser's — the two are not directly subtractable across a network unless clocks are synced, e.g. NTP). Used together as an **order-of-magnitude split**, not an exact decomposition: if this module's p50/p95 sits in the tens of milliseconds (typical for a healthy local pipeline) while the chip reports multiple seconds behind, the lag is downstream — mediamtx segmenting/HLS transport/player buffering, i.e. V-a/V-b's own territory — not this module's encode handoff. Conversely, if this module's own p50/p95 climbs into the hundreds of milliseconds or seconds, the bottleneck is upstream of the encoder (a slow/jittery RTSP source — see adapter-rtsp's own V-c section — an overloaded pipeline, or overlay burn-in), and no amount of mediamtx/player tuning will fix it.

**Overhead.** No packet-capture or wall-clock stopwatch tooling was used to validate the *absolute* lag numbers this produces in this environment (same honest caveat as V-a's own latency table) — what's verified here is the tracker's own math (`LagTrackerTest`, hand-computed percentiles) and its wiring (`PublishDiagnosticsTest`'s `shouldLogLag` gating tests, moved from `StreamStateTest` in docs/plans/active/LAYERING-REFACTOR-PLAN.md E3), not a live end-to-end capture-to-log-line stopwatch run.

## R-a: recording playback (docs/plans/done/OPS-CORE-PLAN.md §R)

**Design: mediamtx does the recording, this class only formats a URL.** No new encoding/muxing/disk-writing code was added here — mediamtx's own native recorder (enabled via `docker-compose.yml`'s `MTX_PATHDEFAULTS_RECORD=yes`) segments every published RTSP path to fMP4 on disk by itself, independent of this class. `MediamtxStreamPublisher.playbackUrl` is pure string formatting against mediamtx's separate playback HTTP server (`MTX_PLAYBACK=yes`), exactly mirroring how `whepUrl` is pure string formatting against mediamtx's WebRTC signaling — see the API surface entry above for the exact URL shape and rounding/path-naming rules.

**mediamtx env var names, verified (not guessed) against mediamtx v1.19.3's own Go source**, since this codebase's own convention (see the `MTX_HLS*` note elsewhere in this file) is "verify against source, don't assume a naming pattern holds":
- `internal/conf/env/env.go`'s `Load`/`loadEnvInternal` builds every struct field's env var as `"MTX_" + <parent prefix>_ + uppercase(json tag)`, recursing into nested structs the same way — confirmed by reading the function directly (`case reflect.Struct: ... prefix+"_"+strings.ToUpper(strings.TrimSuffix(jsonTag, ",omitempty"))`).
- `internal/conf/conf.go`'s top-level `Conf` struct has `PathDefaults Path \`json:"pathDefaults"\`` (→ prefix `MTX_PATHDEFAULTS`) and top-level `Playback bool \`json:"playback"\``/`PlaybackAddress string \`json:"playbackAddress"\`` (→ `MTX_PLAYBACK`/`MTX_PLAYBACKADDRESS` directly, no nesting).
- `internal/conf/path.go`'s per-path `Path` struct (used as `PathDefaults`'s type) has `Record bool \`json:"record"\`` and `RecordDeleteAfter Duration \`json:"recordDeleteAfter"\`` → **`MTX_PATHDEFAULTS_RECORD`** / **`MTX_PATHDEFAULTS_RECORDDELETEAFTER`** (not `MTX_RECORD*`, which is a separate *deprecated*, non-path-scoped alias in `conf.go` that logs a warning and forwards into `PathDefaults` anyway).
- mediamtx's own default `PlaybackAddress` is already `:9996` (`conf.go`'s `setDefaults()`) and default `RecordFormat` is already fMP4 (`RecordFormatFMP4`, `path.go`'s `setDefaults()`) — both set explicitly in `docker-compose.yml` anyway, pinning today's defaults against future drift, the same rationale as the pre-existing `MTX_HLS*` block in that file.
- `Duration`'s `UnmarshalEnv` (`internal/conf/duration.go`) delegates to `time.ParseDuration` (with an added day-suffix extension this task doesn't use) — `"72h"` parses as exactly 72 hours, confirmed by reading `unmarshalInternal`.
- The playback `/get` endpoint's `duration` query param (`internal/playback/on_get.go`'s `parseDuration`) tries `strconv.ParseFloat` first (plain seconds, what this class sends) before falling back to `time.ParseDuration`-style strings — whole-second integers parse cleanly as floats.
- The default `recordPath` (`./recordings/%path/%Y-%m-%d_%H-%M-%S-%f`, `path.go`'s `setDefaults()`) is relative; the `bluenviron/mediamtx` image (`docker/standard.Dockerfile` upstream, `FROM scratch`, no `WORKDIR`) runs with cwd `/` (confirmed via `docker inspect <container> --format '{{.Config.WorkingDir}}'` → `/`), so it resolves to `/recordings/...` inside the container with **no `MTX_PATHDEFAULTS_RECORDPATH` override needed** — `docker-compose.yml`'s `mediamtx-recordings` named volume mounts exactly there.

**Manually verified end-to-end before writing any Java/compose code** (not just read from source): ran `bluenviron/mediamtx:1.19.3` directly via `docker run` with exactly the four env vars above, pushed an 8s synthetic H.264/RTSP feed via a plain `ffmpeg` CLI push, let it finish (clean RTSP disconnect), and confirmed (a) a `.mp4` segment appeared under the container's `/recordings/<path>/` within a few seconds of disconnect, and (b) `GET :playbackPort/get?path=<path>&start=<RFC3339>&duration=<n>` returned `200 video/mp4` with an `ftyp` box at byte offset 4 — i.e. the exact request shape `playbackUrl` builds. Also confirmed `docker compose config` validates cleanly against the updated `docker-compose.yml`.

**Recording finalization is not instantaneous — it happens on path unpublish, not on a fixed segment boundary.** mediamtx's own default `recordSegmentDuration` is 1 hour, so a short recording only becomes visible/servable once its *path* is unpublished (the RTSP push disconnects), which is exactly what `MediamtxStreamPublisher#streamEnded` does (`FFmpegFrameRecorder.stop()` closes the RTSP connection cleanly). Callers — and this module's own docker IT — must call `streamEnded` (or otherwise let the stream naturally end) before a just-published clip becomes fetchable via `playbackUrl`; this is a mediamtx behavior, not something this class works around.

**Constructor change and the `vision-app` compatibility shim.** `playbackViewBase` was added as a 4th constructor argument. Per this class's own MODULE.md convention (see `whepViewBase`'s own note above), new-but-always-available config is normally added by updating every call site, not by overloading — but `vision-app`'s `WiringConfiguration`/`VisionPublishProperties` (the one production call site) was out of this task's file scope (`video-output/publish-hls/**` + root `docker-compose.yml` only) to edit. A 3-arg convenience constructor was kept instead (see API surface above) that derives a default playback base from `whepViewBase`'s host at port 19996, so `vision-app` compiles and runs unchanged, with recording playback already reachable against this stack's own compose file.

**Follow-up status — CLOSED as of docs/plans/active/LAYERING-REFACTOR-PLAN.md E3.** `vision-app`'s Wave W6 (docs/plans/done/CV-TRAINING-V2-PLAN.md §6/§7) has since landed: `VisionPublishProperties.Mediamtx` now carries a `playbackBase` field (`@DefaultValue("http://localhost:19996")`, `station/vision-app/src/main/java/com/drones/vision/app/config/properties/VisionPublishProperties.java` — moved here by docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D) and `PublishWiring#streamPublisherPort` calls this class's 4-arg constructor with that property's value, verified by `vision-app`'s own `PublishWiringTest`. With the real wiring in place, the E3 wave confirmed the old **3-arg convenience constructor and its `DEFAULT_PLAYBACK_PORT`/`derivePlaybackViewBase` derivation were no longer called from anywhere in production** (only from this module's own tests, for brevity) and deleted all three as dead weight — see "E3: layering-refactor split" below. `MediamtxStreamPublisher` now has a single, 4-arg constructor; a caller with no playback configured passes `null` explicitly, same as `MediamtxReplayFrameExtractor` always required.

**Tests.** `MediamtxStreamPublisherTest` gained 8 new unit tests (`playbackUrl*`): URL construction against a configured base, trailing-slash tolerance, `null`-id → empty, unconfigured (`null` base) → empty, whole-second duration rounding (`1499ms→1`, `1500ms→2`, `2500ms→3`), path-name consistency with `viewUrl`/`whepUrl`, the 3-arg convenience constructor's host-derived default, and its `null`-when-no-host fallback. `MediamtxDockerIntegrationTest` gained `recordedStreamIsFetchableAsMp4ThroughPlaybackUrl` (docker-gated, pinned to `bluenviron/mediamtx:1.19.3` — the exact `docker-compose.yml`-pinned tag, not this class's other tests' `:latest` — since this test validates stack-specific record/playback config against the real version this app ships with): starts mediamtx with the same four record/playback env vars as `docker-compose.yml`, publishes a 4s synthetic feed through `MediamtxStreamPublisher`, calls `streamEnded`, polls `playbackUrl`'s own URL (up to 30s) until it returns a non-empty `200`, and asserts an `ftyp` box at byte offset 4.

## Replay frame extraction (docs/plans/done/CV-TRAINING-V2-PLAN.md §6, "W4")

**What it's for.** CV-TRAINING-V2-PLAN's *"capture a training frame from a recorded replay"* feature
(operator scrubs a finished usage's recording, taps "add to dataset") needs one decoded frame at an
arbitrary instant out of mediamtx's recording — the new `ReplayFrameExtractionPort` (`vision-domain`,
Wave W1). `MediamtxReplayFrameExtractor` implements it.

**Seek is delegated to mediamtx, not to ffmpeg — this is the central design decision.** `frameAt`
requests a **one-second window starting at `at`** from mediamtx's playback server —
`MediamtxPlaybackUrls.getUrl(playbackBase, streamId.value(), at, 1)` → `{playbackBase}/get?path=
{streamId}&start={at}&duration=1` — so the MP4 clip mediamtx returns already begins (at mediamtx's
own segment granularity) where we want, and at most ~1s of video is ever decoded. This class never
opens the whole recording and never performs an ffmpeg-side `setTimestamp` seek across a long file —
seeking a large HTTP-served file via ffmpeg would mean either a slow linear scan or byte-range
requests the mediamtx playback server may not even support for an in-progress-write file; asking
mediamtx for a pre-cut window sidesteps the question entirely.

**Shared URL helper.** `MediamtxPlaybackUrls` (new, package-private) is the one place that knows
mediamtx's `/get` query shape. `MediamtxStreamPublisher#playbackUrl` was refactored to delegate to it
(pure internal refactor — its own signature/external behavior is unchanged, still covered by its own
pre-existing `playbackUrl*` tests) instead of building the query string itself. One caveat found
during the refactor: `MediamtxPlaybackUrls.getUrl`'s `pathName` parameter is a `String`, but
`StreamId#value()` returns `UUID` — string concatenation (what the old inline code used) auto-calls
`toString()`, a method-argument context does not, so both call sites now say `id.value().toString()`
explicitly.

**Decode.** `new FFmpegFrameGrabber(url)`, `setFormat("mp4")` (skips format-sniffing probing — the
URL has no `.mp4` extension for auto-detection to key off), `setPixelFormat(avutil.AV_PIX_FMT_BGR24)`,
a 15s `rw_timeout` (see below), `start()`, then **`grabImage()`, not `grab()`** — same reasoning
`adapter-rtsp`'s `FfmpegVideoSource` already documents for its own grab loop: `grab()` also returns
audio/data frames, and mediamtx's mp4 clip could carry an audio track interleaved ahead of the first
video packet, so `grabImage()` (which internally skips non-video packets) is what actually gets "the
next video frame" reliably in one call, not `grab()`. `FrameConverter.copyBgr24(Frame)` (new,
mirroring `adapter-rtsp`'s own method of the same name/signature almost verbatim — a deliberate
duplicate, see that method's javadoc) copies the grabbed frame's pixels into a tightly packed
`ByteBuffer`, honoring JavaCV's padded row stride. Returns `new VideoFrame(streamId, 0L, at, width,
height, PixelFormat.BGR24, copy)` — sequence pinned to `0`, `capturedAt` pinned to the requested `at`
verbatim, exactly `ReplayFrameExtractionPort`'s frozen contract (never `grabber.getTimestamp()` or
`Instant.now()`).

**Read timeout: `rw_timeout`, 15s, in microseconds (`"15000000"`), via `grabber.setOption("rw_timeout",
...)`.** Same generic libavformat/`AVIOContext` option `adapter-rtsp`'s `FfmpegVideoSource` already
uses for the identical "don't let a stalled connection hang the caller" purpose against its own RTSP
sources — verified it isn't RTSP-specific, so it applies equally to this class's plain HTTP fetch
against mediamtx's playback server. Bounds connect **and** read; there is no separate connect-only
timeout option set here (unlike `MediamtxStreamPublisher`'s RTSP push, which sets a distinct
`"timeout"` AVOption too — mediamtx's playback endpoint is a single HTTP GET, one timeout covers the
whole exchange).

**Resilience.** Mirrors `MediamtxStreamPublisher`'s own "never let FFmpeg take down the caller"
posture: the entire grabber lifecycle (`setFormat`/`setPixelFormat`/`setOption`/`start`/`grabImage`)
is inside one `try`; any `Exception` — mediamtx unreachable, a 404 (nothing recorded at that instant,
surfaced by JavaCV as a grabber start failure), a truncated/undecodable clip, a `rw_timeout` firing —
is caught, logged once at `WARNING` naming the request URL and the cause, and turned into
`Optional.empty()`. The grabber is always `release()`d in a `finally`, success or failure (mirrors
`MediamtxStreamPublisher.releaseQuietly`'s "just call release(), swallow anything it throws" idiom —
`adapter-rtsp`'s own grabber cleanup does the same, no separate `stop()` call). `streamId`/`at`
themselves are `Objects.requireNonNull`-checked (a caller passing `null` for either is a programmer
error, not an absence to represent — mirrors this codebase's application-layer idiom, CLAUDE.md); a
`null`/unconfigured `playbackBase` is the one honest-absence case that returns `Optional.empty()`
without any I/O attempt at all, mirroring `MediamtxStreamPublisher#playbackUrl`'s own unconfigured-
base posture.

**Threading — the reason this is a separate class from `MediamtxStreamPublisher`, not a new method on
it.** `StreamPublisherPort`'s contract (and `MediamtxStreamPublisher`'s own class javadoc) is
explicitly per-stream, non-concurrent, stateful egress — `StreamState` is deliberately not
thread-safe because the port guarantees serialized calls per `streamId`. `ReplayFrameExtractionPort`
is the opposite: a request-thread, on-demand fetch that must be safe for unbounded concurrent use
across the same or different streams. `MediamtxReplayFrameExtractor` has **no mutable state at all**
beyond the immutable `playbackBase` field set at construction — one `FFmpegFrameGrabber` is created,
used, and released within a single `frameAt` call, so there is nothing to guard.

**Native log quieting reused, not tripled.** `MediamtxReplayFrameExtractor`'s constructor calls
`MediamtxStreamPublisher.ensureQuietLogging()` (package-private, idempotent, synchronized) instead of
carrying its own third copy of that ~5-line block — both classes live in this one package, so there
is no adapters-must-not-depend-on-each-other concern in sharing it *within* this module, unlike the
genuine cross-adapter duplication against `adapter-rtsp` (see that method's own javadoc).

**Tests.** `MediamtxPlaybackUrlsTest` (new, 4 tests): path/start/duration formatting, trailing-slash
tolerance, the fixed one-second-window shape `MediamtxReplayFrameExtractor` sends, and that `start`
uses `Instant#toString()` verbatim including sub-second precision. `FrameConverterTest` gained 7
`copyBgr24*` tests mirroring `adapter-rtsp`'s own `FrameConverterTest` for its twin method: padded-
stride row copy, contiguous (no-padding) copy, returned buffer independence from the source (mutating
the "native" buffer post-copy doesn't affect the already-copied result), and rejection of a null
frame / no image data / invalid dimensions / non-3-channel / non-byte-backed image buffer.
`MediamtxReplayFrameExtractorTest` (new, 5 tests, no docker needed): unconfigured (`null`) base →
empty with no I/O attempt; an unreachable mediamtx (port 1 on loopback, same fast-refusal idiom
`MediamtxStreamPublisherTest`'s own unreachable test uses) → empty, never throws; a malformed base
(no host component) → empty, never throws; `null` `streamId`/`at` → `NullPointerException`.
`MediamtxDockerIntegrationTest` gained `recordedStreamFrameIsExtractableViaMediamtxReplayFrameExtractor`
(docker-gated, reuses the existing recording/playback container setup
`recordedStreamIsFetchableAsMp4ThroughPlaybackUrl` already established): publishes+records a 4s
synthetic stream, then polls `MediamtxReplayFrameExtractor#frameAt` (recording finalization is not
instantaneous — same reason the raw-bytes playback test polls) until it returns a decoded frame, and
asserts `streamId`/`sequence == 0`/`capturedAt == at`/`format == BGR24`/positive dimensions — this is
what actually exercises the extractor's production URL-building → grab → convert path end-to-end
against a real mediamtx, confirmed genuinely running (not skipped) in this environment.

Config note (historical — Wave W6 has since landed, see "Follow-up status — CLOSED" above): this
module adds no new Spring/config surface of its own (plain classes, no `@ConfigurationProperties`
here). The `playbackBase` value both `MediamtxReplayFrameExtractor`'s constructor and
`MediamtxStreamPublisher`'s 4-arg constructor need lives in `vision-app`'s
`VisionPublishProperties.Mediamtx#playbackBase()`, default `http://localhost:19996` — the same
value the now-deleted 3-arg convenience constructor used to derive, so the default deployment's
behavior never changed across that wiring wave.

## E3: layering-refactor split (docs/plans/active/LAYERING-REFACTOR-PLAN.md §5.1, E-phase — internal restructuring only)

**Goal.** `MediamtxStreamPublisher` had grown to 817 lines carrying URL formatting, recorder
creation/configuration, and three unrelated concerns bundled into one nested `StreamState`
(backoff/outage, cadence measurement+PTS+drift, lag diagnostics). §5.1's target shape for this
adapter is: `MediamtxUrls` + `H264RecorderFactory` + `StreamState` split into `PublishBackoff` /
`CadenceEstimator` / `PublishDiagnostics`, with `LagTracker` (already a standalone class) as the
precedent for "one pure, directly-testable class per concern". **No behavior change**: every
constant is byte-identical to the literal it replaces, `viewUrl`/`whepUrl`/`playbackUrl`/`publish`/
`streamStarted`/`streamEnded` keep their exact external contracts, and this wave does not touch
`vision-app` or add any `@ConfigurationProperties`.

**What moved where:**
- `MediamtxUrls` (new) — `pushUrl`/`viewUrl`/`whepUrl` string formatting, `HLS_PLAYLIST_SUFFIX`/
  `WHEP_PATH_SUFFIX`, and `MediamtxStreamPublisher`'s own `withoutTrailingSlash` helper. Deliberately
  does **not** touch `MediamtxPlaybackUrls` (the playback `/get` query builder, a just-shipped
  feature at the time this wave started) — that class already owns the one URL shape this class
  doesn't build, per this task's own scope instruction.
- `H264RecorderFactory` (new) — `configureRecorder` (the package-private test seam, unchanged
  behavior) plus a new `create(pushUrl, width, height, frameRateFps)` that combines `new
  FFmpegFrameRecorder(...)` + `configureRecorder` + `start()`, releasing the half-initialized
  recorder on a failed start (previously `MediamtxStreamPublisher.startRecorder` did this inline).
  Owns `GOP_SECONDS`, `CONNECT_TIMEOUT_MICROS`, `X264_CRF`, `X264_MAXRATE_BITS_PER_SECOND`,
  `X264_BUFSIZE_BITS`, `X264_SCENECUT_THRESHOLD` — every value unchanged.
- `PublishBackoff` (new, from `StreamState`) — `readyToRetry`/`scheduleRetry`/`beginOutage`/
  `endOutage`, `INITIAL_BACKOFF_MS`=500, `MAX_BACKOFF_MS`=10_000, unchanged.
- `CadenceEstimator` (new, from `StreamState`) — `nextTimestampMicros`/`recordMeasurementSample`/
  `measuredFrameRateFps`/`observeSustainedDrift` and every measurement/drift constant, unchanged.
  This is the largest of the four extractions (209 lines) since PTS quantization, cadence
  measurement, and drift detection are all one cohesive "how fast is this stream really going"
  concern that shares the same measured-fps state.
- `PublishDiagnostics` (new, from `StreamState`) — the `LagTracker` field and `shouldLogLag`,
  `LAG_TRACKER_WINDOW_SIZE`=150, `LAG_LOG_INTERVAL_MILLIS`=30s, unchanged.
- `StreamState` itself is now a 6-line thin holder: `volatile FFmpegFrameRecorder recorder` plus
  `final PublishBackoff backoff`, `final CadenceEstimator cadence`, `final PublishDiagnostics
  diagnostics`. `MediamtxStreamPublisher` went from 817 to 393 lines.
- Tests moved with their classes, no test weakened: the 4 `configureRecorder*` tests →
  `H264RecorderFactoryTest` (new); `StreamStateTest`'s 12 cadence/PTS/drift tests →
  `CadenceEstimatorTest` (new); its 4 lag-diagnostics tests → `PublishDiagnosticsTest` (new);
  `StreamStateTest.java` itself deleted (nothing left in it to test — every method it exercised now
  lives on one of the three new classes).

**`DEFAULT_PLAYBACK_PORT`/`derivePlaybackViewBase` — confirmed genuinely dead, and deleted, along
with the 3-arg convenience constructor that was their only caller.** This needed real verification,
not just trusting the "superseded" premise: a repo-wide grep found `vision-app`'s
`WiringConfiguration#streamPublisherPort` **already** calls the 4-arg constructor with
`mediamtx.playbackBase()` (Wave W6 — docs/plans/done/CV-TRAINING-V2-PLAN.md §6/§7 — landed since this
module's own MODULE.md was last updated, which still described that wiring as "still outstanding").
So in production these three members were unreachable. They were **not**, however, unreachable in
this module's own test suite: roughly 20 test call sites used the 3-arg constructor purely for
brevity (irrelevant to what each test actually asserts), and two tests
(`playbackUrlIsDerivedFromWhepBaseHostViaConvenienceConstructor`,
`playbackUrlIsEmptyViaConvenienceConstructorWhenWhepBaseHasNoHost`) specifically exercised the
derivation logic itself. Since the only reason that convenience constructor ever existed — letting
`vision-app` compile unchanged before its own wiring wave landed — no longer applies, this wave
deleted the constructor and both dead members, updated the ~20 test call sites to the 4-arg
constructor (passing `null` where playback isn't the point of that test), and **deleted** (not
moved) the two derivation-specific tests, since the behavior they tested no longer exists. This is
the one place this wave's test count doesn't match "moved 1:1" — it is 2 fewer than before,
called out explicitly per this plan's own guardrail on never silently dropping a test.

**Build:** `./mvnw -B -pl video-output/publish-hls clean test`, run twice consecutively:
**72 tests, all green, 0 skipped**, both times (docker-gated `MediamtxDockerIntegrationTest`
confirmed actually running, not skipped, ~19–20s both runs) — `PublishDiagnosticsTest` 4 (new),
`MediamtxDockerIntegrationTest` 5, `H264RecorderFactoryTest` 4 (new, moved from
`MediamtxStreamPublisherTest`), `LagTrackerTest` 6, `FrameConverterTest` 14,
`MediamtxStreamPublisherTest` 18 (24 pre-existing − 4 moved to `H264RecorderFactoryTest` − 2
deleted derivation tests), `MediamtxReplayFrameExtractorTest` 5, `MediamtxPlaybackUrlsTest` 4,
`CadenceEstimatorTest` 12 (new, moved from `StreamStateTest`). 74 − 2 = 72, reconciling exactly with
the two deleted tests above.

## F3/D: config extraction (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F3, folded into the D/spine wave)

New public record `PublishSettings` (`com.drones.vision.adapter.publishhls`, framework-free, `static defaults()`) — the single source for every `vision.publish.encoder.*`/`.resilience.*`/`.cadence.*` tunable this module's classes used to bake in as `private static final` constants, with three nested records:
- `PublishSettings.Encoder(crf, maxrateBitsPerSecond, bufsizeBits, preset, gopSeconds, scenecutThreshold)` — `defaults()` = `(21, 6_000_000L, 12_000_000L, "veryfast", 1, 0)`, byte-identical to `H264RecorderFactory`'s own `X264_CRF`/`X264_MAXRATE_BITS_PER_SECOND`/`X264_BUFSIZE_BITS`/(inline `"veryfast"`)/`GOP_SECONDS`/`X264_SCENECUT_THRESHOLD`.
- `PublishSettings.Resilience(initialBackoff, maxBackoff)` — `defaults()` = `(500ms, 10s)`, byte-identical to `PublishBackoff`'s own `INITIAL_BACKOFF_MS`/`MAX_BACKOFF_MS`.
- `PublishSettings.Cadence(measurementFrames, minMeasuredFrameRateFps, maxMeasuredFrameRateFps, driftRatioHigh, driftEwmaAlpha, sustainedDriftWindow, defaultFrameRateFps)` — `defaults()` = `(5, 1.0, 120.0, 1.5, 0.2, 2s, 15.0)`, byte-identical to `CadenceEstimator`'s own six constants (`driftRatioLow` is *not* a field — still derived as `1.0 / driftRatioHigh` inside `CadenceEstimator`, exactly as before).

Every collaborator class gained a **new, additional** settings-taking constructor/method overload; the pre-existing no-settings one is kept, unchanged, now delegating to `PublishSettings.defaults()` (or the matching nested record's `defaults()`) — no existing test in this module needed to change, all 72 stayed green:
- `MediamtxStreamPublisher(URI, URI, URI, URI, PublishSettings)` — new canonical public constructor (the pre-existing 4-arg one delegates `this(rtspPushBase, hlsViewBase, whepViewBase, playbackViewBase, PublishSettings.defaults())`). `StreamState` gained a matching `(PublishSettings)` constructor (its previous implicit no-arg one is gone — both call sites, `streamStarted`/`publish`, now always pass the publisher's own `settings` field), which builds `PublishBackoff(settings.resilience())` and `CadenceEstimator(settings.cadence())`.
- `H264RecorderFactory.create(String, int, int, double, PublishSettings.Encoder)` / `configureRecorder(FFmpegFrameRecorder, double, PublishSettings.Encoder)` — new overloads; the pre-existing 4-arg/2-arg ones delegate with `PublishSettings.Encoder.defaults()`. `preset`/`crf`/`maxrate`/`bufsize`/`sc_threshold`/GOP now read off the `Encoder` argument; `tune=zerolatency`/`rtsp_transport=tcp`/the connect-timeout option stay hardcoded (deliberately out of `PublishSettings.Encoder`'s scope — `tune` is load-bearing for this class's own documented zero-reordering-delay latency guarantee, not a deployment knob; connect-timeout wasn't named in the plan's representative key list either).
- `PublishBackoff(PublishSettings.Resilience)` — new constructor; the no-arg one delegates `this(PublishSettings.Resilience.defaults())`. `INITIAL_BACKOFF_MS`/`MAX_BACKOFF_MS` constants are kept (still referenced by this class's own javadoc `{@value}`) but every actual read now goes through instance fields.
- `CadenceEstimator(PublishSettings.Cadence)` — new constructor; the no-arg one delegates `this(PublishSettings.Cadence.defaults())`. Every constant this class used to reference directly (including sizing the `measurementDeltasMicros` array off `CADENCE_MEASUREMENT_FRAMES`) now reads the matching instance field instead; the constants themselves are kept (still referenced by `CadenceEstimatorTest`'s own direct static-field assertions and this class's own javadoc).
- `MediamtxReplayFrameExtractor(URI, Duration window, Duration readTimeout)` — new 3-arg constructor; the pre-existing 1-arg one delegates `this(playbackBase, Duration.ofSeconds(WINDOW_DURATION_SECONDS), Duration.ofMillis(15_000L))`. `WINDOW_DURATION_SECONDS`/`READ_TIMEOUT_MICROS` constants are kept as documented defaults; the actual `frameAt` call now reads `windowDurationSeconds`/`readTimeoutMicros` instance fields.

`vision-app`'s own `VisionPublishProperties` extension (`encoder`/`resilience`/`cadence`/`replay` nested records, config extraction target: `com.drones.vision.app.config.wiring.PublishWiring`) now actually constructs a `PublishSettings` from `application.yaml` and threads it into `MediamtxStreamPublisher`'s 5-arg constructor, and a `Duration` pair into `MediamtxReplayFrameExtractor`'s 3-arg one — see station/vision-app/MODULE.md's own "Package shape" section for the full wiring-side mapping. `./mvnw -B -pl video-output/publish-hls test`: **72/72 green, unchanged count**.

## M6: mediamtx proxy publisher + live frame grab (docs/plans/active/MEDIA-SOT-PLAN.md, wave M6)

**Goal.** Let mediamtx itself dial an RTSP camera (D3: "who publishes video into mediamtx" switch A)
instead of the JVM decoding it and pushing frames — the enabling move for CV-SCALE's N-workers-one-path
target. `MediamtxStreamPublisher` is completely untouched by this wave; every one of its 72 pre-existing
tests stayed green, unedited.

**New classes**, all in this package (see API surface above for exact signatures): `MediamtxControlApi`
(package-private thin client for §5.3's four operations), `MediamtxControlApiException` (public, the
diagnosable-failure type), `MediamtxProxySettings` (public record), `MediamtxProxyPublisher` (public,
implements `StreamPublisherPort`), `PublisherRouter` (public, implements `StreamPublisherPort`,
per-device dispatch), `MediamtxLiveFrameGrabber` (public, not a port implementation — see below).

**Router decision.** `PublisherRouter` is the *only* `StreamPublisherPort` `vision-app` needs to wire
(a future wave's job, out of this module's scope) — it decides per `streamStarted` call whether a
device's stream reaches `MediamtxProxyPublisher` or the JVM's own `MediamtxStreamPublisher`, based
purely on `(sourceProxyEnabled flag, device.stream().protocol())`. The flag defaults `false` (D1), so
the router's default behaviour is "always direct" — byte-identical to today. Because `proxiesSource`
(called by the application layer *before* `streamStarted`, to decide whether to open a
`VideoSourcePort` at all) and `streamStarted` (which needs a `Device` to route, but whose sibling
calls — `publish`/`streamEnded`/the URL methods — only ever carry a `StreamId`) must agree on the same
routing decision for one stream's whole lifetime, `PublisherRouter` remembers the choice per
`StreamId` in a `ConcurrentHashMap`, populated in `streamStarted` and cleared in `streamEnded`. A
`StreamId` this router never saw `streamStarted` for (caller bug, or state lost across a restart)
falls back to the direct publisher rather than throwing — this module's existing "degrade, don't
throw outside the lifecycle" posture.

**Readiness poll.** `MediamtxProxyPublisher#streamStarted` creates (or idempotently PATCH-repoints)
the mediamtx path, then polls `GET /v3/paths/get/{name}` at a fixed 100ms interval (not a config knob
— §5.5 only budgets the overall `ready-timeout`) until either `ready:true` or
`MediamtxProxySettings.readyTimeout()` (default 10s) elapses. **On timeout it throws
`MediamtxControlApiException` out of `streamStarted`** — a deliberate departure from
`MediamtxStreamPublisher`'s "nothing escapes" posture, because `StreamPipeline#start` calls
`streamPublisherPort.streamStarted` synchronously and that call chain runs synchronously from
`DefaultStreamService`'s own start path (verified by reading `StreamPipeline.java`), so a thrown
exception here genuinely fails the operator-facing start call rather than being swallowed on a
background thread — exactly what "must fail the start call... rather than returning a URL that plays
nothing" (docs/plans/active/MEDIA-SOT-PLAN.md §12) requires. `streamEnded`, by contrast, catches
`MediamtxControlApiException` and logs it at `WARNING` — teardown must not block a caller.

**A robustness case the plan text didn't spell out, found while implementing the poll:** when
`MediamtxProxySettings.sourceOnDemand()` is `true` (D10's opt-out), mediamtx does not dial the camera
until a *reader* connects — so polling readiness at start time, before any viewer or worker has
subscribed, would time out on every single start call, making on-demand mode simply broken rather
than merely slower to become watchable. `streamStarted` therefore **skips the readiness poll entirely**
when `sourceOnDemand` is `true` and returns as soon as the path is created, logging why. This is the
concrete reason `MediamtxProxySettings.sourceOnDemand()`'s javadoc calls this out explicitly — a future
reader of this class would otherwise "fix" the poll to also run in on-demand mode and silently reinstate
the very failure mode D10 exists to describe.

**Un-annotated live frame grab, and why it isn't the playback server.** `MediamtxLiveFrameGrabber`
fills the gap proxy mode opens: nothing in the JVM decodes a proxied stream's video (`publish` is a
no-op), so `StreamPipeline`'s in-memory `latestFrame`/`latestRawFrame` caches (vision-application)
would sit permanently empty for a proxied stream, breaking the snapshot endpoint and training capture.
The obvious-looking reuse — pointing `MediamtxReplayFrameExtractor`'s machinery at "now minus a
second" — does **not** work: this module's own R-a section above documents that mediamtx only
finalizes a recording segment on path *unpublish*, so a request against a still-live path's playback
window 404s indefinitely. `MediamtxLiveFrameGrabber` instead opens the mediamtx **RTSP read** address
directly (`{rtspBase}/{streamId}` — the exact same address `MediamtxStreamPublisher` pushes to, used
here as a read client) with a plain `FFmpegFrameGrabber`, reusing the *style* (bounded connect/read
I/O, `grabImage()` not `grab()`, `FrameConverter.copyBgr24`, shared `ensureQuietLogging`) but not the
playback server itself. Every frame it returns is un-annotated by construction — a proxied path's
source is the camera's own feed, and no overlay code ever touches it (unlike push mode, where
`StreamPipeline` burns boxes in before `MediamtxStreamPublisher#publish`). **Not yet wired into
`StreamService`** — that plumbing (deciding when to call this class vs. read `StreamPipeline`'s cache)
belongs to whichever wave next touches `vision-application`/`vision-app`; this module only supplies the
capability, per its own file-scope boundary.

**The one non-obvious gotcha found writing the integration test, worth flagging for whoever wires M7's
`docker-compose.yml` or writes further tests against a proxied path:** a device's source URL that
happens to be *another path on the same mediamtx instance* (as this wave's own IT uses for a
docker-free "camera" stand-in) must be addressed by mediamtx's **container-internal** RTSP port
(`rtsp://127.0.0.1:8554/...`), never by the host-mapped port the *test JVM* uses to reach the same
server from outside. mediamtx dials `source` URLs from *inside its own container's network
namespace* — a host-mapped port (`docker port` output) is meaningless there, and the create call
still succeeds (mediamtx doesn't validate reachability at path-creation time), so this fails silently
as a readiness timeout, not as a create-time error, which took a debugging pass to track down. A real
camera has one real address and this confusion doesn't arise there; it only bites the "mediamtx pulls
from another path on itself" test/dev pattern.

**§5.3's amended contract, checked against real 1.19.3.** All four operations, both idempotency
fallbacks (create → patch on "already exists"; delete → 404-is-success), and the 401 IP-gating M0
found blocking were re-verified by this wave's own docker-gated IT (below) against a *freshly started*
mediamtx container — not just re-read from M0's transcript. It held with zero surprises: the create
call succeeds even when the source is unreachable (mediamtx defers the actual dial), readiness flips
from `false` to `true` only once mediamtx's own RTSP client to the source connects, and delete is
genuinely idempotent. `MTX_AUTHINTERNALUSERS` was not re-tried as an env override (M0 already
confirmed it doesn't work); this wave's IT mounts a `mediamtx.yml` with a widened `api` user's `ips`,
matching M0's own verified fix, self-contained in a per-run temp file rather than depending on
`cv/cv-service/spikes/pull/results/mediamtx-spike.yml`'s path or survival.

**Auth credentials.** `MediamtxProxySettings.apiUser`/`apiPassword` (unset by default) become an HTTP
Basic `Authorization` header on every Control API call when both are set; the compact constructor
requires `apiPassword` whenever `apiUser` is set, so a half-configured pair fails at construction, not
at the first 401 in production. Fixing the *deployment* (mounting a widened `mediamtx.yml`, or setting
these two properties to match server-side credentials) is wave M7's job — this class only supports
either path.

**Tests.** 39 new: `MediamtxControlApiTest` 16 (unit, no docker — an in-process
`com.sun.net.httpserver.HttpServer` standing in for mediamtx, real sockets rather than a mocked
`HttpClient`, covering create/patch fallback, readiness true/false/404, delete 200/404/500, the 401
auth-failure message, Basic-auth header presence/absence, and the two JSON-field-extraction test
seams directly), `MediamtxProxyPublisherTest` 10 (unit, same in-process-server idiom — readiness
success, readiness timeout's diagnosable message, the on-demand skip, the 401 propagation, `publish`
being a verified no-op, `streamEnded` never throwing, URL delegation, `proxiesSource` always `true`),
`PublisherRouterTest` 8 (unit, two hand-fake `StreamPublisherPort`s, no I/O — routing by protocol+flag,
per-stream stickiness across the whole lifecycle, the unrouted-`StreamId` fallback),
`MediamtxLiveFrameGrabberTest` 4 (unit — unreachable/malformed base, null-argument rejection, never
throws), `MediamtxProxyPublisherDockerIntegrationTest` 1 (docker-gated, ran genuinely — not
skipped — in this environment: starts a real `bluenviron/mediamtx:1.19.3` with the auth-widening
config, publishes a synthetic "camera" stream via the existing `MediamtxStreamPublisher`, proxies it
through `MediamtxProxyPublisher`, asserts the path exists with `source.type=="rtspSource"` and
`ready:true`, grabs a live un-annotated frame via `MediamtxLiveFrameGrabber` and asserts positive
BGR24 dimensions, then asserts the path is gone after `streamEnded`). `./mvnw -B -pl
video-output/publish-hls clean test`, run twice consecutively: **111/111 green, 0 skipped, both
runs** (`MediamtxDockerIntegrationTest` 5 + `MediamtxProxyPublisherDockerIntegrationTest` 1, both
docker-gated, both confirmed actually running against real containers, not skipped).

## Status
**Fully implemented and green.** `src/test/*` was migrated to the current `vision-domain` shapes after the domain refactor that deleted `DeviceType` and moved `Device`/`StreamId` to their present forms: `Device` is now constructed without a type argument (`new Device(DeviceId, String, Set<Capability>, StreamDescriptor)`), and `StreamId` fixed-value tests use `StreamId.of("<uuid>")` (a literal valid UUID) instead of the old free-form string constructor; other call sites use `StreamId.random()`/`DeviceId.random()`.

`./mvnw -B -pl video-output/publish-hls test`: 30 tests, all green, 0 skipped — `FrameConverterTest` 6, `MediamtxStreamPublisherTest` 9 (incl. backoff + deferred-recorder-start coverage), `StreamStateTest` 12 (cadence measurement: median, clamps, degenerate fallback, wall-clock timestamp spans at 15/30fps, monotonic bump), `MediamtxDockerIntegrationTest` 3 (`publishedStreamBecomesFetchableAsHlsOnRealMediamtx`, `burstyFrameDeliveryNeverTriggersNonMonotonicPtsFailures`, and `thirtyFpsSourcePlaysBackAtWallClockSpeedNotSlowMotion` — the measured-cadence/slow-motion regression test, which skips the RTSP reader's initial buffered burst and asserts steady-state media-time/wall-time ratio ≈ 1.0). Docker-gated tests confirmed actually running against a real mediamtx container (not skipped via `@EnabledIf`).

Re-verified after adding the native log-level quieting (see Gotchas): `./mvnw -B -pl video-output/publish-hls clean test` passes twice in a row, 14/14 green each time, with zero `libx264`/`Output #0`/encoding-stats lines anywhere in either run's output (confirmed by grepping the full surefire console output) — only this class's own `System.Logger` INFO/WARNING lines remain, plus genuine native ERROR-level lines (e.g. `[tcp @ ...] Connection ... failed`) from the unreachable-mediamtx test, proving the WARNING threshold suppresses INFO chatter without swallowing real problems.

Re-verified again after the vision-app HLS-proxy feature added `viewUrlSupportsRelativeHlsViewBaseForAppProxiedUrls` (no production-code change in this module — see Gotchas): `./mvnw -B -pl video-output/publish-hls clean test` passes twice in a row, 15/15 green each time — `FrameConverterTest` 6, `MediamtxStreamPublisherTest` 7 (one more than before), `MediamtxDockerIntegrationTest` 2.

docs/plans/done/MVP2-PLAN.md **L-a** (WebRTC/WHEP viewing URL beside HLS): `MediamtxStreamPublisher` gained the `whepViewBase` constructor argument and `whepUrl(StreamId)` override (see API surface above and the Gotchas on WHEP-over-Docker reachability/CORS). `./mvnw -B -pl video-output/publish-hls test`: 35 tests, all green — `FrameConverterTest` 6, `StreamStateTest` 12, `MediamtxStreamPublisherTest` 14 (10 pre-existing + 4 new `whepUrl*` tests: format, trailing-slash tolerance, an absolute non-localhost base round-trips unchanged, null id → empty — mirroring `viewUrl`'s existing coverage), `MediamtxDockerIntegrationTest` 3 (docker-gated, ran green in this environment — unchanged in behavior, their `new MediamtxStreamPublisher(...)` calls just gained a placeholder third `whepViewBase` argument since these tests exercise HLS only). Every pre-existing 2-arg constructor call site across this module's tests was updated to 3 args (no overload added — see API surface note on why).

docs/plans/done/MVP2-PLAN.md **V-a** (glass-to-glass latency: encoder + mediamtx LL-HLS + proxy audit — see the "V-a: latency" section above for the full writeup): `GOP_SECONDS` 2→1, new `X264_SCENECUT_THRESHOLD`("0")/`setMaxBFrames(0)` in `configureRecorder`. `./mvnw -B -pl video-output/publish-hls clean test`: **37 tests, all green, 0 skipped** — `FrameConverterTest` 6, `StreamStateTest` 12, `MediamtxStreamPublisherTest` 16 (14 pre-existing + 2 new: `configureRecorderMinimizesLatencyWithZerolatencyTuneNoBFramesAndClosedGop` asserting `tune`/`getMaxBFrames`/`sc_threshold`, `configureRecorderGopSpansApproximatelyOneSecondAtTypicalFrameRates` asserting GOP at 15/24fps; the pre-existing `configureRecorderAppliesMeasuredFrameRateAndProportionalGop` was updated in place, 60→30, for the new `GOP_SECONDS`), `MediamtxDockerIntegrationTest` 3 (docker-gated, confirmed actually running — not skipped — against a real mediamtx container in this environment, `v1.19.2`, 10.5–10.7s elapsed across two consecutive runs; unchanged in behavior, one comment updated from "~2s/30-frame GOP" to "~1s/15-frame GOP" to match the new constant). `docker-compose.yml`'s `mediamtx` service block was validated with `docker compose config` (clean) and, separately (a full `docker compose up -d mediamtx` wasn't possible in this environment — another concurrent agent's own compose stack already held host ports 8554/18888), by running the real pulled `bluenviron/mediamtx:latest` image directly via `docker run` with the same four `MTX_HLS*` env vars this task added: started cleanly with no config errors, `[HLS] started with listener on :8888` in its own log output. `vision-api`'s `HlsProxyController`/`HlsProxyControllerTest` (Cache-Control forwarding fix, docs above) were built/tested separately — see station/vision-api/MODULE.md; `144/144` green there (`HlsProxyControllerTest` 8, up from 6).

docs/plans/done/MVP2-PLAN.md **V-c** (capture→encode latency measurement — see the "V-c: latency measurement" section above for the full design): new `LagTracker` (pure ring-buffer/percentile class) wired into `MediamtxStreamPublisher.writeFrame`/`StreamState`; no change to `configureRecorder`, GOP, CRF, or any other V-a setting. `./mvnw -B -pl video-output/publish-hls clean test`, run twice consecutively: **47 tests, all green, 0 skipped**, both times — `FrameConverterTest` 6, `LagTrackerTest` 6 (new: empty/single-sample/within-capacity/wrap-drops-oldest/full-window percentile math, non-positive-capacity rejection), `StreamStateTest` 16 (12 pre-existing + 4 new: `shouldLogLag`'s baseline/gating/reset behavior, `lagTracker` accumulates samples), `MediamtxStreamPublisherTest` 16 (unchanged — lag recording is exercised only where a real successful `recorder.record()` call happens, which none of this class's own unit tests reach without a live mediamtx; see the docker IT below), `MediamtxDockerIntegrationTest` 3 (docker-gated, confirmed actually running against a real mediamtx container both runs — this is what actually exercises `writeFrame`'s lag-recording path end-to-end, since it publishes real frames through a real recorder; no test asserts on the log output itself, which would mean parsing `System.Logger` output — fragile and not requested, the pure math is what V-c's own brief asks to unit-test).

docs/plans/done/OPS-CORE-PLAN.md **R-a** (recording via mediamtx: `docker-compose.yml` record/playback config + `MediamtxStreamPublisher#playbackUrl` — see the "R-a: recording playback" section above for the full writeup, including the verified mediamtx env var names/sources and the `vision-app` compatibility-shim rationale): `StreamPublisherPort#playbackUrl` (added concurrently in `vision-domain` by another agent working in parallel, landed and built against as expected) is now implemented — 4th `playbackViewBase` constructor argument (nullable), 3-arg convenience overload deriving a default from `whepViewBase`'s host at port 19996 for zero-wiring-change compatibility with `vision-app`. `docker-compose.yml`'s `mediamtx` service gained `MTX_PATHDEFAULTS_RECORD=yes`, `MTX_PATHDEFAULTS_RECORDDELETEAFTER=72h`, `MTX_PLAYBACK=yes`, `MTX_PLAYBACKADDRESS=:9996` (host `19996`→container `9996`, same 1:1-but-renumbered style as `hls`/`whep`), and a new named volume `mediamtx-recordings` mounted at `/recordings` (not a repo bind mount — mediamtx's own default `recordPath` resolves there given the image's unset-`WORKDIR`/`/` cwd, confirmed via `docker inspect`). `./mvnw -B -pl video-output/publish-hls -am test`: **56 tests, all green, 0 skipped** — `FrameConverterTest` 6, `LagTrackerTest` 6, `StreamStateTest` 16, `MediamtxStreamPublisherTest` 24 (16 pre-existing + 8 new `playbackUrl*` tests), `MediamtxDockerIntegrationTest` 4 (3 pre-existing + 1 new `recordedStreamIsFetchableAsMp4ThroughPlaybackUrl`, docker-gated, confirmed actually running — not skipped — against a real pinned `bluenviron/mediamtx:1.19.3` container; whole docker-gated class ran in 14.81s, the new test alone in 5.6s when run in isolation). `docker compose config` validated clean against the updated compose file. Before writing any Java, the exact record→playback flow was manually verified end-to-end with a real `docker run bluenviron/mediamtx:1.19.3` (the four env vars above) and a plain `ffmpeg` RTSP push: a recorded `.mp4` segment appeared under `/recordings/<path>/` a few seconds after the source disconnected, and `GET :9996/get?path=&start=&duration=` returned `200 video/mp4` with an `ftyp` box at byte offset 4 — the same shape `playbackUrl`/the new docker IT exercise.

docs/plans/done/CV-TRAINING-V2-PLAN.md **Wave W4** (`MediamtxReplayFrameExtractor implements ReplayFrameExtractionPort`, the `MediamtxPlaybackUrls` shared helper, `FrameConverter#copyBgr24` — see "Replay frame extraction" above for the full design/rationale writeup): built against `vision-domain`'s `ReplayFrameExtractionPort` (Wave W1, landed concurrently by another agent — this module only ever compiled/tested against it via `-am`, since the local Maven repo's installed `vision-domain` jar predates that port and this task's file scope excludes installing/publishing artifacts). No new dependencies (this module already carried `javacv`/`ffmpeg-platform-gpl`) and no new config surface in this module (plain classes; the `playbackBase` property itself belongs to `vision-app`'s `VisionPublishProperties`, Wave W6's job — see "Follow-up status" above). `./mvnw -B -pl video-output/publish-hls -am test`, run twice consecutively: **74 tests, all green, 0 skipped**, both times — `FrameConverterTest` 14 (6 pre-existing + 8 new `copyBgr24*`), `LagTrackerTest` 6, `StreamStateTest` 16, `MediamtxStreamPublisherTest` 24 (unchanged — the `playbackUrl` refactor to delegate to `MediamtxPlaybackUrls` is byte-identical in behavior, verified by its own 8 pre-existing `playbackUrl*` tests staying green untouched), `MediamtxReplayFrameExtractorTest` 5 (new), `MediamtxPlaybackUrlsTest` 4 (new), `MediamtxDockerIntegrationTest` 5 (4 pre-existing + 1 new `recordedStreamFrameIsExtractableViaMediamtxReplayFrameExtractor`, confirmed actually running — not skipped — against a real mediamtx container both runs, ~20s for the whole docker-gated class). Note: a `clean` in the same Maven invocation as `-am` intermittently failed *inside `vision-domain`*'s own forked test JVM (`Unable to create test class ... DatasetUploadPortTest`) — unrelated to any change in this module (that class isn't touched here, and re-running the identical command without `clean` succeeded twice in a row); most plausibly transient contention from another agent's concurrent wave also building `vision-domain` at the same time. Plain (non-`clean`) `-pl video-output/publish-hls -am test` is what's proven green here.

docs/plans/active/SYSTEM-STATUS-PLAN.md **§4.2, wave S2 done**: `video-publish`'s health self-report for
`GET /api/system/status` (station/vision-api). Two additive changes to `PublishBackoff`/`MediamtxStreamPublisher`
(`inOutage()`, `PublishSnapshot`/`streamsInOutage()` — see API surface above) plus one new class,
`PublishStatusProvider implements SubsystemStatusPort`. Module gained an explicit `vision-platform`
dependency purely to compile against `SubsystemStatusPort`/`SubsystemStatus`/`Health` — no change to
this module's actual publish/recording/playback behavior. `./mvnw -B -pl core/vision-platform,cv/grpc,
drone-link/mavlink,video-output/publish-hls,station/vision-api,station/vision-app test -DskipWeb`: this
module **111/111 green, unchanged count** — `PublishStatusProvider` has no dedicated unit test of its
own (deferred, same reasoning as `cv/grpc`'s `CvStatusProvider`/`drone-link/mavlink`'s
`MavlinkLinkStatusProvider`: it is a thin mapping over `MediamtxStreamPublisherTest`'s already-tested
`streamsInOutage()` path and `vision-api`'s `SystemStatusControllerTest`, which exercises the endpoint's
aggregation logic against fake `SubsystemStatusPort`s). Wired by `vision-app`'s `SystemStatusWiring`,
gated the same way `PublishWiring#mediamtxStreamPublisher` is (`vision.publish.enabled`, default
`true`) with a companion `Health.DISABLED` bean for the opposite condition — both conditions repeated
verbatim rather than read via `@ConditionalOnBean`, per docs/plans/active/CV-RECONNECT-PLAN.md §3.3.

**docs/plans/active/STREAM-STATE-PLAN.md wave S4 — reader count, the one video-demand term the JVM cannot answer.**

- `MediamtxControlApi#hasReaders(String pathName)` (package-private) — `GET /v3/paths/get/{name}`,
  the same call `isReady` already makes, read for its `readers` array instead of its `ready` field.
  A **presence test, not a count**: the idle policy only asks whether *anyone* is watching, and
  counting would mean parsing session objects this thin client has no other reason to understand.
  404 → `false` (no such path, so nothing can be reading it). Any other non-200, **and a 200 whose
  body carries no `readers` field**, throws — the caller must be able to tell "no readers" from
  "could not ask", because it fails open on the latter and stops a stream on the former.
- `public final class MediamtxReaderProbe` — `(URI apiBase, String apiUser, String apiPassword)`,
  one method `boolean hasReaders(StreamId)` (a stream's id *is* its mediamtx path name). Its own
  object rather than a method on `MediamtxProxyPublisher`, which is only wired in proxy mode:
  **reader count is wanted in every mode**, since push-mode streams publish to mediamtx paths too and
  their WHEP viewers are just as invisible to the application.

**Why this exists at all: WHEP/WebRTC viewers connect straight to mediamtx.** They open no SSE
connection and send no request through the app, so every in-JVM demand signal is blind to them.
Without this probe the idle policy would confidently stop streams being watched over WebRTC — this
product's lowest-latency viewing path.

*Tests:* `MediamtxControlApiTest` +2 (18 total, green) — the two real mediamtx 1.19.3 body shapes
(`"readers":[]` vs a populated array), and an explicit pair asserting the parser **throws rather than
guesses** when the field is absent or the body is null.
