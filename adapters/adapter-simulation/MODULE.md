# adapter-simulation

Synthetic video + telemetry sources so every phase is testable/demoable without real hardware.

**Depends on:** vision-domain · **Used by:** vision-app
**Build/test:** `./mvnw -B -pl adapters/adapter-simulation test` (8 tests, verified via a live run)

## API surface
### `com.drones.vision.adapter.simulation`
- `final class SimulatedVideoSource implements VideoSourcePort` — `supports(StreamDescriptor)`, `open(StreamId, StreamDescriptor): Flow.Publisher<VideoFrame>`, `close(StreamId)`. Supports protocol `"sim"`. Options: `width` (int, default `DEFAULT_WIDTH`=640), `height` (int, default `DEFAULT_HEIGHT`=480), `fps` (int, default `DEFAULT_FPS`=15) — package-visible constants, readable from tests.
  - nested `private static final class StreamRuntime` — one per `open()` call: single-thread `ScheduledExecutorService` (`scheduleAtFixedRate`, period `max(1, 1000/fps)` ms) rendering via `FrameRenderer` and submitting to a per-stream `SubmissionPublisher<VideoFrame>`.
- `final class SimulatedTelemetrySource implements TelemetrySourcePort` — public `SimulatedTelemetrySource()` (1 Hz, `DEFAULT_PERIOD_MILLIS`=1000); package-private `SimulatedTelemetrySource(long periodMillis)` test seam (must be > 0). `supports(Device)`, `open(Device): Flow.Publisher<Telemetry>`, `close(DeviceId)`. Requires `Capability.TELEMETRY` **and** `"sim"` protocol. Options: `lat`/`lon` (double, defaults `DEFAULT_CENTER_LATITUDE`=50.45 / `DEFAULT_CENTER_LONGITUDE`=30.52). Circular track: `TRACK_RADIUS_METERS`=200.0, one lap per `TICKS_PER_LAP`=60 samples, battery drains `BATTERY_DRAIN_PERCENT_PER_SECOND`=0.05%/s from 100%.
  - nested `private static final class DeviceRuntime` — same scheduled-executor-per-`SubmissionPublisher` shape as `StreamRuntime`.
- `final class FrameRenderer` (package-private) — `static byte[] renderJpeg(int width, int height, long sequence) throws IOException`: Java2D-rendered moving filled circle + frame counter + wall-clock time, JPEG-encoded via `ImageIO`.

## Conventions
- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- Each `open()` call creates an independent runtime keyed by `StreamId`/`DeviceId` in a `ConcurrentHashMap`; a stale runtime already present under the same id is defensively closed before the new one starts.
- `close()` is idempotent via `AtomicBoolean closed` CAS; daemon threads named `sim-video-<id>` / `sim-telemetry-<id>`.
- Malformed/blank numeric options silently fall back to the default (no exception) — see `intOption`/`doubleOption`.

## Gotchas
- `SimulatedTelemetrySource`'s package-private constructor is a deliberate test seam (`SimulatedTelemetrySourceTest.fastSource`) — same-package tests drop the period to tens of ms to observe several samples without waiting real seconds; production always uses the public no-arg 1 Hz constructor.
- A render/sample failure (`IOException`/`RuntimeException`) closes the publisher exceptionally and self-closes the runtime rather than crash the scheduler thread.
- `FrameRenderer` uses pure in-memory `BufferedImage`/`Graphics2D` (no AWT display/toolkit) — safe headless (CI/containers).

## Status
Fully implemented (Task T5), no placeholders. All video frames are `PixelFormat.JPEG`; every telemetry sample always populates lat/lon/heading/battery (never null).
