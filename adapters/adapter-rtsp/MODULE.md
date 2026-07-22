# adapter-rtsp

RTSP/RTP video ingest (IP cameras, drone companions) via JavaCV/FFmpeg.

**Depends on:** vision-domain, org.bytedeco:javacv, org.bytedeco:ffmpeg-platform-gpl · **Used by:** vision-app
**Build/test:** `./mvnw -B -pl adapters/adapter-rtsp test` — 13 test methods across 3 classes (`FrameConverterTest` 8, `RtspVideoSourceTest` 4, `RtspVideoSourceLiveManualTest` 1 `@Disabled`), verified via a live run: 0 failures, 1 skipped.

## API surface
### `com.drones.vision.adapter.rtsp`
- `final class RtspVideoSource implements VideoSourcePort` — supports protocol `"rtsp"`. `supports(StreamDescriptor)`, `open(StreamId, StreamDescriptor): Flow.Publisher<VideoFrame>`, `close(StreamId)`.
  - package-private test seam: `Flow.Publisher<VideoFrame> openAny(StreamId id, URI uri, Map<String,String> options)` — runs the exact production grab loop against any URI (skips the `"rtsp"` protocol check), letting tests exercise the real FFmpeg decode path against a local file.
  - Options (both optional): `rtsp_transport` (default `DEFAULT_RTSP_TRANSPORT`="tcp"), `timeout` in **microseconds** (default `DEFAULT_TIMEOUT_MICROS`="10000000" = 10s; applied to both the RTSP demuxer's `timeout` option and the generic `rw_timeout` option). Both only set when `uri.getScheme()` is `"rtsp"`.
  - nested `private static final class StreamRuntime` — one dedicated platform thread (`rtsp-video-<id>`, not virtual — decoding is CPU-bound) per open, running a blocking `FFmpegFrameGrabber` loop; feeds a `SubmissionPublisher<VideoFrame>` built with `PUBLISHER_BUFFER_CAPACITY`=4 and `publisher.offer(frame, (subscriber, dropped) -> true)` for drop-newest backpressure.
- `final class FrameConverter` (package-private, stateless) — `static ByteBuffer copyBgr24(Frame frame)`: copies a JavaCV `Frame`'s BGR24 pixel data into a fresh, tightly-packed heap `ByteBuffer` (strips row stride padding). Throws `IllegalArgumentException` for null frame, missing image data, invalid dimensions, non-3-channel data, or a non-byte-backed image buffer.

## Conventions
- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- Grab loop runs until `stopRequested` or `grabber.grab()` returns `null` (graceful EOF, e.g. a finite file source); audio/data-only frames (empty/null `frame.image`) are silently skipped.
- `close()`: sets `stopRequested`, interrupts the grab thread (best-effort — native `grab()` may not respond), joins up to `CLOSE_JOIN_TIMEOUT_MILLIS`=20s, releases the grabber, closes the publisher. Idempotent via `AtomicBoolean closed`.
- **Native FFmpeg log level pinned to WARNING**: `RtspVideoSource`'s no-arg constructor calls the idempotent package-private `static void ensureQuietLogging()`, which calls `avutil.av_log_set_level(avutil.AV_LOG_WARNING)` once per JVM (a `synchronized` static-boolean guard makes every call after the first a no-op). This silences FFmpeg's default AV_LOG_INFO chatter (codec/format banners) while leaving warnings/errors on stdout/stderr untouched. Deliberately duplicated in `adapter-publish-hls`'s `MediamtxStreamPublisher` (adapters must not depend on each other) — see that module's MODULE.md Gotchas for the empirical reasoning behind choosing plain `av_log_set_level` over `org.bytedeco.javacv.FFmpegLogCallback.set()`.

## Gotchas
- **Mandatory copy**: `FFmpegFrameGrabber` reuses its native image buffers across `grab()` calls. Every grabbed frame's payload MUST be copied via `FrameConverter.copyBgr24` before being wrapped in an immutable `VideoFrame` (whose compact constructor stores the buffer for the record's lifetime) — never hand out the raw `Frame#image` buffer directly.
- Backpressure is drop-**newest**-on-full via `SubmissionPublisher.offer(..., onDrop -> true)` with a buffer capacity of only 4 — a slow subscriber never blocks capture, per `VideoSourcePort`'s latest-wins contract.
- First FFmpeg-touching test in a fresh module pays real cost for native library extraction — `RtspVideoSourceTest.AWAIT_SECONDS`=60 is deliberately generous for exactly this reason; once extracted, runs are fast (~0.25s observed in this environment).
- `resolveFilename(URI)` special-cases the `file:` scheme (via `Paths.get(uri)`) so the `openAny` test seam can grab from a local file exactly like a real RTSP source.

## Status
Fully implemented. `RtspVideoSourceLiveManualTest` is `@Disabled` by design — it documents (in its class javadoc) how to spin up a local mediamtx + ffmpeg publish loop to manually re-enable it; there is no live camera/RTSP server in CI.
