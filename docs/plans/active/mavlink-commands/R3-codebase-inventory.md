# R3 — MAVLink command surface & manual-control path: codebase inventory

**Task:** MAVLINK-COMMANDS R3 · **Scope:** what the platform sends/receives over MAVLink today, and the full keyboard/controller → wire path · **Method:** docs-first (RC-CONTROL-PLAN, CONTROLLER-UX-PLAN, OPERATOR-CONTROL-CONTEXT, VEHICLE-CONTROL-PROFILES-CONTEXT, MISSIONS-PLAN, FLEET-RADIO-PLAN, ZERO-CONFIG-ONBOARDING-CONTEXT, MAVLINK-CORE-PLAN, MODULE.md × 5), verified against code with targeted greps.

---

## 1. Outbound command surface today

Two distinct transports carry outbound MAVLink traffic. **One-shot commands** go through `mavlink-core`'s `CommandService`/`ParameterService`/`RequestResponse` (Family A: request→correlated response, zero or configurable retries). **Continuous manual control** goes through `ManualControlService` (Family B: streaming, no per-frame ack).

### 1a. One-shot commands (`COMMAND_LONG`)

All built in `MavlinkFlightCommander` (`drone-link/mavlink/src/main/java/com/drones/vision/adapter/mavlink/MavlinkFlightCommander.java`), one method per action, each opening a **fresh, stateless `CommandService`** per call (`gateway.sink()`, `gateway.correlator()`, configured `ackTimeout`, `NO_RETRIES` — MavlinkFlightCommander.java:337) and sending exactly one `COMMAND_LONG` with `confirmation=0`:

| Action | Method | MAV_CMD | Notes | Line |
|---|---|---|---|---|
| Set mode | `setMode(Device, String)` | `MAV_CMD_DO_SET_MODE` | param1 = `MAV_MODE_FLAG_CUSTOM_MODE_ENABLED`, param2 = resolved custom_mode | MavlinkFlightCommander.java:146-150 |
| Return to home | `returnToHome(Device)` | `MAV_CMD_DO_SET_MODE` → RTL custom mode | delegates to setMode with RTL's mode number | MavlinkFlightCommander.java:156 |
| Arm | `arm(Device, boolean force)` | `MAV_CMD_COMPONENT_ARM_DISARM` | param1=1, param2=force?21196:0 (ArduPilot force-arm magic number) | MavlinkFlightCommander.java:163 |
| Disarm | `disarm(Device, boolean force)` | `MAV_CMD_COMPONENT_ARM_DISARM` | param1=0, same force semantics | MavlinkFlightCommander.java:168-175 |
| Emergency stop | `emergencyStop(Device)` | `MAV_CMD_DO_SET_MODE` → **Hold** (custom_mode 4), never disarm | FLEET-RADIO R4b: rover-safe — disarming a moving rover cuts steering, not throttle only, and it coasts/crashes; Hold actively brakes | MavlinkFlightCommander.java:207-244 |
| Aux function (switch) | `auxFunction(Device, int function, int level)` | `MAV_CMD_DO_AUX_FUNCTION` | level is 0/1/2 (low/mid/high), param range documented at line 90 | MavlinkFlightCommander.java:250-260 |
| Capabilities probe | `capabilities(Device)` | n/a (reads cached telemetry) | not a wire send — reports what the device supports from decoded `AUTOPILOT_VERSION`/heartbeat | MavlinkFlightCommander.java:266 |

Every call routes through `CommandService.sendLong` (`drone-link/mavlink-core/.../service/CommandService.java:85`), which:
- Sends `COMMAND_LONG` targeted at `SysId`/`TARGET_COMPONENT_AUTOPILOT`.
- Awaits `COMMAND_ACK` correlated on `(origin sysid, command id)` via `Correlator`.
- With `NO_RETRIES` (MavlinkFlightCommander's chosen policy — CommandService.java:63,95-97): **zero retries, one wait up to `ackTimeout`, then `CommandOutcome.Status.NO_ACK`** (CommandService.java:184). `MAV_RESULT_ACCEPTED`→`ACCEPTED`; `TEMPORARILY_REJECTED`/`DENIED`/`UNSUPPORTED`/`FAILED`/`IN_PROGRESS`/`CANCELLED` map 1:1 (CommandService.java:170-176).
- `MavlinkFlightCommander` translates `CommandOutcome` → its own `CommandResult` (ACCEPTED/NO_ACK/etc.) at line 353-359; **no retry wrapper exists at the adapter level** — this is a deliberate zero-retry design (documented at MavlinkFlightCommander.java:59-69), distinct from `RequestResponse`'s general retry mechanism which this class opts out of.

`ParameterService` (`drone-link/mavlink-core/.../service/ParameterService.java`) covers `PARAM_REQUEST_READ`/`PARAM_REQUEST_LIST`/`PARAM_SET`: `read(PeerId, name)` (line 100), `readAll(PeerId, List<String>)` (line 123), `write(PeerId, name, value, MavParamType)` (line 150) — writes are **verified by exact-string PARAM_VALUE compare on arrival**, not assumed (class javadoc line 41-49), with a bounded number of wrong-named `PARAM_VALUE`s tolerated per exchange before failing (line 68-77).

`MessageIntervalService` / `RequestResponse` (mavlink-core L4, per MAVLINK-CORE-PLAN §Family A/B taxonomy) cover `MAV_CMD_SET_MESSAGE_INTERVAL` and `MAV_CMD_REQUEST_MESSAGE` — used by rover-sim's `command_test.cpp`/`command_verify.py` to prove the firmware side (see §5); the platform-side caller for these two specifically was not found wired into `MavlinkFlightCommander` — capability-probe path (`capabilities()`) reads cached decoded telemetry rather than actively requesting `AUTOPILOT_VERSION`, so on the platform side these two MAV_CMDs are exercised by the **firmware test harness**, not confirmed as platform-issued in production yet.

### 1b. Continuous manual control (`RC_CHANNELS_OVERRIDE` #70)

Built in `MavlinkManualControlSender` (`drone-link/mavlink/src/main/java/com/drones/vision/adapter/mavlink/MavlinkManualControlSender.java`), which implements vision-flight's `ManualControlPort`:

- `engage(Device)` (line 127): resolves target peer via `MavlinkTelemetrySource#commandTarget`, refuses `VehicleKind.UNKNOWN`/unidentified vehicles **before ever constructing a `ManualControlService`** (FLEET-RADIO R2 — line 36 comment), then builds a per-engage `mavlink-core` `ManualControlService` (fixed-rate scheduler-driven) and calls `service.engage(peerId)` (line 145-146).
- `send(ManualControlLink, RcChannels)` (line 159): translates vision-flight's `RcChannels` (channel→micros map) into mavlink-core's structurally identical `RcChannels` verbatim (`toCoreChannels`, line 182-183) — **no range/sentinel logic in the adapter**; that lives in mavlink-core's `RcChannels.wireValue(int)`, which resolves the differing release-sentinel encoding between channels 1-8 and 9-16 (comment line 66-69, referencing FLEET-RADIO F3/F4 fixes).
- `release(ManualControlLink)` (line 166).
- `AdapterLink` (line 192) carries `rateHz`, `vehicleKind`, and `unidentifiedReason` alongside the raw link — `rateHz()` (line 217) is read **live** by the WS handler for its outbound `ack`/`watchdog` cadence (RC-LATENCY work, no hardcoded constant — confirmed in `ManualControlWebSocketHandler`, §3 below).

`ManualControlService` (mavlink-core L4) builds `RC_CHANNELS_OVERRIDE` (#70) frames on a fixed-rate scheduler; no per-frame ACK is awaited (MANUAL_CONTROL/RC_OVERRIDE has none in the MAVLink spec) — liveness is inferred instead from the deadman watchdog (§3) and `LinkHealth`.

### 1c. Send-on-arrival / rate (RC-LATENCY work)

Per prior work captured in memory and confirmed in `ManualControlClient` (vision-web, §3): channel updates are sent the instant new axis/button data arrives (`effect()` over `source.axes()`/`source.buttons()`), cutting ~39ms→~9ms versus a fixed poll loop, with a keepalive backstop (`SEND_CHECK_INTERVAL_MS`) bounded by `keepaliveIntervalMs(rateHz)` so the link doesn't go quiet between deliberate stick moves.

---

## 2. Inbound: decoded telemetry, COMMAND_ACK, PARAM_VALUE

### 2a. Telemetry decode

All inbound MAVLink payload decoding funnels through one class: `MavlinkTelemetryDecoder` (`drone-link/mavlink/src/main/java/com/drones/vision/adapter/mavlink/MavlinkTelemetryDecoder.java`), a package-private `instanceof`-chain dispatcher (line 228-261):

| Wire message | Decoded to | Line |
|---|---|---|
| `GLOBAL_POSITION_INT` | position | 228 |
| `SYS_STATUS` | sysStatus | 230 |
| `BATTERY_STATUS` | batteryStatus | 232 |
| `VFR_HUD` | vfrHud | 234 |
| `HEARTBEAT` | heartbeat | 236 |
| `GPS_RAW_INT` | gpsRawInt | 238 |
| `RC_CHANNELS` | rcChannels | 240 |
| `RC_CHANNELS_RAW` | rcChannelsRaw | 242 |
| `STATUSTEXT` | statustext | 244 |
| `WIND` | wind | 246 |
| `VIBRATION` | vibration | 248 |
| `EKF_STATUS_REPORT` | ekfStatusReport | 250 |
| `MISSION_CURRENT` | missionCurrent | 252 |
| `RANGEFINDER` | rangefinder | 254 |
| `ATTITUDE` | attitudeMessage | 256 |
| `GIMBAL_DEVICE_ATTITUDE_STATUS` | gimbalDeviceAttitudeStatus | 258 |
| `MOUNT_ORIENTATION` | mountOrientation | 260 |

A second system sharing the same UDP port is explicitly ignored (line 225, "a second system sharing this port: ignored, see class javadoc") rather than merged — a single-system-per-decoder-instance assumption worth flagging for a fleet scenario.

### 2b. `COMMAND_ACK`

Handled entirely inside `CommandService` (mavlink-core, §1a) via `Correlator` matching on `(origin sysid, command id)` (CommandService.java:27-29) — there is no separate inbound-only ack listener; the correlated future resolves the same `sendLong`/`sendInt` call that issued the command. `COMMAND_ACK_MESSAGE_ID` is re-exported from `CorrelationKeys` (line 48).

### 2c. `PARAM_VALUE`

Handled by `ParameterService` the same way — correlated per-key, verified by exact param-name string match on arrival (§1a). A `PARAM_VALUE` for a name nobody asked for does not resolve anyone's future (it just doesn't match a waiting key); `readAll` tracks one future per requested name (line 123).

### 2d. `HEARTBEAT` / link health

`HeartbeatService` (mavlink-core) both emits the platform's own GCS `HEARTBEAT` on every link a peer has been heard on (class javadoc line 23-40) and computes `isConnected(PeerId)` (line 103) from the last-heard timestamp against `MavlinkCoreSettings`. This is distinct from the address-flap detection added in `DefaultPeerDirectory` (§4).

---

## 3. Manual-control path end to end

```
RadioMaster / gamepad / keyboard / on-screen pad
        │
        ▼
RcInputService (Gamepad API, rAF poll)      KeyboardRcInputService (keydown/keyup)      VirtualRcInputService (drag pads)
   station/vision-web/src/app/core/rc/{rc-input,keyboard-rc-input}.service.ts   — 3 RcSourceKinds behind one RcSource seam
        │
        ▼
ManualControlClient  (station/vision-web/src/app/core/rc/manual-control-client.ts)
   states: idle → engaging → engaged → denied/released
   engage() opens WS, sends `engage` frame; effect() over source.axes()/buttons() sends `channels` frames on arrival (send-on-arrival)
        │  WS: /ws/manual-control  (raw WebSocket, not STOMP)
        ▼
ManualControlWebSocketHandler  (station/vision-api/.../ws/ManualControlWebSocketHandler.java, 313 lines)
   per-connection ConnectionState{sendLock, volatile session}
        │
        ▼
DefaultManualControlService  (contexts/vision-flight — application layer)
   engage: scope/authorization check, VehicleUnidentifiedException gate, watchdog start
        │
        ▼
ManualControlPort  (contexts/vision-flight domain port)  ◄── implemented by
        │
        ▼
MavlinkManualControlSender  (drone-link/mavlink adapter, §1b)
        │
        ▼
mavlink-core ManualControlService → RC_CHANNELS_OVERRIDE (#70) → UDP
```

### 3a. vision-web layer

| Class | Path | Role | Key detail |
|---|---|---|---|
| `RcInputService` | `station/vision-web/src/app/core/rc/rc-input.service.ts` (121 lines) | Gamepad API polling | `RATE_WINDOW=30` frames (~0.5s@60Hz), rAF `loop()`, `gamepadconnected`/`disconnected` listeners, provided per-host with `DestroyRef` cleanup |
| `KeyboardRcInputService` | `station/vision-web/src/app/core/rc/keyboard-rc-input.service.ts` (281 lines) | W/A/S/D + arrow keys as a third `RcSourceKind` | `resolveKey(code, bindings)` maps W/S→THROTTLE, A/D→YAW or STEERING, arrows→PITCH/ROLL or THROTTLE depending on one-pad vs two-pad profile. `RAMP_MS=250`, `TICK_MS=16` (~60Hz). `rampedValue()` clamps to travel range. Uses `KeyboardEvent.code` (layout-independent). `isTypingTarget()` guards form fields |
| — deadman triggers (keyboard) | same file | `setEnabled(false)`, window `blur`, `visibilitychange` (hidden) all call `releaseAll()` | |
| `VirtualRcInputService` | `station/vision-web/src/app/core/rc/` (on-screen drag pads) | third input kind, not read in full this pass | referenced by CONTROLLER-UX-PLAN as the mobile/no-hardware fallback |
| `ManualControlClient` | `station/vision-web/src/app/core/rc/manual-control-client.ts` (378 lines) | WS client | `MANUAL_CONTROL_WS_URL='/ws/manual-control'`, `ENGAGE_TIMEOUT_MS=4000`. **Six deadman triggers** (class javadoc): explicit release, panel closing (DestroyRef), tab hiding (visibilitychange), input source disappearing (`RcSource.live()` effect), socket drop (onclose/onerror), server watchdog/released frame. One-socket-per-session (closes+reopens). `offerChannels`/`shouldSendChannels` implement send-on-arrival; `startSendLoop`/`SEND_CHECK_INTERVAL_MS` is the keepalive backstop bounded by `keepaliveIntervalMs(rateHz)` |
| `ControlActionDispatcher` | `station/vision-web/src/app/core/rc/control-action-dispatcher.ts` (242 lines) | edge-triggered switch-bound **one-shot** commands over REST (not the manual-control socket) | `DANGEROUS_HOLD_MS=600` hold-to-fire for dangerous actions (`holdKey`/`holdPosition`/`holdSince` state machine); non-dangerous fire immediately on edge. `command()` dispatches ARM/DISARM/TOGGLE_ARM (checked against **live** telemetry `armed()`, not remembered client state)/EMERGENCY_STOP/RETURN_TO_HOME/SET_MODE/AUX_FUNCTION to `VisionApi` REST calls. Rationale documented: keeps `/ws/manual-control` contract frozen, works without taking stick control, doesn't need 33Hz transport |

### 3b. vision-api layer

| Class | Path | Role |
|---|---|---|
| `ManualControlWebSocketHandler` | `station/vision-api/.../ws/ManualControlWebSocketHandler.java` (313 lines, full read) | Raw WS handler. Frames in: `engage`/`channels`/`release`. Frames out: `engaged`/`denied`/`ack`/`released`/`watchdog`. Denial codes: `OUT_OF_SCOPE`, `NOT_COMMANDABLE`, `UNSUPPORTED`, `ALREADY_ENGAGED`, `VEHICLE_UNIDENTIFIED`, `BAD_REQUEST`, `MALFORMED`, `UNKNOWN_TYPE`. Exception mapping: `AccessDeniedException`→OUT_OF_SCOPE, `VehicleUnidentifiedException`→VEHICLE_UNIDENTIFIED (caught before the generic handler), `IllegalStateException`→message-substring-mapped (`mapIllegalState`). `rateHz` is read **live** from `ManualControlSession#rateHz()` per connection — no hardcoded constant (RC-LATENCY work) |
| `FlightCommandController` | `station/vision-api/.../controller/FlightCommandController.java` | REST for one-shot commands: `POST /api/assets/{id}/return-home` (202), `/mode` (SetModeRequest body), `/arm`, `/disarm` (both take optional `ForceCommandRequest{force}`), `/emergency-stop` (**no body** — deliberately separate from `disarm(force=true)` so the audit trail distinguishes panic-stop from a considered disarm), `/aux-function` (`AuxFunctionRequest{function, level}`), `GET /flight-capabilities` (404 if unknown/out of scope) |
| `AssetParameterController` | `station/vision-api/.../controller/AssetParameterController.java` | `POST /api/assets/{id}/parameters` (`ParameterWriteRequest{name,value,consent}`→`ParameterWriteResponse`); `consent` is a **hard interlock covering every tier**, unlike `RemediationService`'s own `explicitConsent` which only gates Tier B; name resolution via `ParameterAliases`/`VehicleProfileService#latestProfile` |

### 3c. vision-flight application layer

`DefaultManualControlService` (contexts/vision-flight) — engage/watchdog/refusal logic: scope check → `ControlProfileRepositoryPort` resolves the vehicle's `ControlProfile` (airframe-aware channel mapping, VEHICLE-CONTROL-PROFILES) → `ManualControlPort.engage` → deadman watchdog (300ms default per memory) started on the server side independent of the client's own deadmen. `DefaultFlightCommandService` handles the one-shot command path (arm/disarm/mode/e-stop/aux) → `FlightCommandPort` → adapter.

### 3d. Arming / mode / e-stop / operator gating summary

| Action | Gate | Where enforced |
|---|---|---|
| Manual-control engage | vehicle must be identified (`VehicleKind != UNKNOWN`) | `MavlinkManualControlSender.engage` line 36 comment + `VehicleUnidentifiedException`, surfaced as `VEHICLE_UNIDENTIFIED` in the WS handler |
| Manual-control engage | scope/authorization | `AccessDeniedException` → `OUT_OF_SCOPE` in WS handler |
| Dangerous switch actions (arm/e-stop/etc. via `ControlActionDispatcher`) | 600ms hold-to-fire | `control-action-dispatcher.ts` `DANGEROUS_HOLD_MS` |
| Parameter writes | explicit `consent` flag, every tier | `AssetParameterController` / `ParameterWriteRequest.consent` |
| Command TX to real hardware (vs SITL) | operator go, per MAVLINK-COMMANDS-CONTEXT constraints | process-level, not a code gate found in this pass |
| Emergency stop on a rover | Hold mode, not disarm | `MavlinkFlightCommander.emergencyStop`, FLEET-RADIO R4b |

---

## 4. Session/link layer

### 4a. `DefaultPeerDirectory` — uncommitted address-flap warning

`git diff -- drone-link/mavlink-core/src/main/java/com/drones/mavlink/session/DefaultPeerDirectory.java` (uncommitted at time of writing) adds:

- `FLAP_WINDOW = Duration.ofSeconds(3)`, `WARN_INTERVAL = Duration.ofSeconds(30)`, a `lastFlapWarning` map.
- `warnOnAddressFlap(PeerId, Peer previous, MavFrame frame)`: logs a `WARNING` when a peer's transmit address changes **within** the flap window — distinct from an ordinary >3s-gap reconnect (e.g. a DHCP lease move, which is expected and silent). Message text: *"MAVLink sysid {0}/comp {1} is transmitting from two addresses at once: {2} then {3}. Everything sent to this peer follows the most recent one, so a command stream is being split between them. Two vehicles sharing one sysid is the usual cause; a NAT or DHCP rebinding is the benign one."*
- Called from `recordFrame` **after** `peers.compute(...)` completes — deliberately outside the compute lambda to keep the lambda side-effect-free.
- This directly operationalizes FLEET-RADIO's sysid-collision class of bugs (two devices sharing one sysid silently splitting a command stream) as an observable warning rather than a silent hazard.

### 4b. sysid/compid handling

`SysId`/`CompId`/`PeerId`/`VehicleClass` are the core identity types (mavlink-core MODULE.md). Every peer is keyed by `PeerId` (sysid+compid+link). FLEET-RADIO R5 added a sysid parameter-write endpoint (the vehicle's own `SYSID` param); R6 added readiness checks on `SYSID_MYGCS` and `RC_OPTIONS` bit 1 so a vehicle that doesn't know to accept this platform's GCS identity, or hasn't enabled RC-override-via-companion, is flagged before an operator tries to fly it.

### 4c. Zero-config `:14550` lobby

Per ZERO-CONFIG-ONBOARDING-CONTEXT (Z1-Z5, merged 2026-09-01): a standing MAVLink UDP listener binds `:14550` **at application boot**, independent of any asset being configured yet, replying to any vehicle's `HEARTBEAT` with a GCS heartbeat so unconfigured vehicles show up in a Discovery Inbox. This is the "lobby" — the session/link layer (`UdpListenLink`, `MavlinkSession`) is already running before any `Device`/`Asset` exists to claim a peer.

### 4d. Heartbeat / link-loss detection

- `HeartbeatService` (§2d) computes `isConnected(PeerId)` from peer last-heard time.
- `MavlinkSession.onLinkFailure(BiConsumer<LinkId, IOException>)` (mavlink-core session package, line 169-170): registers a failure listener; **default is a no-op** ("null object" pattern — line 76-79 comment: "a session nobody has called onLinkFailure on behaves exactly as [before]"). The listener is invoked at line 265, guarded by a comment referencing "shutdown must not masquerade as failure" (line 243) — i.e. a deliberate `stop()` must not fire the failure callback.
- This is the FLEET-RADIO R4 fix for the architecture-audit's "silent MAVLink link failure" finding (F7): previously a transmit-path failure had no listener at all; now `MavlinkSession` exposes a hook, and `LinkHealth.Health` (record at `LinkHealth.java:28`: `peerId, connected, lastHeard, received, lost, dropRate`) carries `PeerId` so failures are attributable to a specific peer, not anonymized across a link.
- Whether every production caller has actually **registered** a non-default `onLinkFailure` listener (vs. just having the hook available) was not verified in this pass — worth a targeted grep in the implementation wave (`grep -rn onLinkFailure` outside mavlink-core itself).

---

## 5. rover-sim harness

`infra/rover-sim/` host-compiles the real firmware sources (`~/Arduino/ardupoilot-start/`, **outside this git repo**) against shims (`Arduino.h`, `WiFiUdp.h`, `IPAddress.h`) so firmware logic can be regression-tested without hardware.

### 5a. `make check` targets (Makefile, full read)

`all` builds 6 binaries: `rover_host`, `wire_test`, `session_test`, `motor_test`, `command_test`, `link_test`. `check: wire session motors commands link`.

| Target | Binary | Covers | Status |
|---|---|---|---|
| wire | `wire_test` | frame codec | passes (per MAVLINK-COMMANDS-CONTEXT state-at-start) |
| session | `session_test` | link/session lifecycle, arming edge | **does not compile** — see 5c |
| motors | `motor_test` | motor driver | passes |
| commands | `command_test` | param protocol, message-interval, mode table, aux function, COMMAND_ACK | untracked, new this wave (5b) |
| link | `link_test` | reply addressing, transmit-failure recovery | untracked, new this wave (5d) |

### 5b. `command_test.cpp` / `command_fixture.py` / `command_verify.py` (untracked, full read)

Three-file pattern: `command_fixture.py` builds two pymavlink-encoded RX fixture files (`cmd_a.bin` = cold link, `cmd_b.bin` = after first controller tick — "one of the things under test is what the firmware does BEFORE it has any telemetry to report"); `command_test.cpp` feeds them through the real firmware's `MavlinkUdpLink`+`ParameterStore` and dumps `cmd_frames.bin`; `command_verify.py` decodes that output with **`robust_parsing=False`** pymavlink against the reference dialect — deliberately strict, "a wrong CRC_EXTRA seed, a wrong payload length or a field at the wrong offset fails the run instead of being skipped."

`command_verify.py` checks (all currently pass per file content, 10 `COMMAND_LONG`s total):
- `MAV_CMD_REQUEST_MESSAGE`: `AUTOPILOT_VERSION` accepted; `VFR_HUD` **refused before telemetry exists** (`MAV_RESULT_UNSUPPORTED`), **accepted once a snapshot exists**; requested message is sent *after* its own ACK (ArduPilot ordering).
- `AUTOPILOT_VERSION` content: `flight_sw_version` decodes to 1.1.0/BETA(128); capabilities advertise `PARAM_FLOAT`+`MAVLINK2` only — explicitly **no mission or COMMAND_INT support claimed**, matching what's implemented.
- `MAV_CMD_SET_MESSAGE_INTERVAL`: a pushed stream accepted, an unpushed one refused.
- `MAV_CMD_DO_SET_MODE`: RTL refused (no GPS), HOLD accepted.
- `MAV_CMD_DO_AUX_FUNCTION`: RTL-switch refused, arm-switch accepted, unknown function refused.
- Parameter protocol: read-by-name and read-by-index(0) both answered; a nonexistent parameter met with **silence** (per MAVLink protocol, not an error reply); a read-only param (`MAV_SYSID`) write attempt replies with its **unchanged** value rather than silence ("the only mechanism MAVLink gives a vehicle for saying no"); `RVR_MAX_STR` write is clamped and the **clamped** value is what's echoed back; `RVR_REV_COAST` declared `UINT16`, not float.
- `PARAM_REQUEST_LIST`: every parameter appears exactly once, consistent `param_count`, gapless `param_index`.
- `STATUSTEXT`: refusals (e.g. RTL refused) are explained in human text, not just a result code; all severities are valid `MAV_SEVERITY` values.

### 5c. `session_test.cpp` — confirmed compile failure

`FakeMotorDriver` (session_test.cpp:50-52) still declares `armEsc(uint32_t) override` and `cancelEscArming() override`, and line 115 asserts `motors.armEscCalls == 1` ("the arm edge sends the esc handshake exactly once"). **Confirmed against the actual firmware interface**: `~/Arduino/ardupoilot-start/IMotorDriver.h` (41 lines, full read) no longer declares these methods at all — current surface is `begin()`, `apply(throttle, steering, dtMs)`, `stop()`, `appliedThrottle()`, `appliedSteering()`, `enable()`/`disable()` (STBY control), `enabled()`, `estimatedSpeedMps()`. The ESC handshake was removed after the L298N burnout (2026-08-23) in favor of the reversal dead-time/coast state machine. **`session_test.cpp` will fail to compile** with a "does not override any base class virtual method" error on both `armEsc` and `cancelEscArming`. This is a pre-existing repair item, not something introduced by the command-surface wave; it blocks `make check` from succeeding end-to-end until fixed.

### 5d. `link_test.cpp` (untracked, full read, 210 lines)

Tests two properties of `MavlinkUdpLink`, independent of the command/param surface:

1. **Reply addressing correctness** — the rover replies to whoever it last heard from, not a compile-time constant: transmits first, unprompted, to the *configured* `peerHost:14550`; once a peer sends from a different address (simulating a DHCP lease move), all replies follow *that* address; a second move is followed again; a datagram whose sender address can't be attributed does **not** unlearn the previously-learned peer. Comment: "a vehicle that only ever transmits to a compile-time IP goes permanently mute."
2. **Transmit-failure recovery, escalated not looped** — a short run of `endPacket()` failures (5) is tolerated with no socket rebind and no radio reconnect ("a short run of failures is tolerated without tearing anything down"); a sustained run (100000) triggers exactly one socket rebind and exactly one radio reconnect ("re-associated exactly once, not on every failure"); once transmits succeed again, the reconnect counter stops climbing and traffic resumes. Uses `CountingNetwork`/`WiFiUDP::failEndPacketFor` test doubles.

This is the rover-sim-side counterpart to the platform-side `onLinkFailure`/`LinkHealth` work (§4d) — the firmware's own reconnect logic, tested independently of the Java session layer.

### 5e. `esc_arm_tune.py` (untracked, 60 lines, live-tuning utility, not a test)

A field tool for retuning the ESC power-up handshake **live over MAVLink, no reflash** — steps expressed as `percent/holdMs` against `EscArmingConfig` (0=minPulseUs, 50=neutral, 100=maxPulseUs), sent via `PARAM_SET`. Notably: confirmation is read from **the rover's own serial log on the next arm**, not from a `PARAM_VALUE` echo — because `PARAM_VALUE` replies go to whichever address the rover last heard from (§5d's reply-addressing behavior), which after running this script is the script itself, not the app; the script's own docstring flags that arming from the app afterward re-points the reply address back. This is a real operational gotcha this harness surfaces: **a diagnostic tool can transiently steal the rover's telemetry destination** simply by sending it a frame.

### 5f. `shim/IPAddress.h` (untracked, 39 lines, full read)

Minimal host shim for Arduino's `IPAddress`/`String` pair — only `operator==`, `toString()`, `networkOrder()` (explicit big-endian storage matching `sockaddr_in` order) — exists purely so `WiFiUdp.h`'s real signatures compile on the host; not itself a test.

### 5g. README (full read) — stated current coverage & one flag

Documents the shim architecture (mermaid diagram), what `make wire`/`session`/`motors`/`commands` each check (each found a real bug historically), `make run`/`drive_rover.py` for live SITL driving, a "driving it from the browser" section (Controller drawer, on-screen source), and an end-to-end curl example. **Flag**: the README states a telemetry-only asset currently still needs a paired video device to operate — this predates Z1-Z5 (zero-config onboarding, merged 2026-09-01, which explicitly decoupled engage/telemetry from video per its B4 closure) and should be treated as **possibly stale**; worth a one-line doc fix or a quick behavioral check in the implementation wave.

---

## 6. Gap list

| Gap | Detail | Owning module |
|---|---|---|
| `session_test.cpp` doesn't compile | `FakeMotorDriver` still overrides removed `armEsc`/`cancelEscArming` (§5c) | `infra/rover-sim` (test harness repair; firmware itself already correct) |
| No mission upload/execution | `MissionService` doesn't exist; `CommandService.sendInt`, `MavlinkCoreSettings.Mission` are modelled but unconsumed (per MISSIONS-PLAN gap table); MAV_CMD `MISSION_*` family entirely unimplemented | `contexts/vision-flight` (new context or service) + `drone-link/mavlink` + `vision-web` |
| Platform-side `MAV_CMD_REQUEST_MESSAGE`/`SET_MESSAGE_INTERVAL` callers not confirmed | Firmware supports both (proven by `command_verify.py`); no platform caller found wired through `MavlinkFlightCommander` — `capabilities()` reads cached telemetry rather than actively requesting `AUTOPILOT_VERSION` | `drone-link/mavlink` |
| Zero retries on one-shot commands | Deliberate design (§1a) but means a single lost `COMMAND_LONG` or `COMMAND_ACK` on a lossy link silently no-acks with no automatic retry — operator must notice and re-issue | `drone-link/mavlink` (policy decision, may need revisiting for keyboard/controller UX where a missed arm command is a bad experience) |
| `onLinkFailure` listener registration unverified | Hook exists (§4d) but this pass did not confirm every production `MavlinkSession` actually registers a non-default listener, vs. the hook merely being available | `drone-link/mavlink` / `vision-app` wiring |
| Single-system-per-decoder assumption | `MavlinkTelemetryDecoder` explicitly ignores a second system sharing one UDP port (line 225) rather than routing per-system | `drone-link/mavlink` (fine for one-vehicle-per-adapter-instance today; a gap if a fleet ever shares a port) |
| No `VirtualRcInputService` deep verification this pass | Third `RcSourceKind` (on-screen drag pads) referenced by CONTROLLER-UX-PLAN but not read in full for this report | `vision-web` (low risk — same seam as the other two sources) |
| README rover-sim video-pairing claim possibly stale | States telemetry-only asset needs a paired video device; Z1-Z5 (zero-config) decoupled this | `infra/rover-sim` docs |
| `esc_arm_tune.py` reply-address hijack is an unguarded operational hazard | Running the tuning script transiently steals the rover's telemetry destination until the app re-arms (§5e) — fine for a deliberate field tool, but is the same class of bug the `DefaultPeerDirectory` flap-warning (§4a) exists to catch; worth deciding whether the flap warning should fire on this too, or whether tuning traffic should be excluded | `drone-link/mavlink-core` (policy) / `infra/rover-sim` (tool UX) |
| Command TX to real hardware operator-gating is process-level only | No code-level gate found distinguishing "SITL/rover-sim target" from "real hardware target" for command dispatch — the constraint is currently a human process rule (MAVLINK-COMMANDS-CONTEXT), not enforced in code | `drone-link/mavlink` / `vision-app` (if a code gate is wanted) |
| Keyboard/controller-driven **mission or waypoint** commands | Everything inventoried above is real-time manual control or single-action commands; there is no keyboard/controller-triggered path to any MAV_CMD in the mission family, nor a UI affordance for it | `vision-web` + `contexts/vision-flight` (depends on Missions gap above being resolved first) |

---

## Sources

Docs read: `docs/plans/README.md`, `docs/plans/active/RC-CONTROL-PLAN.md`, `CONTROLLER-UX-PLAN.md`, `OPERATOR-CONTROL-CONTEXT.md`, `VEHICLE-CONTROL-PROFILES-CONTEXT.md`, `MISSIONS-PLAN.md`, `FLEET-RADIO-PLAN.md`, `ZERO-CONFIG-ONBOARDING-CONTEXT.md`, `MAVLINK-CORE-PLAN.md`, `MAVLINK-COMMANDS-CONTEXT.md`; `drone-link/mavlink-core/MODULE.md`, `drone-link/mavlink/MODULE.md`, `contexts/vision-flight/MODULE.md`.

Code verified: `MavlinkFlightCommander.java`, `MavlinkManualControlSender.java`, `MavlinkTelemetryDecoder.java`, `CommandService.java`, `ParameterService.java`, `HeartbeatService.java`, `LinkHealth.java`, `MavlinkSession.java`, `DefaultPeerDirectory.java` (git diff), `ManualControlWebSocketHandler.java`, `FlightCommandController.java`, `AssetParameterController.java`, `rc-input.service.ts`, `keyboard-rc-input.service.ts`, `manual-control-client.ts`, `control-action-dispatcher.ts`; `infra/rover-sim/{README.md,Makefile,command_test.cpp,command_fixture.py,command_verify.py,link_test.cpp,session_test.cpp,esc_arm_tune.py,shim/IPAddress.h}`; `~/Arduino/ardupoilot-start/IMotorDriver.h`.
