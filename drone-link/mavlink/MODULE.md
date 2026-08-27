# adapter-mavlink

MAVLink 2 UDP telemetry ingest (RX), guarded command TX, a persistent RC-override relay, heartbeat
discovery, vehicle onboarding (probe/remediate/configure), and a flight-plan TX simulator — the
driven adapter for `TelemetrySourcePort` / `FlightCommandPort` / `ManualControlPort` /
`DeviceDiscoveryPort` / `FeedTransmitterPort` / `VehicleConfigPort` over MAVLink 2.

**Depends on:** vision-kernel, vision-warehouse, vision-flight, vision-perception, vision-platform
(for `SubsystemStatusPort`/`SubsystemStatus`/`Health`), `drone-link/mavlink-core`,
`io.dronefleet.mavlink:mavlink` (used only where mavlink-core's contract requires a raw library
type: message-type dispatch, `MavCmd`/`MavResult`, dialect field annotations, `Heartbeat`) ·
**Used by:** vision-app

**Build/test:** `./mvnw -B -pl drone-link/mavlink -am test`. SITL-gated integration tests skip
cleanly without a `vision-sitl` docker image; timing-sensitive real-loopback tests poll to their
own deadline and can flake under a `-Dtest=`-filtered run in a loaded sandbox — verify via the full
module build.

## Levels

This module is the **L5 translation layer** (vision-domain types ⇄ `drone-link/mavlink-core`'s
L3/L4 services) plus project policy a reusable library must not know: vehicle claim/re-election and
firmware mode-name tables. Sockets, framing, resync, peer tracking, correlation, dispatch, and the
send/await machinery (`CommandService`, `ManualControlService`, `ParameterService`,
`CapabilityService`, `MessageIntervalService`) all live in `drone-link/mavlink-core` — see that
module's own MODULE.md for their surface. No class here constructs an
`io.dronefleet.mavlink.MavlinkConnection` directly.

## Command TX is gated

`MavlinkFlightCommander`/`MavlinkManualControlSender` send whenever their port method is called —
this adapter enforces reachability only ("you cannot command what you cannot hear"), not
authorization; who is allowed to call them is enforced above this module. The one send path that
fires with no explicit per-call request is `MavlinkConnectRemediator`'s on-connect Mechanism A, and
it is **default-off**: `MavlinkSettings.Onboarding.requestMessagesOnConnect()` defaults `false`, and
`MavlinkGateway` constructs no remediator at all when it's false — structurally "cannot send", not
merely "chose not to".

## API surface

### `com.drones.vision.adapter.mavlink`

- `public final class MavlinkTelemetrySource implements TelemetrySourcePort` — RX. Protocol
  `"mavlink"`, `udp://host:port` `StreamDescriptor.uri()` (**listens**, never connects). `Capability.TELEMETRY`.
  `supports(Device)`, `open(Device): Flow.Publisher<Telemetry>`, `close(DeviceId)`,
  `public List<LinkHealth.Health> claimedVehicleHealth()` (the one public accessor besides the port
  methods — `vision-app`'s `SystemStatusWiring` takes it as a method reference for
  `MavlinkLinkStatusProvider`). Package-private: `static bindKey(host, port)`, `bindKeyFor(Device)`,
  `hasActiveHub(bindKey)`, `unclaimedVehicles(bindKey)`, `claimedVehicles(bindKey)`,
  `commandTarget(bindKey, DeviceId)`, `gateway(bindKey)`. `StreamDescriptor.options["sysid"]`
  (lenient int 1–255) pins a device to one sysid; missing/invalid → unpinned. Constructors: `()`,
  `(MavlinkSettings)`; package-private `(long silenceWindowMillis)` test seam. One `MavlinkGateway`
  per distinct bind address, reference-counted across every device sharing it.
- `final class MavlinkGateway` (package-private) — one per bind address (`host:port`). Owns a
  `UdpListenLink` (binds in its own constructor, throws `IOException` on conflict), a
  `MavlinkSession` built with `MavlinkNode.groundStation()` (sysid 255/compid 190), a
  `VehicleClaimPolicy`, a `MavlinkMessageInventory`, and optionally a `MavlinkConnectRemediator`
  (only when `settings.onboarding().requestMessagesOnConnect()` is `true`). Demultiplexes every
  dispatched frame by sysid only (never source address — a companion computer relaying several
  vehicles is one physical source for all of them). `register(DeviceId, Integer pinnedSysid,
  SubmissionPublisher<Telemetry>): VehicleRegistration`, `unregister(...): boolean` (true once
  empty — caller evicts it), `isClosed()`, `unclaimedVehicles()`, `claimedVehicles()`,
  `commandTarget(DeviceId)`, `sink()`/`correlator()`/`peers()` (session collaborators for a TX class
  to build a mavlink-core service on), `messageInventory()`, `claimedVehicleHealth()`, `close()`
  (package-private — besides `unregister`, only `MavlinkVehicleConfigurator` calls it, for a
  self-bound probe gateway it opened itself). Nested records `UnclaimedVehicle(int sysid, String
  firmware, Integer mavType, Instant lastHeard)`, `ClaimedVehicle(int sysid, DeviceId deviceId,
  String firmware, Integer mavType, Instant lastHeard)`, `CommandTarget(int sysid, String firmware,
  Integer mavType, InetSocketAddress sourceAddress)`.
- `final class VehicleClaimPolicy` (package-private) — project policy: which `Device` owns which
  sysid (pinned/unpinned claim + re-election — see Gotchas). `add`/`remove(VehicleRegistration):
  boolean`, `unclaimedVehicles()`, `claimedVehicles()`, `commandTarget(DeviceId)`,
  `resolve(int sysid): VehicleRegistration` (called once per dispatched frame). One private monitor.
- `final class VehicleRegistration` (package-private) — mutable struct: `deviceId`, `pinnedSysid`,
  `publisher` (final), mutable `claimedSysid`/`decoder`. No accessors — two collaborators only,
  both in-package.
- `final class MavlinkMessageInventory` (package-private) — passive per-peer message inventory via
  its own `Dispatcher.subscribe(MessageFilter.any(), ...)`; independent of `VehicleClaimPolicy` —
  observes every sysid, claimed or not. `observedPeers(): List<Integer>`, `snapshot(int sysid):
  PeerSnapshot` (`null` if never observed or evicted), `close()`. Records `MessageRate(int messageId,
  long count, double hz)`, `PeerSnapshot(int sysid, List<MessageRate> messages, long
  bytesPerSecond)`. Rolling, not cumulative (query-time decay off a fixed ring of per-`bucketWidth`
  sums); LRU-bounded on tracked peers and message-types-per-peer. Configured by
  `MavlinkSettings.Inventory`.
- `final class MavlinkConnectRemediator` (package-private) — Mechanism A on connect: fires
  `MAV_CMD_SET_MESSAGE_INTERVAL` for every configured message the instant a system id is newly
  learned. `(Dispatcher, FrameSink, Correlator, MavlinkSettings)`, `close()`. No port, no dependency
  on `vision-flight`'s requirement table — the message set is pure configuration.
- `public final class MavlinkFlightCommander implements FlightCommandPort` — `setMode`/
  `returnToHome`/`arm`/`disarm`/`emergencyStop`/`auxFunction`/`capabilities`. Every command is one
  `COMMAND_LONG` from a fresh, per-call `CommandService` built on the resolved device's gateway,
  **zero retries** (single-shot: `NO_ACK` on silence, never a resend). `static final int
  TARGET_COMPONENT_AUTOPILOT = 1`. Only ArduPilot/INAV (`autopilot` ARDUPILOTMEGA) is commandable;
  Betaflight (`autopilot` GENERIC) is rejected before `FlightModes` is even consulted, even though
  its own table has an RTL-named mode. Constructors `(MavlinkTelemetrySource)`,
  `(MavlinkTelemetrySource, Duration ackTimeout)`.
- `public final class MavlinkManualControlSender implements ManualControlPort` — `RC_CHANNELS_OVERRIDE`
  (#70) relay. `engage(Device): ManualControlLink` / `send(link, RcChannels)` / `release(link)`;
  its `AdapterLink` carries `vehicleKind()` (resolved from the heartbeat heard at `engage` time,
  fixed for the link's life) and `rateHz()` (the real clamped keepalive rate). Translates
  `vision-flight`'s `RcChannels` into mavlink-core's structurally-identical one at `send`. All
  engaged links from one instance share a single `DefaultTxScheduler` (two daemon threads total).
  v1 scope: channels 1–8 only. Constructors `(MavlinkTelemetrySource, MavlinkSettings.Rc)`;
  package-private `(MavlinkTelemetrySource, long tickPeriodMillis, int releaseFrameCount)` test seam.
- `public final class MavlinkHeartbeatScanner implements DeviceDiscoveryPort` — `method()` =
  `"mavlink"`, `scan(Duration): List<DiscoveredDevice>`. Two paths: **hub-borrow** (a gateway is
  already open — polls its claimed/unclaimed registries, never binds) or **self-bind** (nothing has
  the port open — binds a plain `UdpListenLink`+`FrameReader` for the scan's duration; a bind
  conflict is one WARN log and an empty result, never thrown). Names a vehicle `"<Firmware>
  <kind> (sysid n)"`; `suggestedCategory()` is `"drone"` only for an airborne `mavType`.
  Constructors `(MavlinkTelemetrySource, int port)`, `(..., MavlinkSettings.Scan)`.
- `public final class MavlinkFeedTransmitter implements FeedTransmitterPort` — synthetic MAVLink TX:
  `HEARTBEAT`+`SYS_STATUS`+`GPS_RAW_INT` at 1 Hz, `GLOBAL_POSITION_INT` at `positionRateHz`, driven
  by a looping `MavlinkRoute`. `FeedSpec.source()` is repurposed as the `udp://host:port`
  **destination** this transmitter pushes to (the one deliberate semantic deviation from this port's
  other implementations). Options: `route` (**required**, fails fast if missing/malformed — no
  fallback track), `speedMps`, `batteryDrainPerSecond`, `positionRateHz`, `failsafeBatteryPercent`,
  `sysid` (all lenient: missing/malformed → default). Below `failsafeBatteryPercent`, `custom_mode`
  switches to RTL and `system_status` to CRITICAL — a scripted failsafe trigger. One dedicated
  thread per `start()`. Constructors `()`, `(MavlinkSettings)`.
- `public final class MavlinkVehicleConfigurator implements VehicleConfigPort` — the MAVLink half of
  vehicle onboarding. `supports(Device)`, `probe(String linkKey, Duration window): VehicleProfile`
  (passive inventory + one `AUTOPILOT_VERSION` + one batch of named parameter reads — never throws
  for an incomplete answer), `requestMessageInterval(linkKey, messageId, interval):
  MessageIntervalOutcome` (**Mechanism A**, session-scoped, nothing persisted),
  `readParams(linkKey, names): List<ParameterReading>` (named reads only — an unanswered name has no
  entry, never a fabricated zero), `writeParam(linkKey, name, value): ParameterWriteOutcome`
  (**Mechanism B** — the only method here that changes persistent state: snapshots first, writes,
  reports the read-back; `DENIED` whenever the read-back doesn't match what was asked for).
  Addressed by `"udp://host:port#sysid"` (probe runs *before* device registration, so no `DeviceId`
  exists yet); the `#sysid` suffix is optional for `probe` alone. Rides the `MavlinkGateway` already
  bound to the address when there is one, opens a temporary one otherwise. Constructors
  `(MavlinkTelemetrySource)`, `(MavlinkTelemetrySource, MavlinkSettings)`.
- `public final class MavlinkLinkStatusProvider implements SubsystemStatusPort` — `mavlink-link`'s
  health self-report for `GET /api/system/status`. Constructor takes `Supplier<List<LinkHealth.Health>>`
  (`vision-app` passes `mavlinkTelemetrySource::claimedVehicleHealth`). No vehicle claimed →
  `Health.UNKNOWN`; all connected → `OK`; any vehicle never heard from → `DOWN` (worst case); every
  vehicle heard at least once but some stale → `DEGRADED`. `LinkHealth.Health` carries no per-vehicle
  identity, so this is a rollup across every claimed vehicle, not a per-vehicle report.
- `final class MavlinkTelemetryDecoder` (package-private) — stateful, one instance per claim/
  re-election (never shared); merges a stream of MAVLink messages from one system into `Telemetry`
  samples. `Telemetry accept(MavlinkMessage<?>)` (adapts onto the overload below) / `Telemetry
  accept(int originSystemId, Object payload)` — returns `null` for a second system sharing the
  socket or an unmapped message type. `static String firmwareLabel(int autopilot)`. Backed by four
  package-private mutable state holders (no locking — single-threaded for a decoder's whole life):
  `PositionAndPowerState` (position/velocity/battery + AGL/boot-millis), `FlightStatusState`
  (materializes `FlightState`), `ArdupilotExtras` (every other `extra`-only key), `AttitudeState`
  (aircraft attitude + gimbal orientation, materializes `Attitude`). See the field-mapping table
  below for the full per-message conversion.
- `final class AttitudeState` (package-private) — `ATTITUDE` (#30), `GIMBAL_DEVICE_ATTITUDE_STATUS`
  (#285, preferred), `MOUNT_ORIENTATION` (#265, deprecated fallback). Latches "#285 seen"
  permanently — see Gotchas for the yaw-frame resolution rules.
- `final class QuaternionEuler` (package-private) — `static Euler fromQuaternion(double w, double x,
  double y, double z)`; nested `record Euler(double rollDegrees, double pitchDegrees, double
  yawDegrees)`. Standard ZYX (aerospace Tait-Bryan) decomposition. No MAVLink types in its
  signature — pure math.
- `final class FlightModes` (package-private) — firmware/vehicle-aware mode-name tables.
  `static String name(int autopilot, int mavType, long customMode)` (`"Mode <n>"` fallback for an
  unknown table/entry), `static Integer customModeFor(int autopilot, int mavType, String modeName)`,
  `static List<String> selectableModes(int autopilot, int mavType)` (**ArduPilot-only** — Betaflight
  returns empty even though it has a table, since its RC link never processes `DO_SET_MODE`),
  `static VehicleKind vehicleKind(int mavType)` (**autopilot-independent** — a quadrotor is a
  quadrotor regardless of firmware; `UNKNOWN` for anything unrecognized).
- `final class MavlinkRoute` (package-private) — closed-loop route interpolation for
  `MavlinkFeedTransmitter`. Deliberate duplication of `adapter-simulation`'s `RoutePlan` (adapters
  must never depend on each other). `static MavlinkRoute parse(String routeOption)` (`null` if
  absent/malformed — **no fallback track**, unlike this module's other lenient options),
  `Position positionAt(double metersAlongRoute)`.
- `public record MavlinkSettings(String bindHost, Duration silenceWindow, int maxUnclaimedVehicles,
  Duration closeJoinTimeout, Duration ackTimeout, Scan scan, Transmit transmit, Rc rc, Inventory
  inventory, Onboarding onboarding)` — this module's tunables, `vision-app` maps `vision.mavlink.*`/
  `vision.rc.*` onto one. `static defaults()`, `withSilenceWindow`/`withInventory`/`withOnboarding`.
  Back-compat 8-arg and 9-arg constructors default the fields added after them. Nested:
  - `record Scan(int activeHubPollCount, Duration activeHubMinPollInterval, Duration
    selfBindMinReadTimeout, Duration selfBindMaxReadTimeout)` — `MavlinkHeartbeatScanner` budgets.
  - `record Transmit(Duration tick, Duration heartbeatPeriod, double defaultSpeedMps, double
    defaultPositionRateHz, double defaultFailsafeBatteryPercent, int defaultSysid)`.
  - `record Rc(int overrideHz, int minOverrideHz, int maxOverrideHz, int releaseFrames)` +
    `int clampedOverrideHz()`.
  - `record Inventory(Duration window, Duration bucketWidth, int maxTrackedPeers, int
    maxTrackedMessageTypesPerPeer)` — defaults 10s/1s/64/128.
  - `record Onboarding(List<String> probeParameters, Duration capabilityTimeout, int
    capabilityRetries, Duration parameterTimeout, int parameterRetries, boolean
    requestMessagesOnConnect, List<MessageRequest> onConnectMessageRequests)` — rejects a
    probe-parameter name over MAVLink's 16-char `param_id` at construction. Nested `record
    MessageRequest(int messageId, Duration interval)` (wire id, not a name; negative interval
    rejected, `Duration.ZERO` legal = "resume default rate"). `defaults()`'s 19 probe parameters and
    9 on-connect message requests are firmware-verified against ArduPilot Copter 4.7 — see Gotchas.
    `probeParameters` is overridable through `vision.onboarding.probe.parameters`; empty (the
    default) keeps `defaults()`'s list rather than probing nothing.
- `final class SimulatedVehicleMessages` (package-private) — the MAVLink message builders
  `MavlinkFeedTransmitter` calls (`heartbeat`, `sysStatus`, `gpsRawInt`, `globalPositionInt`).

## Message → field mapping (`MavlinkTelemetryDecoder`)

| MAVLink message | Field(s) | Domain field | Conversion |
|---|---|---|---|
| `GLOBAL_POSITION_INT` | `lat`/`lon` | `Telemetry.latitude`/`longitude` | ÷ 1e7 |
| | `alt` (mm, AMSL) | `Telemetry.altitudeMeters` | ÷ 1000 (AMSL, not `relativeAlt`) |
| | `relative_alt` (mm, height above home) | `Telemetry.aglMeters` | ÷ 1000; always sent, no sentinel |
| | `time_boot_ms` | `Telemetry.deviceBootMillis` | none |
| | `hdg` (cdeg, `65535`=unknown) | `Telemetry.headingDegrees` | ÷ 100, or `null` |
| | `vx`/`vy`/`vz` (cm/s, NED) | `extra["vxMps"/"vyMps"/"vzMps"]` | ÷ 100 |
| `ATTITUDE` (#30) | `roll`/`pitch`/`yaw` (rad) | `Attitude.rollDegrees`/`pitchDegrees`/`yawDegrees` | `Math.toDegrees` |
| `GIMBAL_DEVICE_ATTITUDE_STATUS` (#285, **preferred**) | `q` (quaternion w x y z) | `Attitude.gimbalRollDegrees`/`gimbalPitchDegrees`/`gimbalYawDegrees` | `QuaternionEuler.fromQuaternion`; yaw resolved earth-frame via `flags`/`delta_yaw`, or `null` — see Gotchas |
| `MOUNT_ORIENTATION` (#265, deprecated **fallback**, only until #285 has arrived once) | `roll`/`pitch` (deg, global frame) | `Attitude.gimbalRollDegrees`/`gimbalPitchDegrees` | none |
| | `yaw_absolute` (deg, earth-frame extension field) | `Attitude.gimbalYawDegrees` | none, or `null` if `NaN`; plain `yaw` (vehicle-relative) is never used |
| `SYS_STATUS` / `BATTERY_STATUS` | `batteryRemaining` (%, `-1`=unknown) | `Telemetry.batteryPercent` | none; last-message-wins |
| `SYS_STATUS` | `voltage_battery` (mV, `65535`=unknown) | `extra["batteryVoltage"]` | ÷ 1000, key omitted when unknown |
| `VFR_HUD` | `groundspeed` (m/s) | `extra["groundspeedMps"]` | none |
| `HEARTBEAT` | `autopilot` | `FlightState.firmware` | `3`→`"ardupilot"`, `0`→`"generic"`, `12`→`"px4"`, else `null` |
| | `base_mode` bit `128` | `FlightState.armed` | boolean, always set |
| | `base_mode` bit `1` gates `custom_mode` | `FlightState.mode` | resolved via `FlightModes.name`; unchanged when the bit is clear |
| | `system_status == MAV_STATE_CRITICAL` | `FlightState.failsafe` | boolean, always set |
| `GPS_RAW_INT` | `fix_type` | `FlightState.gpsFixType` | none (already the 0..8 ordinal) |
| | `satellites_visible` (`255`=unknown) | `FlightState.satellites` | none, or `null` |
| | `eph` (HDOP×100, `65535`=invalid) | `FlightState.hdop` | ÷ 100, or `null` |
| `RC_CHANNELS` / `RC_CHANNELS_RAW` | `rssi` (0–254, `255`=invalid) | `FlightState.rssiPercent` | `round(rssi/254×100)`, or `null` |
| `STATUSTEXT` | `text` matching `^(PreArm\|Arm): (.*)` | `FlightState.armingBlockers` | captured reason added to an insertion-ordered, cap-10 set; cleared entirely once `HEARTBEAT` reports armed |
| `WIND` (ardupilotmega) | `direction`/`speed` | `extra["windDirectionDegrees"/"windSpeedMps"]` | none |
| `VIBRATION` (common) | `vibrationX/Y/Z` | `extra["vibeXMs2"/"vibeYMs2"/"vibeZMs2"]` | none; `clipping0/1/2` dropped |
| `EKF_STATUS_REPORT` (ardupilotmega) | `velocityVariance`/`posHorizVariance`/`posVertVariance`/`compassVariance` | `extra["ekf*Variance"]` | none; `terrainAltVariance`/`airspeedVariance` dropped |
| `MISSION_CURRENT` (common) | `seq` | `extra["missionSeq"]` | none |
| `RANGEFINDER` (ardupilotmega) | `distance` (m) | `extra["rangefinderDistanceM"]` | none; `voltage` dropped |

Every other MAVLink message type is ignored. `Telemetry.flightState()` stays `null` until at least
one `FlightState`-contributing row above has fired at least once.

## Conventions

- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- Lenient numeric option parsing (RX's `sysid`; TX's `speedMps`/`batteryDrainPerSecond`/
  `positionRateHz`/`failsafeBatteryPercent`/`sysid`): missing/blank/malformed → default. `route`
  (`MavlinkFeedTransmitter`) is the one deliberate exception — fails fast.
- Idempotent `close()` via `AtomicBoolean` CAS; close-the-link-to-unblock-the-reader idiom.
- Mutable claim/session-adjacent state (`MavlinkGateway`, `VehicleClaimPolicy`,
  `MavlinkMessageInventory`, `MavlinkConnectRemediator`) is each guarded by exactly one private
  monitor; `PeerDirectory` itself needs no external lock (independently thread-safe).
- Decoder state holders (`PositionAndPowerState`, `FlightStatusState`, `ArdupilotExtras`,
  `AttitudeState`) are plain mutable structs with package-private fields and no accessors — each has
  exactly one collaborator (`MavlinkTelemetryDecoder`) and is touched from one thread for its whole
  life.

## Gotchas

- **`udp://host:port` means listen, not connect (RX).** A telemetry radio or SITL instance *pushes*
  datagrams to this app; `MavlinkTelemetrySource` never dials out. `host` is the local bind address
  (wildcard when blank), `port` the local bind port.
- **Claim/re-election is project policy, not a protocol fact — `VehicleClaimPolicy`, not
  `PeerDirectory`.** A **pinned** device claims exactly that sysid, permanently, the first time it's
  heard. An **unpinned** device claims the first sysid heard that nothing else claims; if its
  claimed vehicle then falls silent for the configured window (30s prod default), it may re-elect —
  checked lazily, only when a message from some other still-unclaimed sysid next arrives. A sysid
  nothing claims lands in a bounded (32) unclaimed registry, not silently dropped.
- **Vehicle identity for reachability is fixed at `(sysid, compid=1)`.** `VehicleClaimPolicy` queries
  `PeerDirectory` at the autopilot component id every producer this module talks to (real ArduPilot,
  `MavlinkFeedTransmitter`, every test double) already uses. A vehicle whose telemetry arrives from
  some other component id would still be sysid-claimed correctly but reports as unreachable/
  firmware-unknown until its autopilot component itself transmits.
- **A fresh `MavlinkTelemetryDecoder` on every claim/re-election is load-bearing.** A decoder's
  accumulated fields belong to one physical vehicle; reusing one across a claim change leaks stale
  values into the new vehicle's first samples. `VehicleClaimPolicy.assignClaim` enforces this.
- **Bind failures are synchronous.** `UdpListenLink` binds in its own constructor; a conflict throws
  `IOException`, wrapped as `UncheckedIOException` and propagated synchronously from `open(Device)`
  itself — a caller checking for a bind conflict must catch it around `open()`, not subscribe and
  wait for the publisher's `onError`.
- **A genuine mid-stream link failure raises no `onError`/`onComplete` on any registered
  publisher.** `MavlinkSession`'s reader thread catches the read failure internally, logs a WARNING,
  and just stops. A device whose gateway dies unexpectedly (not via `close()`) goes silent with no
  signal ever firing — a real production-robustness gap, not covered by any existing test, that
  would need a link-failure callback added to `mavlink-core`'s `MavlinkSession` to close.
- **`MavlinkMessageInventory.bytesPerSecond()` is an upper-bound estimate, not a wire
  measurement.** `MavFrame` carries no raw wire byte length (`mavlink-core`'s `FrameReader` reads it
  only transiently, then discards it). This class estimates each frame's size as fixed protocol
  overhead plus that message type's *maximum* payload length (computed once per payload class,
  cached). MAVLink 2 trims trailing all-zero bytes off the wire, so this over-estimates whenever a
  v2 message's trailing fields happen to be zero. Closing the gap needs a `MavFrame.wireLength()`
  field added to `mavlink-core`.
- **Ardupilotmega-dialect messages (`WIND`/`EKF_STATUS_REPORT`/`RANGEFINDER`) depend on
  `mavlink-core`'s own dialect-learning behavior, not this module's.** Dialect is resolved per
  system id across every resync buffer on a link — see `mavlink-core`'s own MODULE.md Gotchas for
  the mechanism; this decoder only ever sees whatever payload type it's handed and has no dialect
  logic of its own.
- **ArduPilot 4.7 has no `SRx_*` stream-rate parameters at all.** `SR0_*`/`SR1_*`/`SR2_*` were each
  read off a live Copter 4.7.0 instance and every one is absent. Consequence: `MAV_CMD_SET_MESSAGE_INTERVAL`
  (Mechanism A) is not one of two ways to fix a starved link on this firmware — it is the only way.
- **A vehicle pushing telemetry at a UDP `--out` channel streams almost nothing until asked.**
  Measured: four message types (`HEARTBEAT` at 1 Hz plus three event-driven ones at ~0.1 Hz), not
  the dozen+ a connected GCS sees. This is the gap `MavlinkVehicleConfigurator`/
  `MavlinkConnectRemediator` exist to close.
- **Parameter names are firmware-version state, not constants.** Copter 4.7 renamed
  `SYSID_THISMAV`→`MAV_SYSID`, `FS_BATT_ENABLE`→`BATT_FS_LOW_ACT`, `GPS_TYPE`→`GPS1_TYPE`; several
  `RTL_ALT`/`WPNAV_SPEED`/`ARMING_CHECK`-style names are absent entirely on that firmware.
  `Onboarding.defaults()`'s 19 names were verified against a live instance. MAVLink gives an
  autopilot no way to report an unknown parameter name — it just says nothing — so a stale list
  degrades into a slow probe that quietly reads less than it claims. Anyone changing the list must
  re-verify it against real firmware, not reason about it.
- **A renamed parameter is probed in two passes, and the second one is normally empty.**
  `MavlinkVehicleConfigurator.readInto` reads the configured list, then re-asks only the names that
  went unanswered, under their other `ParameterAliases` spellings. The probe list therefore carries
  the *current* spelling only. Listing both would look harmless and is not: `ParameterService.readAll`
  fans out concurrently, a firmware carries exactly one spelling of a renamed parameter, and one
  entry nobody can answer holds the whole batch open for the full `parameterTimeout × parameterRetries`
  budget — on every probe of every vehicle, once per rename. Measured: adding one such name cost
  ~8 s and pushed one-shot messages out of the inventory's rate window
  (docs/plans/active/FLEET-RADIO-PLAN.md F0).
- **`FENCE_ALT_MAX` is deliberately not in the probe list** — it does not exist on ArduRover, so on a
  rover it was a guaranteed timeout for nothing. The list is copter-verified but must not be
  copter-only; a fleet that wants it back adds it through the property.
- **Never close a borrowed `MavlinkGateway`.** `MavlinkVehicleConfigurator`'s probe path may either
  borrow the gateway a registered device is already streaming through, or open a temporary one of
  its own. The invariant that must hold absolutely: close only what you opened. A borrowed gateway
  backs a live device's telemetry; closing it tears that down. Enforced by the configurator's
  `LinkLease`, which records which case it is.
- **"Never heard from" is not "asked and got no answer".** `RoutingFrameSink` cannot address a peer
  it has no link for, so a request to an unheard aircraft is *never sent*. `MavlinkVehicleConfigurator`
  checks reachability before every send and reports which happened; both surface as `NO_ACK`, but
  only one is a fact about the aircraft. Reporting an unsent request as an unanswered one would read
  as "this firmware does not support it".
- **`readParams`/`readAll` fires every name concurrently, deliberately.** An absent name costs a
  full timeout, so batching would serialise those waits instead of overlapping them.
- **`MessageObservation.name` (in `MavlinkVehicleConfigurator.probe`'s output) is best-effort.**
  Resolved through the ardupilotmega dialect plus a CamelCase→SCREAMING_SNAKE transform
  (`VfrHud`→`VFR_HUD`), `null` for an id the dialect does not know. An unrecognised message still
  counts toward the inventory.
- **`COMMAND_ACK` correlates on `(origin sysid, command id)` only — never on which message id a
  `MAV_CMD_SET_MESSAGE_INTERVAL` asked for.** `MavlinkConnectRemediator` must send its configured
  message set to one peer strictly one at a time via `CompletableFuture.thenCompose`; a naive
  fire-all-at-once loop would register a second live `Correlator.await` for a key the first request
  already occupies, and `DefaultCorrelator` throws `IllegalStateException` rather than silently
  orphan the first waiter. Chaining serialises the sends without ever blocking the dispatcher's
  calling thread.
- **What "reconnected" means for `MavlinkConnectRemediator`'s idempotency is a judgement call, not
  a protocol fact.** MAVLink has no boot counter and no session identifier, so there is no wire-level
  way to distinguish "same aircraft, radio blipped" from "fresh boot, back to starved defaults". A
  system id is treated as newly learned again once it has gone unheard longer than
  `MavlinkSettings.silenceWindow()` — reusing `VehicleClaimPolicy`'s own threshold. A 31-second radio
  dropout gets re-remediated for free (cheap — ArduPilot just re-confirms a rate it already honours);
  the alternative risks silently leaving a rebooted aircraft in the starved state this mechanism
  exists to fix.
- **This adapter keeps no table of RC aux functions (`MavlinkFlightCommander.auxFunction`), on
  purpose.** A stale copy of the firmware's own `RCx_OPTION` list is worse than none — what a
  function number does is the vehicle's business, and whether it acted shows up as the ack.
- **`emergencyStop` is byte-identical to `disarm(device, true)` on the wire.** Kept as a separate
  method purely so the log line and audit trail record which of the two an operator actually meant.

## Status

Real and load-bearing: RX ingest + fleet-gateway claim/re-election, guarded command TX (mode/arm/
disarm/emergency-stop/aux-function), the persistent RC-override relay, heartbeat discovery, the
onboarding probe/remediate/configure trio (`MavlinkVehicleConfigurator` + `MavlinkConnectRemediator`),
system-status health reporting, and the TX flight-plan simulator. Out of scope, deliberately:
`RANGEFINDER`→`aglMeters` fusion, DEM/terrain intersection, camera intrinsics (consumed by
`contexts/vision-map`, not this module), and PX4 mode-name tables. Try it against real ArduPilot
SITL: register a `mavlink` telemetry device with `uri = udp://0.0.0.0:14550` and
`sim_vehicle.py -v ArduCopter --out=udp:127.0.0.1:14550`.
