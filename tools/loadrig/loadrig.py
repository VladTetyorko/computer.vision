#!/usr/bin/env python3
"""SCALE-100 wave S0 load rig.

Drives a running vision-app instance at stated concurrency levels and measures
what docs/plans/active/SCALE-100-PLAN.md sec 5 (S0) asks for: p50/p99 REST
latency, SSE envelope lag, JVM heap + GC pause, thread count, DB connection
count, and process CPU -- at 5 / 20 / 50 / 100 concurrent "users".

A "user" here means one open cockpit tab: one GET /api/live SSE connection
plus one HLS viewer pulling playlists+segments through /hls/{streamId}/**,
run together (--mode combined, the default) since that is what a real tab
does. --mode sse-only / hls-only isolate one axis at a time, which is what
SCALE-100-PLAN.md sec 7's instruments table asks for ("HLS proxy is the
first wall" wants viewers only, no SSE).

No third-party dependency beyond `requests` (already present on this
machine at the time this rig was written -- see the module's README for
what was checked and rejected: k6/hey/wrk/ab were not installed, and
adding one would violate "prefer the simplest thing that works" when
threads + requests already cover this rig's concurrency (order-100
blocking, I/O-bound connections -- well within what the GIL allows since
every worker spends its time in a network read, not in Python bytecode).

Usage:
    python3 loadrig.py --levels 5,20,50,100 --duration 30
    python3 loadrig.py --levels 100 --mode hls-only --duration 20
    python3 loadrig.py --dry-run     # prints the plan, touches nothing

See README.md in this directory for prerequisites and what each column means.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone

try:
    import requests
except ImportError:  # pragma: no cover - environment check, not a code path
    print("This rig needs the `requests` package (pip install requests).", file=sys.stderr)
    sys.exit(1)


# --------------------------------------------------------------------------
# Config -- every magic number lives here, named, so a re-run can be
# reasoned about without reading the worker functions.
# --------------------------------------------------------------------------

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_LEVELS = [5, 20, 50, 100]
DEFAULT_DURATION_SECONDS = 30
# How often the REST latency sampler fires one GET while a level is running.
REST_SAMPLE_INTERVAL_SECONDS = 0.2
# How often the HLS viewer loop re-pulls the playlist (mimics an hls.js
# client re-polling a live media playlist).
HLS_POLL_INTERVAL_SECONDS = 1.0
# How often the resource-metrics sampler (jstat/proc/docker) takes a sample
# during a level's run -- averaged/maxed over the window in the report.
METRICS_SAMPLE_INTERVAL_SECONDS = 1.0
# Wall-clock cushion between levels so one level's connections fully drain
# before the next opens -- avoids attributing level N's teardown cost to
# level N+1's ramp-up.
COOLDOWN_SECONDS = 3
HTTP_TIMEOUT_SECONDS = 10
# A fresh /api/live connection replays its per-topic ring buffer immediately
# (event-buffer: 300 by default, LiveUpdateRegistry) -- on a busy instance
# that backlog can span tens of seconds of history, which would otherwise
# get measured as "envelope lag" when it is really one-time catch-up cost.
# Samples in the first WARMUP window after connect are discarded so the lag
# column reflects steady-state delivery, not backlog replay.
SSE_LAG_WARMUP_SECONDS = 5.0
# A GET this cheap and side-effect-free represents "ordinary REST traffic"
# for the p50/p99 REST latency column -- it is what FleetStore's own poll
# fallback calls today (fleet-store.ts), so it is representative rather
# than synthetic.
REST_SAMPLE_PATH = "/api/assets"
# Set via --debug-hls-errors; prints upstream body/headers on every HLS
# proxy failure -- a diagnostic switch, not something a normal run needs.
DEBUG_HLS_ERRORS = False


@dataclass
class Sample:
    """One timed request/event, kept minimal so 100 x 30s of these stay cheap."""

    latency_ms: float
    ok: bool
    ts: float = field(default_factory=time.monotonic)


@dataclass
class LevelResult:
    level: int
    mode: str
    duration_s: int
    rest_latencies_ms: list = field(default_factory=list)
    rest_errors: int = 0
    sse_lag_ms: list = field(default_factory=list)
    sse_connect_failures: int = 0
    sse_drops: int = 0
    hls_latencies_ms: list = field(default_factory=list)
    hls_errors: int = 0
    # status codes of failed fetches, one entry per failure -- list.append is
    # GIL-atomic across threads, so this stays race-free without a lock;
    # tallied into counts only at report time (see print_table).
    hls_error_statuses: list = field(default_factory=list)
    heap_used_mb: list = field(default_factory=list)
    gc_pause_ms_total_delta: float = 0.0
    thread_counts: list = field(default_factory=list)
    db_connections: list = field(default_factory=list)
    cpu_percent: list = field(default_factory=list)


def percentile(values: list, p: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


# --------------------------------------------------------------------------
# Process/JVM/DB instrumentation -- no actuator endpoint is exposed
# (confirmed 404 on /actuator/health at rig-writing time), so this reads
# the JVM directly via jstat and /proc, and the DB via `docker exec psql`.
# All read-only; none of this touches product code.
# --------------------------------------------------------------------------


def find_pid_by_port(port: int) -> int | None:
    """Resolve the vision-app JVM's pid by its listening port, not by process
    name -- works whether it was launched via `java -jar` or an IDE run
    config (both were seen while building this rig)."""
    try:
        out = subprocess.run(
            ["ss", "-ltnp"], capture_output=True, text=True, timeout=5
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    for line in out.splitlines():
        if f":{port} " in line or line.rstrip().endswith(f":{port}"):
            m = re.search(r"pid=(\d+)", line)
            if m:
                return int(m.group(1))
    return None


def jstat_gc(pid: int) -> dict | None:
    """One jstat -gc sample, forced to the C locale -- this machine's default
    locale (ru) prints decimals with a comma (e.g. "355,990"), which silently
    breaks float parsing if not forced."""
    try:
        out = subprocess.run(
            ["jstat", "-gc", str(pid)],
            capture_output=True,
            text=True,
            timeout=5,
            env={"LC_ALL": "C", "PATH": "/usr/bin:/bin:/usr/local/bin"},
        )
    except (OSError, subprocess.SubprocessError):
        return None
    lines = out.stdout.strip().splitlines()
    if len(lines) < 2:
        return None
    headers = lines[0].split()
    values = [float(v) for v in lines[1].split()]
    return dict(zip(headers, values))


def proc_status(pid: int) -> dict:
    try:
        with open(f"/proc/{pid}/status") as f:
            text = f.read()
    except OSError:
        return {}
    result = {}
    m = re.search(r"Threads:\s+(\d+)", text)
    if m:
        result["threads"] = int(m.group(1))
    m = re.search(r"VmRSS:\s+(\d+)", text)
    if m:
        result["rss_kb"] = int(m.group(1))
    return result


def proc_cpu_ticks(pid: int) -> tuple[int, int] | None:
    """(utime+stime in clock ticks, wall-clock ns) for a CPU% delta sample."""
    try:
        with open(f"/proc/{pid}/stat") as f:
            fields = f.read().split()
    except OSError:
        return None
    # /proc/pid/stat fields 14/15 are utime/stime (1-indexed); field 2 (comm)
    # may contain spaces, but this process's comm ("java") never does.
    utime, stime = int(fields[13]), int(fields[14])
    return utime + stime, time.monotonic_ns()


def cpu_percent_over(pid: int, before: tuple, after: tuple) -> float | None:
    if before is None or after is None:
        return None
    try:
        clk_tck = int(
            subprocess.run(["getconf", "CLK_TCK"], capture_output=True, text=True, timeout=5).stdout.strip()
        )
    except (OSError, subprocess.SubprocessError, ValueError):
        clk_tck = 100  # near-universal Linux default; documented fallback, not a guess presented as measured
    ticks_delta = after[0] - before[0]
    wall_seconds = (after[1] - before[1]) / 1e9
    if wall_seconds <= 0:
        return None
    return (ticks_delta / clk_tck) / wall_seconds * 100.0


def db_connection_count(pg_container: str, pg_user: str, pg_db: str) -> int | None:
    try:
        out = subprocess.run(
            [
                "docker", "exec", pg_container,
                "psql", "-U", pg_user, "-d", pg_db, "-t", "-c",
                "select count(*) from pg_stat_activity where datname = current_database();",
            ],
            capture_output=True, text=True, timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    try:
        return int(out.stdout.strip())
    except ValueError:
        return None


class MetricsSampler(threading.Thread):
    """Background thread: samples jstat/proc/docker on a fixed cadence into
    a LevelResult while a level is running. Runs on its own thread so the
    workers below never block on it."""

    def __init__(self, pid: int | None, pg_container: str, pg_user: str, pg_db: str,
                 result: LevelResult, stop_event: threading.Event):
        super().__init__(daemon=True)
        self.pid = pid
        self.pg_container = pg_container
        self.pg_user = pg_user
        self.pg_db = pg_db
        self.result = result
        self.stop_event = stop_event
        self._gc_start: dict | None = None
        self._gc_last: dict | None = None

    def run(self) -> None:
        prev_cpu = proc_cpu_ticks(self.pid) if self.pid else None
        while not self.stop_event.is_set():
            if self.pid:
                gc = jstat_gc(self.pid)
                if gc:
                    if self._gc_start is None:
                        self._gc_start = gc
                    self._gc_last = gc
                    used_mb = (gc.get("S0U", 0) + gc.get("S1U", 0) + gc.get("EU", 0) + gc.get("OU", 0)) / 1024.0
                    self.result.heap_used_mb.append(used_mb)
                status = proc_status(self.pid)
                if "threads" in status:
                    self.result.thread_counts.append(status["threads"])
                now_cpu = proc_cpu_ticks(self.pid)
                pct = cpu_percent_over(self.pid, prev_cpu, now_cpu)
                if pct is not None:
                    self.result.cpu_percent.append(pct)
                prev_cpu = now_cpu
            conns = db_connection_count(self.pg_container, self.pg_user, self.pg_db)
            if conns is not None:
                self.result.db_connections.append(conns)
            self.stop_event.wait(METRICS_SAMPLE_INTERVAL_SECONDS)
        if self._gc_start and self._gc_last:
            ygct_delta = self._gc_last.get("YGCT", 0) - self._gc_start.get("YGCT", 0)
            fgct_delta = self._gc_last.get("FGCT", 0) - self._gc_start.get("FGCT", 0)
            self.result.gc_pause_ms_total_delta = (ygct_delta + fgct_delta) * 1000.0


# --------------------------------------------------------------------------
# Load workers
# --------------------------------------------------------------------------


def rest_sampler_worker(base_url: str, stop_event: threading.Event, result: LevelResult) -> None:
    session = requests.Session()
    url = base_url.rstrip("/") + REST_SAMPLE_PATH
    while not stop_event.is_set():
        t0 = time.monotonic()
        try:
            resp = session.get(url, timeout=HTTP_TIMEOUT_SECONDS)
            latency_ms = (time.monotonic() - t0) * 1000.0
            if resp.status_code < 500:
                result.rest_latencies_ms.append(latency_ms)
            else:
                result.rest_errors += 1
        except requests.RequestException:
            result.rest_errors += 1
        stop_event.wait(REST_SAMPLE_INTERVAL_SECONDS)


def sse_client_worker(base_url: str, stop_event: threading.Event, result: LevelResult) -> None:
    """Holds one SSE connection open on /api/live and records envelope lag
    for every `type":"event"` payload -- those carry a server "at" timestamp
    (docs/plans/active/SCALE-100-PLAN.md's own live/detection path), so lag is a
    real measurement (client receipt - server "at"), not a proxy metric.
    Other topics (fleet/devices/map) are counted as arrivals but have no
    per-payload timestamp to diff against."""
    url = base_url.rstrip("/") + "/api/live"
    try:
        resp = requests.get(url, stream=True, timeout=(HTTP_TIMEOUT_SECONDS, None))
    except requests.RequestException:
        result.sse_connect_failures += 1
        return
    if resp.status_code != 200:
        result.sse_connect_failures += 1
        return
    connected_at = time.monotonic()
    try:
        for raw_line in resp.iter_lines(decode_unicode=True):
            if stop_event.is_set():
                break
            if raw_line is None or not raw_line.startswith("data:"):
                continue
            payload_text = raw_line[len("data:"):].strip()
            recv_ts = datetime.now(timezone.utc)
            past_warmup = (time.monotonic() - connected_at) >= SSE_LAG_WARMUP_SECONDS
            try:
                envelope = json.loads(payload_text)
            except json.JSONDecodeError:
                continue
            if not past_warmup:
                continue  # backlog replay window -- see SSE_LAG_WARMUP_SECONDS
            payload = envelope.get("payload")
            at = payload.get("at") if isinstance(payload, dict) else None
            if at:
                try:
                    sent_ts = datetime.fromisoformat(at.replace("Z", "+00:00"))
                    lag_ms = (recv_ts - sent_ts).total_seconds() * 1000.0
                    if lag_ms >= 0:
                        result.sse_lag_ms.append(lag_ms)
                except ValueError:
                    pass
    except requests.RequestException:
        result.sse_drops += 1
    finally:
        resp.close()


_M3U8_URI_RE = re.compile(r"^(?!#)(\S+\.m3u8\S*)$", re.MULTILINE)
_SEGMENT_LINE_RE = re.compile(r"^(?!#)(\S+\.(?:ts|m4s|mp4)\S*)$")


def _latest_real_segment(media_playlist_text: str) -> str | None:
    """The newest segment URI in an LL-HLS media playlist that is NOT a
    mediamtx EXT-X-GAP placeholder (a literal "gap.mp4" mediamtx emits for a
    segment slot it intentionally has nothing to serve -- fetching it 401s by
    design, which is correct upstream behavior, not a proxy failure). A first-
    match regex picked these up under load (more frequent gaps while the
    encoder is contended) and was misreported as HLS proxy errors before this
    fix -- walk the playlist instead and skip any URI whose preceding
    non-blank line is #EXT-X-GAP, preferring the last (most recent) segment."""
    lines = media_playlist_text.splitlines()
    candidates = []
    prev_nonblank = None
    for line in lines:
        stripped = line.strip()
        if not stripped:
            continue
        m = _SEGMENT_LINE_RE.match(stripped)
        if m and prev_nonblank != "#EXT-X-GAP":
            candidates.append(m.group(1))
        prev_nonblank = stripped
    return candidates[-1] if candidates else None


def hls_viewer_worker(base_url: str, stream_id: str, stop_event: threading.Event, result: LevelResult) -> None:
    """Repeatedly pulls the master playlist, then the media playlist, then
    one segment named in it -- the same three-hop pattern a real hls.js
    client does, through the same /hls/{streamId}/** proxy S1 rewrites."""
    session = requests.Session()
    base = base_url.rstrip("/")
    master_url = f"{base}/hls/{stream_id}/index.m3u8"
    while not stop_event.is_set():
        try:
            t0 = time.monotonic()
            master = session.get(master_url, timeout=HTTP_TIMEOUT_SECONDS)
            result.hls_latencies_ms.append((time.monotonic() - t0) * 1000.0)
            if master.status_code != 200:
                result.hls_errors += 1
                result.hls_error_statuses.append(master.status_code)
                if DEBUG_HLS_ERRORS:
                    print(f"# DEBUG master {master.status_code} {master_url} body={master.text[:300]!r} "
                          f"headers={dict(master.headers)}", file=sys.stderr)
                stop_event.wait(HLS_POLL_INTERVAL_SECONDS)
                continue
            media_match = _M3U8_URI_RE.search(master.text)
            if media_match:
                media_url = f"{base}/hls/{stream_id}/{media_match.group(1)}"
                t1 = time.monotonic()
                media = session.get(media_url, timeout=HTTP_TIMEOUT_SECONDS)
                result.hls_latencies_ms.append((time.monotonic() - t1) * 1000.0)
                if media.status_code == 200:
                    seg_uri = _latest_real_segment(media.text)
                    if seg_uri:
                        seg_url = f"{base}/hls/{stream_id}/{seg_uri}"
                        t2 = time.monotonic()
                        seg = session.get(seg_url, timeout=HTTP_TIMEOUT_SECONDS)
                        result.hls_latencies_ms.append((time.monotonic() - t2) * 1000.0)
                        if seg.status_code != 200:
                            result.hls_errors += 1
                            result.hls_error_statuses.append(seg.status_code)
                            if DEBUG_HLS_ERRORS:
                                print(f"# DEBUG segment {seg.status_code} {seg_url} body={seg.text[:300]!r}",
                                      file=sys.stderr)
                else:
                    result.hls_errors += 1
                    result.hls_error_statuses.append(media.status_code)
                    if DEBUG_HLS_ERRORS:
                        print(f"# DEBUG media {media.status_code} {media_url} body={media.text[:300]!r} "
                              f"headers={dict(media.headers)}", file=sys.stderr)
        except requests.RequestException:
            result.hls_errors += 1
            result.hls_error_statuses.append("conn")
        stop_event.wait(HLS_POLL_INTERVAL_SECONDS)


# --------------------------------------------------------------------------
# Orchestration
# --------------------------------------------------------------------------


def discover_stream_ids(base_url: str) -> list:
    try:
        resp = requests.get(base_url.rstrip("/") + "/api/streams", timeout=HTTP_TIMEOUT_SECONDS)
        resp.raise_for_status()
        return [s["streamId"] for s in resp.json()]
    except (requests.RequestException, ValueError, KeyError):
        return []


def ensure_sim_sources(base_url: str, k: int, existing_stream_count: int) -> tuple[list, list]:
    """Tops up to k simulated assets via POST /api/simulations (empty body =
    fully synthetic video + sim telemetry, in-process, no video file or real
    port needed -- SCALE-100-PLAN's own K-simulated-telemetry-sources axis).
    Reuses whatever is already streaming first, only creates the shortfall.
    Returns (new_stream_ids, created_asset_ids) -- the latter is what the
    caller must DELETE /api/simulations/{assetId} on the way out."""
    to_create = max(0, k - existing_stream_count)
    if to_create == 0:
        return [], []
    new_stream_ids, created_asset_ids = [], []
    for i in range(to_create):
        try:
            resp = requests.post(
                base_url.rstrip("/") + "/api/simulations",
                json={"displayName": f"loadrig-sim-{i}"},
                timeout=HTTP_TIMEOUT_SECONDS,
            )
            if resp.status_code == 201:
                body = resp.json()
                created_asset_ids.append(body["assetId"])
                if "streamId" in body:
                    new_stream_ids.append(body["streamId"])
            else:
                print(f"# WARNING: POST /api/simulations returned {resp.status_code} "
                      f"provisioning source {i + 1}/{to_create}", file=sys.stderr)
        except requests.RequestException as e:
            print(f"# WARNING: POST /api/simulations failed provisioning source "
                  f"{i + 1}/{to_create}: {e}", file=sys.stderr)
    return new_stream_ids, created_asset_ids


def cleanup_sim_sources(base_url: str, asset_ids: list) -> None:
    for asset_id in asset_ids:
        try:
            requests.delete(base_url.rstrip("/") + f"/api/simulations/{asset_id}", timeout=HTTP_TIMEOUT_SECONDS)
        except requests.RequestException:
            pass  # best-effort teardown -- a leaked demo asset is a cosmetic problem, not a correctness one


def run_level(base_url: str, level: int, duration: int, mode: str,
              stream_ids: list, pid: int | None,
              pg_container: str, pg_user: str, pg_db: str) -> LevelResult:
    result = LevelResult(level=level, mode=mode, duration_s=duration)
    stop_event = threading.Event()

    metrics_thread = MetricsSampler(pid, pg_container, pg_user, pg_db, result, stop_event)
    metrics_thread.start()

    workers = []
    with ThreadPoolExecutor(max_workers=level * 3 + 4) as pool:
        # One REST caller per "user", not one for the whole level -- this is
        # what makes the REST p50/p99 and DB-connection columns actually
        # respond to the Users axis (SCALE-100-PLAN.md sec 7: "p99 under 100
        # concurrent REST callers"), matching an SSE+HLS tab that also polls.
        rest_futures = [pool.submit(rest_sampler_worker, base_url, stop_event, result) for _ in range(level)]

        if mode in ("combined", "sse-only"):
            for _ in range(level):
                workers.append(pool.submit(sse_client_worker, base_url, stop_event, result))
        if mode in ("combined", "hls-only") and stream_ids:
            for i in range(level):
                sid = stream_ids[i % len(stream_ids)]
                workers.append(pool.submit(hls_viewer_worker, base_url, sid, stop_event, result))

        time.sleep(duration)
        stop_event.set()
        # give SSE readers a moment to notice stop_event and unblock their
        # generator on the next server-sent line rather than hanging the pool
        for f in workers:
            try:
                f.result(timeout=5)
            except Exception:
                pass
        for f in rest_futures:
            try:
                f.result(timeout=5)
            except Exception:
                pass

    metrics_thread.join(timeout=5)
    return result


def fmt(value: float, digits: int = 1) -> str:
    if value is None or (isinstance(value, float) and value != value):  # NaN
        return "n/a"
    return f"{value:.{digits}f}"


def print_table(results: list) -> None:
    headers = [
        "Users", "REST p50 (ms)", "REST p99 (ms)", "REST err",
        "SSE lag p50 (ms)", "SSE lag p99 (ms)", "SSE fail/drop",
        "HLS p50 (ms)", "HLS p99 (ms)", "HLS err",
        "Heap used (MB)", "GC pause (ms)", "Threads", "DB conns", "CPU %",
    ]
    rows = []
    for r in results:
        rows.append([
            str(r.level),
            fmt(percentile(r.rest_latencies_ms, 50)),
            fmt(percentile(r.rest_latencies_ms, 99)),
            str(r.rest_errors),
            fmt(percentile(r.sse_lag_ms, 50)),
            fmt(percentile(r.sse_lag_ms, 99)),
            f"{r.sse_connect_failures}/{r.sse_drops}",
            fmt(percentile(r.hls_latencies_ms, 50)),
            fmt(percentile(r.hls_latencies_ms, 99)),
            str(r.hls_errors),
            fmt(max(r.heap_used_mb) if r.heap_used_mb else float("nan")),
            fmt(r.gc_pause_ms_total_delta),
            str(max(r.thread_counts) if r.thread_counts else "n/a"),
            str(max(r.db_connections) if r.db_connections else "n/a"),
            fmt(statistics.mean(r.cpu_percent) if r.cpu_percent else float("nan")),
        ])
    widths = [max(len(h), *(len(row[i]) for row in rows)) if rows else len(h) for i, h in enumerate(headers)]
    def line(cells):
        return " | ".join(c.ljust(w) for c, w in zip(cells, widths))
    print(line(headers))
    print("-+-".join("-" * w for w in widths))
    for row in rows:
        print(line(row))

    for r in results:
        if r.hls_error_statuses:
            counts = {}
            for code in r.hls_error_statuses:
                counts[code] = counts.get(code, 0) + 1
            breakdown = ", ".join(f"{code}x{n}" for code, n in sorted(counts.items(), key=str))
            print(f"# level={r.level} HLS error breakdown: {breakdown}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--levels", default=",".join(str(l) for l in DEFAULT_LEVELS),
                         help="comma-separated concurrency levels, e.g. 5,20,50,100")
    parser.add_argument("--duration", type=int, default=DEFAULT_DURATION_SECONDS,
                         help="seconds to hold each level's load")
    parser.add_argument("--mode", choices=["combined", "sse-only", "hls-only"], default="combined")
    parser.add_argument("--port", type=int, default=8080, help="app port, for PID auto-discovery")
    parser.add_argument("--pid", type=int, default=None, help="override: skip port-based PID discovery")
    parser.add_argument("--pg-container", default="vision-postgres-1")
    parser.add_argument("--pg-user", default="vision")
    parser.add_argument("--pg-db", default="vision")
    parser.add_argument("--json-out", default=None, help="optional path to also dump raw results as JSON")
    parser.add_argument("--sim-sources", type=int, default=0,
                         help="K: top up to this many simulated assets/streams via POST "
                              "/api/simulations before the sweep (independent of --levels' "
                              "user count -- this is ingest load, not viewer load)")
    parser.add_argument("--keep-sim", action="store_true",
                         help="don't DELETE simulated assets this run created when it exits")
    parser.add_argument("--dry-run", action="store_true", help="print the plan and exit, no requests made")
    parser.add_argument("--debug-hls-errors", action="store_true",
                         help="print upstream body/headers for every HLS proxy failure")
    args = parser.parse_args()

    global DEBUG_HLS_ERRORS
    DEBUG_HLS_ERRORS = args.debug_hls_errors

    levels = [int(x) for x in args.levels.split(",") if x.strip()]
    pid = args.pid or find_pid_by_port(args.port)

    print(f"# vision-app load rig -- base_url={args.base_url} mode={args.mode} "
          f"levels={levels} duration={args.duration}s pid={pid or 'NOT FOUND'}")
    if pid is None:
        print("# WARNING: could not resolve the app's JVM pid via port "
              f"{args.port} -- heap/GC/thread/CPU columns will read n/a. "
              "Pass --pid explicitly if the app runs under a different port.")

    stream_ids = discover_stream_ids(args.base_url)
    created_asset_ids: list = []
    if args.sim_sources > 0:
        print(f"# provisioning simulated sources: have {len(stream_ids)} streaming, "
              f"want {args.sim_sources}...", file=sys.stderr)
        new_ids, created_asset_ids = ensure_sim_sources(args.base_url, args.sim_sources, len(stream_ids))
        stream_ids.extend(new_ids)
        print(f"# now {len(stream_ids)} streams available ({len(created_asset_ids)} created by this run)",
              file=sys.stderr)

    if args.mode in ("combined", "hls-only") and not stream_ids:
        print("# WARNING: GET /api/streams returned no active streams -- HLS "
              "columns will read n/a for every level. Start at least one "
              "stream first (see README.md), or pass --sim-sources.")

    if args.dry_run:
        print(f"# stream_ids discovered: {stream_ids}")
        print("# dry run -- exiting without sending load")
        if created_asset_ids and not args.keep_sim:
            cleanup_sim_sources(args.base_url, created_asset_ids)
        return 0

    results = []
    try:
        for i, level in enumerate(levels):
            print(f"\n## level={level} starting ({args.duration}s)...", file=sys.stderr)
            result = run_level(args.base_url, level, args.duration, args.mode,
                                stream_ids, pid, args.pg_container, args.pg_user, args.pg_db)
            results.append(result)
            if i < len(levels) - 1:
                time.sleep(COOLDOWN_SECONDS)
    finally:
        if created_asset_ids and not args.keep_sim:
            print(f"\n# cleaning up {len(created_asset_ids)} simulated asset(s) this run created...",
                  file=sys.stderr)
            cleanup_sim_sources(args.base_url, created_asset_ids)

    print()
    print_table(results)

    if args.json_out:
        with open(args.json_out, "w") as f:
            json.dump([r.__dict__ for r in results], f, indent=2, default=str)
        print(f"\n# raw samples written to {args.json_out}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
