# MAVLINK-COMMANDS — frozen plan

**Status:** ACTIVE · **Branch:** `feat/mavlink-command-control` · **Frozen:** 2026-09-01 (Fable)
**Grounding:** [MAVLINK-COMMANDS-CONTEXT.md](MAVLINK-COMMANDS-CONTEXT.md) → R1–R4 reports + [O1-SYNTHESIS.md](mavlink-commands/O1-SYNTHESIS.md) (all rationale lives there; this file is the spec).

## Scope

Three tracks, one branch:
- **F (firmware)** — `~/Arduino/ardupoilot-start/` reliability/safety + `infra/rover-sim` harness repair. **Strictly serial** (sketch is outside git: no rollback, one agent at a time).
- **P (platform)** — one-shot command surface fixes in `drone-link/mavlink` (+ one vision-app wiring).
- **W (web)** — keyboard/controller action keys in `station/vision-web`.

Out of scope by decision (O1 §D2c/§D4): missions (MISSIONS-PLAN owns), `DO_REPOSITION`/click-to-go (no GPS), MAVLink signing, heap/persistence/boot-delay/flood-cap items, STATUSTEXT-on-DENIED plumbing, WiFi-RSSI trend.

## Decisions frozen (from O1, full rationale there)

| # | Decision |
|---|---|
| D1 | Keyboard + gamepad ride the existing `RC_CHANNELS_OVERRIDE` stream (33 Hz, client-side send-on-arrival, 3-frame release burst). No `MANUAL_CONTROL` sender. |
| D2a | `vision.mavlink.command-retries: 2` (default), `ack-timeout` re-scoped to 700 ms per attempt. Only absolute-state commands retry (rule, not list). Default flip gated on F0's idempotency test. |
| D2b | Force-arm magic split: `2989` on arm, `21196` on disarm (today both send 21196 — live defect, works only via ArduPilot bug #32996). |
| D2c | Add platform callers: `REQUEST_MESSAGE`(512) → `AUTOPILOT_VERSION` on peer claim; `SET_MESSAGE_INTERVAL`(511) → cockpit streams at configured rates. Trim `Initialising` from `selectableModes`. |
| D2d | Every keyboard/controller command routes through `ControlActionDispatcher` → REST → outcome toast. No fire-and-forget key. |
| D3 | Space = e-stop (immediate, Hold-not-disarm); `Shift+Enter` held 600 ms = toggle-arm vs live telemetry; `1`–`4` = vehicle's own `selectableModes` (dangerous modes inherit hold). Held-key-set polling; blur clears axes **and** action holds; `isTypingTarget()` guards; expo/deadzone client-side only. |
| D4 | Firmware order: F0 harness → F1 wdt → F2 dtMs clamp → F3 network-down test → F4 peer gate + TX STATUSTEXT. **F1 registers the WDT at the END of setup()** (after the 13 s boot delay) or it bricks the boot. |
| D5 | Inherited uncommitted wave: two commits inside F0, both behind green gates; delete `esc_arm_tune.py` (tunes `EscArmingConfig`, which no longer exists). |

## Assumptions (user was away; each cheap to reverse)

- **A1** Keyboard arming is allowed, bound to `Shift+Enter` 600 ms hold. If the user wants click-only arm, delete one entry in the key map.
- **A2** The sketch is **not** vendored into the repo this effort; recommended as a follow-up structural decision (`firmware/rover-esp32/`) — it would let firmware waves parallelize and be revertable.
- **A3** Retry default ships as 2 once F0's idempotency case is green; `command-retries: 0` restores byte-for-byte today's behavior. Real-hardware TX remains operator-gated regardless.

## Waves

| Wave | Agent | File scope (disjoint) | Gate | After |
|---|---|---|---|---|
| **F0** | firmware (general) | `infra/rover-sim/{Makefile,motor_test.cpp,session_test.cpp,session_fixture.py,command_verify.py,command_test.cpp,README.md}`; delete `esc_arm_tune.py`; add command-idempotency case; commit inherited set + separately `mavlink-core/DefaultPeerDirectory.java` | `make check` all six targets green; `./mvnw -B -pl drone-link/mavlink-core -am test` | — |
| **F1** | firmware | `~/Arduino/.../ardupoilot-start.ino`; `infra/rover-sim/shim/` esp_task_wdt shim | `make check` | F0 |
| **F2** | firmware | `~/Arduino/.../VehicleController.cpp`; new `motor_test.cpp` case | `make check` | F1 |
| **F3** | firmware | `infra/rover-sim/{session_test.cpp,session_fixture.py}`; comment at `MavlinkUdpLink.cpp:202` | `make check` | F2 |
| **F4** | firmware | `~/Arduino/.../MavlinkUdpLink.{cpp,h}`; `link_test.cpp`; firmware README | `make check` | F3 |
| **P1** | adapter-builder | `drone-link/mavlink/.../{MavlinkFlightCommander,FlightModes}.java`, `MavlinkSettings`, tests, `application.yaml` mavlink block, `drone-link/mavlink/MODULE.md` | `./mvnw -B -pl drone-link/mavlink -am test` | — |
| **P2** | adapter-builder | new `MavlinkStreamNegotiator` + test + claim wiring point, MODULE.md | same | P1 |
| **P3** | spring-integrator | `station/vision-app` `onLinkFailure` listener wiring + test | `./mvnw -B -pl station/vision-app -am test` (Docker) | — |
| **W1** | web-ui | `core/rc/` keyboard action keys: `keyboard-rc-input.service.ts`, `control-action-dispatcher.ts`, new `keyboard-action-logic.ts`, specs | `npm run test:ci` | — |
| **W2** | web-ui | Controller drawer / transmitter view key-map display + specs | `npm run test:ci` | W1 |
| **X** | docs | `docs/plans/README.md` row, context file close-out, memory | — | all |

Parallel lanes: **F-track ‖ P1 ‖ W1 ‖ P3**; then P2, W2. F waves serial internally.

## Standing rules for every wave agent

- Scoped builds only (`-pl`, `make check`); **never** reactor-wide; run gates in the **foreground and wait** — a backgrounded build dies with your turn.
- `git add` only your scoped paths explicitly; commit your own wave with a conventional message; shared working tree — never `git stash`/`reset` anything outside your scope.
- MODULE.md update is part of your wave, not a separate one.
- vision-web tests: `npm run test:ci`, never bare `npx vitest run`.
- F1 bench verification (hang `loop()` armed, confirm reset) is **operator work** — record as open, do not claim it.
