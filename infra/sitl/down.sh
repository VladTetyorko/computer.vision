#!/usr/bin/env bash
# infra/sitl/down.sh
#
# Tears down every SITL instance started by up.sh, however many there are --
# found by the vision.sitl.fleet=true label rather than a remembered count.
set -euo pipefail

ids="$(docker ps -aq --filter "label=vision.sitl.fleet=true")"
if [ -z "$ids" ]; then
    echo "no vision-sitl containers found"
    exit 0
fi

# shellcheck disable=SC2086
docker rm -f $ids >/dev/null
echo "stopped and removed $(echo "$ids" | wc -l) container(s)"
