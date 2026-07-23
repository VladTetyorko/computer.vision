# MVP3 — the command point

Companion to [ARCHITECTURE.md](../ARCHITECTURE.md), [CYCLES-PLAN.md](CYCLES-PLAN.md) (delegation model, personas), [MVP2-PLAN.md](MVP2-PLAN.md) (complete, incl. §S stability). User directive (2026-07-23): *rethink the UI and asset flow around the app as a **command point** — operators of different drones seeing pictures and telemetry, backed by a warehouse of drones/cameras. One single main page for the operator; one page for a manager of 3–100 pilots.*

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
| C-a | backend | stream snapshots (`GET /api/streams/{id}/snapshot.jpg`) + fleet summary (`GET /api/fleet/summary`) | vision-application, vision-api, vision-app | pending |
| C-b | **UI** | Fly: the operator cockpit page | vision-web | pending |
| C-c | **UI** | Command: the manager dashboard | vision-web, after C-b | pending |

### C-a — backend: snapshots + fleet summary *(scope: vision-application, vision-api, vision-app wiring)*

- **Snapshots:** StreamPipeline already holds the latest published frame (or can retain one cheaply) — expose it as JPEG: `GET /api/streams/{id}/snapshot` (image/jpeg, the overlay-burned frame if burn-in on, else raw; 404 when not streaming; no caching). Manager thumbnails poll this at ~1/5s per *visible* tile — orders of magnitude cheaper than HLS players.
- **Fleet summary:** one aggregated endpoint for the Command page: per-category asset counts by lifecycle × streaming state, plus the attention-relevant per-asset facts in one response (assetId, name, category, lifecycle, streaming?, battery?, telemetryAgeMs?, openEventCount, sourceState) — server-side join of what the SPA today assembles from 4 polls; capped/paged for 100+; browser polls ONE endpoint.
- **Done when:** a curl fetches a JPEG of a live stream and one JSON that answers "who needs attention" for the whole fleet. **Estimate: M.**

### C-b — UI: Fly (operator cockpit) *(scope: vision-web)*

- Route `/fly` (default landing page): asset picker on first visit (streaming assets first), remembered per browser (settings store) — next visit lands straight in the cockpit.
- Layout: video fills the viewport; OSD chip bar overlaid (battery %, telemetry age with color escalation, altitude, heading, speed, transport+behind-live); events ticker (this stream's events + pipeline errors surfaced via player state) as an overlay strip; map inset (M key, existing component); detections strip collapsible; secondary tiles for the asset's other video devices; big Start/Stop (Stop confirms — it ends the stream for everyone); "Replay last flight" link when a finished usage exists.
- Keyboard: M map, B boxes, F fullscreen, Esc collapse — documented on-screen (? overlay).
- Reuses: player (WHEP-first + recovery + stopped state), OSD/telemetry components, LiveMap, events store, replay route. Mostly composition, little new logic.
- **Done when:** operator opens app → one click (or zero after first visit) → flying-aware; nothing on the page needs a second click to see safety-critical state. **Estimate: M/L.**

### C-c — UI: Command (manager dashboard) *(scope: vision-web, after C-b)*

- Route `/command`: attention queue (C-a facts, severity-sorted: battery < 20% / telemetry stale > 10s / source reconnecting / open events; each row: asset, why, age, [Watch] → Fly-in-watch-mode); live strip (snapshot thumbnails, virtualized, O(visible) snapshot polling); fleet map embed (existing component + event markers); warehouse readiness tiles per category (counts from C-a summary; click → filtered Assets page); events feed rail (existing store).
- Fly gains `?watch=1` (watch mode: no Start/Stop, viewing only) as the drill-down target.
- Scales: CDK virtual scroll everywhere list-like; a single summary poll + per-visible-thumbnail snapshot polls; no live player except a hover/click preview (one at a time, LiveDock precedent).
- **Done when:** with 100 simulated assets (script or synthetic), Command stays smooth, issues one summary poll per cycle, and the attention queue surfaces a low-battery drone within one poll. **Estimate: L.**

## Deferred (MVP4 candidates)

Auth/login + operator-vs-manager as real roles on Ownership; per-pilot assignment ("who flies what now"); manager annotations/tasking ("go look at X"); multi-operator presence; recording.
