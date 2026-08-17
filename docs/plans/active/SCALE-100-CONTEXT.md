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
| S0 | dispatched |
| S1 | dispatched |
| S2 | dispatched |
| S3 | dispatched |
| S4–S7 | not started (Band B) |
