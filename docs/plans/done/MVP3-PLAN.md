# MVP3 — the command point

Companion to [ARCHITECTURE.md](../../../ARCHITECTURE.md), [CYCLES-PLAN.md](../../main/CYCLES-PLAN.md) (delegation model, personas), [MVP2-PLAN.md](MVP2-PLAN.md) (complete, incl. §S stability). User directive (2026-07-23): *rethink the UI and asset flow around the app as a **command point** — operators of different drones seeing pictures and telemetry, backed by a warehouse of drones/cameras. One single main page for the operator; one page for a manager of 3–100 pilots.*

**Milestone:** an operator opens the app and is flying-aware in one click; a manager opens the app and knows within five seconds which of 100 assets needs attention.

## The two jobs (design ground truth)

**Operator (pilot):** flies ONE drone at a time; everything else is noise. Needs, on a single page, ordered by importance: (1) the live picture — dominant, lowest-latency transport, detection boxes on it; (2) the safety numbers glanceable without leaving the video: **battery, telemetry/link age (staleness = danger), altitude, heading/speed**; (3) position — map inset, expandable, breadcrumb trail; (4) what-just-happened — events ticker (detections, source reconnects) inline, not on another tab; (5) deliberate big controls: start/stop stream, boxes toggle, map expand, replay-last-flight. Secondary sources (their second camera) as small switchable tiles.

**Manager (3–100 pilots):** does attention triage, not watching. Needs: (1) **attention queue** — assets needing action now (battery < threshold, telemetry stale, source down/reconnecting, open detection events), most severe first, empty = "all quiet"; (2) **live strip** — who is flying: per-stream **thumbnails** (not live players — 100 players is a non-starter), name, pilot-relevant chips; (3) fleet map (exists); (4) **warehouse readiness** — per category: airworthy / streaming / assigned / archived counts; (5) cross-fleet events feed (exists); (6) drill-down: click anything → that asset's cockpit in watch mode. Scales by aggregation + virtualization + O(visible) polling — never O(fleet) requests from the browser.

## Information architecture change

Top-level nav becomes job-oriented: **Fly** (operator cockpit) · **Command** (manager dashboard) · **Assets** (warehouse, today's Devices page) · **Settings**. `/wall`, `/map`, `/live/:id` remain as routes but Command absorbs Wall+Map's job and Fly absorbs Live's; existing pages stay functional during the transition (no capability loss, links keep working). No auth yet (deferred since MVP2): Fly/Command are views, not permissions — MVP4 puts login/roles in front of them using the existing Ownership model.

## Build rules (user directives 2026-07-23)

- **Reuse-first is binding, not advisory:** C-b/C-c compose existing components (player, OSD/telemetry panels, LiveMap, flight-plan dialog, events store/rail, LiveDock, stream-info panel, replay route, PollScheduler, settings store). A new component is justified only when no existing one covers the job after actually reading it — each new component the task creates must be justified in the final report.
- **Map tiles are cached after first load.** Today every map view re-fetches raster tiles from the network (only ephemeral browser HTTP cache in between). Requirement: tiles seen once render from local cache afterwards — surviving reloads, and degrading gracefully to the existing offline-grid fallback only for never-seen tiles. Implementation constraint that rules out the "obvious" answer: a service worker requires a secure context, and LAN viewers use plain `http://<ip>:8080` — so ngsw/SW-based caching would silently not work for exactly the field-ops users who need it most. Prefer an IndexedDB-backed tile layer in `ui/leaflet-loader.ts` (intercept tile load → serve blob from IndexedDB if present → else fetch, render, store; bounded cache with LRU/size cap ~200–500MB and per-layer keying incl. zoom; respect tile-server usage policies — cache, don't bulk-prefetch). Lands in C-b (shared `leaflet-loader` infra, so Fly/Command/replay/detail maps all inherit it).

## Cycles

| Cycle | Kind | Ships | Scope | Status |
|---|---|---|---|---|
| C-a | backend | stream snapshots (`GET /api/streams/{id}/snapshot.jpg`) + fleet summary (`GET /api/fleet/summary`) | vision-application, vision-api, vision-app | ✅ done |
| C-b | **UI** | Fly: the operator cockpit page | vision-web | ✅ done |
| C-c | **UI** | Command: the manager dashboard | vision-web, after C-b | ✅ done |

### C-a — backend: snapshots + fleet summary *(scope: vision-application, vision-api, vision-app wiring)*

- **Snapshots:** StreamPipeline already holds the latest published frame (or can retain one cheaply) — expose it as JPEG: `GET /api/streams/{id}/snapshot` (image/jpeg, the overlay-burned frame if burn-in on, else raw; 404 when not streaming; no caching). Manager thumbnails poll this at ~1/5s per *visible* tile — orders of magnitude cheaper than HLS players.
- **Fleet summary:** one aggregated endpoint for the Command page: per-category asset counts by lifecycle × streaming state, plus the attention-relevant per-asset facts in one response (assetId, name, category, lifecycle, streaming?, battery?, telemetryAgeMs?, openEventCount, sourceState) — server-side join of what the SPA today assembles from 4 polls; capped/paged for 100+; browser polls ONE endpoint.
- **Done when:** a curl fetches a JPEG of a live stream and one JSON that answers "who needs attention" for the whole fleet. **Estimate: M.**

**Done note (for C-b/C-c to build against):**

**1. `GET /api/streams/{streamId}/snapshot`** (note: no `.jpg` suffix — a plain path segment, `Content-Type: image/jpeg` on the response is what tells the browser what it is, consistent with every other id-suffixed path in this API)
- 200, body = JPEG bytes, `Content-Type: image/jpeg`, `Cache-Control: no-store`.
- Downscaled to at most **480px wide** (`SnapshotJpegEncoder.MAX_SNAPSHOT_WIDTH`, vision-api), aspect-preserving. A frame that's already JPEG-encoded and already ≤480px wide (adapter-simulation/adapter-mjpeg's typical case) passes through untouched — cheap by default, not just at the cap.
- 404 (`{"error":"NOT_FOUND", ...}`) when the stream id is unknown **or** is running but hasn't published a frame yet — both are indistinguishable `Optional.empty()` from `StreamService#latestFrame`. 400 for a malformed stream-id UUID.
- The frame is exactly what the pipeline last published — **post-overlay burn-in** when `PipelineConfig#overlayBurnIn()` is on (the default), i.e. the same detection boxes a live viewer sees.
- Poll cost: one JPEG encode per request, on the caller's thread, no server-side caching — cheap enough for the plan's "~1/5s per visible tile" budget, but C-c should still only poll tiles actually on screen (virtualization), not the whole fleet at once.

**2. `GET /api/fleet/summary?includeArchived=`** (note the query param name — deliberately `includeArchived`, not `includeDeleted` like every other list endpoint; see vision-api/MODULE.md's Conventions for why)
- 200, body:
  ```json
  {
    "categories": [
      {"categoryId": "drone", "categoryName": "Drone", "total": 5, "active": 4, "deactivated": 1, "deleted": 0, "streaming": 2}
    ],
    "assets": [
      {
        "assetId": "…", "displayName": "…", "categoryId": "drone", "categoryName": "Drone",
        "lifecycle": "ACTIVE", "streaming": true, "streamId": "…",
        "batteryPercent": 42.0, "telemetryAgeMs": 3100, "openEventCount": 1
      }
    ],
    "totalAssets": 5
  }
  ```
- `categories`: one row per category actually present, sorted by slug; **never capped** — reflects the true fleet regardless of the `assets` list's own cap below.
- `assets`: sorted by `displayName` (case-insensitive), capped at **500** (`DefaultFleetSummaryService.MAX_ASSETS_IN_SUMMARY`) — comfortably past the "3–100 pilots" scale this cycle targets. Compare `assets.length` to `totalAssets` to detect truncation; no cursor/offset param exists yet (not needed at this scale — add one if a future fleet genuinely exceeds 500).
- `streamId`/`batteryPercent`/`telemetryAgeMs` are **omitted from the JSON entirely** (not `null`) when unavailable — check for key presence, not `!== null`.
- `batteryPercent`/`telemetryAgeMs` are derived from the **last telemetry sample ever received**, not scoped to "currently streaming" — an asset that just landed still reports a real (growing) `telemetryAgeMs`, which is exactly the staleness signal the attention queue wants.
- `openEventCount` counts `OPEN` `DetectionEvent`s for that asset among the fleet's 2,000 most-recently-updated events (`DefaultFleetSummaryService.OPEN_EVENTS_SCAN_LIMIT`) — effectively exhaustive at demo scale, honestly documented as not exhaustive at extreme scale.
- **No `sourceState` field exists** — there is no honest "reconnecting"/"degraded" read available anywhere in the backend today (`EventPublisherPort` is write-only; `SupervisedPublisher`'s outage state is private to `DefaultStreamService`). C-c's attention-queue design should key severity off `batteryPercent < 20`/`telemetryAgeMs` staleness/`openEventCount > 0` only — not assume a source-health signal exists.
- 400 for a malformed `includeArchived` value (not boolean-parseable); no other failure case (an empty fleet is 200 with empty arrays).

Scope note: both endpoints are read-only additions; nothing about existing endpoints changed. `vision-domain` was untouched — every new type is an application-layer read model or a vision-api DTO.

### C-b — UI: Fly (operator cockpit) *(scope: vision-web)*

- Route `/fly` (default landing page): asset picker on first visit (streaming assets first), remembered per browser (settings store) — next visit lands straight in the cockpit.
- Layout: video fills the viewport; OSD chip bar overlaid (battery %, telemetry age with color escalation, altitude, heading, speed, transport+behind-live); events ticker (this stream's events + pipeline errors surfaced via player state) as an overlay strip; map inset (M key, existing component); detections strip collapsible; secondary tiles for the asset's other video devices; big Start/Stop (Stop confirms — it ends the stream for everyone); "Replay last flight" link when a finished usage exists.
- Keyboard: M map, B boxes, F fullscreen, Esc collapse — documented on-screen (? overlay).
- Reuses: player (WHEP-first + recovery + stopped state), OSD/telemetry components, LiveMap, events store, replay route. Mostly composition, little new logic.
- **Done when:** operator opens app → one click (or zero after first visit) → flying-aware; nothing on the page needs a second click to see safety-critical state. **Estimate: M/L.**

**Done note (for C-c to build against):**

- **Route**: `/fly` is now the default (`'' → redirectTo 'fly'`); every existing route (`/wall`, `/map`, `/live/:deviceId`, etc.) is untouched and still reachable, plus a new "Fly" nav tab (first in the list). `FlyPage` takes two **query-param** inputs, bound automatically with no route-table change (`withComponentInputBinding()` merges query params in by name, same mechanism path params use): `watch` (`?watch=1` exactly — hides Start/Stop/Replay) and `requestedAssetId` (aliased to `?asset=`, overrides — and becomes — the remembered `SettingsStore.flyAssetId`). **C-c's drill-down is exactly**: `router.navigate(['/fly'], {queryParams: {asset: assetId, watch: 1}})` — no other wiring needed on the Fly side.
- **`SettingsStore.flyAssetId: WritableSignal<string | null>`** — the remembered/switcher-selected drone; `null` shows the picker. C-c doesn't need to touch this directly (the query-param route above handles it), but it's the field to read if a future page wants to know "what is the operator currently set to fly" without navigating there.
- **Reused pieces available to Command too**: `core/device-logic.ts#videoDevices` (every `VIDEO`-capable device on an asset, not just the first), `core/telemetry-logic.ts#batterySeverity`/`telemetryAgeSeverity` (the `'ok'|'low'|'critical'|'unknown'` / `'fresh'|'amber'|'red'` tiers — Command's own attention-queue severity keying, per this plan's own C-c bullet, "battery < 20% / telemetry stale > 10s", can reuse `telemetryAgeSeverity`'s `TELEMETRY_AGE_RED_SECONDS`=10 constant directly rather than re-deriving it), `ui/detections-strip.ts` (moved to `ui/` specifically so a second page could import it without a cross-page import — Command doesn't currently need it, but it's no longer `pages/live/`-scoped if a future surface does).
- **Map-tile cache is unconditionally live on every map already** (`ui/leaflet-loader.ts#mapLayerTileLayer`, the one function `LiveMap`/`FleetMap`/`ReplayMap`/`FlightPlanDialog` all build their tile layer through) — Command's own fleet map embed inherits it automatically, nothing to wire.
- **No `sourceState`/reconnecting signal was invented anywhere in this cycle either** (matches C-a's own done note) — Fly's OSD reads staleness purely from telemetry age + the player's own transport/phase chip, never a fabricated "degraded" concept. Command's attention queue should key off the same honest signals C-a's fleet summary already exposes (`batteryPercent`, `telemetryAgeMs`, `openEventCount`), not assume this cycle added anything new server-visible.

### C-c — UI: Command (manager dashboard) *(scope: vision-web, after C-b)*

- Route `/command`: attention queue (C-a facts, severity-sorted: battery < 20% / telemetry stale > 10s / source reconnecting / open events; each row: asset, why, age, [Watch] → Fly-in-watch-mode); live strip (snapshot thumbnails, virtualized, O(visible) snapshot polling); fleet map embed (existing component + event markers); warehouse readiness tiles per category (counts from C-a summary; click → filtered Assets page); events feed rail (existing store).
- Fly gains `?watch=1` (watch mode: no Start/Stop, viewing only) as the drill-down target.
- Scales: CDK virtual scroll everywhere list-like; a single summary poll + per-visible-thumbnail snapshot polls; no live player except a hover/click preview (one at a time, LiveDock precedent).
- **Done when:** with 100 simulated assets (script or synthetic), Command stays smooth, issues one summary poll per cycle, and the attention queue surfaces a low-battery drone within one poll. **Estimate: L.**

**Done note:**

- **Route**: `/command`, idle-preloaded like every real tab, a real top-level nav entry (see the IA note below). No inputs — unlike `/fly`, nothing else deep-links into Command yet.
- **Attention queue severity, exactly as spec'd**: `battery < 20%` (warning) escalating to `< 10%` (critical); `telemetryAgeMs > 10s` **while streaming only** (critical — a landed asset with old telemetry isn't urgent, an airborne one is); `openEventCount > 0` (warning). No `source reconnecting` reason — per both C-a's and C-b's own done notes, no honest "reconnecting" signal exists anywhere server-side to key off; the queue's three rules are exactly the three C-a's `AssetAttentionResponse` actually supports. Ordering: highest-severity triggered reason first, then more-simultaneous-reasons-first, then alphabetical — see `vision-web/src/app/pages/command/command-logic.ts` for the exact rule and `vision-web/MODULE.md`'s own C-c Status entry for the full boundary-tested writeup.
- **Live strip**: `GET /api/streams/{id}/snapshot` into an `<img>`, cache-busted per poll (the server's `Cache-Control: no-store` alone doesn't make an unchanged `<img src>` re-fetch), gated on `IntersectionObserver` visibility *and* CDK horizontal virtual scroll — snapshot request count is bounded by strip viewport width, never by fleet or streaming-count size. A 404 (no frame published yet) shows a plain "No preview" placeholder, never an error toast.
- **Fleet map embed**: `<vision-fleet-map>` reused completely unmodified (its own event markers/layer switcher/auto-fit all included for free) — but relocated, along with `LiveDock`/`FleetMapStore`/its pure logic, from `pages/map/` to shared `core`/`ui` homes so Command could embed it without breaking this codebase's own "no cross-page import" precedent; `MapPage` itself is behaviorally unchanged (its own full spec suite passes unmodified at the new location). Command's own "Watch" wiring differs from `MapPage`'s: the map's `watch` output is just an assetId, and Command routes it straight to `/fly?asset=<id>&watch=1` (C-b's own pinned one-liner) rather than resolving a device id for `/live/:deviceId` — no `resolveWatchDevice` call needed for Watch, only for the (unchanged) docked Preview.
- **Warehouse readiness tiles**: per-category counts read from `summary.categories` (never capped server-side, unlike `summary.assets`' 500-row cap) — accurate at any fleet size. Click navigates to plain `/devices`, no query param: the Devices page has no category filter to deep-link into today, per this cycle's own explicit "don't build new filtering into Devices" instruction.
- **Events rail**: `pages/wall/wall.ts`'s own rail extracted to `ui/events-rail.ts` (byte-for-byte the same feature) so Command could reuse it too — `WallPage` itself is unchanged in behavior.
- **Nav IA landed as the plan's own end state**: `app.ts`'s tabs are now **Fly · Command · Assets · Settings** (`Assets` a label-only rename — the route stays `/devices`); `Wall`/`Map`/`Debug` moved into a header "More" `<details>` overflow, not removed — every route stays fully reachable, `routerLinkActive` still highlights the overflow trigger when one of its own links is active.
- **Scale**: verified at N=100 both by code reasoning (grep-verified: `VisionApi.fleetSummary()` has exactly one call site in `pages/command/**`) and by dedicated `command-logic.spec.ts` cases building 100 synthetic assets. Full accounting — including the one documented, pre-existing exception (the embedded fleet map's own already-established `core/map-store.ts` polling, unchanged since C6/CU-b, not new O(fleet) surface this cycle adds) — is in `vision-web/MODULE.md`'s C-c Status entry.
- **No `sourceState`/reconnecting signal was invented here either** (matches C-a's and C-b's own done notes) — the queue's `telemetry-stale` reason reads staleness purely from `telemetryAgeMs`, the same honest signal Fly's own OSD already uses.

## Deferred (MVP4 candidates)

Auth/login + operator-vs-manager as real roles on Ownership; per-pilot assignment ("who flies what now"); manager annotations/tasking ("go look at X"); multi-operator presence; recording.
