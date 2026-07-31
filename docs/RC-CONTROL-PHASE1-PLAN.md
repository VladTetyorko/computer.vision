# RC-CONTROL-PHASE1-PLAN — SITL relay (transmitter → backend → ArduPilot SITL)

Status: **draft for review** (2026-07-31). Owner: RC relay. Freezes Phase 1 of
[`RC-CONTROL-PLAN.md`](RC-CONTROL-PLAN.md) into an implementable contract. **SITL ONLY** — no real
airframe. Builds directly on the shipped Phase 0 (`core/rc/**`, `features/fly/rc-monitor.ts`).

Relay a plugged-in RC transmitter's channels from the Fly cockpit, through the backend, to an
ArduPilot SITL instance over MAVLink `RC_CHANNELS_OVERRIDE` (#70) at a fixed rate, with an
input-loss **watchdog/deadman** that auto-releases control. Every relay session is authenticated,
capability-gated, engaged by an explicit operator gesture, and audited.

> **The watchdog is the feature.** Latency is a safety property. This plan mandates a
> "measure glass-to-stick latency" step and a watchdog-fires drill in SITL before anything else.

## Goal, in the operator's terms

> "With my RadioMaster plugged in as a USB joystick, let me press **Take control**, fly the SITL
> copter with the sticks, watch the round-trip latency, and know that the instant I let go, close
> the panel, hide the tab, or the input stalls, the drone stops taking my input — automatically."

Made precise:
- **Take control** is an explicit, capability-gated gesture (modelled on `flight-command-panel`), not
  a passive monitor. It opens **one** relay session on **one** WebSocket connection.
- The browser streams stick/switch samples at whatever rate the Gamepad API delivers (~20–30 Hz);
  the **backend adapter** re-sends the *latest* channels to SITL at a fixed, env-tunable rate
  (~33 Hz default) regardless of browser jitter — a latest-wins mailbox decouples the two.
- An input gap longer than the watchdog timeout (~300 ms default), an explicit **RELEASE**, a socket
  drop, a panel close, or a hidden tab all **release** the channels (send RC release sentinels, then
  stop) so the FC's own failsafe takes over. Release is audited.
- The operator sees the live glass-to-stick round-trip (server `ack` echoes `seq`+`tSent`).

## The hardware & SITL fact that scopes everything (recap from Phase 0)

EdgeTX "USB Joystick (HID)" mode and the radio's RF module are **mutually exclusive** — as a
joystick the radio is *not* transmitting RF, so the platform is the **sole** control path (Topology
A, forced by hardware). On a real airframe with an RF receiver this races the FC's RC-failsafe; **in
SITL there is no receiver, no RF, no failsafe race** — `radio-as-joystick → RC_CHANNELS_OVERRIDE →
ArduPilot SITL` is a complete, honest loop with zero airframe risk. That is why **Phase 1 is SITL
only** and Phase 2 (real vehicle) stays gated on explicit user go (see Safety & non-goals).

## Current state (honest, grounded)

| Layer | Today | Gap this plan closes |
|---|---|---|
| adapter-mavlink **send** | `MavlinkFlightCommander implements FlightCommandPort` — **request→ack one-shots** (arm/mode/RTL); GCS sysid/comp **255/190** → autopilot comp **1**; a fresh `MavlinkUdpOutputStream` per send wrapping the hub's shared socket; "cannot command what you cannot hear" gate in `resolveReachableTarget` (`MavlinkFlightCommander.java` L186-268) | a **new streaming send seam** — a persistent, fixed-rate, ack-less `RC_CHANNELS_OVERRIDE` sender on the shared hub socket |
| adapter-mavlink **fixed-rate loop** | `MavlinkFeedTransmitter.FeedRuntime` — daemon thread `mavlink-feed-<id>`, 50 ms tick, CAS/interrupt/bounded-join close (`MavlinkFeedTransmitter.java` L233-418) — but it sends on its **own ephemeral socket** | mirror the `FeedRuntime` lifecycle, but send on the **hub's shared socket** (`MavlinkSocketHub#socket()`) targeting the vehicle's live `sourceAddress` |
| MAVLink library | `io.dronefleet.mavlink:mavlink:1.1.11`; `RcChannelsOverride` (#70) present with `builder().targetSystem().targetComponent().chan1Raw()…chan18Raw().build()`; `ManualControl` (#69) also present. RX side already decodes `RcChannels`/`RcChannelsRaw` for RSSI | build & send `RcChannelsOverride` #70 (the complete 12+-channel primitive; #69 is stick-only, not used) |
| domain ports | 26 out-ports in `com.drones.vision.domain.port.out`; `FlightCommandPort` is the command-TX analog | new `ManualControlPort` (out) + `RcChannels`/`ControlBinding`/`ChannelMap` value model |
| application command flow | `FlightCommandService`/`DefaultFlightCommandService` — threads `(AssetId, …, UserId actor, VisibilityScope scope)`; deps `AssetService`+`FlightCommandPort`+`AuditTrailPort`; `resolveForCommand`/`firstCommandableDevice` gate; scope → `AccessDeniedException` (403) + audit `DENIED`; audit via `AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET, …)` — **stateless, no watchdog** | new `ManualControlService` — **stateful session** + deterministic watchdog (injected `Clock`+`ScheduledExecutorService`), same gate/audit idiom |
| watchdog / scheduler precedent | **No `@Scheduled`, no rate-limiter, no watchdog** in main code. Nearest structural precedent: `SupervisedPublisher` (injected `ScheduledExecutorService`, `AtomicBoolean stopped`, `volatile ScheduledFuture`, 3-arg prod ctor + wider test-seam ctor); daemon-thread factory idiom (`UsageTracker` L108-112, `"telemetry-supervisor"`) | build the watchdog fresh on that shape; inject clock+scheduler for determinism |
| transport (**the blocker**) | Spring Boot **servlet MVC** (`spring-boot-starter-web`, vision-api only); **NO WebFlux, NO `spring-boot-starter-websocket`** anywhere; no `WebSocketHandler`/`WebSocketConfigurer`. Server→client push is SSE only (`LiveController` `SseEmitter` + `LiveConnection`); browser→backend is one-shot `HttpClient` (`vision-api.ts`); **no streaming upload / WebSocket / EventSource-out client** exists | add `spring-boot-starter-websocket`; a raw `WebSocketHandler` at `/ws/manual-control`; a browser WS client — the inbound stream leg is net-new |
| auth on the wire | Same-origin **session cookie** (no bearer, no `withCredentials`). `CurrentUser` (singleton bean) → `PrincipalResolver`; `DevPrincipalResolver` (unbounded dev principal) when `vision.auth.enabled=false`, `SecurityContextPrincipalResolver` (reads `SecurityContextHolder`) when `true`. `SecurityConfig`: permitAll chain vs secured chain (`/api/**` `authenticated()`) | WS handshake rides the same session cookie; a `HandshakeInterceptor` resolves `UserId`+`VisibilityScope` via the **same** `CurrentUser` bean; `/ws/**` added to the secured chain matcher |
| SITL infra | `infra/sitl/up.sh N` runs N ArduCopter SITL (4.7.0), each pushes MAVLink2 UDP to the host (14550); the platform **listens**. Register a device via `POST /api/devices {protocol:"mavlink", uri:"udp://0.0.0.0:14550", …}`, then target that asset — the same `assetId` the flight-command endpoints use | the relay targets that same `assetId`; no new SITL wiring |
| web Phase 0 | `RcInputService` (signals `connected`/`device`/`axes`/`buttons`/`updateRateHz`, rAF `getGamepads()` poll that the browser pauses when the tab is hidden), pure `rc-input-logic.ts`, `RcMonitor` drawer (`ToolRailPanelId` already includes `'rc'`), `flight-command-panel` (capability-gated audited command drawer) | add a **Take control** engage + WS client + deadman + RELEASE + latency + channel-map display, reusing the Phase-0 input service |

---

## Frozen wire & type contracts

Everything below is frozen. All waves code against it and may parallelize. Names, JSON shapes, status
codes, MAVLink field mapping, and default channel-map data are pinned exactly.

### 1. Domain — `ManualControlPort` (out) + value model  (`vision-domain`)

Package `com.drones.vision.domain.port.out` (ports) and `com.drones.vision.domain.model` (values).
Framework-free; validation in compact constructors with manual `if (…) throw new
IllegalArgumentException(…)` (domain idiom).

```java
package com.drones.vision.domain.port.out;

/** Streaming, ack-less RC-override relay. Fire-and-forget, opposite shape to FlightCommandPort. */
public interface ManualControlPort {
    /** True for a device this adapter can drive with RC override (mavlink + reachable). */
    boolean supports(Device device);

    /** Open a relay link and START the fixed-rate sender thread.
     *  @throws IllegalArgumentException if the device is unsupported OR not currently reachable
     *          ("you cannot command what you cannot hear" — no live source address). */
    ManualControlLink engage(Device device);

    /** Latest-wins: hand the newest channels to the sender's single-slot mailbox. Non-blocking;
     *  MUST NOT itself send on the socket — the link's own thread owns the wire. No-op after release. */
    void send(ManualControlLink link, RcChannels channels);

    /** Release: send RC release-sentinel frames (a short burst) then stop the sender thread. Idempotent. */
    void release(ManualControlLink link);
}

/** Opaque adapter-owned relay handle. Domain declares it; the adapter implements it. */
public interface ManualControlLink {
    boolean active();
}
```

```java
package com.drones.vision.domain.model;

/** RC channel values in microseconds, 1-based (index 0 == channel 1). Length 1..8 for v1
 *  (default map uses ch1..8; ch9..18 extension sentinels are ambiguous — see Open Questions). */
public record RcChannels(List<Integer> microsByChannel) {
    public static final int RELEASE = 0;        // "release this channel back to the RC radio" (ch1..8)
    public static final int IGNORE  = 0xFFFF;   // 65535 — "leave this channel unchanged / ignore"
    public static final int MIN_MICROS = 1000;
    public static final int MAX_MICROS = 2000;
    // compact ctor: List.copyOf; length 1..18; each value in [1000,2000] OR == RELEASE OR == IGNORE.
    public int channel(int oneBased);                  // value for RC channel N
    public static RcChannels released(int channelCount);   // RELEASE for channels 1..channelCount
}

/** One physical control → one RC channel, with linear calibration. WebHID/full-calibration UI deferred. */
public record ControlBinding(Source source, int sourceIndex, int rcChannel,
                             int minMicros, int centerMicros, int maxMicros,
                             double deadband, boolean reversed) {
    public enum Source { AXIS, BUTTON }
    // compact ctor: rcChannel 1..18; min<=center<=max within [1000,2000]; deadband 0..1.
    /** AXIS: normalized in [-1,1]; BUTTON: normalized in [0,1]. Applies reverse, deadband, clamp. */
    public int toMicros(double normalized);
}

public record ChannelMap(List<ControlBinding> bindings) {
    public static ChannelMap defaultMap();                     // the frozen table in §5
    /** axes: gamepad axes (-1..1); buttons: gamepad button values (0..1). Builds the override frame. */
    public RcChannels apply(List<Double> axes, List<Double> buttons);
}
```

### 2. Application — `ManualControlService` + session + watchdog  (`vision-application`)

Spring-annotation-free. `VisibilityScope`/`AccessDeniedException` live in **vision-application** (not
domain), exactly as `DefaultFlightCommandService` uses them; only `Device` crosses to the domain port.

```java
public interface ManualControlService {
    /** Explicit engage gesture. Resolves the asset's first active device the ManualControlPort
     *  supports, opens a relay link, starts the watchdog, audits ENGAGE, returns the live session.
     *  @throws AccessDeniedException  asset out of scope (audited DENIED) — vision-api maps to 403
     *  @throws IllegalStateException  no commandable device / not reachable — mapped to a `denied` frame
     *  @throws IllegalStateException  a session is already active on this service handle (one per connection) */
    ManualControlSession engage(AssetId assetId, UserId actor, VisibilityScope scope,
                                WatchdogListener onWatchdog);
}

public interface ManualControlSession {
    /** Map axes/buttons → RcChannels via the ChannelMap, forward latest to the port, RESET the
     *  watchdog deadline. Records seq/tSent for the caller's ack. No-op once released/tripped. */
    void onChannels(List<Double> axes, List<Double> buttons, long seq, long tSent);
    void release();                 // explicit release: port.release, cancel watchdog, audit RELEASE
    ChannelMap channelMap();        // the map handed to the client on `engaged`
    boolean active();
}

/** Async signal: the service auto-released because inbound input stalled. Already released + audited. */
public interface WatchdogListener { void watchdogTripped(); }
```

- **`DefaultManualControlService`** deps (constructor, `Objects.requireNonNull`-guarded):
  `(AssetService assetService, ManualControlPort manualControlPort, AuditTrailPort auditTrail,
  Clock clock, ScheduledExecutorService watchdogScheduler)`. A **convenience ctor** `(AssetService,
  ManualControlPort, AuditTrailPort)` supplies `Clock.systemUTC()` + a single daemon-thread
  scheduler (`"rc-watchdog"`), mirroring `SupervisedPublisher`'s prod-ctor / test-seam-ctor split.
  **Tests inject a controllable `Clock` + a hand-fake `ScheduledExecutorService`** to fire the
  watchdog deterministically (no wall-clock sleeps).
- **Device resolution & gate** — reuse the `DefaultFlightCommandService` shape verbatim:
  `assetService.details(assetId)` → `scope.includes(asset)` else audit `DENIED:out of scope` +
  `AccessDeniedException`; `firstCommandableDevice` = `devices.stream().filter(Device::isActive)
  .filter(manualControlPort::supports).findFirst()` else `IllegalStateException`.
- **Watchdog** (default `WATCHDOG_TIMEOUT_MS = 300`, `vision.rc.watchdog-timeout-ms`): each
  `onChannels` records `lastInput = clock.instant()` and (re)schedules a deadline check; if a check
  fires and `now − lastInput ≥ timeout`, the session auto-releases (`port.release`), cancels itself,
  audits `WATCHDOG`, and calls `onWatchdog.watchdogTripped()`. **Deadman also fires on** explicit
  `release()` and on service/connection teardown (the api handler calls `release()` on socket close).
- **Rate decoupling is the rate limit**: `onChannels` only updates the latest-wins slot via
  `port.send(link, channels)`; the fixed-rate wire cadence is the adapter's job. No per-frame
  throttle in the service — a browser burst simply overwrites the slot.
- **Audit** — mirror `DefaultFlightCommandService.audit`: `auditTrail.record(AuditEntry.of(actor,
  AuditAction.UPDATED, AuditTargetType.ASSET, assetId, summary, attrs))` with
  `attrs = {assetId, command:"MANUAL_CONTROL", result}` and `result ∈ {ENGAGE, RELEASE, WATCHDOG,
  DENIED:out of scope}`. **No new `AuditAction` value** — `UPDATED` is the closest fit, same
  decision the flight-command flow already made. One audit on engage, one on each release path.
- **No auto-arm.** Engage never arms and never changes mode — arming stays the separate
  `FlightCommandService` path (see Safety & non-goals).

### 3. adapter-mavlink — `MavlinkManualControlSender`  (`adapters/adapter-mavlink`)

`public final class MavlinkManualControlSender implements ManualControlPort`, in package
`com.drones.vision.adapter.mavlink` (so it can call the package-private telemetry-source seams, like
`MavlinkFlightCommander`). Single field `private final MavlinkTelemetrySource telemetrySource;` —
**borrows** the RX instance's hub socket & target registry.

- **`supports(device)`** → `telemetrySource.supports(device)` (mavlink telemetry device).
- **`engage(device)`** — resolve like `MavlinkFlightCommander.resolveReachableTarget`:
  `bindKey = telemetrySource.bindKeyFor(device)`; require `telemetrySource.commandTarget(bindKey,
  device.id())` non-null with non-null `sourceAddress()` (else `IllegalArgumentException`). Construct a
  per-session runtime **modeled on `FeedRuntime`** and start its thread; return it as the
  `ManualControlLink`.
- **Sender runtime (mirror `FeedRuntime` L233-418, one change):**
  - daemon thread named `"mavlink-rc-<deviceId>"`; fixed tick = `1000 / VISION_RC_OVERRIDE_HZ` ms.
  - **sends on the HUB'S SHARED socket** `telemetrySource.socket(bindKey)` (not a fresh socket) —
    the new requirement vs `FeedTransmitter`. `DatagramSocket.send` is thread-safe, so co-existing
    with the hub read thread and the occasional `FlightCommander` write is safe (note in Gotchas).
  - each tick: re-resolve the current target (`commandTarget(bindKey,…).sysid()` +
    `.sourceAddress()`, refreshed per RX message in the hub) so it tracks the vehicle's live UDP
    source; read the latest slot; build and send:
    ```java
    RcChannelsOverride.builder()
        .targetSystem(target.sysid()).targetComponent(1 /*MAV_COMP_ID_AUTOPILOT1*/)
        .chan1Raw(ch1)…​.chan8Raw(ch8)          // IGNORE(0xFFFF) for channels the slot doesn't set
        .build();
    connection.send2(255 /*GCS sysid*/, 190 /*MISSIONPLANNER*/, override);
    ```
    `connection` wraps the shared socket in a `MavlinkUdpOutputStream(socket, addr, port)` exactly as
    `MavlinkFlightCommander.send()` does. GCS 255/190 → autopilot comp 1 is the same convention.
  - **latest-wins mailbox**: a single volatile `RcChannels` slot (a `LatestOnlyMailbox`-style
    single-slot handoff); `send()` writes it, the tick reads it. Before the first `send()` the slot
    holds "all IGNORE" (no override emitted until real channels arrive).
- **`release(link)`** — write the release slot `RcChannels.released(8)` (all `RELEASE = 0` → release
  channels 1..8 back to the RC radio, which in SITL means "no RC" → the FC failsafe engages, the
  desired safety behavior), let the loop emit a short burst (`VISION_RC_RELEASE_FRAMES`, default 3
  ticks), then **CAS-stop** the thread with the same interrupt + bounded-join shutdown as `FeedRuntime`.
  Idempotent.
- **Env knobs** (system property / env, defaulted, clamped): `VISION_RC_OVERRIDE_HZ` default **33**
  (clamp 10..50), `VISION_RC_RELEASE_FRAMES` default **3**. Document in `adapter-mavlink/MODULE.md`.

### 4. Transport — WebSocket protocol  (`vision-api` + `vision-app`)

**Endpoint** `/ws/manual-control` — a raw Spring servlet `WebSocketHandler` (**not** STOMP),
registered via a `WebSocketConfigurer`. JSON **text** frames, discriminated by `type`. **One active
session per connection.**

**Handshake auth** — the upgrade GET rides the same-origin **session cookie** and the same
`SecurityFilterChain` as every `/api/**` call. A `HandshakeInterceptor` resolves the acting principal
via the **same singleton `CurrentUser` bean** the REST controllers use, stashing `UserId` +
`VisibilityScope` into the WS session attributes. When `vision.auth.enabled=false`,
`DevPrincipalResolver` yields the unbounded dev principal (same fallback as everywhere). When `true`,
`SecurityContextPrincipalResolver` reads `SecurityContextHolder`; `SecurityConfig`'s secured chain
must add `/ws/**` to `authenticated()` so an unauthenticated handshake is rejected before upgrade.

**Client → server:**

```json
{ "type": "engage",  "assetId": "<uuid>" }
{ "type": "channels", "axes": [0.0, -0.12, 1.0, 0.0], "buttons": [0.0, 1.0], "seq": 42, "tSent": 1738300000123 }
{ "type": "release" }
```

**Server → client:**

```json
{ "type": "engaged",  "assetId": "<uuid>", "rateHz": 33,
  "channelMap": [ { "source": "AXIS", "sourceIndex": 0, "rcChannel": 1, "label": "Roll" }, … ] }
{ "type": "denied",   "code": "OUT_OF_SCOPE|NOT_COMMANDABLE|UNSUPPORTED|ALREADY_ENGAGED", "reason": "<human string>" }
{ "type": "ack",      "seq": 42, "tSent": 1738300000123, "tServer": 1738300000131 }
{ "type": "released", "reason": "EXPLICIT|SOCKET_CLOSE" }
{ "type": "watchdog", "timeoutMs": 300 }
```

Frozen semantics:
- `engage` while a session is already active → `denied code=ALREADY_ENGAGED` (the connection keeps
  its existing session). Engage maps `AccessDeniedException`→`denied OUT_OF_SCOPE`,
  no-commandable-device→`denied NOT_COMMANDABLE`, unsupported→`denied UNSUPPORTED`.
- The server sends **one `ack` per `channels` frame** (echo `seq`+`tSent`, add server `tServer`) so
  the client measures glass-to-stick RTT. `axes`/`buttons` are the raw Gamepad values (axes −1..1,
  buttons 0..1); mapping to µs is server-side via `ChannelMap`.
- `release` (client) → `released reason=EXPLICIT`. **Socket close (any cause) → the handler calls
  `session.release()`** and the session ends (`released reason=SOCKET_CLOSE` is best-effort; the
  socket may already be gone). The `watchdog` frame is sent when `WatchdogListener` fires; the
  session is already released server-side and the client must re-`engage` to resume.
- The handler owns per-`WebSocketSession` state: the `ManualControlSession` and its active flag. It
  implements `WatchdogListener` to push the `watchdog` frame (guard concurrent `sendMessage` with a
  per-connection lock, exactly as `LiveConnection` guards `SseEmitter#send`).

### 5. Default channel map (frozen data — `ChannelMap.defaultMap()`)

v1 uses **channels 1..8 only** (ch9..18 extension release-sentinel is ambiguous — Open Questions).
Linear default; per-axis calibration UI deferred but modeled (`ControlBinding`).

| Source | Index | RC ch | Function | min / center / max µs | deadband | reversed |
|---|---|---|---|---|---|---|
| AXIS | 0 | 1 | Roll (aileron) | 1000 / 1500 / 2000 | 0.0 | false |
| AXIS | 1 | 2 | Pitch (elevator) | 1000 / 1500 / 2000 | 0.0 | false |
| AXIS | 2 | 3 | Throttle | 1000 / 1500 / 2000 | 0.0 | false |
| AXIS | 3 | 4 | Yaw (rudder) | 1000 / 1500 / 2000 | 0.0 | false |
| BUTTON | 0 | 5 | Aux 1 (flight-mode switch) | 1000 / — / 2000 | — | false |
| BUTTON | 1 | 6 | Aux 2 | 1000 / — / 2000 | — | false |
| BUTTON | 2 | 7 | Aux 3 | 1000 / — / 2000 | — | false |
| BUTTON | 3 | 8 | Aux 4 | 1000 / — / 2000 | — | false |

Channels the browser frame doesn't populate are sent as `IGNORE (0xFFFF)`. Throttle (axis 2) rests
differently per radio/EdgeTX map — the linear default may need `reversed`/endpoint tuning; that is a
SITL-tuning + deferred-calibration concern, called out honestly, not hidden.

---

## Design decisions (with rationale)

### A. Streaming seam, not another `FlightCommandPort` method
RC relay is a continuous, ack-less stream with a watchdog — the **opposite** shape to the request→ack
one-shots `FlightCommandPort` models. A new `ManualControlPort` keeps each port honest to its
protocol. It reuses the flight-command *application* idiom (scope gate, `firstCommandableDevice`,
audit) but adds the one thing that flow lacks: **session state + a watchdog**.

### B. Rate decoupling in the adapter; watchdog in the application
Two independent clocks. The **wire cadence** (fixed ~33 Hz `RC_CHANNELS_OVERRIDE`) is a MAVLink/adapter
concern — it lives next to the socket, mirroring `FeedRuntime`, on a latest-wins mailbox so browser
jitter never reaches the wire. The **watchdog** (input-loss → release) is a *safety policy* — it
lives in the application layer where it is deterministic and unit-testable via an injected
`Clock` + `ScheduledExecutorService` (the `SupervisedPublisher` seam pattern). Keeping them apart
means the wire keeps ticking at a safe fixed rate while the policy layer decides when to stop.

### C. Send on the shared hub socket (the one new hard requirement)
`FeedTransmitter` opens its own ephemeral socket; the relay must send from the **same** `host:port`
the platform already listens on so the FC sees a consistent GCS endpoint and `RC_CHANNELS_OVERRIDE`
reaches the vehicle's live `sourceAddress`. So the sender borrows `MavlinkTelemetrySource` (like
`MavlinkFlightCommander`) and writes via `telemetrySource.socket(bindKey)`. `DatagramSocket.send` is
thread-safe → the RC thread, the hub read thread, and the occasional flight-command write coexist.

### D. Guardrails leave existing behavior unchanged
No existing test changes. Everything is additive: a new port + value objects (domain), a new service
+ new scheduler (application, wired in `WiringConfiguration`), a new adapter class, a new WS endpoint
behind a **new dependency** (`spring-boot-starter-websocket` in vision-api only), and a new Fly panel
action. The relay only ever runs when the operator explicitly engages; nothing autostarts. Adding
`/ws/**` to the secured chain is the only touch to `SecurityConfig`, and it *tightens* (auth-required)
rather than loosening. The dev-auth-disabled path is unchanged (permitAll chain already covers `/ws`).

### E. Capability + preconditions, no auto-arm
Engage requires: the vehicle **heard + commandable** (`commandTarget` non-null with a live
`sourceAddress` — the "cannot command what you cannot hear" rule) **and** `ManualControlPort.supports`
(a mavlink device the adapter can drive — the manual-control capability). Engage **never arms and
never sets mode**; arming stays the separate `FlightCommandService` path. Whether to *require* a
specific RC-honoring flight mode before engage is an Open Question, defaulted to "don't gate mode in
v1" (SITL lets the operator pick the mode via the flight panel first).

---

## Implementation waves (disjoint file scopes)

Each wave ends **independently green** with its scoped build and its `MODULE.md` updated. Sequencing:
**R1 → (R2 ‖ R3) → R4 → R5**. The frozen contracts above are the sole coupling; R2 and R3 both depend
only on R1 and can run in parallel. R4 needs R2 (service) **and** R3 (the concrete adapter bean it
wires + the app must boot). R5 needs R4 (the frozen frames).

### R1 — domain: ports + value model — `vision-domain/**`  (agent: domain-modeler)
- Add `ManualControlPort` + `ManualControlLink` (`port/out`), and `RcChannels`, `ControlBinding`
  (with `Source`), `ChannelMap` (`model`) exactly per §1/§5. `ChannelMap.defaultMap()` returns the
  frozen 8-channel table; `apply(...)` builds the `RcChannels`; `ControlBinding.toMicros` is the
  linear map (reverse, deadband, clamp).
- **No** dependency on any framework or on `VisibilityScope` (application-only). Keep validation in
  compact ctors (`IllegalArgumentException`).
- Tests: `RcChannels` validation (range/sentinel/length) + `released(n)`; `ControlBinding.toMicros`
  (center/endpoints/deadband/reverse/clamp); `ChannelMap.defaultMap()` shape + `apply` mapping a
  known axes/buttons vector to expected µs. Verify: `-pl vision-domain test` green; `MODULE.md`
  updated (new port + value model, sentinel semantics, ch1..8 scope).

### R2 — application: service + watchdog/deadman/audit/session — `vision-application/**`  (agent: application-service). Depends R1.
- `ManualControlService` + `ManualControlSession` + `WatchdogListener` + `DefaultManualControlService`
  per §2. Inject `Clock` + `ScheduledExecutorService` (prod convenience ctor supplies a daemon
  `"rc-watchdog"` scheduler + `Clock.systemUTC()`). Reuse the `DefaultFlightCommandService` gate/audit
  idiom (scope → `AccessDeniedException` + audit `DENIED`; `firstCommandableDevice`; `AuditAction.
  UPDATED`, `command:"MANUAL_CONTROL"`).
- Watchdog default 300 ms via `vision.rc.watchdog-timeout-ms` (read where the bean is constructed in
  R4 wiring; the service takes the value/scheduler, not the property). One-session-per-service-handle
  guard.
- Tests (hand-fake `ManualControlPort` + `AssetService` + `AuditTrailPort`, **injected fake
  scheduler + mutable `Clock`**): engage audits `ENGAGE` + opens the link; `onChannels` forwards
  mapped channels (latest-wins, fake port captures) and resets the deadline; **watchdog fires** when
  the clock advances past the timeout with no input → `port.release` called, `WATCHDOG` audited,
  `WatchdogListener` invoked; explicit `release` → `port.release` + `RELEASE` audit; out-of-scope
  engage → `AccessDeniedException` + `DENIED` audit; no commandable device → `IllegalStateException`;
  second engage while active → refusal. Verify: `-pl vision-application test` green; `MODULE.md` updated.

### R3 — adapter-mavlink: fixed-rate `RC_CHANNELS_OVERRIDE` sender — `adapters/adapter-mavlink/**`  (agent: adapter-builder). Depends R1.
- `MavlinkManualControlSender implements ManualControlPort` per §3: borrow `MavlinkTelemetrySource`;
  `engage` resolves the reachable target (reuse the `resolveReachableTarget` logic) and starts a
  `FeedRuntime`-shaped per-session daemon (`"mavlink-rc-<deviceId>"`, fixed tick from
  `VISION_RC_OVERRIDE_HZ`) that sends `RcChannelsOverride` #70 on `telemetrySource.socket(bindKey)`
  (GCS 255/190 → autopilot comp 1) reading a latest-wins slot; `send` updates the slot; `release`
  emits a `released(8)` burst then CAS-stops (interrupt + bounded join).
- Tests (a fake/loopback `DatagramSocket` capturing datagrams, mirroring the module's existing socket
  tests): assert `#70` frames flow at ~the configured rate; assert channel µs match the sent slot and
  unset channels are `0xFFFF`; assert `release` emits the release burst (`chanNRaw == 0` for ch1..8)
  then the thread stops (no further frames). Keep tests SITL-free and docker-free. Verify: `-pl
  adapters/adapter-mavlink test` green; `MODULE.md` updated (new surface + `RC_CHANNELS_OVERRIDE`
  send, shared-socket note, env knobs, sentinel semantics).

### R4 — vision-api + vision-app: WebSocket transport + wiring — `vision-api/**`, `vision-app/**`  (agent: spring-integrator). Depends R2 + R3.
- **vision-api**: add `spring-boot-starter-websocket` to `vision-api/pom.xml`. Add
  `ManualControlWebSocketHandler` (raw `TextWebSocketHandler`) + `WebSocketConfigurer` registering
  `/ws/manual-control` + a `HandshakeInterceptor` that resolves `UserId`+`VisibilityScope` via the
  injected singleton `CurrentUser` and stores them in session attributes. Parse/emit the §4 frames
  (frame DTOs in `com.drones.vision.api.dto`); translate engage exceptions → `denied` codes; on
  `channels` call `session.onChannels(...)` then send `ack`; guard `sendMessage` with a
  per-connection lock; on `afterConnectionClosed` call `session.release()`. Implement
  `WatchdogListener` → send `watchdog`.
- **vision-app**: `WiringConfiguration` — `@Bean mavlinkManualControlSender(MavlinkTelemetrySource)`
  (instance-borrow, concrete type, exactly like `mavlinkFlightCommander` L271-274) and `@Bean
  ManualControlService manualControlService(AssetService, ManualControlPort, AuditTrailPort)` reading
  `vision.rc.watchdog-timeout-ms` (default 300) to size the watchdog. `SecurityConfig`'s secured
  chain: add `/ws/**` to `authenticated()`. Keep `domainAndApplicationAreSpringAnnotationFree` +
  `onlyAppMayDependOnAdapterPackages` ArchUnit rules green (WS handler lives in vision-api; no adapter
  import).
- Tests: a `WebSocketHandler` unit/integration test (Spring `StandardWebSocketClient` or a handler
  unit test with a fake `WebSocketSession`) covering engage→engaged, channels→ack, release→released,
  denied paths, and close→release; a wiring smoke test. Verify: `-pl vision-api test` and `-pl
  vision-app test` green; both `MODULE.md`s updated (new endpoint, handshake auth, `/ws/**` matcher,
  wiring, env knobs).

### R5 — web: Take control + WS relay client + deadman — `vision-web/**`  (agent: web-ui). Depends R4.
- **`core/rc`**: a `ManualControlClient` service — opens `new WebSocket('/ws/manual-control')`
  (same-origin, cookie auth, mirroring how `LiveStore` opens its `EventSource`); sends `engage`, then
  streams `channels` frames from `RcInputService`'s `axes`/`buttons` at the send rate (throttled to
  ~the server `rateHz`); parses `engaged`/`denied`/`ack`/`released`/`watchdog`; exposes signals
  `engaged`/`denied`/`latencyMs` (from `ack` RTT = `now − tSent`)/`released`/`channelMap`.
- **Deadman on the browser side**: release (send `release` + close socket) on the RELEASE gesture,
  panel close, `document.visibilitychange`→hidden, and any WS `close`/`error`. Leverage the Phase-0
  fact that the rAF poll already pauses when the tab is hidden — layer the explicit `release` on top.
- **`features/fly`**: extend the `rc` drawer (or a sibling panel) with a prominent **Take control**
  engage button (explicit gesture, capability-gated like `flight-command-panel` — only enabled when
  the asset is commandable), a large always-visible **RELEASE** control, a live **latency (ms)**
  readout, the **engaged/denied/watchdog** state, and the **default channel-map** display (which axis
  drives which RC channel). Honest copy: "SITL only", "release stops your input and the drone's
  failsafe takes over", "latency is round-trip glass-to-stick".
- Tests: vitest for the client (frame encode/decode, latency calc, deadman triggers) + panel logic;
  `tsc` clean; production build green. Verify: `npm run test:ci` + build green; `vision-web/MODULE.md`
  updated.

---

## SITL verification (user-run — not CI)

There is **no SITL instance and no RC transmitter in CI**; this is the human's step and the gate on
Phase 2. Latency and the watchdog are validated here, on the machine with the radio plugged in.

1. **Start SITL**: `cd infra/sitl && ./up.sh 1` (one ArduCopter 4.7.0 pushing MAVLink2 UDP to the
   host on 14550). `docker logs -f vision-sitl-1` to watch it boot/arm/fly.
2. **Register the device**: `POST /api/devices { "name":"SITL", "protocol":"mavlink",
   "uri":"udp://0.0.0.0:14550", "capabilities":["TELEMETRY"] }`; confirm telemetry (heartbeat, mode,
   armed) appears for the asset in the cockpit.
3. **Plug the radio** in as EdgeTX "USB Joystick (HID)"; open the Fly `rc` drawer; move a stick and
   confirm the Phase-0 monitor shows live channels.
4. **Pick an RC-honoring mode + arm** via the flight-command panel (e.g. Stabilize/AltHold/Loiter) —
   engage does **not** arm. (SITL's `autofly.py` may already be flying a CIRCLE; switch to a manual
   mode.)
5. **Take control** in the cockpit; fly the SITL copter from the sticks; observe the vehicle respond
   in the SITL log / any GCS map.
6. **Measure glass-to-stick latency**: read the live `ack` RTT readout; record min/median/jitter.
   Treat it as a safety budget — tune `VISION_RC_OVERRIDE_HZ` and `vision.rc.watchdog-timeout-ms`
   against it. GPS-assisted modes tolerate far more latency than Stabilize/Acro.
7. **Watchdog drill (the key test)**: while flying, (a) hit **RELEASE** — channels must release
   immediately; (b) **unplug the radio** / stop moving past the timeout — the watchdog must
   auto-release within `WATCHDOG_TIMEOUT_MS` and the cockpit must show the `watchdog` state; (c)
   **close the panel** and **hide the tab** — each must release. Confirm each path releases and the
   SITL vehicle falls back to its FC behavior. Confirm every engage/release/watchdog appears in the
   audit trail.
8. **Tear down**: `./down.sh`.

Observe: does the copter track the sticks; what is the latency + jitter; does every deadman path
release within the timeout; is every session audited.

## Safety & non-goals (named, not dropped)

- **Phase 2 (real vehicle) is gated on explicit user go** — same doctrine as I-e. Nothing in this
  plan touches a real airframe; the port/service/adapter are SITL-exercised only.
- **No auto-arm, no auto-mode**: engage never arms or changes mode; arming stays the
  `FlightCommandService` path. The operator arms deliberately, separately.
- **Failsafe-race doctrine**: SITL has no RF receiver, so there is **no RC-failsafe race** — releasing
  channels (`RELEASE=0`) simply removes override and the FC's own (input-loss) failsafe takes over.
  The real-airframe receiver/failsafe question is a **Phase 2** precondition, out of scope here.
- **Deferred (modeled, not built)**: WebHID raw-report capture (Gamepad API is fine for SITL v1 — the
  Phase-0 service surface already isolates the source); full per-axis **calibration UI** (the
  `ControlBinding` model + linear default ships now); **multi-session / multi-vehicle** relay (one
  session per connection, one asset); `ManualControl` #69 stick-only path (we use #70); channels
  **9..18** (v1 default map is ch1..8 to avoid the extension sentinel ambiguity); persisting a
  per-operator channel map server-side.

## Open questions / to confirm during implementation

1. **Watchdog timeout + send rate** — 300 ms / 33 Hz are defaults to **validate in SITL** against the
   measured latency+jitter (step 6). Tune the env knobs; don't assume.
2. **Require a specific flight mode before engage?** — v1 default: **no** (operator sets mode via the
   flight panel first). Revisit if SITL shows Stabilize/Acro are too latency-sensitive to hand raw
   sticks safely; a "require AltHold/Loiter to engage" gate is a cheap add if needed.
3. **WS auth when `vision.auth.enabled=false`** — the permitAll chain lets the handshake through and
   `DevPrincipalResolver` supplies the unbounded dev principal (same as REST). Confirm the
   `HandshakeInterceptor` resolves identity identically in both chains and that `/ws/**` under the
   secured chain rejects an unauthenticated upgrade with 401 before the socket opens.
4. **Channel-release sentinel for ch9..18** — v1 sidesteps it (default map = ch1..8, where `0` =
   release-to-radio is unambiguous). If a future map uses ch9..18, verify the correct extension
   release value in SITL before shipping it (the MAVLink #70 extension uses different sentinels than
   the base ch1..8 fields).
5. **`ack`-per-frame chattiness** — at ~30 Hz the server acks every `channels` frame for latency
   measurement. If that proves noisy on a real LAN, sample (ack every Nth) — a client-transparent
   change since the client already keys on `seq`. Default: ack every frame.
