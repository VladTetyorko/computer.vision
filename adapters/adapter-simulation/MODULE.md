# adapter-simulation

Synthetic video + telemetry sources so every phase is testable/demoable without real hardware.

**Depends on:** vision-domain · **Used by:** vision-app
**Build/test:** `./mvnw -B -pl adapters/adapter-simulation test` (26 tests, verified via a live run)

## API surface
### `com.drones.vision.adapter.simulation`
- `final class SimulatedVideoSource implements VideoSourcePort` — `supports(StreamDescriptor)`, `open(StreamId, StreamDescriptor): Flow.Publisher<VideoFrame>`, `close(StreamId)`. Supports protocol `"sim"`. Options: `width` (int, default `DEFAULT_WIDTH`=640), `height` (int, default `DEFAULT_HEIGHT`=480), `fps` (int, default `DEFAULT_FPS`=15) — package-visible constants, readable from tests.
  - nested `private static final class StreamRuntime` — one per `open()` call: single-thread `ScheduledExecutorService` (`scheduleAtFixedRate`, period `max(1, 1000/fps)` ms) rendering via `FrameRenderer` and submitting to a per-stream `SubmissionPublisher<VideoFrame>`.
- `final class SimulatedTelemetrySource implements TelemetrySourcePort` — public `SimulatedTelemetrySource()` (1 Hz, `DEFAULT_PERIOD_MILLIS`=1000); package-private `SimulatedTelemetrySource(long periodMillis)` test seam (must be > 0). `supports(Device)`, `open(Device): Flow.Publisher<Telemetry>`, `close(DeviceId)`. Requires `Capability.TELEMETRY` **and** `"sim"` protocol. Options (all lenient, see Conventions):
  | Option | Meaning | Default |
  |---|---|---|
  | `lat`/`lon` | circular-track center (double) | `DEFAULT_CENTER_LATITUDE`=50.45 / `DEFAULT_CENTER_LONGITUDE`=30.52; ignored once a valid `route` is given |
  | `route` | `lat,lon[,altM];lat,lon[,altM];…`, ≥2 points (docs/CYCLES-PLAN.md §7, CT-a) | absent/malformed → no route, circular track applies (back-compat) |
  | `speedMps` | cruise speed along `route` (double, must be positive) | `RoutePlan.DEFAULT_SPEED_MPS`=12.0 |
  | `routeMode` | `loop`/`bounce`/`once`, case-insensitive, only meaningful with `route` | `RoutePlan.DEFAULT_MODE`=`LOOP` |
  | `batteryDrainPerSecond` | drain rate, whether flying the circle or a route | `BATTERY_DRAIN_PERCENT_PER_SECOND`=0.05%/s from 100% |

  Circular track (back-compat default, unchanged since before CT-a): `TRACK_RADIUS_METERS`=200.0, one lap per `TICKS_PER_LAP`=60 samples.
  - nested `private static final class DeviceRuntime` — same scheduled-executor-per-`SubmissionPublisher` shape as `StreamRuntime`; each tick either computes the next circular-track point (no valid `route`) or advances a cumulative `distanceMeters` by `speedMps * tickSeconds` and asks the parsed `RoutePlan` for the position there.
- `final class RoutePlan` (package-private, docs/CYCLES-PLAN.md §7, CT-a) — a pure, framework-free, scheduler-free flight-route engine, fully unit-tested without any thread:
  - `static RoutePlan parse(String routeOption, String modeOption)` — parses the `route`/`routeMode` option strings; returns `null` (never throws) when `routeOption` is absent or malformed (fewer than 2 points, unparseable number, wrong field count per point) — signals the caller to fall back to the circular track; an unrecognized `modeOption` falls back to `DEFAULT_MODE` (`LOOP`), also without throwing
  - `Position positionAt(double metersAlongRoute)` — returns `record Position(latitude, longitude, altitudeMeters, headingDegrees)` interpolated along the route at that cumulative distance, folded per `RouteMode`
  - nested `enum RouteMode { LOOP, BOUNCE, ONCE }` and `record Waypoint(latitude, longitude, altitudeMeters)` — both adapter-local; deliberately **not** shared with vision-application's own `RouteMode`/`Waypoint` types of the same name, since this module depends only on vision-domain (see Gotchas)
  - Distance/bearing between waypoints use a local equirectangular approximation (adequate at flight-plan scale, same technique the circular track already uses); bearing is geographic (0=north, 90=east, clockwise)
  - `LOOP` traverses the path plus a closing end→start leg, wrapping via `distance % lapLength`; `BOUNCE` folds into a "there and back" period and mirrors the second half onto the same forward segments with a 180°-reversed heading; `ONCE` clamps distance to the path length and holds at the end
  - Altitude interpolates linearly only when **both** a segment's endpoints give one; otherwise `null`
- `final class FrameRenderer` (package-private) — `static byte[] renderJpeg(int width, int height, long sequence) throws IOException`: Java2D-rendered moving filled circle + frame counter + wall-clock time, JPEG-encoded via `ImageIO`.

## Conventions
- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- Each `open()` call creates an independent runtime keyed by `StreamId`/`DeviceId` in a `ConcurrentHashMap`; a stale runtime already present under the same id is defensively closed before the new one starts.
- `close()` is idempotent via `AtomicBoolean closed` CAS; daemon threads named `sim-video-<id>` / `sim-telemetry-<id>`.
- Malformed/blank numeric options silently fall back to the default (no exception) — see `intOption`/`doubleOption`/`positiveDoubleOption` (the last also rejects a parsed non-positive value, used for `speedMps`). A malformed/absent `route` string is the same lenient idiom at a larger grain: `RoutePlan.parse` returns `null` instead of throwing, and the adapter falls back to the circular track — strict validation of user-supplied route data lives one layer up, in vision-application/vision-api (`TelemetryPlan`/`Waypoint`/`StartSimulationRequest#toSpec()`), where the option strings this module parses are themselves produced from already-validated input.

## Gotchas
- `SimulatedTelemetrySource`'s package-private constructor is a deliberate test seam (`SimulatedTelemetrySourceTest.fastSource`) — same-package tests drop the period to tens of ms to observe several samples without waiting real seconds; production always uses the public no-arg 1 Hz constructor.
- A render/sample failure (`IOException`/`RuntimeException`) closes the publisher exceptionally and self-closes the runtime rather than crash the scheduler thread.
- `FrameRenderer` uses pure in-memory `BufferedImage`/`Graphics2D` (no AWT display/toolkit) — safe headless (CI/containers).
- `RoutePlan`'s tick math is purely a function of tick *count*, not wall-clock time: `DeviceRuntime` advances `distanceMeters` by `speedMps * (periodMillis / 1000.0)` once per scheduled tick regardless of how much real time actually elapsed between ticks, so `positionAt` results are deterministic given the tick index — useful for tests (see `SimulatedTelemetrySourceTest`'s route tests, which assert step distances precisely) but means a caller relying on wall-clock elapsed time to predict "how far along the route" would be wrong if the scheduler ever falls behind (it won't meaningfully at 1Hz in practice).
- This module has no dependency on vision-application (see the module table above), so `RoutePlan.RouteMode`/`RoutePlan.Waypoint` (adapter-local, string-driven) are intentionally distinct types from vision-application's `RouteMode`/`Waypoint` (domain-shaped, used by `TelemetryPlan`) even though the names and (for `Waypoint`) shape match — the two layers communicate only through `StreamDescriptor.options()` strings, never a shared Java type, per the hexagonal dependency rule.

## Status
Fully implemented (Task T5), no placeholders. All video frames are `PixelFormat.JPEG`; every telemetry sample always populates lat/lon/heading/battery (never null). docs/CYCLES-PLAN.md §7 (CT-a) added configurable flight plans (`route`/`speedMps`/`routeMode`/`batteryDrainPerSecond` options, the `RoutePlan` engine) on top of the pre-existing circular-track behavior, which stays the unmodified back-compat default — `RoutePlanTest` (13 tests) plus 5 new `SimulatedTelemetrySourceTest` cases cover it, bringing this module to 26 tests total.
