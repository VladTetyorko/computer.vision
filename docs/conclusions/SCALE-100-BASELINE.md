# SCALE-100 wave S0 — measured baseline

> **Superseded as a comparison basis.** §4.1 below warns that this ran on a dev JVM. The Band A
> after-sweep therefore rebuilt this commit as a packaged jar and re-measured it rather than diffing
> against these numbers — see [`SCALE-100-AFTER.md`](SCALE-100-AFTER.md). This document is still the
> reference for the rig's pitfalls and for what the pre-Band-A code did on a dev JVM.

Baseline measurements for [`SCALE-100-PLAN.md`](../plans/active/SCALE-100-PLAN.md), taken with the
rig at [`tools/loadrig/`](../../tools/loadrig/) against the code **as it stands before S1/S2/S3**, so
their own before/after rows have something real to compare against. Read
[`tools/loadrig/README.md`](../../tools/loadrig/README.md) alongside this — it documents the rig's
design and four non-obvious pitfalls found while building it (locale, SSE backlog replay, mediamtx
gap-segment placeholders, DB-connection sampling resolution); this document is about what those
measurements found, not how the rig works.

**These are real measurements, not estimates.** Every number below came from an actual run against a
live instance; raw per-sample JSON for each run is described inline. Where something could *not* be
measured, that is stated explicitly rather than filled in with a guess (per this wave's own honesty
requirement).

## 1. Exactly what was measured

| | |
|---|---|
| Commit | `a70c107` (`docs(scale): cost the road from one pilot to a hundred users`) on `feat/scale-100`. This is a docs-only commit; the most recent commit touching `station/`/`storage/`/`contexts/`/`core/` on this branch is `74eab93` ("Postgres is the only store, and tests run against a real one"), which lands POSTGRES-ONLY — the in-memory devsupport repositories are gone, every request reaches Postgres through the still-unpooled `DriverManagerConnectionProvider`. |
| Machine | `vladte-HP-ProBook-455-G8-Notebook-PC`, Ubuntu 24.04.4 LTS, kernel 7.0.0-28-generic, 12 logical CPUs, 30 GiB RAM. A shared development laptop, not a dedicated test box — see §4. |
| JDK | OpenJDK 21.0.11 |
| App process | **Already running** when this wave started (pid 711873, started 2026-08-16 via an IntelliJ run configuration against `station/vision-app` compiled classes) — not started fresh by this rig. See §4 for why this matters and what it costs the numbers below. |
| Persistence | Postgres 16 in Docker (`vision-postgres-1`), host port 5433, unpooled `DriverManagerConnectionProvider` (S3's target). |
| Media | mediamtx 1.19.3 in Docker (`vision-mediamtx-1`). |
| CV | `vision.cv.enabled=true` — 2 pre-existing simulated streams (`FPV Pis-UN`, `FPV Vyriy`) were already running **and detecting** for the entire test session, contributing continuous background CPU load throughout every measurement below (this is disclosed, not hidden — see §4). |
| Auth | `vision.auth.enabled=false` (default) — permit-all, no login flow needed. |

## 2. Main sweep — combined mode (SSE + HLS + REST per user)

One "user" = one open `GET /api/live` SSE connection + one HLS viewer (playlist → media playlist →
segment, 1s poll) + one REST poller (`GET /api/assets` every 200ms) — see the README for why REST
polling is deliberately more aggressive than the ~1 req/s/tab real usage measured in
`SCALE-100-PLAN.md` §2.1 (this is a saturation probe, not a traffic replay). 20s hold per level, 3s
cooldown between levels, against the 2 pre-existing HLS streams.

| Users | REST p50 (ms) | REST p99 (ms) | REST err | SSE lag p50 (ms) | SSE lag p99 (ms) | SSE fail/drop | HLS p50 (ms) | HLS p99 (ms) | HLS err | Heap used (MB) | GC pause (ms) | Threads | DB conns (see §3.3) | CPU % |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 5   | 2.6  | 7.1   | 0 | 0.7  | 1.9   | 0/0 | 9.7   | 40.8   | 0 | 552.4 | 50.0  | 188 | 1 | 193.3 |
| 20  | 2.6  | 45.0  | 0 | 1.2  | 10.9  | 0/0 | 20.4  | 251.2  | 0 | 568.0 | 86.0  | 339 | 1 | 231.0 |
| 50  | 17.6 | 308.2 | 0 | 10.0 | 203.6 | 0/0 | 106.0 | 1290.3 | 0 | 569.2 | 195.0 | 610 | 1 | 299.0 |
| 100 | 70.1 | 424.3 | 0 | 28.5 | 226.5 | 0/0 | 173.9 | 3452.7 | 0 | 592.4 | 225.0 | 494 | 1 | 301.8 |

Command: `python3 loadrig.py --levels 5,20,50,100 --duration 20`. Zero hard errors (no 5xx, no
connection failures, no SSE drops) at any level tested — the app stays *up* through 100 concurrent
users on every axis. What degrades is latency, not availability: REST p99 grows 60x (7ms → 424ms) and
HLS p99 grows 85x (41ms → 3453ms) from 5 to 100 users, while SSE lag stays comparatively bounded (2ms →
227ms p99) — on this evidence, at up to 100 users the HLS proxy path degrades faster than the SSE
dispatcher does, consistent with the plan's ranking of S1 as higher-leverage than S2 (§3), though see
§3.2 for a sharper, more direct confirmation of the same ordering.

## 3. Targeted checks

### 3.1 "HLS proxy is the first wall" (plan §7, row 1) — isolated, no SSE

`python3 loadrig.py --levels 100 --duration 20 --mode hls-only`

| Users | REST p50 | REST p99 | HLS p50 | HLS p99 | Heap (MB) | GC pause (ms) | Threads | CPU % |
|---|---|---|---|---|---|---|---|---|
| 100 | 36.0 | 632.2 | 355.2 | 2326.1 | 901.6 | 214.0 | **908** | 303.3 |

This is the single most concrete finding in this baseline. Compare `Threads: 908` here against
`Threads: 494` in the combined-mode row above at the same user count, and against this same JVM's
**idle thread count of 138** measured before any load (and confirmed stable across the whole session).
100 HLS-only viewers **more than 6.5x'd the JVM's thread count**, and heap peaked at 901.6 MB versus
552-592 MB in every combined-mode row. This is direct, measured evidence for the plan's own item (b):
*"a new `HttpClient` is built per proxied request and never closed — each allocates a selector thread +
connection pool, released only at GC"* (`HlsProxyController.java:192`). Sampled again ~15s after the
run stopped, thread count had fallen back to 232 — mostly transient (GC does eventually reclaim it, as
the javadoc says), but settling well above the pre-test baseline of 138, not fully back to zero cost.

The counterintuitive detail — HLS-only shows a *higher* thread count than combined mode at the same
user count — is plausible rather than confirmed: with SSE connections also competing for Tomcat's
request-handling thread pool in combined mode, fewer HLS requests may complete per second, which would
mean fewer per-request `HttpClient` instances get created and leaked in the same window. This is one
consistent explanation for the pattern, not a verified mechanism — flagging it as a hypothesis, not a
finding, per this wave's own honesty rule.

### 3.2 K simulated telemetry sources — functional check, not a full sweep

`POST /api/simulations` (empty body) creates a fully synthetic asset (in-process video + `sim`
telemetry, no video file or real port needed) and auto-starts its stream — exactly the mechanism
`SCALE-100-PLAN.md` §5 asks the rig to drive. `--sim-sources K` tops up to K streaming assets, reusing
what's already running, and tears down whatever it created on exit.

Verified end-to-end at small scale: `python3 loadrig.py --levels 3 --duration 8 --sim-sources 3
--mode hls-only` topped up from 2 to 3 streams (1 created), ran cleanly (one transient 404 — expected,
matches `HlsProxyController`'s own documented behavior for a segment requested moments after a stream
starts, not a failure), and `DELETE /api/simulations/{assetId}` cleanly removed the created asset —
confirmed via `GET /api/streams` returning to 2 afterward.

**Not pushed to K=100.** Each simulated asset runs a real in-process video encode
(`SCALE-100-PLAN.md` §2.2: "per-stream in-JVM decode + H.264 encode costs roughly one core per
stream"), and this machine was already at load average 9.6-10 on 12 cores from the 2 pre-existing
streams plus the sweep above — adding dozens more would have meant deliberately overloading a shared
development machine mid-session, a real risk (the app process is IntelliJ-attached, not disposable) for
a number the plan's own §2.2 already states from the code. The mechanism is proven; the 100-source
sweep is left for a dedicated, disposable machine (see §5).

### 3.3 DB connection count — a measurement limitation, not a clean number

Every row above shows `DB conns: 1`. This is **not evidence that connection churn is low** — it is
this rig's polling resolution failing to catch it. A dedicated check
(`docs/conclusions` — see raw probe: 30 threads continuously hammering `GET /api/assets` for 6s, with
`pg_stat_activity` polled roughly every 150-300ms via `docker exec`) never observed more than 1
concurrent backend either. With no pool (`storage/persistence` still uses the raw
`DriverManagerConnectionProvider`, S3's target), a repository call most plausibly opens and closes a
physical connection within single-digit milliseconds on loopback Postgres — faster than any
polling-based sampler, including one polling several times a second, can reliably catch mid-flight.
Measuring the true **churn rate** (not point-in-time count) would need either Postgres-side connection
logging (`log_connections`/`log_disconnections`, a config change this wave's scope excludes) or
JVM-side instrumentation (product code, also excluded). Read every "DB conns: 1" above as a **floor**,
not a measurement of "no problem here."

### 3.4 The §2.1 polling-rate claim ("~1 req/s per tab, not 3-5") — attempted, not completed

`SCALE-100-PLAN.md` §7 flags this as the plan's own least-trustworthy figure and asks for a cheap
browser check: open the SPA, let SSE connect, count requests over 60s. This needed a connected Chrome
browser; two were available in this environment (one Windows, one Linux) and selecting between them
requires an interactive tool (`AskUserQuestion`) not available to this background session. Guessing
which browser to drive risked interrupting a session that might belong to the user's own live work, so
this check was left undone rather than guessed at. **Not measured — a person with interactive browser
access should run it**: open the app in a browser, DevTools → Network, filter out `/api/live`, wait 60s
on a Fly cockpit page, count requests.

## 4. Caveats that materially affect every number above

Read this before comparing an S1/S2/S3 "after" row against §2/§3 — some of these differences need to be
controlled for, not just repeated, for a fair before/after comparison.

1. **This was a dev-mode JVM, not a production launch.** The already-running process was started by an
   IntelliJ run configuration with `-XX:TieredStopAtLevel=1` (C1/client-compiler only — the JIT never
   promotes hot methods to C2) plus an attached debugger agent (`-javaagent:...idea_rt.jar`) and JMX
   enabled. This trades steady-state throughput for faster startup — every latency number above is
   plausibly **worse** than the same code launched as `java -jar vision-app-*.jar` would show. A
   same-JVM-mode comparison (both baseline and "after" runs via the packaged jar, or both via the same
   IDE launch) matters more than the absolute numbers here.
2. **Two streams, with detection on, ran for the entire session — including before this rig touched
   anything.** `vision.cv.enabled=true`, two simulated assets (`FPV Pis-UN`, `FPV Vyriy`) were already
   `STREAMING` with active `DETECTION` events flowing at multiple events/second each. Every row above
   includes this ambient load. This makes the baseline more representative of real usage than an
   artificially idle app would be, but it means "5 users" here is never really "5 users and nothing
   else."
3. **The exact working-tree state of the running process could not be independently verified.** This
   agent is git-isolated to its own worktree (a deliberate harness restriction — cross-worktree git
   operations are blocked outright) and could not run `git status`/`git diff` against the checkout the
   running process's `target/classes` was compiled from. It is inferred, not proven, to match `a70c107`:
   that checkout's own `git worktree list` entry shows branch `feat/scale-100` at `a70c107`, and the
   process's compiled-classes timestamp (14:28:33) sits ~90 seconds before the `74eab93` commit that
   landed the last source change on this branch, with only docs commits after it.
4. **This machine was shared and already loaded.** Load average reached ~10 on 12 cores during the
   100-user sweep — a laptop also running an IDE, a debugger-attached JVM, and (per §3.2) two video
   encodes throughout. A dedicated, idle test box would show different absolute numbers; the *shape* of
   the degradation (HLS and REST tails growing much faster than SSE lag) is the more portable finding.
5. **The app process stopped partway through this session, after every measurement above was already
   collected.** All of §2, §3.1 and §3.2 completed successfully; a final post-hoc smoke test run
   afterward found pid 711873 gone and nothing listening on 8080. No OOM evidence was found in the logs
   this agent could read (`dmesg`/`journalctl` were not accessible to confirm either way), and memory
   was healthy (14 GiB available) at the last check before it happened. Two Testcontainers containers
   started roughly when this was noticed, consistent with a sibling S1/S2/S3 agent's own test run rather
   than anything this rig did — but the cause is genuinely undetermined, not confirmed. This did not
   invalidate any number above (all runs completed cleanly beforehand), but it means no further ad-hoc
   verification was possible late in the session, and it is worth someone checking whether that dev
   session was intentionally stopped.
6. **This agent's own worktree started on the wrong base commit** (`331a6a7`, a stale pre-module-reorg
   tree with no `station/`/`storage/` paths and no `SCALE-100-PLAN.md`) and was corrected mid-task via
   `git reset --hard feat/scale-100` after the orchestrator flagged it. All measurements in this
   document were taken after that correction, against the already-running process described above,
   which was unaffected by the worktree issue (it runs from the separate main checkout, not this
   agent's worktree). Flagged for completeness, not because it changed any number.

## 5. How to get a cleaner (non-dev-JVM, dedicated-machine) baseline later

1. `./mvnw -B -pl station/vision-app -am package -DskipTests` (scoped build, not reactor-wide) to
   produce `station/vision-app/target/vision-app-*.jar`.
2. Run it as `java -jar vision-app-*.jar` (no `TieredStopAtLevel`, no debugger agent) against a fresh
   `docker compose up -d postgres mediamtx`, with no pre-existing simulated streams.
3. Re-run `python3 tools/loadrig/loadrig.py --levels 5,20,50,100 --duration 30` — same command used for
   §2 above.
4. If a dedicated (non-laptop, non-shared) machine is available, retry §3.2 at the plan's full K=100.

## 6. Bottom line for S1/S2/S3

- **S1 (HLS out of the JVM byte path) has the sharpest, most directly measured justification of the
  three** — §3.1's thread-count and heap numbers are a concrete, reproducible signature of the exact
  bug S1 targets, not an inference from reading the code.
- **S2 (SSE dispatcher)**: on this evidence, SSE lag stayed the best-behaved of the three axes even at
  100 users (226ms p99, versus HLS's 3453ms) — this baseline does not by itself show SSE as the more
  urgent of the two, though S2's own acceptance test (one TCP-stalled client, 500 connections) targets a
  failure mode this general-purpose sweep does not exercise and this rig does not yet automate.
- **S3 (connection pool)**: §3.3 could not measure connection churn directly, but the fact that it
  *couldn't* — connections cycling faster than a sub-second poll can observe — is itself consistent with
  "no pool, opened and closed per call" rather than evidence against it. REST p99 growing 60x under load
  (§2) is also consistent with per-call connection setup cost compounding under concurrency, though this
  baseline cannot isolate that from JVM/GC/Tomcat-thread-pool effects happening at the same time.
