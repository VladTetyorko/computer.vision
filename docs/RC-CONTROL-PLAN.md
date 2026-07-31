# RC-CONTROL-PLAN — piloting the drone from a plugged-in RC transmitter

Status: **Phase 0 in progress** (started 2026-07-31). Owner: RC relay.

Goal: let an operator plug a physical RC transmitter (a RadioMaster, 12 channels, EdgeTX) into the
machine running the Fly cockpit and have its sticks + switches **relayed to the drone** over the
existing MAVLink link. This is the next rung above I-e Stage 2 (`arm`/`disarm`/`setMode`), and the
highest-stakes command-TX path in the system.

## The hardware fact that shapes everything

**EdgeTX "USB Joystick (HID)" mode and the transmitter's RF module are mutually exclusive.** The
moment the RadioMaster is a USB joystick feeding the platform, it is **not transmitting to the drone
over RF**. Confirmed on the target radio. Therefore:

- There is **no "aux/assist while the pilot keeps RF sticks"** middle ground. Using the radio as a
  joystick means the platform is the **sole control path** — it must carry sticks *and* switches, or
  the drone has no control input at all. This is **Topology A (platform = ground control station,
  radio = USB input device)**, forced by the hardware, not a choice.
- This is a proven, standard architecture — it is exactly how QGroundControl flies a vehicle from a
  USB joystick over a telemetry/4G link (`MANUAL_CONTROL` / `RC_CHANNELS_OVERRIDE`).

### The real-world gotcha (drone side)

If the airframe has an ELRS/Crossfire **receiver bound to this radio**, putting the radio into
joystick mode = the receiver sees signal loss = the aircraft's **own RC-failsafe fires**
(RTL/Land/Disarm). So Topology A on a real airframe requires one of:
- a drone with **no dependence on that radio's RF** (companion computer / 4G / telemetry-only
  MAVLink), or
- FC failsafe reconfigured so RC-override-over-MAVLink is the accepted control source and *its* loss
  (not RF loss) triggers RTL/Land.

**In SITL there is no receiver, no RF, no failsafe race** — `radio-as-joystick →
RC_CHANNELS_OVERRIDE → ArduPilot SITL` is a complete, honest end-to-end loop with zero airframe risk.
That is why every phase before a real vehicle is SITL-only (`infra/sitl/`), exactly like I-e.

## Current state (what already exists, grounded)

- `adapter-mavlink` sends today via `MavlinkFlightCommander implements FlightCommandPort` —
  **request→ack** one-shots (`MAV_CMD_DO_SET_MODE`, `MAV_CMD_COMPONENT_ARM_DISARM`, `capabilities`),
  one connection per send + `COMMAND_ACK` await, sharing the gateway socket via `MavlinkSocketHub`.
- RC relay is the **opposite shape**: a continuous **fire-and-forget stream** (~20–50 Hz), **no
  per-message ack**, plus a **failsafe watchdog**. So it needs a *new streaming send seam* in
  `adapter-mavlink`, not another `FlightCommandPort` method.
- `vision-web` Fly cockpit composes tool-rail drawers via `PanelState` (frozen `ToolRailPanelId` in
  `features/fly/fly-logic.ts`); `flight-command-panel` is the closest analog (a capability-gated,
  audited command-TX drawer).

## Phases (safe → real)

### Phase 0 — browser channel monitor (this cut; **zero drone risk**)
Read the transmitter in the Fly cockpit and show all its channels live. Proves input fidelity and
turns "is the latency OK?" into a measured number. Nothing touches the backend or the drone.

- **Input source: the Gamepad API** (not WebHID yet). Rationale: EdgeTX USB-Joystick mode enumerates
  as a standard HID gamepad, so `navigator.getGamepads()` exposes its sticks/pots as `axes` and its
  switches as `buttons` with **zero device-specific report parsing** — robust across EdgeTX channel
  maps, and it cannot be mis-decoded. WebHID (raw report, event-driven, lower latency, >8 analog
  axes) is the **Phase-1 fidelity upgrade** once we need exact PWM and every pot as an analog axis;
  the Phase-0 service surface is source-agnostic so WebHID slots behind it without touching the UI.
- **Frozen contract (Phase 0):**
  - `core/rc/rc-input-logic.ts` (pure, unit-tested): `RcDeviceInfo`, `RcSnapshot` (axes:number[],
    buttons:number[], timestamp), and helpers — `axisToPercent(v)` (−1..1 → −100..100),
    `buttonToPercent(b)`, `defaultAxisLabel(i)`/`defaultButtonLabel(i)`, `computeUpdateRateHz(times)`.
  - `core/rc/rc-input.service.ts` (signals): `supported()`, `connected: Signal<boolean>`,
    `device: Signal<RcDeviceInfo|null>`, `axes: Signal<number[]>`, `buttons: Signal<number[]>`,
    `updateRateHz: Signal<number>`. Listens for `gamepadconnected`/`disconnected`; while connected,
    an rAF poll loop reads `getGamepads()[index]` and updates the signals (zoneless-safe). Provided
    per-host (like `TelemetryStore`), started/stopped by the panel's lifecycle.
  - `features/fly/rc-monitor.ts` — a `<vision-side-panel title="Controller" icon="gamepad">` drawer:
    not-supported → `<vision-notice variant="warn">`; not-connected → instructions ("plug the radio
    in as USB Joystick, then move a stick to connect"); connected → device name, an update-rate chip
    (mono), a centered −100..+100 bar + mono value per axis, an on/off pill per button.
  - Wiring: add `'rc'` to `ToolRailPanelId`; a `gamepad` icon in `icon-registry.ts`; a tool-rail
    button + panel mount in `fly.html`; `RcMonitor` in `fly.ts` imports. The rail button is
    **always available** (a monitor, not a command) — unlike `flight`, it is not capability-gated.
- **Done when:** the operator sees their RadioMaster's channels move live in the cockpit; pure logic
  unit-tested; `npm run test:ci` + prod build green; MODULE.md updated. No new backend, no new
  MAVLink, no domain/application/adapter change.

### Phase 1 — SITL relay (design; not this cut)
Fly ArduPilot SITL from the transmitter, end to end.
- **domain:** `ManualControlPort` (out) with a *streaming* verb (e.g. `engage(assetId)` →
  `send(channels)` at rate → `release(assetId)`), plus a `ChannelMap`/`ControlBinding` value model
  (physical axis/button → RC channel + calibration: center/endpoints/deadband/reverse).
- **application:** `ManualControlService` — explicit **engage/disengage deadman**, rate-limit,
  **watchdog** (input-loss → release channels within a fixed timeout so the FC failsafe takes over),
  capability + arm/mode preconditions, audit (mirrors `FlightCommandService`).
- **adapter-mavlink:** a persistent fixed-rate sender of **`RC_CHANNELS_OVERRIDE` (#70)** on the
  shared socket (ArduPilot's complete 12+-channel primitive; `MANUAL_CONTROL` #69 is the stick-only
  alternative). Honour the release semantics (`0`/`0xFFFF` = release a channel).
- **driving side:** the cockpit streams channel values to the backend (WebSocket) while a "Take
  control" engage is active; upgrade capture to **WebHID** here for raw fidelity.
- **Verify in SITL only** (`infra/sitl/`); **measure glass-to-stick latency** and tune the watchdog.

### Phase 2 — real vehicle (design; gated)
Only after the watchdog/failsafe is proven in SITL **and** the receiver/failsafe question above is
answered for the specific airframe. Requires explicit user go, same doctrine as I-e.

## Non-negotiables
- SITL before any airframe. The watchdog/failsafe is the feature, not an add-on.
- Latency is a safety property: browser → WS → backend → UDP → FC adds tens of ms + jitter;
  GPS-assisted modes (Loiter/PosHold) tolerate far more than Stabilize/Acro. Measure, don't assume.
- Every relay session is audited, capability-gated, and engaged by an explicit operator gesture.
