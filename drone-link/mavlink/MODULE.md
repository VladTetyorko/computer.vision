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
  `public Map<DeviceId, LinkHealth.Health> claimedVehicleHealth()` (**FLEET-RADIO R4/D4** — was
  `List<LinkHealth.Health>`; the one public accessor besides the port methods — `vision-app`'s
  `SystemStatusWiring` takes it as a method reference for `MavlinkLinkStatusProvider`, which now
  aggregates per-vehicle instead of averaging a fleet-wide list). Package-private: `static bindKey(host, port)`, `bindKeyFor(Device)`,
  `hasActiveHub(bindKey)`, `unclaimedVehicles(bindKey)`, `claimedVehicles(bindKey)`,
  `commandTarget(bindKey, DeviceId)`, `gateway(bindKey)`. `StreamDescriptor.options["sysid"]`
  (lenient int 1–255) pins a device to one sysid; missing/invalid → unpinned. Constructors: `()`,
  `(MavlinkSettings)`; package-private `(long silenceWindowMillis)` test seam. One `MavlinkGateway`
  per distinct bind address, reference-counted across every device sharing it.
- `final class MavlinkGateway` (package-private) — one per bind address (`host:port`). Owns a
  `MavlinkLink` (production: a `UdpListenLink`, binds in its own constructor, throws `IOException`
  on conflict), a `MavlinkSession` built with `MavlinkNode.groundStation()` (sysid 255/compid 190), a
  `VehicleClaimPolicy`, a `MavlinkMessageInventory`, and optionally a `MavlinkConnectRemediator`
  (only when `settings.onboarding().requestMessagesOnConnect()` is `true`). Demultiplexes every
  dispatched frame by sysid only (never source address — a companion computer relaying several
  vehicles is one physical source for all of them). `register(DeviceId, Integer pinnedSysid,
  SubmissionPublisher<Telemetry>): VehicleRegistration`, `unregister(...): boolean` (true once
  empty — caller evicts it), `isClosed()`, `unclaimedVehicles()`, `claimedVehicles()`,
  `commandTarget(DeviceId)`, `sink()`/`correlator()`/`peers()` (session collaborators for a TX class
  to build a mavlink-core service on), `messageInventory()`, `Map<DeviceId, LinkHealth.Health>
  claimedVehicleHealth()` (**FLEET-RADIO R4/D4** — was `List<LinkHealth.Health>`; keyed by the
  claiming device, resolving each `ClaimedVehicle`'s `PeerId` and querying `session.health().of(...)`
  per vehicle rather than returning one undifferentiated list), `close()` (package-private —
  besides `unregister`, only `MavlinkVehicleConfigurator` calls it, for a self-bound probe gateway
  it opened itself). Constructors: `(String bindHost, int port, MavlinkSettings)` (production,
  delegates to the one below via `new UdpListenLink(bindHost, port)`) and package-private
  `(MavlinkLink, MavlinkSettings)` — a **FLEET-RADIO R4 test seam** (java-clean-code §3's sanctioned
  single-seam exception): widening the field type from `UdpListenLink` to `MavlinkLink` cost nothing
  (the field was already used only through methods `MavlinkLink` itself declares) and let
  `MavlinkGatewayLinkFailureTest` exercise the real `MavlinkGateway`→`VehicleClaimPolicy`→
  `SubmissionPublisher` chain end-to-end against a hand-built failing link, instead of sabotaging a
  real `DatagramSocket` via reflection. **(FLEET-RADIO R4/F7)** wires
  `session.onLinkFailure((linkId, cause) -> handleLinkFailure(cause))` in this constructor;
  `handleLinkFailure` logs a WARNING, calls `claimPolicy.closeAllPublishersExceptionally(cause)`
  (**D5**), then `close()`s the gateway itself. Nested records `UnclaimedVehicle(int sysid, String
  firmware, Integer mavType, Instant lastHeard)`, `ClaimedVehicle(int sysid, DeviceId deviceId,
  String firmware, Integer mavType, Instant lastHeard)`, `CommandTarget(int sysid, String firmware,
  Integer mavType, InetSocketAddress sourceAddress)`.
- `final class VehicleClaimPolicy` (package-private) — project policy: which `Device` owns which
  sysid (pinned/unpinned claim + re-election — see Gotchas). `add`/`remove(VehicleRegistration):
  boolean`, `unclaimedVehicles()`, `claimedVehicles()`, `commandTarget(DeviceId)`,
  `resolve(int sysid): VehicleRegistration` (called once per dispatched frame),
  `closeAllPublishersExceptionally(Throwable)` (**FLEET-RADIO R4/D5**, package-private — called only
  by `MavlinkGateway.handleLinkFailure`; closes every currently-registered device's
  `SubmissionPublisher` via `closeExceptionally`, under the same lock `add`/`remove` use). One
  private monitor.
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
  its own table has an RTL-named mode. **`emergencyStop` is vehicle-kind-gated (FLEET-RADIO R4b)** —
  see its own Gotchas section below; it is no longer a single, uniform command for every device.
  Constructors `(MavlinkTelemetrySource)`, `(MavlinkTelemetrySource, Duration ackTimeout)`.
- `public final class MavlinkManualControlSender implements ManualControlPort` — `RC_CHANNELS_OVERRIDE`
  (#70) relay. `engage(Device): ManualControlLink` / `send(link, RcChannels)` / `release(link)`;
  its `AdapterLink` carries `vehicleKind()` (resolved from the heartbeat heard at `engage` time,
  fixed for the link's life), `rateHz()` (the real clamped keepalive rate), and (**FLEET-RADIO R2**)
  `unidentifiedReason()` — overrides the port's default, populated at `engage` from
  `FlightModes.unidentifiedReason(target.mavType())` and fixed for the link's life exactly like
  `vehicleKind()` is. Translates `vision-flight`'s `RcChannels` into mavlink-core's
  structurally-identical one at `send`. All engaged links from one instance share a single
  `DefaultTxScheduler` (two daemon threads total). v1 scope: channels 1–8 only. Constructors
  `(MavlinkTelemetrySource, MavlinkSettings.Rc)`; package-private
  `(MavlinkTelemetrySource, long tickPeriodMillis, int releaseFrameCount)` test seam.
- `public final class MavlinkHeartbeatScanner implements DeviceDiscoveryPort` — `method()` =
  `"mavlink"`, `scan(Duration): List<DiscoveredDevice>`. Two paths: **hub-borrow** (a gateway is
  already open — polls its claimed/unclaimed registries, never binds) or **self-bind** (nothing has
  the port open — binds a plain `UdpListenLink`+`FrameReader` for the scan's duration; a bind
  conflict is one WARN log and an empty result, never thrown). Names a vehicle `"<Firmware>
  <kind> (sysid n)"`, `<kind>` sourced from `mavlink-core`'s `VehicleClass.label` (**FLEET-RADIO
  R1** — previously a local, hand-maintained string table that disagreed with
  `MavlinkVehicleConfigurator`'s own on the same vehicles; see FlightModes below); a vehicle whose
  `MAV_TYPE` the shared table cannot name at all falls back to `"vehicle"`. `suggestedCategory()`
  is `"drone"` only for an airborne `VehicleClass` (`COPTER`/`PLANE`), not merely a recognized one —
  a rover, a submarine, an unsupported airframe or a not-a-vehicle instrument all get no category.
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
  bound to the address when there is one, opens a temporary one otherwise. `VehicleProfile.vehicleKind`
  is sourced from `mavlink-core`'s `VehicleClass.label` (**FLEET-RADIO R1** — previously its own local
  table, which named a surface boat `"surface boat"` while `MavlinkHeartbeatScanner` said plain
  `"boat"` for the identical vehicle; both now agree, and this configurator's own finer spellings
  — `"surface boat"`, `"coaxial helicopter"` — are the ones the shared table kept). `null` only for a
  genuinely unrecognized `MAV_TYPE`, never for a recognized-but-unsupported or not-a-vehicle one (both
  get a real label). Constructors `(MavlinkTelemetrySource)`, `(MavlinkTelemetrySource, MavlinkSettings)`.
- `public final class MavlinkLinkStatusProvider implements SubsystemStatusPort` — `mavlink-link`'s
  health self-report for `GET /api/system/status`. **(FLEET-RADIO R4/D4, rewritten)** Constructor is
  `(Supplier<Map<DeviceId, LinkHealth.Health>> claimedVehicleHealth, MavlinkSettings.LinkStatus
  thresholds)` (`vision-app`'s `SystemStatusWiring` passes `mavlinkTelemetrySource::claimedVehicleHealth`
  and a `LinkStatus` built from `VisionMavlinkProperties`). No vehicle claimed → `Health.UNKNOWN`; a
  vehicle never heard from, or a connected vehicle whose drop rate is at/above
  `thresholds.dropRateAlarmPercent()` → `DOWN` (worst case, named); a connected-but-stale vehicle, or
  one at/above `thresholds.dropRateWarnPercent()`, → `DEGRADED`; otherwise `OK`. `detail` **now names
  the specific worst `DeviceId`** (ties broken deterministically by the device id's own `UUID`
  ordering) alongside the fleet-wide connected-count and average drop rate — before this wave,
  `LinkHealth.Health` carried no per-vehicle identity at all, so this class could only ever report a
  fleet average ("at least one vehicle is bad") and never say *which* one. Drop-rate severity is no
  longer hardcoded (**D7**) — both thresholds, plus the unrelated `linkFailureGrace` bound, come from
  the constructor's `MavlinkSettings.LinkStatus`.
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
  quadrotor regardless of firmware; `UNKNOWN` for anything unrecognized), `static
  Optional<UnidentifiedReason> unidentifiedReason(int mavType)` (**FLEET-RADIO R2**) — the *why*
  behind a `vehicleKind(mavType) == UNKNOWN` answer, switching on the same `VehicleClass.of(mavType)`:
  `COPTER`/`PLANE`/`ROVER` → empty; `SUBMARINE` and `UNSUPPORTED_VEHICLE` both → `UNSUPPORTED_VEHICLE`
  (a submarine is, from an operator's engage attempt, indistinguishable from an airship or a rocket —
  "recognized airframe, not flyable here" either way); `NOT_A_VEHICLE` → `NOT_A_VEHICLE`; `UNKNOWN` →
  `NEVER_IDENTIFIED`. Consumed by `MavlinkManualControlSender`'s `AdapterLink` below, never by
  `vehicleKind` itself — the two methods answer different questions from the same classification.
  **FLEET-RADIO R1:**
  the vehicle-family switch (which used to be this class's own `Set<Integer>`s per family) now
  delegates entirely to `mavlink-core`'s `VehicleClass` — `vehicleKind` and the ArduPilot-table
  selector (`tableFor`) both switch on `VehicleClass.of(mavType)`, so this class carries no
  `MAV_TYPE` literal any more. `ARDUPILOT_ROVER` is now complete: `8` Dock, `9` Circle and `16`
  Initialising were added (verified against ArduPilot's `Rover/mode.h` for the `stable-4.7.0`
  generation this project's own SITL image pins — see FLEET-RADIO R1 Gotchas below for why `Dock`
  stays in `customModeFor`'s reverse lookup despite being an optional compile-time mode on real
  firmware).
- `final class MavlinkRoute` (package-private) — closed-loop route interpolation for
  `MavlinkFeedTransmitter`. Deliberate duplication of `adapter-simulation`'s `RoutePlan` (adapters
  must never depend on each other). `static MavlinkRoute parse(String routeOption)` (`null` if
  absent/malformed — **no fallback track**, unlike this module's other lenient options),
  `Position positionAt(double metersAlongRoute)`.
- `public record MavlinkSettings(String bindHost, Duration silenceWindow, int maxUnclaimedVehicles,
  Duration closeJoinTimeout, Duration ackTimeout, Scan scan, Transmit transmit, Rc rc, Inventory
  inventory, Onboarding onboarding, LinkStatus linkStatus)` — this module's tunables, `vision-app` maps
  `vision.mavlink.*`/`vision.rc.*` onto one. `static defaults()`, `withSilenceWindow`/`withInventory`/
  `withOnboarding`/`withLinkStatus`. Back-compat 8-arg and 9-arg constructors default every field
  added after them, **including `linkStatus`** (`LinkStatus.defaults()` — FLEET-RADIO R4 kept both
  overloads' arity unchanged per CLAUDE.md rule 10 / java-clean-code §3: a new collaborator updates
  call sites and back-compat delegation targets, never a new overload). Nested:
  - `record LinkStatus(double dropRateWarnPercent, double dropRateAlarmPercent, Duration
    failureGrace)` (**FLEET-RADIO R4/D7**, new) — `MavlinkLinkStatusProvider`'s per-vehicle drop-rate
    severity thresholds (`dropRateAlarmPercent >= dropRateWarnPercent` enforced in the compact
    constructor, both range-checked 0..100) and the promptness bound this wave's own regression test
    (`MavlinkGatewayLinkFailureTest`) holds a link-failure notification to. `static defaults()` →
    5.0 / 20.0 / 2s, byte-identical to `VisionMavlinkProperties`'s own `DEFAULT_*` constants that map
    onto it. **`failureGrace` has no production runtime branch** — `MavlinkSession`'s link-failure
    listener is fully synchronous with no retry/backoff, so nothing in this module currently reads
    this field for a decision; it exists as a configured, documented value (CLAUDE.md rule 1: no
    magic numbers even in a test's own promptness assertion) rather than a bare literal in a test
    file, and is wired through `vision.mavlink.link-failure-grace` so it is visible and changeable in
    one place if a future wave gives it a real runtime meaning.
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
- **(FLEET-RADIO R4/F7/D5 — fixed) A genuine mid-stream link failure now raises `onError` on every
  registered publisher, promptly.** Before this wave, `MavlinkSession`'s reader thread caught the
  read failure internally, logged a WARNING, and just stopped — a device whose gateway died
  unexpectedly (not via `close()`) went silent with no signal ever firing, indistinguishable from a
  vehicle that had merely gone quiet. Fixed via `mavlink-core`'s new `MavlinkSession.onLinkFailure`
  listener (see that module's own MODULE.md/API.md): `MavlinkGateway`'s constructor wires it to
  `handleLinkFailure`, which calls `VehicleClaimPolicy.closeAllPublishersExceptionally(cause)` — every
  currently-registered device's `SubmissionPublisher` closes exceptionally with the real
  `IOException`, then the gateway itself closes. An ordinary, intentional `unregister()`/`close()`
  never triggers this path (the listener never fires for a shutdown racing with a poll failure — see
  `mavlink-core`'s own contract). Proven end-to-end by `MavlinkGatewayLinkFailureTest` (2, real
  `MavlinkGateway` + a hand-built failing `MavlinkLink`, injected via the new
  `MavlinkGateway(MavlinkLink, MavlinkSettings)` test seam).
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
- **`emergencyStop` is byte-identical to `disarm(device, true)` on the wire — but only for `COPTER`,
  `PLANE` and `UNKNOWN` (FLEET-RADIO R4b).** For `ROVER` it is a `MAV_CMD_DO_SET_MODE` into
  ArduRover's `Hold`, never a disarm; see FLEET-RADIO R4b Gotchas below for the full per-kind
  rationale. Kept as a separate method purely so the log line and audit trail record which of the
  (now two, per kind) underlying wire commands an operator actually triggered.

### FLEET-RADIO R1 Gotchas

- **This module's three `MAV_TYPE`→name/family tables (`FlightModes.vehicleKind`/`tableFor`,
  `MavlinkHeartbeatScanner.vehicleKind`, `MavlinkVehicleConfigurator.vehicleKind`) disagreed on the
  same vehicles before this wave** — a surface boat was `"boat"` in discovery and `"surface boat"`
  in the onboarding probe, `"fixed-wing"` vs `"fixed wing"`, and only the configurator knew a
  submarine existed at all. None of the three knew a dodecarotor (`MAV_TYPE` 29), a decarotor (35)
  or a generic multirotor (43) — all three fell through to a `"vehicle"`/`"Mode 6"`-style fallback
  as if unrecognized. All three now delegate to `mavlink-core`'s `VehicleClass`, the one project-wide
  table; a UI or log line that previously read `"boat"` now reads `"surface boat"` — the only
  user-visible spelling change. `VehicleTaxonomyAgreementTest` fails immediately if any of the three
  grows a local, hand-maintained copy again.
- **The kept vocabulary is `MavlinkVehicleConfigurator`'s where the two disagreed** (`"surface
  boat"` over `"boat"`, `"fixed-wing"` from the scanner over the configurator's `"fixed wing"` — the
  one exception, kept because it matches this module's own existing hyphenation convention
  elsewhere), plus new labels neither table had before (`"dodecacopter"`, `"decacopter"`,
  `"multirotor"`, a label per VTOL subtype, `"submarine"`, a label per recognized-but-unsupported
  airframe, a label per not-a-vehicle instrument). `station/vision-api`'s `OnboardingWireContractTest`
  asserts only that `VehicleProfile.vehicleKind` is present/absent, never an exact string, so this
  relabeling is not a frozen-wire break.
- **`VehicleClass` gives discovery and the onboarding probe a real, distinct label for a non-vehicle
  instrument heartbeating on the same link (a gimbal, a GCS, an ADS-B transponder, ...), rather than
  collapsing it into the same fallback a genuinely unidentified vehicle gets.** Before this wave both
  cases read identically (`"vehicle"` in discovery, `UNKNOWN` `VehicleKind` in `FlightModes`) — this
  matters directly for the next wave (R2), which makes an `UNKNOWN` vehicle refuse to engage manual
  control; without this distinction, a gimbal quietly sharing a vehicle's radio link would refuse to
  engage for the wrong reason (looking exactly like an unidentified aircraft) instead of the right one
  (it was never a vehicle). `FlightModes.vehicleKind` still folds both `NOT_A_VEHICLE` and `UNKNOWN`
  into `VehicleKind.UNKNOWN` today — that enum has no finer slot yet — but the underlying
  classification is no longer ambiguous, only `VehicleKind`'s own vocabulary is.
- **`FlightModes.ARDUPILOT_ROVER`'s three missing modes (`8` Dock, `9` Circle, `16` Initialising)
  were verified against ArduPilot's own `Rover/mode.h`** for the `stable-4.7.0` generation this
  project's own `infra/rover-sim` SITL image is pinned to (`docs/plans/active/FLEET-RADIO-PLAN.md`
  R7). `"Initialising"` (with an "s", ArduRover's own spelling) is kept exactly as upstream spells
  it, distinct from `ARDUPILOT_PLANE`'s `"Initializing"` (with a "z") two tables below — different
  firmware source trees, not normalized to agree with each other.
- **`Dock` (custom_mode 8) is compiled in behind `#if MODE_DOCK_ENABLED` on real ArduPilot, so it is
  absent from some builds — a nuance that only matters for `customModeFor`'s *reverse* lookup (name
  → number, used to command a mode), never for the forward lookup (number → name, used to display
  one).** A build without Dock compiled in simply never reports `custom_mode == 8`, so the forward
  lookup's table entry is inert on that build. `customModeFor` deliberately still resolves
  `"Dock"` → `8` on every build, including ones that lack it, because there is no live per-vehicle
  capability signal to gate an optional compiled-in mode on, and hiding the entry would make Dock
  permanently uncommandable even on builds that do have it. The actual safety net is one layer up:
  `MavlinkFlightCommander.send` already throws for an explicit non-`ACCEPTED` `COMMAND_ACK`, so a
  build that honestly rejects an unsupported mode change surfaces that rejection normally, the same
  as any other rejected command. The one gap this cannot close — a build that ACKs `ACCEPTED` for a
  mode change it does not actually honor — is a firmware-honesty problem, not something a
  client-side mode-name table can fix.

### FLEET-RADIO R4 Gotchas

- **`MavlinkGateway`'s field was widened from `UdpListenLink` to the `MavlinkLink` interface purely
  to get a clean test seam — this cost nothing in production.** Every use of the field
  (`close()`, `id()`, passing it to `session.addLink(link)`) was already declared on `MavlinkLink`
  itself; nothing about production behavior changed. The alternative considered and rejected was
  reflection-based sabotage of a real `DatagramSocket` (closing it out from under `UdpSocketIo`/
  `UdpListenLink` via two or more private-field hops) to force a genuine `IOException` for
  `MavlinkGatewayLinkFailureTest` — rejected as fragile and invasive compared to a one-parameter
  package-private constructor overload injecting a hand-built `MavlinkLink` double.
- **`VehicleClaimPolicy.closeAllPublishersExceptionally` closes *every* currently-registered
  publisher on the gateway, not just one device's.** This is correct for what F7 actually models: a
  `MavlinkSession`'s reader thread failing means the one shared UDP socket for that bind address is
  gone, which affects every vehicle claimed through it, not a single device. A multi-vehicle gateway
  (several sysids sharing one `bindHost:port`) therefore reports every one of its claimed devices as
  failed, together, on one socket death — this is accurate, not over-broad.
- **An ordinary `unregister()`/gateway `close()` still never calls `publisher.close()` (normal
  completion) on the departing device's `SubmissionPublisher` — this is a pre-existing fact, outside
  R4's scope, not something this wave fixed for symmetry.** `MavlinkGatewayLinkFailureTest`'s ordinary-
  teardown test therefore asserts the *absence* of `onError`, not the presence of `onComplete` — the
  correct, narrow claim for "shutdown must not masquerade as a link failure" (Expected Result #4),
  without depending on unrelated pre-existing behavior (publishers being simply abandoned on the
  normal path today) that this wave was not asked to change.
- **`SystemStatusWiring` now declares `@EnableConfigurationProperties(VisionMavlinkProperties.class)`
  alongside `TelemetryWiring`'s and `DiscoveryWiringConfiguration`'s own identical declarations of the
  same properties class — not a mistake, matching existing precedent.** Spring tolerates the same
  `@ConfigurationProperties` class being enabled from more than one `@Configuration` class; this wiring
  needed `VisionMavlinkProperties` for its three new D7 thresholds and pulling `TelemetryWiring`'s full
  `MavlinkSettings` object in just to read three scalars would have been a heavier, unnecessary coupling.
- **`failureGrace`/D7's threshold plumbing was placed in `SystemStatusWiring.mavlinkLinkStatus`, not
  threaded through `TelemetryWiring.toMavlinkSettings()`'s `MavlinkSettings` object, even though
  `MavlinkSettings.LinkStatus` is a field on that very record.** Both wiring classes now independently
  construct a `MavlinkSettings.LinkStatus` from the same `VisionMavlinkProperties` fields — `TelemetryWiring`
  because `MavlinkGateway`/`MavlinkTelemetrySource` never read `linkStatus` off `MavlinkSettings` today
  (nothing at that layer consumes drop-rate severity), and `SystemStatusWiring` because that is the one
  bean that actually needs the thresholds. `MavlinkSettings.linkStatus` exists so the field has a home
  on the settings record precedent (`Scan`/`Transmit`/`Rc`/`Inventory`/`Onboarding` are all consumer-
  grouped nested records) even though `SystemStatusWiring`'s own bean method takes `VisionMavlinkProperties`
  directly rather than reading it back off a `MavlinkSettings` instance.

### FLEET-RADIO R4b Gotchas

- **`emergencyStop` now branches on `FlightModes.vehicleKind(target.mavType())`, resolved from the
  same `ResolvedTarget` every command already resolves — no new lookup, no new state.** `COPTER`/
  `PLANE` are byte-identical to before this wave (`armOrDisarm(device, DISARM, true, "emergency
  stop")`, unchanged). `ROVER` sends `MAV_CMD_DO_SET_MODE` into ArduRover's `Hold` (custom_mode 4)
  instead — a ground rover or surface boat does not fall when disarmed, it *coasts* with its
  steering dead, so a forced disarm is actively wrong for it; ArduRover's `Hold` actively brakes and
  holds against a slope while keeping steering authority alive.
- **No disarm follows a successful rover `Hold`, by decision, not by omission.** A rover's active
  brake in `Hold` typically depends on the motor controller staying armed to apply reverse/holding
  torque; disarming immediately afterward would release the very brake the stop just applied, which
  on a slope is worse than never having stopped at all. An operator who wants the vehicle fully
  powered down once it is confirmed stationary issues a separate, deliberate `disarm` — never
  bundled into the panic-stop path.
- **`VehicleKind.UNKNOWN` stays on the forced-disarm path, deliberately, not as a leftover
  default.** `VehicleKind.UNKNOWN` is what `FlightModes.vehicleKind` returns for a genuinely
  unrecognized `MAV_TYPE` *and* for `VehicleClass.SUBMARINE`/`UNSUPPORTED_VEHICLE`/`NOT_A_VEHICLE` —
  none of those has a rover-shaped mode table this class could resolve `"Hold"` against, so
  attempting one would either throw before anything is sent or require inventing a new guess, which
  `VehicleKind.UNKNOWN`'s own contract (`contexts/vision-flight`'s own Gotchas: "no safe default")
  forbids. A forced disarm needs no vehicle-family mode table at all — `MAV_CMD_COMPONENT_ARM_DISARM`
  is universal across every ArduPilot vehicle kind — so it is the one stop command guaranteed to
  actually reach the aircraft and produce a real, reportable outcome for a machine that never said
  what it is, rather than an error in place of a stop attempt.
- **Never silently does nothing, on either path.** Both branches end in the same `send()` every
  other command already uses: `ACCEPTED`/`NO_ACK`, or a thrown `IllegalStateException` naming the
  vehicle's own refusal. A refused or unacknowledged rover `Hold` propagates exactly like a refused
  or unacknowledged forced disarm always has — nothing here catches and downgrades a failure into a
  false success. `contexts/vision-flight`'s `DefaultFlightCommandService.sendAndAudit` audits and
  rethrows either outcome unchanged, same as before this wave.
- **`FlightCommandPort.emergencyStop`'s own javadoc was rewritten** (in `contexts/vision-flight`,
  outside this module's own file boundary but factually stale otherwise) — it used to claim
  unconditional equivalence to `disarm(device, true)` and "an airborne vehicle will fall" for every
  device; both are now true only for `COPTER`/`PLANE`/`UNKNOWN`.
- **No web UI change shipped in R4b's own task**, despite the plan's own scope line naming
  `station/vision-web/.../fly/**`. There is no clicked "Emergency stop" button anywhere in this
  codebase's UI — `EMERGENCY_STOP` is reachable only via a bound RC switch action, dispatched by
  `station/vision-web`'s `core/rc/control-action-dispatcher.ts`, whose "Emergency stop" wording comes
  from `core/rc/control-action-logic.ts#actionLabel`. **This has since shipped, as part of FLEET-RADIO
  R2's task**: `actionLabel` now takes an optional `vehicleKind` and reads `'Emergency stop (Hold)'`
  on a `ROVER`, unchanged `'Emergency stop'` everywhere else — see `station/vision-web/MODULE.md` for
  the shipped signature and call sites.

### FLEET-RADIO R2 Gotchas

- **`VehicleClass.SUBMARINE` folds into `UnidentifiedReason.UNSUPPORTED_VEHICLE`, not its own
  reason.** `UnidentifiedReason` has three values, not one per `VehicleClass` constant — an operator
  refused engage on a submarine and one refused on a real-but-unsupported airframe both get told
  "this platform does not support flying or driving what's on this link", which is the true, actionable
  fact in both cases. `VehicleClass` itself keeps `SUBMARINE` a distinct constant (R1's decision,
  unchanged) purely so a future ArduSub wave has a slot to read from; `FlightModes.unidentifiedReason`
  is where the two are deliberately merged back down for the operator-facing message.
- **`unidentifiedReason()` is resolved once, at `engage`, from the same heartbeat `vehicleKind()`
  reads — never re-resolved per `send`.** Identical freshness contract to `vehicleKind()` itself: a
  vehicle that starts heartbeating a recognized `MAV_TYPE` mid-session does not retroactively change
  an already-refused engage (there is no session to change — `engage` throws before one is built), and
  does not need to, because `DefaultManualControlService#engage` re-resolves both from a fresh link on
  every call.

## Status

Real and load-bearing: RX ingest + fleet-gateway claim/re-election, guarded command TX (mode/arm/
disarm/emergency-stop/aux-function), the persistent RC-override relay, heartbeat discovery, the
onboarding probe/remediate/configure trio (`MavlinkVehicleConfigurator` + `MavlinkConnectRemediator`),
system-status health reporting, and the TX flight-plan simulator. Out of scope, deliberately:
`RANGEFINDER`→`aglMeters` fusion, DEM/terrain intersection, camera intrinsics (consumed by
`contexts/vision-map`, not this module), and PX4 mode-name tables. Try it against real ArduPilot
SITL: register a `mavlink` telemetry device with `uri = udp://0.0.0.0:14550` and
`sim_vehicle.py -v ArduCopter --out=udp:127.0.0.1:14550`.

**`docs/plans/active/FLEET-RADIO-PLAN.md` R1 done** — `FlightModes`, `MavlinkHeartbeatScanner` and
`MavlinkVehicleConfigurator` all now delegate vehicle-family/naming to `mavlink-core`'s one
`VehicleClass` table instead of three disagreeing local copies; the ArduRover mode table is
complete (Dock/Circle/Initialising); see the FLEET-RADIO R1 Gotchas above.

**`docs/plans/active/FLEET-RADIO-PLAN.md` R4b done (Java half)** — `MavlinkFlightCommander.emergencyStop`
is now vehicle-kind-gated: unchanged forced disarm for `COPTER`/`PLANE`/`UNKNOWN`, ArduRover `Hold`
for `ROVER`/surface boat, never a disarm following a successful `Hold`. See the FLEET-RADIO R4b
Gotchas above for the full rationale; its web half has since shipped, in R2's own task (see below).

**`docs/plans/active/FLEET-RADIO-PLAN.md` R2 done.** New `FlightModes.unidentifiedReason(int mavType)`
maps a `MAV_TYPE` to *why* it is `VehicleKind.UNKNOWN`, not merely that it is; `MavlinkManualControlSender`'s
`AdapterLink` now overrides `ManualControlLink#unidentifiedReason()` with it, resolved once at
`engage` alongside `vehicleKind()`. See the FLEET-RADIO R2 Gotchas above for the `SUBMARINE`→
`UNSUPPORTED_VEHICLE` folding decision.

**`docs/plans/active/FLEET-RADIO-PLAN.md` R4 done** — "the link has a name, and says when it dies."
`LinkHealth.Health` gained `PeerId` (D4, in `mavlink-core`); `MavlinkGateway`/`MavlinkTelemetrySource`'s
`claimedVehicleHealth()` both now return `Map<DeviceId, LinkHealth.Health>`; `MavlinkLinkStatusProvider`
was rewritten to aggregate per-vehicle at the consumer and name the specific worst device rather than
averaging a fleet-wide list. `MavlinkSession` gained `onLinkFailure` (F7, in `mavlink-core`) and
`MavlinkGateway` wires it to close every registered publisher exceptionally via
`VehicleClaimPolicy.closeAllPublishersExceptionally` (D5) before closing itself. The three severity/
promptness thresholds are `vision.mavlink.drop-rate-warn-percent`/`drop-rate-alarm-percent`/
`link-failure-grace` (D7), documented with defaults in `application.yaml`. See the FLEET-RADIO R4
Gotchas above for the `MavlinkGateway` test-seam constructor and the wiring-placement rationale.
`./mvnw -B -o -pl drone-link/mavlink test` — **235 tests**, all green (2026-08-27; +8 from R2's 227:
6 new `MavlinkLinkStatusProviderTest`, 2 new `MavlinkGatewayLinkFailureTest`).

**`docs/plans/active/FLEET-RADIO-PLAN.md` R7 test half done — F11 closed.** New
`MavlinkSitlRoverIntegrationTest` (1, docker-and-image gated exactly like the other four `MavlinkSitl*`
tests — see `SitlContainer`) drives a genuine ArduRover SITL instance through arm → mode change → RC
override and asserts all three of R7's own claims against real firmware: (1) the vehicle's own live
`HEARTBEAT.type` (10, GROUND_ROVER) fed into `FlightModes.selectableModes` resolves the rover table
with `"Dock"` present — a copter table can never contain it; (2) commanding `"Circle"` (one of R1's
own three newly-added modes, not a mode the pre-R1 table already had) is confirmed by the vehicle's
own *subsequent heartbeat* reporting `mode=Circle`, not merely by an `ACCEPTED` ack; (3) an RC
override on **extension channel 9** (R3/F4's exact bug) is confirmed by reading the vehicle's own
`RC_CHANNELS` (#65) telemetry back over a **second, independent** MAVLink connection opened straight
to SITL's own `serial2` control port (see `SitlContainer.serial2Port()`) — a channel this
module's production code never opens or reads — proving `chan9Raw` actually changes from its
pre-override baseline to the exact value the override placed there. See this test's own class javadoc
for the one honestly-scoped caveat: it proves the override changed the vehicle's belief about its RC
input, not that an `RCx_OPTION` aux function bound to CH9 would fire (this module deliberately keeps
no table of aux function numbers — see this file's own Gotchas on `MavlinkFlightCommander.auxFunction`
— so asserting on one here would mean asserting on a guessed magic number, not on anything R3 changed).
`SitlContainer` gained a `vehicle` parameter (`start(purpose, port, sysid, speedup, vehicle)`, the
three pre-existing overloads unchanged and still default to the image's own `copter`) and a
`serial2Port()` accessor for exactly this second-connection use case. Measured stable across three
consecutive runs: **~15.8s each**, no flakiness observed. `./mvnw -B -o -pl drone-link/mavlink test` —
**236 tests**, all green (2026-08-27, Docker available and used, not skipped).
