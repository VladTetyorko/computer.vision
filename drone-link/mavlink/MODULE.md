# adapter-mavlink

MAVLink 2 UDP telemetry ingest (RX), guarded command TX, a persistent RC-override relay, plug-and-fly
heartbeat discovery, and a flight-plan transmit simulator (TX) — the driven adapter for
`TelemetrySourcePort` / `FlightCommandPort` / `ManualControlPort` / `DeviceDiscoveryPort` /
`FeedTransmitterPort` over MAVLink 2. docs/plans/active/MAVLINK-CORE-PLAN.md **W4** rebuilt this module
onto `drone-link/mavlink-core`: it is now purely the **L5 translation layer** (vision domain types ⇄
mavlink-core's L3/L4 services) plus two pieces of genuine project policy — vehicle claim/re-election
and firmware mode-name tables — that a reusable library must not know about (plan §3.4).

**Depends on:** vision-kernel, vision-warehouse, vision-flight, vision-perception, `drone-link/mavlink-core`,
`io.dronefleet.mavlink:mavlink:1.1.11` (used directly only where mavlink-core's own contract requires a
raw library type: `MavlinkTelemetryDecoder`'s message-type dispatch, `FlightModes`'/`MavlinkFlightCommander`'s
`MavCmd`/`MavResult`, `SimulatedVehicleMessages`'s builders, `MavlinkHeartbeatScanner`'s `Heartbeat`) ·
**Used by:** vision-app

**Build/test:** `./mvnw -B -pl drone-link/mavlink test` — 155 tests across 13 classes.
**155/155 green**, confirmed across three consecutive full `-pl drone-link/mavlink test` runs
(docs/plans/active/GEO-POSE-PLAN.md wave V2). The two SITL-gated tests ran un-skipped and passed
every run (`vision-sitl:4.7.0` present on this machine): `MavlinkSitlSmokeIntegrationTest` 1/1 in
~1.2s, `MavlinkSitlReturnHomeIntegrationTest` 2/2 in ~31s. Timing-sensitive cases in
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
  port class to build a `CommandService`/`ManualControlService` on). Nested records
  `UnclaimedVehicle(int sysid, String firmware, Integer mavType, Instant lastHeard)`,
  `ClaimedVehicle(int sysid, DeviceId deviceId, String firmware, Integer mavType, Instant lastHeard)`,
  `CommandTarget(int sysid, String firmware, Integer mavType, InetSocketAddress sourceAddress)` —
  field-for-field identical to the pre-W4 `MavlinkSocketHub`'s own.
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
- `final class MavlinkFlightCommander implements FlightCommandPort` — `setMode`/`returnToHome`/
  `arm`/`disarm`/`capabilities`, unchanged resolve/reject rules and wire bytes (see Gotchas for
  what's frozen). Delegates the actual send/await to a fresh, per-call `com.drones.mavlink.service.CommandService`
  built from the resolved device's `MavlinkGateway.sink()`/`.correlator()`, **zero retries**
  (deliberately, to preserve the pre-existing single-shot wire behaviour). `static final int
  TARGET_COMPONENT_AUTOPILOT = 1` (referenced by `VehicleClaimPolicy` and `MavlinkManualControlSender`
  — single source of truth, same idiom as before W4). Constructors unchanged:
  `MavlinkFlightCommander(MavlinkTelemetrySource)`, `MavlinkFlightCommander(MavlinkTelemetrySource, Duration ackTimeout)`.
- `final class MavlinkManualControlSender implements ManualControlPort` — `engage`/`send`/`release`,
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
- `final class MavlinkTelemetryDecoder` (package-private) — state-holder split, now four-way as of
  docs/plans/active/GEO-POSE-PLAN.md wave V2 (`PositionAndPowerState`/`FlightStatusState`/
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
- `final class FlightModes`, `final class MavlinkRoute` (package-private) — untouched by W4/V2.

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

docs/plans/active/GEO-POSE-PLAN.md **wave V2 done**: this adapter now decodes the pose measurements
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
