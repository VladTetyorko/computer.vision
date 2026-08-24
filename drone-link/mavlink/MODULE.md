# adapter-mavlink

MAVLink 2 UDP telemetry ingest (RX), guarded command TX, a persistent RC-override relay, plug-and-fly
heartbeat discovery, and a flight-plan transmit simulator (TX) — the driven adapter for
`TelemetrySourcePort` / `FlightCommandPort` / `ManualControlPort` / `DeviceDiscoveryPort` /
`FeedTransmitterPort` over MAVLink 2. docs/plans/active/MAVLINK-CORE-PLAN.md **W4** rebuilt this module
onto `drone-link/mavlink-core`: it is now purely the **L5 translation layer** (vision domain types ⇄
mavlink-core's L3/L4 services) plus two pieces of genuine project policy — vehicle claim/re-election
and firmware mode-name tables — that a reusable library must not know about (plan §3.4).

**Depends on:** vision-kernel, vision-warehouse, vision-flight, vision-perception, vision-platform
(explicit, since wave S2 — for `SubsystemStatusPort`/`SubsystemStatus`/`Health`), `drone-link/mavlink-core`,
`io.dronefleet.mavlink:mavlink:1.1.11` (used directly only where mavlink-core's own contract requires a
raw library type: `MavlinkTelemetryDecoder`'s message-type dispatch, `FlightModes`'/`MavlinkFlightCommander`'s
`MavCmd`/`MavResult`, `SimulatedVehicleMessages`'s builders, `MavlinkHeartbeatScanner`'s `Heartbeat`) ·
**Used by:** vision-app

**Build/test:** `./mvnw -B -pl drone-link/mavlink test` — 189 tests across 19 classes.
**189/189 green**, confirmed across three consecutive full `-pl drone-link/mavlink -am test` runs
(docs/plans/active/DRONE-ONBOARDING-PLAN.md wave O8). Four SITL-gated tests now exist and all four ran
un-skipped and passed every run (`vision-sitl:4.7.0` present on this machine): `MavlinkSitlSmokeIntegrationTest`
1/1 in ~1.2s, `MavlinkSitlReturnHomeIntegrationTest` 2/2 in ~31s, `MavlinkSitlOnboardingIntegrationTest`
(wave O4) 1/1 in ~13s, `MavlinkSitlOnConnectIntegrationTest` (wave O8, **new**) 1/1 in ~13s. Timing-sensitive cases in
`MavlinkRoundTripIntegrationTest`/`MavlinkFleetGatewayIntegrationTest`/`MavlinkHeartbeatScannerTest`
poll to their own timeout and can flake under a `-Dtest=` filtered run in a loaded sandbox — always
verify via the full module build. **Note:** W4 documented one test (`MavlinkRoundTripIntegrationTest.anArdupilotmegaWindMessageSurvivesTheRealUdpPathIntoATelemetrySample`) as a deterministic failure. **It was fixed in `drone-link/mavlink-core` at the end of W4** — dialect is now learned per MAVLink *system id*, shared across every resync buffer on a link, rather than per source address. The test has passed on every run since. See the corrected Gotchas entry below.

## Levels

| Level | Lives in | Owns |
|---|---|---|
| L1–L3 (transport/codec/session) | `drone-link/mavlink-core` | sockets, framing, resync, peers, correlation, dispatch — see that module's own MODULE.md |
| L4 (services) | `drone-link/mavlink-core` | `CommandService`, `ManualControlService` — the actual send/await/relay machinery |
| **L5 (this module)** | `adapter-mavlink` | vehicle claim policy, MAVLink↔vision-domain translation, firmware mode-name tables, the synthetic TX simulator |

No class in this module constructs an `io.dronefleet.mavlink.MavlinkConnection` any more (verified by
grep) — every socket/session/service concern goes through `drone-link/mavlink-core`.

## API surface

### `com.drones.vision.adapter.mavlink`

- `final class MavlinkTelemetrySource implements TelemetrySourcePort` — RX. Supports protocol
  `"mavlink"` with a `udp://host:port` `StreamDescriptor.uri()` (**listen**, not connect — see
  Gotchas) and `Capability.TELEMETRY`. `supports(Device)`, `open(Device): Flow.Publisher<Telemetry>`,
  `close(DeviceId)`. Holds `Map<String bindKey, MavlinkGateway>`, one gateway per distinct
  `host:port`, reference-counted across every device sharing it. `StreamDescriptor.options["sysid"]`
  (lenient int 1-255) pins a device to a sysid; omitted/invalid means unpinned. Package-private:
  `static String bindKey(host, port)`, `bindKeyFor(Device)`, `hasActiveHub(bindKey)`,
  `unclaimedVehicles(bindKey)`, `claimedVehicles(bindKey)`, `commandTarget(bindKey, DeviceId)`, and
  `MavlinkGateway gateway(bindKey)` (the one new accessor W4 added, replacing the deleted `socket`/
  `awaitAck`/`cancelAckWait` pass-throughs — TX port classes now depend on a real collaborator).
  Public constructors unchanged: `MavlinkTelemetrySource()`, `MavlinkTelemetrySource(MavlinkSettings)`;
  package-private `MavlinkTelemetrySource(long silenceWindowMillis)` test seam.
  **`public List<com.drones.mavlink.session.LinkHealth.Health> claimedVehicleHealth()`** (docs/plans/done/SYSTEM-STATUS-PLAN.md
  §4.2, wave S2) — flattens `claimedVehicleHealth()` (see `MavlinkGateway`, below) across every gateway
  this source holds. **Public**, not package-private like this class's other accessors — a deliberate,
  narrow exception, made specifically so `vision-app`'s `SystemStatusWiring` (a different package) can
  take it as a method reference (`mavlinkTelemetrySource::claimedVehicleHealth`) for
  `MavlinkLinkStatusProvider` (`vision-platform`'s `SubsystemStatusPort`, implemented in this module —
  see below).
- `final class MavlinkGateway` (package-private, **new**) — one per bind address, replaces the old
  `MavlinkSocketHub`. Owns a `com.drones.mavlink.transport.UdpListenLink` (binds in its own
  constructor — throws `IOException` on conflict, see Gotchas), a
  `com.drones.mavlink.session.MavlinkSession` built with `MavlinkNode.groundStation()` (sysid 255 /
  compid 190), and a `VehicleClaimPolicy`. Subscribes once to `session.dispatcher()`; for every
  frame, asks `VehicleClaimPolicy` which registration owns that sysid and, if any, feeds the frame's
  raw payload to that registration's `MavlinkTelemetryDecoder` and submits the resulting `Telemetry`.
  `register(DeviceId, Integer pinnedSysid, SubmissionPublisher<Telemetry>): VehicleRegistration`,
  `unregister(VehicleRegistration): boolean` (true = now empty → gateway closes itself),
  `isClosed()`, `unclaimedVehicles()`, `claimedVehicles()`, `commandTarget(DeviceId)`, and
  `sink()`/`correlator()`/`peers()` (the session's `FrameSink`/`Correlator`/`PeerDirectory`, for a TX
  port class to build a `CommandService`/`ManualControlService` on). Also owns
  `MavlinkMessageInventory messageInventory()` (package-private, **new**, docs/plans/active/DRONE-ONBOARDING-PLAN.md
  wave O1 — see below); constructed alongside the session and closed in `close()`, between the frame
  subscription and the link. Also owns an optional `MavlinkConnectRemediator` (wave O8, **new** — see
  below), constructed **only** when `settings.onboarding().requestMessagesOnConnect()` is `true`; with
  the flag `false` the field stays `null` and no third dispatcher subscription is ever registered, so
  "flag off" is structurally "cannot send a command", not merely "chose not to send one". Nested records
  `UnclaimedVehicle(int sysid, String firmware, Integer mavType, Instant lastHeard)`,
  `ClaimedVehicle(int sysid, DeviceId deviceId, String firmware, Integer mavType, Instant lastHeard)`,
  `CommandTarget(int sysid, String firmware, Integer mavType, InetSocketAddress sourceAddress)` —
  field-for-field identical to the pre-W4 `MavlinkSocketHub`'s own.
  **`List<com.drones.mavlink.session.LinkHealth.Health> claimedVehicleHealth()`** (package-private,
  wave S2) — maps this gateway's `claimedVehicles()` to the underlying `session.health().of(PeerId)`
  reading for each one's `(sysid, compid=MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT)` identity
  — the same peer-identity convention `VehicleClaimPolicy` already uses (see above). One entry per
  claimed vehicle, no vehicle identity carried alongside it (`LinkHealth.Health` itself has none) —
  `MavlinkLinkStatusProvider` (below) is the class that turns this list into a rollup.
- `final class VehicleClaimPolicy` (package-private, **new**, replaces `VehicleClaimRegistry`) —
  project policy only: pinned-sysid claims, unpinned first-unclaimed-wins, 30s-silence re-election,
  the bounded (32) unclaimed registry. No longer parses `HEARTBEAT` itself — firmware/mavType/
  last-heard/reply-address come from `PeerDirectory`/`Peer.heartbeat()`, queried at
  `(sysid, compid=`{@code MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT}{@code =1})` (see
  Gotchas). `add`/`remove(VehicleRegistration)`, `unclaimedVehicles()`, `claimedVehicles()`,
  `commandTarget(DeviceId)`, `resolve(int sysid): VehicleRegistration` (called once per dispatched
  frame by `MavlinkGateway`).
- `final class VehicleRegistration` (package-private) — one device's claim state: `deviceId`,
  `pinnedSysid`, `publisher`, mutable `claimedSysid`/`decoder`. Shrunk in W4 — firmware/mavType/
  last-heard/source-address moved to `PeerDirectory` (see above), no longer duplicated here.
- `final class MavlinkMessageInventory` (package-private, **new**, docs/plans/active/DRONE-ONBOARDING-PLAN.md
  wave O1 — stage 1 of the onboarding probe pipeline) — a passive, per-peer message inventory: its own
  `Dispatcher.subscribe(MessageFilter.any(), ...)` counts every observed `(sysid, messageId)` pair's
  count/Hz over a rolling window and estimates each peer's bytes/s. One `MavlinkMessageInventory` per
  `MavlinkGateway` (constructed in its constructor, closed in its `close()`); observes every sysid on
  the link, claimed or not, independent of `VehicleClaimPolicy`. `observedPeers(): List<Integer>`,
  `snapshot(int sysid): PeerSnapshot` (`null` if never observed or LRU-evicted), `close()`. Nested
  records `MessageRate(int messageId, long count, double hz)` and
  `PeerSnapshot(int sysid, List<MessageRate> messages, long bytesPerSecond)`. **Rolling, not
  cumulative**: `count`/`hz` describe only the trailing `MavlinkSettings.Inventory.window()` — ages
  out to zero as real time passes even with no new frame arriving (query-time decay via a fixed-size
  ring of per-`bucketWidth` sums, no eviction thread needed), matching the plan's own §2.4
  never-a-frozen-live-value rule. **Per-sysid isolation** (one `PeerInventory` per observed system id,
  never pooled) is proven by a real-loopback test, `MavlinkMessageInventoryIntegrationTest`. Bounded
  memory against a flooding/malicious sender via LRU eviction on both tracked peers
  (`maxTrackedPeers`) and tracked message types per peer (`maxTrackedMessageTypesPerPeer`), the same
  `LinkedHashMap`+`removeEldestEntry` idiom `mavlink-core`'s own `FrameReader.buffers` already uses.
  **`bytesPerSecond` is a documented upper-bound estimate, not a wire measurement** — see Gotchas.
  Configured by the new `MavlinkSettings.Inventory(Duration window, Duration bucketWidth, int
  maxTrackedPeers, int maxTrackedMessageTypesPerPeer)` record (defaults: 10s window, 1s bucket, 64
  peers, 128 message types per peer — rationale for each is in that record's own javadoc), the 9th
  component of `MavlinkSettings`, threaded through `MavlinkSettings.defaults()`/`withSilenceWindow()`
  and a new `withInventory(Inventory)` copy method. A back-compat 8-arg `MavlinkSettings` constructor
  (defaulting `inventory` to `Inventory.defaults()`) keeps `vision-app`'s existing `TelemetryWiring`
  call site compiling unchanged — out of this wave's file scope.
- `final class MavlinkConnectRemediator` (package-private, **new**, docs/plans/active/DRONE-ONBOARDING-PLAN.md
  wave O8) — Mechanism A **on connect**: its own third, independent `Dispatcher.subscribe(MessageFilter.any(), ...)`
  fires `MAV_CMD_SET_MESSAGE_INTERVAL` (via a fresh `MessageIntervalService`/`CommandService` built on
  the gateway's session `FrameSink`/`Correlator`) for every configured message the instant a system id
  is newly learned. `MavlinkConnectRemediator(Dispatcher, FrameSink, Correlator, MavlinkSettings)`,
  `close()`. No new port, no dependency on `vision-flight`'s requirement table — the message set is
  pure configuration (`MavlinkSettings.Onboarding.onConnectMessageRequests()`), so this class stays as
  ignorant of *why* a message matters as `MavlinkFeedTransmitter` is. **Idempotent per peer**: a system
  id that keeps heartbeating is remediated exactly once; one that falls silent longer than
  `MavlinkSettings.silenceWindow()` (the same threshold `VehicleClaimPolicy` already uses) is treated
  as newly learned again next time it's heard — see O8 Gotchas for the reasoning and its real
  consequence (a request re-sent to an aircraft that never actually rebooted). **Requests are chained
  one at a time via `CompletableFuture.thenCompose`, never fired concurrently** — see O8 Gotchas for
  why a naive fire-and-forget loop is an actual bug here, not a style choice. Every outcome is only
  ever logged (INFO on accepted, WARNING otherwise); nothing here has a caller waiting on a result.
- `final class MavlinkFlightCommander implements FlightCommandPort` — `setMode`/`returnToHome`/
  `arm`/`disarm`/`emergencyStop`/`auxFunction`/`capabilities` (`capabilities` also reporting `FlightModes.vehicleKind(target.mavType())`
  as `FlightCapability#vehicleKind`), unchanged resolve/reject rules and wire bytes (see Gotchas for
  what's frozen). Delegates the actual send/await to a fresh, per-call `com.drones.mavlink.service.CommandService`
  built from the resolved device's `MavlinkGateway.sink()`/`.correlator()`, **zero retries**
  (deliberately, to preserve the pre-existing single-shot wire behaviour). `static final int
  TARGET_COMPONENT_AUTOPILOT = 1` (referenced by `VehicleClaimPolicy` and `MavlinkManualControlSender`
  — single source of truth, same idiom as before W4). Constructors unchanged:
  `MavlinkFlightCommander(MavlinkTelemetrySource)`, `MavlinkFlightCommander(MavlinkTelemetrySource, Duration ackTimeout)`.
  - **`emergencyStop(Device)`** (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §2.4) — a **forced disarm**
    on the wire (`MAV_CMD_COMPONENT_ARM_DISARM` with the `21196` magic), byte-identical to
    `disarm(device, true)`; QGroundControl's own `Vehicle::emergencyStop` sends exactly this. It is a
    separate method purely so the log line and the audit trail record which of the two the operator meant.
  - **`auxFunction(Device, int function, int level)`** (decision C5) — `MAV_CMD_DO_AUX_FUNCTION` (218):
    `param1` = the `RCx_OPTION` function number, `param2` = `0` low / `1` middle / `2` high; a `level`
    outside `[0,2]` throws `IllegalArgumentException` before anything is resolved or sent. **This adapter
    keeps no table of aux functions on purpose** — a stale copy of the firmware's own list is worse than
    none, so what a number means stays the vehicle's business and whether it acted shows up as the ack.
- `final class MavlinkManualControlSender implements ManualControlPort` — `engage`/`send`/`release`, its
  `AdapterLink` now also carrying **`vehicleKind()`** = `FlightModes.vehicleKind(target.mavType())`
  (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P9 — resolved from the heartbeat being
  heard at `engage` time and fixed for the link's life, so the stick layout the operator gets is the
  vehicle's own; a target that never sent a heartbeat yields `UNKNOWN`) and **`rateHz()`** = `coreRc.clampedOverrideHz()` (docs/plans/done/RC-LATENCY-PLAN.md
  §2 C — the real keepalive rate, so `vision-api` stops mirroring a constant). Latency behaviour follows
  `mavlink-core`'s reworked `ManualControlService`: a stick that moves reaches the wire on arrival
  (bounded by `vision.rc.max-override-hz`) instead of waiting out a fixed tick, while `vision.rc.override-hz`
  becomes the keepalive floor for an unchanged stick.
  unchanged v1-scope/latest-wins/release-burst rules. Delegates to a per-engage
  `com.drones.mavlink.service.ManualControlService`, sharing one `com.drones.mavlink.session.DefaultTxScheduler`
  (two daemon threads total) across every engaged link from one sender instance — replaces the
  pre-W4 one-hand-rolled-thread-per-link design (plan §4 DRY target). Translates `vision-flight`'s
  `RcChannels` into `mavlink-core`'s structurally-identical `RcChannels` at `send()` (the L4/L5
  boundary translation `mavlink-core`'s own MODULE.md flags as this wave's job). Constructors
  unchanged: `MavlinkManualControlSender(MavlinkTelemetrySource, MavlinkSettings.Rc)`; package-private
  `MavlinkManualControlSender(MavlinkTelemetrySource, long tickPeriodMillis, int releaseFrameCount)` test seam.
- `final class MavlinkHeartbeatScanner implements DeviceDiscoveryPort` — unchanged naming/
  categorization/`details` output. Hub-borrow path unchanged (polls `MavlinkGateway` via
  `MavlinkTelemetrySource`). Self-bind path now uses a plain `com.drones.mavlink.transport.UdpListenLink`
  + `com.drones.mavlink.codec.FrameReader` instead of a raw `DatagramSocket` +
  `MavlinkConnection`. Constructors unchanged.
- `final class MavlinkFeedTransmitter implements FeedTransmitterPort` — unchanged option surface/
  defaults/message content (`SimulatedVehicleMessages` untouched). `FeedRuntime`'s ephemeral send
  socket is now a `com.drones.mavlink.transport.UdpTargetLink` + `com.drones.mavlink.codec.FrameWriter`
  instead of a hand-rolled `DatagramSocket`/`MavlinkConnection` pair. Constructors unchanged.
- `final class MavlinkLinkStatusProvider implements SubsystemStatusPort` (`vision-platform`,
  docs/plans/done/SYSTEM-STATUS-PLAN.md §4.2, **new**, wave S2) — `mavlink-link`'s health self-report
  backing `GET /api/system/status`. Constructor takes `Supplier<List<LinkHealth.Health>>` (`vision-app`
  passes `mavlinkTelemetrySource::claimedVehicleHealth`, see above) rather than the source directly —
  matches `CvStatusProvider`'s (cv/grpc) supplier-based shape. No vehicle is claimed → `Health.UNKNOWN`
  ("No MAVLink vehicle is currently claimed"). Otherwise this is a rollup across every claimed vehicle,
  since `LinkHealth.Health` carries no vehicle identity to report per-vehicle: all `connected` →
  `Health.OK` (`detail` states the connected count and an aggregate average `dropRate` as a percentage);
  any vehicle whose `lastHeard()` has never been recorded at all → `Health.DOWN` (worst case, `since`
  is the stalest non-null `lastHeard` if any vehicle has one); otherwise (every vehicle heard from at
  least once, some just stale) → `Health.DEGRADED`, `detail` names the staleness age of the oldest
  reading. `hint` is "Check the MAVLink radio/link and vehicle power" for both `DOWN`/`DEGRADED`.
- `final class MavlinkTelemetryDecoder` (package-private) — state-holder split, now four-way as of
  docs/plans/done/GEO-POSE-PLAN.md wave V2 (`PositionAndPowerState`/`FlightStatusState`/
  `ArdupilotExtras`/`AttitudeState`); `accept`'s own dispatch/return-null/system-lock contract is
  unchanged. `Telemetry accept(MavlinkMessage<?>)` is a one-line adapter onto
  `Telemetry accept(int originSystemId, Object payload)` — `MavlinkGateway` calls the latter
  directly (a `com.drones.mavlink.codec.MavFrame` carries sysid and raw payload separately, not a
  dronefleet `MavlinkMessage` wrapper); every existing golden-bytes test keeps calling the original
  overload, unchanged. `static String firmwareLabel(int autopilot)` unchanged.
- `final class AttitudeState` (package-private, **new**, wave V2) — the fourth state-holder:
  aircraft attitude (`ATTITUDE` #30) and gimbal orientation (`GIMBAL_DEVICE_ATTITUDE_STATUS` #285
  preferred, `MOUNT_ORIENTATION` #265 deprecated fallback), materializing
  `com.drones.vision.kernel.Attitude`. Latches "#285 seen" permanently once true; from then on
  `applyMountOrientation` is a no-op for the decoder's remaining lifetime (G4). See Gotchas for the
  yaw-frame resolution rules.
- `final class QuaternionEuler` (package-private, **new**, wave V2) — `static Euler
  fromQuaternion(double w, double x, double y, double z)`, the standard ZYX (aerospace Tait-Bryan)
  quaternion→Euler decomposition; nested `record Euler(double rollDegrees, double pitchDegrees,
  double yawDegrees)`. No MAVLink/dronefleet types in its signature — pure math, independently unit
  tested (`QuaternionEulerTest`) against hand-computed values built via the inverse (Euler→quaternion)
  formula. See Gotchas for the convention and gimbal-lock behavior.
- `final class FlightModes` (package-private) — mode-name tables, plus **`static VehicleKind vehicleKind(int mavType)`** (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §1.4): sorts a `HEARTBEAT.type` into `COPTER`/`PLANE`/`ROVER` over the same `MAV_TYPE` sets `tableFor` already used for mode selection, and `UNKNOWN` for anything else (a GCS, an antenna tracker, a type this class has never seen). Deliberately **autopilot-independent**, unlike `tableFor`: what kind of machine it is does not depend on whose firmware is flying it.
- `final class MavlinkRoute` (package-private) — untouched by W4/V2.

## Message → field mapping

| MAVLink message | Field(s) | Domain field | Conversion |
|---|---|---|---|
| `GLOBAL_POSITION_INT` | `lat`/`lon` | `Telemetry.latitude`/`longitude` | ÷ 1e7 |
| | `alt` (mm, AMSL) | `Telemetry.altitudeMeters` | ÷ 1000 (AMSL, not `relativeAlt`) |
| | `relative_alt` (mm, height above home) | `Telemetry.aglMeters` | ÷ 1000; no sentinel, always sent (wave V2) |
| | `time_boot_ms` | `Telemetry.deviceBootMillis` | none (wave V2) |
| | `hdg` (cdeg, `65535`=unknown) | `Telemetry.headingDegrees` | ÷ 100, or `null` |
| | `vx`/`vy`/`vz` (cm/s, NED) | `extra["vxMps"/"vyMps"/"vzMps"]` | ÷ 100 |
| `ATTITUDE` (#30, common) | `roll`/`pitch`/`yaw` (rad) | `Attitude.rollDegrees`/`pitchDegrees`/`yawDegrees` | `Math.toDegrees` (wave V2) |
| `GIMBAL_DEVICE_ATTITUDE_STATUS` (#285, common, **preferred**) | `q` (quaternion, w x y z) | `Attitude.gimbalRollDegrees`/`gimbalPitchDegrees`/`gimbalYawDegrees` | `QuaternionEuler.fromQuaternion`; yaw resolved earth-frame via `flags`/`delta_yaw`, or `null` — see Gotchas (wave V2) |
| `MOUNT_ORIENTATION` (#265, common, deprecated **fallback**, only until #285 has arrived once) | `roll`/`pitch` (deg, global frame) | `Attitude.gimbalRollDegrees`/`gimbalPitchDegrees` | none |
| | `yaw_absolute` (deg, earth-frame extension field) | `Attitude.gimbalYawDegrees` | none, or `null` if `NaN`; plain `yaw` (vehicle-relative) is never used — see Gotchas (wave V2) |
| `SYS_STATUS` / `BATTERY_STATUS` | `batteryRemaining` (%, `-1`=unknown) | `Telemetry.batteryPercent` | none; last-message-wins |
| `SYS_STATUS` | `voltage_battery` (mV, `65535`=unknown) | `extra["batteryVoltage"]` | ÷ 1000, key omitted when unknown |
| `VFR_HUD` | `groundspeed` (m/s) | `extra["groundspeedMps"]` | none |
| `HEARTBEAT` | `autopilot` | `FlightState.firmware` | `3`→`"ardupilot"`, `0`→`"generic"`, `12`→`"px4"`, else `null` |
| | `base_mode` bit `128` | `FlightState.armed` | boolean, always set |
| | `base_mode` bit `1` gates `custom_mode` | `FlightState.mode` | resolved via `FlightModes.name(autopilot, type, custom_mode)`; unchanged when the bit is clear |
| | `system_status == MAV_STATE_CRITICAL` | `FlightState.failsafe` | boolean, always set (compared by enum identity, not a hardcoded ordinal — see Gotchas) |
| `GPS_RAW_INT` | `fix_type` | `FlightState.gpsFixType` | none (already the 0..8 ordinal) |
| | `satellites_visible` (`255`=unknown) | `FlightState.satellites` | none, or `null` |
| | `eph` (HDOP×100, `65535`=invalid) | `FlightState.hdop` | ÷ 100, or `null` |
| `RC_CHANNELS` / `RC_CHANNELS_RAW` | `rssi` (0–254, `255`=invalid) | `FlightState.rssiPercent` | `round(rssi/254×100)`, or `null` |
| `STATUSTEXT` | `text` matching `^(PreArm\|Arm): (.*)` | `FlightState.armingBlockers` | captured reason added to an insertion-ordered, cap-10, oldest-evicted-first set; cleared entirely the moment `HEARTBEAT` reports armed |
| `WIND` (ardupilotmega) | `direction` (deg) / `speed` (m/s) | `extra["windDirectionDegrees"/"windSpeedMps"]` | none |
| `VIBRATION` (common) | `vibrationX`/`vibrationY`/`vibrationZ` (m/s²) | `extra["vibeXMs2"/"vibeYMs2"/"vibeZMs2"]` | none; `clipping0`/`clipping1`/`clipping2` deliberately skipped (frozen key table) |
| `EKF_STATUS_REPORT` (ardupilotmega) | `velocityVariance`/`posHorizVariance`/`posVertVariance`/`compassVariance` | `extra["ekfVelocityVariance"/"ekfPosHorizVariance"/"ekfPosVertVariance"/"ekfCompassVariance"]` | none; `terrainAltVariance`/`airspeedVariance` dropped (no key in the frozen table) |
| `MISSION_CURRENT` (common) | `seq` | `extra["missionSeq"]` | none |
| `RANGEFINDER` (ardupilotmega) | `distance` (m) | `extra["rangefinderDistanceM"]` | none; `voltage` dropped |

Every other MAVLink message type is ignored. `Telemetry#flightState()` stays `null` until at least one
row above through `STATUSTEXT` has fired at least once for the decoder's lifetime.

## Conventions

- Plain classes, no Spring — instantiated directly by `vision-app`'s wiring config.
- Lenient numeric option parsing (RX's `sysid`; TX's `speedMps`/`batteryDrainPerSecond`/
  `positionRateHz`/`failsafeBatteryPercent`/`sysid`): missing/blank/malformed → default (RX:
  unpinned). `route` is the one deliberate exception (fails fast).
- Idempotent close via `AtomicBoolean` CAS; close-the-link-to-unblock-the-reader idiom, now
  delegated to `drone-link/mavlink-core`'s own links/session rather than hand-rolled per class.
- `public final class MavlinkVehicleConfigurator implements VehicleConfigPort` (`vision-flight`,
  docs/plans/active/DRONE-ONBOARDING-PLAN.md **wave O4**, **new**) — the MAVLink half of vehicle
  onboarding. `MavlinkVehicleConfigurator(MavlinkTelemetrySource)` /
  `MavlinkVehicleConfigurator(MavlinkTelemetrySource, MavlinkSettings)`. Public (not package-private
  like this module's plumbing) because `vision-app` must construct it to satisfy the port.
  - `boolean supports(Device)` — delegates to `MavlinkTelemetrySource.supports`, so one source of
    truth decides what this module can carry.
  - `VehicleProfile probe(String linkKey, Duration window)` — stage 3 of the plan's pipeline: O1's
    passive `MavlinkMessageInventory` + one `AUTOPILOT_VERSION` (`CapabilityService`) + one batch of
    named parameter reads (`ParameterService.readAll`), assembled into one snapshot. Never throws for
    an incomplete answer.
  - `MessageIntervalOutcome requestMessageInterval(String linkKey, int messageId, Duration interval)` —
    **Mechanism A**, `MAV_CMD_SET_MESSAGE_INTERVAL` via `mavlink-core`'s `MessageIntervalService`.
    Session-scoped; nothing persisted, so no snapshot and no restore.
  - `List<ParameterReading> readParams(String linkKey, List<String> names)` — named reads only; a name
    that goes unanswered has **no entry**, never a fabricated zero.
  - `ParameterWriteOutcome writeParam(String linkKey, String name, double value)` — **Mechanism B**,
    the only method here that changes persistent state. Snapshots first (a value that cannot be read
    cannot be restored, so the write is refused), writes, and reports the aircraft's read-back.
  - Addressed by `linkKey` (`"udp://host:port#sysid"`), not by `Device`, because the probe runs
    *before* registration when no `DeviceId` exists (plan D7). The `#sysid` suffix is optional for
    `probe` alone; the other three reject a link key without it.
  - Rides the `MavlinkGateway` already bound to the address when there is one (closing nothing), and
    opens a temporary gateway when there is not (closing exactly that one). See Gotchas.
- `MavlinkSettings.Onboarding` (10th `MavlinkSettings` component; back-compat 9-arg constructor
  kept, plus `withOnboarding(...)`) — `record Onboarding(List<String> probeParameters, Duration
  capabilityTimeout, int capabilityRetries, Duration parameterTimeout, int parameterRetries,
  boolean requestMessagesOnConnect, List<MessageRequest> onConnectMessageRequests)`. Rejects a
  probe-parameter name longer than MAVLink's 16-character `param_id` at construction rather than
  silently truncating it into a *different* parameter.
  - **Wave O8 addition:** `requestMessagesOnConnect` (**default `false`**) gates
    `MavlinkConnectRemediator` entirely (see above); `onConnectMessageRequests` is the message set it
    asks for, as `record MessageRequest(int messageId, Duration interval)` — a wire `messageId`, not a
    name (this module carries no name→id table outside `MavlinkVehicleConfigurator`'s best-effort
    dialect lookup), `interval` following `MessageIntervalService.setMessageInterval`'s own contract
    (negative rejected, `Duration.ZERO` legal — "resume default rate", not "never"). A back-compat
    5-arg `Onboarding` constructor keeps `MavlinkSitlOnboardingIntegrationTest`/
    `MavlinkVehicleConfiguratorTest`'s existing call sites compiling, defaulting the flag `false` and
    the message set to `defaults()`'s own nine-entry firmware-verified list (500ms/2Hz each:
    `SYS_STATUS`, `ATTITUDE`, `GLOBAL_POSITION_INT`, `RC_CHANNELS`, `SERVO_OUTPUT_RAW`, `VFR_HUD`,
    `GPS_RAW_INT`, `SCALED_IMU2`, `SYSTEM_TIME` — the same nine `MavlinkSitlOnboardingIntegrationTest`
    proved ArduPilot 4.7 accepts) so flipping the flag alone still does something sensible.
- `SitlContainer` (test-only, package-private, **new** in O4) — the one docker/SITL harness every SITL
  test in this module now shares (`dockerAvailable()`, `imagePresent()`, `start(purpose, port, sysid[,
  speedup])`, `AutoCloseable`, `freePort()`). `MavlinkSitlSmokeIntegrationTest` and
  `MavlinkSitlReturnHomeIntegrationTest` each carried their own copy before; a third was about to be
  written. It picks each container's ArduPilot `INSTANCE` itself rather than always using 0, so
  concurrent builds do not collide on SITL's host-bound ports (see Gotchas).
- `MavlinkGateway`/`VehicleClaimPolicy` are guarded by one private monitor each (`VehicleClaimPolicy`'s
  own lock protects claim state; `PeerDirectory` itself needs no external lock, it's independently
  thread-safe).

## Gotchas

- **`udp://host:port` means listen, not connect (RX).** A telemetry radio or SITL instance *pushes*
  datagrams to this app; `MavlinkTelemetrySource` never dials out. `host` is the local bind address
  (wildcard when blank), `port` the local bind port.
- **Claim/re-election is project policy, not a protocol fact — `VehicleClaimPolicy`, not
  `PeerDirectory`.** A **pinned** device claims exactly that sysid, permanently, the first time
  it's heard. An **unpinned** device claims the first sysid heard that nothing else claims; if its
  claimed vehicle then falls silent for the configured window (30s prod default), it may re-elect —
  checked lazily, only when a message from some other still-unclaimed sysid next arrives. A sysid
  nothing claims lands in the bounded (32) unclaimed registry.
- **Vehicle identity for reachability is fixed at `(sysid, compid=1)`** — `VehicleClaimPolicy`
  queries `PeerDirectory` at the autopilot component id every producer this module talks to (real
  ArduPilot, `MavlinkFeedTransmitter`, every test double) already uses. A vehicle whose telemetry
  arrives from some other component id would still be sysid-claimed correctly but would report as
  unreachable/firmware-unknown until its autopilot component itself transmits — an accepted
  simplification, matching the old sysid-only design's own known limits.
- **A fresh `MavlinkTelemetryDecoder` on every claim/re-election is load-bearing.** A decoder's
  accumulated fields belong to one physical vehicle; reusing one across a claim change leaks stale
  values into the new vehicle's first samples. `VehicleClaimPolicy.assignClaim` enforces this.
- **Bind failures are now synchronous, not asynchronous.** `com.drones.mavlink.transport.UdpListenLink`
  binds in its own constructor; a conflict throws `IOException`, which `MavlinkTelemetrySource`
  wraps as `UncheckedIOException` and lets propagate synchronously from `open(Device)`. Before W4,
  the hub bound lazily on a background thread and a conflict surfaced later via the publisher's
  `onError`. This is a deliberate, more honest behaviour change — no test exercised the old
  asynchronous path, so nothing broke, but a caller checking for a bind conflict must now catch it
  around `open()` itself rather than subscribing and waiting for `onError`.
- **A genuine mid-stream link failure no longer signals every registered publisher's `onError`.**
  Pre-W4, `MavlinkSocketHub`'s own read loop caught the `IOException` from a broken socket and
  called `closeExceptionally` on every live registration. `drone-link/mavlink-core`'s `MavlinkSession`
  reader thread (`LinkRuntime.runLoop`) catches that same exception internally, logs a WARNING, and
  just stops — it exposes no hook for `MavlinkGateway` to detect the failure and forward it. A
  device whose gateway dies unexpectedly (not via `close()`) now goes silent with no `onError`/
  `onComplete` ever firing, rather than erroring. **No existing test exercises this path** (every
  test only ever closes cleanly), so this did not break the acceptance criterion, but it is a real
  production-robustness regression and would need a `drone-link/mavlink-core` change (a link-failure
  callback on `MavlinkSession`, or an equivalent) to close — flagged, not fixed, per this wave's
  frozen-library rule.
- **`MavFrame` carries no raw wire byte length, so `MavlinkMessageInventory.bytesPerSecond()` is an
  upper-bound estimate, not a measurement — a real gap, flagged for a future `mavlink-core` wave.**
  `FrameReader` reads a frame's raw bytes only transiently (to pull two flag bytes into `MavHeader`)
  then discards the array; `MavFrame` (frozen by that module's own `API.md`) has nowhere to keep it.
  Absent that field, this class estimates each frame's size as fixed protocol overhead
  (version/signature-dependent: 8 bytes v1, 12 bytes v2 unsigned, +13 more for v2's signature block)
  plus that message type's *maximum* payload length — the sum of every `@MavlinkFieldInfo` field's
  `unitSize() * max(1, arraySize())`, exactly what `io.dronefleet.mavlink`'s own
  `ReflectionPayloadSerializer` would allocate, computed once per payload class and cached (never
  reflected on a per-frame basis). MAVLink 2 trims trailing all-zero bytes off the wire, so this
  over-estimates whenever a v2 message's trailing fields happen to be zero — closing the gap for real
  would need a `MavFrame.wireLength()`-shaped field added to `mavlink-core`, out of this wave's scope
  (task brief: no library change).
- **The ardupilotmega dialect is now learned per UDP source address, not per sysid — a
  `drone-link/mavlink-core` L2 design tradeoff, confirmed by a failing test.** Before W4, this module read
  every message off one long-lived `MavlinkConnection` per socket; that connection's own
  `systemDialects` cache was keyed purely by sysid, so one vehicle's first `HEARTBEAT` unlocked
  ardupilotmega-only messages (`WIND`/`EKF_STATUS_REPORT`/`RANGEFINDER`) for the rest of that
  connection's life, from *any* physical sender claiming that sysid. `drone-link/mavlink-core`'s
  `FrameReader`/`ResyncBuffer` instead keeps one resync buffer **per source `LinkPeer`** (the fix
  for a real pre-W4 flaw — concatenating datagrams from different senders into one byte stream) and
  primes each buffer's own fresh `MavlinkConnection` with whatever dialect *that same buffer* last
  resolved — a documented, deliberate per-buffer approximation of the old per-sysid cache (see
  `mavlink-core`'s own `ResyncBuffer` Gotchas) — **fixed at the end of W4, entry kept for history.**
  W1 keyed the learned dialect per resync buffer, i.e. per *source address*. An ArduPilot vehicle's own
  `HEARTBEAT` therefore unlocked ardupilotmega-only messages (`WIND`, `EKF_STATUS_REPORT`,
  `RANGEFINDER`) for that one address only, so the same vehicle relayed through a second address — a
  companion computer, a router, or a plain NAT rebind — silently fell back to `CommonDialect` and
  stopped decoding them. `MavlinkRoundTripIntegrationTest.anArdupilotmegaWindMessageSurvivesTheRealUdpPathIntoATelemetrySample` sends a raw `WIND` datagram from a separate ephemeral socket precisely to
  prove the dialect mechanism holds over the real socket/thread path, so it caught this exactly as
  designed. The fix is in `mavlink-core`'s `ResyncBuffer#dialectForPendingFrame`: dialect is a property
  of the **vehicle**, not of the address its datagrams arrive from — the same `(sysid, compid)`-not-
  transport-address identity rule the rest of that module follows — and is now held in one map shared
  across every buffer on the link. Guarded by `DialectIsLearnedPerSystemNotPerSourceTest` in
  `mavlink-core`, which was verified to fail without the fix.

### O4 Gotchas (probe, remediation, parameter names)

- **ArduPilot 4.7 has no `SRx_*` stream-rate parameters at all.** `SR0_*`, `SR1_*` and `SR2_*` were
  each read off a live Copter 4.7.0 instance and every one is absent. Consequence: **Mechanism A
  (`MAV_CMD_SET_MESSAGE_INTERVAL`) is not one of two ways to fix a starved link on this firmware, it
  is the only way** — the plan's Tier-A "write `SR2_EXTRA2`" remediation cannot exist here. Whoever
  writes the readiness/remediation table (O8/O11) needs this before designing around `SR2_*`.
- **A vehicle pushing at a UDP `--out` channel streams almost nothing until asked.** Measured: four
  message types — `HEARTBEAT` at 1 Hz plus three event-driven ones at ~0.1 Hz — not the dozen a
  connected GCS sees. This is the concrete gap the whole onboarding plan exists to close, and it is
  why `MavlinkSitlOnboardingIntegrationTest` is shaped probe → remediate → probe again.
- **Parameter names are firmware-version state, not constants.** Copter 4.7 renamed
  `SYSID_THISMAV`→`MAV_SYSID`, `FS_BATT_ENABLE`→`BATT_FS_LOW_ACT`, `GPS_TYPE`→`GPS1_TYPE`; `RTL_ALT`,
  `WPNAV_SPEED`, `ARMING_CHECK`, `LAND_SPEED`, `ANGLE_MAX` and `PILOT_SPEED_UP` are all absent too.
  Every name in `Onboarding.defaults()` was verified against a live instance, and the SITL test
  asserts **all twenty** answer — because MAVLink gives an autopilot no way to report an unknown
  name. It just says nothing, so a stale list degrades into a slow probe that quietly reads less than
  it claims. Anyone changing the list must re-verify it, not reason about it.
- **Never close a borrowed gateway.** `MavlinkGateway.close()` was widened from private to
  package-private for `MavlinkVehicleConfigurator`'s registration-less probe gateway. The invariant
  that still holds absolutely is *close only what you opened* — a borrowed gateway backs a registered
  device's live telemetry, and closing it would tear that down. Enforced by the configurator's
  `LinkLease`, which records which case it is.
- **"Never heard from" is not "asked and got no answer".** `RoutingFrameSink` cannot address a peer
  it has no link for, so a request to an unheard aircraft is *never sent*. The configurator checks
  reachability before every send and says which happened; both are `NO_ACK` (nothing changed either
  way), but only one is a fact about the aircraft. Reporting an unsent request as an unanswered one
  would read as "this firmware does not support it".
- **`readAll` fires every name at once, deliberately.** An absent name costs a full timeout, so
  batching would serialise those waits instead of overlapping them. Twenty concurrent
  `PARAM_REQUEST_READ`s against ArduPilot 4.7 lose nothing — every name that exists comes back.
- **`MessageObservation.name` is best-effort.** Resolved through `ArdupilotmegaDialect` (a superset of
  common) plus a CamelCase→SCREAMING_SNAKE transform (`VfrHud`→`VFR_HUD`), and `null` for an id the
  dialect does not know. An unrecognised message still counts toward the inventory.
- **Two SITL containers at the same instance cannot coexist — `SitlContainer` allocates one.**
  `--network host` is what keeps the telemetry wiring simple, and it is also what puts SITL's *own*
  listeners on the host: `arducopter -I N` binds TCP 5762/5763 and UDP 5501, each offset by 10·N.
  Started twice at instance 0, the second container dies at boot (`bind port 5762 for SERIAL1`) and
  its test fails much later, looking like lost telemetry rather than a port clash. **Reproduced**
  by running two builds of this module at once — ordinary here, since one agent may verify while
  another builds. `SitlContainer.claimInstance()` now probes and reserves a free instance (0–9), and
  `start()` verifies the container is still alive before returning, so this failure names itself.
  Verified by parking a foreign SITL on instance 0 and running the suite green around it.

### O8 Gotchas (on-connect remediation)

- **`COMMAND_ACK` correlates on `(origin sysid, command id)` only — never on which message id a
  `MAV_CMD_SET_MESSAGE_INTERVAL` asked for.** `mavlink-core`'s `CorrelationKeys.forCommandAck` builds
  one `MatchKey` per `(sysid, command)`, so every one of the nine default on-connect requests to the
  same peer produces the *identical* key. A naive loop that fired all nine at once (the first draft of
  `MavlinkConnectRemediator.remediate`, caught before any test was written against it) would have
  registered a second live `Correlator.await` for a key the first request already occupies —
  `DefaultCorrelator` throws `IllegalStateException` rather than silently orphan the first waiter. The
  fix is real, not cosmetic: requests are chained via `CompletableFuture.thenCompose`, so request
  `n+1` is sent only once request `n`'s exchange has resolved (acked or timed out) — serialised
  without ever blocking the dispatcher's calling thread, since `thenCompose` on an incomplete future
  only registers a continuation. Anyone adding a second on-connect command type in the future must
  keep this serialisation; two independent command *types* to the same peer would use distinct
  `MatchKey`s and could safely run concurrently, but two of the *same* type cannot.
- **What "reconnect" means for idempotency is a judgement call, not a protocol fact.** MAVLink gives
  an observer no boot counter and no session identifier, so there is no wire-level way to tell "same
  aircraft, radio blipped for a second" apart from "fresh boot, back to starved defaults". This module
  resolves that ambiguity toward the safe side: a system id is treated as newly learned again once
  it has gone unheard for longer than `MavlinkSettings.silenceWindow()` (30s prod default) — reusing
  `VehicleClaimPolicy`'s own threshold rather than inventing a second one. The real cost of resolving
  it this way: an aircraft that merely had a 31-second radio dropout gets re-remediated for free
  (cheap — ArduPilot just re-confirms a rate it already honours); the alternative (guessing "no
  reboot happened") risks silently leaving a rebooted aircraft back in the exact starved state this
  mechanism exists to close, which is the worse failure to risk.
- **The plan's stated SITL exit criterion cannot be run, and the corrected one is what
  `MavlinkSitlOnConnectIntegrationTest` proves.** The plan describes connecting with `SR2_EXTRA2=0`
  and observing `VFR_HUD` arrive without a parameter write. Wave O4 already established that **no
  `SRx_*` parameter exists on ArduPilot 4.7 at all** (see the O4 Gotchas block above) — there is no
  parameter to set to `0`. The corrected, actually-run criterion: connect once with the flag off and
  observe the same starved baseline O4 measured (a handful of message types, `VFR_HUD` absent);
  connect a second time, to the same still-running SITL, with the flag on, and observe the configured
  set begin arriving on the very first heartbeat of that connection — with `MavlinkVehicleConfigurator`
  never even constructed in the test, so literally no parameter is read or written anywhere in it.
- **The message-rate threshold in that SITL test has real headroom, deliberately.** VFR_HUD is
  requested at a 500ms/2Hz nominal rate; the test's floor is 1.0 Hz (50%), not 2.0 (0%). The inventory
  reads a decaying rolling-window rate off real wall-clock time, and a full-module test run has this
  test's own SITL container competing with every other test's JVM/threads for CPU — an instantaneous
  reading as low as 1.8 Hz was observed under that load even though the aircraft was honouring the
  request exactly. A zero-headroom threshold is not a meaningful assertion of "streaming near what was
  requested" under those conditions; it is a coin flip against scheduler noise.

## Test scaffolding changed in W4 (docs/plans/active/MAVLINK-CORE-PLAN.md §6.1 rule 3)

Every existing assertion, expected value, and wire-level check is unchanged. Only how each test's
own fakes talk to the wire changed, because the classes they used to build on were deleted:

- **`MavlinkFeedTransmitterTest`** — `firstOriginSystemId`'s raw receiver migrated from
  `MavlinkUdpInputStream` + `MavlinkConnection` to `UdpListenLink` + `FrameReader`.
- **`MavlinkRoundTripIntegrationTest`** — `sendRawWindDatagram` migrated from
  `MavlinkUdpOutputStream` + `MavlinkConnection` to `UdpTargetLink` + `FrameWriter`.
- **`MavlinkFlightCommanderTest`** — `FakeVehicle` migrated from `MavlinkUdpInputStream`/
  `MavlinkUdpOutputStream` + `MavlinkConnection` to a bidirectional `UdpTargetLink` +
  `FrameWriter`/`FrameReader` (`FrameWriter.broadcast` is internally synchronized per link, so the
  old explicit `writeLock` around concurrent heartbeat/ack sends is gone too). `awaitClaimedWithFirmware`
  retyped from `MavlinkSocketHub.CommandTarget` to `MavlinkGateway.CommandTarget`.
- **`MavlinkManualControlSenderTest`** — `FakeVehicle` migrated the same way; the received frame's
  source port now reads off `MavFrame.source().port()` instead of a bespoke `lastSourceAddress()`
  accessor. `awaitReachable` retyped to `MavlinkGateway.CommandTarget`. One assertion's *scaffolding*
  changed without changing its *meaning*: `telemetrySource.socket(bindKey).getLocalPort()` (that
  accessor is deleted — see API surface) became the already-known local `port` variable, since
  `UdpListenLink` always binds to the exact configured port, never an OS-assigned one — mathematically
  the same value, proving the same thing (frames arrive from the platform's own bound port, not a
  socket this port class opened itself).
- **`MavlinkFleetGatewayIntegrationTest`** — two usages plus the local helper's return type retyped
  from `MavlinkSocketHub.UnclaimedVehicle` to `MavlinkGateway.UnclaimedVehicle`.
- **`MavlinkTelemetryDecoderTest`** — one javadoc `{@code MavlinkSocketHub}` reference corrected to
  describe the new per-source dialect mechanism; no code, assertion, or helper touched.

## Status

docs/plans/active/MAVLINK-CORE-PLAN.md **W4 done**: rewired onto `drone-link/mavlink-core`. Deleted outright:
`MavlinkSocketHub`, `CommandAckRegistry`, `MavlinkUdpInputStream`, `MavlinkUdpOutputStream`.
`VehicleClaimRegistry` → `VehicleClaimPolicy`. New: `MavlinkGateway`. Five port classes' public
constructors unchanged (D8). `vision-app`'s wiring config, ArchUnit rules, and every other adapter
are untouched (out of that wave's file scope).

docs/plans/done/GEO-POSE-PLAN.md **wave V2 done**: this adapter now decodes the pose measurements
`GeoProjection.aimFrom` (vision-kernel wave V1) needs — `GLOBAL_POSITION_INT.relative_alt`/
`time_boot_ms`, `ATTITUDE` (#30), `GIMBAL_DEVICE_ATTITUDE_STATUS` (#285, preferred) and
`MOUNT_ORIENTATION` (#265, deprecated fallback) — landing on `Telemetry.aglMeters`/`attitude`/
`deviceBootMillis`. New: `AttitudeState` (fourth decoder state-holder), `QuaternionEuler` (standalone
quaternion→Euler math, independently unit-tested). `MavlinkTelemetryDecoderTest.ignoresUnrecognizedMessageTypes`
now uses `SYSTEM_TIME` instead of `ATTITUDE` as its example of a genuinely unmapped message, since
`ATTITUDE` is recognized as of this wave. 155/155 tests green (135 pre-existing + 20 new: 15 decoder
tests, two of them purely defensive NaN/infinite-wire-value coverage since `Attitude`'s compact ctor
throws on a non-finite component, + 5 `QuaternionEulerTest` cases), confirmed across three consecutive
full-module runs; SITL tests ran un-skipped and passed every run. Out of scope, deliberately:
`RANGEFINDER`→`aglMeters` (G2 — see Gotchas), DEM/terrain intersection, camera intrinsics, and
`contexts/vision-map`/`vision-api`/`vision-web` (wave V3's job — this module does not consume
`CameraAim`/`aimFrom` itself).

Try it with real ArduPilot SITL (unchanged by W4/V2): register a `mavlink` telemetry device with
`uri = udp://0.0.0.0:14550` and `sim_vehicle.py -v ArduCopter --out=udp:127.0.0.1:14550`. ArduCopter's
SITL streams `ATTITUDE` and `GLOBAL_POSITION_INT` (with a non-zero `relative_alt`) by default; it does
not run a simulated gimbal, so `GIMBAL_DEVICE_ATTITUDE_STATUS`/`MOUNT_ORIENTATION` were validated via
golden-bytes tests only, not against a live SITL gimbal — flagged as untested against a real sender in
the report for this wave.

docs/plans/done/SYSTEM-STATUS-PLAN.md **§4.2, wave S2 done**: `mavlink-link`'s health self-report for
`GET /api/system/status` (station/vision-api). Two small, additive changes to existing classes —
`MavlinkGateway.claimedVehicleHealth()` (package-private) and `MavlinkTelemetrySource.claimedVehicleHealth()`
(widened to **public**, a deliberate exception to this class's usual package-private-plumbing convention,
made solely so `vision-app`'s `SystemStatusWiring` — a different package — can take it as a method
reference) — plus one new class, `MavlinkLinkStatusProvider implements SubsystemStatusPort`. See API
surface above for both. Module gained an explicit `vision-platform` dependency purely to compile
against `SubsystemStatusPort`/`SubsystemStatus`/`Health` — no new runtime coupling beyond that one
interface, and no change to this module's actual MAVLink transport/session behavior. `./mvnw -B -pl
core/vision-platform,cv/grpc,drone-link/mavlink,video-output/publish-hls,station/vision-api,station/vision-app
test -DskipWeb`: this module **155/155 green, unchanged count** — `MavlinkLinkStatusProvider` has no
dedicated unit test of its own (deferred, same reasoning as `cv/grpc`'s `CvStatusProvider`: it is a
thin mapping/rollup over `MavlinkTelemetrySourceTest`'s already-tested `claimedVehicleHealth` path and
`vision-api`'s `SystemStatusControllerTest`, which exercises the endpoint's aggregation logic against
fake `SubsystemStatusPort`s). Wired by `vision-app`'s `SystemStatusWiring` — unconditionally (unlike
`cv-service`/`video-publish`, `mavlink-link` has no enable/disable flag of its own to gate a companion
`Health.DISABLED` bean on).

docs/plans/active/DRONE-ONBOARDING-PLAN.md **wave O1 done**: passive message inventory (msgid → Hz,
bytes/s per peer), stage 1 of the probe pipeline O4 will later consume — no library change, no write,
no new port, pure observation of what a connected vehicle already sends. New: `MavlinkMessageInventory`
(package-private, one per `MavlinkGateway`) and `MavlinkSettings.Inventory` (new 9th `MavlinkSettings`
component, back-compat 8-arg constructor kept for `vision-app`'s `TelemetryWiring`, untouched — out of
this wave's file scope). See API surface and Gotchas above. 164/164 tests green (155 pre-existing +
8 new `MavlinkMessageInventoryTest` cases covering the counting/windowing/eviction arithmetic against
a hand-fake `Dispatcher` + 1 new `MavlinkMessageInventoryIntegrationTest` case — the task's mandated
real-loopback proof that two genuine `MavlinkFeedTransmitter` feeds at distinct sysids on one socket
never pool their counts), confirmed across three consecutive full-module runs; SITL tests ran
un-skipped and passed every run. Production-robustness finding (not fixed, flagged only, per this
wave's frozen-library rule): `MavFrame` carries no raw wire byte length, so `bytesPerSecond()` is a
documented upper-bound estimate rather than a true measurement (see Gotchas). `drone-link/mavlink-core`
was not touched (another wave's concurrent scope).

docs/plans/active/DRONE-ONBOARDING-PLAN.md **wave O4 done**: `MavlinkVehicleConfigurator implements
VehicleConfigPort` — the module now *asks* a vehicle what it is and *changes* what it streams, where
before it only listened. Probe = O1's inventory + `AUTOPILOT_VERSION` + a named parameter batch
(both new services from O2's `mavlink-core`); Mechanism A = `MAV_CMD_SET_MESSAGE_INTERVAL`;
Mechanism B = snapshot → `PARAM_SET` → read-back, reported `DENIED` (never `ACCEPTED`) when the
aircraft stored something other than what was asked for. New: `MavlinkVehicleConfigurator`,
`MavlinkSettings.Onboarding` (10th component, back-compat 9-arg constructor kept for `vision-app`'s
`TelemetryWiring`, untouched — out of this wave's file scope), and the shared test-only
`SitlContainer`. `MavlinkGateway.close()` widened private→package-private (see Gotchas). See API
surface and the O4 Gotchas block above.

**172/172 tests green** (164 pre-existing, none weakened, + 7 new `MavlinkVehicleConfiguratorTest`
cases + 1 new `MavlinkSitlOnboardingIntegrationTest` case), confirmed across three consecutive full
`-pl drone-link/mavlink -am test` runs with **`Skipped: 0`** — all three SITL tests ran un-skipped
against real ArduPilot Copter 4.7.0 every run, as this wave's brief requires.

The SITL test proves, against firmware rather than our own simulator: 20/20 configured parameters
read; `AUTOPILOT_VERSION` decoded to a `major.minor.patch` version and a capability set containing
`MAV_PROTOCOL_CAPABILITY_MAVLINK2`; nine `MAV_CMD_SET_MESSAGE_INTERVAL` requests `ACCEPTED` and the
requested streams then observed arriving (≥8 message types, `VFR_HUD` ≥3 Hz, up from four types
before); and a `FENCE_ALT_MAX` write surviving an *independent* re-read plus a restore to the
snapshot. `MavlinkVehicleConfiguratorTest` covers the half SITL cannot — what the configurator claims
when a vehicle answers nothing — since a real aircraft that answers correctly can never demonstrate
honest reporting of an aircraft that doesn't.

Two findings that change work downstream, not deferred but recorded because they belong to later
waves: ArduPilot 4.7 has **no `SRx_*` stream-rate parameters**, so O8/O11's remediation table cannot
be built on writing them; and the plan's original probe list named eleven parameters that do not
exist on that firmware, now replaced with twenty verified ones. Both are written up in the O4 Gotchas
block above.

docs/plans/active/DRONE-ONBOARDING-PLAN.md **wave O8 done**: Mechanism A now also fires *automatically*
the instant a gateway learns a peer, not only when `MavlinkVehicleConfigurator` is asked to. New:
`MavlinkConnectRemediator` (package-private, one per `MavlinkGateway`, constructed only when the flag
is on) and two new `MavlinkSettings.Onboarding` components, `requestMessagesOnConnect` (**default
`false`**) and `onConnectMessageRequests` — a back-compat 5-arg `Onboarding` constructor keeps this
wave's two existing call sites (`MavlinkSitlOnboardingIntegrationTest`, `MavlinkVehicleConfiguratorTest`)
compiling unchanged. **No new port, no dependency on `vision-flight`'s requirement table** — the
message set is pure configuration, per the task brief. `station/vision-app/**` (the `vision.onboarding.*`
Spring property wiring for this flag) is explicitly out of this wave's file scope — a different wave
(O5) owns it. See API surface and the O8 Gotchas block above for the correlation-collision fix,
the silence-window idempotency-reset decision, and the corrected SITL exit criterion.

**189/189 tests green** (172 pre-existing, none weakened, + 8 new `MavlinkSettingsTest` cases + 6 new
`MavlinkConnectRemediatorTest` cases (fast hand-fake `Dispatcher`/`FrameSink`/`Correlator`, covering
per-peer sequencing/idempotency/silence-reset/empty-set arithmetic) + 2 new
`MavlinkConnectRemediationIntegrationTest` cases (real UDP loopback via a hand-rolled bidirectional
`FakeVehicle`, proving both "the configured set arrives in order and is not resent on later
heartbeats" and, as the task brief's requirement 3 demands, "the flag off means literally zero
commands sent" — asserted, not assumed) + 1 new `MavlinkSitlOnConnectIntegrationTest` case), confirmed
across three consecutive full `-pl drone-link/mavlink -am test` runs with **`Skipped: 0`** — all four
SITL tests (including the pre-existing three) ran un-skipped against real ArduPilot Copter 4.7.0 every
run.

The SITL test proves, against firmware rather than this module's own simulator: a stock SITL connected
with the flag off streams the same starved baseline wave O4 measured (`VFR_HUD` absent, a handful of
message types); reconnecting with the flag on to the *same still-running* container makes the
configured nine-message set begin arriving automatically on the very first heartbeat of that new
connection, `VFR_HUD` observed near its requested 2 Hz — all without `MavlinkVehicleConfigurator` ever
being constructed, i.e. without any parameter read or write anywhere in the test. That is the corrected
form of the plan's own stated exit criterion (see O8 Gotchas for why the literal criterion, written
around a parameter that does not exist on this firmware, could not be run as stated).

**docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md Wave P3 done** (this adapter's half of per-vehicle stick layouts): `FlightModes.vehicleKind(int mavType)` added; `MavlinkFlightCommander#capabilities` and `MavlinkManualControlSender`'s `AdapterLink` both now report it. **Nothing new is decoded** — the vehicle family was already being classified from `HEARTBEAT.type` to pick a mode table, then thrown away. This wave only routes a fact the adapter had all along up to the layer that needed it, which is why the diff is three small methods and no wire change. 6 new `FlightModesTest` assertions + updated `MavlinkFlightCommanderTest`/`MavlinkManualControlSenderTest`; `./mvnw -B -pl drone-link/mavlink -am test` green.

**docs/plans/active/CONTROLLER-SETUP-CONTEXT.md Wave C3 done** (this adapter's half of operator-bound switches): `MavlinkFlightCommander` gained `emergencyStop` (forced disarm, `21196`) and `auxFunction` (`MAV_CMD_DO_AUX_FUNCTION` 218). Both reuse the existing resolve → `requireCommandableFirmware` → `send` → await-ack path, so the frozen wire rules in Gotchas apply to them unchanged. One command reaches every `RCx_OPTION` feature an airframe has, which is why no per-gadget port method (gimbal, gripper, camera) was added — see the context doc §3.1.
