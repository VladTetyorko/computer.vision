#!/usr/bin/env bash
# infra/sitl/up.sh N
#
# Launches N real ArduPilot SITL instances (docker), each a distinct
# MAVLink sysid, each pushing MAVLink 2 UDP to the host and flying a
# default circuit -- docs/DRONE-INFRA-PLAN.md I-c.
#
# Usage:
#   ./up.sh          # 1 instance (CI-style smoke)
#   ./up.sh 5        # 5 instances, sysid 1..5
#
# Env overrides (all optional, see README.md):
#   MAVLINK_TARGET_HOST  default host.docker.internal (the docker host)
#   MAVLINK_TARGET_PORT  default 14550
#   SITL_HOME            default CMAC "-35.363261,149.165230,584,353"
#   SITL_SPEEDUP         default 1 (real-time)
#   TAKEOFF_ALT_M         default 20
set -euo pipefail

N="${1:-1}"
if ! [[ "$N" =~ ^[0-9]+$ ]] || [ "$N" -lt 1 ]; then
    echo "usage: $0 [N]  (N must be a positive integer, got '${N}')" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="vision-sitl:4.7.0"

# One image build serves every instance -- N separate `docker build`s would
# just rebuild the identical layers N times.
docker build -t "$IMAGE" "$SCRIPT_DIR"

echo "Starting ${N} ArduPilot SITL instance(s) -> udp ${MAVLINK_TARGET_HOST:-host.docker.internal}:${MAVLINK_TARGET_PORT:-14550}"

for i in $(seq 1 "$N"); do
    instance=$((i - 1))
    name="vision-sitl-${i}"
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker run -d \
        --name "$name" \
        --label vision.sitl.fleet=true \
        --add-host=host.docker.internal:host-gateway \
        -e SYSID_THISMAV="$i" \
        -e INSTANCE="$instance" \
        -e MAVLINK_TARGET_HOST="${MAVLINK_TARGET_HOST:-host.docker.internal}" \
        -e MAVLINK_TARGET_PORT="${MAVLINK_TARGET_PORT:-14550}" \
        -e SITL_HOME="${SITL_HOME:--35.363261,149.165230,584,353}" \
        -e SITL_SPEEDUP="${SITL_SPEEDUP:-1}" \
        -e TAKEOFF_ALT_M="${TAKEOFF_ALT_M:-20}" \
        --restart unless-stopped \
        "$IMAGE" >/dev/null
    echo "  started ${name} (sysid=${i})"
done

echo "Done. Tail one instance's log: docker logs -f vision-sitl-1"
echo "Tear down: ./down.sh"
