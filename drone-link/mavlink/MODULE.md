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
authorization; who is allowed to call them is enforced above this module. Two send paths fire with
no explicit per-call request. `MavlinkConnectRemediator`'s on-connect Mechanism A is
**default-off**: `MavlinkSettings.Onboarding.requestMessagesOnConnect()` defaults `false`, and
`MavlinkGateway` constructs no remediator at all when it's false — structurally "cannot send", not
merely "chose not to". **(MAVLINK-COMMANDS-PLAN P2)** `MavlinkStreamNegotiator`, by contrast, is
**always constructed and always fires**, on every claim — one `MAV_CMD_REQUEST_MESSAGE` probe plus
(unless Mechanism A already owns the peer's interval channel) a `MAV_CMD_SET_MESSAGE_INTERVAL` chain
for six cockpit messages. This has no flag of its own, deliberately (see the P2 Gotchas below and
`MavlinkStreamNegotiator`'s own class javadoc for why) — every real GCS does this at connect, so
there is nothing to structurally prevent here the way Mechanism A's opt-in remediation is prevented.

## API surface

### `com.drones.vision.adapter.mavlink`

- `public final class MavlinkTelemetrySource implements TelemetrySourcePort` — RX. Protocol
  `"mavlink"`, `udp://host:port` `StreamDescriptor.uri()` (**listens**, never connects). `Capability.TELEMETRY`.
  `supports(Device)`, `open(Device): Flow.Publisher<Telemetry>`, `close(DeviceId)`,
  `public Map<DeviceId, LinkHealth.Health> claimedVehicleHealth()` (**FLEET-RADIO R4/D4** — was
  `List<LinkHealth.Health>`; the one public accessor besides the port methods — `vision-app`'s
  `SystemStatusWiring` takes it as a method reference for `MavlinkLinkStatusProvider`, which now
  aggregates per-vehicle instead of averaging a fleet-wide list). **(ZERO-CONFIG-ONBOARDING Z2b, new)**
  `public void holdLobby(int port)` / `public void releaseLobby(int port)` — the claim-free standing
  lobby (see its own Gotchas entry below). Package-private: `static bindKey(host, port)`, `bindKeyFor(Device)`,
  `hasActiveHub(bindKey)`, `unclaimedVehicles(bindKey)`, `claimedVehicles(bindKey)`,
  `commandTarget(bindKey, DeviceId)`, `gateway(bindKey)`. `StreamDescriptor.options["sysid"]`
  (lenient int 1–255) pins a device to one sysid; missing/invalid → unpinned. Constructors: `()`,
  `(MavlinkSettings)`; package-private `(long silenceWindowMillis)` test seam. One `MavlinkGateway`
  per distinct bind address, reference-counted across every device sharing it — `holdLobby`/`releaseLobby`
  share that same reference-counting via the identical `gateways.compute` path `open`/`close` use, always
  at `DEFAULT_BIND_HOST` ("0.0.0.0").
- `final class MavlinkGateway` (package-private) — one per bind address (`host:port`). Owns a
  `MavlinkLink` (production: a `UdpListenLink`, binds in its own constructor, throws `IOException`
  on conflict), a `MavlinkSession` built with `MavlinkNode.groundStation()` (sysid 255/compid 190), a
  `VehicleClaimPolicy`, a `MavlinkMessageInventory`, and optionally a `MavlinkConnectRemediator`
  (only when `settings.onboarding().requestMessagesOnConnect()` is `true`) and, **(MAVLINK-COMMANDS-PLAN
  P2, always)**, a `MavlinkStreamNegotiator`, built before `VehicleClaimPolicy` so its `negotiate(int)`
  method reference can be handed in as the claim policy's `onClaimed` hook. Demultiplexes every
  dispatched frame by sysid only (never source address — a companion computer relaying several
  vehicles is one physical source for all of them). `register(DeviceId, Integer pinnedSysid,
  SubmissionPublisher<Telemetry>): VehicleRegistration`, `unregister(...): boolean` (true once it has
  actually closed the gateway — **since Z2b**, an empty claim policy alone is no longer sufficient; a
  live lobby hold (`lobbyHeld.get()`) suppresses the close exactly like a live device registration
  always has), `isClosed()`, `unclaimedVehicles()`, `claimedVehicles()`,
  `commandTarget(DeviceId)`, `sink()`/`correlator()`/`peers()` (session collaborators for a TX class
  to build a mavlink-core service on), `messageInventory()`, `Map<DeviceId, LinkHealth.Health>
  claimedVehicleHealth()` (**FLEET-RADIO R4/D4** — was `List<LinkHealth.Health>`; keyed by the
  claiming device, resolving each `ClaimedVehicle`'s `PeerId` and querying `session.health().of(...)`
  per vehicle rather than returning one undifferentiated list), `close()` (package-private —
  besides `unregister`, only `MavlinkVehicleConfigurator` calls it, for a self-bound probe gateway
  it opened itself). **(ZERO-CONFIG-ONBOARDING Z2b, new)** `void holdLobby()` / `void releaseLobby()`
  / `boolean isLobbyHeld()` — the claim-free hold and its GCS heartbeat TX (own Gotchas entry below).
  Constructors: `(String bindHost, int port, MavlinkSettings)` (production,
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
  sysid (pinned/unpinned claim + re-election — see Gotchas). Constructor takes an
  `IntConsumer onClaimed` (**MAVLINK-COMMANDS-PLAN P2**, one call site: `MavlinkGateway` passes
  `streamNegotiator::negotiate`) — `assignClaim` invokes it with the claimed sysid exactly once per
  claim event (initial claim and silence-window re-election alike), never for an unclaimed sysid
  merely landing in the unclaimed registry, and never twice for one claim. `add`/`remove(VehicleRegistration):
  boolean`, `unclaimedVehicles()`, `claimedVehicles()`, `commandTarget(DeviceId)`,
  `resolve(int sysid): VehicleRegistration` (called once per dispatched frame),
  `closeAllPublishersExceptionally(Throwable)` (**FLEET-RADIO R4/D5**, package-private — called only
  by `MavlinkGateway.handleLinkFailure`; closes every currently-registered device's
  `SubmissionPublisher` via `closeExceptionally`, under the same lock `add`/`remove` use),
  `boolean isEmpty()` (**ZERO-CONFIG-ONBOARDING Z2b, new** — package-private, called only by
  `MavlinkGateway.releaseLobby()`; `true` once no registration at all remains, under the same lock).
  One private monitor.
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
- `final class MavlinkStreamNegotiator` (package-private) — **(MAVLINK-COMMANDS-PLAN P2, new)** the
  stream negotiation every real GCS does at connect, on every claim (not gated by
  `Onboarding.requestMessagesOnConnect()` — see its own class javadoc and the P2 Gotchas below).
  `(FrameSink sink, Correlator correlator, MavlinkSettings settings)`, `void negotiate(int sysid)` —
  the one method, handed to `VehicleClaimPolicy` as its `onClaimed` hook. Sends one
  `MAV_CMD_REQUEST_MESSAGE`(512) for `AUTOPILOT_VERSION` (via a fresh `CapabilityService`), then,
  unless `settings.onboarding().requestMessagesOnConnect()` is `true`, one
  `MAV_CMD_SET_MESSAGE_INTERVAL`(511) per `settings.streamNegotiation().streams()` entry, strictly
  sequential via `CompletableFuture.thenCompose` (never blocks the calling — session reader — thread).
  Every outcome is logged and none of them fail the claim: `UNSUPPORTED`/`DENIED`/`NO_ACK` → INFO;
  a send-level fault (e.g. no link registered) → WARNING. Deliberately does **not** wire its probe's
  `CapabilityReport` into `MavlinkFlightCommander.capabilities(Device)` this wave — a known, documented
  scope boundary, not an oversight.
- `public final class MavlinkFlightCommander implements FlightCommandPort` — `setMode`/
  `returnToHome`/`arm`/`disarm`/`emergencyStop`/`auxFunction`/`capabilities`. Every command is one
  `COMMAND_LONG` from a fresh, per-call `CommandService` built on the resolved device's gateway.
  **(MAVLINK-COMMANDS-PLAN P1)** No longer single-shot: `send`'s private `retryable` parameter gates
  a bounded retry (`MavlinkSettings.commandRetries()`, default 2, i.e. 3 attempts) at `ackTimeout()`
  per attempt (default 700ms) — only for *absolute-state* commands, which today is all four callers
  (`setMode`/`armOrDisarm`/`emergencyStopRover`/`auxFunction`, all pass `true`); a future
  relative/incremental command must pass `false` at its own call site. A terminal `COMMAND_ACK`
  (e.g. `DENIED`) never retries — only silence does (`mavlink-core`'s `RequestResponse`, unmodified).
  See the MAVLINK-COMMANDS-PLAN P1 Gotchas below for the force-magic split and the retry eligibility
  rule in full. `static final int TARGET_COMPONENT_AUTOPILOT = 1`. Only ArduPilot/INAV (`autopilot`
  ARDUPILOTMEGA) is commandable; Betaflight (`autopilot` GENERIC) is rejected before `FlightModes` is
  even consulted, even though its own table has an RTL-named mode. **`emergencyStop` is
  vehicle-kind-gated (FLEET-RADIO R4b)** — see its own Gotchas section below; it is no longer a
  single, uniform command for every device. Constructors: `(MavlinkTelemetrySource)` and
  `(MavlinkTelemetrySource, Duration ackTimeout)` are back-compat overloads (the latter is
  `vision-app`'s `TelemetryWiring` call site, out of this module's reach — see Gotchas for the
  production gap this leaves); `(MavlinkTelemetrySource, MavlinkSettings)` is canonical.
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
  **(MAVLINK-COMMANDS-PLAN P2, new)** `probe`'s capability request and `requestMessageInterval` both
  now go through a private `awaitWithContentionRetry` rather than the plain `await` every other call
  here still uses — see the P2 Gotchas below for why (they share a correlator key with
  `MavlinkStreamNegotiator`, which now fires on every claim).
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
  returns empty even though it has a table, since its RC link never processes `DO_SET_MODE`; **
  MAVLINK-COMMANDS-PLAN P1** also excludes `ARDUPILOT_ROVER`'s `"Initialising"` — custom_mode 16, a
  boot transient nothing should ever be commanded into — from this list only; `name`/`customModeFor`
  still resolve it both ways, so it stays fully decodable inbound),
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
  Duration closeJoinTimeout, Duration ackTimeout, int commandRetries, Scan scan, Transmit transmit,
  Rc rc, Inventory inventory, Onboarding onboarding, LinkStatus linkStatus, StreamNegotiation
  streamNegotiation)` — this module's tunables, `vision-app` maps `vision.mavlink.*`/`vision.rc.*`
  onto one. **(MAVLINK-COMMANDS-PLAN P1)**
  `ackTimeout` is now documented as the **per-attempt** wait (re-scoped from a single whole-command
  wait — its type and default-field position are unchanged, only its meaning); `commandRetries` is
  new, inserted right after it, `>= 0` enforced in the compact constructor. `static defaults()` →
  `ackTimeout = 700ms`, `commandRetries = 2` (`DEFAULT_ACK_TIMEOUT_MILLIS`/`DEFAULT_COMMAND_RETRIES`,
  D2a — worst case 3 attempts × 700ms ≈ 2.1s, inside the old single-wait 2s budget).
  `withSilenceWindow`/`withAckTimeout`/`withCommandRetries`/`withInventory`/`withOnboarding`/
  `withLinkStatus`/`withStreamNegotiation` (**MAVLINK-COMMANDS-PLAN P2, new**). Back-compat 8-arg and
  9-arg constructors default every field added after them, **including `commandRetries`** now (to
  `DEFAULT_COMMAND_RETRIES`), `linkStatus` (`LinkStatus.defaults()` — FLEET-RADIO R4), and
  `streamNegotiation` (`StreamNegotiation.defaults()` — P2) kept both overloads' arity unchanged per
  CLAUDE.md rule 10 / java-clean-code §3: a new collaborator updates call sites and back-compat
  delegation targets, never a new overload). **`vision-app`'s `TelemetryWiring#toMavlinkSettings`
  calls the 8-arg overload**, so every deployment picks up `commandRetries = 2` **and**
  `StreamNegotiation.defaults()` automatically the moment this module is rebuilt — see the
  MAVLINK-COMMANDS-PLAN P1 Gotchas below for the one place this back-compat design does *not* close
  the loop (the `ackTimeout` actually wired into `MavlinkFlightCommander` in production). Nested:
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
  - `record StreamNegotiation(List<Onboarding.MessageRequest> streams)` — **(MAVLINK-COMMANDS-PLAN
    P2, new)** `MavlinkStreamNegotiator`'s own interval-chain configuration, reusing
    `Onboarding.MessageRequest`'s `(int messageId, Duration interval)` shape rather than duplicating
    it. Deliberately no enable flag (contrast `Onboarding.requestMessagesOnConnect()`) — see the P2
    Gotchas below. `static defaults()` → six cockpit messages at 250ms/4Hz each: `ATTITUDE`(30),
    `GLOBAL_POSITION_INT`(33), `VFR_HUD`(74), `RC_CHANNELS`(65), `GPS_RAW_INT`(24, gates
    `GLOBAL_POSITION_INT`'s lat/lon trustworthiness per the OPERATOR-UX-4 N1 Gotcha above),
    `BATTERY_STATUS`(147). No `VisionMavlinkProperties` field exists for this yet — see the P2 Gotchas
    below for the "documentation only, but not dormant" distinction from `command-retries`' gap.
- `final class SimulatedVehicleMessages` (package-private) — the MAVLink message builders
  `MavlinkFeedTransmitter` calls (`heartbeat`, `sysStatus`, `gpsRawInt`, `globalPositionInt`).

## Message → field mapping (`MavlinkTelemetryDecoder`)

| MAVLink message | Field(s) | Domain field | Conversion |
|---|---|---|---|
| `GLOBAL_POSITION_INT` | `lat`/`lon` | `Telemetry.latitude`/`longitude` | ÷ 1e7, **only when `FlightStatusState.hasGpsFix()` is true** (OPERATOR-UX-4 N1 — `GPS_RAW_INT.fix_type` ≥ `GPS_FIX_TYPE_2D_FIX`; unheard/no-fix → both `null`, dropping any previously-known position) |
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
| `GPS_RAW_INT` | `fix_type` | `FlightState.gpsFixType` | none (already the 0..8 ordinal); also gates `GLOBAL_POSITION_INT`'s `lat`/`lon` (see above row) |
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
- **(OPERATOR-UX-4 N1 — fixed) No GPS fix means no position, never `{0,0}`.** A real ESP32 rover
  with no GPS fix sends `GLOBAL_POSITION_INT` with `lat=lon=0` regardless — before this fix, this
  decoder recorded that unconditionally, so warehouse's `lastKnownPosition` became `{0,0,0}` and the
  UI plotted the vehicle on Null Island (an ocean spot off West Africa). Fixed by gating
  `PositionAndPowerState.applyPosition`'s `lat`/`lon` on `FlightStatusState.hasGpsFix()`
  (`GPS_RAW_INT.fix_type >= GPS_FIX_TYPE_2D_FIX`, read off the enum via this codebase's established
  `EnumValue.of(...).value()` idiom — never a bare `2` literal): no fix (or no `GPS_RAW_INT` ever
  heard, treated identically — "unknown" is never "assume it's fine") emits a sample with `latitude`/
  `longitude` both `null`; a fix that drops **mid-session** drops the position on the *next*
  `GLOBAL_POSITION_INT`, not merely stops updating it — a stale last-known reading is never left in
  place once the fix that produced it is gone. Every other field on the same message (`alt`, `hdg`,
  velocity, `relative_alt`, `time_boot_ms`) is unaffected; only the lat/lon pair is gated. This
  depends on `GPS_RAW_INT` having been processed by the time a given `GLOBAL_POSITION_INT` arrives —
  true for every real vehicle and for `SimulatedVehicleMessages` (always transmits a fixed 3D fix,
  and does so before its first position tick in `MavlinkFeedTransmitter.FeedRuntime`'s send-order),
  so RX/TX round-trip and SITL tests stay green unchanged.
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
  calling thread. **(MAVLINK-COMMANDS-PLAN P2)** This is not just an intra-class concern any more —
  `MavlinkStreamNegotiator`'s own interval chain, `MavlinkConnectRemediator`'s on-connect chain, and
  `MavlinkVehicleConfigurator`'s manual `requestMessageInterval`/`probe` calls all now share this same
  keying scheme for the same peer, so any two of them running close together for one vehicle can
  collide on it. See the P2 Gotchas below for the two fixes this wave added (`MavlinkStreamNegotiator`
  stepping aside for Mechanism A; `MavlinkVehicleConfigurator.awaitWithContentionRetry`).
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

### ZERO-CONFIG-ONBOARDING Z2b Gotchas

- **The lobby hold must never appear in `VehicleClaimPolicy.registrations` — it is a completely
  separate `AtomicBoolean lobbyHeld` on `MavlinkGateway`, not a fake `Device`.** An unpinned
  registration claims the first sysid heard (`VehicleClaimPolicy.claim`); if a lobby hold registered
  one to keep itself alive, it would steal the claim from whichever real device is actually meant to
  own that vehicle. `holdLobby()`/`releaseLobby()` touch only the gateway's own flag and (on release)
  query `claimPolicy.isEmpty()` — they never call `register`/`unregister`.
- **`unregister()`'s self-close condition is now `empty && !lobbyHeld.get()`, and `releaseLobby()`'s
  is the mirror: clear the flag, then close only `if (claimPolicy.isEmpty())`.** These are two
  independent multi-step operations racing on the same gateway with no shared lock beyond
  `VehicleClaimPolicy`'s own monitor (for `remove`/`isEmpty`) and the `AtomicBoolean` CAS (for
  `lobbyHeld`). Both orderings were checked by hand: whichever of the two operations *reads* the
  other's just-written state sees it (the CAS and the synchronized query are each individually
  sequentially consistent), so there is no interleaving where both operations observe "someone else
  is still holding this open" and the gateway leaks open forever, nor one where both decide to close
  while the other's caller still believes it has a live registration/hold.
- **A genuine link failure closes the gateway unconditionally, lobby held or not.**
  `MavlinkGateway.close()` always runs `stopLobbyHeartbeat()` first (idempotent — a no-op if no hold
  is active), so `handleLinkFailure` → `close()` (FLEET-RADIO R4/F7's existing wiring, unchanged)
  tears down the heartbeat scheduler exactly as it tears down every other collaborator, before
  evicting the gateway from `MavlinkTelemetrySource`'s `gateways` map. `lobbyHeld` itself is **not**
  cleared by `close()` — harmless, since a closed gateway is discarded, never reused; only
  `isClosed()` is the contract callers actually rely on.
- **Healing after close reuses `open(Device)`'s existing `compute` replace-when-closed logic — it was
  not new code to write, only to route `holdLobby(int)` through.** Both `holdLobby(int)` and
  `open(Device)` call the identical `gateways.compute(bindKey, (key, existing) -> existing == null ||
  existing.isClosed() ? newGateway(...) : existing)`; a lobby hold on a port whose gateway has already
  self-closed (link failure, or a zero-device release) gets a fresh, working gateway on the very next
  `holdLobby` call, not a resurrected dead one.
- **GCS heartbeat TX is owned by the hold itself, not by `MavlinkTelemetrySource`.**
  `holdLobby()` builds one `DefaultTxScheduler` + core `HeartbeatService` (constructed off the
  gateway's own `MavlinkSession` — `session.sink()`/`session.peers()`, so identity is GCS 255/190 for
  free) and calls `start()`; `releaseLobby()` and `close()` both call `stop()`+`scheduler.close()`
  through the same private `stopLobbyHeartbeat()`, stored in an `AtomicReference` so a concurrent
  close and release can't both tear it down or leak it. Mirrors
  `MavlinkManualControlSender`'s pattern of owning a `DefaultTxScheduler` for its own TX lifetime,
  except per-hold rather than per-instance, since a hold can be acquired and released many times.
- **`HeartbeatService` only replies to a link's *last-learned* peer — accepted, not fixed.** Its
  `emitHeartbeat()` (mavlink-core, unchanged) sends one heartbeat per distinct `LinkId` known to
  `PeerDirectory#peers()`, addressed at whatever peer was most recently heard on that link. With
  several vehicles simultaneously announcing on the same held port, the reply rotates between them —
  each new announcer's own next heartbeat re-targets it, so every vehicle transmitting at its own
  ≥1 Hz PX4-convention rate still gets its lock-on within a few seconds; nothing here queues or
  fans out one heartbeat per known peer.
- **`MavlinkHeartbeatScanner` needed no code change.** Its existing `hasActiveHub(bindKey)` check
  already steers `scan()` onto the hub-borrow path whenever *any* gateway is registered at that
  bind key — a lobby-held gateway satisfies that exactly like a device-backed one always has, so the
  unclaimed-vehicle registry the lobby accumulates is already the scanner's discovery feed with zero
  scanner changes.
- **`MavlinkLobbyHoldTest` covers this wave** (real UDP loopback throughout, no mocks): hold-then-open
  and open-then-hold both reuse one `MavlinkGateway` (asserted by reference identity, package-private
  `gateway(bindKey)`) and the device's claim still works; release with zero devices closes and frees
  the socket (a probe re-bind after release must succeed); release with a live device leaves the
  gateway open and clears only the flag; a lobby hold alone never claims a heard sysid (stays in
  `unclaimedVehicles` until a real device registers and claims it); a lobby hold heals after its
  gateway has already closed; the heartbeat scheduler is proven to start on hold and stop on release
  by actually listening for (and later for absence of) a `HEARTBEAT` reply on a hand-built fake
  vehicle's own UDP socket, not by inspecting threads; a dedicated `FailingLink`-based gateway-level
  test proves a genuine link failure closes a held gateway regardless of the hold; the scanner test
  asserts both `hasActiveHub` and an actual successful `scan()` discovery through a held-only port.

### MAVLINK-COMMANDS-PLAN P1 Gotchas

- **The force-arm magic was `21196` (the force-*disarm* magic) until this wave — a live defect, not
  a matter of style.** `MAV_CMD_COMPONENT_ARM_DISARM`'s param2 "force" magic is `2989` on the arm
  path and `21196` on the disarm path (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2b, verified
  against ArduPilot's own arming docs and issues #32996/#26521); `armOrDisarm` sent `21196` on both
  before this wave. It "worked" for a forced arm only because ArduPilot firmware silently *bypasses
  every pre-arm check* on receiving the wrong magic instead of correctly rejecting the malformed
  request — every forced arm ever sent by this class before this wave has been an accidental
  safety-check bypass, not the deliberate "I know what I'm doing, force it" the `force=true` flag is
  supposed to mean. Now split into `ARM_FORCE_MAGIC = 2989f` / `DISARM_FORCE_MAGIC = 21196f`,
  selected by which value `armParam` is; the forced-disarm value is unchanged (still correctly
  `21196` — `emergencyStop`'s unconditional forced disarm is byte-identical to before).
- **Retry eligibility is frozen at the call site, not derived from the command type.** `send`'s
  private `boolean retryable` parameter is the one place this decision lives (docs/plans/active/
  MAVLINK-COMMANDS-PLAN.md D2a) — `true` only for *absolute-state* commands, where resending after
  silence is safe because the state requested does not change between attempts (`mavlink-core`'s
  `CommandService` itself increments `confirmation` per attempt, so the vehicle can tell a resend
  from a fresh request, per MAVLink's own confirmation-field contract). All four current callers
  (`setMode`, `armOrDisarm`, `emergencyStopRover`, `auxFunction`) pass `true`, each with an inline
  comment naming why. Any future *relative/incremental* command (e.g. a delta `DO_REPOSITION`) **must
  pass `false`** — resending it would double-apply the delta, which the whole point of silence-only
  retry must never risk. A terminal `COMMAND_ACK` (`DENIED`, `UNSUPPORTED`, …) never triggers a
  retry regardless of `retryable` — `mavlink-core`'s `RequestResponse.retryOrFail` only resends on
  `TimeoutException`, confirmed by reading it directly and covered by
  `aDeniedAckIsTerminalAndNeverTriggersARetry`.
- **`vision.mavlink.command-retries` default of 2 is gated on `infra/rover-sim`'s F0 idempotency
  case** (a repeated `COMMAND_LONG` with a rising `confirmation` must be a firmware no-op, never a
  double-effect) — run by a different wave/agent in parallel with this one. Shipped as 2 regardless,
  per this wave's own instruction; if F0 finds the firmware non-idempotent, the fix is one line
  (`DEFAULT_COMMAND_RETRIES` in `MavlinkSettings`, or `vision.mavlink.command-retries: 0` in
  `application.yaml`), not a design change — the retry machinery itself doesn't care what the budget
  is, `armReturnsNoAckAfterExhaustingEveryConfiguredAttempt` reads the live default rather than
  hardcoding "3" for exactly this reason.
- **Known production gap: `vision-app`'s `TelemetryWiring`/`VisionMavlinkProperties` were out of this
  wave's file scope, so the *deployed* `ackTimeout` does not actually become 700ms.**
  `TelemetryWiring#mavlinkFlightCommander` still calls `MavlinkFlightCommander`'s 2-arg
  `(MavlinkTelemetrySource, Duration)` back-compat constructor with `properties.ackTimeout()`
  (`VisionMavlinkProperties`'s `@DefaultValue("2s")`, unchanged) — so production gets the *new*
  `commandRetries = 2` (via `MavlinkSettings`'s 8-arg back-compat constructor default, which
  `toMavlinkSettings` does call) layered onto the *old* 2s per-attempt timeout, not the intended
  700ms. Worst case for a fully-silent vehicle is therefore **~6s (3 × 2s), not the ~2.1s this wave's
  own D2a decision claims** — a real, currently-unaddressed latency regression risk, not merely an
  aesthetic gap. Closing it needs a future wave to add a `commandRetries` field to
  `VisionMavlinkProperties`, thread it through `TelemetryWiring`, and switch that wiring onto
  `MavlinkFlightCommander`'s canonical `(MavlinkTelemetrySource, MavlinkSettings)` constructor instead
  of the 2-arg one — none of which this wave's brief (`drone-link/mavlink/**` plus the
  `application.yaml` `mavlink:` block only) permits touching. The `application.yaml` block documents
  this gap inline next to `ack-timeout`/`command-retries`.
- **`MavlinkFlightCommanderTest`'s retry tests read `MavlinkSettings.defaults()` for the expected
  attempt count rather than hardcoding it**, so they stay correct if a future wave (e.g. the
  F0-triggered flip above) changes `DEFAULT_COMMAND_RETRIES`; the ack-on-retry test additionally
  `assumeTrue`s `commandRetries() >= 1` so it skips cleanly (not red) if that default ever drops to 0.

### MAVLINK-COMMANDS-PLAN P2 Gotchas

- **Negotiation is best-effort, by design — nothing it sends can fail a claim.**
  `MavlinkStreamNegotiator.negotiate` is fired from inside `VehicleClaimPolicy.assignClaim`'s own
  monitor, but every send it makes is async (`CompletableFuture`, never blocking); `UNSUPPORTED`/
  `DENIED`/`NO_ACK` on any one message is logged (INFO for the honest-refusal codes, WARNING for a
  send-level fault or an unexpected result) and the chain simply continues to the next message. A
  vehicle that ignores every request still claims normally and streams whatever it already streams —
  P2 can only make a link richer, never worse.
- **Unconditional by default (no flag of its own) — except the interval chain steps aside for
  Mechanism A, and that exception is a real wire-level constraint, not a policy choice.**
  `MAV_CMD_SET_MESSAGE_INTERVAL`'s `COMMAND_ACK` correlates on `(origin sysid, command id)` only,
  never on which message id was asked for (see the general Gotcha above, written for
  `MavlinkConnectRemediator` but equally true here) — so this negotiator's own interval chain and
  `MavlinkConnectRemediator`'s on-connect chain cannot both hold a live `Correlator.await` for the
  same peer's `(sysid, 511)` key at once; the second registration throws `IllegalStateException`,
  logged as a WARNING "could not be sent" by whichever side loses the race. Discovered this wave via
  `MavlinkConnectRemediationIntegrationTest` going red the moment P2 shipped unconditionally. Fixed
  by having `MavlinkStreamNegotiator`'s constructor read
  `settings.onboarding().requestMessagesOnConnect()` once, into `intervalChainOwnedByMechanismA`, and
  skip its own `negotiateStreams` call entirely when it is `true` — Mechanism A already owns that
  exact job for that peer when an operator has explicitly turned it on. The `AUTOPILOT_VERSION` probe
  is unaffected (a different correlator key, `(sysid, 512)`) and always runs regardless of the flag.
- **The same collision reaches `MavlinkVehicleConfigurator`'s manual, operator-invoked endpoints too
  — a second, broader manifestation the flag-based fix above does not cover, since these calls are
  not gated by any flag.** `probe`'s `AUTOPILOT_VERSION` request and `requestMessageInterval` share
  their correlator keys with `MavlinkStreamNegotiator`'s probe/interval chain respectively, and an
  operator can call either at any time — including moments after a claim, while this negotiator's own
  chain is still in flight. Before this wave's fix, that race produced an instant, spurious
  `IllegalStateException` → `NO_ACK`/no-report the moment two local collaborators wanted the same key,
  which is dishonest: it reads exactly like the aircraft never answered, when in fact the request was
  never sent at all. Caught by `MavlinkVehicleConfiguratorTest.requestsThatAreSentToAReachableAircraftAndGoUnansweredTimeOutIntoNoAck`'s
  own elapsed-time assertion ("must actually have been sent and waited on, not short-circuited"; it
  failed at ~5ms instead of waiting out `ackTimeout`). Fixed with a new private
  `MavlinkVehicleConfigurator.awaitWithContentionRetry` (plus `CORRELATOR_CONTENTION_BACKOFF = 100ms`):
  on an `IllegalStateException`-caused `ExecutionException`, it retries a **fresh** exchange (a new
  send, a new registration — replaying the same failed future would just fail again) after the
  backoff, bounded by the same overall budget the call already computes; any other outcome (a real
  reply, a real timeout, an unrelated fault) is returned exactly as the plain `await` helper already
  would. Only `requestCapabilities`/`requestMessageInterval` use it — `readParams`/`writeParam`
  correlate on `PARAM_VALUE`, a key `MavlinkStreamNegotiator` never touches, so they keep the plain
  `await`.
- **`MavlinkSitlOnConnectIntegrationTest`'s "starved baseline" premise no longer holds, and its proof
  message had to change.** `StreamNegotiation.defaults()` requests `VFR_HUD` (74) unconditionally —
  the same message id the test used to prove Mechanism A's flag caused something. A connection with
  the flag off is no longer starved of `VFR_HUD`; it is starved of nothing P2 already asks for. The
  test now uses `SERVO_OUTPUT_RAW` (36) as its proof message instead — one of Mechanism A's four
  P2-exclusive on-connect ids (`SYS_STATUS`=1, `SERVO_OUTPUT_RAW`=36, `SCALED_IMU2`=116,
  `SYSTEM_TIME`=2), never requested by P2's own default set, so seeing it arrive is still unambiguous
  proof the flag — not P2 — caused it. Re-run against real SITL after the rewrite (docker+image
  present): the flag-off connection's `MavlinkStreamNegotiator` chain got all six messages
  `ACCEPTED` by ArduPilot 4.7.0, and the flag-on connection's own `MavlinkConnectRemediator` chain ran
  cleanly afterward with zero collisions, confirming the `intervalChainOwnedByMechanismA` fix
  end-to-end against real firmware, not just the fake-vehicle unit tests.
- **`MavlinkFlightCommander.capabilities(Device)` is not wired to this probe's `CapabilityReport` this
  wave — a known, deliberate scope boundary, not an oversight.** The on-claim `AUTOPILOT_VERSION`
  probe's result is only logged, never cached or exposed through the existing `capabilities()` port
  method; a future wave that wants a claim-time capability cache to back that method can build one,
  but P2's own brief was the negotiation itself.
- **Production automatically inherits `StreamNegotiation.defaults()` the moment this module is
  rebuilt — no `vision-app` change required, unlike `commandRetries`' still-open gap above.**
  `TelemetryWiring#toMavlinkSettings` builds `MavlinkSettings` via the 8-arg back-compat constructor,
  which this wave updated (like every other back-compat constructor and `withXxx` method on this
  record) to append `StreamNegotiation.defaults()` automatically. Unlike `MavlinkFlightCommander`'s
  `ackTimeout` gap (P1 Gotchas above, still open), there is no separate, stale constructor overload in
  the way here — `MavlinkGateway` always builds its `MavlinkStreamNegotiator` off whatever
  `MavlinkSettings` it is constructed with, so there is nothing left for a future wave to thread
  through before this is live in production; the only future work is making the six ids/rate
  *configurable* (a `VisionMavlinkProperties` field), not making negotiation *happen*.

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

**`docs/plans/active/OPERATOR-UX-4-PLAN.md` N1 (W4) done.** `PositionAndPowerState.applyPosition`
now takes `boolean hasGpsFix` (`FlightStatusState.hasGpsFix()`, the caller — `MavlinkTelemetryDecoder`
— resolves it fresh per `GLOBAL_POSITION_INT`); `lat`/`lon` are recorded only when true, else both
set `null` — see the Gotchas entry above for the full defect/fix/test-impact writeup. 4 new decoder
tests (`noGpsFixSampleHasNoPosition`, `a3dGpsFixSampleHasAPosition`, `a2dGpsFixIsAlsoSufficientForAPosition`,
`gpsFixLostMidSessionDropsThePosition`); 4 pre-existing `MavlinkTelemetryDecoderTest` fixtures that
asserted on a bare `GlobalPositionInt` with no preceding fix now prime one first
(`mapsGlobalPositionIntWithUnitConversions`, `heartbeatEmitsASampleWithoutChangingAnyOtherField`,
`locksOntoTheFirstSystemIdAndIgnoresAllOthers`,
`preExistingPositionBatteryAndHeadingMappingsAreUnchangedAlongsideFlightState`) — the position values
themselves are unchanged, only the fixture setup. `./mvnw -B -pl drone-link/mavlink -am test` —
**240 tests**, all green (2026-08-29).

**`docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md` §11 Z2b done.** `MavlinkTelemetrySource`
gained `holdLobby(int port)`/`releaseLobby(int port)` — the claim-free "standing lobby" that keeps a
gateway's socket bound (and its GCS heartbeat replying) with zero device registrations, so a vehicle
broadcasting to a well-known port (PX4 convention: :14550) locks unicast onto this app before any
operator has added a device for it. Mechanism: an `AtomicBoolean lobbyHeld` + `AtomicReference`-held
`DefaultTxScheduler`/`HeartbeatService` pair directly on `MavlinkGateway`, never a fake `Device` or a
`VehicleClaimPolicy` registration — see the Gotchas section above for the full race-safety and
heal-after-close reasoning. `VehicleClaimPolicy` gained one new query, `isEmpty()`. No new
`MavlinkSettings` knob was needed — the hold reuses `MavlinkGateway`'s existing `coreSettings`
(derived from `MavlinkSettings.closeJoinTimeout()`) and mavlink-core's own 1 Hz `HeartbeatService`
default. New `MavlinkLobbyHoldTest` (9 tests, real UDP loopback, no mocks).
`./mvnw -B -pl drone-link/mavlink -am test` — **249 tests**, all green (2026-08-31).

**`docs/plans/active/MAVLINK-COMMANDS-PLAN.md` P1 done.** Three independent fixes: (1) the force-arm
magic defect — `2989`/`21196` split, previously both paths sent `21196` and an ArduPilot firmware bug
silently converted every forced arm into an unintended safety-check bypass (see the P1 Gotchas above
for the full defect writeup); (2) bounded retry for absolute-state commands — `MavlinkSettings`
gained `commandRetries` (default 2) alongside `ackTimeout` re-scoped to per-attempt (default 700ms),
`MavlinkFlightCommander#send` gated by a frozen-at-call-site `retryable` parameter, all four current
callers eligible; (3) `FlightModes.selectableModes` trims ArduRover's `"Initialising"` (custom_mode
16, a boot transient) from the operator-facing list while `name`/`customModeFor` keep it fully
decodable inbound. New tests: `armSucceedsWhenTheAckOnlyArrivesOnTheSecondAttempt`,
`armReturnsNoAckAfterExhaustingEveryConfiguredAttempt`, `aDeniedAckIsTerminalAndNeverTriggersARetry`
(`MavlinkFlightCommanderTest`); `defaultsCarryTheD2aRetryPolicy`, `commandRetriesRejectsANegativeValue`,
`bothBackCompatConstructorsDefaultCommandRetriesToTheD2aDefault`,
`withAckTimeoutAndWithCommandRetriesReplaceOnlyThatOneField` (`MavlinkSettingsTest`); existing
`armSendsComponentArmDisarmWithForceMagicAndReturnsAccepted` updated to assert `2989.0f`;
`selectableModesForARoverIncludesDockAndCircleButTrimsInitialising` (renamed/extended) asserts
`Initialising` absent from the selectable list while a sibling test still proves `name()` resolves it.
**Known gap, not closed by this wave** (out of its file scope): `vision-app`'s `TelemetryWiring` still
wires `MavlinkFlightCommander` off `VisionMavlinkProperties.ackTimeout()` (2s default) via the 2-arg
back-compat constructor, so production's actual per-attempt wait stays 2s, not 700ms, until a future
wave threads a `commandRetries` field through `VisionMavlinkProperties`/`TelemetryWiring` — see the P1
Gotchas above for the worst-case latency this leaves (~6s, not the ~2.1s D2a intends).
`./mvnw -B -pl drone-link/mavlink -am test` — **256 tests**, all green (2026-09-01).

**`docs/plans/active/MAVLINK-COMMANDS-PLAN.md` P2 done.** New `MavlinkStreamNegotiator`
(package-private): on every claim, one `MAV_CMD_REQUEST_MESSAGE`(512) `AUTOPILOT_VERSION` probe, then
(unless `Onboarding.requestMessagesOnConnect()`/Mechanism A already owns the peer's interval channel)
one `MAV_CMD_SET_MESSAGE_INTERVAL`(511) per new `MavlinkSettings.StreamNegotiation.defaults()` entry —
`ATTITUDE`/`GLOBAL_POSITION_INT`/`VFR_HUD`/`RC_CHANNELS`/`GPS_RAW_INT`/`BATTERY_STATUS` at 250ms each.
No enable flag of its own, by design; every outcome is logged and none of them fail the claim. Hook is
`VehicleClaimPolicy`'s new `IntConsumer onClaimed` constructor param, wired in `MavlinkGateway`'s
constructor as `streamNegotiator::negotiate`, invoked from `assignClaim` exactly once per claim event.
`MavlinkSettings` grew a 13th field/`StreamNegotiation` nested record and a `withStreamNegotiation`
wither; every back-compat constructor and existing wither defaults it, so `vision-app`'s
`TelemetryWiring` (an 8-arg-constructor call site, out of this wave's file scope, unmodified) picks up
`StreamNegotiation.defaults()` automatically the moment this module rebuilds — unlike P1's still-open
`commandRetries`/`ackTimeout` gap, this one is not dormant. Two real, previously-latent production bugs
surfaced and were fixed this wave, both stemming from `MAV_CMD_SET_MESSAGE_INTERVAL`'s `COMMAND_ACK`
correlating on `(sysid, command id)` only, never on message id: (1) `MavlinkStreamNegotiator`'s own
interval chain colliding with `MavlinkConnectRemediator`'s, fixed by having the former step aside
entirely when Mechanism A is active; (2) the same collision reaching `MavlinkVehicleConfigurator`'s
manual `probe`/`requestMessageInterval` endpoints (no flag gates those), fixed by a new
`awaitWithContentionRetry` helper that retries a fresh exchange after a short backoff instead of
surfacing an instant, dishonest "no reply". See the P2 Gotchas above for both in full, including the
real-SITL re-verification of fix (1) and the message-id rewrite fix (1) forced on
`MavlinkSitlOnConnectIntegrationTest` (its "starved baseline" proof message, `VFR_HUD`, is now one of
P2's own six defaults, so it switched to `SERVO_OUTPUT_RAW` — a message id exclusive to Mechanism A's
own on-connect set). New tests: `MavlinkStreamNegotiatorTest` (3, real UDP loopback against a local
`FakeVehicle`); 4 new `MavlinkSettingsTest` cases for `StreamNegotiation`; `MavlinkFlightCommanderTest`
and `MavlinkConnectRemediationIntegrationTest`'s shared `FakeVehicle` test doubles gained an auto-drain
mechanism so P2's now-unconditional negotiation traffic never surfaces as an unexpected `COMMAND_LONG`
to a test asserting on its own, unrelated command.
`./mvnw -B -pl drone-link/mavlink -am test` — **263 tests**, all green, foreground/blocking run
(2026-09-01); `MavlinkSitlOnConnectIntegrationTest` re-run individually against real SITL
(docker+image present) also green, confirming the Mechanism A step-aside end-to-end against real
ArduPilot 4.7.0, not just fake-vehicle unit tests.
