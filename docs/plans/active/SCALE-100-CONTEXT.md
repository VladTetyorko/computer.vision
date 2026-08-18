# SCALE-100 — implementation context

Working context for executing [SCALE-100-PLAN.md](SCALE-100-PLAN.md). Written before any code, per
CLAUDE.md's "create a file with a context, then start working on it".

**Branch:** `feat/scale-100`, cut from `fix/postgres-only-auth` (**not** master).
**Why not master:** `fix/postgres-only-auth` is 6 commits ahead / 0 behind master and carries
POSTGRES-ONLY (`74eab93`), which deleted the devsupport in-memory repositories. S3 (connection pool)
is only meaningful on top of it, and S1/S2 are unaffected either way. This branch merges to master
behind `fix/postgres-only-auth`, not in front of it.

**Band A (in flight):** S0, S1, S2, S3 — four Sonnet agents, isolated git worktrees, disjoint scopes.
**Band B (after A merges):** S4, S5, S6, S7.

---

## 1. The disjointness contract

Each agent owns its listed paths **exclusively** and may not edit anything outside them. Overlaps
were designed out rather than coordinated at merge time.

| Wave | Owns | Module build |
|---|---|---|
| **S0** | `docs/conclusions/SCALE-100-BASELINE.md`, `tools/loadrig/**` (new) | none (no product code) |
| **S1** | `station/vision-api/src/**/api/proxy/**`, its tests | `-pl station/vision-api` |
| **S2** | `station/vision-api/src/**/api/live/**`, its tests | `-pl station/vision-api` |
| **S3** | `storage/persistence/**` (incl. `pom.xml`, `MODULE.md`) | `-pl storage/persistence` |

### Reserved — nobody in Band A touches these

- **`station/vision-app/src/main/resources/application.yaml`** — three waves want keys in it, so the
  orchestrator (Opus) applies every key at integration time. Agents state the keys they need in their
  final report instead of editing the file.
- **`station/vision-api/.../support/VisionApiProperties.java`** — S1 and S2 would both edit it. It is
  S7's job (§5 S7 of the plan); Band A agents keep their new tunables as named constants in their own
  class and list them for S7.
- **`station/vision-app/.../config/wiring/**`** — orchestrator-only, for the same reason.

S1 and S2 both live in `station/vision-api`, hence the worktree isolation: two concurrent
`mvn -pl station/vision-api test` runs in one checkout collide on `target/`.

---

## 2. Standing rules for every agent

1. **Scoped builds only** — `./mvnw -B -pl <module> test`. Never a reactor-wide `verify` while a
   sibling agent may hold another module red (CLAUDE.md).
2. **No behavior change as a side effect.** The existing suite must pass with **no assertion edited**.
   If a test must change, stop and report rather than editing it.
3. **Update the module's `MODULE.md`** in the same task (CLAUDE.md mandatory workflow).
4. **No magic numbers.** Every new timing/sizing value is a named constant with a javadoc saying why,
   ready for S7 to lift into a properties record.
5. **Javadoc says WHY, not what.** Short. No markdown in Java comments.
6. Do not commit, do not create branches, do not merge — the orchestrator integrates.

---

## 3. Known traps, per wave

- **S1 — the cookie trap.** The per-request `HttpClient` exists *because* of the per-request
  `CookieManager` (`HlsProxyController#fetch`). mediamtx issues per-session HLS cookies; a shared
  `CookieHandler` would hand viewer A's session to viewer B. The shared client must carry **no**
  cookie handler, forwarding the `Cookie` header explicitly per request. This is a security
  regression if done naively.
- **S2 — ordering.** `seq` must stay monotonic per topic and `Last-Event-ID` resume must keep working.
  Sequencing stays on the single scheduler thread; only the per-connection *write* moves off it.
- **S3 — the stale javadoc.** `PersistenceUnit.java:39` claims HikariCP is already on the classpath
  transitively and that swapping providers is "config-only". Verified false on 2026-08-17 —
  `dependency:list` shows no pool library. The dependency must be added and that paragraph corrected.
- **S0 — honesty.** If the compose stack cannot be brought up in this environment, report that
  plainly and commit the rig plus an empty baseline table. Do not estimate numbers and present them
  as measurements.

---

## 4. Integration order

`S3 → S1 → S2` into `feat/scale-100` (smallest diff first, and S3 is the highest-value wave). S0's
docs merge whenever they land. Then the orchestrator applies the reserved-file changes
(`application.yaml`, wiring) and runs one full `./mvnw -B verify` before Band B starts.

---

## 5. Status

**Band A is complete.** All four waves merged, every one verified by the orchestrator re-running its
build rather than trusting the agent's report.

| Wave | State |
|---|---|
| S0 | **merged** `4b13be6` — rig + real measured baseline at `a70c107` |
| S1 | **merged** `73d3cc6` — HLS proxy streams; cookie-isolation gate passed |
| S2 | **merged** `02cb7d8` — dispatch split; 598/598 green with S1 |
| S3 | **merged** `5060686` + wiring `cb99829` — 148 persistence / 190 app tests green |
| S4–S7 | not started (Band B) |

### What the baseline actually showed (`docs/conclusions/SCALE-100-BASELINE.md`)

Measured at `a70c107`, i.e. **before** S1/S2/S3:

| Load | Threads | Heap | REST p99 | HLS p99 |
|---|---|---|---|---|
| idle | 138 | — | — | — |
| 100 HLS viewers only | **908** | 901 MB | 632 ms | 2326 ms |
| 100 combined | — | — | 424 ms | 3453 ms |

The 138 → 908 thread jump is the unclosed per-request `HttpClient` seen from outside the JVM — it
ranked S1 correctly, and independently of the reasoning that ranked it. Thread count fell back only
to 232 a minute after load stopped.

The document is careful about what it does **not** establish: REST p99 growing 60× is *consistent
with* the missing pool but this rig cannot separate that from GC and Tomcat thread-pool effects. Two
items are marked not-measured with reasons (a full K=100 sim sweep, and the browser idle-tab polling
spot-check that §7 of the plan flagged as its weakest claim — still unverified).

### The after-sweep — run 2026-08-18

Results: [`docs/conclusions/SCALE-100-AFTER.md`](../../conclusions/SCALE-100-AFTER.md). Band A's
headroom claims are no longer estimates.

It was **not** run as a diff against the baseline above. That baseline came from an IntelliJ dev JVM
(`TieredStopAtLevel=1` + debugger agent), so a packaged-jar "after" would have credited S1/S2/S3 with
the C2 compiler. The baseline commit was rebuilt into its own jar and re-measured under identical
conditions instead — jar vs jar, same script, same ambient load.

Three things a re-runner needs to know:

1. **Detection must be forced on, or the SSE-lag column is blank.** The rig times only payloads
   carrying `at`, i.e. DETECTION events, and detection is opt-in *and* demand-gated (CV-DEMAND §3.5):
   an SSE subscription alone creates no demand. A sidecar that PATCHes `detectionEnabled:true` and
   polls `/detections` ran alongside both sides.
2. **The baseline's 908 threads did not reproduce** (314 before / 168 after under a packaged JVM).
   Direction and mechanism held; the magnitude was dev-JVM-specific. Do not quote "908 → 168".
3. **`pkill -f <jar-name>` matches the driver script's own argv** and makes it kill itself. Cost two
   silent no-op runs here. Kill by pid from `ss -ltnp` instead.

### Two shipping bugs the sweep found (both now fixed on this branch)

Neither is a scaling defect; both were invisible until someone ran the packaged jar over plain http.

- **`3756a0a`** — `flyway-core` was declared at `test` scope in `vision-app`, which by Maven's
  nearest-definition rule demoted the transitive compile dependency too and dropped it from the
  repackaged jar. `java -jar` (i.e. `Dockerfile`) died at boot with `NoClassDefFoundError`.
  Introduced by `74eab93`; passed every test, because tests have test scope.
- **`6e640d3`** — S1's `Set-Cookie` relay broke HLS over plain http. mediamtx sends each session
  cookie twice, bare and `Secure`-hardened; the hardened copy replaces the usable one, so an http
  viewer can never return `hlsSession` and every media playlist 401s. 2592/2592 HLS requests failed
  in the first after-run. Hidden in dev because browsers treat `http://localhost` as secure.

**Lesson for Band B: run the packaged jar over a non-localhost origin at least once per band.** Both
bugs were shipped-and-green under the reactor classpath and `http://localhost`.

### Orchestrator changes on top of the agents' work

- **S3:** dropped the `hibernate-hikaricp` dependency the agent added. It supplies only
  `HikariCPConnectionProvider`, which the chosen design deliberately does not use (it would build a
  second, unshared pool), so nothing would ever have loaded it. `com.zaxxer:HikariCP` alone is the
  real dependency. Rebuilt green without it.
- **Plan §4 decision 2 reversed — `spring.threads.virtual.enabled` stays OFF.** See §7.

---

## 6. Incident: stale worktree bases

Two of the four Band A worktrees were created at `331a6a7` — **28 commits behind**, predating the
module regroup, so `station/vision-api/` and `storage/persistence/` did not exist in them at all.
S1 detected this itself and reset; S3 was unaffected. **S2 and S0 were both affected** and were
redirected to `git reset --hard feat/scale-100`.

Cost was zero only by luck: S2 had written nothing yet, and S0 had only untracked files (which
survive a hard reset). **Check `git rev-parse HEAD` in every agent worktree before trusting its
output** — a stale base is silent, and an agent that finds none of its target paths will improvise
rather than stop.

---

## 7. Reversal: virtual threads stay off

The plan pinned `spring.threads.virtual.enabled=true` as decision 2. It stays **off**, and
`application.yaml` records why.

This project targets **Java 21**, where a virtual thread blocking inside a `synchronized` block pins
its carrier — JEP 491 removes that only in Java 24. Blocking I/O under `synchronized` is therefore a
per-site hazard to be surveyed, not assumed away.

**Amended after S2 landed.** The original rationale named `LiveConnection`'s `synchronized (sendLock)`
around a blocking `SseEmitter#send` as the blocker. S2 fixed exactly that — `sendLock` is now a
`ReentrantLock`, which parks instead of pinning, and it was changed for this very reason. So the
specific objection no longer holds and the note has been corrected rather than left standing.

The decision is unchanged because **other sites remain, unaudited**:
- `ManualControlWebSocketHandler:292` — `synchronized (state.sendLock)` around a blocking WebSocket send.
- `MediamtxStreamPublisher` — called directly by a stream start/stop request thread.
- (`LiveRingBuffer`'s `synchronized` methods are pure in-memory work — not a concern.)

What changed is the *kind* of open question: it was an argued one, and it is now a measurable one.
The rig and a pre-change baseline both exist. Survey those sites, then flip it and re-run
`tools/loadrig` — do not flip it on reasoning alone.
