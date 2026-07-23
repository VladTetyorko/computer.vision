# adapter-v4l2

USB/V4L2 local camera ingest — docs/MVP2-PLAN.md X-b. **RX only**: a local capture device has no
wire to transmit *to* — the same "no TX half" situation as `adapter-simulation`'s `sim` source and
`adapter-rtsp`'s `file` source (`docs/CYCLES-PLAN.md` §0's RX/TX doctrine). There is no
`V4l2FeedTransmitter`, and none is planned — you cannot "simulate" a webcam plug-in the way a
network protocol can be simulated by pushing to a local server; the only way to exercise this
adapter for real is a real device node (see "v4l2loopback test recipe" below).

**Depends on:** vision-domain, org.bytedeco:javacv, org.bytedeco:ffmpeg-platform-gpl (same managed
versions as `adapter-rtsp`: `${javacv.version}`=1.5.10, `${ffmpeg.version}`=6.1.1-1.5.10, already
pinned in the root pom) · **Used by:** vision-app
**Build/test:** `./mvnw -B -pl adapters/adapter-v4l2 test` — 19 tests across 3 classes:
`V4l2VideoSourceTest` 10 (`supports()` matrix + construction/validation, no device I/O),
`V4l2FrameConverterTest` 8 (synthetic-frame unit tests, no device I/O),
`V4l2LoopbackIntegrationTest` 1 (real-device ingest, assumption-checked — see below). Verified via
a live run: 0 failures, 0 skipped **in this environment**, because a real, readable `/dev/video0`
happened to exist here (see below); elsewhere it skips cleanly.

## Deviation from the plan's brief — protocol/URI shape (read this first)

docs/MVP2-PLAN.md X-b's brief proposed protocol `"usb"` with `uri = v4l2:///dev/videoN`. **This
adapter does not implement that shape.** Reading `adapter-discovery`'s already-shipped
`V4l2Scanner` (its source and its own test, `V4l2ScannerTest`) shows it actually emits:

- `StreamDescriptor.protocol()` = `"v4l2"` (not `"usb"`)
- `StreamDescriptor.uri()` = a `file:` scheme URI naming the real device node, e.g.
  `file:/dev/video0` (not a `v4l2://` scheme URI)

`DiscoveredDeviceResponse` (vision-api) flattens `suggestedStream` verbatim — nothing in the
discovery→registration path rewrites the protocol or URI in between — so a user who runs
"Discover" and clicks "register" on a V4L2 hit ends up with a `Device` whose `StreamDescriptor` is
exactly what `V4l2Scanner` produced. For the plan's own done-criterion ("plugging a webcam +
'Discover' + one click = live wall tile") to actually work, `V4l2VideoSource.supports()` **must**
accept that real shape, not the brief's originally-imagined one. This class therefore supports
`protocol = "v4l2"` with any `file:`-scheme URI (not just `/dev/videoN` paths — the same
scheme-only check `adapter-rtsp`'s `FfmpegVideoSource` uses for its own `"file"` protocol), and
explicitly rejects both `protocol = "usb"` and a `v4l2://` scheme URI (see
`V4l2VideoSourceTest#rejectsTheOriginallyProposedUsbProtocolString`/
`#rejectsTheOriginallyProposedV4l2SchemeUri`). `docs/MVP2-PLAN.md`'s X-b row has been corrected in
place to record this (see its own done-note).

No collision risk with `FfmpegVideoSource`'s `"file"` protocol: the two adapters key off different
`protocol()` strings (`"v4l2"` vs `"file"`), so `VideoSourceRegistry`'s first-match `supports()`
scan never has to choose between them for the same descriptor.

## API surface

### `com.drones.vision.adapter.v4l2`
- `final class V4l2VideoSource implements VideoSourcePort` — supports protocol `"v4l2"` with any
  `file:`-scheme URI. `supports(StreamDescriptor)`, `open(StreamId, StreamDescriptor):
  Flow.Publisher<VideoFrame>`, `close(StreamId)`.
  - package-private test seam: `Flow.Publisher<VideoFrame> openAny(StreamId id, URI uri,
    Map<String,String> options)` — runs the exact production grab loop against any `file:` URI
    naming a device path, skipping the protocol check (mirrors `FfmpegVideoSource`'s own seam).
  - Options (all optional, straight FFmpeg `v4l2`-demuxer AVOptions, no defaults — an unset option
    lets the driver's own default win, deliberately different from `adapter-rtsp`'s
    default-having options, since there's no one sensible default resolution/format across
    arbitrary webcams):
    - `video_size` — capture resolution, e.g. `"1280x720"`.
    - `framerate` — capture frame rate, e.g. `"30"`.
    - `input_format` — raw capture pixel format requested from the driver, e.g. `"mjpeg"`,
      `"yuyv422"` — independent of the `BGR24` format this class always decodes *to* before
      publishing (see Conventions).
  - **No `timeout`/`rw_timeout` option**, unlike `adapter-rtsp`'s `rtsp` protocol — see Gotchas for
    why.
  - nested `private static final class StreamRuntime` — one dedicated platform thread
    (`v4l2-video-<id>`, not virtual — decoding is CPU-bound) per open, running a blocking
    `FFmpegFrameGrabber` loop (format `"v4l2"`, filename = the device path resolved from the
    descriptor's `file:` URI); feeds a `SubmissionPublisher<VideoFrame>` built with buffer capacity
    4 and `publisher.offer(frame, (subscriber, dropped) -> true)` for drop-newest backpressure —
    identical shape to `FfmpegVideoSource`'s `StreamRuntime`, minus pacing/looping (neither applies
    to a live local device).
- `final class V4l2FrameConverter` (package-private, stateless) — `static ByteBuffer
  copyBgr24(Frame frame)`: byte-for-byte duplicate of `adapter-rtsp`'s `FrameConverter.copyBgr24`
  (see Gotchas for why it's duplicated, not shared).

## Conventions

- Plain class, no Spring — instantiated directly by `vision-app`'s wiring config
  (`WiringConfiguration#v4l2VideoSource`).
- Grab loop uses `grabber.grabImage()`, not `grab()` — same rationale as `FfmpegVideoSource`
  (skip non-video packets), even though a v4l2 capture device is unlikely to interleave audio the
  way an mp4 container does; kept for consistency and because it costs nothing.
- Every grabbed frame is decoded to `PixelFormat.BGR24` (`grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24)`)
  and copied via `V4l2FrameConverter.copyBgr24` before being wrapped in an immutable `VideoFrame` —
  the grabber reuses its native image buffers across `grab()` calls, so the raw buffer must never
  be handed out directly (same mandatory-copy rule as `adapter-rtsp`).
- `close()`: sets `stopRequested`, interrupts the grab thread (best-effort), joins up to 20s,
  releases the grabber, closes the publisher. Idempotent via `AtomicBoolean closed`.
- Native FFmpeg log level pinned to `AV_LOG_ERROR` once per JVM (`ensureQuietLogging()`), same
  idiom and same reasoning as `adapter-rtsp`'s `FfmpegVideoSource`/`adapter-publish-hls`'s
  `MediamtxStreamPublisher` — deliberately duplicated here too (see Gotchas).

## Gotchas

- **Duplication, by design (adapters must not depend on each other, CLAUDE.md's dependency rule):**
  this module reimplements, rather than imports, three things `adapter-rtsp` already has —
  `FrameConverter.copyBgr24` (byte-for-byte identical, renamed `V4l2FrameConverter`), the
  `ensureQuietLogging()` native-log-quieting guard, and the `StreamRuntime`
  grab-thread/publisher/backpressure/close shape. This is the same tradeoff `adapter-mavlink`'s
  MODULE.md documents for its own duplicated pieces (e.g. its route-math technique borrowed from
  `adapter-simulation`) — a handful of small, stable, independently-testable blocks copy-pasted
  rather than factored into a shared module that would violate the adapter-isolation rule.
- **No `timeout`/`rw_timeout` option, unlike `adapter-rtsp`'s `rtsp` protocol.** Those are AVOptions
  of network-facing FFmpeg protocols (the RTSP demuxer's own `timeout` option, and the generic
  avio-layer `rw_timeout`); the `v4l2` demuxer reads a local character device directly via
  `ioctl()`/`read()`, not through avio, and FFmpeg's `v4l2` demuxer exposes no equivalent read-
  timeout AVOption (confirmed by grepping `FFmpegFrameGrabber`'s own source for `timeout` — the
  inherited `FrameGrabber.setTimeout(int)` field exists but is never read anywhere in
  `FFmpegFrameGrabber`, i.e. it's a no-op for this grabber regardless of protocol). A mid-stream USB
  unplug on Linux surfaces as an ordinary I/O error from the kernel driver on the very next
  read — not an indefinitely stuck socket the way a broken TCP connection can be — so the grab
  loop's existing "any exception → `publisher.closeExceptionally(e)`" path (the same one
  `FfmpegVideoSource` uses) already satisfies the port's "unrecoverable failure → onError, never a
  hang" contract without needing RTSP's watchdog-timeout workaround. This is a reasoned deviation
  based on how the v4l2 demuxer is documented to work, not something exercised by an actual
  unplug-mid-stream test in this task (doing that reliably needs real, unpluggable USB hardware or
  root-level driver manipulation, out of reach in an automated test).
- **`Paths.get(uri)` for device-path resolution** works for both `file:/dev/video0` (discovery's
  actual, authority-less form) and `file:///dev/video0` (the more common triple-slash form) — both
  parse to the same absolute path per `java.io.File(URI)`'s documented rules (absolute hierarchical
  URI, scheme `file`, undefined/empty authority, non-empty path); `V4l2VideoSourceTest`'s
  `supportsAnyFileSchemeUriRegardlessOfPathShape` covers this.
- **The v4l2loopback test recipe genuinely worked in this environment** — see "v4l2loopback test
  recipe" below.

## v4l2loopback test recipe

`V4l2LoopbackIntegrationTest` uses an **assumption-check pattern**: rather than assuming a webcam
or loopback device exists, its JUnit `@EnabledIf`-gated condition (`v4l2DeviceAvailable()`) probes
before the test class is even allowed to run, and the whole class skips cleanly (never fails) with
a message pointing back at this recipe when nothing usable is found. The probe:

1. Honors `VISION_V4L2_TEST_DEVICE=/dev/videoN` if set (skip scanning, use exactly that path).
2. Otherwise scans every `/dev/video*` node on the host.
3. For each candidate, spends up to 3 real seconds on a background thread trying to `start()` an
   `FFmpegFrameGrabber` against it (format `v4l2`) and pull one frame; a candidate that isn't
   readable, that throws, or that doesn't finish within the budget (protects the build from a
   device that blocks indefinitely) is treated as unusable and the scan moves to the next one.
4. The first candidate that yields a real frame is remembered and reused by the actual `@Test`
   method, which then exercises the *production* `V4l2VideoSource.open()` path (not a shortcut) and
   asserts a real `VideoFrame` arrives with `PixelFormat.BGR24` and positive dimensions.

**To set up a device to run this locally (Linux):**

```bash
sudo modprobe v4l2loopback video_nr=9 card_label="vision-test-loopback"
# feed a synthetic test pattern into it, kept running in the background:
ffmpeg -re -f lavfi -i testsrc=size=640x480:rate=30 -f v4l2 /dev/video9
```

Then either let the scan find it automatically, or pin it explicitly:
`VISION_V4L2_TEST_DEVICE=/dev/video9 ./mvnw -B -pl adapters/adapter-v4l2 test`. A real, unoccupied
USB webcam works exactly the same way — no loopback module needed, just point
`VISION_V4L2_TEST_DEVICE` at wherever the kernel assigned it (or let the scan find it).

**What actually happened when this task ran the test**: this sandbox turned out to already have a
real, group-readable `/dev/video0..3` (confirmed via `ffprobe -f v4l2 -list_formats all -i
/dev/video0`, which reported real MJPEG/YUYV modes) — no `modprobe`/`ffmpeg` setup step was needed
here, the scan found `/dev/video0` on its first attempt and the test genuinely exercised the real
FFmpeg `v4l2` decode path end to end, not a skip. On a box with no camera and no loopback module,
the exact same test skips instead of failing — both outcomes are "correct" for this test; only a
red result would indicate a real bug.

## Device permissions (host setup note, not code)

On Linux, `/dev/video*` nodes are typically owned by the `video` group with `crw-rw----`
permissions. The user running `vision-app` (or this module's tests) needs to be a member of that
group (`sudo usermod -aG video $USER`, then re-login) to open a device it doesn't already have
broader ACL access to — `V4l2VideoSource` itself does nothing about this (no privilege
elevation, no udev rule management); a permission-denied device simply fails the FFmpeg
`grabber.start()` call, which the grab loop's normal exception path turns into `onError`.

## Status

Fully implemented per docs/MVP2-PLAN.md X-b: `V4l2VideoSource` (RX only, no TX half — see the
module's own opening paragraph for why), wired into `vision-app`
(`WiringConfiguration#v4l2VideoSource`, joining `videoSourceRegistry`'s `List<VideoSourcePort>`).
19 tests, all passing (`./mvnw -B -pl adapters/adapter-v4l2 test`).

**Deviation from the brief, corrected in `docs/MVP2-PLAN.md`'s own X-b row**: protocol `"v4l2"`
with a `file:`-scheme URI (matching `adapter-discovery`'s real `V4l2Scanner` emission), not the
brief's originally-proposed `"usb"`/`v4l2://` shape — see "Deviation from the plan's brief" above
for the full writeup and why matching discovery, not the brief's guess, is what actually makes
"Discover → register → stream" work.
