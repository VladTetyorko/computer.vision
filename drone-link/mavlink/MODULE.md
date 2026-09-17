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

## Section index

| Topic | Heading |
|---|---|
| RX telemetry source, gateway, claim/re-election policy (`MavlinkTelemetrySource`, `MavlinkGateway`, `VehicleClaimPolicy`, `VehicleRegistration`) | API surface |
| Claim-free standing lobby (`holdLobby`/`releaseLobby`/`isLobbyHeld`) | API surface (also Gotchas) |
| Intake/link diagnostics (`MavlinkIntakeStatus`, `MavlinkMessageInventory`) | API surface |
| On-connect/on-claim stream negotiation (`MavlinkConnectRemediator`, `MavlinkStreamNegotiator`) | API surface (also Conventions § Command TX gating, Gotchas) |
| Guarded command TX (`MavlinkFlightCommander`) | API surface |
| RC-override relay (`MavlinkManualControlSender`) | API surface |
| Heartbeat discovery (`MavlinkHeartbeatScanner`) | API surface |
| TX flight-plan simulator (`MavlinkFeedTransmitter`, `MavlinkRoute`, `SimulatedVehicleMessages`) | API surface |
| Onboarding probe/remediate/configure (`MavlinkVehicleConfigurator`) | API surface |
| Link health self-report (`MavlinkLinkStatusProvider`) | API surface |
| Telemetry decoding, attitude/gimbal decode, firmware mode tables (`MavlinkTelemetryDecoder`, `AttitudeState`, `QuaternionEuler`, `FlightModes`) | API surface |
| All tunables (`MavlinkSettings` + nested records) | API surface |
| MAVLink message → domain field mapping | Message → field mapping |
| Layering vs `mavlink-core`; command-TX gating policy | Conventions |
| Force-arm magic, `COMMAND_ACK` correlation limits, GPS-fix gating, lobby-hold races, supports()-honesty landmines, firmware mode-table quirks | Gotchas |
| What's real, RX-only vs TX-capable, simulator-only, flag-gated | Status |
| Wave-by-wave narrative, dates, test counts | [`MODULE-HISTORY.md`](MODULE-HISTORY.md) |

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
  lobby (see its own Gotchas entry below). **(SOURCE-ONBOARDING-2 A2, new)** `public MavlinkIntakeStatus
  intakeStatus(int port)` — the P1/P2 diagnostic read (own Gotchas entry below); always resolves
  against `DEFAULT_BIND_HOST`, so it takes a bare port, mirroring `holdLobby`/`releaseLobby`; throws
  `IllegalArgumentException` outside `[1,65535]`; never throws for a port nothing has ever bound —
  returns `MavlinkIntakeStatus.unbound(bindAddress)` instead. Package-private: `static bindKey(host, port)`, `bindKeyFor(Device)`,
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
  per vehicle rather than returning one undifferentiated list). **(SOURCE-ONBOARDING-2 A2, new)**
  `MavlinkIntakeStatus intakeStatus(String bindAddress)` (package-private) — composes `link instanceof
  UdpListenLink listen ? listen.intake() : NO_INTAKE` (mavlink-core's pre-parse `LinkIntake` — all-zero
  for the `MavlinkLink`-only test-seam constructor, since a hand-built double has no socket to count)
  with this gateway's own `framesDecoded` counter and the current unclaimed/claimed sysid lists. A
  private `AtomicLong framesDecoded` is incremented as the very first statement of `onFrame(MavFrame)`
  — every frame `mavlink-core` has already resynced/decoded and dispatched, regardless of whether
  `VehicleClaimPolicy` finds a claiming registration; this is what makes `intakeStatus` able to answer
  P2 ("bytes arrive, nothing decodes") independent of claim status. `close()` (package-private —
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
- `public record MavlinkIntakeStatus(boolean bound, String bindAddress, boolean lobbyHeld, long
  datagramsReceived, long bytesReceived, Instant lastDatagramAt, long framesDecoded, List<Integer>
  unclaimedSysids, List<Integer> claimedSysids)` — **(SOURCE-ONBOARDING-2 A2/C2, new)** the P1/P2
  diagnostic snapshot `MavlinkTelemetrySource.intakeStatus(int)` returns; field names match `GET
  /api/discovery/status`'s future `telemetryIntake` shape (C2) field-for-field so that wave's DTO
  mapping is trivial. Compact-constructor validated (`bindAddress` non-blank, the three counters
  `>= 0`, both sysid lists defensively `List.copyOf`'d). `static unbound(String bindAddress)` — the
  honest answer for a bind address nothing has ever opened or held: `bound=false`, every other field
  zeroed/empty, not merely a zeroed status with `bound=true`.
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
  `Onboarding.requestMessagesOnConnect()` — see its own class javadoc and the Gotchas below on
  `COMMAND_ACK` correlation).
  `(FrameSink sink, Correlator correlator, MavlinkSettings settings)`, `void negotiate(int sysid)` —
  the one method, handed to `VehicleClaimPolicy` as its `onClaimed` hook. Sends one
  `MAV_CMD_REQUEST_MESSAGE`(512) for `AUTOPILOT_VERSION` (via a fresh `CapabilityService`), then,
  unless `settings.onboarding().requestMessagesOnConnect()` is `true`, one
  `MAV_CMD_SET_MESSAGE_INTERVAL`(511) per `settings.streamNegotiation().streams()` entry, strictly
  sequential via `CompletableFuture.thenCompose` (never blocks the calling — session reader — thread).
  Every outcome is logged and none of them fail the claim: `UNSUPPORTED`/`DENIED`/`NO_ACK` → INFO;
  a send-level fault (e.g. no link registered) → WARNING. Deliberately does **not** wire its probe's
  `CapabilityReport` into `MavlinkFlightCommander.capabilities(Device)` — a known, documented
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
  See the Gotchas below for the force-arm magic split (`2989`/`21196`). `static final int
  TARGET_COMPONENT_AUTOPILOT = 1`. Only ArduPilot/INAV (`autopilot`
  ARDUPILOTMEGA) is commandable; Betaflight (`autopilot` GENERIC) is rejected before `FlightModes` is
  even consulted, even though its own table has an RTL-named mode. **(ASSET-FLOWS-PLAN C5, new)**
  `supports(Device)` is now firmware-honest, not just protocol-honest: `false` for a device whose
  most-recently-heard firmware is a *known* non-commandable one (Betaflight/any non-ArduPilot label
  today), `true` for protocol-match-plus-never-heard exactly as before — see the Gotchas below and
  this class's own "supports(Device) is honest about firmware" javadoc section for the full
  before/after and why "never heard" deliberately stays `true`. **`emergencyStop` is
  vehicle-kind-gated (FLEET-RADIO R4b)** — see its own Gotchas entry below; it is no longer a
  single, uniform command for every device. Constructors: `(MavlinkTelemetrySource)` and
  `(MavlinkTelemetrySource, Duration ackTimeout)` are back-compat overloads;
  `(MavlinkTelemetrySource, MavlinkSettings)` is canonical — and since MAVLINK-COMMANDS-PLAN P4,
  `vision-app`'s `TelemetryWiring#mavlinkFlightCommander` calls the canonical one (700ms×3 in
  production; see `MODULE-HISTORY.md`'s MAVLINK-COMMANDS-PLAN P1 entry for the production-gap
  defect this closed).
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
  **(ASSET-FLOWS-PLAN C5)** `supports(Device)` is deliberately left protocol-only (unlike
  `MavlinkFlightCommander`'s new firmware-aware version) — see the Gotchas below for why
  `RC_CHANNELS_OVERRIDE` is not a Betaflight false positive the way `MAV_CMD_DO_SET_MODE`/
  `MAV_CMD_COMPONENT_ARM_DISARM` are.
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
  **(SOURCE-ONBOARDING-2 A2, U8, new)** `@Override public SourceStatus lastStatus()` — a `volatile
  SourceStatus`, defaulting to `OK`. The hub-borrow path performs no socket I/O of its own (an active
  hub is reachable by definition), so it always sets `OK`. The self-bind path sets `UNREACHABLE` only
  on a genuine bind-conflict `IOException` (the scanner's own I/O actually failed) and `OK` on a
  successful bind, **before** the read loop — so an empty result from a successful bind (nothing
  transmitting) reads `OK`, distinct from a bind conflict, exactly mirroring `MediamtxPathScanner`'s
  established `SourceStatus` idiom in `device-discovery/onvif-mdns-v4l2` (own Gotchas entry below).
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
  here still uses — see the Gotchas below on `COMMAND_ACK` correlation for why (they share a
  correlator key with `MavlinkStreamNegotiator`, which fires on every claim). **(ASSET-FLOWS-PLAN C5)**
  `supports(Device)` also stays protocol-only, deliberately — see the Gotchas below for why
  onboarding probing has no firmware-specific verb to be dishonest about the way flight commands do.
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
  `NEVER_IDENTIFIED`. Consumed by `MavlinkManualControlSender`'s `AdapterLink` above, never by
  `vehicleKind` itself — the two methods answer different questions from the same classification.
  **FLEET-RADIO R1:**
  the vehicle-family switch (which used to be this class's own `Set<Integer>`s per family) now
  delegates entirely to `mavlink-core`'s `VehicleClass` — `vehicleKind` and the ArduPilot-table
  selector (`tableFor`) both switch on `VehicleClass.of(mavType)`, so this class carries no
  `MAV_TYPE` literal any more. `ARDUPILOT_ROVER` is now complete: `8` Dock, `9` Circle and `16`
  Initialising were added (verified against ArduPilot's `Rover/mode.h` for the `stable-4.7.0`
  generation this project's own SITL image pins — see the Gotchas below for why `Dock`
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
  `StreamNegotiation.defaults()` automatically the moment this module is rebuilt — the one loop this
  back-compat design left open (production `ackTimeout`) was closed by P4 (`vision-app`'s
  `TelemetryWiring` now uses the canonical constructor — see `MODULE-HISTORY.md`'s
  MAVLINK-COMMANDS-PLAN P1 entry for the defect history). Nested:
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
    it. Deliberately no enable flag (contrast `Onboarding.requestMessagesOnConnect()`) — see the
    Gotchas below on `COMMAND_ACK` correlation. `static defaults()` → six cockpit messages at 250ms/4Hz each: `ATTITUDE`(30),
    `GLOBAL_POSITION_INT`(33), `VFR_HUD`(74), `RC_CHANNELS`(65), `GPS_RAW_INT`(24, gates
    `GLOBAL_POSITION_INT`'s lat/lon trustworthiness per the GPS-fix Gotcha below),
    `BATTERY_STATUS`(147). No `VisionMavlinkProperties` field exposes this yet — unlike
    `commandRetries`' now-closed wiring gap, this is not dormant: `MavlinkGateway` always builds its
    `MavlinkStreamNegotiator` off whatever `MavlinkSettings` it receives, so `StreamNegotiation.defaults()`
    already applies in production; the six ids/rate are simply not yet exposed as a
    runtime-configurable property.
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

### Levels

This module is the **L5 translation layer** (vision-domain types ⇄ `drone-link/mavlink-core`'s
L3/L4 services) plus project policy a reusable library must not know: vehicle claim/re-election and
firmware mode-name tables. Sockets, framing, resync, peer tracking, correlation, dispatch, and the
send/await machinery (`CommandService`, `ManualControlService`, `ParameterService`,
`CapabilityService`, `MessageIntervalService`) all live in `drone-link/mavlink-core` — see that
module's own MODULE.md for their surface. No class here constructs an
`io.dronefleet.mavlink.MavlinkConnection` directly.

### Command TX gating

`MavlinkFlightCommander`/`MavlinkManualControlSender` send whenever their port method is called —
this adapter enforces reachability only ("you cannot command what you cannot hear"), not
authorization; who is allowed to call them is enforced above this module. Two send paths fire with
no explicit per-call request. `MavlinkConnectRemediator`'s on-connect Mechanism A is
**default-off**: `MavlinkSettings.Onboarding.requestMessagesOnConnect()` defaults `false`, and
`MavlinkGateway` constructs no remediator at all when it's false — structurally "cannot send", not
merely "chose not to" (docs/plans/active/MAVLINK-COMMANDS-PLAN.md P2). `MavlinkStreamNegotiator`, by
contrast, is **always constructed and always fires**, on every claim — one `MAV_CMD_REQUEST_MESSAGE`
probe plus (unless Mechanism A already owns the peer's interval channel) a
`MAV_CMD_SET_MESSAGE_INTERVAL` chain for six cockpit messages. This has no flag of its own,
deliberately (see the Gotchas below on `COMMAND_ACK` correlation, and `MavlinkStreamNegotiator`'s own
class javadoc, for why) — every real GCS does this at connect, so there is nothing to structurally
prevent here the way Mechanism A's opt-in remediation is prevented.

### Implementation idioms

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
  `mavlink-core`'s own contract). Proven end-to-end by `MavlinkGatewayLinkFailureTest` (real
  `MavlinkGateway` + a hand-built failing `MavlinkLink`, injected via the
  `MavlinkGateway(MavlinkLink, MavlinkSettings)` test seam).
- **An ordinary `unregister()`/gateway `close()` never calls `publisher.close()` (`onComplete`) on the
  departing device's `SubmissionPublisher` — only a genuine link failure raises `onError` (above); a
  normal teardown raises neither.** A caller distinguishing "device removed cleanly" from "link died"
  must not wait on `onComplete` to tell them — it never arrives on either path today.
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
  calling thread. This is not just an intra-class concern — `MavlinkStreamNegotiator`'s own interval
  chain (fired on every claim), `MavlinkConnectRemediator`'s on-connect chain, and
  `MavlinkVehicleConfigurator`'s manual `requestMessageInterval`/`probe` calls all share this same
  keying scheme for the same peer, so any two of them running close together for one vehicle can
  collide on it. Two current mitigations: `MavlinkStreamNegotiator` reads
  `Onboarding.requestMessagesOnConnect()` once at construction and skips its own interval chain
  entirely when Mechanism A (`MavlinkConnectRemediator`) already owns that peer's channel — the
  reason `MavlinkStreamNegotiator` has **no enable flag of its own**: every real GCS negotiates
  streams at connect, so there is nothing to gate the way Mechanism A's opt-in remediation is gated,
  and every send it makes is async, so an unresponsive vehicle can only make a link richer, never
  fail a claim; `MavlinkVehicleConfigurator.requestCapabilities`/`requestMessageInterval` instead
  retry a **fresh** exchange (new send, new registration — replaying a failed future just fails
  again) after a short backoff (`CORRELATOR_CONTENTION_BACKOFF`) whenever the collision itself, not
  the vehicle, produced the `IllegalStateException` — so an operator-invoked probe never reports a
  local collision as a false "vehicle never answered." `readParams`/`writeParam` correlate on
  `PARAM_VALUE`, a key neither negotiator ever touches, so they need no such retry.
- **What "reconnected" means for `MavlinkConnectRemediator`'s idempotency is a judgement call, not
  a protocol fact.** MAVLink has no boot counter and no session identifier, so there is no wire-level
  way to distinguish "same aircraft, radio blipped" from "fresh boot, back to starved defaults". A
  system id is treated as newly learned again once it has gone unheard longer than
  `MavlinkSettings.silenceWindow()` — reusing `VehicleClaimPolicy`'s own threshold. A 31-second radio
  dropout gets re-remediated for free (cheap — ArduPilot just re-confirms a rate it already honours);
  the alternative risks silently leaving a rebooted aircraft in the starved state this mechanism
  exists to fix.
- **(SOURCE-ONBOARDING-2 A2, U8) `MavlinkHeartbeatScanner.lastStatus()` distinguishes "down" from
  "empty".** Before this wave, `DeviceDiscoveryPort#lastStatus()`'s inherited default (`OK`,
  unconditionally) meant a genuine self-bind conflict on this scanner's port read identically to
  "nothing is transmitting right now" — both an empty `scan()` result with no other machine-readable
  signal. Fixed the same way `MediamtxPathScanner` (`device-discovery/onvif-mdns-v4l2`) already fixed
  the identical gap for its own scanner: a `volatile SourceStatus lastStatus` field, `UNREACHABLE` set
  only in `scanBySelfBinding`'s bind-conflict `catch (IOException e)`, `OK` set right after a
  successful bind and unconditionally in `scanActiveHub` (which performs no I/O of its own — an
  active hub is reachable by definition). Self-healing: the very next successful scan after a
  transient bind conflict flips the status back to `OK`, never sticky.
- **This adapter keeps no table of RC aux functions (`MavlinkFlightCommander.auxFunction`), on
  purpose.** A stale copy of the firmware's own `RCx_OPTION` list is worse than none — what a
  function number does is the vehicle's business, and whether it acted shows up as the ack.
- **ArduRover's `"Initialising"` (custom_mode 16, with an "s") and ArduPilot Plane's
  `"Initializing"` (with a "z") are deliberately spelled differently in `FlightModes`** — different
  firmware source trees, each verified against its own upstream, not normalized to agree with each
  other. `selectableModes` excludes Rover's `"Initialising"` from the operator-facing list (a boot
  transient nothing should ever be commanded into); `name`/`customModeFor` still resolve it both ways,
  so it stays fully decodable inbound.
- **`Dock` (ArduRover custom_mode 8) is compiled in behind `#if MODE_DOCK_ENABLED` on real firmware,
  so it is absent from some builds — this only matters for `customModeFor`'s *reverse* lookup (name →
  number, used to command a mode), never the forward lookup (number → name, used to display one).**
  `customModeFor` still resolves `"Dock"` → `8` on every build, including ones without it compiled in,
  because there is no live per-vehicle capability signal to gate an optional compiled-in mode on, and
  hiding the entry would make Dock permanently uncommandable even on builds that do have it. The
  safety net is one layer up: `MavlinkFlightCommander.send` already throws for any non-`ACCEPTED`
  `COMMAND_ACK`, so a build that honestly rejects the mode change surfaces that rejection normally.
  The one gap this cannot close — a build that ACKs `ACCEPTED` for a mode change it does not actually
  honor — is a firmware-honesty problem, not something a client-side mode-name table can fix.
- **`MAV_CMD_COMPONENT_ARM_DISARM`'s param2 force-magic is direction-specific:
  `ARM_FORCE_MAGIC = 2989f` to force-arm, `DISARM_FORCE_MAGIC = 21196f` to force-disarm — sending the
  wrong one is not a no-op.** ArduPilot does not reject an unrecognized magic; it silently *bypasses
  every pre-arm check* instead of rejecting the malformed request. Sending `21196` on the arm path
  (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2b, verified against ArduPilot's own arming docs and
  issues #32996/#26521) still "works" but produces an accidental safety-check bypass, not the
  deliberate "I know what I'm doing, force it" the `force=true` flag is supposed to mean. The
  forced-disarm value (`21196`, `emergencyStop`'s unconditional forced disarm) is the same either way
  — only the arm path is direction-sensitive.
- **`emergencyStop` is byte-identical to `disarm(device, true)` on the wire — but only for `COPTER`,
  `PLANE` and `UNKNOWN`.** For `ROVER` it is a `MAV_CMD_DO_SET_MODE` into ArduRover's `Hold`
  (custom_mode 4), never a disarm: a ground rover or surface boat does not fall when disarmed, it
  *coasts* with its steering dead — actively wrong for a panic stop — while `Hold` actively brakes and
  holds against a slope while keeping steering authority alive. No disarm follows a successful
  `Hold`, by decision, not omission: a rover's active brake in `Hold` typically depends on the motor
  controller staying armed to apply reverse/holding torque, so disarming immediately after would
  release the very brake the stop just applied. `VehicleKind.UNKNOWN` stays on the forced-disarm path
  deliberately — it has no rover-shaped mode table to resolve `"Hold"` against, and a forced disarm is
  universal across every ArduPilot vehicle kind, so it is the one stop command guaranteed to reach the
  aircraft and produce a real, reportable outcome for a machine that never said what it is. An
  operator who wants the vehicle fully powered down once stationary issues a separate, deliberate
  `disarm` — never bundled into the panic-stop path. Kept as a separate method purely so the log line
  and audit trail record which of the (now two, per kind) underlying wire commands an operator
  actually triggered.
- **`MavlinkFlightCommander`'s internal reachability guard (`resolveReachableTarget`, called before
  `requireCommandableFirmware` on every command, directly or via `resolveCustomMode`) must check
  `telemetrySource.supports(device)` directly — never this class's own `supports(Device)`.** The two
  methods answer different questions since `supports(Device)` became firmware-aware
  (ASSET-FLOWS-PLAN C5): routing the internal guard through `this.supports(device)` would make a
  claimed Betaflight device fail the generic reachability check first, silently replacing
  `requireCommandableFirmware`'s specific, already-tested rejection messages (naming Betaflight;
  "not commandable in DRONE-INFRA I-e — ArduPilot/INAV only") with a strictly worse generic
  "`MavlinkFlightCommander` does not support device: …" — exactly the kind of
  two-call-sites-of-the-same-method landmine a firmware-aware `supports()` creates.
- **`MavlinkFlightCommander.supports(Device)` deliberately stays `true` for a device whose firmware
  has never been heard — the more honest of the two choices, not the less.** `resolveReachableTarget`
  already throws a specific "no MAVLink vehicle has ever been heard for device … you cannot command
  what you cannot hear" message for that exact case, and `capabilities()` already answers
  `notCommandable()` for it too — both through their own, more specific mechanism. Flipping
  `supports()`'s never-heard case to `false` would not make either of those more honest; it would only
  make `contexts/vision-flight`'s `DefaultFlightCommandService.firstCommandableDevice` silently skip
  the device instead of surfacing that specific, useful message.
- **Betaflight is not a false positive for `RC_CHANNELS_OVERRIDE` the way it is for
  `MAV_CMD_DO_SET_MODE`/`MAV_CMD_COMPONENT_ARM_DISARM`.** Betaflight's own `rx/mavlink.c` has accepted
  `RC_CHANNELS_OVERRIDE` (#70) — the same wire message `MavlinkManualControlSender` sends — since
  ~2025.12.0-beta, but its RC link genuinely never processes `DO_SET_MODE`/`ARM_DISARM` at any
  firmware version. Only `HEARTBEAT.autopilot`'s coarse ardupilot/generic/px4 label is decoded — no
  firmware *version* — so there is no fact this module can check that would make gating
  `ManualControlPort.supports()` on "generic" honest; doing so would trade today's real false positive
  for a new false negative (a modern Betaflight build genuinely honoring RC override reported as
  unsupported). `MavlinkManualControlSender`/`MavlinkVehicleConfigurator` are therefore left
  protocol-only, deliberately — `MavlinkVehicleConfigurator.probe`/`readParams`/`writeParam` have no
  equivalent firmware-verb question at all, since probing is protocol-level by design (best-effort,
  never throws for an incomplete answer; `VehicleProfile.complete()`/`incompleteReason()` already
  carry the honesty this port needs).
- **(SOURCE-ONBOARDING-2 A2) `intakeStatus`'s two counters answer different questions, and only
  comparing them is diagnostic.** `datagramsReceived`/`bytesReceived`/`lastDatagramAt` are counted
  pre-parse, at the socket (`mavlink-core`'s `LinkIntake`, deliberately dumb — see that record's own
  javadoc/Gotchas in `drone-link/mavlink-core`'s MODULE.md); `framesDecoded` is counted one layer up,
  in `MavlinkGateway.onFrame`, only once `mavlink-core` has actually resynced and decoded a frame.
  Zero datagrams means nothing is reaching the socket at all (P1: wrong network/port/firewall);
  datagrams arriving with zero frames decoded means something is reaching the port that is not a
  valid MAVLink 2 frame (P2: wrong protocol, MAVLink 1, garbage) — neither counter alone can tell
  those apart, which is why `MavlinkIntakeStatus` carries both rather than one derived verdict.
- **`intakeStatus` only ever resolves against `DEFAULT_BIND_HOST` ("0.0.0.0").** A test that opens a
  device or holds a lobby against a bare `127.0.0.1` URI (the older convention some pre-A2 tests use)
  is invisible to `intakeStatus(port)` — it looks up `gateways.get(bindKey(DEFAULT_BIND_HOST, port))`
  only, never a loopback-specific key. `MavlinkIntakeStatusTest` uses `holdLobby(port)` (always
  wildcard) for exactly this reason, matching `MavlinkLobbyHoldTest`'s own convention.
- **A hand-built `MavlinkLink` test double reports an honestly all-zero `LinkIntake`, never a fabricated
  one.** `MavlinkGateway`'s `(MavlinkLink, MavlinkSettings)` test-seam constructor (FLEET-RADIO R4)
  accepts any `MavlinkLink`, not just the production `UdpListenLink`; `intakeStatus`'s `instanceof
  UdpListenLink` pattern match falls back to a shared `NO_INTAKE` constant rather than throwing or
  guessing, so a `FailingLink`-style test double never crashes a status read, it just correctly has
  nothing pre-parse to report.
- **The claim-free "standing lobby" (`MavlinkTelemetrySource.holdLobby`/`releaseLobby`,
  `MavlinkGateway.holdLobby`/`releaseLobby`/`isLobbyHeld`) is a separate `AtomicBoolean lobbyHeld` on
  `MavlinkGateway` — never a fake `Device` or a `VehicleClaimPolicy` registration.** A lobby hold that
  registered itself as a device would steal an unpinned claim from whichever real device is meant to
  own that vehicle. `unregister()`'s self-close condition is `empty && !lobbyHeld.get()`;
  `releaseLobby()`'s is the mirror (clear the flag, then close only if `claimPolicy.isEmpty()`) — the
  two race on the same gateway with no shared lock beyond `VehicleClaimPolicy`'s own monitor (for
  `remove`/`isEmpty`) and the flag's own CAS, and neither ordering can leak the gateway open forever or
  double-close it. A genuine link failure closes the gateway unconditionally, lobby held or not —
  `close()` always tears down the lobby's own heartbeat scheduler first (idempotent), before evicting
  the gateway; `lobbyHeld` itself is not cleared by `close()`, harmless since a closed gateway is never
  reused. Healing after close reuses `open(Device)`'s existing replace-when-closed `compute` logic — a
  lobby hold on a port whose gateway has already self-closed gets a fresh, working gateway on the very
  next `holdLobby` call. The lobby owns its own GCS heartbeat TX (a `DefaultTxScheduler`+
  `HeartbeatService` pair built off the gateway's own session, so identity is GCS 255/190 for free),
  started on hold and stopped on release/close through one `AtomicReference`-held scheduler so a
  concurrent close and release can't both tear it down or leak it. `HeartbeatService` only replies to a
  link's *last-learned* peer (accepted, not fixed) — with several vehicles announcing on one held port
  the reply rotates between them, so every vehicle transmitting at its own ≥1 Hz PX4-convention rate
  still gets its lock-on within a few seconds. `MavlinkHeartbeatScanner` needed no code change for
  this: its existing `hasActiveHub(bindKey)` check already steers `scan()` onto the hub-borrow path
  whenever *any* gateway is registered at that bind key, lobby-held or device-backed alike.

## Status

Real and load-bearing: RX ingest + fleet-gateway claim/re-election, guarded command TX (mode/arm/
disarm/emergency-stop/aux-function), the persistent RC-override relay, heartbeat discovery, the
onboarding probe/remediate/configure trio (`MavlinkVehicleConfigurator` + `MavlinkConnectRemediator`),
system-status health reporting, and the TX flight-plan simulator. Command TX is gated by reachability
only, not authorization (see Conventions § Command TX gating); `MavlinkConnectRemediator`'s on-connect
Mechanism A is flag-gated (`vision.onboarding.request-messages-on-connect`, default off) and not
constructed at all when off, while `MavlinkStreamNegotiator` has no flag and always fires on claim.
Out of scope, deliberately: `RANGEFINDER`→`aglMeters` fusion, DEM/terrain intersection, camera
intrinsics (consumed by `contexts/vision-map`, not this module), and PX4 mode-name tables. Try it
against real ArduPilot SITL: register a `mavlink` telemetry device with `uri = udp://0.0.0.0:14550`
and `sim_vehicle.py -v ArduCopter --out=udp:127.0.0.1:14550`.

Wave-by-wave history: [`MODULE-HISTORY.md`](MODULE-HISTORY.md).
