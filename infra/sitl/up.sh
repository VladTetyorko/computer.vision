#!/usr/bin/env bash
# infra/sitl/up.sh [N] [VEHICLE]
#
# Launches N real ArduPilot SITL instances (docker), each a distinct
# MAVLink sysid, each pushing MAVLink 2 UDP to the host --
# docs/plans/active/DRONE-INFRA-PLAN.md I-c.
#
# Usage:
#   ./up.sh              # 1 copter (CI-style smoke)
#   ./up.sh 5            # 5 copters, sysid 1..5
#   ./up.sh 2 rover      # 2 rovers, sysid 1..2
#
# A MIXED fleet is two calls, and SYSID_BASE is what keeps them from colliding
# (docs/plans/active/FLEET-RADIO-PLAN.md R7 -- one port carrying two kinds of
# vehicle is the plan's own exit gate):
#   ./up.sh 2                        # copters, sysid 1..2
#   SYSID_BASE=10 ./up.sh 1 rover    # rover,   sysid 10
# down.sh tears down both -- it finds containers by label, not by count.
#
# Env overrides (all optional, see README.md):
#   MAVLINK_TARGET_HOST  default host.docker.internal (the docker host)
#   MAVLINK_TARGET_PORT  default 14550
#   SITL_HOME            default CMAC "-35.363261,149.165230,584,353"
#   SITL_SPEEDUP         default 1 (real-time)
#   TAKEOFF_ALT_M        default 20 (copter only)
#   SYSID_BASE           default 1 -- first sysid, and the instance offset
set -euo pipefail

N="${1:-1}"
VEHICLE="${2:-copter}"
SYSID_BASE="${SYSID_BASE:-1}"
if ! [[ "$N" =~ ^[0-9]+$ ]] || [ "$N" -lt 1 ]; then
    echo "usage: $0 [N] [copter|rover]  (N must be a positive integer, got '${N}')" >&2
    exit 1
fi
if [ "$VEHICLE" != "copter" ] && [ "$VEHICLE" != "rover" ]; then
    echo "usage: $0 [N] [copter|rover]  (got vehicle '${VEHICLE}')" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="vision-sitl:4.7.0"

# One image build serves every instance -- N separate `docker build`s would
# just rebuild the identical layers N times.
docker build -t "$IMAGE" "$SCRIPT_DIR"

echo "Starting ${N} ArduPilot SITL ${VEHICLE}(s) -> udp ${MAVLINK_TARGET_HOST:-host.docker.internal}:${MAVLINK_TARGET_PORT:-14550}"

for i in $(seq 1 "$N"); do
    sysid=$((SYSID_BASE + i - 1))
    # Instance number tracks the sysid, not the loop counter: SITL offsets every
    # local port by 10*instance, so two fleets started separately would collide
    # on those ports if both counted from 0.
    instance=$((sysid - 1))
    name="vision-sitl-${VEHICLE}-${sysid}"
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker run -d \
        --name "$name" \
        --label vision.sitl.fleet=true \
        --add-host=host.docker.internal:host-gateway \
        -e VEHICLE="$VEHICLE" \
        -e SYSID_THISMAV="$sysid" \
        -e INSTANCE="$instance" \
        -e MAVLINK_TARGET_HOST="${MAVLINK_TARGET_HOST:-host.docker.internal}" \
        -e MAVLINK_TARGET_PORT="${MAVLINK_TARGET_PORT:-14550}" \
        -e SITL_HOME="${SITL_HOME:--35.363261,149.165230,584,353}" \
        -e SITL_SPEEDUP="${SITL_SPEEDUP:-1}" \
        -e TAKEOFF_ALT_M="${TAKEOFF_ALT_M:-20}" \
        --restart unless-stopped \
        "$IMAGE" >/dev/null
    echo "  started ${name} (sysid=${sysid})"
done

echo "Done. Tail one instance's log: docker logs -f vision-sitl-${VEHICLE}-${SYSID_BASE}"
echo "Tear down: ./down.sh"
