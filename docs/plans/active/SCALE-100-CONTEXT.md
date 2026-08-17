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

| Wave | State |
|---|---|
| S0 | in flight (restarted — see §6) |
| S1 | **merged** `73d3cc6` — 592/592 green, verified independently by the orchestrator |
| S2 | in flight (restarted — see §6) |
| S3 | **merged** `5060686` + wiring `cb99829` — 148 persistence / 190 app tests green |
| S4–S7 | not started (Band B) |

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

The plan pinned `spring.threads.virtual.enabled=true` as decision 2. That is **wrong for this
codebase today** and `application.yaml` now records why.

This project targets **Java 21**, where a virtual thread blocking inside a `synchronized` block pins
its carrier — JEP 491 removes that only in Java 24. `LiveConnection` wraps every blocking
`SseEmitter#send` in `synchronized (sendLock)`, and `LiveUpdateRegistry#connect` runs its whole
snapshot burst on the **request** thread. Enabling virtual threads globally would let a handful of
concurrent `/api/live` connects pin every carrier in the scheduler pool and stall unrelated
requests — the precise opposite of this plan's purpose.

Revisit after S2 moves those sends off the request thread, or after a move to Java 24+, and only
with an S0 measurement rather than on reasoning alone.
