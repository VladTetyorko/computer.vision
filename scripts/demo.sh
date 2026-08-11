#!/usr/bin/env bash
#
# scripts/demo.sh — MVP1 "friends demo" assembly (docs/plans/done/MVP1-PLAN.md §C9).
#
# Starts >=3 simultaneous source protocols on the Wall (direct/rtsp/mjpeg
# file playback plus one fully synthetic sim-protocol source), one of them
# flying a scripted telemetry route so the Map tab has something to show
# trails for, and prints every URL to open. Idempotent: re-running it while
# the demo sources are already registered skips creating duplicates.
#
# Usage:
#   scripts/demo.sh <clip1> [clip2] [clip3]
#   scripts/demo.sh --stop
#
#   clip1   played back directly (transport=direct) and flies a 3-waypoint
#           telemetry loop
#   clip2   pushed over RTSP to mediamtx and ingested back (transport=rtsp);
#           defaults to clip1 if omitted
#   clip3   pushed over MJPEG (transport=mjpeg); defaults to clip1 if omitted
#
# Clip paths must be absolute and readable by the vision-app *process*:
#   - host-run jar: any absolute path on this machine, e.g. ~/Videos/clip.mp4
#   - docker compose: a path inside the vision-app container. The compose
#     file bind-mounts ${DEMO_CLIPS_DIR:-./clips} to /clips (read-only) for
#     exactly this — drop your clips in ./clips/ and pass /clips/your-clip.mp4
#
# Env:
#   BASE_URL  vision-app origin (default http://localhost:8080)
#
# Requires: curl, jq.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

# Deterministic names so re-runs (and --stop) can find what this script made
# instead of accumulating duplicates (docs/plans/done/MVP1-PLAN.md §C9's idempotency ask).
NAME_DIRECT="Demo drone 1 (direct)"
NAME_RTSP="Demo drone 2 (rtsp)"
NAME_MJPEG="Demo drone 3 (mjpeg)"
NAME_SYNTHETIC="Demo drone 4 (synthetic)"

# A nice 3-waypoint loop for the direct-transport asset's flight plan
# (docs/main/CYCLES-PLAN.md §7 CT-a's TelemetryPlan wire shape) — a triangular
# patrol around Golden Gate Park, purely cosmetic coordinates for the Map tab.
TELEMETRY_ROUTE=$(jq -n '{
  speedMps: 12,
  routeMode: "loop",
  route: [
    {latitude: 37.7694, longitude: -122.4862, altitudeMeters: 60},
    {latitude: 37.7718, longitude: -122.4746, altitudeMeters: 80},
    {latitude: 37.7658, longitude: -122.4700, altitudeMeters: 60}
  ]
}')

# All progress output goes to stderr — several functions below (ensure_simulation,
# ensure_synthetic_device) are called via command substitution to capture just their
# final id, and stdout is what that substitution captures.
log()  { printf '[demo] %s\n' "$*" >&2; }
warn() { printf '[demo] WARN: %s\n' "$*" >&2; }
err()  { printf '[demo] ERROR: %s\n' "$*" >&2; }

usage() {
  cat <<EOF
Usage: $0 <clip1> [clip2] [clip3]
       $0 --stop

  clip1  direct playback + flies a 3-waypoint telemetry loop
  clip2  pushed over RTSP (defaults to clip1)
  clip3  pushed over MJPEG (defaults to clip1)

Env: BASE_URL (default http://localhost:8080)
EOF
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { err "missing required command: $1"; exit 1; }
}

# Waits for vision-app to answer, per the brief's health-check endpoint.
wait_for_api() {
  log "waiting for ${BASE_URL}/api/devices ..."
  local attempts=60
  local i=0
  until curl -sf -m 2 "${BASE_URL}/api/devices" >/dev/null 2>&1; do
    i=$((i + 1))
    if (( i >= attempts )); then
      err "timed out waiting for ${BASE_URL} — is vision-app running? (docker compose up -d / mvnw spring-boot:run)"
      exit 1
    fi
    sleep 2
  done
  log "vision-app is up."
}

# ---- read helpers ----------------------------------------------------------

list_assets() { curl -sf -m 5 "${BASE_URL}/api/assets"; }
list_devices() { curl -sf -m 5 "${BASE_URL}/api/devices"; }
list_streams() { curl -sf -m 5 "${BASE_URL}/api/streams"; }

find_asset_id_by_name() {
  list_assets | jq -r --arg n "$1" '[.[] | select(.displayName == $n)][0].assetId // empty'
}

find_device_id_by_name() {
  list_devices | jq -r --arg n "$1" '[.[] | select(.name == $n)][0].id // empty'
}

video_device_of_asset() {
  curl -sf -m 5 "${BASE_URL}/api/assets/$1" \
    | jq -r '[.devices[] | select(.capabilities | index("VIDEO"))][0].id // empty'
}

active_stream_of_device() {
  list_streams | jq -r --arg d "$1" '[.[] | select(.deviceId == $d)][0].streamId // empty'
}

# ---- write helpers ----------------------------------------------------------

# $1 displayName  $2 videoPath  $3 transport  $4 telemetry JSON fragment or ''
create_simulation() {
  local name="$1" video="$2" transport="$3" telemetry="${4:-}"
  local body
  body=$(jq -n --arg n "$name" --arg v "$video" --arg t "$transport" \
    '{displayName: $n, videoPath: $v, transport: $t, autoStart: true}')
  if [[ -n "$telemetry" ]]; then
    body=$(jq --argjson tel "$telemetry" '. + {telemetry: $tel}' <<<"$body")
  fi
  curl -sf -m 20 -X POST "${BASE_URL}/api/simulations" \
    -H 'Content-Type: application/json' -d "$body"
}

register_synthetic_device() {
  local name="$1"
  local body
  body=$(jq -n --arg n "$name" '{name: $n, protocol: "sim", uri: "sim://demo"}')
  curl -sf -m 10 -X POST "${BASE_URL}/api/devices" \
    -H 'Content-Type: application/json' -d "$body"
}

start_device_stream() {
  curl -sf -m 10 -X POST "${BASE_URL}/api/devices/$1/stream" >/dev/null
}

# Ensures one simulated (file-based) demo asset exists, creating it if
# missing. Deliberately does NOT try to resume an existing-but-stopped
# rtsp/mjpeg asset itself (its transmitted feed is separate in-process state
# owned by SimulationService and isn't recreated by a plain restart) — a
# fresh `--stop` + re-run always gets a clean asset either way, and this
# keeps the common "already running" re-run case (the one this idempotency
# guard actually targets) a simple, honest skip.
ensure_simulation() {
  local name="$1" video="$2" transport="$3" telemetry="${4:-}"
  local asset_id
  asset_id=$(find_asset_id_by_name "$name")
  if [[ -n "$asset_id" ]]; then
    log "  '${name}' already exists (assetId=${asset_id}) — skipping"
  else
    log "  creating '${name}' (transport=${transport}, clip=${video})"
    local resp
    resp=$(create_simulation "$name" "$video" "$transport" "$telemetry")
    asset_id=$(jq -r '.assetId' <<<"$resp")
    log "    assetId=${asset_id}"
  fi
  printf '%s' "$asset_id"
}

ensure_synthetic_device() {
  local name="$1"
  local device_id
  device_id=$(find_device_id_by_name "$name")
  if [[ -n "$device_id" ]]; then
    log "  '${name}' already exists (deviceId=${device_id}) — skipping registration"
  else
    log "  registering '${name}' (protocol=sim, uri=sim://demo)"
    local resp
    resp=$(register_synthetic_device "$name")
    device_id=$(jq -r '.id' <<<"$resp")
    log "    deviceId=${device_id}"
  fi
  local stream_id
  stream_id=$(active_stream_of_device "$device_id")
  if [[ -n "$stream_id" ]]; then
    log "    already streaming (streamId=${stream_id})"
  else
    log "    starting stream"
    start_device_stream "$device_id"
  fi
  printf '%s' "$device_id"
}

print_live_url() {
  local name="$1" asset_id="$2"
  local device_id
  device_id=$(video_device_of_asset "$asset_id")
  if [[ -n "$device_id" ]]; then
    printf '  %-28s %s/live/%s\n' "$name" "$BASE_URL" "$device_id"
  else
    warn "  ${name}: no video device found on asset ${asset_id}"
  fi
}

# ---- --stop -----------------------------------------------------------------

do_stop() {
  wait_for_api
  log "tearing down every \"Demo drone *\" source ..."

  local asset_ids
  asset_ids=$(list_assets | jq -r '.[] | select(.displayName | startswith("Demo drone")) | .assetId')
  if [[ -z "$asset_ids" ]]; then
    log "  no simulated demo assets found"
  else
    while IFS= read -r id; do
      [[ -z "$id" ]] && continue
      log "  DELETE /api/simulations/${id}"
      curl -sf -m 15 -X DELETE "${BASE_URL}/api/simulations/${id}" || warn "    failed for ${id}"
    done <<<"$asset_ids"
  fi

  # The synthetic source is a bare Device (no owning Asset — see the header
  # comment), so it's outside "DELETE /api/simulations/{assetId}"'s reach;
  # stop its stream the same way the Devices page would (DELETE
  # /api/streams/{streamId}), leaving the device registered (mirrors the
  # simulated assets above, which also survive as stopped/offline rather than
  # being deleted) so a later re-run finds it again instead of piling up
  # soft-deleted duplicates. Matched by exact name (not a "Demo drone" prefix
  # like the assets above) — a prefix match would also catch the simulated
  # assets' own generated device names ("Demo drone 1 (direct) · video" etc.),
  # which are already handled by the DELETE /api/simulations/* loop above.
  local device_id
  device_id=$(find_device_id_by_name "$NAME_SYNTHETIC")
  if [[ -z "$device_id" ]]; then
    log "  no synthetic demo device found"
  else
    local stream_id
    stream_id=$(active_stream_of_device "$device_id")
    if [[ -n "$stream_id" ]]; then
      log "  DELETE /api/streams/${stream_id} (device ${device_id})"
      curl -sf -m 15 -X DELETE "${BASE_URL}/api/streams/${stream_id}" || warn "    failed for ${stream_id}"
    else
      log "  device ${device_id} already stopped"
    fi
  fi

  log "teardown complete."
}

# ---- main -------------------------------------------------------------------

main() {
  need_cmd curl
  need_cmd jq

  if [[ "${1:-}" == "--stop" ]]; then
    do_stop
    return
  fi
  if [[ $# -lt 1 ]]; then
    usage
    exit 1
  fi
  if [[ "$1" == "-h" || "$1" == "--help" ]]; then
    usage
    exit 0
  fi

  local clip1="$1"
  local clip2="${2:-$1}"
  local clip3="${3:-$1}"

  wait_for_api

  log "assembling the demo ..."
  local asset_direct asset_rtsp asset_mjpeg device_synthetic
  asset_direct=$(ensure_simulation "$NAME_DIRECT" "$clip1" direct "$TELEMETRY_ROUTE")
  asset_rtsp=$(ensure_simulation "$NAME_RTSP" "$clip2" rtsp)
  asset_mjpeg=$(ensure_simulation "$NAME_MJPEG" "$clip3" mjpeg)
  device_synthetic=$(ensure_synthetic_device "$NAME_SYNTHETIC")

  echo
  log "Wall: ${BASE_URL}/wall"
  log "Map:  ${BASE_URL}/map"
  echo
  log "Live URLs:"
  print_live_url "$NAME_DIRECT" "$asset_direct"
  print_live_url "$NAME_RTSP" "$asset_rtsp"
  print_live_url "$NAME_MJPEG" "$asset_mjpeg"
  printf '  %-28s %s/live/%s\n' "$NAME_SYNTHETIC" "$BASE_URL" "$device_synthetic"
  echo
  log "Detection boxes need cv-service up (vision.cv.enabled=true, endpoint reachable)."
  log "Kill it mid-demo to see graceful degradation (video/telemetry keep going, boxes"
  log "pause); restarting it resumes detections within a few seconds."
  echo
  log "Run '$0 --stop' to tear everything this script started back down."
}

main "$@"
