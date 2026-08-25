# Rover firmware ↔ platform MAVLink alignment

**Status:** active · **Started:** 2026-08-25 · **Branch:** `feat/controller-setup-c15`

The ESP32 rover firmware lives outside this repo, in the Arduino sketchbook at
`~/Arduino/ardupoilot-start`. `infra/rover-sim/` compiles its real sources on the host, so
everything asserted here is asserted against what gets flashed. See
[`infra/rover-sim/README.md`](../../../infra/rover-sim/README.md).

## 1. Why

The firmware was written against the platform's *telemetry* contract and got that right — all
eight streams it pushes are decoded. It was never aligned to the platform's **command** surface,
which has grown since: onboarding probes, aux functions, message-interval remediation. Three
operator-visible things are broken or dishonest today, and one hardware risk is only half-fixed.

## 2. Command surface, as of this branch

Everything the platform can put on the wire, and where the firmware stood before this work.

| Wire | Sent by | Before |
|---|---|---|
| `RC_CHANNELS_OVERRIDE` #70, ch1–8 (+ch9–18 `IGNORE`) | `MavlinkManualControlSender` → `ManualControlService` | ✅ correct |
| `MAV_CMD_COMPONENT_ARM_DISARM` 400 | `arm` / `disarm` / `emergencyStop` | ✅ correct, force magic honoured |
| `MAV_CMD_DO_SET_MODE` 176 | `setMode`, **and `returnToHome` (RTL = custom_mode 11)** | ⚠️ MANUAL(0)+HOLD(4) only |
| `MAV_CMD_DO_AUX_FUNCTION` 218 | `POST …/aux-function`, 13-entry catalog | ❌ acked UNSUPPORTED |
| `MAV_CMD_SET_MESSAGE_INTERVAL` 511 | `MavlinkVehicleConfigurator`, `MavlinkConnectRemediator` | ❌ acked UNSUPPORTED |
| `MAV_CMD_REQUEST_MESSAGE` 512 → `AUTOPILOT_VERSION`(148) | probe capability stage | ❌ acked UNSUPPORTED |
| `PARAM_REQUEST_READ` 20 (×20 names) / `PARAM_SET` 23 | probe parameter stage, remediation | ❌ dropped silently |
| GCS `HEARTBEAT` | **nothing** — `HeartbeatService` exists in `mavlink-core`, wired nowhere | ✅ firmware's assumption holds |

Platform → vehicle addressing is fixed at sysid 255 / comp 190 → sysid 1 / comp 1.

## 3. Decisions taken (operator, 2026-08-25)

**D1 — RTL stays rejected.** The rover has no GPS; `RTL`, `SMART_RTL`, `AUTO`, `GUIDED`,
`LOITER`, `FOLLOW`, `SIMPLE` are all acked `MAV_RESULT_UNSUPPORTED`. Only `MANUAL` and `HOLD`
are honoured. Consequence, accepted knowingly: vision's **Return home** button and aux function 4
stay dead against this vehicle, and say so on the wire rather than pretending.

**D2 — Full alignment otherwise.** Parameter protocol, `AUTOPILOT_VERSION`,
`SET_MESSAGE_INTERVAL`, `DO_AUX_FUNCTION` and `STATUSTEXT` all implemented, each covered by a
rover-sim regression test.

**D3 — Motor transitions must be smooth and safe against opposing current.** See §5.

## 4. What the firmware gains

* **Parameter protocol** — `PARAM_REQUEST_READ`, `PARAM_REQUEST_LIST`, `PARAM_SET`, replying
  `PARAM_VALUE`. Backed by `ParameterStore`, which owns a *mutable* `AppConfig` seeded from
  `appConfig()`. Modules already hold `const X&` references into it, so a write lands on the
  running vehicle with no module change and no reflash.
* **`AUTOPILOT_VERSION`** on `MAV_CMD_REQUEST_MESSAGE` — the probe's capability stage passes.
* **`SET_MESSAGE_INTERVAL`** — remaps the seven stream periods, which are now store cells.
* **`DO_AUX_FUNCTION`** — the switch functions a rover can honour (31/81/153/165); the rest
  rejected. Function 4 (RTL) rejected under D1.
* **`STATUSTEXT`** — arm, disarm, failsafe, and every rejection, surfaced in the platform UI.
* **`SERVO_OUTPUT_RAW`** + **`SYSTEM_TIME`** — two of the three streams the on-connect list asks
  for. `SCALED_IMU2` is deliberately not sent: there is no second IMU to report.

## 5. Motor safety — the part that burns hardware

`reversalDeadTimeMs` (added 2026-08-23 after the L298N failed) fixes **shoot-through**: both legs
of one bridge conducting at once. It does *not* fix the second failure mode, which is what D3 is
about.

Ramping straight through zero into the opposite direction energises the bridge **against a still-
spinning armature**. The motor's back-EMF adds to the applied voltage instead of opposing it, and
the current that flows is not limited by the winding resistance alone. On a bipolar bridge this is
how you get a hot leg without ever shorting the supply.

The fix is a three-phase reversal, all three durations configurable:

```
 demand  ────────╮                        ╭────────
                 ╰──╮                  ╭──╯
 output  ───────────╰──┬───┬───┬───────╯
                 decel │coast│dead│ accel
                       │     │    │
        ramp down to 0 │hold │both│ ramp up in the new direction
        (fast — no     │at 0 │low │ (gentle — inrush into a stalled
         opposing      │motor│    │  armature)
         current)      │spins│    │
                       │down │    │
```

* `decelPerSecond` > `accelPerSecond`: shedding duty costs nothing, adding it does.
* `reversalCoastMs` + `reversalCoastPerUnit × speed` — coast scales with the speed being left,
  so a crawl reverses briskly and a full-speed reversal waits.
* `reversalDeadTimeMs` — unchanged, still the electrical gap.

`stop()` keeps its immediate cut: zero duty on all four pins is a coast, which is the safest
electrical state there is, and a failsafe must not ramp.

## 6. Files

**Firmware** (`~/Arduino/ardupoilot-start`): `MavlinkProtocol.h`, `ParameterStore.{h,cpp}` (new),
`MavlinkUdpLink.{h,cpp}`, `HBridgeMotorDriver.{h,cpp}`, `Config.{h,cpp}`, `ardupoilot-start.ino`,
`README.md`.

**Harness** (`infra/rover-sim/`): `command_fixture.py`, `command_test.cpp`, `command_verify.py`
(all new — planned as `param_test.cpp`, split into fixture/driver/verifier to match the existing
`wire_*` trio), `motor_test.cpp`, `wire_test.cpp`, `session_test.cpp`, `rover_host.cpp` (the last
three rewired to `ParameterStore`), `Makefile`, `README.md`.

## 7. Verification

`make check` in `infra/rover-sim/` — wire, session, motors, **commands**. Every byte the firmware
transmits is decoded by pymavlink with `robust_parsing=False`, so a wrong CRC seed or a field at
the wrong offset fails the run rather than being skipped.

## 8. Outcome (2026-08-25) — done

`make clean && make all` builds with **zero warnings**; all four suites pass. Measured, not
asserted-as-boolean:

| | Result |
|---|---|
| `wire` | 11 datagrams / 414 bytes, all decoded |
| `session` | arm cold → drive → stall → re-arm, incl. the RC5 interlock |
| `motors` | reversal **340 ms** at full speed vs **240 ms** from a crawl; **19** ramp-up ticks vs **7** down |
| `commands` | **57** datagrams, **28** parameters, 32 `PARAM_VALUE`s, 10 acks, 4 `STATUSTEXT`s |

### Two real bugs the harness caught that reading the code did not

1. **`MAV_PARAM_TYPE` was wrong.** UINT16 and UINT32 were set to 4 and 6, which are `INT16` and
   `INT32`. Every unsigned parameter would have been mistyped on the wire.
2. **`MAV_PROTOCOL_CAPABILITY` was wrong.** `PARAM_FLOAT` was on bit 0 — which is
   `MISSION_FLOAT` — and `MAVLINK2` on bit 17 instead of 13. The rover was falsely advertising
   mission support to a platform that would then have offered mission upload against a vehicle
   with no position estimate.

Both are the same class of error: a constant recalled rather than checked. The fix was to query
`pymavlink` for each value instead of trusting memory, and the verifier now asserts the capability
word by **exact equality** rather than checking individual bits, so a future stray bit fails.

### One harness bug that made a green run meaningless

The `Makefile` declared no header dependencies. `MavlinkProtocol.h` is almost entirely constants,
so a header-only edit — the normal way a wire bug is both introduced *and* fixed — left every
binary stale and the suite passing against the old code. This was mistaken for "the fix did not
work" before the cause was found. Every target now depends on `$(HEADERS)`.

### Decisions as built

- **D1 (reject RTL) held.** MANUAL and HOLD are accepted; every navigation-shaped mode is acked
  `MAV_RESULT_UNSUPPORTED` with a `STATUSTEXT` saying why. The accepted consequence: the app's
  **Return home** button and aux function 4 stay dead against this rover.
- **D2 (full alignment) complete** — param protocol, `AUTOPILOT_VERSION`, `SET_MESSAGE_INTERVAL`,
  `REQUEST_MESSAGE`, `DO_AUX_FUNCTION`, `STATUSTEXT`.
- **D3 (motor safety) complete**, and larger than specced: the plan had one coast, the build has
  a coast that **scales with speed** plus **split accel/decel rates**, because a single fixed
  figure sized for the worst case makes the rover feel dead at low speed.

### Not verified — the honest gap

**No motor has turned.** Every reversal timing is measured against captured LEDC writes on the
host. The thermal claim underneath — that coasting first keeps the leg cool — is reasoning about
back-EMF, not a measurement on hardware. The first real full-speed reversal should be done with a
finger on the bridge. Likewise nothing here has been driven by the app end to end since the
command surface landed; `make commands` proves the rover answers correctly, not that the app asks
what we think it asks.

### Java side

No Java changed, so no `MODULE.md` needed updating. `drone-link/mavlink` already documents the
command surface this firmware was aligned *to*; the alignment was one-directional.
