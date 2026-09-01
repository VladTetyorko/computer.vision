# MAVLINK-COMMANDS — task context

**Started:** 2026-09-01 · **Branch (planned):** `feat/mavlink-command-control` · **Status:** research fan-out

## The ask (user, verbatim intent)

1. **Research** MAVLink commands: what they are used for in ground stations, in drones,
   in RC cars/rovers, in navigation. Sonnet agents research, Opus summarizes.
2. **Then**: how to use those commands when operating with a **controller/keyboard**,
   and start implementation (delegated to agents).
3. **Firmware**: the ESP32 MAVLink sketch — found at `~/Arduino/ardupoilot-start/`
   (~4.5k lines) — make it **more reliable and safe**; research what other RC-car
   hardware/software does and adopt it.

## State found at start (2026-09-01)

- Firmware `~/Arduino/ardupoilot-start/`: layered (ino → VehicleController →
  IMotorDriver/ICommandLink/INetworkLink; MavlinkV2Codec + MavlinkUdpLink + ParameterStore
  + Tb6612MotorDriver). Arm/disarm = TB6612 STBY pin; per-axis reversal state machine
  (dead-time + coast) after the L298N burnout of 2026-08-23. No ESC handshake any more.
- Uncommitted work in the repo (previous session, command-surface wave):
  `infra/rover-sim/` command_test/command_fixture/command_verify (+ Makefile/README/motor/
  session/wire updates, shim IPAddress.h) and `mavlink-core/DefaultPeerDirectory.java`.
  - `make check`: **wire_test PASSES incl. command surface** (param protocol,
    REQUEST_MESSAGE, SET_MESSAGE_INTERVAL, DO_AUX_FUNCTION, mode table, COMMAND_ACK).
  - `session_test` **does not compile**: fake still overrides `armEsc`/`cancelEscArming`,
    removed from `IMotorDriver`. Repair in implementation wave.
- Related merged history: RC-CONTROL Phase 1 (manual control SITL), FLEET-RADIO R0-R7,
  zero-config Z1-Z5 (lobby binds :14550 at boot), CONTROLLER-UX cycles 1-3.
  Zero-config Z6 (firmware side) was open — the uncommitted rover-sim work is that thread.

## Delegation plan

| Wave | Agent | Scope |
|---|---|---|
| R1 | Sonnet, web | MAV_CMD catalog: what GCSs send, drone vs rover applicability, ACK semantics → `mavlink-commands/R1-command-catalog.md` |
| R2 | Sonnet, web | Manual control: MANUAL_CONTROL vs RC_OVERRIDE vs GUIDED targets; QGC joystick/keyboard mapping; ArduPilot Rover modes+failsafes → `R2-manual-control.md` |
| R3 | Sonnet, local | vision codebase: current command surface + control input path (web→api→flight→adapter-mavlink→UDP), gaps → `R3-codebase-inventory.md` |
| R4 | Sonnet, local+web | Firmware audit vs RC-car reliability practice; ranked defect list → `R4-firmware-audit.md` |
| O1 | Opus | Summarize R1-R4 → update this file + write plan skeleton |
| P  | Fable | Freeze plan `MAVLINK-COMMANDS-PLAN.md`: waves, wire contract, disjoint scopes |
| I* | Sonnet | Implementation waves (platform + web + firmware + rover-sim harness repair) |

## Constraints

- CLAUDE.md dependency rule; Spring only in app/api/adapters. Scoped builds only (`-pl`).
- Firmware edits happen in `~/Arduino/ardupoilot-start/` (outside git repo!) — rover-sim
  compiles those sources directly; `make check` in `infra/rover-sim` is its regression gate.
- Command TX to real hardware stays operator-gated; SITL/rover-sim is the proving ground.
- Failsafe/up-to-date priority (CLAUDE.md §9): newest command wins, stale links fail safe.

## Synthesis (O1)

R1-R4 merged into **[mavlink-commands/O1-SYNTHESIS.md](mavlink-commands/O1-SYNTHESIS.md)** —
decisions + wave draft for Fable to freeze. Decided:

- **Transport**: keyboard *and* gamepad keep riding `RC_CHANNELS_OVERRIDE` (R2 confirmed); no parallel
  `MANUAL_CONTROL`. Send loop stays client-side (send-on-arrival, ~9ms).
- **Commands**: force-arm magic is a live defect — we send `21196` (force-*disarm*) on arm, which
  ArduPilot mishandles as a checks-bypassing force-arm; must be `2989`. Retries go 0 → `command-retries: 2`
  with `ack-timeout` re-scoped to 700ms per attempt (≤2.1s total, no latency regression), retry only
  absolute-state commands. Add platform callers for `REQUEST_MESSAGE`/`SET_MESSAGE_INTERVAL`. Defer
  `DO_REPOSITION` (no GPS on the rover).
- **Already done, contrary to the brief**: the full Rover mode table ships and feeds the UI via
  `selectableModes` — only `Initialising` needs trimming.
- **Keyboard contract**: Space = e-stop immediate; Shift+Enter held 600ms = toggle arm; 1-4 = vehicle's
  own modes. Action keys enter the existing `ControlActionDispatcher`, so no key gets a fire-and-forget
  path and no arm can silently no-ack. Blur clears holds as well as axes. Expo/deadzone client-side only.
- **Firmware order** (R4 §6 confirmed): harness repair → `esp_task_wdt` → `dtMs` clamp → network-down
  test → learned-peer gate + TX `STATUSTEXT`. Cut there. Flagged: the WDT must register *after* the 13s
  boot delay or it bricks the boot.
- **Inherited wave**: commit inside F0 behind a green `make check` (two commits: mavlink-core flap
  warning, then rover-sim set + repair). Delete `esc_arm_tune.py` — `EscArmingConfig` no longer exists.
- **Open for the user**: may the keyboard arm at all? vendor the sketch into the repo? confirm the retry
  default flip.

## Close-out (2026-09-01)

All waves shipped on `feat/mavlink-command-control` (12 commits, master..HEAD), every gate green:

| Wave | Commit | Result |
|---|---|---|
| F0 | `c153603a` + `aaa1438c` | flap warning committed; harness fully repaired (motor_test rewritten to TB6612, session fake aligned, idempotency case added, esc_arm_tune.py deleted) — six binaries / five suites green |
| F1 | firmware-only | esp_task_wdt 3000ms, registered end-of-setup() (boot-delay safe), fed per loop() |
| F2 | `c8a15c20` | dtMs clamped to 2×controlPeriodMs; stall can no longer bypass the accel ramp |
| F3 | `0f7b3ee3` | network-down failsafe proven: link clock inert in outage, controller clock alone zeroes demand ≤ commandTimeoutMs+controlPeriodMs, coast path, resume works. No defect found |
| F4 | `c13451a6` | learned-peer command gate (500ms silence re-learn; telemetry re-learn untouched) + TX-failure STATUSTEXT; README security-model section |
| P1 | `cc057ede` | force-arm magic split 2989/21196 (live defect — was the checks-bypassing ArduPilot-bug variant), bounded retries, Initialising trimmed |
| P2 | `0e55a83f` | MavlinkStreamNegotiator on claim: REQUEST_MESSAGE(AUTOPILOT_VERSION) + SET_MESSAGE_INTERVAL×6 @4Hz; two real ack-correlation collision bugs found+fixed; SITL-verified |
| P3 | `6897fdbb` | onLinkFailure verified already wired by construction; wiring test pins status pipe |
| P4 | `169f8665` | production wiring gets 700ms×3 (was 2s single-shot); call-site update per CLAUDE.md rule 10 |
| W1 | `779aec63` | keyboard action keys through ControlActionDispatcher; blur clears holds; +39 specs |
| W2 | `fe9b3bd2` | key map in the transmitter picture: live verb/mode names, hold-sweep idiom |

Firmware changed (outside git — backups fw-backup-F1..F4 under the job tmp dir): ardupoilot-start.ino,
Config.h/.cpp, VehicleController.cpp, MavlinkUdpLink.h/.cpp, README.md.

**Open, deliberately:** merge; F1 bench WDT reset test (operator, powered hardware); live rover drive
(operator-gated); capabilities()↔CapabilityReport wiring; stream ids/rates → VisionMavlinkProperties;
MAVLink signing; vendoring the sketch into the repo (recommended follow-up — assumption A2).
