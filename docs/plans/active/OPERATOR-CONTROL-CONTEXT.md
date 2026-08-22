# OPERATOR-CONTROL — working context

Started 2026-08-21. Task: the operator owns a **RadioMaster TX12 (EdgeTX)**. Audit the controller
flow we have, decide what to add, compare against other ground stations, and generalize it so it is
useful on **ArduPilot, INAV and Betaflight** — creating adapter modules where a firmware has no
native path for operator input. Adapter pattern, operator-first.

Companion docs: [RC-CONTROL-PLAN.md](RC-CONTROL-PLAN.md) (Phase 0/1 design),
[RC-CONTROL-PHASE1-PLAN.md](../done/RC-CONTROL-PHASE1-PLAN.md) (frozen impl plan),
[FC-INTEGRATIONS-PLAN.md](../done/FC-INTEGRATIONS-PLAN.md) (firmware decode facts),
[ANY-DRONE-PLAN.md](../../conclusions/ANY-DRONE-PLAN.md) (probe→diagnose→remediate loop),
[CREW-CONTROL-PLAN.md](CREW-CONTROL-PLAN.md) (owns who-has-control arbitration — do not re-invent).

---

## 1. What exists today — verified by reading the code, not the plans

### 1.1 The flow, end to end

```
RadioMaster TX12 (EdgeTX "USB Joystick" mode — RF OFF)
   └─ Gamepad API, rAF poll               RcInputService            vision-web core/rc
        └─ axes[] / buttons[] signals
             └─ "Controller" tool-rail drawer   rc-monitor.ts        vision-web features/fly
                  └─ "Take control"  ──WS──►  /ws/manual-control
                       └─ ManualControlWebSocketHandler             vision-api
                            └─ DefaultManualControlService          vision-flight (application)
                                 ├─ scope gate + audit
                                 ├─ ChannelMap.defaultMap()  ── axes/buttons → µs per channel
                                 └─ ManualControlPort.send(link, RcChannels)
                                      └─ MavlinkManualControlSender  drone-link/mavlink
                                           └─ mavlink-core ManualControlService
                                                └─ RC_CHANNELS_OVERRIDE #70 @33 Hz, shared socket
```

### 1.2 The pieces, and what each is actually responsible for

| Layer | Type | Responsibility |
|---|---|---|
| kernel | `FlightState.firmware` | decoded from `HEARTBEAT.autopilot` — already tells us which firmware we are talking to |
| flight/domain | `RcChannels(List<Integer> microsByChannel)` | 1..18 channels, µs, `RELEASE=0` / `IGNORE=0xFFFF` sentinels |
| flight/domain | `ControlBinding(source, sourceIndex, rcChannel, min, center, max, deadband, reversed)` | one axis/button → one channel, linear calibration, `toMicros()` |
| flight/domain | `ChannelMap(List<ControlBinding>)` | `apply(axes, buttons) → RcChannels`; `defaultMap()` = axes 0..3 → CH1..4, buttons 0..3 → CH5..8 |
| flight/domain | `ManualControlPort` | out-port: `supports(Device)` / `engage(Device) → link` / `send(link, channels)` / `release(link)` |
| flight/application | `DefaultManualControlService` | scope gate, audit, **one session per app**, 300 ms input-loss watchdog, device resolution |
| vision-api | `ManualControlWebSocketHandler` | raw WS `/ws/manual-control`, frozen JSON frames engage/channels/release ↔ engaged/denied/ack/released/watchdog |
| drone-link/mavlink | `MavlinkManualControlSender` | the **only** `ManualControlPort` impl; borrows the RX gateway socket; `supports()` delegates to `MavlinkTelemetrySource.supports()` |
| vision-web | `RcInputService`, `manual-control-client.ts`, `rc-monitor.*` | Gamepad read, WS client, latency/rate readout, engage/release UI |
| vision-app | `VisionRcProperties` (`vision.rc.*`) | watchdog 300 ms, override 33 Hz (clamp 10..50), release burst 3 |

### 1.3 What is genuinely good and must be kept

- **`RcChannels` in microseconds is already the universal vocabulary.** MAVLink `RC_CHANNELS_OVERRIDE`,
  MSP `MSP_SET_RAW_RC` and CRSF `RC_CHANNELS_PACKED` are all trivially derived from µs-per-channel.
  The domain therefore needs **no change** to generalize across firmwares — the generalization is
  entirely a matter of port selection plus new adapters.
- The **watchdog is in the application layer** with an injected clock+scheduler — deterministic,
  testable, transport-independent. Correct placement; every new adapter inherits it for free.
- **Latest-wins mailbox + fixed-rate sender thread** decouples browser jitter from wire cadence.
- Scope gate + audit on every engage/release/watchdog trip.
- Release semantics (sentinel burst, then stop) so the aircraft's own failsafe takes over.

### 1.4 Verified gaps — the reason this task exists

**Firmware reach**

| # | Gap | Evidence |
|---|---|---|
| G1 | Exactly one transport exists (MAVLink RC override). Our own repo already records that **INAV's MAVLink is transmit-only** and **Betaflight's MAVLink is telemetry-only** (`infra/edge/elrs-backpack.md`) — so on 2 of the 3 target firmwares the Take-control button cannot work at all | `infra/edge/elrs-backpack.md`, module index (`drone-link/` holds only `mavlink`, `mavlink-core`) |
| G2 | **`supports()` answers the wrong question.** `MavlinkManualControlSender.supports(device)` delegates to `MavlinkTelemetrySource.supports(device)` — i.e. "is this a MAVLink UDP device", *not* "does this firmware accept operator input". A Betaflight aircraft streaming MAVLink telemetry answers `true`, engages, shows **"Live"** in the cockpit, and the aircraft never moves. That is a fabricated read — the exact failure mode CLAUDE.md and this codebase's poka-yoke doctrine forbid | `MavlinkManualControlSender:supports`, `MavlinkTelemetrySource:117-127` |
| G3 | No verification that the override landed. We already decode `RC_CHANNELS` (for RSSI) — comparing *what the FC reports it received* against *what we sent* is nearly free and is the single most honest thing this feature could show | `FlightStatusState`, `MavlinkTelemetryDecoder` |

**Operator flow**

| # | Gap | Evidence |
|---|---|---|
| G4 | `ChannelMap` is a domain type with calibration fields that **nothing ever configures** — `DefaultManualControlSession` hardcodes `ChannelMap.defaultMap()`. No calibration, no TX-mode (1/2/3/4) choice, no reverse, no expo, no per-model saving, no persistence | `DefaultManualControlService:...channelMap = ChannelMap.defaultMap()` |
| G5 | Only 4 axes + 4 buttons → channels 1..8. A TX12 exposes more (pots S1/S2, 6-pos switch); everything beyond index 3 is silently dropped | `ChannelMap.defaultMap()` |
| G6 | **No pre-engage safety interlock.** Nothing checks throttle position, armed state or flight mode before engaging. Engaging with the throttle stick anywhere but idle on an armed aircraft commands that throttle instantly | `DefaultManualControlService.engage` |
| G7 | Switches drive aux channels only — no button→command bindings (arm / disarm / RTL / mode), which is what makes a radio useful even where channel override is impossible | `flight-command-panel-logic.ts` has `'mode' \| 'arm' \| 'disarm'` but nothing links the radio to it |
| G8 | One session **per application**, not per asset — two operators on two different aircraft collide. Documented as an intentional Phase-1 simplification; it is now a real limit | `DefaultManualControlService` class javadoc |
| G9 | `engaged.rateHz` is a hardcoded constant in vision-api, not the adapter's live rate — the cockpit displays a number that can be untrue | `ManualControlWebSocketHandler` class javadoc |
| G10 | Latency is measured and displayed but **acted on by nothing** | `manual-control-logic.ts#computeLatencyMs` |
| G11 | Take-control lives inside a tool-rail drawer; closing the drawer releases control. The engaged state has no cockpit-wide presence and no always-visible abort | `rc-monitor.html`, `fly-logic.ts#ToolRailPanelId` |
| G12 | The WebSocket surface is outside LIVE-SCOPE W1's authority guard, which only inspects `@RestController` handlers | `LIVE-SCOPE-PLAN.md` §3 W1 |

### 1.5 Constraints inherited (not to be re-litigated)

- **EdgeTX USB-Joystick mode ⊥ RF.** Radio-as-joystick means the platform is the *sole* control path.
- **Phase 2 (real airframe) is gated on explicit user go.** SITL is the verification target.
- **Who-has-control arbitration belongs to `CREW-CONTROL-PLAN`** (`ControlClaim`, `AssignmentRole{PIC,OBSERVER}`) — specced, not built. This task must not fork it.
- Adapter-selection idiom already exists and must be mirrored: `VideoSourceRegistry` — `List<Port>` +
  first `supports()` match + `UnsupportedProtocolException`. Same shape as `List<TelemetrySourcePort>`.
- Device model needs no new field for a second control path: an `Asset` already has **1..n Devices**,
  each with its own `StreamDescriptor(protocol, uri, options)`. A separate MSP/CRSF control endpoint
  is naturally *another Device on the same Asset* — which the existing
  `firstCommandableDevice(devices)` loop already resolves without modification.

---

## 2. Firmware wire facts — verified against firmware sources

**This section corrects a premise this repo has been carrying.** `infra/edge/elrs-backpack.md` says
INAV's MAVLink is "transmit-only" and that Betaflight has no MAVLink below 2025.12. Both statements
are true **about the telemetry module** (`telemetry/mavlink.c`) and both are **misleading about
control**, because in each firmware inbound RC arrives through a *separate* file — a serial-RX
driver, `rx/mavlink.c`. Gap G1 as originally written was wrong; the corrected G1 is in §2.4.

### 2.1 What each firmware actually accepts

| | MAVLink `RC_CHANNELS_OVERRIDE` #70 | MAVLink `MANUAL_CONTROL` #69 | MSP `MSP_SET_RAW_RC` (200) |
|---|---|---|---|
| **ArduPilot** | **Yes, native, always on**, 16 ch | **Yes** — Copter/Plane/Rover/Sub, different axis maps per vehicle | n/a (no MSP) |
| **INAV** | **Yes** — dedicated `SERIALRX_MAVLINK` driver (`src/main/rx/mavlink.c`), 18 ch, compiled in by default | No handler | **Yes** — sole-RX *or* selective override |
| **Betaflight** | **Yes, since ~2025.12.0-beta** — `src/main/rx/mavlink.c`, built for ELRS MAVLink mode, in unified targets by default | Not implemented | **Yes** — sole-RX *or* selective override |

### 2.2 What enables it, and what silently refuses it

| Firmware | Enable | Silent-refusal modes | Stale-input timeout |
|---|---|---|---|
| ArduPilot | default-on | sender must equal **`SYSID_MYGCS`**; **`RC_OPTIONS` bit 1** (`IGNORE_OVERRIDES`) kills it outright | **`RC_OVERRIDE_TIME`**, default **3.0 s** |
| INAV | `serialrx_provider = MAVLINK` (MAVLink *becomes* the receiver) | wrong `receiver_type`/provider ⇒ frames parsed and discarded. **Sender sysid is deliberately NOT checked** | MSP-as-RX 200 ms; MSP-override ~700 ms |
| Betaflight | `serialrx_provider = MAVLINK` (BF ≥ 2025.12) | same; older BF has no `rx/mavlink.c` at all | MSP override channel freshness **300 ms** |

### 2.3 The finding that reshapes the whole feature — **shared control**

Both INAV and Betaflight ship a **selective, switch-gated RC override** that runs *alongside a live RF
receiver*:

- **Betaflight** `rx/msp_override.c` reads the real receiver **and** the MSP buffer every cycle and
  substitutes per channel only when (a) a `BOXMSPOVERRIDE` flight-mode switch is active, (b) that
  channel's bit is set in `msp_override_channels_mask`, and (c) the MSP data is fresh (300 ms).
  `msp_override_failsafe` chooses whether a real RX is mandatory at all.
- **INAV** `rx/msp_override.c` + `msp_override_channels` + `BOXMSPRCOVERRIDE`, explicitly guarded to
  run only when `receiverType != RX_TYPE_MSP` — i.e. *by design*, alongside a real bound receiver.
- **ArduPilot** reaches the same posture natively: RC aux function **46 ("RC Override Enable")** lets a
  switch on the pilot's own radio decide whether MAVLink overrides are honoured at all.

**RC-CONTROL-PLAN declared this middle ground impossible** ("there is no aux/assist while the pilot
keeps RF sticks... Topology A, forced by the hardware"). That conclusion was correct *only* for the
EdgeTX-USB-joystick input method. It is **false for the aircraft side**: all three firmwares support
partial, switch-arbitrated control sharing, and the deadman is a **physical switch on the pilot's
radio** — strictly safer than any ground-side watchdog, because it needs no link to work.

### 2.4 Corrected gaps

| # | Corrected statement |
|---|---|
| **G1′** | All three firmwares accept `RC_CHANNELS_OVERRIDE`. The gap is not protocol — it is **configuration** (INAV/BF need `serialrx_provider=MAVLINK`) and **honesty** (we cannot see whether they have it). This is precisely ANY-DRONE-PLAN's probe→diagnose→remediate loop, applied to control |
| **G13** | **Latent sentinel bug.** `RcChannels.released(n)` emits `0` for every channel. On ArduPilot chan 1-8 that correctly releases, but on **chan 9-16 both `0` and `65535` mean "ignore this field"** — the release sentinel there is **`65534`**. Today v1 only uses ch1-8 so nothing is broken; the moment we honour D-full-width release past ch8, un-released channels would stay overridden until `RC_OVERRIDE_TIME` expired. ArduPilot also reads **only chan1..16** — there is no chan17/18 handling, so our `[1,18]` domain range over-promises |
| **G14** | We send as GCS sysid 255 and never check the vehicle's `SYSID_MYGCS`. If an operator has changed it, every override is dropped in silence |

### 2.5 The ground-side injection routes (for later, not now)

- **EdgeTX `Master/Serial` trainer** (`TRAINER_MODE_MASTER_SERIAL`, `radio/src/trainer.cpp`) takes
  **standard SBUS — 100000 baud 8E2, 25-byte frames, 16×11-bit** — on an **AUX UART pad**, and exposes
  the channels as mixer sources **TR1..TR16**, so a physical switch in the model's mixer can arbitrate
  per channel. It coexists with a transmitting RF module (inferred with high confidence from EdgeTX
  issue #6453, where the radio crashes *only* when the RF module is off). **UNVERIFIED:** whether the
  SBUS-trainer function can bind to the radio's own **USB-VCP** port — if it cannot, this route needs
  a USB-TTL adapter, not just the radio's USB-C cable.
- **ExpressLRS standalone TX module**: documented to run with no handset, fed over **USB at 460800
  baud**, auto-starting the link when it detects inbound MAVLink. **UNVERIFIED:** whether RC *uplink*
  works in that mode — every worked example in the ELRS docs is telemetry-only.
- No documented way to inject raw CRSF `RC_CHANNELS_PACKED` into a TX module.

Both routes put the injection point **on the operator's machine**, not the server — which matters
because this platform is deployed to remote servers (`docker-compose.yml`, per CLAUDE.md). Deferred.

## 3. Decisions

Numbered so the plan doc can cite them. Firmware-reach decisions (D9+) wait on the wire-fact research.

| # | Decision | Because |
|---|---|---|
| **D1** | **`RcChannels` (microseconds, 1-based, `RELEASE`/`IGNORE` sentinels) is frozen as the universal control vocabulary.** No domain change to generalize across firmwares | MAVLink `RC_CHANNELS_OVERRIDE`, MSP `MSP_SET_RAW_RC` and CRSF `RC_CHANNELS_PACKED` are all one linear map away from µs-per-channel. The existing domain is already general; only port *selection* and *adapters* are missing |
| **D2** | **`ManualControlRegistry` in `vision-flight`'s application layer** — `List<ManualControlPort>`, first `supports(...)` match, a typed refusal when none matches. `DefaultManualControlService` resolves through it instead of holding one port | Exactly mirrors `VideoSourceRegistry` (perception) and `List<TelemetrySourcePort>` (UsageTracker) — the open/closed extension point this codebase already proved twice. Adding a firmware means adding an adapter to the wiring, not editing a service |
| **D3** | **`supports()` becomes honesty-shaped**: it answers "will this aircraft actually act on operator input", not "is this device on my protocol". The observed `FlightState.firmware` (already decoded from `HEARTBEAT.autopilot`) is part of the question | G2 + S4. Today a Betaflight aircraft answers `true`, the cockpit says **Live**, and nothing moves. Every other honesty rule in this codebase (`AssetAttention.sourceState`, "never fake a read") forbids that |
| **D4** | **Calibration is a hard gate, not a nicety.** No calibrated profile for this (device, asset) ⇒ engage is refused with the reason, and the relay emits nothing at all | S1 — QGC's `if (!calibrated) return`. "Uncalibrated but with default scaling" is the dangerous middle ground: it *looks* like it works |
| **D5** | **One canonical channel map (mode 2) + a TX-mode transform**, never four stored maps | S2. Four parallel maps drift apart the first time one axis assignment changes |
| **D6** | **Buttons bind to *commands* (arm/disarm/mode/RTL) by default, not to aux channels.** Aux-channel binding stays available but is opt-in | S3 — ArduPilot's own documentation says not to let a joystick own the mode/aux channels. Our current `defaultMap()` does exactly that |
| **D7** | **Sent-vs-received channel monitor**: show what we sent alongside what the FC reports in `RC_CHANNELS`, and flag divergence | S6 — the single biggest capability missing from *every* station surveyed, and nearly free for us since we already decode `RC_CHANNELS` |
| **D8** | **Release is full-width**: the release burst covers every channel the session ever touched, never a subset | S5 — ArduPilot #32862, where a partial override timing out leaves stale values on un-refreshed channels |
| **D9** | **Who-has-control arbitration is NOT built here.** This task consumes `CREW-CONTROL-PLAN`'s `ControlClaim` if it lands first, and otherwise keeps today's single-session guard | That plan owns PIC/OBSERVER + TTL claims. Forking it would create two competing authority models for the same aircraft |

| **D10** | **Shared control is the headline feature, not full control.** The primary operator model becomes: *the pilot keeps their radio and their RF link; a switch on the radio hands N channels to the platform; flipping it back takes control instantly.* Full control (platform as sole path) stays for SITL and no-radio aircraft | §2.3. The deadman becomes a physical switch that needs no link to work — strictly safer than any ground-side watchdog, and it is supported natively by all three firmwares |
| **D11** | **No new protocol module is needed for reach.** `RC_CHANNELS_OVERRIDE` covers all three firmwares. `drone-link/msp` is worth building for the *shared-control* path on INAV/Betaflight, but it is Layer 2, not the unblocking step | §2.1 — the original "Betaflight has no operator-control path" premise is outdated |
| **D12** | **Fix the sentinel and range truth in the domain**: chan 9-16 release sentinel is **65534**, not 0; ArduPilot reads only chan 1..16 | G13 — a latent bug that fires the moment we widen past channel 8, which D8 requires |
| **D13** | **Configuration state is part of readiness.** `serialrx_provider`, `RC_OPTIONS` bit 1, `SYSID_MYGCS` and the override-enable switch are read/observed and reported before engage, with a remediation string | G1′/G14 — this is ANY-DRONE-PLAN's probe→diagnose→remediate loop applied to control; it is what turns "nothing moved" into "your FC ignores overrides, here is the parameter" |
| **D14** | **Ground-side injection (EdgeTX SBUS trainer / ELRS standalone TX) is deferred**, and recorded with its two open UNVERIFIED questions | §2.5 — the injection point is the operator's machine, not the server, which this platform's remote-deployment model makes a separate architectural problem |

### 3.1 Branch note

At the time of writing, `feat/live-scope` has **uncommitted LIVE-SCOPE W2 work** in the tree
(`StreamAccess.java` + two modified controllers). This task must not disturb it: implementation runs
in a separate git worktree off `master`, not by switching branches in place.

---

## 4. How other stations do it — and the 8 ideas worth stealing

Researched against QGroundControl (source: `src/Joystick/`), Mission Planner, ArduPilot wiki,
Auterion Mission Control, UgCS, Herelink, Skydio Remote Flight Deck, mLRS, ExpressLRS
(`elrs-joystick-control`), and the Betaflight/INAV configurator Receiver tabs.

### 4.1 The findings that change our design

| # | Finding | Source | What it means for us |
|---|---|---|---|
| S1 | **QGC produces *zero* output until calibration completes** — `if (!_joystickSettings.calibrated()) { return; }`, a hard gate in the poll loop. Enabling the joystick *is* the last step of calibration; there is no independent "enable" switch | `Joystick.cc` | Calibration is not a nicety. Uncalibrated ⇒ no output at all, never "output with default scaling" |
| S2 | **QGC stores the axis map canonically as Mode 2** and re-derives modes 1/3/4 by remap function, rather than four parallel maps | `Joystick.cc` `_remapFunctionsInFunctionMapToNewTransmittedMode` | One canonical `ChannelMap` + a TX-mode transform, not four saved maps that drift apart |
| S3 | **ArduPilot's own docs say: do NOT let the joystick own the mode channel or aux channels** — use a button bound to a *mode-change command* instead | `common-joystick.rst` | Our `defaultMap()` binding buttons 0..3 → CH5..8 "flight-mode switch" is against the firmware vendor's own advice. Buttons should bind to commands, not aux channels, by default |
| S4 | **`RC_OVERRIDE_TIME` (default 3 s)** is ArduPilot's own override dead-man; **`RC_OPTIONS` bit 1** makes the autopilot ignore MAVLink overrides entirely; **`MAV_GCS_SYSID`** must match the sender's sysid or overrides are refused | ArduPilot param reference, dev docs | Three *silent* refusal modes we currently cannot see. A readiness check must read these before the operator ever presses Take control |
| S5 | **Known ArduPilot defect #32862:** when overrides cover only *some* channels and then time out, fallback can consume **stale** values on the un-refreshed channels | `ardupilot#32862` | Our release burst must cover every channel we ever touched — full-width release, never partial |
| S6 | **The single biggest capability missing from every GCS surveyed** is a monitor of what the *aircraft actually received*, as opposed to what the station sent. The FC configurators (Betaflight Receiver tab, INAV) have it; no GCS does | Betaflight/INAV configurator docs, and the survey's own conclusion | We already decode `RC_CHANNELS`. Sent-vs-received is cheap for us and beats every station in the survey |
| S7 | **INAV names the axis order as a saveable string** (`AETR1234` / `TAER1234`) instead of leaving it an invisible convention | INAV Getting Started | Give `ChannelMap` a short human-readable code — diffable, pasteable, explainable |
| S8 | **UgCS publishes the whole control chain** (joystick → client → server → VSM → ground radio → air radio → autopilot) as operator-visible status, so a break is attributable to a hop | UgCS manual | Our chain is longer (browser → WS → service → adapter → UDP → FC). Name it and show where it broke |

### 4.2 Where we already beat the field

- **Numeric round-trip latency for the control path specifically**: no station in the survey shows one.
  We compute it (`manual-control-logic.ts#computeLatencyMs`) — but act on it nowhere (G10).
- **A dedicated always-present abort widget**: no station has one. QGC's "Emergency Stop" is a
  *configurable button binding*, not a guaranteed UI element. Our `RELEASE` button exists but is
  trapped inside a drawer (G11).
- **Per-engage audit with an acting identity and a visibility scope**: no surveyed station has any
  authority model beyond ArduPilot's coarse `MAV_GCS_SYSID` whitelist.

### 4.3 Confirmed prior art for the "keep the RF link" route

`kaack/elrs-joystick-control` converts a USB gamepad straight to **CRSF/ELRS packets over serial**,
bypassing MAVLink and any GCS. ExpressLRS discussion #3492 argues the same direction from the other
side: MANUAL_CONTROL/RC_OVERRIDE over a shared telemetry link is bandwidth-hostile, and the
translation belongs down in the link hardware. mLRS makes the same structural point — keep stick data
on the dedicated low-latency RC channel, never in the telemetry stream.

This is the strongest available answer to "Betaflight has no native operator-control path": don't
send it a control protocol at all — inject channels into the RF link, where every firmware already
accepts them as ordinary RC.
