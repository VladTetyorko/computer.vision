# SCALE-100 wave S0 -- load rig

A load-testing harness for `vision-app`, built for
[`docs/plans/active/SCALE-100-PLAN.md`](../../docs/plans/active/SCALE-100-PLAN.md) wave S0. It drives a
running instance at stated concurrency and prints a table: REST p50/p99 latency, SSE envelope lag, JVM
heap + GC pause, thread count, DB connection count, and process CPU, at whatever levels you ask for
(the plan wants 5 / 20 / 50 / 100).

The measured baseline this rig produced is
[`docs/conclusions/SCALE-100-BASELINE.md`](../../docs/conclusions/SCALE-100-BASELINE.md) -- read that
for the actual numbers and their caveats. This file is about the rig itself.

## Why a plain Python script, not k6/hey/wrk

Checked on the machine this was built on: none of `k6`, `hey`, `wrk`, `ab` were installed, only `curl`
and `python3` (with `requests`, already present). Installing a new load-testing framework for ~100
concurrent, I/O-bound, long-lived connections is more machinery than the job needs -- Python threads
block on network reads, which release the GIL, so a few hundred OS threads each doing blocking
`requests` calls is well within what one process can drive from a single laptop-class machine. If this
rig ever needs to drive thousands of connections, that is the point to reach for `k6`; it was not
needed here.

## What it drives

One **"user"** = one open cockpit tab = one `GET /api/live` SSE connection **plus** one HLS viewer
pulling `GET /hls/{streamId}/index.m3u8` -> media playlist -> a segment, on a 1s poll loop, the same
three-hop pattern `hls.js` uses. `--mode combined` (the default) runs both together per user; `--mode
sse-only` / `--mode hls-only` isolate one axis, which is what the plan's own §7 instrument table asks
for (e.g. "HLS proxy is the first wall" wants HLS viewers with no SSE in flight).

A third, independent axis -- **K simulated telemetry sources** -- is `--sim-sources K`. It calls the
app's own `POST /api/simulations` (empty body: fully synthetic video + `sim` telemetry, in-process, no
video file or real network port needed) to top up to K streaming assets before the sweep starts, reusing
whatever is already streaming first. Whatever it creates gets torn down with `DELETE
/api/simulations/{assetId}` when the rig exits, unless `--keep-sim` is passed. See the baseline doc for
why this axis was only exercised at small K on the machine this ran on, not pushed to 100.

Every REST-latency and DB-connection-under-load number scales with the level too: `run_level` starts
one REST-polling worker (hitting `GET /api/assets`, the same endpoint `FleetStore`'s own poll fallback
calls) *per user*, not one for the whole level -- otherwise those two columns would never respond to
the concurrency axis at all.

## Prerequisites

- A running `vision-app` reachable at `--base-url` (default `http://localhost:8080`), with
  `vision.live.enabled` and `vision.publish.enabled` both true (today's defaults).
- At least one active stream for the HLS/combined modes -- either already running, or provisioned via
  `--sim-sources`.
- `jstat` on `PATH` (ships with the JDK) for heap/GC -- optional, degrades to `n/a` columns if missing
  or if the app's pid can't be resolved.
- `docker` on `PATH` with the Postgres container reachable (`--pg-container`, default
  `vision-postgres-1`) for the DB-connection column -- also optional, same degrade-to-`n/a` behavior.
- No auth setup needed against the default config (`vision.auth.enabled=false` is permit-all).

Nothing above is a hard requirement: every instrument that can't be resolved reports `n/a` rather than
failing the run, so the REST/SSE/HLS columns are always available even on a host with no `jstat`/`docker`.

## Running it

```
python3 loadrig.py --levels 5,20,50,100 --duration 30
```

Useful variants:

```
python3 loadrig.py --dry-run                              # sanity-check discovery, no load sent
python3 loadrig.py --levels 100 --mode hls-only            # isolate the HLS proxy axis
python3 loadrig.py --levels 5 --sim-sources 5 --duration 15  # exercise the K-sources axis
python3 loadrig.py --levels 100 --debug-hls-errors         # print upstream body/headers on HLS failures
python3 loadrig.py --pid 12345                              # skip port-based JVM pid discovery
python3 loadrig.py --json-out results.json                  # dump raw per-sample data alongside the table
```

The app's JVM pid is auto-resolved by asking `ss -ltnp` who is listening on `--port` (default 8080) --
this works whether the app was started via `java -jar` or an IDE run configuration (both were seen
while building this rig; process name/cmdline differs between the two, listening port does not).

## Pitfalls this rig had to work around (read before trusting a number you didn't expect)

These were all found empirically while building this rig against a live instance, and are folded into
the code as named constants/comments, not left as tribal knowledge:

1. **Locale breaks `jstat` parsing.** On a host whose default locale isn't `C`/`en_US` (this one is
   `ru`), `jstat` prints decimals with a comma (`355,990` instead of `355.990`). Every `jstat` invocation
   here is forced to `LC_ALL=C`. If you port this rig and heap numbers look like garbage or throw, check
   this first.
2. **A fresh SSE connection replays its backlog immediately.** `LiveUpdateRegistry`'s per-topic ring
   buffers (`event-buffer: 300` by default) get replayed in full the instant a new `/api/live` connection
   opens. On a busy instance that backlog can span tens of seconds of history -- measured directly: an
   early version of this rig without the warm-up fix reported **47s p50 / 96s p99 SSE lag**, which was
   entirely backlog-replay age, not real delivery latency. `SSE_LAG_WARMUP_SECONDS` (5s) discards samples
   from that window per connection so the lag column reflects steady state.
3. **mediamtx's LL-HLS gap placeholders look like real segments to a naive regex.** mediamtx marks an
   intentionally-missing segment slot with `#EXT-X-GAP` immediately before a literal `gap.mp4` URI in the
   media playlist. A first-match segment regex picks this up under load (gaps appear more often when the
   encoder is contended) and fetching it correctly 401s -- which an earlier version of this rig counted
   as an HLS proxy failure (32/50 requests "failing" at only 5 concurrent users, reproduced then
   root-caused via `--debug-hls-errors`, which dumps the upstream body/headers on every failure). Fixed
   by walking the playlist and skipping any URI whose preceding line is `#EXT-X-GAP`, preferring the
   newest real segment. **If you see HLS errors whose body is `{"status":"error","error":"authentication
   error"}` against a URL ending in `gap.mp4`, that is this bug reappearing, not a proxy regression** --
   check `_latest_real_segment` first.
4. **DB connection count via periodic `pg_stat_activity` polling likely undercounts churn.** With no
   pool (`storage/persistence` still uses the raw `DriverManagerConnectionProvider` at the time this rig
   was written), a repository call may open and close a physical connection within single-digit
   milliseconds -- faster than any reasonable polling interval can reliably observe. A dedicated
   30-thread continuous-hammer probe against `GET /api/assets` never observed more than 1 concurrent
   backend even sampling every ~150-300ms. Read the DB-connections column as "a floor on concurrent
   connections observed," not as evidence of low churn -- see the baseline doc's caveats section.

## What each column means

| Column | Source | Notes |
|---|---|---|
| REST p50/p99 (ms) | `GET /api/assets`, one caller per user, every 200ms | Deliberately more aggressive than the ~1 req/s/tab the plan's §2.1 measured for real polling -- this is a saturation probe, not a realistic-traffic replay. |
| SSE lag (ms) | `type:"event"` envelopes' own `"at"` timestamp vs. local receipt time, post-warmup | Only `event` (detection) envelopes carry a per-payload timestamp; other topics (fleet/devices/map) are received but not lag-timed. |
| HLS p50/p99 (ms) | every playlist/segment fetch in the 3-hop chain | Includes the S1-target buffer-whole-body-in-heap path as it exists today. |
| Heap used (MB) | `jstat -gc`, S0U+S1U+EU+OU, max over the window | Young+old+survivor live bytes; not RSS. |
| GC pause (ms) | `jstat -gc`, (YGCT+FGCT) delta over the window, converted to ms | Total GC time spent during the level, not a per-pause number. |
| Threads | `/proc/<pid>/status` `Threads:`, max over the window | |
| DB conns | `docker exec <pg> psql ... pg_stat_activity`, max over the window | See pitfall 4 above -- likely a floor, not the true churn rate. |
| CPU % | `/proc/<pid>/stat` utime+stime delta / wall time, per-1s sample, averaged | Can exceed 100% (multi-core); this is the whole JVM process, not one thread. |
