# R1 — MAVLink command catalog: what ground stations actually send

**Task:** MAVLINK-COMMANDS R1 · **Scope:** command protocol mechanics, the MAV_CMD catalog that matters in practice, GCS session lifecycle, mission protocol, guided navigation, and a minimum-honest-rover-surface recommendation. **Method:** web research against mavlink.io, ardupilot.org (dev/rover/copter/planner), MAVProxy source, and QGroundControl/Mission Planner source/issue trackers.

Every fact below is either sourced from a fetched/searched authoritative page (linked inline) or flagged `[recall]` where it comes from stable, well-known MAVLink spec knowledge that could not be pulled from a live page in this pass (the `common.xml`/`common.html` pages are too large for single-shot fetch tooling — see note in §0). Numbers marked `[recall]` are internally consistent with every value that *was* independently confirmed by search, but a coding agent should grep `pymavlink`'s generated `common.py` (`pip show pymavlink` → `dialects/v20/common.py`) before hardcoding an id this report didn't get independent confirmation for.

---

## 0. Method note (read before trusting a number)

`mavlink.io/en/messages/common.html` renders all ~170 `MAV_CMD` entries plus every other `common.xml` enum on one page; it is too large for this tooling's single-fetch budget and truncates partway through the enum section before reaching `MAV_RESULT`. The raw `common.xml` on GitHub timed out for the same reason. Where a value below has a live citation it's a `mavlink.io` service-doc page (`command.html`, `services/mission.html`, `services/standard_modes.html`), an ArduPilot dev-doc page, or a MAVProxy/QGC source-code snippet returned by search. Independently corroborated numbers: `MAV_CMD_COMPONENT_ARM_DISARM=400`, force-arm `2989`, force-disarm `21196`, `MAV_CMD_DO_SET_MODE=176`, `MAV_CMD_SET_MESSAGE_INTERVAL=511`, `MAV_CMD_REQUEST_MESSAGE=512`, `MAV_CMD_DO_REPOSITION=192` (via bug reports referencing "Cmd 192"), `MAV_CMD_DO_PAUSE_CONTINUE=193` (via the same threads), `MAV_CMD_DO_AUX_FUNCTION=218`, `MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN=246`, ArduPilot Rover `custom_mode` numbers (`MANUAL=0 … GUIDED=15`).

---

## 1. The command protocol

Source: [MAVLink Command Protocol](https://mavlink.io/en/services/command.html), [common.html message defs](https://mavlink.io/en/messages/common.html).

### 1.1 COMMAND_LONG (#76) vs COMMAND_INT (#75)

| | `COMMAND_LONG` (id 76) | `COMMAND_INT` (id 75) |
|---|---|---|
| `target_system`, `target_component` | uint8 | uint8 |
| `command` | uint16 (`MAV_CMD`) | uint16 (`MAV_CMD`) |
| `confirmation` | uint8 — 0 on first send, incremented on each resend of the *same* command | *(no confirmation field)* |
| `frame` | — | uint8 `MAV_FRAME` — coordinate frame for `x`/`y`/`z` |
| `current`, `autocontinue` | — | present but **unused for live commands** (mission-item legacy fields) |
| params 1–4 | float | float |
| params 5–6 (`x`/`y`) | float | **int32**, scaled ×1e7 for lat/lon in global frames |
| param 7 (`z`) | float | float |

**Rule of thumb:** `COMMAND_INT` exists for positional/navigation commands that need integer lat/lon precision (float32 loses ~1m precision far from the origin) and an explicit `MAV_FRAME`. `COMMAND_LONG` is the default for everything else, and is *required* when a command needs float precision in params 5–6 (which `COMMAND_INT`'s int32 encoding would truncate). A flight stack that only implements one form for a given command replies `MAV_RESULT_COMMAND_LONG_ONLY` / `MAV_RESULT_COMMAND_INT_ONLY` to tell the sender to retry with the other message type. [Command Protocol](https://mavlink.io/en/services/command.html)

### 1.2 COMMAND_ACK (#77) and MAV_RESULT

| Field | Type | Notes |
|---|---|---|
| `command` | uint16 | the `MAV_CMD` being acknowledged |
| `result` | uint8 | `MAV_RESULT` enum |
| `progress` | uint8 (MAVLink2 ext) | 0–100 for long-running commands mid-flight; `255` if not supplied |
| `result_param2` | int32 (MAVLink2 ext) | command-specific extra failure detail |
| `target_system`, `target_component` | uint8 (MAVLink2 ext) | addresses the ACK back to the original sender — important on a bus with more than one GCS/companion computer |

`MAV_RESULT` enum (0–9) — confirmed 0,1,4,5,6,7,8,9 live via [command.html](https://mavlink.io/en/services/command.html) and cross-checked search; 2,3 `[recall]`, internally consistent:

| Value | Name | Meaning |
|---|---|---|
| 0 | `ACCEPTED` | Command valid and executed / will be executed |
| 1 | `TEMPORARILY_REJECTED` | Valid command, but temporarily rejected (e.g. a long-running command of the same type is already in flight — only one instance may run at a time) |
| 2 | `DENIED` | Command permanently denied (won't succeed if retried unchanged) |
| 3 | `UNSUPPORTED` | Command unknown/unsupported by this component |
| 4 | `FAILED` | Command was executed but failed |
| 5 | `IN_PROGRESS` | Long-running command is executing; more ACKs (with `progress`) or a final result will follow |
| 6 | `CANCELLED` | Long-running command was cancelled (via `COMMAND_CANCEL`) |
| 7 | `COMMAND_LONG_ONLY` | Retry as `COMMAND_LONG` |
| 8 | `COMMAND_INT_ONLY` | Retry as `COMMAND_INT` |
| 9 | `COMMAND_UNSUPPORTED_MAV_FRAME` | The `frame` given isn't supported for this command |

### 1.3 Retry / timeout convention

The **command protocol deliberately does not codify exact numbers** — it says only that the sender starts a timeout after sending, and on no ACK "resend the command with an incremented `confirmation` value... for a flight-specific number of times before giving up." On `IN_PROGRESS`, the timeout window extends for the long-running case; a second identical long-running command gets `TEMPORARILY_REJECTED` while the first is still active. [Command Protocol](https://mavlink.io/en/services/command.html)

Contrast with the **mission protocol**, which *does* codify numbers: default message timeout **1500ms**, per-plan-item timeout **250ms**, max retries **5**. [Mission Protocol](https://mavlink.io/en/services/mission.html)

Concrete implementation examples found:
- ArduPilot's own `mavlink-core` client in this repo (`drone-link/mavlink-core/.../service/CommandService.java`) uses a **zero-retry** policy for `MavlinkFlightCommander` (`NO_RETRIES`, single wait up to `ackTimeout`, then `NO_ACK`) — a deliberate design choice, not spec-mandated. (Per this repo's own R3 codebase inventory, `docs/plans/active/mavlink-commands/R3-codebase-inventory.md`.)
- QGC's `Vehicle::sendMavCommand` implements queued `MAV_CMD` sends with retry (commit reference: [gitlab mirror, "New Vehicle::sendMavCommand - Queued MAV_CMD with retry"](https://gitlab.imerir.com/damien.bontemps/qgroundcontrol/commit/d84dd7e42da6aeb91b9b6068896e689ef921bd52)) — exact retry count/timeout not confirmed live in this pass; treat QGC as "retries a few times with increasing confirmation," not as a numeric spec.

### 1.4 Addressing

- `target_system` is the numeric MAVLink system id of the vehicle (commonly `1`); `target_component` is usually `MAV_COMP_ID_AUTOPILOT1` (`1`), sometimes `0`.
- Convention across GCS implementations (QGC, MAVProxy) is to target the specific autopilot component, not broadcast — `target_component=0` is technically "all components on the system" per spec but is inconsistently honored, so practitioner code always sets it explicitly. `[recall]`
- `COMMAND_ACK`'s MAVLink2 `target_system`/`target_component` extension lets a responder address the ACK back to whoever sent the command, which matters the moment more than one GCS or a companion computer shares the link.

---

## 2. MAV_CMD catalog — what matters in practice

Legend for **Applicability**: **D**=multirotor/fixed-wing drone, **R**=ArduPilot Rover (ground), **B**=Rover "Boat" frame class (same firmware, different physics — loiters instead of holding position on RTL, etc).

### 2.1 Arming / safety

| ID | MAV_CMD | Key params | GCS trigger | D/R/B | Expected ACK |
|---|---|---|---|---|---|
| 400 | `COMPONENT_ARM_DISARM` | p1: `1`=arm/`0`=disarm. p2: `0`=normal (subject to pre-arm checks) / `2989`=**force-arm** magic / `21196`=**force-disarm** magic (bypasses checks) | QGC arm slider (drag) / long-press for force; MP Actions "ARM"/"DISARM" buttons; MAVProxy `arm throttle` / `arm throttle force` / `disarm` / `disarm force` | D,R,B | `ACCEPTED`, or `FAILED`/`DENIED` with a `STATUSTEXT` explaining the failed pre-arm check. [ArduPilot arming doc](https://ardupilot.org/dev/docs/mavlink-arming-and-disarming.html), [MAVProxy arm.py source](https://github.com/ardupilot/MAVProxy/blob/master/MAVProxy/modules/mavproxy_arm.py) |
| — | ⚠ known ArduPilot bug | Sending `param2=21196` (the *disarm* force magic) on an **arm** request (`param1=1`) is mishandled by ArduPilot as a force-*arm* — it silently bypasses all pre-arm checks instead of being rejected. Do not reuse the disarm magic on the arm path. | — | D,R,B | [ArduPilot issue #32996](https://github.com/ArduPilot/ardupilot/issues/32996), [issue #26521](https://github.com/ArduPilot/ardupilot/issues/26521) |
| 401 | `RUN_PREARM_CHECKS` | no meaningful params | MAVProxy `arm prearms` (force a fresh pre-arm check pass without arming) | D,R,B | `ACCEPTED`/`FAILED` |
| 185 | `DO_FLIGHTTERMINATION` `[recall]` | p1: `1`=terminate (irreversible — cuts to a crash-safe state), `0`=normal | Not a stock button in QGC/MP; wired to advanced failsafe / geofence-breach actions, or sent manually via MAVProxy `long DO_FLIGHTTERMINATION 1` | D (primary use case); ArduPilot Rover has no meaningful "terminate flight" concept — expect `UNSUPPORTED` or a no-op equivalent | `ACCEPTED` then the vehicle is unrecoverable, or `UNSUPPORTED` |
| 209 | `DO_MOTOR_TEST` `[recall id, params confirmed via search]` | p1: motor instance (1..N). p2: throttle type (`0`=percent, `1`=PWM µs, `2`=pilot-throttle passthrough). p3: throttle value. p4: timeout between sequenced tests (s). p5: motor count (0/1=one motor, 2=two, …). p6: test order | MP "Motor Test" screen (Initial Setup → Mandatory Hardware); QGC Vehicle Setup → Motors | D (per-motor spin test), R (skid-steer: test each side's motor output) | `ACCEPTED` then the motor physically spins for the timeout window; `FAILED` if disarmed-safety interlock blocks it |

### 2.2 Mode

| ID | MAV_CMD | Key params | GCS trigger | D/R/B | Expected ACK |
|---|---|---|---|---|---|
| 176 | `DO_SET_MODE` | p1 = `base_mode` — set to `1` (`MAV_MODE_FLAG_CUSTOM_MODE_ENABLED`) so `custom_mode` is honored. p2 = `custom_mode` — vehicle/firmware-specific numeric mode | QGC flight-mode dropdown (top HUD text); MP mode dropdown + Auto/Loiter/RTL shortcut buttons in Actions tab; MAVProxy `mode <name>` | D,R,B | `ACCEPTED` if the transition is legal from current state, else `FAILED`/`DENIED` (e.g. GUIDED without a position fix, per-mode arming restrictions). [Get and Set FlightMode](https://ardupilot.org/dev/docs/mavlink-get-set-flightmode.html) |

**ArduPilot Rover `custom_mode` table** (confirmed via [Rover/mode.h](https://github.com/ardupilot/ardupilot/blob/master/Rover/mode.h) search result — verify against the exact firmware version, modes have been added over time, e.g. `DOCK`):

| Value | Mode |
|---|---|
| 0 | MANUAL |
| 1 | ACRO |
| 3 | STEERING |
| 4 | HOLD |
| 5 | LOITER |
| 6 | FOLLOW |
| 7 | SIMPLE |
| 9 | CIRCLE |
| 10 | AUTO |
| 11 | RTL |
| 12 | SMART_RTL |
| 15 | GUIDED |
| 16 | INITIALISING |

For comparison, **ArduPilot Copter** custom_mode differs entirely (0=STABILIZE, 2=ALT_HOLD, 3=AUTO, 4=GUIDED, 5=LOITER, 6=RTL, 9=LAND …) `[recall]` — the numeric value is meaningless without knowing which vehicle firmware is on the other end; a GCS resolves the name→number mapping per-firmware, per-frame-class before sending. This repo's `MavlinkFlightCommander.setMode`/`returnToHome` already does this resolution (per R3 codebase inventory).

`base_mode` itself is a bitmask (`MAV_MODE_FLAG`) — bit 0 = `CUSTOM_MODE_ENABLED` (`1`), and separately bit 7 = `SAFETY_ARMED` (`128`, read-only status reported in `HEARTBEAT.base_mode`, not something you set via `DO_SET_MODE`). `[recall]`

A newer **`MAV_CMD_DO_SET_STANDARD_MODE`** command (companion to `HOLD`/`MISSION`/`RETURN`/`LAND`/`TAKEOFF` firmware-agnostic "standard modes") exists in the spec's forward direction — PX4 implements it from v1.15, QGC daily builds support it; **ArduPilot support and the exact numeric id were not confirmed** in this pass. Treat as forward-looking, not yet load-bearing. [Standard Modes Protocol](https://mavlink.io/en/services/standard_modes.html)

### 2.3 Message/stream control

| ID | MAV_CMD | Key params | GCS trigger | D/R/B | Expected ACK |
|---|---|---|---|---|---|
| 511 | `SET_MESSAGE_INTERVAL` | p1 = target message id (e.g. `33`=`GLOBAL_POSITION_INT`). p2 = interval in **microseconds** (`0`=request default rate, `-1`=stop sending) | Not a stock end-user button — GCS internal connection setup requests exactly the messages its widgets need; exposed to power users via QGC's MAVLink Console / Analyze tools | D,R,B | `ACCEPTED`/`UNSUPPORTED` per message id |
| 512 | `REQUEST_MESSAGE` | p1 = target message id, one-shot. p2–p7 = message-specific args (e.g. requesting `MESSAGE_INTERVAL` (id 244) with p2 = the queried message's id) | GCS fetching a message it doesn't stream continuously — `HOME_POSITION`, `AUTOPILOT_VERSION` at connect for capability probing | D,R,B | `ACCEPTED` + the requested message follows, or `FAILED`/`UNSUPPORTED` |
| — | `REQUEST_DATA_STREAM` (#66, legacy message, not a `MAV_CMD`) | stream id + rate, coarse groups | Still used by MAVProxy and older tooling for bulk stream negotiation; ArduPilot supports it alongside `SET_MESSAGE_INTERVAL` | D,R,B | no ACK — it's fire-and-forget; success is observed by whether the stream starts flowing |

ArduPilot additionally exposes per-stream-group rate parameters (`SRx_*`, one group per telemetry channel index `x`, e.g. `SR1_EXTRA1`) that bound/default the rates a GCS can request — these are configuration, not commands. [Requesting Data From The Autopilot](https://ardupilot.org/dev/docs/mavlink-requesting-data.html)

### 2.4 Navigation

| ID | MAV_CMD | Key params | GCS trigger | D/R/B | Expected ACK / notes |
|---|---|---|---|---|---|
| 22 | `NAV_TAKEOFF` | p1: min pitch (deg, fixed-wing). p4: yaw. p5/p6: lat/lon (0 or NaN = use current). p7: target altitude | QGC "Takeoff" guided action (altitude slider); MP Actions "TAKEOFF" | **D only** — no meaningful ground-vehicle semantics; a rover should reply `UNSUPPORTED` | `ACCEPTED` then climbs; also a mission-item command |
| 21 | `NAV_LAND` | p1: abort altitude. p2: precision-land mode. p4: yaw. p5/p6: lat/lon. p7: altitude | QGC "Land" guided action; MP "LAND" action | **D only**. ArduPilot Rover/Boat added a separate `DOCK` mode for auto-docking, not `NAV_LAND` | `ACCEPTED` then descends |
| 20 | `NAV_RETURN_TO_LAUNCH` | no meaningful params (all 0) | QGC "Return"/RTL safety button; MP RTL shortcut / Actions RTL | D,R,B — Rover: holds position at home; Boat: loiters at home ([ArduPilot dev rover-commands doc](https://ardupilot.org/dev/docs/mavlink-rover-commands.html)) | `ACCEPTED`, switches into RTL mode |
| 16 | `NAV_WAYPOINT` | p1: hold time (s) at WP. p2: acceptance radius (m). p3: pass radius (signed = direction). p4: yaw. p5/p6: lat/lon. p7: altitude | **Not sent live by a GCS as a standalone command** — it's the workhorse mission-item command inside `MISSION_ITEM_INT` uploads (see §4). Also double-used by ArduPilot's "guided single item" convention (`current=2`, see §5) for click-to-go | D,R,B — Rover ignores altitude (ground vehicle); hold-time still honored | Not directly ACKed as a live command in normal use — see §4 for mission-item semantics |
| 192 | `DO_REPOSITION` | p1: speed (m/s, `-1`=no change). p2: bitmask `MAV_DO_REPOSITION_FLAGS` — bit0 = force a mode change into GUIDED even if not already there. p3: loiter radius (signed for direction, added later for Plane). p4: yaw (deg, NaN=unchanged). p5/p6: lat/lon (int32, or `INT32_MAX`=keep current). p7: altitude | QGC "Go To Location" — click the map while in Guided mode → `Vehicle::guidedModeGotoLocation()` → `MAV_CMD_DO_REPOSITION` on ArduPilot when the vehicle reports support ([QGC APMFirmwarePlugin.cc](https://github.com/mavlink/qgroundcontrol/blob/master/src/FirmwarePlugin/APM/APMFirmwarePlugin.cc)) | D,R,B, **but**: a real regression exists — ArduPilot **Rover 4.6.3** was reported to reply `MAV_RESULT_DENIED` to `DO_REPOSITION` ([ArduPilot Discourse thread](https://discuss.ardupilot.org/t/rover-4-6-3-mav-cmd-do-reposition-returns-mav-result-denied/145240)) — don't assume it silently always works on rover firmware; verify per version | `ACCEPTED` or `DENIED`/`FAILED` — see the rover caveat above |
| 178 | `DO_CHANGE_SPEED` | p1: speed type (0=airspeed,1=ground speed,2=climb,3=descent — ArduPilot really only honors ground speed meaningfully for Rover). p2: speed (m/s, `-1`=no change). p3: throttle % (`-1`=no change). p4: 0=absolute/1=relative (not universally honored) | QGC guided-action speed slider; MP "Change Speed" field+button | D,R,B (Rover: supported and commonly used — [rover mission command doc](https://ardupilot.org/rover/docs/common-mavlink-mission-command-messages-mav_cmd.html)) | `ACCEPTED` |
| 179 | `DO_SET_HOME` | p1: `1`=use current location / `0`=use given lat/lon/alt in p5/p6/p7 | QGC "Set Home" guided action; MP right-click map "Set Home Here" / Actions "Set Home Alt" | D,R,B | `ACCEPTED` |
| 112 | `CONDITION_DELAY` | p1: delay in seconds | Mission-item only — a GCS's mission editor inserts this between waypoints, not sent live | D,R,B | mission-item, no live ACK expected |
| 115 | `CONDITION_YAW` | p1: target angle (0–360°). p2: angular speed (°/s). p3: direction (`-1`=CCW/`1`=CW/`0`=shortest). p4: `0`=absolute/`1`=relative | Mission item, or occasionally sent live for camera-aiming heading holds | Meaningful mostly for D (heading independent of ground track); a skid-/Ackermann-steer Rover steers via its drive geometry, so this command is largely meaningless for R/B — expect it to be accepted-and-ignored or `UNSUPPORTED` depending on firmware | `ACCEPTED`/`UNSUPPORTED` |

### 2.5 Preflight

| ID | MAV_CMD | Key params | GCS trigger | D/R/B | Expected ACK |
|---|---|---|---|---|---|
| 241 | `PREFLIGHT_CALIBRATION` | p1: gyro. p2: magnetometer. p3: ground-pressure/baro. p4: radio/RC trim. p5: accelerometer. p6: airspeed (or legacy compass/motor cal on some firmware). p7: ESC `[recall, params well established]` | QGC Vehicle Setup → Sensors wizards; MP Initial Setup → Mandatory Hardware calibration screens | D,R,B (Rover needs compass+accel cal too — [Rover arming doc](https://ardupilot.org/rover/docs/arming-your-rover.html)) | Long-running: `IN_PROGRESS` ACKs while the operator physically moves/orients the vehicle, then `ACCEPTED` or `FAILED` (timeout/movement error) |
| 246 | `PREFLIGHT_REBOOT_SHUTDOWN` | p1: `0`=nothing/`1`=reboot autopilot/`2`=shutdown autopilot. p2: same for onboard companion computer. p3: camera. p4: MAVLink component | QGC/MP "Reboot Vehicle" prompt after firmware/param changes | D,R,B | `ACCEPTED` (then the link drops as it reboots — no further ACK expected) |
| 245 | `PREFLIGHT_STORAGE` `[recall]` | p1: `0`=read param storage/`1`=write current params/`2`=reset params to defaults. p2: same semantics for mission storage | MP "Reset to Default"; less commonly exposed directly in QGC's stock UI | D,R,B | `ACCEPTED`/`FAILED` |

### 2.6 Auxiliary (relays, servos, grippers, aux switches)

| ID | MAV_CMD | Key params | GCS trigger | D/R/B | Expected ACK |
|---|---|---|---|---|---|
| 218 | `DO_AUX_FUNCTION` | p1: function id (ArduPilot's `RCn_OPTION` numeric enum — the same catalog as a physical aux switch). p2: switch position `0`=low(deactivate)/`1`=mid/`2`=high(activate) | Simulates a physical RC aux switch from the GCS — used when a GCS wants to trigger a feature (sprayer, lights, gripper, camera trigger) mapped to `RCn_OPTION` without a real switch present | D,R,B — very common on Rover (sprayers, lights, mowers) | `ACCEPTED`/`UNSUPPORTED` |
| 183 | `DO_SET_SERVO` | p1: output/servo instance number. p2: PWM µs | MP Actions "Set Servo" | D,R,B | `ACCEPTED` |
| 181 | `DO_SET_RELAY` | p1: relay instance number. p2: state `0`/`1` | MP Actions "Set Relay" | D,R,B | `ACCEPTED` |
| 211 | `DO_GRIPPER` | p1: gripper instance number. p2: `GRIPPER_ACTION` — `0`=`GRIPPER_ACTION_RELEASE`/`1`=`GRIPPER_ACTION_GRAB` | Delivery-drone payload UI (mostly Copter); rover payload-drop rigs use the same `AP_Gripper` subsystem | D (primary), R (supported by `AP_Gripper` on any vehicle) | `ACCEPTED` |
| 251 `[recall]` | `DO_SET_REVERSE` | p1: `0`=forward/`1`=reverse | Not a stock GCS button — mission-item/companion-computer use for a rover that needs to drive in reverse for part of a mission | **Rover-only** | `ACCEPTED`/`UNSUPPORTED` (D,B likely `UNSUPPORTED`) |

### 2.7 Camera / gimbal (brief — out of scope for a ground rover but included per ask)

| ID | MAV_CMD | Purpose | Trigger |
|---|---|---|---|
| 203 | `DO_DIGICAM_CONTROL` | fire the shutter once | camera-control button |
| 205 | `DO_MOUNT_CONTROL` | pitch/roll/yaw a gimbal/mount directly | ROI/gimbal panel drag |
| 195/197/198 | `DO_SET_ROI_LOCATION`/`_NONE`/`_SYSID` | point camera at a fixed location / clear ROI / track another system | "point camera here" map action |

Modern QGC/MAVSDK increasingly use the dedicated **Camera Protocol v2** (`MAV_CMD_IMAGE_START_CAPTURE` etc., separate service doc) rather than the legacy digicam commands above — flagged here as a heads-up, not detailed further since it's out of scope for a rover command surface.

---

## 3. GCS session lifecycle

### 3.1 Normal sequence (any GCS)

| Phase | Wire traffic | Notes |
|---|---|---|
| 1. Connect | Vehicle emits `HEARTBEAT` (#0) at 1Hz unconditionally; a modern GCS (QGC) also emits its own `HEARTBEAT` at 1Hz so the vehicle can detect "a GCS is present" for failsafe purposes | [MAVLink Basics](https://ardupilot.org/dev/docs/mavlink-basics.html) |
| 2. Stream negotiation | GCS sends `REQUEST_DATA_STREAM` (legacy bulk) and/or `MAV_CMD_SET_MESSAGE_INTERVAL` (511, per-message) for exactly what its widgets need. **ArduPilot does not auto-push telemetry to an unknown peer** — it waits to be asked (or serves whatever the port's default `SRx_*` rates say) | [Requesting Data From The Autopilot](https://ardupilot.org/dev/docs/mavlink-requesting-data.html) |
| 3. Param download | `PARAM_REQUEST_LIST` (broadcast bulk request) → autopilot streams `PARAM_VALUE` for every parameter; GCS tracks received count vs the `param_count` in the first `PARAM_VALUE` and re-requests any gaps individually via `PARAM_REQUEST_READ` | standard practice, `[recall]` |
| 4. (Optional) Mission download/upload | `MISSION_REQUEST_LIST` → `MISSION_COUNT` → per-item `MISSION_REQUEST_INT`/`MISSION_ITEM_INT` → `MISSION_ACK` (see §4) | |
| 5. Arm | `COMMAND_LONG` `COMPONENT_ARM_DISARM` (400), p1=1 | |
| 6. Operate | mode changes (`DO_SET_MODE`), guided commands (`DO_REPOSITION`, `DO_CHANGE_SPEED`), optionally `RC_CHANNELS_OVERRIDE` (#70) for manual control | |
| 7. Disarm | `COMPONENT_ARM_DISARM` p1=0, either operator-initiated or automatic landing-detected disarm | |

### 3.2 QGroundControl Fly-view buttons → wire (source: [GuidedActionsController.qml](https://raw.githubusercontent.com/mavlink/qgroundcontrol/master/src/FlyView/GuidedActionsController.qml), [Vehicle.cc](https://github.com/mavlink/qgroundcontrol/blob/master/src/Vehicle/Vehicle.cc), [APMFirmwarePlugin.cc](https://github.com/mavlink/qgroundcontrol/blob/master/src/FirmwarePlugin/APM/APMFirmwarePlugin.cc))

| UI element | QGC function called | Wire command |
|---|---|---|
| Arm slider (drag to confirm) | `_activeVehicle.armed = true` | `COMPONENT_ARM_DISARM` p1=1, p2=0 |
| Arm slider, force variant | `_activeVehicle.forceArm()` | `COMPONENT_ARM_DISARM` p1=1, p2=**2989** |
| Emergency Stop | `_activeVehicle.emergencyStop()` | `COMPONENT_ARM_DISARM` p1=0, p2=**21196** (force-disarm magic) — this is QGC's mapping of ArduPilot's force-disarm option to a UI "kill switch" |
| RTL / Return button | `_activeVehicle.guidedModeRTL(smartRTL)` — ArduPilot plugin implementation calls `_setFlightModeAndValidate(smartRTL ? smartRTLFlightMode() : rtlFlightMode())` | `DO_SET_MODE` → RTL or SmartRTL custom_mode (ArduPilot treats RTL as a *mode*, not a one-shot nav command, though PX4's plugin instead sends `MAV_CMD_NAV_RETURN_TO_LAUNCH` directly) |
| Pause | `_activeVehicle.pauseVehicle()` | **Historically `MAV_CMD_DO_REPOSITION` (192)** with current position + zero speed, *not* the dedicated `MAV_CMD_DO_PAUSE_CONTINUE` (193) — flagged by the community as confusing/wrong ([QGC issue #7612](https://github.com/mavlink/qgroundcontrol/issues/7612), [PX4 issue #12544](https://github.com/PX4/PX4-Autopilot/issues/12544), [PX4 forum discussion](https://discuss.px4.io/t/pause-button-why-cmd-192-reposition-instead-of-cmd-193-do-pause/17227)). A minimal rover implementation should still answer `DO_PAUSE_CONTINUE` correctly for GCSs/tooling that *do* send it |
| Land | `guidedModeLand()` | `NAV_LAND` (D only) |
| Takeoff | `guidedModeTakeoff(alt)` / `startTakeoff()` | `NAV_TAKEOFF` (D only) |
| Mode dropdown (click flight-mode text) | `_activeVehicle.flightMode = <name>` | `DO_SET_MODE` |
| Click map in Guided ("Go To Location") | `guidedModeGotoLocation(coord, radius)` | `DO_REPOSITION` (ArduPilot, if supported — see §2.4/§5 caveat) |
| Orbit | `guidedModeOrbit(center, radius, altitude)` | `DO_REPOSITION`/orbit-specific extension (D-centric, not relevant to rover) |
| Change speed slider | `guidedModeChangeGroundSpeedMetersSecond(v)` | `DO_CHANGE_SPEED` |
| Set Home action | `doSetHome(coord)` | `DO_SET_HOME` |
| Start Mission | `startMission()` | mode change to AUTO + (if needed) `MISSION_START` (300) |
| Resume Mission | `resumeMission(index)` | `MISSION_SET_CURRENT` / `DO_SET_MISSION_CURRENT` (224) then mode=AUTO |

### 3.3 Mission Planner Actions tab → wire (source: [Mission Planner Flight Data doc](https://ardupilot.org/planner/docs/mission-planner-flight-data.html))

The Actions tab is deliberately closer to the raw protocol than QGC's Fly view: it exposes **four dropdowns** — (1) raw `MAV_CMD` action name, (2) waypoint index, (3) flight mode, (4) camera-mount state — each with its own "go" button, plus three shortcut buttons (**Auto**, **Loiter**, **RTL**). Named features in the tab map directly to commands already in §2: Arm/Disarm → `COMPONENT_ARM_DISARM`; mode buttons/dropdown → `DO_SET_MODE`; "Restart Mission"/"Resume Mission" → `MISSION_SET_CURRENT` + mode=AUTO; "Set Home Alt"/set-home → `DO_SET_HOME`; "Change Speed"/"Change Alt" → `DO_CHANGE_SPEED`/guided altitude change; "Set Loiter Rad" → a parameter write, not a command; servo/relay overrides → `DO_SET_SERVO`/`DO_SET_RELAY`; "Fly to Here" (right-click map, requires Guided mode) → the `MISSION_ITEM` `current=2` guided-goto convention (see §5) historically, with newer builds trending toward `DO_REPOSITION` to match QGC. Mission Planner's own docs note that MP uses a shortened version of each `MAV_CMD_*` name as the dropdown label (e.g. `MAV_CMD_NAV_WAYPOINT` → `WAYPOINT`), which is useful when reverse-engineering a `.tlog` or a "what does this dropdown item send" question. [Planning a Mission with Waypoints and Events](https://ardupilot.org/planner/docs/common-planning-a-mission-with-waypoints-and-events.html)

### 3.4 MAVProxy

MAVProxy is the closest thing to "raw protocol as a REPL" — every command above is directly reachable via its `long`/`arm`/`mode`/`wp` modules, e.g. `arm throttle`, `arm throttle force`, `disarm force`, `mode GUIDED`, `long DO_SET_SERVO 5 1900`. Its `cmdlong` module is a generic `MAV_CMD` dispatcher — useful as a debugging tool against any implementation, including a custom rover firmware, since it doesn't assume ArduPilot-specific mode tables the way QGC/MP's typed UI does. [MAVProxy Arming docs](https://ardupilot.org/mavproxy/docs/uav_configuration/arming.html), [mavproxy_cmdlong.py](https://github.com/ArduPilot/MAVProxy/blob/master/MAVProxy/modules/mavproxy_cmdlong.py)

---

## 4. Mission protocol — one page

Source: [Mission (Plan) Protocol](https://mavlink.io/en/services/mission.html).

### 4.1 Upload (GCS → vehicle)

```
GCS  --MISSION_COUNT(n)-->            vehicle
GCS  <--MISSION_REQUEST_INT(seq=0)--  vehicle
GCS  --MISSION_ITEM_INT(seq=0)-->     vehicle
GCS  <--MISSION_REQUEST_INT(seq=1)--  vehicle
GCS  --MISSION_ITEM_INT(seq=1)-->     vehicle
        ... repeat for all n items ...
GCS  <--MISSION_ACK(result)--         vehicle
```

### 4.2 Download (vehicle → GCS)

```
GCS  --MISSION_REQUEST_LIST-->        vehicle
GCS  <--MISSION_COUNT(n)--            vehicle
GCS  --MISSION_REQUEST_INT(seq=0)-->  vehicle
GCS  <--MISSION_ITEM_INT(seq=0)--     vehicle
        ... repeat ...
GCS  --MISSION_ACK-->                 vehicle
```

### 4.3 Timing

| Parameter | Value |
|---|---|
| Default message timeout | 1500 ms |
| Per-plan-item timeout | 250 ms |
| Max retries | 5 |
| On timeout exhaustion | abort, both sides return to idle |

### 4.4 `MISSION_ITEM_INT` fields

| Field | Type | Meaning |
|---|---|---|
| `seq` | uint16 | sequence number (0-based; **ArduPilot reserves `seq==0` for the home position**, not the first real waypoint — a common off-by-one trap) |
| `command` | uint16 | the `MAV_CMD` this item represents (`NAV_WAYPOINT`, `NAV_TAKEOFF`, `DO_SET_SERVO`, …) |
| `frame` | uint8 | `MAV_FRAME` |
| `current` | uint8 | `0`=normal item, `1`=this is the currently-active item (status, not a request), **`2`=ArduPilot's "guided-mode single item" extension** — see §5 |
| `autocontinue` | uint8 | auto-advance to the next item on completion |
| `param1..4`, `x`/`y`(int32, lat/lon×1e7)/`z`(float) | — | same layout as `COMMAND_INT` |
| `mission_type` | uint8 | distinguishes the three plan types sharing this protocol: `MAV_MISSION_TYPE_MISSION` (flight plan, `NAV_*`/`DO_*`/`CONDITION_*`), `MAV_MISSION_TYPE_FENCE` (`NAV_FENCE_*`), `MAV_MISSION_TYPE_RALLY` (`NAV_RALLY_POINT` only) |

### 4.5 `MAV_MISSION_RESULT` (in `MISSION_ACK`) `[recall, standard/stable enum]`

| Value | Name |
|---|---|
| 0 | `ACCEPTED` |
| 1 | `ERROR` |
| 2 | `UNSUPPORTED_FRAME` |
| 3 | `UNSUPPORTED` |
| 4 | `NO_SPACE` |
| 5 | `INVALID` |
| 6–12 | `INVALID_PARAM1`..`INVALID_PARAM7` |
| 13 | `INVALID_SEQUENCE` |
| 14 | `DENIED` |
| 15 | `OPERATION_CANCELLED` |

Any non-`ACCEPTED` result is treated as a hard failure of the whole transfer, not a per-item warning — both sides reset to idle and the GCS must restart the upload from scratch.

### 4.6 ArduPilot deviations from the spec `[all confirmed via mavlink.io mission service page]`

- `seq==0` is reserved for home, not the first waypoint.
- Uploads are **not atomic** — a failure partway through can leave a mixed old/new mission on the vehicle.
- Float rounding on upload/download round-trips can cause the re-downloaded mission to differ slightly from what was uploaded.
- A NACK mid-transfer doesn't always terminate the upload cleanly.
- A mission **cannot be cleared while AUTO mode is actively executing it**.
- ArduPilot doesn't implement explicit operation-cancellation the way the spec describes.

### 4.7 Rover-specific mission semantics

- **Altitude (`z`/param7) in `NAV_WAYPOINT` and friends is ignored** — a ground vehicle has no meaningful altitude target. `[recall, corroborated indirectly by rover mission-command doc listing `NAV_LOITER_TURNS`/`NAV_PAYLOAD_PLACE` as "Copter and Plane only" while altitude-bearing items remain listed for Rover with alt silently unused]`
- Speed during a mission is governed by `WP_SPEED` (a parameter, the default cruise speed) and can be overridden per-mission via `DO_CHANGE_SPEED` items interspersed between waypoints. [Rover mission command list](https://ardupilot.org/rover/docs/common-mavlink-mission-command-messages-mav_cmd.html)
- `NAV_WAYPOINT` param1 (hold time) and param2/param3 (acceptance/pass radius) both apply to Rover the same way they do for Copter/Plane — the vehicle pauses at the waypoint for the hold time before continuing.
- `NAV_LOITER_TURNS`, `NAV_PAYLOAD_PLACE` are explicitly Copter/Plane-only per ArduPilot's own doc — a rover implementation should reply `UNSUPPORTED` for these rather than silently accepting and ignoring.
- Rover-only mission extras: `DO_SET_REVERSE` (drive backward for part of a mission), `DO_SET_RESUME_REPEAT_DIST` (rewind distance on mission resume) — both `ArduPilotMega`-dialect or Rover-specific, verify id against the exact dialect XML before hardcoding. `[recall, low confidence on exact ids]`

---

## 5. Guided navigation: DO_REPOSITION vs SET_POSITION_TARGET vs the mission-item trick

Three distinct mechanisms exist for "make the vehicle go here right now while in Guided mode," and practitioners genuinely mix them:

| Mechanism | Message | Repeat requirement | Who actually uses it |
|---|---|---|---|
| One-shot command | `MAV_CMD_DO_REPOSITION` (192) via `COMMAND_LONG`/`COMMAND_INT` | send once; vehicle drives to the target and holds — no resend needed | **QGC's "Go To Location"** on ArduPilot, when the vehicle reports support for the command ([APMFirmwarePlugin.cc](https://github.com/mavlink/qgroundcontrol/blob/master/src/FirmwarePlugin/APM/APMFirmwarePlugin.cc)) |
| Streamed setpoint | `SET_POSITION_TARGET_GLOBAL_INT` (#86) / `SET_POSITION_TARGET_LOCAL_NED` (#84) | **must be re-sent at least every ~1s; the vehicle stops after ~3s of silence** ([ArduPilot rover guided-mode commands doc](https://ardupilot.org/dev/docs/mavlink-rover-commands.html)) | Companion computers doing continuous offboard control (visual servoing, follow-me); not what a click-once GCS button uses |
| Mission-item "guided single item" trick | `MISSION_ITEM_INT` with `command=NAV_WAYPOINT` (16) and **`current=2`** | send once, no ack loop beyond the normal mission-item handshake | **Mission Planner's "Fly To Here"** (right-click map, Guided mode) — an ArduPilot-specific convention distinct from a normal mission upload; not part of the general MAVLink spec |

**Practical read for a rover GCS-facing surface:** implement `DO_REPOSITION` first — it's what modern QGC sends, it's one-shot (simpler state machine than a streamed setpoint), and it's explicitly designed for this ("intended for guided commands... for missions use `MAV_CMD_NAV_WAYPOINT` instead" — this is the command's own documented purpose). Support `SET_POSITION_TARGET_GLOBAL_INT`/`_LOCAL_NED` next if/when a companion-computer or continuous-control use case shows up (note the 3-second stop-on-silence failsafe is a *feature* to replicate, not a bug — it's the deadman for streamed setpoints, same idea as this repo's manual-control watchdog). The `MISSION_ITEM_INT current=2` trick is lowest priority — it's a Mission-Planner/ArduPilot-specific back channel, not something a from-scratch GCS or this platform's own web UI would need to originate, though a rover should tolerate receiving it (interpret as a `DO_REPOSITION`-equivalent) if it wants to look sane to Mission Planner specifically.

**Known caveat to carry into implementation:** `DO_REPOSITION` on ArduPilot Rover has an open bug report of returning `MAV_RESULT_DENIED` in 4.6.3 ([Discourse thread](https://discuss.ardupilot.org/t/rover-4-6-3-mav-cmd-do-reposition-returns-mav-result-denied/145240)) — if this platform ever talks to real ArduPilot Rover firmware (vs. its own custom ESP32 firmware) for guided goto, don't assume `DO_REPOSITION` is unconditionally reliable; a `SET_POSITION_TARGET_GLOBAL_INT` fallback path is worth keeping in the design even if `DO_REPOSITION` is primary.

---

## 6. Minimum honest rover command surface

"Honest" here means: the firmware doesn't have to *implement* the behavior, but it must **answer with a correct `COMMAND_ACK`/`MISSION_ACK`** (typically `UNSUPPORTED`) rather than staying silent — silence reads as "link is dead" to QGC/MP/MAVProxy and triggers their own timeout/retry UI, which is a worse experience than an honest "no."

| Category | Must answer | Why |
|---|---|---|
| **Identity** | `HEARTBEAT` (#0) at 1Hz, unconditionally, with a correct `MAV_TYPE` (`MAV_TYPE_GROUND_ROVER`, not `MAV_TYPE_GENERIC`) and `MAV_AUTOPILOT` | every GCS's connect flow gates on the first heartbeat; wrong `MAV_TYPE` breaks QGC's UI chrome (it picks icons/available actions off this) |
| **Arm/disarm** | `COMPONENT_ARM_DISARM` (400) — real implementation, this is core | every session lifecycle in §3 starts here |
| **Mode** | `DO_SET_MODE` (176) — real implementation for at least MANUAL/HOLD/GUIDED/AUTO/RTL, `ACCEPTED`/`FAILED` correctly | mode dropdown is the single most-used control surface in both QGC and MP |
| **Streams** | `SET_MESSAGE_INTERVAL` (511) and ideally `REQUEST_MESSAGE` (512) — even a partial implementation covering `GLOBAL_POSITION_INT`, `ATTITUDE`, `SYS_STATUS`, `VFR_HUD`, `RC_CHANNELS` | QGC's connect sequence explicitly requests these; refusing silently makes half the Fly-view instruments go blank with no error shown |
| **Params** | `PARAM_REQUEST_LIST`/`PARAM_REQUEST_READ`/`PARAM_VALUE`/`PARAM_SET` — can be a tiny fixed param set, but must exist | QGC's connect flow blocks/spinners on param download; a GCS that never gets a terminal `PARAM_VALUE` count can hang its UI |
| **Mission (minimal)** | `MISSION_REQUEST_LIST`/`MISSION_COUNT` (answer `0` if no mission stored) + `MISSION_ACK` with a correct `MAV_MISSION_RESULT` on any upload attempt (`UNSUPPORTED` is fine if missions genuinely aren't implemented yet) | both QGC and MP probe for an existing mission on connect; an unanswered `MISSION_REQUEST_LIST` is another silent hang |
| **Navigation, honest subset** | `NAV_WAYPOINT` (16, only relevant as mission-item), `NAV_RETURN_TO_LAUNCH` (20), `DO_REPOSITION` (192), `DO_CHANGE_SPEED` (178), `DO_SET_HOME` (179) — answer `UNSUPPORTED` for anything not yet implemented (`DO_PAUSE_CONTINUE` 193 included, since QGC's own Pause button is inconsistent about which of 192/193 it sends — see §3.2) | these are the commands a rover GCS session actually exercises during normal driving |
| **Preflight** | `PREFLIGHT_REBOOT_SHUTDOWN` (246) at minimum (`ACCEPTED` then actually reboot) — `PREFLIGHT_CALIBRATION` (241) can legitimately be `UNSUPPORTED` if there's no interactive calibration flow, but must not hang | "reboot vehicle" is a stock button in both GCSs after any param change |
| **Aux** | `DO_AUX_FUNCTION` (218), `DO_SET_SERVO` (183), `DO_SET_RELAY` (181) can all legitimately reply `UNSUPPORTED` until payload hardware exists — but must reply | Mission Planner's Actions tab sends these on a single click; a hang here looks identical to a dead link |
| **Everything else in §2** | reply `MAV_RESULT_UNSUPPORTED` (3) — never silence | this is the actual point of "honest": an explicit "I heard you, I don't do that" is indistinguishable from a working link at the transport level, whereas silence is indistinguishable from a dead one |

**One extra rule worth calling out explicitly for this project:** per CLAUDE.md's failsafe/up-to-date priority (§9) and the existing manual-control watchdog design already in this codebase (per R3), any streamed-setpoint mechanism this rover ever implements (`SET_POSITION_TARGET_*`, `RC_CHANNELS_OVERRIDE`) should replicate the "stop after ~3s of silence" convention ArduPilot itself uses — it's not just an ArduPilot quirk, it's the correct failsafe shape for any streamed command channel, and it's exactly the deadman-watchdog pattern already built for `RC_CHANNELS_OVERRIDE` in `DefaultManualControlService`.
