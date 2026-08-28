# infra/sitl — fleet-in-a-box (real ArduPilot SITL)

`./up.sh N [copter|rover]` launches **N real ArduPilot SITL instances** in docker, each a
distinct MAVLink sysid, each pushing MAVLink 2 UDP to the host — full PreArm/EKF/failsafe
realism the platform's own synthetic simulators (`adapter-simulation`) can't produce.
`docs/plans/active/DRONE-INFRA-PLAN.md` I-c, extended to a second vehicle by
`docs/plans/active/FLEET-RADIO-PLAN.md` R7.

A **copter** (the default) flies a small default circuit near its home location: GUIDED
takeoff → CIRCLE. A **rover** arms in MANUAL and stops there, deliberately — it is here to be
*driven*, and a vehicle already running its own guided mission would fight the RC overrides the
tests send it. MANUAL is also the mode a rover legitimately operates in with **no GPS fix at
all**, which is exactly the case that used to read as a hard preflight failure.

## Prerequisites

- Docker (tested against Docker 28.3.3 / Compose v2.39.1; any reasonably current docker with
  `--add-host ...:host-gateway` support works — that's been available on Linux since Docker
  20.10).
- Nothing else — the image bundles everything it needs (see "Provenance" below).

## Quick start

```
./up.sh                      # 1 copter, sysid 1 (CI-style smoke)
./up.sh 5                    # 5 copters, sysid 1..5
./up.sh 2 rover              # 2 rovers, sysid 1..2
docker logs -f vision-sitl-copter-1    # watch a given instance boot/arm/fly
./down.sh                    # tear down every instance up.sh started, however many
```

A **mixed** fleet is two calls; `SYSID_BASE` keeps their sysids and their SITL port offsets
from colliding:

```
./up.sh 2                        # copters, sysid 1..2
SYSID_BASE=10 ./up.sh 1 rover    # rover,   sysid 10
```

One port carrying a copter *and* a rover is the `FLEET-RADIO-PLAN` exit gate itself (§6):
per-peer drop rate, the rover's own mode table rather than the copter's, and "an unidentified
vehicle cannot be engaged" are all claims that only mean something with a second,
differently-shaped vehicle on the wire. Read the single-port limit below before expecting the
platform to show you both as separate assets today.

For the single-instance case, `docker compose up -d --build` (this directory's
`docker-compose.yml`) works too — and `docker compose --profile rover up -d --build` brings up
the copter *and* a rover on the same port under sysid 2. The rover sits behind a compose profile
so the plain single-vehicle smoke case is byte-identical to what it was before R7 — see that file's own header comment for why `up.sh` (not
`docker compose --scale`) is what generates N>1.

## Pointing the platform at it

Register a `mavlink` telemetry device listening on the port SITL pushes to (default 14550):

```
POST /api/devices
{ "name": "SITL fleet", "protocol": "mavlink",
  "uri": "udp://0.0.0.0:14550", "capabilities": ["TELEMETRY"] }
```

**Today's limit, read before running N>1 expecting N assets**: one device listening on a
given `udp://host:port` locks onto the first MAVLink sysid it hears on that socket and
silently drops every other sysid arriving on the same port
(`drone-link/mavlink/MODULE.md`, "Multiple systems on one port: first system id seen
wins, no re-election"). `./up.sh 5` genuinely produces 5 distinct sysids on the wire (verified
below) — but *today*, registering one device against `udp://0.0.0.0:14550` only ever shows you
sysid 1. The multi-vehicle single-port gateway (`docs/plans/active/DRONE-INFRA-PLAN.md` I-a, "MAVLink fleet
gateway") is what makes one port genuinely serve N vehicles as N assets; until it lands, either
run one device per sysid by pointing each SITL instance at its own port
(`MAVLINK_TARGET_PORT=14550`, `14551`, ... — set per-instance by exporting a different value
before each `up.sh 1` call, or editing the env inline in a copy of `up.sh`'s loop) or use this
fleet for exactly the single-vehicle-per-port case it already fully supports today.

## Env overrides

| Var | Default | Meaning |
|---|---|---|
| `MAVLINK_TARGET_HOST` | `host.docker.internal` | Where SITL pushes MAVLink (the docker host by default — the platform LISTENS, SITL always pushes, see adapter-mavlink's Gotchas) |
| `MAVLINK_TARGET_PORT` | `14550` | Target UDP port |
| `SITL_HOME` | `-35.363261,149.165230,584,353` (CMAC, `lat,lon,alt,heading`) | Home location for every instance in this run |
| `SITL_SPEEDUP` | `1` | ArduPilot SITL's own `--speedup` (simulation-time multiplier; raise for faster demos, keep at 1 for realistic timing) |
| `TAKEOFF_ALT_M` | `20` | GUIDED takeoff altitude (meters) before switching to CIRCLE. **Copter only** — a rover ignores it |
| `SYSID_BASE` | `1` | First sysid of this run, and the SITL instance offset. Raise it to add a second fleet without colliding with the first |
| `VEHICLE` | `copter` | `copter` or `rover`. Set by `up.sh`'s second argument; also readable directly by `docker run`/compose |

Set as shell env vars before calling `up.sh`, e.g. `MAVLINK_TARGET_PORT=15550 ./up.sh 3`.

## Provenance (official sources only, no ArduPilot build)

- **Flight code**: the official prebuilt ArduCopter SITL binary from
  `https://firmware.ardupilot.org/Copter/stable-4.7.0/SITL_x86_64_linux_gnu/arducopter`
  (ArduPilot's own release server — verified `curl -I` 200 OK 2026-07-28; that directory's
  `git-version.txt` attributes the build to ArduPilot/ardupilot commit `1511f27`, the 4.7.0
  release tag). Pinned to the numbered `stable-4.7.0` path, not the floating `stable` alias, so
  this image never silently changes under a future release — see `Dockerfile`'s own header
  comment for the full rationale and how to bump it.
  - **No maintained third-party SITL docker image was used.** Researched Docker Hub/GitHub for
    an ArduPilot-org-published, current SITL runtime image before writing this Dockerfile;
    found only individually-maintained community images (`radarku/ardupilot-sitl`,
    `edrdo/ardupilot-sitl-docker`, several forks) of unclear currency/trust, and ArduPilot's
    own `ardupilot/ardupilot-dev-*` images on Docker Hub are **build environments** (full
    toolchain + source checkout to compile ArduPilot yourself), not prebuilt SITL runtimes —
    using one would mean building ArduPilot from source, which this task's brief explicitly
    ruled out. Downloading the official prebuilt binary directly was the more trustworthy,
    verifiable path.
- **`autofly.py`'s MAVLink library**: `pymavlink==2.4.49` from PyPI, the ArduPilot-project-
  maintained Python MAVLink library (ships a manylinux wheel for this platform — no compiler,
  no ArduPilot source checkout).
- **Base image**: `python:3.12-slim` (Debian).

## How it flies (and why it needs help to)

Running the raw `arducopter` binary directly (deliberately, instead of `sim_vehicle.py` /
MAVProxy, which need a full ArduPilot source checkout) skips everything `sim_vehicle.py`
normally sets up for you. `entrypoint.sh` fills the gap with a `--defaults` param file
containing exactly what's needed to arm cleanly — `FRAME_CLASS`/`FRAME_TYPE` (a hard PreArm
safety check, not gated by `ARMING_CHECK`) and `INS_ACC*`/`COMPASS_OFS*` calibration markers
(same values ArduPilot's own `Tools/autotest/default_params/copter.parm` uses to mark a
simulated IMU "as calibrated" without a real calibration flight) — both reproduced and fixed
empirically against this image; see `entrypoint.sh`'s own comments for the exact PreArm text
each one fixes.

`autofly.py` is a small internal test-harness "GCS": it connects to SITL's own local control
port (`serial1`, never exposed outside the container — `serial0` is what `entrypoint.sh` points
at the platform), waits for the EKF to settle, arms (retrying every few seconds against
transient `PreArm`/`Arm` rejections — normal while GPS/EKF converge, not a bug), takes off in
GUIDED mode, then switches to CIRCLE so the vehicle flies a small circuit around its takeoff
point indefinitely. Failure here is non-fatal by design: if `autofly.py` ever gives up, the
container keeps running and SITL keeps pushing telemetry — the vehicle just stays on the
ground instead of flying, never taking the container down.

## Resource footprint per instance

Measured on this dev machine (docker 28.3.3, no other load):

| | |
|---|---|
| Image size | ~248 MB |
| RAM, steady state (post-boot, flying) | ~20-25 MB |
| CPU, steady state | ~5-6% of one core (`SITL_SPEEDUP=1`; scales up with speedup) |
| Boot-to-flying wall time | ~45-70s at default speed (EKF/GPS settle + arm-retry loop + climb) |

N instances scale roughly linearly in RAM/CPU (each is an independent process); network/CPU
contention on the docker host becomes the practical ceiling well before any single instance's
own footprint does.

## What was verified locally vs. researched

- **Verified, live, on this machine**: image builds; `up.sh 1` and `up.sh 2` both run
  end-to-end (arm → GUIDED takeoff → CIRCLE); a scratch Python UDP listener confirmed MAVLink 2
  datagrams (`0xFD` magic) arriving at the target host:port with the **correct, distinct sysid
  byte** for each instance (sysid 1 and 2 simultaneously on one port, from a 2-instance run);
  `down.sh` removed every container it started, confirmed via `docker ps -a --filter
  label=vision.sitl.fleet=true` returning empty afterward; per-instance resource footprint
  (table above) measured via `docker stats`.
- **Researched, not independently re-derived**: the `--serial0 udpclient:HOST:PORT` device
  string syntax and the `-I`/`--instance` "+10 per instance" port-offset convention (matched
  ArduPilot's own dev-docs example and the arducopter binary's own `--help`, then confirmed
  working live); the exact FRAME_CLASS/INS_ACC/COMPASS_OFS default values (copied verbatim from
  ArduPilot's own `Tools/autotest/default_params/copter.parm`, then confirmed working live —
  the "needs help to arm" section above is the live-verified part, the exact param *values*
  are the researched part).

## Sources

- [firmware.ardupilot.org — Copter stable-4.7.0 SITL build](https://firmware.ardupilot.org/Copter/stable-4.7.0/SITL_x86_64_linux_gnu/) —
  binary provenance.
- [ArduPilot dev docs — Using SITL](https://ardupilot.org/dev/docs/using-sitl-for-ardupilot-testing.html) —
  `--serial0=udpclient:<ip>:14550` push-mode example.
- [ArduPilot — Tools/autotest/default_params/copter.parm](https://github.com/ArduPilot/ardupilot/blob/master/Tools/autotest/default_params/copter.parm) —
  source of the FRAME_CLASS/FRAME_TYPE/INS_ACC*/COMPASS_OFS* defaults baked into
  `entrypoint.sh`.
- `docs/plans/active/DRONE-INFRA-PLAN.md` I-c (this phase's spec) and I-a (the multi-vehicle gateway this
  fleet is designed to hand off to).
- `drone-link/mavlink/MODULE.md` — the platform-side ingest model this fleet targets.
