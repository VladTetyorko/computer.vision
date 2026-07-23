# adapter-mavlink

MAVLink 2 UDP telemetry ingest (RX) and flight-plan transmit simulator (TX) — docs/MVP2-PLAN.md X-a, the first *real drone* protocol under the RX/TX doctrine (`docs/CYCLES-PLAN.md` §0).

**Depends on:** vision-domain, `io.dronefleet.mavlink:mavlink:1.1.11` · **Used by:** vision-app
**Build/test:** `./mvnw -B -pl adapters/adapter-mavlink test` — 40 tests across 5 classes: `MavlinkTelemetryDecoderTest` 8 (golden-bytes unit-conversion mapping), `MavlinkRouteTest` 10 (pure interpolation math), `MavlinkTelemetrySourceTest` 9 (`supports()` matrix + open/close), `MavlinkFeedTransmitterTest` 11 (`supports()` matrix + `start()` validation), `MavlinkRoundTripIntegrationTest` 2 (loopback TX→UDP→RX moving/battery-reporting drone; malformed-datagram resilience) — no hardware, no docker. Verified stable across repeated runs.

## Library choice

**`io.dronefleet.mavlink:mavlink` 1.1.11** (Maven Central, latest as of this task — checked 2026-07-23; last released 2023-02-27, dependency-free at runtime: its own `mavlink-protocol` sub-artifact declares zero non-test dependencies, and the combined jars are ~1.8 MB, mostly generated per-dialect message classes). Chosen over hand-rolling frame decode because:
- It already CRC-validates and auto-resyncs past malformed/unparseable frames inside `MavlinkConnection#next()` (skips bad frames and keeps scanning for the next valid one, never throws for that case) — exactly the "unparseable datagrams skipped, never fatal" requirement, for free.
- Typed, code-generated message classes (`GlobalPositionInt`, `Heartbeat`, `SysStatus`, ...) with builders — no manual bit-twiddling for the messages this task needs, and headroom for more (`BATTERY_STATUS`, attitude, etc.) without touching the wire-format layer later.
- No transitive baggage to justify avoiding it (see size/deps above) — the "hand-roll if it drags baggage" escape hatch in this task's brief doesn't apply.

Version pinned via root `pom.xml`'s `<dronefleet-mavlink.version>` property, same pattern as `javacv.version`/`jmdns.version`.

## API surface

### `com.drones.vision.adapter.mavlink`
- `final class MavlinkTelemetrySource implements TelemetrySourcePort` — RX. Supports protocol `"mavlink"` with a `udp://host:port` `StreamDescriptor.uri()` and `Capability.TELEMETRY`. `supports(Device)`, `open(Device): Flow.Publisher<Telemetry>`, `close(DeviceId)`.
  - **`udp://host:port` means listen, not connect** — see Gotchas. `host` blank/absent → binds the wildcard `0.0.0.0` (all interfaces).
  - nested `private static final class DeviceRuntime` — one dedicated platform thread (`mavlink-telemetry-<id>`) per `open()`: binds a `DatagramSocket`, builds a `MavlinkConnection` over `MavlinkUdpInputStream`, and loops `connection.next()` → `MavlinkTelemetryDecoder.accept(message)` → `SubmissionPublisher.submit(sample)`. `close()`: CAS-guarded idempotent, closes the socket (unblocks a pending `receive()`) before interrupting + bounded-joining (5s) the read thread.
- `final class MavlinkFeedTransmitter implements FeedTransmitterPort` — TX. Supports `FeedSpec.protocol()` `"mavlink"` with a `source()` whose scheme is `udp` and which carries a non-blank host **and** a positive port. `supports(FeedSpec)`, `start(FeedId, FeedSpec): StreamDescriptor`, `stop(FeedId)`.
  - **`FeedSpec.source()` is repurposed as a destination**, not a file — see Gotchas.
  - Options: `route` (**required**, `lat,lon[,altM];lat,lon[,altM];...`, ≥2 points — missing/malformed → `IllegalArgumentException` at `start()`, no lenient fallback); `speedMps` (default 12.0); `batteryDrainPerSecond` (default 0.05 %/s); `positionRateHz` (default 5.0). All numeric options: non-positive/unparseable → default (same lenient idiom as every other adapter, except `route` itself).
  - nested `private static final class FeedRuntime` — one dedicated platform thread (`mavlink-feed-<id>`) per `start()`: owns an ephemeral `DatagramSocket`, sends `HEARTBEAT`+`SYS_STATUS` once a second and `GLOBAL_POSITION_INT` at `positionRateHz`, always as system id 1 / component id 1, paced by a 50ms tick loop comparing wall-clock deadlines (not a `ScheduledExecutorService` — one thread, two cadences, simplest thing that works). `close()`: same CAS/interrupt/bounded-join (5s) shape as RX.
- `final class MavlinkTelemetryDecoder` (package-private) — stateful message→`Telemetry` merger; one instance per RX `open()`. See "Message → field mapping" below and its own javadoc for the full unit-conversion table.
- `final class MavlinkRoute` (package-private) — minimal `LOOP`-only flight-route engine for TX (parse + `positionAt(metersAlongRoute)`); deliberately duplicates `adapter-simulation`'s `RoutePlan` *technique* (equirectangular approximation), not its code — see Gotchas.
- `final class MavlinkUdpInputStream` / `final class MavlinkUdpOutputStream` (package-private) — bridge a `DatagramSocket` to the `InputStream`/`OutputStream` `MavlinkConnection` expects; one datagram in, one datagram out per MAVLink message.

## Message → field mapping

| MAVLink message | Field(s) | Domain field | Conversion |
|---|---|---|---|
| `GLOBAL_POSITION_INT` | `lat`/`lon` | `Telemetry.latitude`/`longitude` | ÷ 1e7 |
| | `alt` (mm, AMSL) | `Telemetry.altitudeMeters` | ÷ 1000 (AMSL, not `relativeAlt`) |
| | `hdg` (cdeg, `65535`=unknown) | `Telemetry.headingDegrees` | ÷ 100, or `null` |
| | `vx`/`vy`/`vz` (cm/s, NED) | `extra["vxMps"/"vyMps"/"vzMps"]` | ÷ 100 |
| `SYS_STATUS` / `BATTERY_STATUS` | `batteryRemaining` (%, `-1`=unknown) | `Telemetry.batteryPercent` | none; last-message-wins |
| `VFR_HUD` | `groundspeed` (m/s) | `extra["groundspeedMps"]` | none |
| `HEARTBEAT` | — | — | liveness only; still emits a sample |

Every other message type (hundreds in the common dialect) is ignored.

## Conventions
- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- Lenient numeric option parsing (RX has none; TX's `speedMps`/`batteryDrainPerSecond`/`positionRateHz`) matches every other adapter's idiom: missing/blank/malformed → default. `route` is the one deliberate exception (fails fast) — see Gotchas.
- Idempotent close/stop via `AtomicBoolean` CAS, dedicated named threads (`mavlink-telemetry-<id>`, `mavlink-feed-<id>`), close-the-socket-to-unblock-the-read-thread — all identical in shape to `adapter-mjpeg`'s RX/TX runtimes, this module's closest precedent.

## Gotchas
- **`udp://host:port` means listen, not connect (RX).** A telemetry radio or SITL instance *pushes* datagrams to this app; `MavlinkTelemetrySource` never dials out. `host` is the local bind address (wildcard when blank), `port` the local bind port. The sender's own address is never checked — any datagram arriving on the bound port is read, which is also how multiple systems can end up sharing one port (see next point).
- **Multiple systems on one port: first system id seen wins, no re-election.** `MavlinkTelemetryDecoder` locks onto `MavlinkMessage#getOriginSystemId()` from the very first message it decodes (any type, including `HEARTBEAT`) and silently drops every later message from a different system for that decoder's lifetime. If the first system goes silent, a second one starting up is *not* adopted.
- **Malformed/garbage datagrams need no special handling here.** `io.dronefleet.mavlink.MavlinkConnection#next()` already scans for the next valid frame-start marker and silently drops anything that fails to parse or fails CRC, internally, before ever returning — confirmed by reading `MavlinkPacketReader`/`MavlinkFrameReader`'s own source and proven by `MavlinkRoundTripIntegrationTest`'s noise-interleaved test. `MavlinkUdpInputStream#read()` never returns `-1` (a live listening socket has no natural EOF), which is what lets the library's internal resync loop keep working across datagram boundaries exactly like it would over a continuous serial/TCP stream. The only exception that ever reaches `MavlinkTelemetrySource`'s own `catch` is a genuine `IOException` from the socket itself (almost always `close()` unblocking the read thread).
- **`FeedSpec.source()` is repurposed as a destination for TX, not a file.** Every other `FeedTransmitterPort` in this codebase (`RtspFeedTransmitter`, `MjpegFeedTransmitter`) reads a local video *file* named by `source()` and either pushes to its own constructor-injected base or serves its own ephemeral port. MAVLink has no such thing to read — the transmitted content is a synthesized flight plan — and no path-based multiplexing the way RTSP/mediamtx has, so what actually varies per feed is *where to send it*. `MavlinkFeedTransmitter.start()` therefore treats `spec.source()` as the `udp://host:port` **destination** to push datagrams to, and returns it back verbatim as the `StreamDescriptor` — which is also exactly the address a `MavlinkTelemetrySource` should bind/listen on to receive it, since MAVLink UDP push has one shared address for both ends. Documented here because it's the one place this adapter's use of `FeedSpec`/`FeedTransmitterPort` deviates from every other implementation's convention.
- **TX's `route` option has no lenient fallback**, unlike everything else in this codebase (`adapter-simulation`'s `lat`/`lon` circular-track default, this module's own `speedMps`/etc.). A missing/malformed route throws `IllegalArgumentException` at `start()` instead of falling back to a circular track, because reimplementing that fallback would duplicate more of `adapter-simulation`'s `RoutePlan` than this task's "minimal reimplementation" brief called for — see `MavlinkRoute`'s own javadoc.
- **`MavlinkRoute` only implements `LOOP`** (retrace via a closing end→start leg, repeat indefinitely) — no `BOUNCE`/`ONCE` — because a transmitted simulation feed exists to be watched/rehearsed indefinitely, the same reasoning behind `RtspFeedTransmitter`/`MjpegFeedTransmitter`'s own `loop=true` TX default. `adapter-simulation`'s `RoutePlan` (three modes, altitude/bounce folding) is the fuller reference if a future task needs more.
- **`MavlinkFeedTransmitter` and `MavlinkTelemetrySource` are not yet wired into `SimulationService`/`SimulationTransport`** (vision-application) — `SimulationTransport` has no `MAVLINK` variant, and adding one is out of X-a's scope (vision-application wasn't in this task's file scope). Both beans are still fully usable today via the plain device/asset APIs (`POST /api/devices` + `POST /api/assets`, or a future task's `SimulationTransport.MAVLINK`), exactly like `adapter-mjpeg`'s pair was before its own follow-up wiring task landed.

## Try it with a real SITL instance

ArduPilot's SITL can push straight at this adapter without needing `MavlinkFeedTransmitter` at all — register a `mavlink` telemetry device with `uri = udp://0.0.0.0:14550` (or any free port) and point SITL at it:

```
sim_vehicle.py -v ArduCopter --out=udp:127.0.0.1:14550
```

(`--out` tells SITL to *push* MAVLink to that address — matches this adapter's listen-not-connect model exactly.)

## Status
Fully implemented per docs/MVP2-PLAN.md X-a: RX (`MavlinkTelemetrySource`) and TX (`MavlinkFeedTransmitter`) both land in this one task, module-skeleton-to-tests-green, alongside `vision-app` wiring (`mavlinkTelemetrySource`/`mavlinkFeedTransmitter` beans — see vision-app/MODULE.md). Not yet wired into `SimulationService`'s `transport` dispatch (see Gotchas) — that's the natural follow-up task, in the same shape as `adapter-mjpeg`'s own two-task split (docs/CYCLES-PLAN.md §5).
