# R2 — Manual control: sticks, keys, and setpoints

Research task for the MAVLINK-COMMANDS effort. Covers the three ways a GCS drives a
MAVLink vehicle in real time, their send-rate/failsafe contracts, button/keyboard
mapping practice (QGC, Mission Planner, rover WASD conventions), the ArduPilot Rover
mode table, and RC-world expo/deadzone practice — closing with a recommendation for
this project's web GCS (Angular → WebSocket → backend → MAVLink UDP).

**Grounding note:** this repo already ships a streaming manual-control path —
`drone-link/mavlink-core/.../service/ManualControlService.java` and
`drone-link/mavlink/.../MavlinkManualControlSender.java` — built on
**`RC_CHANNELS_OVERRIDE` (#70)**, not `MANUAL_CONTROL`, at a default 33 Hz clamped to
[10, 50] Hz, with a 3-frame release burst on disengage. Section 6 evaluates that
choice against this research rather than proposing a green-field design.

---

## 1. Three ways to drive a vehicle from a GCS

| Approach | Message(s) | Shape | Vehicle interprets as | Precision | Who uses it |
|---|---|---|---|---|---|
| **Manual axes** | `MANUAL_CONTROL` (#69) | Streamed, fire-and-forget, no ack | Generic joystick axes → mapped to pitch/roll/throttle/yaw pseudo-channels, then through `RCMAP_*` to real RC channels | Coarse — vehicle-generic, no per-channel addressing | QGC (physical + virtual joystick, "Normal" mode) |
| **RC override** | `RC_CHANNELS_OVERRIDE` (#70) | Streamed, fire-and-forget, no ack | Directly overwrites up to 18 RC input channels in PWM µs | Fine — caller addresses exact channel numbers | Mission Planner joystick/gamepad; **this repo's `ManualControlService`** |
| **Guided setpoints** | `SET_POSITION_TARGET_LOCAL_NED` (#84) / `_GLOBAL_INT` (#86) / `SET_ATTITUDE_TARGET` (#82) | One-shot per setpoint, must be **re-sent periodically** to stay live | Autopilot's own GUIDED-mode controller drives position/velocity/heading; not raw stick passthrough | Highest — physical units (m, m/s, rad) | Click-to-drive-here, autonomous companion computers, DroneKit/MAVSDK scripts |

### 1a. `MANUAL_CONTROL` fields (mavlink.io common.html#MANUAL_CONTROL)

| Field | Type | Range | Meaning |
|---|---|---|---|
| `target` | uint8 | — | target system |
| `x` | int16 | −1000..1000 | pitch-equivalent axis (forward/back); `INT16_MAX` = axis not used |
| `y` | int16 | −1000..1000 | roll-equivalent axis (left/right) |
| `z` | int16 | −1000..1000 | throttle-equivalent axis; **not** symmetric like the others in most vehicle interpretations |
| `r` | int16 | −1000..1000 | yaw rotation (CW = +1000) |
| `buttons` | uint16 | bitmask | buttons 0–15, 1 = pressed |
| `buttons2` (MAVLink 2 ext.) | uint16 | bitmask | buttons 16–31 |
| `enabled_extensions` | uint8 | bitmask | which of `s`, `t`, `aux1..aux6` are populated |
| `s`, `t` | int16 | −1000..1000 | extra pitch-only / roll-only axes (extended gimbal/aux control) |
| `aux1..aux6` | int16 | −1000..1000 | auxiliary channels |

Axis semantics are **vehicle-type dependent** by spec note — the field names (x/y/z/r)
are nominal "pitch/roll/throttle/yaw" but ArduPilot remaps them through the same
`RCMAP_ROLL` / `RCMAP_PITCH` / `RCMAP_THROTTLE` / `RCMAP_YAW` parameters used for
physical RC. For a standard 2-channel Ackermann Rover (`RCMAP_ROLL=1`,
`RCMAP_THROTTLE=3` by default): **`y` (roll-equivalent) drives steering** and
**`z` (throttle-equivalent) drives throttle**; `x` (pitch) and `r` (yaw) are unused
by a plain steering/throttle rover but are available for skid-steer/omni frames that
remap channels differently.
[Source: mavlink.io common.html#MANUAL_CONTROL](https://mavlink.io/en/messages/common.html#MANUAL_CONTROL),
[ArduPilot RCMAP docs](https://ardupilot.org/rover/docs/common-rcmap.html),
[ArduPilot RC Input dev doc](https://ardupilot.org/dev/docs/mavlink-rcinput.html).

### 1b. `RC_CHANNELS_OVERRIDE` fields (mavlink.io common.html#RC_CHANNELS_OVERRIDE)

| Field | Type | Units | Meaning |
|---|---|---|---|
| `target_system` / `target_component` | uint8 | — | addressing |
| `chan1_raw`..`chan8_raw` | uint16 | µs | 1000 = full low, 1500 ≈ center, 2000 = full high (AETR-ish, but **channel ordering is vehicle/frame convention, not protocol-defined** — mavlink.io explicitly warns real transmitters "might violate" any assumed ordering) |
| `chan9_raw`..`chan18_raw` (MAVLink 2 ext.) | uint16 | µs | extension channels |
| special value **`0`** (channels 1–8) | — | — | release this channel back to the RC radio / normal control |
| special value **`UINT16_MAX` (65535)** | — | — | ignore this field (leave unset in the frame) |
| special value **`UINT16_MAX − 1` (65534)** (channels 9–18 only) | — | — | release this channel — the **release sentinel differs** between the 1–8 block (`0`) and the 9–18 block (`65534`); `0` in the extension block means *ignore*, not release |

This repo's `RcChannels` domain type (`drone-link/mavlink-core/.../service/RcChannels.java`)
already encodes exactly this 1–8 vs 9–16 sentinel asymmetry (see `wireValue(int)` and
the FLEET-RADIO-PLAN F4 note in `ManualControlService`'s javadoc) — this was a real
defect class, not a hypothetical.
[Source: mavlink.io common.html#RC_CHANNELS_OVERRIDE](https://mavlink.io/en/messages/common.html#RC_CHANNELS_OVERRIDE).

### 1c. Guided setpoints — `SET_POSITION_TARGET_LOCAL_NED` (mavlink.io + ArduPilot dev docs)

| Field | Type | Units | Meaning |
|---|---|---|---|
| `time_boot_ms` | uint32 | ms | sender timestamp |
| `coordinate_frame` | uint8 | — | `MAV_FRAME_LOCAL_NED`(1) / `LOCAL_OFFSET_NED`(7) / `BODY_NED`(8) / `BODY_OFFSET_NED`(9) |
| `type_mask` | uint16 | bitmask | which of position/velocity/accel/yaw/yaw-rate to **ignore** |
| `x,y,z` | float | m | position (NED; z negative = up) |
| `vx,vy,vz` | float | m/s | velocity |
| `afx,afy,afz` | float | m/s² (or N if bit 10 set) | acceleration or force |
| `yaw` | float | rad | heading setpoint (0 = North) |
| `yaw_rate` | float | rad/s | turn-rate setpoint |

**ArduPilot Rover's accepted `type_mask` values** (from `ardupilot.org/dev/docs/mavlink-rover-commands.html`):

| Intent | type_mask (binary) | Decimal |
|---|---|---|
| Position only | `0b110111111100` | 3580 |
| Velocity only | `0b110111100111` | 3559 |
| Yaw only | `0b100111111111` | 2559 |
| Yaw-rate only | `0b010111111111` | 1535 |
| Velocity + yaw | `0b100111100111` | 2535 |
| Velocity + yaw-rate | `0b010111100111` | 1511 |

Rules: X and Y must be supplied together for position or velocity; if position is
set, velocity/yaw/yaw-rate are ignored; acceleration fields are **not** supported by
Rover. **Velocity commands must be re-sent at least once per second — the vehicle
stops if none arrives within 3 seconds** (same shape as the RC-override timeout, see
§2). Rover also accepts `SET_POSITION_TARGET_GLOBAL_INT` (WGS84 lat/lon) and
`SET_ATTITUDE_TARGET` (heading + speed via `thrust`, valid in GUIDED and
`GUIDED_NoGPS`). Known quirk: yaw supplied alongside a position/velocity target is
not always honored once the target is reached (ArduPilot issue #14960).
[Source: ArduPilot dev docs — Rover Commands in Guided Mode](https://ardupilot.org/dev/docs/mavlink-rover-commands.html),
[SET_POSITION_TARGET_LOCAL_NED](https://mavlink.io/en/messages/common.html#SET_POSITION_TARGET_LOCAL_NED).

`SET_ATTITUDE_TARGET` itself carries `q` (quaternion), `body_roll/pitch/yaw_rate`,
and `thrust` (0..1, or −1..1 for reverse-capable vehicles) — for Rover this is used
as a heading+speed command, not a literal attitude command (a rover has no
roll/pitch DOF to command).
[Source: mavlink.io common.html#SET_ATTITUDE_TARGET](https://mavlink.io/en/messages/common.html#SET_ATTITUDE_TARGET).

### Which GCS uses which, and why

| GCS | Joystick/gamepad message | Rationale (from docs/source) |
|---|---|---|
| **QGroundControl** | `MANUAL_CONTROL` by default ("Normal" joystick mode); advanced setting can switch to attitude-target style control | Generic across ArduPilot/PX4, needs no per-vehicle channel mapping; QGC explicitly documents this as the easy path and calls anything else "advanced ... can cause unpredicted results" |
| **Mission Planner** | `RC_CHANNELS_OVERRIDE` | Calibrates the joystick against the vehicle's own `RCx_MIN/MAX/TRIM`, i.e. it treats the joystick as a literal replacement RC transmitter, channel-for-channel |
| **This repo (`ManualControlService`)** | `RC_CHANNELS_OVERRIDE` | Explicit channel addressing (steering=1, throttle=3 by convention) avoids depending on ArduPilot's `MANUAL_CONTROL`→RCMAP indirection, and channels 9–16 are usable directly for radio/aux features (see FLEET-RADIO-PLAN) |

[Source: QGC Joystick Setup docs](https://docs.qgroundcontrol.com/master/en/qgc-user-guide/setup_view/joystick.html),
[Mission Planner joystick setup discussion](https://github.com/ArduPilot/MissionPlanner/issues/2401),
[ArduPilot Joystick/Gamepad docs](https://ardupilot.org/copter/docs/common-joystick.html).

### Which ArduPilot Rover mode expects which input

Manual modes (`MANUAL`, `ACRO`, `STEERING`) accept **either** `MANUAL_CONTROL` or
`RC_CHANNELS_OVERRIDE` interchangeably — both are just alternate RC input sources to
ArduPilot; MAVLink RC input takes priority over the physical transmitter whenever
present (§2). `GUIDED` mode is the only mode that accepts the position/velocity/
attitude setpoint messages from §1c; sending stick input while in `GUIDED` does
**not** drive the vehicle directly, though ArduPilot Rover's `GUIDED` mode does let
the transmitter throttle stick modulate speed 75–100% → configured-to-max-speed as a
manual override *on top of* the active setpoint. `HOLD` and the fully-autonomous
modes (`AUTO`, `RTL`, `SMART_RTL`) ignore manual stick input entirely by design (see
§4 table).
[Source: ArduPilot Guided mode docs](https://ardupilot.org/rover/docs/guided-mode.html).

---

## 2. Send-rate and failsafe contract

| Parameter | Default | Scope | Source |
|---|---|---|---|
| QGC joystick **active** send rate | 25 Hz (`axisFrequencyHz`, user-configurable) | axes | QGC Joystick Setup docs / `Joystick.cc` |
| QGC joystick **idle** send rate | 5 Hz | axes, while sticks are centered | QGC Joystick Setup docs |
| QGC button repeat rate | separate `buttonFrequencyHz` setting | buttons | QGC Joystick Setup docs |
| This repo's RC-override rate | 33 Hz default, clamped `[minOverrideHz=10, maxOverrideHz=50]` | `RC_CHANNELS_OVERRIDE` | `MavlinkCoreSettings.Rc` / `MavlinkSettings.Rc`, RC-CONTROL-PHASE1-PLAN §3 |
| `RC_OVERRIDE_TIME` | **3 s** (0 = disable overrides entirely, −1 = never time out) | ArduPilot: both `MANUAL_CONTROL` and `RC_CHANNELS_OVERRIDE` funnel into the same "MAVLink RC input" path and share this timeout | [ArduPilot forum / GitHub #12184](https://github.com/ArduPilot/ardupilot/issues/12184), ArduPilot dev RC-input doc |
| `FS_GCS_ENABLE` | 0 = disabled (must be explicitly turned on); 1 = act on `FS_ACTION`, 2 = ignore in AUTO | Rover/Copter | ArduPilot parameter docs |
| `FS_GCS_TIMEOUT` | **5 s** since last MAVLink heartbeat | GCS (telemetry-link) failsafe | ArduPilot Rover failsafe docs |
| `FS_TIMEOUT` (radio/RC failsafe) | **1 s** | loss of RC signal (incl. lost `RC_CHANNELS_OVERRIDE` in GCS-only operation) | ArduPilot Rover failsafes doc |
| `FS_ACTION` | vehicle-specific; Rover options: 1=RTL, 2=Hold, 3/4=SmartRTL→fallback, 5=Disarm, 6=Loiter→fallback | shared by radio and GCS failsafe | ArduPilot Rover failsafes doc |
| Rover HOLD-mode behavior on failsafe | servo/motor outputs pinned to `SERVOx_TRIM` (neutral), steering straight ahead | most common `FS_ACTION` target — arm/disarm gestures are deliberately inert in HOLD so recovery doesn't accidentally re-trigger motion | ArduPilot HOLD mode doc |
| GUIDED velocity setpoint resend | re-send ≥ 1/s | stops after 3 s silence | ArduPilot dev Rover-commands doc |

**Net effect for Rover:** whichever streaming path is used (`MANUAL_CONTROL` or
`RC_CHANNELS_OVERRIDE`), silence for `RC_OVERRIDE_TIME` (3 s default) hands control
back to the physical RC (or, with none connected, effectively becomes an RC-loss
condition after the shorter `FS_TIMEOUT` of 1 s once no channel data is being
refreshed) — and separately, silence on the *telemetry link* (no heartbeat) for
`FS_GCS_TIMEOUT` (5 s) triggers the GCS failsafe independently, typically dropping
the vehicle into `HOLD`. These are two different clocks watching two different
signals (channel data vs. heartbeat), both defaulting to a few seconds, both
converging on the same `FS_ACTION`.
[Source: ArduPilot Rover Failsafes](https://ardupilot.org/rover/docs/rover-failsafes.html),
[Hold Mode](https://ardupilot.org/rover/docs/hold-mode.html).

### What a well-behaved GCS does on release / focus loss

- **QGC**: on joystick disconnect it stops the RC-override-aux stream with an empty
  frame (`sendJoystickAuxRcOverrideThreadSafe({}, {}, false)`); the `MANUAL_CONTROL`
  axis path is not shown zeroing explicitly in `Joystick.cc` — it relies on ceasing
  to send, letting `RC_OVERRIDE_TIME`/heartbeat timeouts recover control. No explicit
  "center on release" packet is guaranteed in QGC's own polling loop.
- **This repo's `ManualControlService.release()`**: writes an all-`RELEASE`-sentinel
  frame into the mailbox and **actively transmits it `releaseFrames` times** (default
  3, at the coalesce rate) before stopping the periodic task — an explicit,
  belt-and-suspenders neutral burst rather than relying on the vehicle-side timeout
  to notice silence. This is the stronger of the two patterns and should stay the
  model for keyboard/gamepad "all keys up" / "gamepad disconnected" / "window blurred"
  handling (§6).

---

## 3. Button/gesture mapping practice

### QGC joystick button actions

QGC's Joystick Setup screen lets the operator assign, per button, one of: **arm**,
**disarm**, a **flight-mode change** (recommended over binding a raw RC channel to
mode, specifically to avoid clashing with a physical RC's own mode channel — QGC docs
cite ArduPilot issue #32862 as the reason), gimbal control, camera trigger, and
**Emergency Stop**. "Emergency Stop" in QGC maps to `MAV_CMD_COMPONENT_ARM_DISARM`
with the force-disarm magic value **21196** in param2 — a forced immediate disarm
that bypasses the normal "only disarm if landed" check (called "disarm force" in
MAVProxy). This is distinct from `MAV_CMD_DO_FLIGHTTERMINATION`, which is a dedicated
motor-power-cut command with airframe-termination origins.
[Source: GitHub ArduPilot/ardupilot#26521](https://github.com/ArduPilot/ardupilot/issues/26521),
[MAVLink Command Protocol](https://mavlink.io/en/services/command.html).

### Arm-via-stick-gesture

Standard convention (`ARMING_RUDDER` parameter family, shared across ArduPilot
vehicle types): with throttle centered/zero, **hold the steering/yaw stick fully to
one side for ~2 seconds** to arm (opposite side to disarm). For Rover specifically:
vehicle must be in `HOLD`, `MANUAL`, `ACRO`, or `STEERING` mode; center the throttle
stick and hold steering fully right for 2 s to arm. Rudder-arming only works in
manual-throttle modes with throttle at zero — it is deliberately impossible in
autopilot-driven modes.
[Source: ArduPilot arming docs discussion](https://ardupilot.org/rover/docs/arming-your-rover.html).

### Mission Planner

Joystick support is calibrated against the OS joystick driver (Windows game
controller wizard), then Mission Planner scales inputs to each channel's
`RCx_MIN/MAX/TRIM` and streams `RC_CHANNELS_OVERRIDE`. Same guidance as QGC: don't
bind the mode-switch channel (RC5/RC8) directly from the joystick; use a button →
"Change Mode" action instead.
[Source: GitHub ArduPilot/MissionPlanner#2401](https://github.com/ArduPilot/MissionPlanner/issues/2401).

### Typical gamepad layout (community convention, not a spec)

| Control | Typical binding |
|---|---|
| Throttle / steering | Left stick Y / X (or two separate sticks for tank-mixed skid-steer) |
| Mode cycle | Shoulder buttons (LB/RB) or D-pad |
| Arm | Start/Options, often held or combined with a second button (two-condition latch, mirroring ELRS's arm-switch convention, §5) |
| Disarm / E-stop | A dedicated, physically separate button from arm — never the same button toggled, to avoid an accidental double-tap re-arming |
| Recommended controllers (QGC-tested) | Sony PS3/PS4, Logitech F310/F710, FrSky Taranis (as a gamepad via USB), TBS Tango 2, Logitech Extreme 3D Pro |

[Source: QGC Joystick Setup docs](https://docs.qgroundcontrol.com/master/en/qgc-user-guide/setup_view/joystick.html).

### Keyboard driving

**QGC has no native keyboard-driving feature** — its Fly View documentation covers
only mouse/touch controls (map click-to-go, sliders for takeoff/land) and the
joystick subsystem (physical device or on-screen virtual thumbsticks); no WASD or
arrow-key vehicle control is documented anywhere in the QGC user guide, and none of
the Joystick.cc / Fly View source discussion surfaced any keyboard input path. Rover
GCS/web projects that want keyboard driving build it themselves. Practice that
generalizes across such DIY implementations:

- **Held-key state, not single keystrokes**: track a `Set<key>` of currently-down
  keys via `keydown`/`keyup`, and on every send-loop tick derive the analog value
  from that set (e.g. W held → throttle ramps toward +1000, released → ramps back to
  0) — this decouples the control rate from the OS's keyboard auto-repeat rate/delay,
  which is inconsistent across platforms and not designed for control loops.
- **Discrete step vs. analog**: two viable models — (a) each keypress nudges a
  stored setpoint by a fixed step (simple, but coarse and not self-centering), or
  (b) held-key = full deflection with client-side ramping/expo applied before
  encoding to the wire range (matches stick behavior better, recommended for a
  rover that should coast/decelerate predictably).
- **Key-repeat pitfalls**: never drive the send loop directly off repeated
  `keydown` events — browsers throttle/vary repeat timing (typically ~500 ms initial
  delay then ~30 Hz, both OS-configurable), which would make throttle response
  jerky and unrepeatable across machines. Poll the held-key set on a fixed timer
  instead (see §6).
- **Focus loss = neutral**: bind `window.blur` / `visibilitychange` to force the
  held-key set empty and immediately send (or trigger) a release burst — this is the
  keyboard analogue of a joystick being unplugged, and is the single most important
  safety property since a background browser tab still receiving `keydown` echoes
  (or simply a stuck key) with no way to physically observe the vehicle is the
  worst-case runaway scenario.

---

## 4. ArduPilot Rover mode table

`Mode::Number` enum, `Rover/mode.h` (ArduPilot/ardupilot, master):

| Mode | custom_mode | Manual stick input | GUIDED setpoints | Requires position estimate | Notes |
|---|---|---|---|---|---|
| `MANUAL` | 0 | Direct passthrough (steering=y/roll, throttle=z axis) | No | No | Baseline; recommended to always have one TX switch position mapped here |
| `ACRO` | 1 | Yes — steering stick = turn **rate** (`ACRO_TURN_RATE` at full stick), throttle = speed; heading-hold when steering centered | No | No | Rate-based, not lateral-accel-based like STEERING |
| *(2 = Learning, deprecated/removed)* | 2 | — | — | — | not present in current enum |
| `STEERING` | 3 | Yes — steering stick = lateral acceleration target (turn radius ≈ `TURN_RADIUS` at low speed, growing with speed, capped by `ATC_TURN_MAX_G`); throttle stick = speed via `CRUISE_THROTTLE`/`CRUISE_SPEED`; heading-hold at center | No | Yes (uses speed/turn controller) | |
| `HOLD` | 4 | **No** — outputs pinned to trim | No | No | Common failsafe target; arm/disarm gestures inert here by design |
| `LOITER` | 5 | No (autonomous station-keeping) | No | Yes | Mainly boats: computes a stop point, corrects drift back within radius |
| `FOLLOW` | 6 | Partial — throttle/speed can be manually influenced while steering auto-tracks the followed vehicle | No | Yes | Tracks another MAVLink-broadcasting vehicle/GCS at a configured offset |
| `SIMPLE` | 7 | Yes — same sticks as Manual/Acro/Steering but reinterpreted relative to the heading at arming time, not current heading | No | No (compass/heading only) | Aid for disoriented operators |
| `DOCK` | 8 *(conditional build flag)* | No | No | Yes | Autonomous docking |
| `CIRCLE` | 9 | No | No | Yes | Autonomous circling |
| `AUTO` | 10 | No | No (runs its own mission) | Yes | Waypoint mission execution |
| `RTL` | 11 | **No** | No | Yes | Returns directly to the arm location; holds (surface) or loiters (boat) on arrival; speed via `RTL_SPEED` (0 → falls back to `WP_SPEED`) |
| `SMART_RTL` | 12 | No | No | Yes | Retraces the actual path home (obstacle-safe) instead of a direct line |
| *(13–14 unused)* | — | — | — | — | |
| `GUIDED` | 15 | Partial — throttle stick can modulate commanded speed 75–100%→max while a setpoint is active | **Yes** — the only mode that consumes §1c setpoints | Yes | GCS/companion-computer control; no TX switch position needed since GCS sets it |
| `INITIALISING` | 16 | No | No | — | Boot-time transient state |

Manual-vs-autopilot classification per ArduPilot source comments: `is_autopilot_mode()`
is false for `MANUAL, ACRO, STEERING, HOLD, SIMPLE, FOLLOW, INITIALISING` and true for
`AUTO, RTL, SMART_RTL, GUIDED, LOITER, CIRCLE`.
[Source: `Rover/mode.h`](https://github.com/ArduPilot/ardupilot/blob/master/Rover/mode.h),
[Steering Mode](https://ardupilot.org/rover/docs/steering-mode.html),
[Acro Mode](https://ardupilot.org/rover/docs/acro-mode.html),
[Manual Mode](https://ardupilot.org/rover/docs/manual-mode.html),
[Hold Mode](https://ardupilot.org/rover/docs/hold-mode.html),
[Guided Mode](https://ardupilot.org/rover/docs/guided-mode.html),
[RTL Mode](https://ardupilot.org/rover/docs/rtl-mode.html),
[Smart RTL Mode](https://ardupilot.org/rover/docs/smartrtl-mode.html),
[Follow Mode](https://ardupilot.org/rover/docs/follow-mode.html),
[Simple Mode](https://ardupilot.org/rover/docs/simple-mode.html).

---

## 5. Expo / rates / deadzone practice from the RC world

| Concept | RC-world meaning | Typical convention |
|---|---|---|
| **Expo (exponential)** | Reshapes stick input with a cubic-ish curve: flatter near center, steeper toward the edges, full deflection unchanged | EdgeTX/OpenTX: 0% = linear, higher % = flatter center for fine low-speed control while preserving full-deflection response; QGC's own joystick expo uses the same `-k·x³ + (1+k)·x` blend |
| **Dual rates** | A separate max-deflection scaler (historically distinct from expo, now often merged into the same "Inputs" curve editor in EdgeTX) | Two/three switch-selected rate profiles (e.g. low-rate for precision, high-rate for aggressive maneuvering) |
| **Deadzone/deadband** | A small window around center where input is forced to exactly zero, to absorb stick centering slop | Applied once, client-side, before expo — QGC's `_adjustRange()` does deadband first, then expo |
| **Arm-switch convention** | A dedicated 2-position (or latched 2-condition) switch, never a stick gesture alone for a "real" vehicle | ExpressLRS fixes **AUX1 (channel 5)** as the arm channel by protocol convention (low=disarmed, high=armed) — hardcoded, not configurable, specifically to keep packet size down and give every receiver a predictable arm channel. Best practice: combine with a second (safety) switch as a two-condition latch, since a single arm switch bumped in transport/handling will spin motors |
| **Throttle-cut** | A switch that forces zero throttle output regardless of stick position, independent of arm state — a "you may be armed but you will not move" override | Standard on FPV transmitters; conceptually the same role `HOLD` mode plus zero-throttle serves on a rover |
| **Failsafe PWM on link loss** | Receiver outputs a neutral (or explicitly configured) PWM immediately on signal loss, regardless of last stick position | ELRS: documented edge case where a **reboot during signal loss with throttle applied** can momentarily replay full throttle before failsafe engages — mitigated by having the arm switch itself on a channel that stays disarmed across reboot (this is *why* the arm channel is fixed and always evaluated first) |

**What belongs on the GCS side vs. the vehicle side**: deadzone and expo should be
applied **once**, on the GCS/joystick-input side, before encoding to the wire range
— applying it a second time on the vehicle (ArduPilot Rover has its own
`MANUAL_STR_EXPO` for steering-stick expo in MANUAL mode) produces a compounded,
unpredictable curve. Practical rule: if the GCS is shaping the stick curve for
usability, leave the vehicle-side expo parameter at 0/disabled, and vice versa — pick
one layer, not both. Rate limiting / send frequency and failsafe timing, by
contrast, are unambiguously **vehicle-side** concerns (`RC_OVERRIDE_TIME`,
`FS_TIMEOUT`, `FS_GCS_TIMEOUT`) — the GCS's only obligation there is to keep sending
inside that window and to send a clean neutral/release burst on its own initiative
rather than depending on the vehicle to notice silence (§2).
[Source: Oscar Liang — EdgeTX Inputs/Mixes](https://oscarliang.com/inputs-mixes-outputs/),
[EdgeTX manual — Inputs](https://manual.edgetx.org/color-radios/model-settings/inputs-mixes-and-outputs/inputs),
[ExpressLRS Switch Configs](https://www.expresslrs.org/software/switch-config/),
[ExpressLRS arming discussion](https://github.com/ExpressLRS/ExpressLRS/discussions/1377),
[ArduPilot Manual Mode (`MANUAL_STR_EXPO`)](https://ardupilot.org/rover/docs/manual-mode.html).

---

## 6. Recommendation for this project's web GCS

**Keep the existing `RC_CHANNELS_OVERRIDE` path for both keyboard and gamepad; do
not add a parallel `MANUAL_CONTROL` sender.** `ManualControlService` already gives
explicit, per-channel addressing (steering on channel 1, throttle on channel 3 —
matching `RCMAP_ROLL`/`RCMAP_THROTTLE` defaults) which is strictly more predictable
for a rover than routing through ArduPilot's generic pitch/roll/throttle/yaw→RCMAP
indirection that `MANUAL_CONTROL` requires; it also already has channels 9–16
available for future aux features. Running two parallel streaming messages into the
same `RC_OVERRIDE_TIME` clock would only add a "which one wins" ambiguity ArduPilot
itself doesn't clearly document (§2) for no control-fidelity gain.

| Decision | Recommendation | Rationale |
|---|---|---|
| Wire message for both keyboard and gamepad | `RC_CHANNELS_OVERRIDE` via the existing `ManualControlPort`/`ManualControlService` | Already built, already channel-explicit, already handles the release-sentinel asymmetry (F4) and channel range (F17) correctly |
| Target send rate | Keep the existing 33 Hz default (clamped 10–50 Hz) | Comfortably brackets QGC's own 25 Hz active-joystick rate and sits far inside the 3 s `RC_OVERRIDE_TIME` window even at the 10 Hz floor |
| Neutral / release semantics | Reuse `ManualControlService.release()`'s N-frame burst (default 3) for: all keys up, gamepad disconnected, and `window.blur`/`visibilitychange` | It is already the stronger of the two patterns seen in this research (QGC merely stops sending and waits on the vehicle-side timeout); the frontend's only job is to *call* `release()` promptly on any of those three triggers, not reinvent neutral logic |
| Keyboard input model | Held-key `Set` polled on the same send-loop tick, not raw `keydown`/`keyup` events; W/S → throttle, A/D → steering, each ramped toward full deflection while held and back to 0 on release, with deadzone/expo applied client-side before encoding to the ±(1000–2000µs) wire range | Matches §3's key-repeat pitfall and gives throttle a stick-like feel instead of step artifacts; keeps expo/deadzone as a single client-side layer per §5 |
| Gamepad input model | Left stick Y → throttle, left stick X (or right stick X) → steering; shoulder buttons cycle mode; one dedicated, distinct button for arm (with a confirm/hold gesture) and a separate, larger/more-reachable button for E-stop | Mirrors QGC's button-action model and the RC world's two-condition arm-latch convention (§5) — arm and E-stop must never share a control |
| E-stop | `COMMAND_LONG` → `MAV_CMD_COMPONENT_ARM_DISARM` with param2 = force-disarm magic value `21196`, sent as a one-shot acked command (this repo's "Family A" request/ack path, e.g. `MavlinkFlightCommander`), **riding alongside** the still-streaming `RC_CHANNELS_OVERRIDE` — not blocking on it and not replacing it | Same split this codebase's own `ManualControlService` javadoc already documents: streaming control (Family B, no ack, no failsafe-on-silence defined by the protocol) is architecturally distinct from one-shot commands (Family A, ack-gated) — E-stop belongs firmly in the latter for a delivery guarantee |
| Mode changes | `MAV_CMD_DO_SET_MODE` (base_mode + `custom_mode` from §4's table) as a Family-A acked one-shot, independent of the streaming channel data | The streaming RC data continues unchanged through a mode switch; ArduPilot itself treats mode as orthogonal to RC channel values (a `HOLD`→`MANUAL` switch doesn't require new channel data to already be "correct") |
| Arm gesture | Prefer an explicit UI arm control (button/confirm) over replicating the RC-world stick gesture (steering-right 2 s) — the gesture exists to let a bare transmitter arm with no separate channel, which a web GCS doesn't need | A dedicated `COMMAND_LONG` arm request is unambiguous and matches how this repo already issues acked commands; the stick-gesture convention is a hardware constraint of physical transmitters, not a UX target to copy |

**Open question for the implementation wave (not resolved by this research alone):**
whether keyboard/gamepad send loops should live entirely client-side (Angular timer
→ WebSocket message per tick) or whether the backend should own the fixed-rate loop
and the frontend only pushes "current held state" on change — the latter matches
`ManualControlService`'s own coalesce/keepalive split (§2, "two rates not one") and
would let the browser tab go idle between key transitions instead of maintaining its
own 30+ Hz timer.

---

## Sources

- MAVLink common message set — `MANUAL_CONTROL`, `RC_CHANNELS_OVERRIDE`,
  `SET_POSITION_TARGET_LOCAL_NED`, `SET_ATTITUDE_TARGET`:
  https://mavlink.io/en/messages/common.html
- MAVLink Command Protocol: https://mavlink.io/en/services/command.html
- ArduPilot dev docs — RC Input: https://ardupilot.org/dev/docs/mavlink-rcinput.html
- ArduPilot dev docs — Rover Commands in Guided Mode:
  https://ardupilot.org/dev/docs/mavlink-rover-commands.html
- ArduPilot Rover docs — Control Modes index, Manual, Acro, Steering, Hold, Loiter,
  Follow, Simple, Guided, RTL, Smart RTL, Failsafes, Arming, Radio/Joystick:
  https://ardupilot.org/rover/docs/
- `Rover/mode.h` (ArduPilot/ardupilot, master):
  https://github.com/ArduPilot/ardupilot/blob/master/Rover/mode.h
- ArduPilot RCMAP docs: https://ardupilot.org/rover/docs/common-rcmap.html
- ArduPilot RC_OVERRIDE_TIME discussion:
  https://github.com/ArduPilot/ardupilot/issues/12184
- ArduPilot force-disarm / Emergency Stop:
  https://github.com/ArduPilot/ardupilot/issues/26521
- QGroundControl Joystick Setup docs:
  https://docs.qgroundcontrol.com/master/en/qgc-user-guide/setup_view/joystick.html
- QGroundControl `Joystick.cc` source:
  https://github.com/mavlink/qgroundcontrol/blob/master/src/Joystick/Joystick.cc
- QGroundControl joystick-rate issue:
  https://github.com/mavlink/qgroundcontrol/issues/6297 ,
  https://github.com/mavlink/qgroundcontrol/issues/11359
- Mission Planner joystick setup:
  https://github.com/ArduPilot/MissionPlanner/issues/2401
- PX4 `ManualControlSetpoint` uORB message:
  https://docs.px4.io/main/en/msg_docs/ManualControlSetpoint
- PX4 Manual Control / `COM_RC_IN_MODE`: https://docs.px4.io/main/en/config/manual_control
- EdgeTX manual — Inputs: https://manual.edgetx.org/color-radios/model-settings/inputs-mixes-and-outputs/inputs
- Oscar Liang — Inputs, Mixes, Outputs in EdgeTX: https://oscarliang.com/inputs-mixes-outputs/
- ExpressLRS Switch Configs: https://www.expresslrs.org/software/switch-config/
- ExpressLRS arming discussion: https://github.com/ExpressLRS/ExpressLRS/discussions/1377
- This repo (grounding, not external): `drone-link/mavlink-core/src/main/java/com/drones/mavlink/service/ManualControlService.java`,
  `drone-link/mavlink/src/main/java/com/drones/vision/adapter/mavlink/MavlinkManualControlSender.java`,
  `docs/plans/done/RC-CONTROL-PHASE1-PLAN.md`, `docs/plans/active/FLEET-RADIO-PLAN.md`
