# adapter-publish-hls

Pushes H.264 to a mediamtx sidecar (RTSP push, or mediamtx-side pull-proxy) so browsers can watch live
streams via mediamtx's HLS/WHEP egress; mediamtx is the video source of truth — viewing and CV both
subscribe to the one path it serves (docs/plans/done/MEDIA-SOT-PLAN.md).

**Depends on:** `vision-kernel`, `vision-platform` (`SubsystemStatusPort`/`SubsystemStatus`/`Health`),
`vision-warehouse` (`Device`), `vision-perception` (`VideoFrame`/`PixelFormat`/`StreamPublisherPort`),
`vision-events` (`ReplayFrameExtractionPort`), `org.bytedeco:javacv`, `org.bytedeco:ffmpeg-platform-gpl`
(plus JDK-only `java.net.http.HttpClient` for the mediamtx Control API client — no new Maven dependency)
**Used by:** `vision-app`
**Build/test:** `./mvnw -B -pl video-output/publish-hls test`. Docker-gated tests (`MediamtxDockerIntegrationTest`,
`MediamtxProxyPublisherDockerIntegrationTest`) run the real `docker` CLI directly (no Testcontainers) and
self-skip via `@EnabledIf(dockerAvailable)` when docker is unreachable.

## API surface
### `com.drones.vision.adapter.publishhls`
- `final class MediamtxStreamPublisher implements StreamPublisherPort` (public) — JVM-side push
  publisher: decodes `VideoFrame`s and encodes/pushes them to mediamtx via `FFmpegFrameRecorder`.
  - `MediamtxStreamPublisher(URI rtspPushBase, URI hlsViewBase, URI whepViewBase, URI playbackViewBase)`
    — delegates to the 5-arg ctor with `PublishSettings.defaults()`. First three non-null;
    `playbackViewBase` nullable (`null` ⇒ `playbackUrl` always `Optional.empty()`).
  - `MediamtxStreamPublisher(URI, URI, URI, URI, PublishSettings settings)` — canonical constructor;
    `settings` threads encoder/resilience/cadence tunables into every `StreamState` this instance creates.
  - `streamStarted(StreamId, Device)`, `publish(StreamId, VideoFrame)`, `streamEnded(StreamId)`.
  - `viewUrl(StreamId): Optional<URI>` → `{hlsViewBase}/{id}/index.m3u8`; `whepUrl(StreamId): Optional<URI>`
    → `{whepViewBase}/{id}/whep` (mediamtx serves WHEP for every published path at zero extra setup —
    pure string formatting, not a second live session). Both delegate to `MediamtxUrls`.
  - `playbackUrl(StreamId, Instant start, Duration duration): Optional<URI>` → `{playbackViewBase}/get?
    path={id}&start={start}&duration={roundedSeconds}` (delegates to `MediamtxPlaybackUrls`); `start`
    uses `Instant#toString()` verbatim (RFC3339); `duration` rounded to the nearest whole second.
  - Recorder creation is delegated to `H264RecorderFactory`; frame/pixel conversion to `FrameConverter`;
    backoff to `PublishBackoff`; cadence measurement/PTS/drift to `CadenceEstimator`; lag tracking to
    `PublishDiagnostics`/`LagTracker` — see their own entries below.
  - `public record PublishSnapshot(int total, List<StreamId> inOutage)` / `public PublishSnapshot streamsInOutage()`
    — `total` = streams this publisher currently tracks state for; `inOutage` names which have an active
    backoff outage. Backs `PublishStatusProvider`.
  - nested `private static final class StreamState` — per-stream mutable bookkeeping, not thread-safe
    (port contract serializes calls per `streamId`): `volatile FFmpegFrameRecorder recorder` plus
    `final PublishBackoff backoff`, `final CadenceEstimator cadence`, `final PublishDiagnostics diagnostics`.
- `final class MediamtxUrls` (package-private, stateless) — `static String pushUrl/viewUrl/whepUrl(URI, StreamId)`
  string formatting; trailing slash on the base tolerated.
- `final class H264RecorderFactory` (package-private, stateless) — `static FFmpegFrameRecorder create(String pushUrl, int width, int height, double frameRateFps[, PublishSettings.Encoder])`
  (new + configure + `start()`, releasing on a failed start); `static void configureRecorder(FFmpegFrameRecorder, double frameRateFps[, PublishSettings.Encoder])`.
  Fixed (not settings-driven): format `"rtsp"`, `rtsp_transport=tcp`, connect timeout 5s, codec
  `AV_CODEC_ID_H264`/`"libx264"`, `tune=zerolatency`, `setMaxBFrames(0)`, pixel format `AV_PIX_FMT_YUV420P`.
  Settings-driven (`PublishSettings.Encoder`, default `crf=21, maxrate=6_000_000, bufsize=12_000_000,
  preset="veryfast", gopSeconds=1, scenecutThreshold=0`): CRF/VBV cap, preset, GOP, scene-cut threshold.
- `final class PublishBackoff` (package-private, mutable, not thread-safe) — `readyToRetry()`,
  `scheduleRetry()`, `beginOutage(): boolean` (true the first call of an outage), `endOutage(): boolean`,
  `inOutage(): boolean`. Two constructors: no-arg (`PublishSettings.Resilience.defaults()`) and
  `(PublishSettings.Resilience)` (`initialBackoff`=500ms, `maxBackoff`=10s by default).
- `final class CadenceEstimator` (package-private, mutable, not thread-safe) — measures a stream's real
  frame rate from its first `measurementFrames` published frames (median of inter-frame deltas, clamped
  to `[minMeasuredFrameRateFps, maxMeasuredFrameRateFps]`, degenerate deltas fall back to
  `defaultFrameRateFps`), and computes each frame's PTS. `nextTimestampMicros(Instant, double fps): long`,
  `recordMeasurementSample(Instant): boolean`, `measuredFrameRateFps(): double`, `observeSustainedDrift(Instant): boolean`.
  Two constructors: no-arg (`PublishSettings.Cadence.defaults()`, `measurementFrames=5,
  min=1.0, max=120.0, driftRatioHigh=1.5, driftEwmaAlpha=0.2, sustainedDriftWindow=2s, defaultFrameRateFps=15.0`)
  and `(PublishSettings.Cadence)`.
- `final class PublishDiagnostics` (package-private, mutable, not thread-safe) — `final LagTracker lagTracker`
  (package-private field), `shouldLogLag(long nowEpochMs): boolean` (gates the periodic summary log,
  default window 150 samples / 30s interval).
- `final class LagTracker` (package-private, pure, no I/O) — fixed-capacity drop-oldest ring buffer of
  lag samples (ms). `LagTracker(int capacity)` (rejects `capacity <= 0`); `void record(long lagMillis)`
  (`O(1)`, no allocation); `long p50()`/`long p95()` (nearest-rank over a sorted defensive copy,
  `O(n log n)` — call only occasionally); `int sampleCount()`. Returns `0` for both percentiles when empty.
- `final class FrameConverter` (package-private, stateless) — **into JavaCV**: `static Frame toFrame(VideoFrame)`
  dispatches on `PixelFormat` (`BGR24`/`JPEG` only, else `IllegalArgumentException`); `bgr24ToFrame`,
  `jpegToFrame`. **Out of JavaCV**: `static ByteBuffer copyBgr24(Frame)` — a decoded grabber `Frame` →
  tightly packed heap buffer, stride padding stripped; a deliberate near-duplicate of `adapter-rtsp`'s
  own method (adapters must not depend on each other).
- `final class MediamtxPlaybackUrls` (package-private, stateless) — `static String getUrl(URI playbackBase, String pathName, Instant start, long durationSeconds)`
  → `{playbackBase}/get?path={pathName}&start={start}&duration={durationSeconds}`. Shared by
  `MediamtxStreamPublisher#playbackUrl` and `MediamtxReplayFrameExtractor#frameAt`.
- `final class MediamtxReplayFrameExtractor implements ReplayFrameExtractionPort` (public) — pulls one
  decoded BGR24 frame out of a stream's mediamtx recording, for CV-training frame capture from a
  recorded replay. `MediamtxReplayFrameExtractor(URI playbackBase)` / `(URI playbackBase, Duration window, Duration readTimeout)`
  (`playbackBase` nullable — unconfigured ⇒ every call returns empty). `frameAt(StreamId, Instant): Optional<VideoFrame>`
  requests a one-second window starting at `at` from mediamtx's playback server (seek delegated to
  mediamtx, never an ffmpeg-side seek across the whole recording), decodes via `FFmpegFrameGrabber`
  (`setFormat("mp4")`, `grabImage()` not `grab()`, 15s `rw_timeout`), returns
  `new VideoFrame(streamId, 0L, at, width, height, BGR24, copy)` — `sequence` pinned `0`, `capturedAt`
  pinned to the requested `at` verbatim. No mutable state beyond the immutable `playbackBase` field —
  safe for unbounded concurrent calls (unlike `MediamtxStreamPublisher`'s per-stream contract).
- `final class MediamtxControlApi` (package-private) — thin client for mediamtx's v3 Control API, used
  by `MediamtxProxyPublisher`/`MediamtxReaderProbe`. `MediamtxControlApi(URI apiBase, String apiUser, String apiPassword)`
  (credentials nullable — no `Authorization` header when absent). `void createOrUpdatePath(String pathName, String sourceUrl, boolean sourceOnDemand, String rtspTransport)`
  — `POST /v3/config/paths/add/{name}`, falls through to `PATCH /v3/config/paths/patch/{name}` on an
  HTTP 400 whose body is mediamtx's `"path already exists"` error. `boolean isReady(String pathName)` —
  `GET /v3/paths/get/{name}`; 404 → `false` (not-ready, not an error). `boolean hasReaders(String pathName)`
  — same GET, reads the `readers` array presence, not a count; 404 → `false`; a 200 body with no
  `readers` field throws (the caller must distinguish "no readers" from "could not ask"). `void deletePath(String pathName)`
  — `DELETE /v3/config/paths/delete/{name}`; 200 or 404 both succeed (idempotent). Every other non-2xx
  throws `MediamtxControlApiException`; an HTTP 401 names "authentication" and the fix (`vision.publish.mediamtx.api-user`/`api-password`,
  or a widened `mediamtx.yml`) rather than a bare status code.
- `final class MediamtxControlApiException extends RuntimeException` (public) — thrown by
  `MediamtxControlApi` and, on a readiness timeout, `MediamtxProxyPublisher#streamStarted`. Deliberately
  allowed to escape `streamStarted` (unlike this module's usual "nothing escapes" posture) — a proxied
  start that cannot reach a ready path must fail loud, not hand back a URL that plays nothing;
  `streamEnded` still catches it and logs at `WARNING`.
- `public record MediamtxProxySettings(String rtspTransport, Duration readyTimeout, boolean sourceOnDemand, String apiUser, String apiPassword)`
  — `MediamtxProxyPublisher`'s tunables. `static defaults()` = `("automatic", 10s, false, null, null)`.
  Compact ctor requires `apiPassword` whenever `apiUser` is set.
- `final class MediamtxProxyPublisher implements StreamPublisherPort` (public) — mediamtx dials the
  camera itself; the JVM never decodes the source. `MediamtxProxyPublisher(URI apiBase, URI hlsViewBase, URI whepViewBase, URI playbackViewBase, MediamtxProxySettings settings)`.
  `streamStarted(StreamId, Device)` creates/idempotently re-points a mediamtx path at `device.stream().uri()`,
  then — unless `settings.sourceOnDemand()` — polls readiness (fixed 100ms interval) up to `settings.readyTimeout()`;
  times out by throwing `MediamtxControlApiException`. `publish` is an intentional no-op. `streamEnded`
  deletes the path, swallowing `MediamtxControlApiException` (logs `WARNING`). `viewUrl`/`whepUrl`/`playbackUrl`
  delegate to the same `MediamtxUrls`/`MediamtxPlaybackUrls` helpers `MediamtxStreamPublisher` uses (same
  path name, `streamId.value()`, regardless of which publisher created it). `proxiesSource(Device)` always `true`.
- `final class PublisherRouter implements StreamPublisherPort` (public) — the one `StreamPublisherPort`
  `vision-app` wires. `PublisherRouter(StreamPublisherPort directPublisher, StreamPublisherPort proxyPublisher, boolean sourceProxyEnabled)`.
  Routes to `proxyPublisher` iff `sourceProxyEnabled && device.stream().protocol().equals("rtsp")`, else
  `directPublisher`. `proxiesSource(Device)` evaluates the same rule, pure, called by the application
  layer *before* `streamStarted`. `streamStarted` remembers which publisher a `StreamId` routed to (a
  `ConcurrentHashMap`) so later `publish`/`streamEnded`/URL calls (which carry only a `StreamId`) stay
  consistent; an unrouted `StreamId` falls back to `directPublisher`.
- `final class PublishStatusProvider implements SubsystemStatusPort` (public) — `video-publish`'s health
  self-report for `GET /api/system/status`. `PublishStatusProvider(MediamtxStreamPublisher publisher)` —
  takes the **concrete** class, not the `StreamPublisherPort` interface (`streamsInOutage()` isn't on
  that port, and neither `MediamtxProxyPublisher` nor `PublisherRouter` tracks per-stream outage state
  the same way). `total == 0` or no stream in outage → `Health.OK`; otherwise **always `Health.DEGRADED`,
  never `Health.DOWN`** (losing publish for one stream is degraded availability for that stream, not a
  platform-wide outage) — `detail` names the actual `StreamId`s in outage.
- `final class MediamtxLiveFrameGrabber` (public) — `MediamtxLiveFrameGrabber(URI rtspBase)` /
  `(URI rtspBase, Duration connectTimeout, Duration readTimeout)`. `grab(StreamId): Optional<VideoFrame>`
  opens `{rtspBase}/{streamId}` as an RTSP **read** client (the same address a push publishes to, or a
  proxied path is reachable at) and decodes exactly one frame (`grabImage()`, `rtsp_transport=tcp` fixed,
  BGR24, `FrameConverter.copyBgr24`). Never throws — an unreachable base or no decodable video returns
  `Optional.empty()`, logged once at `WARNING`. `capturedAt` is stamped at grab time (`Instant.now()`),
  not derived from the RTSP PTS. Fills the gap proxy mode opens (nothing in the JVM decodes a proxied
  stream, so `StreamPipeline`'s cached frames would sit empty) — **not yet wired into `StreamService`**,
  this class only supplies the capability.
- `public final class MediamtxReaderProbe` — `(URI apiBase, String apiUser, String apiPassword)`, one
  method `boolean hasReaders(StreamId)` (a stream's id is its mediamtx path name). Exists because WHEP/
  WebRTC viewers connect straight to mediamtx — no SSE connection, no app request — so they are
  invisible to every in-JVM demand signal an idle-stream policy might otherwise use; wanted in every
  mode (push-mode streams have WHEP viewers too), so it is its own class rather than a method on
  `MediamtxProxyPublisher`, which is only wired in proxy mode.
- `public record PublishSettings(Encoder encoder, Resilience resilience, Cadence cadence)` — the single
  source for every `vision.publish.encoder.*`/`.resilience.*`/`.cadence.*` tunable; a `null` component
  normalizes to that nested record's own `defaults()` in the compact ctor. `static defaults()`.
  - `record Encoder(int crf, long maxrateBitsPerSecond, long bufsizeBits, String preset, int gopSeconds, int scenecutThreshold)`
    — `defaults()` = `(21, 6_000_000, 12_000_000, "veryfast", 1, 0)`.
  - `record Resilience(Duration initialBackoff, Duration maxBackoff)` — `defaults()` = `(500ms, 10s)`.
  - `record Cadence(int measurementFrames, double minMeasuredFrameRateFps, double maxMeasuredFrameRateFps, double driftRatioHigh, double driftEwmaAlpha, Duration sustainedDriftWindow, double defaultFrameRateFps)`
    — `defaults()` = `(5, 1.0, 120.0, 1.5, 0.2, 2s, 15.0)` (`driftRatioLow` is not a field — derived as
    `1.0 / driftRatioHigh` inside `CadenceEstimator`).

## Conventions
- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- **`System.Logger`, not slf4j** — `private static final System.Logger LOG = System.getLogger(...)`.
- `FFmpegFrameRecorder` is created lazily on the first `publish()` call for a stream, once frame
  dimensions are known — not in `streamStarted`.
- Different `StreamId`s are tracked independently in a `ConcurrentHashMap`; per-stream state needs no
  locking because the port contract serializes calls per stream.
- Every collaborator that used to hardcode a tunable as a `private static final` constant now takes the
  matching `PublishSettings` nested record via an additional constructor/method overload; the
  no-settings overload is kept and delegates to that record's own `defaults()`, so no call site is forced
  to change. Follow this pattern for any new tunable rather than adding another bare constant.

## Gotchas
- **Native FFmpeg log level pinned to `AV_LOG_ERROR`** (not `WARNING`: swscale emits a benign per-frame
  `deprecated pixel format used` warning for yuvj-tagged inputs that JavaCV cannot suppress per-context).
  `MediamtxStreamPublisher`'s constructor calls the idempotent package-private `static void ensureQuietLogging()`
  (`avutil.av_log_set_level`, a `synchronized` static-boolean guard). Without it, every recorder
  start/stop dumps libx264/muxer INFO banners to stdout/stderr, easily mistaken for errors. Deliberately
  duplicated (not shared) in `adapter-rtsp`'s `RtspVideoSource` — adapters must not depend on each other.
  `org.bytedeco.javacv.FFmpegLogCallback.set()` was evaluated and rejected: it does no level filtering of
  its own (still needs the same `av_log_set_level` call), and once active it fragments FFmpeg's own
  multi-part log lines (e.g. the muxer's `Output #0 ...` block) into separately-prefixed partial lines —
  objectively worse output for no offsetting benefit.
- **Nothing escapes** `publish`/`streamStarted`/`streamEnded` on `MediamtxStreamPublisher` — every
  JavaCV/FFmpeg/frame-conversion exception is swallowed so a broken or absent mediamtx never takes down
  the owning pipeline. Failures log at `WARNING` once per outage (`PublishBackoff.beginOutage()`), not
  once per dropped frame; recovery logs once at `INFO` (`endOutage()`). `MediamtxProxyPublisher` is the
  deliberate exception for a readiness timeout — see its own entry above.
- **PTS quantization**: `FFmpegFrameRecorder.setTimestamp(long)` rounds the microsecond value down to a
  whole frame number (`round(timestampMicros * fps / 1_000_000)`) before handing it to the muxer. Two
  frames whose `capturedAt` land under one frame period apart (routine under bursty delivery) can round
  to the *same* frame number, and the muxer rejects the second write (`error -22`, non-monotonic DTS).
  Bumping the microsecond value by 1 does **not** fix it — still rounds to the same frame. The fix, in
  `CadenceEstimator.nextTimestampMicros`: track the last *frame number* actually emitted; on a collision,
  advance to `lastFrameNumber + 1` and convert that back to microseconds — frame numbers are always
  strictly increasing, so they never collide regardless of burst timing.
- **No epoch reset on reconnect**: `firstCapturedAt`/`lastFrameNumber` live on `StreamState.cadence` and
  are set once, lazily, on the *first* `nextTimestampMicros` call ever made for that instance.
  `onPublishFailed` nulls `state.recorder` on a failed publish/reconnect but does **not** reset those
  fields — only `streamStarted` (a brand-new `StreamState`) resets the PTS epoch. A mid-stream reconnect
  therefore keeps counting frame numbers from the original epoch, which is what keeps the new recorder's
  PTS monotonic relative to what mediamtx already received before the drop.
- `viewUrl` strips any trailing slash from `hlsViewBase` before appending — don't double up `//`.
  `hlsViewBase` may be a relative URI (e.g. `/hls`, `vision-app`'s `HlsProxyController` default) — string
  concatenation works identically for a relative or absolute base.
- **`whepUrl`/`playbackUrl` are pure string formatting — neither makes the target actually reachable, and
  neither is ever proxied.** Both are handed to the viewer's browser verbatim, unlike `hlsViewBase`,
  which `vision-app` typically points at an app-relative proxy path. Getting a real browser WHEP session
  working through Docker needs a 1:1 (not remapped) host↔container UDP port mapping for mediamtx's ICE
  port plus `MTX_WEBRTCADDITIONALHOSTS` set to an address the browser can reach — see `docker-compose.yml`'s
  comments. mediamtx's own default CORS (`webrtcAllowOrigins: ["*"]`) is permissive, so no CORS handling
  is needed here for WHEP, unlike HLS (`HlsProxyController` exists for browser-reachability/port reasons,
  not CORS).
- **GOP must be kept in sync with mediamtx's `hlsSegmentDuration` by hand.** `PublishSettings.Encoder.gopSeconds`
  (default 1) is a Java-side value; `docker-compose.yml`'s `MTX_HLSSEGMENTDURATION` is a separate,
  independent mediamtx-side value. Nothing enforces they stay equal — an HLS segment can never be
  *shorter* than the keyframe interval it's cut on, so if one changes without the other, segments
  silently widen back out to the GOP's duration. **mediamtx's own env var naming**: `MTX_` + the struct
  field's `json` tag uppercased verbatim (e.g. `hlsVariant` → `MTX_HLSVARIANT`, not `MTX_HLS_VARIANT`).
  **`MTX_HLSSEGMENTCOUNT` has a hard floor of 7 under `hlsVariant: lowLatency`** (`gohlslib`'s
  `Muxer.Start()` refuses below 7) — don't "optimize" it down to shrink the live buffer window; it won't
  reduce latency (the player's own `liveSyncDurationCount` governs that) and will break the stream instead.
- **Recording finalization happens on path unpublish, not on a fixed segment boundary.** mediamtx's
  default `recordSegmentDuration` is 1 hour, so a short recording only becomes fetchable via `playbackUrl`
  once its path is unpublished (`MediamtxStreamPublisher#streamEnded` does this by stopping the recorder,
  which closes the RTSP push cleanly). A still-live path's playback window 404s indefinitely — this is
  why `MediamtxLiveFrameGrabber` exists as a separate live-RTSP-read path rather than reusing
  `MediamtxReplayFrameExtractor` against "now minus a second" for a proxied stream.
- **A proxied path's `source` URL is dialed from *inside mediamtx's own container network namespace*.**
  A device source URL that happens to be another path on the same mediamtx instance (a docker-free
  "camera" stand-in, as this module's own tests use) must be addressed by mediamtx's container-internal
  RTSP port, never by the host-mapped port the test JVM itself uses to reach the same server. The create
  call still succeeds (mediamtx doesn't validate reachability at creation time), so a mismatch here fails
  silently as a readiness timeout, not a create-time error. A real camera has one real address and this
  confusion doesn't arise there.
- **`MediamtxProxyPublisher` skips the readiness poll entirely when `sourceOnDemand` is `true`** —
  mediamtx does not dial the camera until a reader connects, so polling at start time would time out on
  every single start call. Do not "fix" the poll to also run in on-demand mode.
- **Capture→encode lag (`LagTracker`/`PublishDiagnostics`) is not end-to-end glass-to-glass latency.**
  `MediamtxStreamPublisher.writeFrame` measures `now - videoFrame.capturedAt()` right before handing a
  frame to `FFmpegFrameRecorder.record` — everything upstream of that handoff (source capture, ingest
  decode, pipeline, overlay burn-in) is included; everything downstream (mediamtx segmenting, HLS/WHEP
  transport, browser buffering) is invisible to this number by construction. A player's own "behind live"
  estimate is the number to compare against for the full picture; if this module's own p50/p95 sits in
  the tens of milliseconds while the player reports seconds behind, the bottleneck is downstream, not here.

## Status
Fully implemented and green: direct JVM-side push publishing, mediamtx-side pull-proxy publishing
(`PublisherRouter`, off by default via `vision.publish.source-proxy.enabled`), recording + playback URL
formatting, replay frame extraction for CV training, a live un-annotated frame grab for proxied streams
(capability only — not yet wired into `StreamService`), reader-presence probing for the idle-stream
policy, and a mediamtx health self-report for `GET /api/system/status`.
