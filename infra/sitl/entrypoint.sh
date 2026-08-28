#!/usr/bin/env bash
# infra/sitl/entrypoint.sh
#
# Launches one ArduPilot SITL instance with a distinct MAVLink sysid, pushes
# MAVLink 2 over UDP to the vision platform (SITL is the client -- the platform
# LISTENS, see adapter-mavlink/MODULE.md "udp://host:port means listen, not
# connect"), then runs a default routine appropriate to the vehicle. All env
# vars have defaults so `docker run` with none of them still produces a working
# vehicle, and VEHICLE defaults to copter so nothing that existed before
# docs/plans/active/FLEET-RADIO-PLAN.md R7 changes behaviour.
#
# VEHICLE=copter (default): GUIDED takeoff, then CIRCLE around the takeoff
#   point -- the flying circuit DRONE-INFRA-PLAN I-c asked for.
# VEHICLE=rover: arms in MANUAL and holds. Deliberately NOT an autonomous
#   circuit: a rover's reason for existing here is to be *driven* (R3's RC
#   override, R1's rover mode table), and a vehicle already executing its own
#   guided mission would fight the very stick inputs the tests send it. The
#   platform sees an armed, telemetry-streaming, drivable vehicle.
set -euo pipefail

VEHICLE="${VEHICLE:-copter}"
SYSID_THISMAV="${SYSID_THISMAV:-1}"
INSTANCE="${INSTANCE:-0}"
MAVLINK_TARGET_HOST="${MAVLINK_TARGET_HOST:-host.docker.internal}"
MAVLINK_TARGET_PORT="${MAVLINK_TARGET_PORT:-14550}"
# CMAC (Canberra Model Aircraft Club) -- ArduPilot's own long-standing SITL
# demo default home location; harmless, well east-of-nowhere coordinates,
# changed here only to give operators a recognizable, documented default.
SITL_HOME="${SITL_HOME:--35.363261,149.165230,584,353}"
SITL_SPEEDUP="${SITL_SPEEDUP:-1}"
TAKEOFF_ALT_M="${TAKEOFF_ALT_M:-20}"

case "${VEHICLE}" in
  copter)
    SITL_BINARY=./arducopter
    SITL_MODEL=quad
    ;;
  rover)
    SITL_BINARY=./ardurover
    SITL_MODEL=rover
    ;;
  *)
    echo "[entrypoint] VEHICLE must be 'copter' or 'rover', got '${VEHICLE}'" >&2
    exit 2
    ;;
esac

echo "[entrypoint] vehicle=${VEHICLE} sysid=${SYSID_THISMAV} instance=${INSTANCE} target=udp ${MAVLINK_TARGET_HOST}:${MAVLINK_TARGET_PORT} home=${SITL_HOME} speedup=${SITL_SPEEDUP}"

DEFAULTS_FILE=/tmp/defaults.parm

# sim_vehicle.py normally loads a vehicle's arming-critical defaults from
# ArduPilot's own Tools/autotest/default_params/<vehicle>.parm -- running the
# raw binary (this image, deliberately no ArduPilot source checkout) sets none
# of them, and arming stalls on hard safety checks that ARMING_CHECK=0 does NOT
# waive ("PreArm: Motors: Check frame class and type", "PreArm: 3D Accel
# calibration needed" -- both reproduced empirically against this image before
# these lines were added). Every value below is copied verbatim from that same
# official file, per vehicle; the tiny non-zero INS_ACC offsets are that file's
# own trick to mark the simulated IMU "as calibrated" without a real
# calibration flight.
#
# MAV_SYSID, not SYSID_THISMAV: ArduPilot 4.7 renamed it, and this image IS
# 4.7.0 -- MavlinkSitlOnboardingIntegrationTest reads MAV_SYSID off this very
# binary and gets no answer at all for the old spelling
# (docs/plans/active/FLEET-RADIO-PLAN.md F0). The line was silently dead here
# before; --sysid on the command line is what actually set the id, and still is.
cat > "$DEFAULTS_FILE" <<EOF
MAV_SYSID ${SYSID_THISMAV}
ARMING_CHECK 0
INS_ACCOFFS_X 0.001
INS_ACCOFFS_Y 0.001
INS_ACCOFFS_Z 0.001
INS_ACCSCAL_X 1.001
INS_ACCSCAL_Y 1.001
INS_ACCSCAL_Z 1.001
INS_ACC2OFFS_X 0.001
INS_ACC2OFFS_Y 0.001
INS_ACC2OFFS_Z 0.001
INS_ACC2SCAL_X 1.001
INS_ACC2SCAL_Y 1.001
INS_ACC2SCAL_Z 1.001
EOF

# Copter block: fetched 2026-07-28 from raw.githubusercontent.com/ArduPilot/
# ardupilot/master/Tools/autotest/default_params/copter.parm
if [ "${VEHICLE}" = "copter" ]; then
cat >> "$DEFAULTS_FILE" <<EOF
FRAME_CLASS 1
FRAME_TYPE 0
COMPASS_OFS_X 5
COMPASS_OFS_Y 13
COMPASS_OFS_Z -18
COMPASS_OFS2_X 5
COMPASS_OFS2_Y 13
COMPASS_OFS2_Z -18
COMPASS_OFS3_X 5
COMPASS_OFS3_Y 13
COMPASS_OFS3_Z -18
INS_ACC3OFFS_X 0.000
INS_ACC3OFFS_Y 0.000
INS_ACC3OFFS_Z 0.000
INS_ACC3SCAL_X 1.000
INS_ACC3SCAL_Y 1.000
INS_ACC3SCAL_Z 1.000
EOF
fi

# Rover block: fetched 2026-08-27 from the sibling rover.parm in that same
# directory. No FRAME_CLASS/FRAME_TYPE -- ArduRover has no frame class, which is
# exactly the kind of copter-only assumption this wave exists to stop making.
# MODE3/MODE4/MODE5 are the flight-mode-switch positions the official file sets
# (11=RTL, 10=AUTO, 2=LEARNING); RC1/RC3 and SERVO1/SERVO3 ranges are steering
# and throttle, and they are what an RC override has to land inside to move the
# vehicle at all (docs/plans/active/FLEET-RADIO-PLAN.md R3).
if [ "${VEHICLE}" = "rover" ]; then
cat >> "$DEFAULTS_FILE" <<EOF
ATC_SPEED_P 0.1
ATC_STR_RAT_FF 0.75
BATT_MONITOR 4
CRUISE_SPEED 5
CRUISE_THROTTLE 30
MODE3 11
MODE4 10
MODE5 2
RC1_MAX 2000
RC1_MIN 1000
RC3_MAX 2000
RC3_MIN 1000
RELAY1_PIN 1
RELAY2_PIN 2
SERVO1_MIN 1000
SERVO1_MAX 2000
SERVO3_MAX 2000
SERVO3_MIN 1000
SIM_PIN_MASK 127
WP_RADIUS 3
WP_SPEED 5
INS_LOG_BAT_MASK 127
EOF
fi

# --serial0 udpclient:HOST:PORT: SITL pushes (client role) to the platform's
# listening socket -- the format is ArduPilot's own (libraries/AP_HAL_SITL
# device-string parser: tcp:/tcpclient:/udp:/udpclient:/mcast:), confirmed
# against the arducopter binary's own --help and cross-checked with
# ArduPilot's "Using SITL" dev docs example `--serial0=udpclient:<ip>:14550`.
#
# serial1 is left at its SITL default (local "tcp:5762", offset +10*instance
# by -I) -- that's the local control link autofly.py below arms/flies the
# vehicle over; never exposed outside this container.
"${SITL_BINARY}" \
  -S \
  -w \
  -I "${INSTANCE}" \
  --home "${SITL_HOME}" \
  --model "${SITL_MODEL}" \
  --speedup "${SITL_SPEEDUP}" \
  --sysid "${SYSID_THISMAV}" \
  --serial0 "udpclient:${MAVLINK_TARGET_HOST}:${MAVLINK_TARGET_PORT}" \
  --defaults "${DEFAULTS_FILE}" \
  &
SITL_PID=$!

# Forward termination to the SITL process so `docker stop`/down.sh exit promptly.
trap 'echo "[entrypoint] stopping sitl pid ${SITL_PID}"; kill "${SITL_PID}" 2>/dev/null || true' TERM INT

# Fire-and-forget: run the vehicle's default routine once it is ready.
# Failures here (e.g. EKF never settles) must not take the SITL process down
# -- the container should keep pushing telemetry either way.
python3 /sitl/autofly.py \
  --vehicle "${VEHICLE}" \
  --instance "${INSTANCE}" \
  --takeoff-alt "${TAKEOFF_ALT_M}" \
  || echo "[entrypoint] autofly.py exited non-zero -- vehicle stays unarmed, telemetry still flows" &

wait "${SITL_PID}"
