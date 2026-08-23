# SCALE-100 Band A — measured after-sweep

What S1 (HLS proxy), S2 (SSE dispatch) and S3 (connection pool) actually bought, measured with the
same rig ([`tools/loadrig/`](../../tools/loadrig/)) that produced
[`SCALE-100-BASELINE.md`](SCALE-100-BASELINE.md). Read that document first for the rig's design and
its four pitfalls; this one is only about the before/after comparison.

**These are real measurements.** Both sides were run on the same machine, back to back, by the same
script, on 2026-08-18.

## 1. Why this is a fresh before-run, not a diff against the baseline

The baseline (§4.1 of that document) was taken against an **IntelliJ dev JVM** —
`-XX:TieredStopAtLevel=1`, a debugger agent, JMX — because that process happened to already be
running. Comparing a packaged-jar "after" against it would have credited S1/S2/S3 with the C2
compiler. So the baseline commit was checked out into its own worktree, built into its own jar, and
measured again under identical conditions. **Every number below is jar-vs-jar.**

| | before | after |
|---|---|---|
| Commit | `a70c107` (code state = `74eab93`, POSTGRES-ONLY) | `6e640d3` (branch tip) |
| Contains | — | S1 `73d3cc6`, S2 `02cb7d8`, S3 `5060686`+`cb99829` |
| Launch | `java -jar vision-app-*.jar`, no JVM flags | identical |
| Ambient load | 2 simulated streams, detection **on**, cv-service local | identical |
| Driver | `scratchpad/run_sweep.sh`, one script, one argument | identical |

Both jars carry the `flyway-core` packaging fix (§4), without which neither boots.

## 2. Combined sweep — SSE + HLS + REST per user

One "user" = one `/api/live` SSE connection + one HLS viewer (3-hop) + one `GET /api/assets` poller.
30s per level. **b** = before, **a** = after.

| Users | REST p50 b→a | REST p99 b→a | **REST err b→a** | SSE lag p50 b→a | SSE lag p99 b→a | SSE fail b→a | HLS p50 b→a | HLS p99 b→a | Threads b→a |
|---|---|---|---|---|---|---|---|---|---|
| 5   | 28.5 → **24.3** | 54.0 → **45.9** | 0 → 0 | 0.5 → 0.6 | 2.2 → **1.7** | 0 → 0 | 7.1 → **5.6** | 1702 → **1200** | 130 → **115** |
| 20  | 38.6 → **27.5** | 123.6 → 129.2 | 4 → **0** | 1.3 → **1.2** | 16.2 → **6.8** | 1 → **0** | 8.0 → **7.2** | 95 → **57** | 194 → **133** |
| 50  | 103.2 → **60.8** | 334.5 → **223.7** | 55 → **0** | 16.2 → **6.8** | 162.2 → **103.8** | 0 → 0 | 66.3 → **30.2** | 380 → **237** | 376 → **143** |
| 100 | 258.6 → **196.7** | 989.7 → **742.2** | **307 → 0** | 64.0 → **53.6** | 518.3 → **403.0** | **19 → 0** | 323.1 → **198.5** | 1400 → **911** | 268 → **163** |

Every column improves at every level except REST p99 at 20 users (123.6 → 129.2, inside this
machine's run-to-run noise).

## 3. What each wave actually did

### 3.1 S3 (connection pool) — the headline, and the plan had the reason wrong

**307 failed REST requests at 100 users became 0.** Not slower — *failed*: roughly one in four polls.
The before-side app log names the cause 1100 times:

> `org.hibernate.HibernateException: The internal connection pool has reached its maximum size and no
> connection is currently available`

Zero occurrences on the after side.

This **corrects fact (g) of the plan**, which called the old setup unpooled. It was not: Hibernate's
`DriverManagerConnectionProviderImpl` ships a rudimentary 20-connection pool. The defect was never
"opens a connection per call" — it is that this pool **fails immediately instead of waiting** when
saturated, converting a queueing problem into a 500. HikariCP's `connectionTimeout` (30s) makes the
same contention wait rather than fail, which is the entire difference between the two rows.

It also explains [`SCALE-100-BASELINE.md`](SCALE-100-BASELINE.md) §3.3, where the rig never observed
more than 1 concurrent backend and said so as a floor. With 30s levels and a real pool the column
resolves properly: 9 → 15 → 21 → 21 (after), tracking the level until it reaches `maxPoolSize` 20 plus
Flyway's. The baseline's "DB conns: 1" was a sampling artifact, as it suspected.

### 3.2 S1 (HLS proxy) — thread growth is flat now

The isolating run (`--mode hls-only --levels 100`), the same probe that produced the baseline's
headline 908:

| | before | after |
|---|---|---|
| Threads | 314 | **168** |
| REST p99 | 984.8 ms | **684.7 ms** |
| REST errors | 161 | **0** |
| HLS p50 | 315.6 ms | **157.0 ms** |
| HLS p99 | 1395.1 ms | **848.8 ms** |
| GC pause | 319 ms | **176 ms** |

Threads at idle are identical (51 vs 52), so the whole difference is per-request client allocation.
Note the **baseline's 908 did not reproduce** — a packaged JVM with a smaller heap collects the
abandoned clients sooner than the dev JVM did. The direction and the mechanism reproduce; the
magnitude was environment-specific and should not be quoted as "908 → 168".

### 3.3 S2 (SSE dispatch) — measurable, smaller than the other two

SSE envelope lag p99 improves 36% at 50 users (162 → 104 ms) and 22% at 100 (518 → 403 ms), and 19
connection failures at 100 users become 0. Real, but the smallest of the three effects — consistent
with the plan ranking S2 below S1 and S3.

Measuring this at all required a change of method. The rig times only payloads carrying an `at`
field, which in practice means DETECTION events, and detection is **opt-in and demand-gated**
(CV-DEMAND §3.5) — so an SSE subscription alone produces nothing to time, and the column reads `n/a`.
A `demand_keeper.py` sidecar (PATCH `detectionEnabled:true`, then poll `/detections`) ran identically
alongside both sides. Anyone re-running this needs it, or the S2 column is blank.

## 4. Two shipping bugs this sweep found

Neither is a scaling issue. Both were found because this was the first time anyone ran the packaged
jar over plain http.

1. **`3756a0a` — the container could not boot.** `station/vision-app/pom.xml` declared `flyway-core`
   at `test` scope for testsupport code, on the premise that runtime still had it transitively.
   Maven's nearest-definition rule made the direct declaration win, demoting the module's only
   `flyway-core` to test and dropping it out of the repackaged jar. `java -jar` — exactly what
   `Dockerfile` runs — died with `NoClassDefFoundError: org/flywaydb/core/Flyway`. Introduced by
   `74eab93`; invisible to every test and IDE launch, which run from a classpath where test scope is
   present.

2. **`6e640d3` — HLS playback was dead over plain http.** S1 began relaying every upstream
   `Set-Cookie`. mediamtx emits each session cookie twice, once bare and once hardened with `Secure;
   SameSite=None; Partitioned`; same name and path, so the hardened copy replaces the usable one. Over
   http the browser drops it, `hlsSession` never comes back, and mediamtx answers the media playlist
   with 401. **2592 of 2592 HLS requests failed this way** in the first after-run — against zero
   before, because the old per-request cookie jar never echoed mediamtx's `cookieCheck` probe, so
   mediamtx fell back to putting the session in the playlist URL. It hid because browsers treat
   `http://localhost` as a secure context and keep `Secure` cookies there. The proxy now rewrites
   cookie attributes to match the viewer's scheme.

   **The §2 and §3 tables are from the re-run after this fix**, with 0 HLS errors on both sides.

## 5. Where the ceiling is now

At 100 concurrent users on a shared 12-core laptop, with CV running: **no errors on any axis**, REST
p99 742 ms, HLS p99 911 ms, SSE lag p99 403 ms. Before Band A the same load dropped ~25% of REST
requests. The app now degrades in latency instead of availability, which was Band A's goal.

Latency at 100 users is still not good, and CPU is the visible constraint (380% of 1200% with two
video encodes and inference in the same JVM). Band B's targets — telemetry batching (S4), debounced
fleet broadcasts (S5), gating the ungated polls (S6) — all reduce work per user rather than unblock a
serialized path, which is the right shape for what is left.

## 6. Caveats

1. **Same shared laptop as the baseline** (`vladte-HP-ProBook-455-G8`, 12 cores, 30 GiB), not a
   dedicated test box. Absolute numbers are machine-specific; the before/after *ratios* are what this
   document claims.
2. **CPU is near saturation at the top levels on both sides** (342% before, 380% after at 100 users),
   so the 100-user row measures a partly CPU-bound system, not a purely software-bound one.
3. **One run per side.** No repetition, so single-column differences under ~10% (e.g. REST p99 at 20
   users) are not distinguishable from noise. The large effects — 307 errors → 0, threads 376 → 143 —
   are far outside it.
4. **The browser idle-tab polling spot-check is still not done** (baseline §3.4). It remains the
   weakest claim in the plan's §2.1.
5. **`spring.threads.virtual.enabled` was off on both sides**, per the reversal in
   [`SCALE-100-CONTEXT.md`](../plans/done/SCALE-100-CONTEXT.md) §7. This sweep says nothing about it.
