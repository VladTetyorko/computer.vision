#!/usr/bin/env bash
# infra/sitl/entrypoint.sh
#
# Launches one ArduCopter SITL instance with a distinct MAVLink sysid,
# pushes MAVLink 2 over UDP to the vision platform (SITL is the client --
# the platform LISTENS, see adapter-mavlink/MODULE.md "udp://host:port means
# listen, not connect"), then flies a small default circuit: GUIDED takeoff
# followed by CIRCLE mode around the takeoff point. All env vars have
# defaults so `docker run` with none of them still produces a flying vehicle.
set -euo pipefail

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

echo "[entrypoint] sysid=${SYSID_THISMAV} instance=${INSTANCE} target=udp ${MAVLINK_TARGET_HOST}:${MAVLINK_TARGET_PORT} home=${SITL_HOME} speedup=${SITL_SPEEDUP}"

DEFAULTS_FILE=/tmp/defaults.parm
# FRAME_CLASS/FRAME_TYPE/INS_ACC*/COMPASS_OFS*: sim_vehicle.py normally loads
# these from ArduPilot's own Tools/autotest/default_params/copter.parm --
# running the raw binary (this image, deliberately no ArduPilot source
# checkout) sets none of them on its own, and arming stalls on hard safety
# checks that ARMING_CHECK=0 does NOT waive ("PreArm: Motors: Check frame
# class and type", "PreArm: 3D Accel calibration needed" -- both reproduced
# empirically against this image before these lines were added). Values
# below are copied verbatim from that same official file (fetched
# 2026-07-28: raw.githubusercontent.com/ArduPilot/ardupilot/master/Tools/
# autotest/default_params/copter.parm) -- the tiny non-zero INS_ACC offsets
# are that file's own trick to mark the simulated IMU "as calibrated"
# without a real calibration flight.
cat > "$DEFAULTS_FILE" <<EOF
SYSID_THISMAV ${SYSID_THISMAV}
ARMING_CHECK 0
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
INS_ACC3OFFS_X 0.000
INS_ACC3OFFS_Y 0.000
INS_ACC3OFFS_Z 0.000
INS_ACC3SCAL_X 1.000
INS_ACC3SCAL_Y 1.000
INS_ACC3SCAL_Z 1.000
EOF

# --serial0 udpclient:HOST:PORT: SITL pushes (client role) to the platform's
# listening socket -- the format is ArduPilot's own (libraries/AP_HAL_SITL
# device-string parser: tcp:/tcpclient:/udp:/udpclient:/mcast:), confirmed
# against the arducopter binary's own --help and cross-checked with
# ArduPilot's "Using SITL" dev docs example `--serial0=udpclient:<ip>:14550`.
#
# serial1 is left at its SITL default (local "tcp:5762", offset +10*instance
# by -I) -- that's the local control link autofly.py below arms/flies the
# vehicle over; never exposed outside this container.
./arducopter \
  -S \
  -w \
  -I "${INSTANCE}" \
  --home "${SITL_HOME}" \
  --model quad \
  --speedup "${SITL_SPEEDUP}" \
  --sysid "${SYSID_THISMAV}" \
  --serial0 "udpclient:${MAVLINK_TARGET_HOST}:${MAVLINK_TARGET_PORT}" \
  --defaults "${DEFAULTS_FILE}" \
  &
SITL_PID=$!

# Forward termination to the SITL process so `docker stop`/down.sh exit promptly.
trap 'echo "[entrypoint] stopping sitl pid ${SITL_PID}"; kill "${SITL_PID}" 2>/dev/null || true' TERM INT

# Fire-and-forget: fly the default circuit once the vehicle is ready.
# Failures here (e.g. EKF never settles) must not take the SITL process down
# -- the container should keep pushing telemetry either way.
python3 /sitl/autofly.py \
  --instance "${INSTANCE}" \
  --takeoff-alt "${TAKEOFF_ALT_M}" \
  || echo "[entrypoint] autofly.py exited non-zero -- vehicle stays on the ground, telemetry still flows" &

wait "${SITL_PID}"
