#!/usr/bin/env bash
# One-command local stack.
#
# Default ("light") mode runs the two things that genuinely want to be containers —
# postgres and mediamtx — in docker, and runs cv-service and vision-app as host
# processes. That is not a shortcut: building the cv-service image pulls torch +
# ultralytics + openvino, several GB, and this machine's disk has repeatedly been the
# binding constraint. Light mode needs no image build at all.
#
#   scripts/local-up.sh            light mode (default)
#   scripts/local-up.sh --full     everything in docker (needs ~6 GB free)
#   scripts/local-up.sh --stop     tear down both modes
#   scripts/local-up.sh --logs     tail the host-process logs
#   scripts/local-up.sh --reset-db drop the postgres volume, then bring up light mode
#
# On "Migrations have failed validation": commit 8cea337 rewrote doc paths inside the
# COMMENTS of migrations V3-V9, and Flyway checksums whole files. Any database migrated
# before 2026-08-11 therefore fails validation on a purely cosmetic change. --reset-db
# is the blunt local answer; a database with data worth keeping wants `flyway repair`
# (or an UPDATE of flyway_schema_history.checksum) instead.
#
# Ports (host): 8080 vision-app · 8554 RTSP · 18888 HLS · 18889 WHEP · 19996 playback
#               19997 mediamtx control (loopback) · 50051 cv-service · 5433 postgres
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
LOGS="$ROOT/.local-run"
MODE="${1:-light}"

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
# Externally timed: bash's own /dev/tcp redirection has no connect timeout and will
# hang the whole script with no child process to point at.
port_open() { timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null; }
await() { # await <port> <seconds> <what>
  for _ in $(seq 1 "$2"); do port_open "$1" && return 0; sleep 1; done
  die "$3 never opened :$1 after $2s — see $LOGS/"
}
die() { printf '\033[1;31mfail:\033[0m %s\n' "$*" >&2; exit 1; }

stop_host_procs() {
  for f in "$LOGS/cv-service.pid" "$LOGS/vision-app.pid"; do
    [ -f "$f" ] || continue
    pid=$(cat "$f")
    if kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; fi
    rm -f "$f"
  done
  # Belt and braces: anything still holding our two host ports is a previous run.
  for port in 50051 8080; do
    # Every pid, not just the first: SO_REUSEPORT lets two servers share :50051, so
    # killing one leaves a listener behind and the port still looks "up".
    pids=$(ss -ltnpH "sport = :$port" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | sort -u || true)
    for pid in $pids; do
      say "killing leftover process $pid on :$port"
      kill "$pid" 2>/dev/null || true
    done
  done
  sleep 1
}

if [ "$MODE" = "--stop" ]; then
  say "stopping host processes"; stop_host_procs
  say "stopping containers";     docker compose down --remove-orphans
  say "down."; exit 0
fi

if [ "$MODE" = "--reset-db" ]; then
  say "dropping the postgres volume"
  stop_host_procs
  docker compose down -v --remove-orphans
  MODE=light
fi

if [ "$MODE" = "--logs" ]; then
  tail -f "$LOGS"/cv-service.log "$LOGS"/vision-app.log; exit 0
fi

[ -f .env ] || { say "creating .env from .env.example"; cp .env.example .env; }
set -a; . ./.env; set +a
: "${POSTGRES_PORT:=5433}"

# A previous run of either mode must be gone before this one binds anything.
say "clearing any previous run"
stop_host_procs
docker compose down --remove-orphans >/dev/null 2>&1 || true
mkdir -p "$LOGS"

if [ "$MODE" = "--full" ]; then
  avail=$(df --output=avail -BG "$ROOT" | tail -1 | tr -dc '0-9')
  [ "$avail" -ge 8 ] || die "--full needs ~8 GB free for the cv-service image layers; only ${avail} GB available. Use light mode, or free space first."
  say "building vision-app jar"
  ./mvnw -B -q package -DskipTests
  say "docker compose up --build (first run pulls torch/ultralytics — several minutes)"
  docker compose up -d --build
  say "stack up: http://localhost:8080"
  exit 0
fi

# ---------- light mode ----------
say "starting postgres + mediamtx"
docker compose up -d postgres mediamtx

say "waiting for postgres"
for i in $(seq 1 60); do
  docker compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1 && break
  [ "$i" = 60 ] && die "postgres did not become ready"
  sleep 1
done

say "starting cv-service (host venv)"
[ -x cv-service/.venv/bin/python ] || die "cv-service/.venv is missing — run: cd cv-service && python3 -m venv .venv && .venv/bin/pip install -e '.[cv]'"
( cd cv-service && nohup .venv/bin/python -m cv_service.grpc.server >"$LOGS/cv-service.log" 2>&1 & echo $! >"$LOGS/cv-service.pid" )

say "waiting for cv-service on :50051 (loads + warms the model first, ~30-60s)"
await 50051 120 "cv-service"

# The jar carries the built Angular SPA, so it is stale the moment any tracked source
# is newer than it. find -newer asks that directly; sorting `ls -t` over `git ls-files`
# does not (xargs batches, so it sorts per batch and lies for a repo this size).
JAR=$(ls -t vision-app/target/vision-app-*.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then
  say "no jar yet — building (a few minutes, includes the Angular SPA)"
  ./mvnw -B package -DskipTests || die "maven build failed"
  JAR=$(ls -t vision-app/target/vision-app-*.jar | head -1)
else
  STALE=$(find . -newer "$JAR" -type f \
            \( -name '*.java' -o -name '*.ts' -o -name '*.html' -o -name '*.css' \
               -o -name '*.yaml' -o -name '*.properties' -o -name 'pom.xml' \) \
            -not -path './.git/*' -not -path '*/target/*' -not -path '*/node_modules/*' \
            -not -path './cv-service/*' -print -quit 2>/dev/null || true)
  if [ -n "$STALE" ]; then
    say "jar is older than $STALE — rebuilding (a few minutes, includes the Angular SPA)"
    ./mvnw -B package -DskipTests || die "maven build failed"
    JAR=$(ls -t vision-app/target/vision-app-*.jar | head -1)
  else
    say "reusing $JAR (no source newer than it)"
  fi
fi

say "starting vision-app"
# mediamtx/publish bases are left to application.yaml's own host-mode defaults
# (localhost:8554/18888/18889) — those defaults exist precisely for this shape, so
# restating them here would only create a second place to keep in sync.
VISION_CV_ENABLED=true \
VISION_CV_ENDPOINT=localhost:50051 \
VISION_PERSISTENCE_ENABLED=true \
VISION_PERSISTENCE_JDBC_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/${POSTGRES_DB}" \
VISION_PERSISTENCE_USERNAME="$POSTGRES_USER" \
VISION_PERSISTENCE_PASSWORD="$POSTGRES_PASSWORD" \
VISION_AUTH_ENABLED=true \
nohup java -jar "$JAR" >"$LOGS/vision-app.log" 2>&1 &
echo $! >"$LOGS/vision-app.pid"

say "waiting for vision-app on :8080"
for i in $(seq 1 150); do
  if port_open 8080; then break; fi
  kill -0 "$(cat "$LOGS/vision-app.pid")" 2>/dev/null || die "vision-app exited — see $LOGS/vision-app.log"
  if [ "$i" = 150 ]; then die "vision-app never answered on :8080 — see $LOGS/vision-app.log"; fi
  sleep 1
done

cat <<EOF

  ready → http://localhost:8080     (dev seed logins: admin/admin, manager/manager, pilot/pilot)

  logs    scripts/local-up.sh --logs
  stop    scripts/local-up.sh --stop
  demo    scripts/demo.sh <clip.mp4>
EOF
