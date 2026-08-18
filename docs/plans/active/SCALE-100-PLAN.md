# SCALE-100-PLAN — carrying 20–100 concurrent users on one JVM

**Status:** **built — Band A (S0–S3) and Band B (S4–S7) are both on `feat/scale-100`, unmerged.**
Band A is measured ([`SCALE-100-AFTER.md`](../../conclusions/SCALE-100-AFTER.md)); Band B is
packaged-jar-verified but has not been re-run through the load rig. Owner: station (vision-api /
vision-app / adapter-persistence), with one small vision-web wave.
**Scope boundary:** this plan lifts the single-instance ceiling from *"one pilot, one manager, a small
crew"* to *"20–100 concurrent users and 10–30 concurrent streams on one JVM"* — **without**
rearchitecting anything. Every change here is local surgery or configuration. The moment the answer
requires a second instance, it belongs to
[DOMAIN-SEPARATION-PLAN.md](DOMAIN-SEPARATION-PLAN.md), not here (§8).
**Reads with:** [ARCHITECTURE.md](../../../ARCHITECTURE.md) ·
[MEDIA-SOT-PLAN.md](MEDIA-SOT-PLAN.md) (whose shipped M6/M7 proxy-publisher path is what makes S1's
bypass viable) · [DOMAIN-SEPARATION-PLAN.md](DOMAIN-SEPARATION-PLAN.md) (U2/U3, where the 50k answer
lives) · [POSTGRES-ONLY-CONTEXT.md](POSTGRES-ONLY-CONTEXT.md) (W1–W3, the in-progress move off the
devsupport in-memory repositories that S3 assumes lands first)

> **The one-line goal.** Take the JVM out of the video byte path, stop one thread from owning every
> live update, and give the database a real connection pool. Nothing else on this list matters until
> those three are done.

---

## 1. Why now, and what the ceiling actually is

The app today is a single JVM that is simultaneously the **media plane** (decodes, encodes and
proxies video), the **CV plane** (per-frame inference fan-out) and the **control plane** (REST, SSE,
persistence). That is the correct shape for one operator and it is the source of every item below.

Nothing here is a latent bug — each is a deliberate single-instance simplification, most of them
documented as such in the code (`LiveUpdateRegistry`'s own javadoc says *"per-process, single-instance
deployment, per the plan"*). This plan is the point at which several of those simplifications stop
paying for themselves.

---

## 2. Current state — measured against the code, not guessed

| # | Fact | Evidence |
|---|---|---|
| a | Every HLS **segment and playlist** for every viewer is fetched by the JVM, buffered whole in heap as `byte[]`, and re-emitted | `station/vision-api/.../proxy/HlsProxyController.java:107,198` |
| b | A **new `HttpClient` is built per proxied request** and never closed — each allocates a selector thread + connection pool, released only at GC | same file, `fetch()` line 192 |
| c | **One** thread (`live-update-dispatcher`) runs coalesce-flush, heartbeats and *every* SSE broadcast, with blocking `emitter.send()` per connection | `station/vision-api/.../live/LiveUpdateRegistry.java:276-282`, `LiveConnection.java:84` |
| d | `broadcast()` re-serializes the same envelope once **per connection** | `LiveUpdateRegistry.java:526-537` |
| e | `publishFleetChanged()` recomputes the **whole** fleet + devices snapshot (all assets, all devices, all streams) on every asset/device/stream write, then broadcasts to all | `LiveUpdateRegistry.java:400-410, 597-620` |
| f | `telemetryBuffers` / `detectionBuffers` are keyed per asset and **never evicted** — memory grows with assets-seen-since-boot, never shrinks | `LiveUpdateRegistry.java:217-218, 581-583` |
| g | ~~There is **no connection pool.**~~ **Corrected by measurement 2026-08-18** — `DriverManagerConnectionProvider` does pool, up to 20. Its real defect is that it **fails instead of waiting** when saturated: `HibernateException: The internal connection pool has reached its maximum size`, 1100 times in a 100-user sweep, surfaced as 500s. S3's win is the wait, not the pool | `PersistenceUnit.java:141-147`; [`SCALE-100-AFTER.md`](../../conclusions/SCALE-100-AFTER.md) §3.1 |
| g2 | That class's own javadoc claims HikariCP is *"already on the classpath transitively via Hibernate's own dependencies"* and that swapping it in is *"a config-only change"*. **Both are false** — `mvn dependency:list` on the module returns no pool library at all. S3 must add the dependency and correct the javadoc | `PersistenceUnit.java:39-41`, verified 2026-08-17 |
| g3 | Since POSTGRES-ONLY landed (`74eab93`), the devsupport in-memory repositories are **gone** — there is no `vision.persistence.enabled` fallback any more. Every request now reaches Postgres through the unpooled provider | `application.yaml:38` (past-tense comment), `git log fix/postgres-only-auth` |
| h | Every repository call opens its **own** `EntityManager` + transaction. A controller touching three repos = three connections, three transactions, no unit of work | `storage/persistence/.../config/JpaOperations.java:38,57` |
| i | Telemetry writes **two rows per sample**, each with its own `flush()`, on the ingest thread | `contexts/vision-perception/.../pipeline/UsageTracker.java:402-403`; `JpaTelemetryRepository.java:77-78` |
| j | `VisionApiProperties.HlsProxy` / `.Live` records already exist but the controller and registry still use **local constants** — a half-finished extraction | `VisionApiProperties.java:89,113` vs `HlsProxyController.java:90-91`, `LiveUpdateRegistry.java:170-200` |
| k | No rate limiting anywhere on `/api/**` | repo-wide search: only `ChannelMap` (RC) and the manual-control WS handler throttle anything |

### 2.1 What is *not* as bad as it looks

Worth stating because it changes the ranking: **the SPA's polling is mostly already SSE-gated.**
`FleetStore` (`fleet-store.ts:137`), `EventsStore` (`events-store.ts:98`), `TelemetryStore` and
`DetectionsStore` (via `live-fallback-logic.ts#resolveAssetScopedTransport`) all pause their polls
when the live connection is open and resume on drop. Four pollers are **not** gated —
`cockpit-facade.ts:490` (5s, `listAssets()` + `loadAsset()`), `drone-picker-facade.ts:58` (5s),
`cv-control-panel.ts:455` (2s tracks), `geofence-store.ts:54` (30s) — which is roughly **1 req/s per
open cockpit tab**, not the 3–5 a first read of the constants suggests. That makes S6 a cheap
cleanup, not a headline fix.

### 2.2 The other thing that is not a bottleneck yet

Per-stream in-JVM decode + H.264 encode costs roughly **one core per stream**. That scales with
*assets*, not *users*, and `MEDIA-SOT-PLAN.md`'s shipped source-proxy path
(`vision.publish.source-proxy.enabled`) already has the exit: mediamtx dials the camera and the JVM
never touches a frame. Turning that on is an operational decision, not work this plan owns.

---

## 3. Ranked by cost/benefit — this is the costing

Estimates are focused implementation time for one delegated agent, excluding review. "Headroom" is
the ceiling *after* the fix, holding everything else constant.

> **Band A (S0–S3) is built and measured.** The headroom column below was an estimate; what it
> actually bought is in [`SCALE-100-AFTER.md`](../../conclusions/SCALE-100-AFTER.md) — at 100 users,
> 307 failed REST requests → 0, threads 376 → 143, HLS p99 −35%, SSE lag p99 −22%. The ranking held:
> S3 first, S1 second, S2 smallest.
>
> **Band B (S4–S7) is built but its headroom column is still an estimate** — it has not been re-run
> through the S0 rig. Two of its numbers are claims, not measurements: S4's "800+ samples/s" and S6's
> "~0.2 req/s per idle tab" (the latter needs a browser, not the rig). Do not quote either as
> measured. S6's rate limit additionally ships **disabled** — see §5.6.

| Rank | Wave | Fixes | Effort | Headroom gained | Risk |
|---|---|---|---|---|---|
| 1 | **S3** Connection pool | 2g, 2g2, 2g3, 2h | **S — ~4 h** | DB stops being the first thing to fall over under concurrency | Low |
| 2 | **S1** Video out of the JVM | 2a, 2b | **M — ~6 h** | ~15 → 100+ concurrent viewers | Low—Medium (cookie scoping, §5.1) |
| 3 | **S2** SSE dispatcher | 2c, 2d, 2f | **M — ~6 h** | one slow client stops being everyone's problem; ~50 → 500+ connections | Low |
| 4 | **S4** Telemetry write path | 2i | **M — ~5 h** | ~100 → 800+ samples/s ingest | Medium (batching changes durability window) |
| 5 | **S5** Fleet snapshot recompute | 2e | **S — ~3 h** | write throughput stops degrading with asset count | Low |
| 6 | **S6** Ungated polls + rate limit | 2k, §2.1 | **S — ~3 h** | ~1 → ~0.2 req/s per idle tab; abuse ceiling exists | Low |
| 7 | **S7** Finish the properties extraction | 2j | **XS — ~2 h** | none directly; makes every knob above tunable per deployment | None |
| — | **S0** Load rig | prerequisite | **M — ~5 h** | none directly; without it every number above is a guess | None |

**Total: ~34 h.** Sequencing note: **S0 first**, then S1/S2/S3 in parallel (disjoint scopes), then
S4/S5/S6/S7 in parallel. Two agents working the parallel bands finish in roughly three working days.

If the budget only stretches to half of this: **S0 + S1 + S2 + S3** (~21 h) is the coherent subset —
it lifts every one of the three plane-level ceilings and leaves the rest as tuning.

**Where each wave landed** on `feat/scale-100` (branched from `fix/postgres-only-auth`, so one merge
delivers both):

| Wave | Commit(s) |
|---|---|
| S0 load rig | `4b13be6` |
| S1 video out of the JVM | `73d3cc6`, fixed by `6e640d3` (cookie relay broke plain-http HLS) |
| S2 SSE dispatcher | `02cb7d8` |
| S3 connection pool | `5060686`, `cb99829` |
| Band A measurement | `2cabd00` → [`SCALE-100-AFTER.md`](../../conclusions/SCALE-100-AFTER.md) |
| S4 telemetry write path | `d8cc79f`, wired by `fd0b823` |
| S5 fleet recompute | `9424064` |
| S6a ungated polls | `a842d00` |
| S6b rate limit | `0d07c91` (ships disabled — §5 S6) |
| S7 properties extraction | `9d9bb4f` |

Also on the branch and unrelated to this plan: `3756a0a` (test-scoped `flyway-core` made the packaged
jar unbootable) — found only because the band check boots the jar, never the reactor classpath.

---

## 4. Decisions (pinned)

1. **One instance stays one instance.** No shared session store, no external bus, no stream-ownership
   routing. Those are DOMAIN-SEPARATION's, and adopting any of them here would make this plan a
   rewrite instead of a tuning pass.
2. **Virtual threads on** (`spring.threads.virtual.enabled=true`) — Boot 4 / Java 21, and the request
   path is blocking by construction. This is a config line, folded into S1's acceptance rather than
   its own wave.
3. **HikariCP**, not Agroal — the smaller dependency surface, and `hibernate-hikaricp` is the
   supported wiring for a raw `Configuration`.
4. **No behavior change is acceptable as a side effect.** Every wave's acceptance criteria include
   the existing test suite green with no assertion edits.
5. **Every new tunable is a property**, defaulted to today's effective value (CLAUDE.md rule 1). No
   wave may introduce a literal in a hot path.

---

## 5. Waves

### S0 — The load rig · *gates the "after" number of every other wave*

**Scope:** `docs/conclusions/SCALE-100-BASELINE.md` (new), plus a throwaway harness under
`/tmp` — **no product code**.
**Owner:** claude (measurement, not implementation).

Build a rig that drives a running compose stack at a stated concurrency: N synthetic SSE clients on
`/api/live`, M HLS viewers pulling segments, K simulated telemetry sources. Record, at 5 / 20 / 50 /
100 users: p50 and p99 REST latency, SSE envelope lag, JVM heap and GC pause, thread count, DB
connection count, CPU.

**Acceptance:** a committed baseline table. Every later wave restates its own before/after row
against this rig. *Without this, §3's headroom column is an estimate and stays one.*

---

### S1 — Video leaves the JVM byte path

**Scope:** `station/vision-api/src/main/java/com/drones/vision/api/proxy/HlsProxyController.java`,
its test, `station/vision-app/src/main/resources/application.yaml`.
**Owner:** spring-integrator.
**Effort:** ~6 h.

Three changes, in order of value:

1. **Stream instead of buffer.** `ResponseEntity<byte[]>` + `BodyHandlers.ofByteArray()` becomes
   `StreamingResponseBody` + `BodyHandlers.ofInputStream()`. A 2 MB segment stops being a 2 MB heap
   allocation per concurrent viewer.
2. **One shared `HttpClient`, built once as a bean.** ⚠️ **The constraint that makes this
   non-trivial:** the per-request client exists *because* of the per-request `CookieManager`
   (`fetch()` line 189) — mediamtx issues per-session HLS cookies, and a shared `CookieHandler` would
   leak one viewer's session cookie to another. The fix is a shared client with **no** `cookieHandler`
   at all, forwarding the browser's `Cookie` header explicitly on the request and collecting
   `Set-Cookie` from the response — which the controller already does in `collectSetCookies`. Redirect
   hops must then carry the header manually, or `Redirect.NEVER` + one explicit hop. **A shared client
   with a shared cookie handler is a cross-viewer session leak — do not ship it.**
3. **Forward `Range`.** Byte-range requests currently aren't passed through; harmless for live HLS,
   wrong for the recording playback path.
4. Set `spring.threads.virtual.enabled=true` and pin an explicit `server.tomcat.max-connections`.

**Acceptance:** S0 rig at 100 HLS viewers — heap allocation rate per viewer falls by >10×; JVM thread
count stops growing with request count; playback is byte-identical (compare segment hashes through the
proxy vs. direct from mediamtx); no cookie from viewer A ever appears on viewer B's upstream request
(explicit test).

**Note:** the strictly better answer is *no proxy at all* — nginx in front, or handing browsers
mediamtx's own address. That is deliberately **not** this wave: `/hls/**` exists so the mediamtx port
stays an implementation detail and the browser only ever talks to one origin
(`application.yaml`'s own `view-base` comment). Revisit it when a reverse proxy enters the deployment,
which is a DOMAIN-SEPARATION-era decision.

---

### S2 — The SSE dispatcher stops being one thread

**Scope:** `station/vision-api/src/main/java/com/drones/vision/api/live/LiveUpdateRegistry.java`,
`LiveConnection.java`, their tests.
**Owner:** spring-integrator.
**Effort:** ~6 h.

1. **Serialize once, write N times.** `broadcast()` currently hands the object to each emitter, so
   Jackson runs per connection. Serialize the envelope to a `String`/byte array once, send that.
   O(connections) JSON becomes O(1) JSON + O(connections) writes.
2. **Split the executor.** Keep the single-threaded scheduler for `flushPending`/`heartbeatAll`
   ordering (the `seq` sequence depends on it), but dispatch the actual per-connection writes onto a
   small bounded pool or a virtual-thread executor, so a blocked `send()` on one connection cannot
   stall the flush loop for every other viewer.
3. **Evict per-asset buffers.** `telemetryBuffers`/`detectionBuffers` need a removal path on stream
   stop — or at minimum a bounded LRU. Today they are a slow leak keyed by asset.
4. **Bound the per-connection write.** A connection whose write blocks past a timeout gets
   unregistered rather than held.

**Acceptance:** S0 rig at 500 SSE connections with one deliberately-stalled client (TCP window pinned
at zero) — the other 499 keep receiving envelopes within their normal coalesce window. `Last-Event-ID`
resume still works across every topic. Heap after 1000 stream start/stop cycles returns to baseline.

---

### S3 — Give the database a connection pool

**Scope:** `storage/persistence/pom.xml`,
`storage/persistence/src/main/java/com/drones/vision/adapter/persistence/config/PersistenceUnit.java`,
`.../config/JpaOperations.java`, `storage/persistence/MODULE.md`,
`station/vision-app/src/main/resources/application.yaml`,
`station/vision-app/.../config/properties/VisionPersistenceProperties.java`.
**Owner:** spring-integrator.
**Effort:** ~4 h.
**Depends on:** POSTGRES-ONLY — **landed** (`74eab93`, branch `fix/postgres-only-auth`). With the
in-memory fallback deleted, every request now reaches Postgres through the unpooled provider, which
is why this wave is ranked first rather than third.

1. Add `org.hibernate.orm:hibernate-hikaricp` — it is **not** transitively present despite
   `PersistenceUnit.java:39`'s claim (§2 g2); correct that javadoc in the same change. Configure `hibernate.connection.provider_class` plus
   `hibernate.hikari.maximumPoolSize` / `minimumIdle` / `connectionTimeout` / `leakDetectionThreshold`,
   every one of them read from `VisionPersistenceProperties` with a documented default (CLAUDE.md
   rule 1) — **not** hardcoded in `PersistenceUnit`.
2. Reuse Flyway's `DataSource` for Hibernate instead of handing Hibernate a bare JDBC URL, so
   migration and runtime share one pool.
3. Leave `JpaOperations`' per-call `EntityManager` **as is** for now, but document the cost in
   `MODULE.md`. A request-scoped unit of work is a genuine refactor across 19 repositories and belongs
   in its own wave — this one only removes the pool starvation.

**Acceptance:** the "not for production use" warning is gone from startup logs; S0 rig at 100 users
shows a bounded, observable connection count under load instead of unbounded churn; the
Testcontainers integration test suite passes unchanged.

---

### S4 — Telemetry write path

**Scope:** `storage/persistence/.../repository/JpaTelemetryRepository.java`,
`contexts/vision-perception/.../pipeline/UsageTracker.java`, tests, both `MODULE.md`s.
**Owner:** application-service (UsageTracker) + spring-integrator (repository) — **two agents, split
at the port boundary**.
**Effort:** ~5 h.

1. Drop the per-`save` `em.flush()` where the retention delete doesn't require it (the javadoc at
   `JpaTelemetryRepository.java:36` explains exactly when it does — honor that case, batch the rest).
2. Batch samples: buffer per usage and flush on a size **or** time bound, both properties. This is the
   one wave with a real trade-off — a crash loses up to one batch window of telemetry. **Default the
   window small (≤250 ms) so the loss is bounded well inside CLAUDE.md rule 9's "newest data wins"
   intent**, and make it a documented property so a deployment that wants zero loss sets it to 0.
3. `UsageTracker:402-403` does two writes per sample; the `usageRepository.save(updated)` half is a
   counter update that does not need per-sample durability. Coalesce it onto the same batch boundary.

**Acceptance:** S0 rig sustains 800 samples/s with p99 ingest latency inside the existing budget;
telemetry loss on a `kill -9` is bounded by the configured window and is covered by a test.

> **Built; the loss-window half is tested, the 800 samples/s half is not measured.** Both the size
> bound and the time bound *evict* their buffer (`compute` returning `null`), so the pending map does
> not grow one entry per usage forever — a regression test in `PostgresDockerIntegrationTest` pins
> both paths, because the first implementation leaked on the window path. `UsageTracker`'s coalesced
> summary write persists `tracking.usage`, the freshest fold, rather than the calling thread's own
> `updated` snapshot: two subscription threads can reach the size bound out of order, and CLAUDE.md
> rule 9 says the newest wins.

---

### S5 — Stop recomputing the world on every write

**Scope:** `station/vision-api/.../live/LiveUpdateRegistry.java` (the `freshFleetEnvelope` /
`freshDevicesEnvelope` path only — **coordinate with S2, same file**).
**Owner:** spring-integrator, **sequenced after S2** to keep the file scope disjoint in time.
**Effort:** ~3 h.

`publishFleetChanged()` fires on every asset/device/stream lifecycle event and rebuilds the entire
fleet and devices snapshot. Debounce it on the dispatcher (coalesce all calls within one
`COALESCE_MILLIS` window into a single recompute) — the same treatment telemetry already gets, and it
composes with S2's serialize-once change for free.

**Acceptance:** 50 rapid asset writes produce ≤ 2 recomputes; the SPA still converges to the correct
fleet state.

> **Built** (`9424064`), with the acceptance covered by test rather than by rig.

---

### S6 — Close the ungated polls, add a rate limit

**Scope (frontend):** `station/vision-web/src/app/features/fly/cockpit-facade.ts`,
`features/fly/drone-picker-facade.ts`, `features/fly/cv-control-panel.ts`,
`core/geofence/geofence-store.ts`.
**Scope (backend):** one filter in `station/vision-api`, wired in `vision-app`.
**Owner:** web-ui (frontend) + spring-integrator (filter) — **two agents, no file overlap**.
**Effort:** ~3 h.

1. Gate the four remaining pollers on `isLiveAvailable()` exactly as `FleetStore` already does
   (`fleet-store.ts:162` is the pattern to copy — pause on open, resume-and-refetch on drop). The
   `fleet` topic already carries what `cockpit-facade`'s `listAssets()` poll re-fetches.
2. `cv-control-panel`'s 2s tracks poll has **no** SSE topic behind it — either leave it (documented,
   it is panel-scoped and short-lived) or fold tracks into the `detections:<assetId>` payload. Prefer
   leaving it; note the decision in the panel's javadoc.
3. Add a simple per-principal token-bucket filter on `/api/**` with a generous default. This is not
   security hardening, it is a blast-radius bound: today one misbehaving tab can saturate the app.

**Acceptance:** an idle cockpit tab with SSE open issues ≤ 0.2 req/s; the bucket's limit is a property
and a test proves 429 on breach.

> **Built, with one decision this section did not anticipate: the limiter ships disabled.**
> `RateLimitFilter` keys per principal, so when `vision.auth.enabled=false` — the default this app
> runs in today — every caller in the deployment collapses into a single shared bucket. A budget that
> is generous per user becomes an outage at this plan's own 100-user target. Enabling it is therefore
> an *auth-enabled* decision, not a tuning decision, and `RateLimitWiring` is
> `@ConditionalOnProperty(havingValue = "true")` so nothing registers until someone says so.
> Verified in the packaged jar: off → 80 rapid `/api/assets` calls all 200; on at 10/min → exactly
> 10×200 then 429 with `{"error":"TOO_MANY_REQUESTS"}`, `/api/live` exempt across 15 calls.
>
> The **≤ 0.2 req/s half of this acceptance is unverified** — it needs a browser with an open cockpit
> tab, not the S0 rig. The four pollers are gated in code; the resulting rate is reasoned, not counted.

---

### S7 — Finish the properties extraction

**Scope:** `station/vision-api/.../proxy/HlsProxyController.java` (constants → `VisionApiProperties.HlsProxy`),
`station/vision-api/.../live/LiveUpdateRegistry.java` (constants → `VisionApiProperties.Live`),
`station/vision-app/.../config/properties/`, `application.yaml`.
**Owner:** spring-integrator, **after S1 + S2 + S5** (all three touch those files).
**Effort:** ~2 h.

`VisionApiProperties.HlsProxy` and `.Live` already exist with exactly the right shape and exactly
today's values (`VisionApiProperties.java:89,113`); the controller and registry just never got rewired
to them (`LAYERING-REFACTOR-PLAN.md` §7 row B deferred it). Finish it, and add the new S1–S5 knobs to
the same records so a deployment tunes coalesce window, buffer sizes, dispatcher pool size, HLS
timeouts and pool sizing from `application.yaml` alone.

**Acceptance:** no literal timing/sizing constant remains in either class; `application.yaml`
documents each new key in the file's established commented-default style.

> **Built.** The sweep found two literals this scope did not list — the HLS proxy's error-body preview
> length and its redirect-hop cap — and both became keys. `marks-buffer` was renamed `map-buffer` to
> match the vocabulary [MAP-REWORK-PLAN.md](../done/MAP-REWORK-PLAN.md) left behind. See §6 for the
> one key deliberately *not* added.

---

## 6. New configuration keys (all defaulted to today's effective behavior)

```
vision.api.hls-proxy.connect-timeout / request-timeout   # already in the record; wired up (S7)
vision.api.hls-proxy.error-body-preview-max-chars
        / max-redirect-hops                              # S7, not foreseen here — literals found in S1's proxy
vision.api.live.coalesce / heartbeat / *-buffer          # already in the record; wired up (S7)
vision.api.live.map-buffer                               # S7, renamed from marks-buffer (map-rework naming)
vision.api.live.send-timeout / buffer-eviction           # S2 / S7
vision.api.rate-limit.enabled / permits-per-minute       # S6, default off — and stays off, see below
vision.persistence.pool.max-size / min-idle
        / connection-timeout / leak-detection-threshold  # S3
vision.persistence.telemetry.batch-size / batch-window   # S4, default 100 / 200ms — NOT today's behavior
spring.threads.virtual.enabled                           # S1
```

**Two deviations from this section's original promise, both deliberate:**

- **`vision.api.live.dispatch-threads` was never added.** S2 dispatches on
  `newVirtualThreadPerTaskExecutor()`, which has no thread-count concept — the key would have been
  bound, stored, and silently ignored. A knob that reads as tuning but does nothing is worse than its
  absence.
- **`vision.persistence.telemetry.batch-window` defaults to 200 ms, not 0.** So S4 does *not* land
  dark: batching is on out of the box, and a crash loses up to 200 ms of telemetry. That is the wave's
  whole point, and shipping it off would have meant shipping nothing. A deployment that wants the old
  per-sample durability sets `batch-window: 0` (`TelemetryBatchSettings.isImmediate()` is the path).
  Every other key above does default to current behavior.

---

## 7. Instruments — what would falsify each claim

| Claim | Instrument |
|---|---|
| "HLS proxy is the first wall" | S0 rig, viewers only, no SSE — watch allocation rate and thread count |
| "One dispatcher thread is the second" | S0 rig with one TCP-stalled SSE client; measure lag for the others |
| "No pool starves the DB" | connection count + p99 under 100 concurrent REST callers with persistence on |
| "Telemetry writes cap ingest" | sample rate ramp until p99 write latency knees |
| "Polling is ~1 req/s/tab, not 3–5" | one idle cockpit tab, SSE open, count requests over 60 s |

The last row is there because it is the one number in §2 that was **corrected during analysis** rather
than measured — treat it as the least trustworthy figure in this document until S0 runs.

---

## 8. Deliberately **not** in this plan

Everything required for a second instance, and everything required for 50,000 users. Named here so
nobody folds one into a wave above:

- **Shared session store / JWT.** Auth is `HttpSession` (`AuthWiringConfiguration`), so two instances
  need sticky sessions today. → DOMAIN-SEPARATION.
- **Live fan-out over a bus.** `LiveUpdateRegistry` is per-process by design and its `seq` is an
  in-process `AtomicLong`, so `Last-Event-ID` resume cannot survive an instance switch. → NATS
  JetStream, DOMAIN-SEPARATION U2/U3.
- **Stream ownership + routing.** `StreamPipeline` is pinned to a JVM with nothing routing a client to
  the owner. → asset-unit leases, DOMAIN-SEPARATION.
- **Telemetry into a time-series store.** Row-per-sample via JPA is fine at 100 users; it is not the
  50k answer.
- **Request-scoped unit of work.** Real, worth doing, ~19 repositories wide. Its own plan.
- **Turning on `vision.publish.source-proxy.enabled`.** Already built (MEDIA-SOT M6/M7); an operational
  decision, not a wave.

**One framing question this plan does not answer, and the 50k plan must:** 50,000 *users* watching 100
drones is a fan-out problem (media + SSE split). 50,000 *drones* is an ingest problem (sharded pipeline
plane). They need different architectures and they currently bottleneck in the same JVM.

---

## 9. Risks

| Risk | Where | Mitigation |
|---|---|---|
| Shared `HttpClient` leaks HLS session cookies across viewers | S1 | Explicit no-`cookieHandler` design + a cross-viewer isolation test; called out in §5.1 |
| Batching telemetry loses samples on crash | S4 | Bounded window, default small, property-configurable to 0; documented in `MODULE.md` |
| Multi-threaded dispatch reorders envelopes within a topic | S2 | Keep sequencing on the single scheduler thread; only the per-connection **write** moves off it |
| S2/S5/S7 all touch `LiveUpdateRegistry.java` | waves | Sequenced, not parallel — S2 → S5 → S7, one agent at a time (CLAUDE.md delegation rule: disjoint file scopes) |
| S3 lands before POSTGRES-ONLY completes and has nothing to pool | S3 | Gated on that plan's W1–W3; harmless if it lands early (`enabled=false` skips the whole path) |
