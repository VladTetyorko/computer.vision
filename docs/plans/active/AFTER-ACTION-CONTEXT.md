# AFTER-ACTION-CONTEXT — what exists before C13 starts

Gathered 2026-08-19 against `master` at `554fc105` (immediately after `feat/fixed-camera-geo`
merged). Companion to [AFTER-ACTION-PLAN.md](AFTER-ACTION-PLAN.md), which is the authoritative
spec. This file records **what was verified to exist**, so the plan does not have to re-argue it
and no wave has to rediscover it.

Row **C13** of `docs/main/MASTER-MATRIX.md` §5: *"After-action evidence package
(flight+video+detections+marks+params) — export over existing aggregates"*, S–M ~40 h, depends on
recording (HAVE) and I7 vehicle passport (HAVE, merged 2026-08-19).

---

## 1. Why this row and not another

Chosen with K4 as the two rows that are **both cheap and unblocked**. Three others that looked
cheaper were checked first and are not:

| Row | Listed | Actually |
|---|---|---|
| P11 operator manual fix | S 16 h | **blocked** — `PositionFix`/`FixOrigin` exist only on the parked `feat/visual-geo` branch, at paths (`vision-domain/`, `vision-application/`) the module reorg deleted |
| A2 origins on the wire | S 12 h | **blocked** — same host classes, same branch |
| B1 ADS-B overlay | S–M 40 h | behind the K1–K4 feature freeze, and needs a third-party feed |

The matrix marks A3 (*merge `feat/visual-geo`*) as the prerequisite for P6–P9, C7a, C11 and M5 —
it never marked P11 and A2 as dependent on it, which is a matrix error worth fixing when this
cycle closes.

## 2. Every ingredient, verified present

| Ingredient | Where | Reached by |
|---|---|---|
| Flight session | `AssetUsage` in `vision-warehouse` | `GET /api/usages` |
| Telemetry + detections | `ReplayService#timeline` → `UsageTimeline` in `vision-events` | `GET /api/usages/{usageId}/timeline` |
| Video | `ReplayService#recordingFor` → `UsageRecording(url, start, durationSeconds)` | `GET /api/usages/{usageId}/recording` |
| Params | `FlightPassport` in `vision-flight` (I7, wave O11–O13) | `GET /api/assets/{assetId}/usages/{usageId}/passport` |
| Config drift | same | `GET /api/assets/{assetId}/usages/{usageId}/drift` |
| Marks | `MarkService#list(Viewer)` in `vision-map` | `GET /api/map/marks` |
| Audit trail | `AuditController` | `GET /api/audit` |

Nothing in this list needs building. C13 is an assembly, which is why it is 40 h and not 200.

## 3. The four constraints that shape the design

**3.1 `vision-events` is a sink and must stay one.** Its `MODULE.md` is explicit: it may read
`flight`, `perception` and `warehouse`, and *nothing may read it back except `vision-learning`*.
W1.6b measured that a cross-cutting seam living in this module caused **four of the module graph's
seven cycles**, and paid all four off. Verified against the poms: `events → {flight, perception,
warehouse}`, and `map → {identity, perception}` — so `events` cannot see marks today.

Adding `events → map` would be acyclic, but it would put a *presentation* concern (an evidence
bundle for a referee) inside a domain module, against the one warning that module's doc actually
gives. **The package is assembled in `vision-api`**, which already sees every context and already
has precedent for cross-context orchestration in `api/support/RemediationOrchestrator`.

**3.2 A mark is not bound to a flight.** `Mark` carries `id, layerId, position, kind, createdAt,
status, source, verification` — no `assetId`, no `usageId`. "Marks from this flight" can only ever
mean *marks created inside the flight's time window and visible to the requester*. That is an
approximation and the package must say so in words, not imply a link that does not exist.

**3.3 `MarkService#list` is scope-aware, and that is a feature.** It takes a `Viewer`. The package
must be built through the **requesting** viewer's scope — two referees with different scopes get
different packages, correctly. The package states whose scope produced it.

**3.4 The telemetry series is already lossy, twice over, and neither loss is currently visible.**
Both are documented gotchas in `contexts/vision-events/MODULE.md`:

- `TelemetryRepositoryPort#findByUsage(usageId, limit)` has **no time bounds**. `DefaultReplayService`
  fetches up to `fetchLimit` (20,000) *earliest* samples and filters in memory. Past ~5.5 h of 1 Hz
  flight, later windows are silently empty.
- `UsageTimeline` **thins** to `maxPoints`, capped at `maxPointsCeiling` (2,000). A one-hour flight
  at 1 Hz is 3,600 samples served as 2,000.

An evidence package that quietly ships 2,000 of 3,600 samples is worse than no package. Detecting
this is cheap — `thin()` returns the list unchanged when `size <= maxPoints`, so a returned count
**equal to** the requested `maxPoints` means thinning occurred. C13 does not fix the lossiness (that
needs a time-bounded query added to another context's port, out of scope) — it **declares** it.

## 4. What this cycle is not

- Not a PDF report. No new dependency; a ZIP of open formats.
- Not video bytes. The recording lives in mediamtx; the package carries its URL, start and duration,
  and says plainly when there is none.
- Not a fix for §3.4. Recorded as a follow-up, declared in the manifest, not silently worked around.
- Not a scheduled/automatic export. One request, one package.
