# UI-STRUCTURE-PLAN — feature-responsibility folders for vision-web

Status: **proposed** (2026-07-24). Read-only analysis + target design for `vision-web/src/app`.
No code moved by this document — it is the spec a later delegated task executes.

Scope: `vision-web/src/app` only (134 files, ~24.3k LOC across `app.*`/`core/`/`pages/`/`ui/`
as of this writing). Angular 21, standalone components, signals, zoneless, Vitest.

## 0. Why now

Today's three buckets — `pages/` (11 route features), `core/` (23 singleton-ish files), `ui/`
(23 shared-ish files) — group by *layer*, not by *feature*. That was fine at MVP1 size; at
MVP3-complete size (`vision-web/MODULE.md` is itself 757 lines) two symptoms already show up:

1. **One real cross-feature violation exists today**: `pages/map/map.ts` imports
   `buildTestDroneRequest` from `pages/devices/simulate-logic.ts` — a page reaching into another
   page's private module, which this codebase's own stated convention (`core/device-logic.ts`'s
   doc comment: *"this codebase has no precedent for one page importing another page's module —
   every cross-page dependency runs through `core/` instead"*) says shouldn't happen. It happened
   anyway because there's no obvious shared home for "build a synthetic test-drone request" — it
   isn't a store, it isn't UI, so it stayed put in the one feature that needed it first.
2. **New in-flight work (docs/REALTIME-PLAN.md Phase R-b, uncommitted as of this analysis) is
   landing new files at `core/webrtc-certificate*.ts` and `ui/webrtc-ice-restart.ts`** — correct
   by *today's* rule (core = services, ui = shared components) but by *responsibility* these are
   Player-internal WebRTC plumbing, used by nothing else. The flat layer-first layout doesn't have
   a better place to put them, so they land in the generic bucket by default. This is exactly the
   drift this plan closes.

## 1. Current-state map (condensed)

### Routes (`app.routes.ts`), one lazy `loadComponent` chunk each

| Path | Component | Nav tab? |
|---|---|---|
| `''` → redirect | → `fly` | — |
| `/fly` | `FlyPage` | Fly (default) |
| `/command` | `CommandPage` | Command |
| `/wall` | `WallPage` | More ▾ |
| `/map` | `MapPage` | More ▾ |
| `/devices` | `DevicesPage` | Assets |
| `/assets/:assetId` | `AssetDetailPage` | (drill-in only) |
| `/assets/:assetId/replay/:usageId` | `ReplayPage` | (drill-in only) |
| `/live/:deviceId` | `LivePage` | (drill-in only) |
| `/settings` | `SettingsPage` | Settings |
| `/debug` | `DebugPage` | More ▾ |
| `**` | `NotFoundPage` | — |

### Folder sizes today

| Folder | Files | LOC | What it holds |
|---|---:|---:|---|
| `core/` | 41 | ~6,070 | API client, 6 stores + their pure `-logic.ts` twins, cross-cutting infra (poll scheduler, idle preload, toast, api-error), plus **new R-b files**: `webrtc-certificate*.ts` |
| `pages/` | 60 | ~10,590 | 11 route features, each `<name>.ts/.html/.css` + page-local logic/child components |
| `ui/` | 28 | ~7,220 | Shared presentational pieces: player, maps, dialogs, rails — reused by 2+ pages each, plus **new R-b file** `webrtc-ice-restart.ts` |
| `app.*` | 5 | 377 | Shell: config, routes table, root component/nav |

### Shared state/services (`core/`)

`VisionApi`/`models.ts` (typed REST client) · `FleetStore` (root singleton, 5s devices/streams
poll) · `TelemetryStore`/`DetectionsStore`/`FleetMapStore` (page-provided, one instance per route
activation) · `EventsStore` (root singleton, refcounted `activate()`/`release()`) ·
`SettingsStore` (root, localStorage-backed) · `PollScheduler` (the one shared `setInterval`) ·
`ToastService` · `IdlePreload`/`LeafletWarmup` (bootstrap) · pure `*-logic.ts` twins for every
store above, plus `device-logic.ts`/`warehouse-logic.ts`/`stream-info-logic.ts` (cross-page pure
logic with no store of their own) · new: `webrtc-certificate.ts`/`-db.ts`/`-logic.ts` (R-b,
in-flight, WHEP-only).

### Presentational components (`ui/`)

`player.ts` (the video surface, 1,461 LOC — WHEP+HLS, self-recovering) + its pure satellites
(`player-recovery.ts`, `live-edge-logic.ts`, `detection-overlay-logic.ts`) · `stream-info-panel.ts`
· `detections-strip.ts` · `live-map.ts`/`fleet-map.ts`/`live-dock.ts`/`leaflet-loader.ts`
(+ IndexedDB tile cache: `tile-cache-db.ts`/`tile-cache-logic.ts`) · `flight-plan-dialog.ts`/
`flight-plan-logic.ts` · `events-rail.ts` · `toast-host.ts` · new: `webrtc-ice-restart.ts` (R-b,
in-flight, WHEP-only) · **`fly-osd.ts`** — the one file here used by exactly one page (`FlyPage`).

### Cross-feature dependency map (who imports what, beyond `core`/`ui`)

Every page imports from `core/` and `ui/` freely (by design — that's the point of those two
buckets today). The one page→page edge is the violation called out in §0:

```
pages/map/map.ts  ──imports──▶  pages/devices/simulate-logic.ts (buildTestDroneRequest)
```

No other page imports another page's file. `ui/*` components that inject a `core/` store
(`TelemetryOsd`-style DI-sharing: `stream-info-panel.ts`, `fleet-map.ts`, `live-map.ts`,
`live-dock.ts`, `events-rail.ts`, `flight-plan-dialog.ts`) are the established, intentional
pattern — the host page provides the store, the shared component injects the same instance.
That pattern is preserved, not changed, by this plan.

### Misplaced / worth fixing while moving files

| Finding | Detail |
|---|---|
| **`ui/fly-osd.ts` is feature-local, not shared** | Only `FlyPage` uses it (confirmed: it's the only importer). It sits in the shared `ui/` bucket purely because "components live in `ui/`" was the only rule, not because a second feature needs it. |
| **`pages/map/map.ts` → `pages/devices/simulate-logic.ts`** | The one real cross-page import (§0.1). `buildTestDroneRequest`/`TestDroneForm` need a shared, non-page home; the rest of `simulate-logic.ts` (`buildSyntheticRegisterRequest`, `mapSimulatedDevices`, `SIMULATED_CATEGORY`) is genuinely Devices-only and stays. |
| **Identical filename in two different folders**: `core/warehouse-logic.ts` and `pages/devices/warehouse-logic.ts` | One is the generic lifecycle state machine (shared by Devices + Asset Detail), the other is Devices' own row-view-model builder. Same basename, different content, different folders — a `grep`/tab-switch trap waiting to happen. |
| **New R-b files already landing in the old flat buckets** | `core/webrtc-certificate*.ts`, `ui/webrtc-ice-restart.ts` are Player-only WebRTC internals with nowhere better to go under today's layout — living proof the flat layer-first structure runs out of room exactly where this plan adds one. |
| **Components over ~500 LOC** (flagged, not solved by this move) | `ui/player.ts` (1,461), `pages/devices/devices.ts` (774), `pages/asset-detail/asset-detail.ts` (566), `pages/fly/fly.ts` (509, borderline). `player.ts` already delegates all its *pure* logic to sibling files (`player-recovery.ts`, `live-edge-logic.ts`, `detection-overlay-logic.ts`) — its remaining size is mostly irreducible DOM/WebRTC/HLS wiring. `devices.ts`/`asset-detail.ts` are the two components most likely to benefit from splitting out child components (e.g. Devices' "Advanced raw-devices table" as its own component) in a future cycle — **not** part of this folder migration; noted here so it isn't lost. |

## 2. Target structure

Three top-level buckets replace `pages/`/`core`/`ui`:

```
src/app/
├── app.config.ts / app.css / app.html / app.routes.ts / app.ts      (shell — unchanged)
├── core/        singleton services, API client, cross-cutting stores + their pure logic
├── shared/      reusable pieces used by ≥2 features, grouped by subsystem, not dumped flat
│   ├── ui/      genuinely dumb, domain-free presentational widgets
│   ├── player/  the video surface: player + WebRTC/HLS state machines + WHEP/ICE plumbing
│   └── map/     the Leaflet surface: bootstrap, tile cache, map components, flight-plan editor
└── features/    one folder per routed page — owns its component, logic, routes, page-local children
```

### 2.1 `core/` — full mapping

```
core/
├── api/
│   ├── models.ts                          (unchanged)
│   ├── vision-api.ts                       (unchanged)
│   └── vision-api.spec.ts                  (unchanged)
├── api-error.ts / api-error.spec.ts        (unchanged — cross-cutting, no natural pairing)
├── poll-scheduler.ts / poll-scheduler.spec.ts   (unchanged)
├── idle-preload.ts                          (unchanged)
├── leaflet-warmup.ts                        (unchanged)
├── toast.service.ts                         (unchanged)
├── stream-info-logic.ts / stream-info-logic.spec.ts   (unchanged — no store of its own)
├── fleet/                                    ← NEW subfolder
│   ├── fleet-store.ts                        ← from core/fleet-store.ts
│   ├── device-logic.ts / device-logic.spec.ts        ← from core/
│   ├── warehouse-logic.ts / warehouse-logic.spec.ts  ← from core/ (generic lifecycle logic)
│   └── simulation-logic.ts / simulation-logic.spec.ts ← NEW FILE, extracted (see §3, step B1)
├── telemetry/                                 ← NEW subfolder
│   ├── telemetry-store.ts / telemetry-store.spec.ts   ← from core/
│   └── telemetry-logic.ts / telemetry-logic.spec.ts   ← from core/
├── detections/                                ← NEW subfolder
│   ├── detections-store.ts / detections-store.spec.ts ← from core/
│   └── detections-logic.ts / detections-logic.spec.ts ← from core/
├── events/                                    ← NEW subfolder
│   ├── events-store.ts / events-store.spec.ts  ← from core/
│   └── events-logic.ts / events-logic.spec.ts  ← from core/
├── map/                                       ← NEW subfolder
│   ├── map-store.ts / map-store.spec.ts        ← from core/
│   └── map-logic.ts / map-logic.spec.ts        ← from core/
├── settings/                                  ← NEW subfolder
│   └── settings-store.ts / settings-store.spec.ts ← from core/
└── live/                                      ← NEW, empty until docs/REALTIME-PLAN.md Phase R-c
    └── (live-store.ts lands here directly when R-c is implemented — see §4)
```

**What moved where, and why**: every store already has a pure `-logic.ts` twin (existing
convention: `telemetry-logic.ts` next to `telemetry-store.ts`, tested independently). This plan
just turns that *naming* pairing into a *folder* pairing, so `core/` stops being one 20-file
alphabetical scroll and instead reads as "six concerns, each with its state + its pure rules."
`device-logic.ts`/`warehouse-logic.ts`/the new `simulation-logic.ts` all concern fleet
inventory (devices/assets, lifecycle, capability filtering, synthetic registration) and move
into `core/fleet/` alongside `fleet-store.ts` for the same reason — and this incidentally kills
the `warehouse-logic.ts` filename collision from §1 for free (the generic one is now
`core/fleet/warehouse-logic.ts`; the Devices-page-specific one is renamed in `features/devices/`
— see §2.3). `api-error.ts`/`poll-scheduler.ts`/`idle-preload.ts`/`leaflet-warmup.ts`/
`toast.service.ts`/`stream-info-logic.ts` have no paired store and stay flat at `core/` root —
forcing every file into a subfolder for its own sake is ceremony, not clarity.

### 2.2 `shared/` — full mapping

```
shared/
├── ui/
│   ├── toast-host.ts                        ← from ui/
│   └── events-rail.ts / .html / .css        ← from ui/
├── player/
│   ├── player.ts                            ← from ui/       [move LAST — see §3 Phase D]
│   ├── player-recovery.ts / .spec.ts        ← from ui/
│   ├── live-edge-logic.ts / .spec.ts        ← from ui/
│   ├── detection-overlay-logic.ts / .spec.ts ← from ui/
│   ├── detections-strip.ts                  ← from ui/
│   ├── stream-info-panel.ts                 ← from ui/
│   ├── webrtc-certificate.ts                ← from core/     [R-b, in-flight — see §4]
│   ├── webrtc-certificate-db.ts             ← from core/     [R-b, in-flight]
│   ├── webrtc-certificate-logic.ts / .spec.ts ← from core/   [R-b, in-flight]
│   └── webrtc-ice-restart.ts / .spec.ts     ← from ui/       [R-b, in-flight]
└── map/
    ├── leaflet-loader.ts                    ← from ui/
    ├── tile-cache-db.ts                     ← from ui/
    ├── tile-cache-logic.ts / .spec.ts       ← from ui/
    ├── live-map.ts / .html / .css           ← from ui/
    ├── fleet-map.ts / .html / .css          ← from ui/
    ├── live-dock.ts / .html / .css          ← from ui/
    ├── flight-plan-dialog.ts / .html / .css ← from ui/
    └── flight-plan-logic.ts / .spec.ts      ← from ui/
```

**Why three siblings, not one flat `shared/ui/`**: `player/` and `map/` are each a small
integrated subsystem — a state machine plus a third-party integration (WebRTC/hls.js; Leaflet)
plus a persistence layer (IndexedDB cert store; IndexedDB tile cache) — reused whole by 3+
features each. Filing them next to `toast-host.ts` under one generic "dumb components" folder
would either make that folder not-actually-dumb, or tempt someone to leave the *next*
WebRTC/Leaflet file in `core/`/`ui/` root the way R-b's new files already have (§0.2). Giving
each subsystem its own folder is the same "concern gets a folder" move as `core/`'s `fleet/`/
`telemetry/`/etc. — just one layer further from the domain stores and closer to the DOM/browser
APIs. `shared/ui/` stays genuinely small and genuinely dumb: two files today. That's honest,
not a placeholder — this codebase has no reusable button/table/badge *components* today (chips,
buttons, tables are global CSS classes in `src/styles.css`, applied directly in each feature's
own template); don't manufacture a component library for zero current consumers. Extract into
`shared/ui/` the day a second feature needs the identical markup, per this codebase's own
already-stated precedent ("move to a shared home once a second page needs it" —
`core/device-logic.ts`'s doc comment).

### 2.3 `features/` — full mapping

```
features/
├── fly/                                  (default route, operator cockpit)
│   ├── fly.routes.ts                     ← NEW (extracted from app.routes.ts)
│   ├── fly.ts / .html / .css             ← from pages/fly/
│   ├── fly-logic.ts / .spec.ts           ← from pages/fly/
│   └── fly-osd.ts                        ← from ui/fly-osd.ts  (misplaced-file fix, §1)
├── command/                              (manager dashboard)
│   ├── command.routes.ts                 ← NEW
│   ├── command.ts / .html / .css         ← from pages/command/
│   ├── command-logic.ts / .spec.ts       ← from pages/command/
│   └── live-strip-tile.ts                ← from pages/command/
├── wall/
│   ├── wall.routes.ts                    ← NEW
│   ├── wall.ts / .html / .css            ← from pages/wall/
│   └── wall-tile.ts                      ← from pages/wall/
├── map/                                  (fleet overview tab — NOT the shared/map/ library)
│   ├── map.routes.ts                     ← NEW
│   └── map.ts / .html / .css             ← from pages/map/
├── devices/
│   ├── devices.routes.ts                 ← NEW
│   ├── devices.ts / .html / .css         ← from pages/devices/
│   ├── simulate-logic.ts / .spec.ts      ← from pages/devices/ (trimmed, §3 step B1)
│   └── devices-page-logic.ts / .spec.ts  ← renamed from pages/devices/warehouse-logic.ts
├── asset-detail/
│   ├── asset-detail.routes.ts            ← NEW
│   ├── asset-detail.ts / .html / .css    ← from pages/asset-detail/
│   └── asset-detail-logic.ts / .spec.ts  ← from pages/asset-detail/
├── replay/
│   ├── replay.routes.ts                  ← NEW
│   ├── replay.ts / .html / .css          ← from pages/replay/
│   ├── replay-logic.ts / .spec.ts        ← from pages/replay/
│   └── replay-map.ts / .html / .css      ← from pages/replay/ (feature-local, unchanged reasoning)
├── live/                                 (single-device cockpit, `/live/:deviceId`)
│   ├── live.routes.ts                    ← NEW
│   ├── live.ts / .html / .css            ← from pages/live/
│   └── telemetry-osd.ts                  ← from pages/live/
├── settings/
│   ├── settings.routes.ts                ← NEW
│   └── settings.ts / .html / .css        ← from pages/settings/
├── debug/
│   ├── debug.routes.ts                   ← NEW
│   ├── debug.ts / .html / .css           ← from pages/debug/
│   ├── debug-api.service.ts              ← from pages/debug/
│   ├── debug-endpoints.ts / .spec.ts     ← from pages/debug/
│   ├── debug-history.ts / .spec.ts       ← from pages/debug/
│   └── debug-response.ts / .spec.ts      ← from pages/debug/
└── not-found/
    ├── not-found.routes.ts               ← NEW (or inline `**` stays in app.routes.ts — either is fine, see §5)
    └── not-found.ts                      ← from pages/not-found/
```

**`pages/` → `features/`, why a rename and not just a move**: purely semantic, but it's the
semantic the rest of this plan hangs off — "this folder is a self-contained feature, not just
a page template" is what justifies giving each one its own `-logic.ts`, its own routes file, and
license to keep feature-local child components (`wall-tile.ts`, `live-strip-tile.ts`,
`telemetry-osd.ts`, `fly-osd.ts`) beside the page rather than in a shared bucket by default.
`features/map/` (the `/map` fleet-overview *page*) and `shared/map/` (the Leaflet *library* used
by `map`, `command`, `live`, `asset-detail`, `fly`, `replay`, `devices`) are deliberately
different folders at different tree levels despite the name overlap — `MapPage` (the feature)
*consumes* `shared/map/fleet-map.ts` (the library), it doesn't own it. If this reads confusingly
in practice, `features/map/` can be renamed `features/fleet-overview/` at zero cost (git mv only,
no import-path shape change) — noted as an option, not required.

## 3. Migration plan — ordered, atomic, tests green after each step

**Ground rule for every step below**: because every `features/<x>/` and `pages/<x>/` folder sits
at the same depth under `src/app/` (two segments), and every `shared/<x>/` folder sits at the
same depth `ui/` did (one segment, just with one more path component in the string), **moving a
file never changes how many `../` a relative import needs — only the path string after it.**
That makes every step below a mechanical `git mv` + a scripted `grep -rl '<old-path>' | xargs sed
-i 's#<old>#<new>#'`, not a manual per-file edit. Verify with `./mvnw -B -pl vision-web test`
(or `npm run test:ci` directly) after each step; each step is small enough that a red build points
at exactly one thing.

**Sequencing note — do not fight the in-flight work.** `git status` at the time of this analysis
shows exactly what's mid-flight (docs/REALTIME-PLAN.md Phases R-a/R-b, and a backend-only R-c
slice under `vision-api`/`vision-app`/`vision-domain` this plan doesn't touch): modified —
`core/map-store.ts`, `core/telemetry-store.ts`(+`.spec`), `pages/asset-detail/asset-detail.ts`,
`pages/fly/fly.ts`, `pages/fly/fly-logic.ts`(+`.spec`), `pages/live/live.ts`,
`pages/wall/wall-tile.ts`, `ui/player.ts`, `ui/player-recovery.ts`(+`.spec`); new/untracked —
`core/webrtc-certificate*.ts`, `ui/webrtc-ice-restart.ts`(+`.spec`). **Every step below is
ordered so the "safe now" work (nothing in the list above) proceeds first, and every step
touching a file from that list is explicitly marked deferred**, to run only once that work has
landed — moving a file out from under an open edit, or editing an import line in a file someone
else is mid-diff on, is exactly the merge-conflict risk the task briefing warned about. Re-check
`git status` before running any step marked deferred below; the list above is a snapshot, not a
promise.

### Phase 0 — warm-up (zero dependency on anything else, prove the mechanics)

| Step | Files | Kind | Effort |
|---|---|---|---|
| 0.1 | `pages/settings/*` → `features/settings/*` | pure move | 10 min |
| 0.2 | `pages/debug/*` (9 files) → `features/debug/*` | pure move | 15 min |
| 0.3 | `pages/not-found/not-found.ts` → `features/not-found/not-found.ts` | pure move | 5 min |
| 0.4 | `pages/replay/*` (9 files) → `features/replay/*` | pure move | 20 min |

### Phase A — extract the two `shared/` libraries first (so features move once, not twice)

| Step | Files | Kind | Effort |
|---|---|---|---|
| A1 | `ui/leaflet-loader.ts`, `ui/tile-cache-db.ts`, `ui/tile-cache-logic.ts(+.spec)`, `ui/live-map.*`, `ui/fleet-map.*`, `ui/live-dock.*`, `ui/flight-plan-dialog.*`, `ui/flight-plan-logic.ts(+.spec)` → `shared/map/` | pure move (15 files); update ~8 importer files (`fly.ts`, `live.ts`, `asset-detail.ts`, `map.ts`, `command.ts`, `devices.ts`, `replay-map.ts`) | 60–90 min |
| A2 | `ui/toast-host.ts`, `ui/events-rail.*` → `shared/ui/` | pure move; update `app.html`, `wall.ts`, `command.ts` | 20 min |

### Phase B — feature folders (`pages/` → `features/`), including the two real fixes

**B-now (nothing below touches a file from the Sequencing-note list — run any time):**

| Step | Files | Kind | Effort |
|---|---|---|---|
| B1 | `pages/devices/*` → `features/devices/*`, **plus**: rename `warehouse-logic.ts` → `devices-page-logic.ts` (+spec); extract `buildTestDroneRequest`/`TestDroneForm` out of `simulate-logic.ts` into new `core/fleet/simulation-logic.ts` (+spec, moved test cases) | move + 1 rename + 1 real code split | 45–60 min |
| B2 | `pages/map/map.*` → `features/map/map.*`; repoint its import from `../devices/simulate-logic` to `core/fleet/simulation-logic` (closes the §1 violation) — `pages/map/map.ts` itself isn't in the in-flight list, only `core/map-store.ts` is, and this step doesn't touch that file | move + import fix | 20 min |
| B4 | `pages/command/*` → `features/command/*` | pure move | 20 min |

**B-deferred (each of these moves a file that's currently mid-edit — wait for R-a/R-b to land,
re-check `git status`, then run):**

| Step | Files | Kind | Effort |
|---|---|---|---|
| B3 | `pages/wall/*` → `features/wall/*` — bundles `wall-tile.ts`, currently modified | pure move | 15 min |
| B5 | `pages/asset-detail/*` → `features/asset-detail/*` — `asset-detail.ts` currently modified | pure move | 20 min |
| B6 | `pages/live/*` → `features/live/*` — `live.ts` currently modified | pure move | 15 min |
| B7 | `pages/fly/*` → `features/fly/*`, **plus** `ui/fly-osd.ts` → `features/fly/fly-osd.ts` (misplaced-file fix, §1) — `fly.ts`/`fly-logic.ts`(+`.spec`) currently modified | move + 1 relocate | 20 min |

**B-final** (after both groups above — can run in two passes, once per group, rather than waiting for all ten):

| Step | Files | Kind | Effort |
|---|---|---|---|
| B8 | Add `<name>.routes.ts` to every feature folder above; rewrite `app.routes.ts` to compose them (redirect + `**` stay inline in `app.routes.ts` — they're shell concerns, not any one feature's) | new files, mechanical | 45–60 min total |

### Phase C — regroup `core/` stores into per-concern subfolders

Independent of each other; land one at a time.

**C-now:**

| Step | Files | Effort |
|---|---|---|
| C1 | `core/detections-{store,logic}.ts(+.spec)` → `core/detections/` | 15 min |
| C2 | `core/events-{store,logic}.ts(+.spec)` → `core/events/` (consumers: `shared/ui/events-rail.ts`, `shared/map/fleet-map.ts`, `features/wall`, `features/command`, `features/asset-detail`) | 20 min |
| C3 | `core/settings-store.ts(+.spec)` → `core/settings/` (widest fan-in by consumer *count*, but each is a one-line path edit) | 15 min |
| C4 | `core/fleet-store.ts`, `core/device-logic.ts(+.spec)`, `core/warehouse-logic.ts(+.spec)` → `core/fleet/`, joining `simulation-logic.ts` from B1 | 45–60 min |

**C-deferred** (both move a file currently mid-edit per the Sequencing note — wait, re-check
`git status`, then run):

| Step | Files | Effort |
|---|---|---|
| C5 | `core/map-{store,logic}.ts(+.spec)` → `core/map/` — `core/map-store.ts` is currently modified; `map-logic.ts` isn't but they move together | 20 min |
| C6 | `core/telemetry-{store,logic}.ts(+.spec)` → `core/telemetry/` — `telemetry-store.ts`(+`.spec`) currently modified | 20 min |

### Phase D — the player/WebRTC cluster (schedule last, gated on the concurrent R-b task)

**D-now** (none of these four are in the modified/untracked list):

| Step | Files | Effort |
|---|---|---|
| D1 | `ui/live-edge-logic.ts(+.spec)`, `ui/detection-overlay-logic.ts(+.spec)`, `ui/detections-strip.ts`, `ui/stream-info-panel.ts` → `shared/player/` | 25 min |

**D-deferred** — every file here is either currently modified or a brand-new untracked file from
the in-flight R-b task; wait for it to land, re-check `git status`, then move the whole cluster
in one step so it lands at its final home rather than moving twice:

| Step | Files | Effort |
|---|---|---|
| D2 | `ui/player.ts`, `ui/player-recovery.ts(+.spec)`, `core/webrtc-certificate.ts`, `core/webrtc-certificate-db.ts`, `core/webrtc-certificate-logic.ts(+.spec)`, `ui/webrtc-ice-restart.ts(+.spec)` → `shared/player/` | 45 min |

### Phase E — cleanup + mandatory doc update

| Step | What | Effort |
|---|---|---|
| E1 | Delete now-empty `pages/` and `ui/` directories (git mv empties them automatically; confirm nothing's left) | 5 min |
| E2 | **Update `vision-web/MODULE.md`** (mandatory per this repo's `CLAUDE.md` module-docs workflow). Sounds scarier than it is: the doc is already organized concern-by-concern with path-prefixed headings (`### src/app/pages/fly/**`, `### src/app/core/api/`, …) rather than as a directory dump — this is almost entirely "replace the path prefix in each heading and in-line back-tick path" (`pages/fly/` → `features/fly/`, `ui/player.ts` → `shared/player/player.ts`, etc.), a scripted find/replace pass followed by a careful read-through, not a rewrite. Budget real time anyway — it's 757 lines. | 2–4 hr |

**Total estimated effort**: ~9–13 hours of (mostly mechanical, scriptable) work across ~20 atomic
steps, of which roughly half is Phase E's documentation pass. Every step keeps
`./mvnw -B -pl vision-web test` green; no step other than B1/B2 touches program behavior.

## 4. Interaction with docs/REALTIME-PLAN.md (so this plan doesn't fight it)

- **Phase R-a** (real WHEP stall detection, O(N) guard, `telemetry-store.ts` assetId-first) is
  frontend-only and already landing in `ui/player.ts`/`core/telemetry-store.ts` at their
  *current* paths. Nothing in this plan asks that work to target new paths pre-emptively —
  Phase D and the `telemetry/` half of Phase C above are explicitly sequenced after it.
- **Phase R-b** (persistent WHEP: ICE restart, session `Location`/DELETE, stable DTLS
  certificate) is what's producing the new `webrtc-certificate*.ts`/`webrtc-ice-restart.ts`
  files spotted mid-flight during this analysis (§0.2). This plan's answer for them is
  `shared/player/` (§2.2) — they're Player-only WebRTC internals, the same bucket `player.ts`
  and `player-recovery.ts` land in. **Recommendation for whoever lands R-b**: no need to change
  course now — finish R-b at today's paths, then Phase D moves the whole cluster in one step
  once it's stable. If R-b's own task has capacity to place *new* files directly at
  `shared/player/...` once this plan is accepted, that's a small head start, not required.
- **Phase R-c** (`LiveStore`, the single `EventSource`/SSE connection; `FleetStore`/
  `TelemetryStore`/`DetectionsStore`/`EventsStore` become projections of it) should create
  `LiveStore` directly at **`core/live/live-store.ts`** (the empty subfolder reserved for it in
  §2.1) rather than at a flat `core/live-store.ts` that would need a second move later. The four
  stores it projects into already have their own `core/<concern>/` homes by the time R-c is
  likely to start (Phase C, scheduled well before R-c per docs/REALTIME-PLAN.md's own phase
  order) — each becomes a thinner file in the same folder it's already in, importing
  `core/live/live-store.ts` as a sibling-level dependency. `PollScheduler` staying at `core/`
  root (its degraded-fallback role per R-c) needs no change either way.

## 5. Rules going forward (one sentence each)

- **A new routed page** → `features/<name>/`, with its own `<name>.routes.ts`,
  `<name>.ts/.html/.css`, and a `<name>-logic.ts` for any pure/testable derivation; add one line
  to `app.routes.ts` importing that feature's routes.
- **A new service/store used by exactly one feature** → lives inside that feature's own folder
  (like `features/live/telemetry-osd.ts`) — it only moves to `core/` the day a *second* feature
  needs it.
- **A new store + its pure logic, needed by ≥2 features** → `core/<concern>/`, as a
  `<concern>-store.ts` / `<concern>-logic.ts` pair (add a new subfolder if it's a new concern;
  don't wedge it into an existing one that isn't really the same thing).
- **A new cross-cutting utility with no store** (another `poll-scheduler.ts`-shaped thing) →
  `core/` root, no subfolder needed for a single file.
- **A new dumb, domain-free presentational component reused by ≥2 features** (a table, a badge,
  a button variant) → `shared/ui/` — and don't pre-build it for one consumer; extract on the
  second use, per this codebase's own standing precedent.
- **Anything video/WebRTC/HLS-playback-shaped** → `shared/player/`, no matter how small — that's
  the one bucket, don't let a new one-off land in `core/`/`ui/` root the way R-b's files did.
- **Anything Leaflet/map-shaped** (a new map layer, a new map-embedding component) →
  `shared/map/`.
- **A new wire type or REST call** → `core/api/models.ts` / `core/api/vision-api.ts`. *(If that
  file ever crosses ~500–600 lines or gains a clearly separable 6th resource area, split by
  resource — `devices.api.ts`/`assets.api.ts`/`fleet.api.ts`/etc., each still hand-imported, no
  barrel. Not needed today at 279/489 lines.)*
- **No `index.ts` barrels, anywhere, ever** — matches this codebase's existing zero-barrel
  convention; import the concrete file directly.
- **Tests are always colocated**: `x.ts` + `x.spec.ts` in the same folder, never a parallel
  `__tests__/` tree — unchanged from today.
- **Template/styles**: inline `template`/`styles` in the `.ts` file while short; split into
  sibling `.html`/`.css` once the template alone would run past ~40–50 lines or needs real
  syntax highlighting to read — this is already this codebase's de facto rule (compare
  `ui/toast-host.ts`, inline, against `ui/live-dock.html`, split); this plan just names it so a
  new component's author doesn't have to guess by looking for precedent.
- **Naming**: keep the existing suffix conventions as-is — `-logic.ts` (pure, tested,
  Angular-free), `-store.ts` (injectable, holds state/polls), bare name for components
  (`fly.ts`, not `fly.component.ts` — matches this app's existing convention and current Angular
  guidance of omitting type suffixes for component files). `core/toast.service.ts`'s lone
  `.service.ts` (dot, not hyphen) is a pre-existing, harmless exception — not worth a
  repo-wide suffix-unification pass on its own.

## 6. What this plan deliberately does not do

- **No TypeScript path aliases** (`@core/*`, `@shared/*`, `@features/*`). They'd make every step
  above even cheaper to script, but they're a build-tooling convention change this codebase has
  never used (every import today is relative) and nobody asked for — introducing one as a side
  effect of a folder reshuffle is out of scope. Worth a separate, explicit conversation if the
  team wants it later; not required for this migration to succeed.
- **No component splitting.** `player.ts` (1,461 LOC), `devices.ts` (774), `asset-detail.ts`
  (566) are flagged in §1 as follow-up candidates, not touched here — this plan only moves
  files between folders; breaking up a god-component is a separate, riskier task with its own
  design questions (which child components, which inputs/outputs) that shouldn't ride along
  with a mechanical reorganization.
- **No MODULE.md format change.** `vision-web/MODULE.md` keeps its current concern-by-concern
  prose structure (per `.claude/skills/module-docs/SKILL.md`'s one-`MODULE.md`-per-Maven-module
  convention) — only its path references update (Phase E2). Splitting it per-feature would
  contradict that convention and isn't proposed.
