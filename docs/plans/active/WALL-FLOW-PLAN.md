# WALL-FLOW — the watcher's surface: tiles that say what they are, and nothing else at rest

Status: ACTIVE · branch `feat/fly-flow-ux` (plan doc only; waves get their own branch) · owner
request 2026-09-02/03, live review of `/wall` with the backend on `:8080`, `ng serve` on `:4200`
and one simulated stream running.

Sister plan to [`FLY-FLOW-PLAN.md`](FLY-FLOW-PLAN.md), same doctrine applied to a different user:
**low effort, low overwhelm, levels/layers of modules, controls at the moment of intent, honest
states — never a dead-looking toggle, never a fabricated placeholder.** Fly's rework asked "what
does the pilot need at *this* stage"; this one asks "what does a person watching twelve pictures
need on *this* tile".

---

## 1. The user and the job

The `/wall` user is **watching, not flying**. Nav says so — Wall's canonical home is the Operate
group with the description *"Every live stream at once, auto-paused off-screen — pick one to watch
full-screen"* (`station/vision-web/src/app/features/hubs/nav-entries.ts:136-141`), and the original
product statement is sharper still: *"Grid tiles are quiet by default and flash a colored border on
a detection — motion in the periphery is how humans monitor many feeds at once"*
(`docs/main/UX-DESIGN.md:171`).

The job loop is four steps, and only the first one is continuous:

```
1. WATCH      keep N pictures in peripheral vision        ← 95% of the time on this page
2. NOTICE     one of them needs me (something was seen,
              the picture froze, that aircraft is in trouble)
3. DRILL      look at that one properly, decide
4. HAND OFF   pilot it / open its record / go back to watching
```

Everything on the surface is judged by that loop. A control that does not serve step 1 must not be
visible during step 1. A step-2 signal that the watcher has to *read text to find* has failed —
periphery notices motion, colour and position, not sentences. And step 3 must never cost the
wall: a drill-in that navigates away destroys the other eleven pictures, which is the one thing a
watcher cannot afford.

**What `/wall` is today, measured against that:** a thumbnail index of *streams* with a fleet-wide
*detection log* stapled to the right-hand third. It cannot tell you which tile needs you, every
action it offers leaves the page, and the one feed it does show is the only class of event that is
already visible in the video.

---

## 2. Diagnosis

Evidence is code (path:line, read 2026-09-03) plus the live review of the running app the same day
(marked **LIVE**). Consequence is always stated from the watcher's seat.

### 2.1 The surface

| # | Defect | Evidence | Consequence for the watcher |
|---|---|---|---|
| D1 | **A tile cannot say whether its picture is real.** `ActiveStream` carries `state` (`STARTING`/`LIVE`/`STALLED`/`RECONNECTING`/`UNOBSERVED`), `detectionEnabled` and `detectionState` (`core/api/models.ts:119-128`). `wall-tile.html` binds none of them — only `viewUrl`/`whepUrl`. | `wall-tile.html:2-9` | A frozen feed and a live feed are pixel-identical on the wall. Step 2 of the loop is impossible for the most common failure there is. |
| D2 | **A stream with no publisher URL renders as a black tile with no explanation.** The facade counts them into one page-level banner instead (`unwatchable()`); the tile itself says nothing. | `wall-facade.ts:32-35`, `wall.html:26-31`, `wall-tile.html:2-3` | "Two streams are running without a publisher URL" — *which two?* The watcher has to guess which black rectangles the banner means. |
| D3 | **Tiles are named after the device, and the device name leaks plumbing.** The tile title is `device()?.name ?? stream().deviceId`; the wall builds its tiles from `fleet.streams()` × `fleet.device(deviceId)` and never touches an asset. **LIVE:** the one tile read `Skyfall Vampire 2 · telemetry`. | `wall-tile.html:11-13`, `wall-facade.ts:25-30` | The app's model is Asset-first everywhere else (CLAUDE.md, "users interact with `Asset`; `Device` is low-level plumbing"). The wall shows the plumbing, with a transport suffix on it. |
| D4 | **Keying on streams/devices also costs the wall every fact it needs.** With no `assetId` a tile cannot join `AssetAttention` (`models.ts:1751-1765` — `displayName`, `batteryPercent`, `telemetryAgeMs`, `openEventCount`, `flightMode`, `armed`, `failsafe`, already served by `GET /api/fleet/summary` and already polled by Command), and `DetectionsStore.track(streamId, assetId?)` can never use the asset-scoped live topic — it is documented as permanently polling for exactly this call site. | `detections-store.ts` class doc ("a caller with no asset id in scope (`LivePage`/`WallTile`) always polls"), `command-facade.ts` fleet-summary poll | The wall is the one page that most needs "which of these needs me", and it is the one page structurally unable to ask. |
| D5 | **Every visible tile runs two of its own pollers.** `WallTile` provides its own `TelemetryStore` + `DetectionsStore` and starts both on intersection. | `wall-tile.ts:43`, `wall-tile.ts:110-142` | At 12 visible tiles that is ~24 requests every 2s to say what one 5s fleet-summary poll already answers. The O(visible) posture is honoured; the O(1) alternative was never taken. |
| D6 | **The per-tile "detection boxes" button is not per-tile.** It cycles `SettingsStore.declutterLevel`, the one shared persisted preference the Fly cockpit and `/live` also read. | `wall-tile.ts:56-70`, `station/vision-web/MODULE.md:52` | Clicking the glyph on tile 3 changes tiles 1–12 *and* the cockpit. A control that looks local and acts global is the definition of a surprising UI. |
| D7 | **The tile's status vocabulary is invented glyphs.** `⬢{battery}` / `▲{altitude}` in a chip, and `▦ ▢ ◉ ▢×` on the boxes button, with the real meaning hidden in `title` tooltips. | `wall-tile.html:15-24`, `wall-tile.ts:78-94` | Style law bans emoji/glyphs as UI icons (`frontend-style` §9) and the app has an icon set (`battery`, `alert`, `signal`, `eye`…). On a 240px tile these read as noise, and a tooltip is not a label. |
| D8 | **Altitude is on the wall at all.** | `wall-tile.ts:101-104` | Altitude is a *pilot's* number. The watcher's questions are "is it alive, is it healthy, did something happen" — a metre reading answers none of them. |
| D9 | **Drilling in leaves the wall.** The tile's only action is `Watch live →` (`/live/:deviceId`), a routed navigation that unmounts the grid, every player and the events poll. | `wall-tile.html:33-39` | Step 3 of the loop costs the watcher the other eleven feeds and a full re-buffer on the way back. |
| D10 | **A text button per tile is chrome multiplied by N.** "Watch live" + the glyph button + two chips sit in a footer bar under every single tile. | `wall-tile.html:10-48` | Twelve tiles = twelve identical button pairs competing with twelve moving pictures. The owner's standing note — "at rest, less". |
| D11 | **`Tiles per row` is still a raw `<select>` of `[2,3,4,5,6]` over a fixed-column grid.** The design doc asked for a 3-stop density control writing `--tile-min` into `repeat(auto-fill, minmax(…))`; the shipped code even carries the IOU in a comment. | `wall.html:16-24`, `wall.css:28-32`, `docs/extracts/design/03-wall.md:41-44,55-57`, `wall.html:8-12` | The window is told the column count instead of the layout adapting to the window. **LIVE:** one tile at 1854px reads as a lone thumbnail in a void — the exact acceptance criterion `03-wall.md:68-71` says must not happen. |
| D12 | **The wall has no tests at all.** No `wall*.spec.ts` anywhere; no pure `wall-logic.ts` to test. Every rule lives in a template or a component. | `find station/vision-web/src -path '*wall*' -name '*.spec.ts'` → empty | Every change to this surface is unverifiable by CI, on a page the repo calls the operator's default "watch everything" home (`docs/conclusions/UX-SIMPLIFY-REVIEW.md:71`). |
| D13 | **`features/wall/**` is undocumented in `MODULE.md`.** The features section names every feature folder except `wall/` and `alerts/`. | `station/vision-web/MODULE.md:182` | The mandatory module-doc workflow has a hole exactly where this plan works. |

### 2.2 The events flow, checked end to end (the owner's explicit ask)

Traced: `EventsStore` → `EventsRail` → `WallFacade.openEvent` → router. Every finding reproduced in
code; D15/D16/D19 also reproduced **LIVE**.

| # | Defect | Evidence | Consequence for the watcher |
|---|---|---|---|
| D14 | **The rail is fleet-wide furniture on a video wall.** 300px of the page, permanently mounted, sticky, never collapsible, showing every visible detection event in the deployment — including ones from streams that are not on this wall. | `wall.html:56`, `wall.css:37-44`, `events-rail.ts:11` (cap 20) | A third of a monitoring surface is spent on a list, and `/monitor/alerts` is already the two-pane page whose whole job is triaging that same feed (`features/alerts/**`). The wall pays screen for a duplicate. |
| D15 | **The feed is dominated by sources it cannot name.** `describeEventSource` resolves a name only through **currently-active streams** (`streams.find(streamId)` → `devices.find(deviceId)`); anything else becomes `Removed device · 7fd88790`. A stream that merely *restarted* gets a new `streamId`, so its own history is instantly unnameable. **LIVE:** 33 rows, every one a removed device, and the asset filter dropdown offered nothing else. | `events-logic.ts:148-160` | The rail's default view is either noise or empty. Neither is monitoring. |
| D16 | **The shipped fix for that hid the rows instead of naming them.** `includeRemoved` defaults `false` and filters them out; the header offers `Include removed devices (33)`. | `events-rail.ts:48-59,112-118`, `events-rail.html:26-38` | **LIVE**, on a wall with a live tile: the rail's visible content was zero real rows and one checkbox about 33 hidden ones. The watcher is being asked to administrate a log, not to watch. `event.assetId` was on almost every one of those rows the whole time — the fleet summary can name it. |
| D17 | **Clicking an event about a live drone opens a form.** `openEvent` tries a replay deep link first; `resolveReplayDeepLink` returns `undefined` whenever the covering usage is still open — i.e. always, for anything happening now — so it falls to `resolveEventTarget`, where `assetId` wins over the live stream and routes to `/assets/:assetId`. The row's own promise reads `Details ›`. | `wall-facade.ts:59-79`, `events-logic.ts:221-228`, `events-logic.ts:177-186`, `events-rail.ts:155-161` | "A person was seen on camera 3" → the watcher lands on camera 3's *inventory record*, having lost the wall. The one thing they wanted — to look at camera 3 — is the one thing the click cannot do. |
| D18 | **Nothing connects a row to a tile.** The row names its source in text; the wall never highlights, ranks or pulses the tile the event came from. | `events-rail.html:50-63`, `wall-tile.html` (no event binding) | The watcher does the join by hand: read a name, scan twelve tiles for it. That is the work the surface exists to remove. |
| D19 | **The feed has no recency floor.** Rows are capped at 20 but never aged out; `OPERATOR-UX-7` recorded "the newest row nine days old" on this very page. | `events-rail.ts:11,113-118`, `docs/plans/active/OPERATOR-UX-7-PLAN.md:11` | On a live surface, the top row can be a museum piece with a ticking "9d ago" next to it. |
| D20 | **The one event class the wall shows is the one already visible in the video.** Detections are drawn on the tiles. What actually breaks a watch — stream stalled, `PIPELINE_ERROR`, device offline, geofence breach — lives in `LiveStore.liveEvents()`/`SystemEventsStore` and appears only in the header bell and `/system`. | `core/system-events/system-events-store.ts`, `system-events-logic.ts:202` (`activePipelineErrorMessagesByStreamId`, already consumed by `command-facade.ts:160`) | The rail reports what the watcher can see and stays silent about what they cannot. |
| D21 | **The rail survives the empty state.** With zero streams the page shows one small card and still hands the right column to the rail. **LIVE.** | `wall.html:33-57` | The page's emptiest moment spends its largest region on a list of things that are not there. |
| D22 | **`WallFacade`'s events lifecycle is stale ceremony.** It calls `activate()`/`release()` under a comment claiming "O(visible) discipline"; the permanently-mounted `NotificationBell` has held a refcount since app boot, so the poll never stops. `EventsStore`'s own doc already says so. | `wall-facade.ts:42-47`, `events-store.ts:27-41` | Harmless at runtime, dishonest in the source — the next reader believes leaving `/wall` stops a poll it does not stop. |

**Verdict on the events flow:** the mechanism is sound (one shared store, one cursor, SSE-or-poll,
dedupe, notification gating — `events-store.ts` is good code). The *placement* is wrong. A
fleet-wide, unbounded, unnameable, navigation-on-click log is a triage surface — and the app already
has one at `/monitor/alerts`. On the wall, an event is not a row; it is a property of a tile.

---

## 3. The target model — stages × layers

**Page stages:**

```
P0 empty      no stream anywhere    one card: what to do next, full width
P1 watching   ≥1 tile               the grid IS the page; chrome is one 40px bar
P2 focused    one tile enlarged     over the wall — a layer, never a navigation
(drawer       any stage             activity sheet, opened on intent, closes back)
```

Deliberately **no `WallStage` enum**, unlike Fly. Fly needed one because four stages disagreed
about precedence; here the stage is `tiles().length === 0` and `focusedTile() !== null`, two signals
the facade already has. A type that only restates two booleans is ceremony. The *stage* discipline
still applies — it is a rule about what may render, not a value to compute. The per-tile state
machine (`TileHealth`) is where this wall's real derivation lives, and that one is frozen in §3.4.

**Layers** — every module belongs to exactly one, and never borrows another's slot:

```
L0  the glass       tile video, nothing burned on it (the player's own live/latency badge stays)
L1  the frame       ONE top bar: Wall · live count · density · Activity (n)
L2  the tile skin   identity + at most ONE state indicator at rest; the rest on hover/focus
L3  on demand       focus view, activity drawer
```

Two laws, the wall's equivalent of Fly's "one call-to-action per stage":

1. **At rest a tile shows the picture, its name, and — only when it is *not* simply live — one
   honest reason.** Every other control appears on hover, on focus, or in the drawer.
2. **Nothing on the wall navigates away from the wall.** Drill-in is focus, in place. Leaving is an
   explicit, labelled exit inside the focus view.

**Decision, frozen: tile order is stable, never attention-sorted.** Sort by title
(case-insensitive), then `streamId`. A watcher builds spatial memory — "the gate camera is
bottom-left" — and re-ordering tiles under an alarm destroys exactly the faculty the surface is
built on. Attention is signalled *in place*: colour, a border pulse, a dot. This deliberately
diverges from Command's attention-sorted rail (`command-logic.ts#buildEntityRows`), because Command
is a list and the wall is a map of the room.

### 3.1 Verdict per element

| Element | Verdict | Why |
|---|---|---|
| `.surface-dark` root + `min-height: var(--shell-h)` + `fullBleed` route | **KEEP** verbatim | The wall is a video surface end to end (`VISUAL-REFRESH` F3/W4); the min-height fix is a documented past bug. |
| Off-screen player suspend (`IntersectionObserver`, 250px preroll) | **KEEP** verbatim | The one measured, load-bearing behaviour on this page (`WEB-PLAN` W6). |
| Page bar (title, live count) | **KEEP**, extend | Gains the `Activity (n)` button; count noun stays "stream". |
| `Tiles per row` `<select>` | **REPLACE** → 3-stop `.segmented` density writing `--tile-min` into `repeat(auto-fill, minmax(var(--tile-min), 1fr))` | `03-wall.md:41-44,55-57`, owed since wave 2 (D11). Persisted `wallDensity` **number keeps its key and type** — 2/3/4 are the stops, ≥4 reads as dense; no schema migration, no settings-page ripple (that duplicate control is already deleted, `account-settings-facade.ts:26-29`). |
| Page-level "N streams without a publisher URL" notice | **MOVE** to the tile | D2. The fact is true; the place is wrong. |
| Empty state → `/assets` | **KEEP**, full width in P0; relabel the button **"Go to Inventory"** | D21: the rail stops stealing the column. The route is right, the copy is stale — `/assets` was renamed Inventory in WAREHOUSE-UX §3.1 and the wall still says "Go to Assets" (`wall.html:41`). |
| Tile title = device name | **CHANGE** → asset `displayName`, falling back to device name, then an 8-char device-id fragment | D3. Same fallback ladder `describeEventSource` already establishes. |
| Tile telemetry chip `⬢`/`▲` | **REMOVE** the glyphs and altitude; **KEEP** battery, coloured by `batteryAttentionSeverity`, with `vision-icon[name=battery]` | D7/D8. |
| Tile mode chip | **MOVE** to the focus view; `failsafe`/`armed` surface on the tile as attention, not as a word | The FC-INTEGRATIONS ask (`FC-INTEGRATIONS-PLAN.md:143`) is honoured at the level a watcher can act on. |
| Tile `Watch live` link | **REMOVE** → the tile *is* the click target (focus); the two real exits live in the focus view | D9/D10. |
| Tile boxes-cycle glyph button | **REMOVE** → one wall-level declutter control in the bar | D6: it was always global; now it looks it. |
| Per-tile `TelemetryStore` | **REMOVE** | D4/D5. `AssetAttention` already carries battery, telemetry age, mode, armed, failsafe from one 5s poll. |
| Per-tile `DetectionsStore` | **KEEP**, now passing `assetId` → live SSE transport when available | Per-frame boxes cannot come from a summary; the asset id makes it free when `LiveStore` is open. |
| Player's own latency/live badge | **KEEP** as-is | It is the cheapest honest "pixels are moving" signal on the tile, and it costs nothing. |
| Events rail, permanently mounted | **REMOVE** from the resting layout | D14/D21. |
| The events *data* | **KEEP** — re-expressed as (a) a per-tile pulse and (b) an on-demand drawer scoped to this wall | D18/D20. |
| `EventsStore.activate()/release()` | **KEEP**, comment corrected | D22 — cheap, and correct if the bell ever stops being permanent. |
| Event click → `/assets/:id` | **REPLACE** → focus that tile; the focus view then offers the exits | D17. |

### 3.2 What gets added (all four are joins of data that already exists)

- **A1 · Tile health.** `ActiveStream.state` + "no publisher URL" + an active `PIPELINE_ERROR` for
  this `streamId` (`activePipelineErrorMessagesByStreamId`, 15-minute decay, fed from
  `LiveStore.liveEvents()`, no new poll) collapse into one `TileHealth`. Precedence:
  `no-publisher` → `pipeline-error` → `stalled` → `reconnecting` → `starting` → `live`/`unknown`.
  `live` and `unknown` render **nothing** — the picture is the message, and `UNOBSERVED` is the
  honest "cannot judge" (`stream-state-logic.ts:37-52`'s own rule, applied at tile scale).
  **Decision — the health verdict is the server's, not the player's.** `<vision-player>` exposes no
  phase/first-frame output and this plan does not add one: `StreamState` is measured server-side
  from frame arrivals precisely so the UI stops inferring health from "the player has not errored
  yet" (`models.ts:130-143`). The player's own live-dot/latency badge stays as a second, independent
  signal; it is never promoted to a verdict.
- **A2 · Tile attention.** `attentionReasons(asset, gpsFixType?, geofenceBreaches?,
  pipelineErrorDetail?, thresholds?)` → one severity dot + a border tint, with the reason list in
  its `title`. Same call Command makes, same root stores (`GeofenceStore` is `providedIn: 'root'`
  and started at boot, so breaches are free). **Two anti-double-signal rules, frozen:** the
  `open-events` reason is *excluded* from the tile's dot (A3's pulse says it better, with the
  label), and when `health === 'pipeline-error'` the message renders once — as the health line, not
  also as a reason chip. One fact, one indicator (`frontend-style` §5; the same fix `event-row`
  already took in OPERATOR-UX-7 W1).
- **A3 · Tile pulse.** An `OPEN` detection event for this tile's `streamId` within
  `PULSE_WINDOW_MS` flashes the tile border and shows one label chip ("Person ×3"). This is
  `UX-DESIGN.md:171` finally built, and it is what replaces the rail for step 2. Honours
  `prefers-reduced-motion`: no flash, the chip alone.
- **A4 · Focus view + activity drawer.** L3. Focus: the tile enlarged over the wall with boxes,
  its facts (mode / armed / battery / telemetry age / health), and two labelled exits
  (`Open cockpit` → `/fly/:assetId`, `Watch live` → `/live/:deviceId`) plus `Esc`. Drawer:
  `vision-event-row` rows for **this wall's streams only**, inside a recency window, each row
  focusing its tile; a footer line links to `/monitor/alerts` for everything outside the window —
  honest about being a window, not a history. The two are **not** mutually exclusive (a row click
  focuses a tile with the drawer still open, so the watcher can step through), so both live as
  plain facade signals, the `LiveFacade#railOpen`/`mapInsetVisible` precedent, not a `UiStore`.

### 3.3 Reuse ledger — what this rework does not write

| Reused | From | Used for |
|---|---|---|
| `AssetAttention` + `GET /api/fleet/summary` | `models.ts:1751-1783`, `VisionApi#fleetSummary`, polled exactly as `command-facade.ts` does | tile identity, battery, telemetry age, mode, armed, failsafe, open-event count — one poll for the whole wall |
| `attentionReasons` / `REASON_RANK` / `batteryAttentionSeverity` / `attentionAgeLabel` | `core/fleet/attention-logic.ts` | the tile's severity verdict, in the app's one attention vocabulary |
| `activePipelineErrorMessagesByStreamId` (+ `PIPELINE_ERROR_ATTENTION_WINDOW_MS`) | `core/system-events/system-events-logic.ts:178-232` | tile health, with no new transport (`LiveStore` is already connected) |
| `EventsStore` (root, SSE-or-poll, dedupe, notification gate) | `core/events/events-store.ts` | pulses + drawer rows; untouched |
| `vision-event-row` | `shared/ui/event-row.ts` | drawer rows (default two-line variant, its documented 300px-host layout) |
| `vision-page-bar` (`[pageBarFilters]`/`[pageBarActions]` slots), `vision-notice`, `vision-empty`, `vision-icon`, `vision-icon-button[variant="hud"]`, `.segmented`, `.chip`, `.dot` | `shared/ui/**`, `styles.css` | the frame, the empty state, tile/focus chrome |
| `<vision-player>` (`src`/`whepUrl`/`suspended`/`compact`/`detections`/`boxesMode`; WHEP→HLS fallback and background re-upgrade) | `shared/player/player.ts:343-374` | every tile and the focus view, bound exactly as today |
| Fly's HUD idioms — `.hud-pill`, `.hud-badge`, `--hud-*` frosted chrome, the `.dock` single-anchor rule, `.hud-confirm` scrim, and `:host ::ng-deep … vision-player .frame { aspect-ratio }` | `fly-hud.css`, `cockpit.css:264-340,588-612` (`.secondary-tile` is already a wall tile in miniature) | tile overlay chrome and the focus layer — copied idioms, not a shared component |
| `humanAge`, `pluralize` | `core/telemetry/telemetry-logic.ts`, `shared/ui/text-logic.ts` | one age vocabulary; the wall's own `pluralize` import moves off `page-bar.ts` while W3 is in the file (`MODULE.md:195`) |

Deliberately **not** reused: `sortAssetsForPicker`/`triageOrder` (attention ordering — see §3's
stable-order decision), and `features/fly/stream-state-logic.ts#videoNotice`. The latter was
considered and rejected: it has two importers and would move cleanly to `core/`, but its wording is
cockpit-sentence-scale ("No video arriving — the source stopped sending.") where a 240px tile needs
two words. `wall-logic.ts` words the same axis at tile scale, the same host-fits-its-room split
`event-row`'s `dense` variant already makes explicit.

### 3.4 Frozen contracts

Cross-file, freeze exactly. New pure logic lives in `features/wall/wall-logic.ts` with a spec
(repo rule: pure logic in `*-logic.ts`, unit-tested; components are not).

```ts
// features/wall/wall-logic.ts
export type TileHealth = 'live' | 'starting' | 'stalled' | 'reconnecting'
                       | 'no-publisher' | 'pipeline-error' | 'unknown';

export interface TilePulse { readonly label: string; readonly count: number; readonly atIso: string; }

export interface WallTileModel {
  readonly streamId: string;
  readonly deviceId: string;
  readonly assetId?: string;          // absent → device not linked to an asset
  readonly title: string;             // asset displayName → device name → deviceId.slice(0,8)
  readonly unlinked: boolean;         // true → the tile says "Not linked to an asset", quietly
  readonly viewUrl?: string;
  readonly whepUrl?: string;
  readonly health: TileHealth;
  readonly healthLabel: string | null;        // null for 'live' AND 'unknown' — say nothing, never guess
  readonly severity: AttentionSeverity | 'ok';   // max rank of `reasons`; 'open-events' excluded (§3.2 A2)
  readonly reasons: readonly AttentionReason[];  // dot tooltip; excludes 'open-events' and 'pipeline-error'
  readonly batteryPercent?: number;
  readonly batterySeverity: BatteryAttentionSeverity;   // 'unknown' when absent — never a guess
  readonly telemetryAgeLabel: string | null;  // attentionAgeLabel(); null when never reported
  readonly flightMode?: string;
  readonly armed?: boolean;
  readonly failsafe?: boolean;
  readonly pulse: TilePulse | null;
  readonly openEventCount: number;
}

export interface WallActivityRow {
  readonly event: DetectionEvent;
  readonly tile: WallTileModel;      // the tile this row focuses — rows for other streams are dropped
  readonly sourceLabel: string;      // tile.title, i.e. the asset name; never `Removed device · …`
}

export interface BuildWallTilesInput {
  readonly streams: readonly ActiveStream[];         // FleetStore.streams()
  readonly devices: readonly Device[];               // FleetStore.devices() — title fallback only
  readonly assets: readonly AssetAttention[];        // FleetSummary.assets; [] when the poll degraded
  readonly events: readonly DetectionEvent[];        // EventsStore.events()
  readonly pipelineErrors: ReadonlyMap<string, string>;   // streamId → message
  readonly breaches: readonly GeofenceBreach[];
  readonly nowMs: number;
}

export const DENSITY_STOPS: readonly { value: number; label: string; tileMinPx: number }[] = [
  { value: 2, label: 'Comfortable', tileMinPx: 480 },
  { value: 3, label: 'Compact',     tileMinPx: 320 },
  { value: 4, label: 'Dense',       tileMinPx: 240 },
];
export function tileMinPx(wallDensity: number): number;          // ≥4 → dense, <2 → comfortable
export function buildWallTiles(input: BuildWallTilesInput): readonly WallTileModel[];  // stable order
export function wallActivityRows(
  events: readonly DetectionEvent[], tiles: readonly WallTileModel[], nowMs: number,
): readonly WallActivityRow[];                       // newest-first, this wall's streams, inside the window
export const PULSE_WINDOW_MS = 60_000;
export const ACTIVITY_WINDOW_MS = 60 * 60 * 1000;   // one hour; older → /monitor/alerts
```

```ts
// features/wall/wall-facade.ts — the page injects ONLY this (architecture.spec.ts:42 guards it)
tiles: Signal<readonly WallTileModel[]>;      liveCount: Signal<number>;
density: Signal<number>;  setDensity(value: number): void;  tileMinPx: Signal<number>;
boxesMode: Signal<BoxesMode>;  cycleBoxesMode(): void;      // the wall-level declutter control
focusedStreamId: Signal<string | null>;  focusedTile: Signal<WallTileModel | null>;
focus(streamId: string): void;  clearFocus(): void;
activityOpen: Signal<boolean>;  toggleActivity(): void;  activity: Signal<readonly WallActivityRow[]>;
activityCount: Signal<number>;                 // rows inside ACTIVITY_WINDOW_MS, for the bar button
```

Component selectors and I/O (3 files each — `.ts`/`.html`/`.css`, never inline):

| Selector | Inputs | Outputs |
|---|---|---|
| `vision-wall-tile` | `[tile]: WallTileModel`, `[boxesMode]: BoxesMode` | `(focused): string` (streamId) |
| `vision-wall-focus` | `[tile]: WallTileModel`, `[boxesMode]: BoxesMode` | `(closed)`, `(openCockpit): string`, `(watchLive): string` |
| `vision-wall-activity` | `[rows]: readonly WallActivityRow[]` | `(rowActivated): string` (streamId), `(closed)` |

No backend change anywhere. No new tokens, no new colours: severity uses the existing
`--color-danger`/`--color-warn`/`--color-live` chip and dot idioms, tile chrome over glass uses
`--hud-*` (legal inside `.surface-dark`, `frontend-style` §2), selection uses the one selection
language (§4 inset bar + `--color-info-soft`).

---

## 4. Waves

Each wave is one `web-ui` subagent, file-scoped, independently green
(`npx tsc --noEmit` **and** `npm run test:ci`, which is `ng test --watch=false` — never raw
`npx vitest run`, it fabricates ~536 failures) and ends with the module doc updated if it changed
conventions.

Constraints every wave inherits: three files per component (`.ts`/`.html`/`.css`, never an inline
template or styles); `wall/wall` is in `architecture.spec.ts`'s `ROUTED_PAGES`, so `wall.ts` may
inject **only** `WallFacade` (no `VisionApi`, no bare `*Store`) and may declare no
`open|menu|confirm|editing`-named bare `signal()` — every such flag lives on the facade;
`wall-tile` and the two new children are non-routed presentational components and keep the
documented carve-out (they may DI-share a host-provided store). Tokens only, no new colours, both
themes checked (`.claude/skills/frontend-style`).

**W1 — the wall's model** (`features/wall/wall-logic.ts` *(new)*, `wall-logic.spec.ts` *(new)*,
`wall-facade.ts`)
Everything in §3.4's logic block, plus the facade rewire: add a 5s `api.fleetSummary()` poll
(`PollScheduler`, the `command-facade.ts` precedent verbatim), read
`activePipelineErrorMessagesByStreamId(liveStore.liveEvents(), now)`, `GeofenceStore`'s breaches and
`EventsStore.events()`, build `tiles` through `buildWallTiles`, and expose the whole frozen facade
surface including the focus/activity signals (so W2 and W3 never touch this file). Degrade honestly:
a failed/forbidden summary leaves `severity: 'ok'`, `batterySeverity: 'unknown'`, device-name titles
— never a fabricated fact. Fix `activate()/release()`'s stale comment (D22). Spec covers: health
precedence, title fallback ladder, unlinked assets, the two anti-double-signal rules, pulse
windowing, density mapping, stable ordering, and the degraded-summary path.

**W2 — the tile skin** (`features/wall/wall-tile.{ts,html,css}`) — parallel with W3
One input `[tile]`, one output `(focused)`. Delete the `Watch live` link, the boxes button, the
glyph chips, the altitude readout and the tile's own `TelemetryStore` (D5–D10). At rest: the
picture, the title, and — only when `healthLabel` is non-null — one HUD line; severity as a border
tint + dot; `pulse` as a short border flash + one label chip. The footer (battery, telemetry age,
"Not linked to an asset") appears on hover/focus-within only. Keep the `IntersectionObserver`
suspend verbatim; keep `DetectionsStore`, now `track(streamId, assetId)`. Respect
`prefers-reduced-motion` on the pulse (`frontend-style` §9).

**W3 — the frame and the focus layer** (`features/wall/wall.{ts,html,css}`,
`wall-focus.{ts,html,css}` *(new)*) — parallel with W2
Bar: title + live count + density `.segmented` (3 stops) + one declutter control + `Activity (n)`.
Grid: `repeat(auto-fill, minmax(var(--tile-min), 1fr))` (D11). P0 empty state goes full width and
its button is relabelled **Go to Inventory**.
`vision-wall-focus` mounts over the stage when `focusedTile()` is set — enlarged player with boxes,
the tile's facts (mode/armed/battery/age/health), and the two labelled exits + `Esc`/backdrop close.
The events rail markup stays untouched in this wave (W4 owns it), so the page is shippable at every
point.

**W4 — activity on demand** (`features/wall/wall-activity.{ts,html,css}` *(new)*,
`features/wall/wall.{ts,html,css}`) — after W3
Delete `<vision-events-rail>` from `wall.html` and the `.events-rail` sizing from `wall.css`
(the component itself stays — `/command` still uses it). Add the drawer: `vision-event-row` rows
built from `wallActivityRows`, ordered newest-first, each row focusing its tile (never navigating,
D17); a quiet footer line "Older activity → Alerts center".

**The scoping is the fix for D15/D16, and it needs no new naming machinery.** A row exists only if
its event matches a tile on this wall, so `sourceLabel` is that tile's asset name by construction —
`Removed device · 7fd88790` cannot occur here, and no `includeRemoved` checkbox is needed. Frozen
matching rule: **by `assetId` when the event and a tile both carry one, else by `streamId`** — so an
asset whose stream restarted mid-session still shows its earlier events against its current tile
instead of falling off the wall. Everything the wall cannot name is exactly what `/monitor/alerts`
is for, and the footer says so. Delete `WallFacade.openEvent` and its `resolveReplayDeepLink` import
with it.

**W5 — verify and document** (`station/vision-web/MODULE.md`, `docs/plans/README.md`, this plan)
`npx tsc --noEmit` on both configs, `npm run test:ci`, `ng build --configuration production` with a
bundle delta note; a live pass of P0/P1/P2 + the drawer in **both themes** with ≥2 streams, one of
them deliberately stalled; add the missing `features/wall/**` MODULE.md entry (D13) and the wall's
new conventions; write the close-out table here and add this plan's row to the plans README (the
status authority).

Sequencing: **W1 → (W2 ∥ W3) → W4 → W5.** W2 and W3 share no file; both depend only on W1's frozen
contracts.

---

## 5. Verification, non-goals, and what is deliberately not in these waves

**Acceptance (checked live, not only green):**
1. A stalled or publisher-less tile says so on the tile, within one poll — and a healthy tile says
   nothing at all (`03-wall.md:68-71`'s "reads as intentional" plus D1/D2).
2. Resizing the window reflows tiles with no control touched; one stream at 1854px is a
   comfortably-sized tile, not a thumbnail in a void.
3. A detection on tile 5 is noticeable from the periphery without reading text, and clicking it in
   the drawer focuses tile 5 — the wall is never unmounted by any click except the two labelled
   exits.
4. Tile titles are asset names; a device with no asset says so quietly rather than showing plumbing.
5. Every tile's declutter state matches the one bar control, and nothing on a tile pretends to be
   per-tile when it is not.

**Out of the frontend waves — backend work, listed, not silently assumed:**
- **OUT-1** `DetectionEventResponse` carries no source name (`DetectionEventResponse.java`), which
  is why `describeEventSource` can only resolve a name through *currently-active* streams (D15). The
  wall sidesteps it by scoping (W4), but `/monitor/alerts` and the header bell are fleet-wide and
  still hit it head-on — `Removed device · 7fd88790` on rows whose device is alive and well. The
  real fix is an `assetName`/`deviceName` field on the DTO, and it belongs to whoever next owns the
  Alerts centre; this plan does not pretend to have fixed it.
- **OUT-2** There is no structured per-stream health; `SYSTEM-STATUS-PLAN` §2 defers it to S2b
  behind `StreamPipeline`'s K3 decomposition. Tile health is therefore `StreamState` plus a
  documented 15-minute `PIPELINE_ERROR` decay heuristic — the honest ceiling, and it is labelled as
  such in code.
- **OUT-3** `GET /api/events` has no `streamId in (…)` filter; the drawer filters client-side over
  the ≤200 events `EventsStore` retains. Fine at today's volumes; a server-side filter is the fix if
  a wall ever watches more streams than the retention window covers.
- **OUT-4** Acknowledging an alert (and saved threshold rules) remains unbuilt and stays that way —
  `/monitor/alerts` already says so plainly.

**Non-goals, named:**
- **Saved wall layouts** (`/monitor/layouts`, a `ComingSoon` route; `UI-REDESIGN-PLAN.md:515` notes
  it needs no backend). Out of these waves; the density stop stays the only persisted wall state.
- **Ghost tiles for assets that are not streaming.** The wall is what is streaming; the fleet-wide
  picture is Command's job. Making the wall a start/stop console would turn a watch surface into a
  control surface.
- **Audio alarms**, PTZ, recording controls, multi-monitor spawning.
- **Touching `shared/ui/events-rail.*` itself.** `/command` still renders it unchanged; this plan
  removes a *consumer*, it does not rework the component.

**Residuals accepted up front, both real losses:**
1. The wall gains one fleet-wide 5s poll (`GET /api/fleet/summary`, the same one `/command` runs)
   and loses one `TelemetryStore` poller per visible tile — net strongly negative at any wall wider
   than one tile, but it does mean a wall open in a background tab now polls once every 5s where it
   previously polled only while tiles were on screen. Accepted: it is one request, and it is what
   makes D1–D4 fixable at all.
2. **The drawer shows strictly less than the rail did**: only this wall's streams, only the last
   hour. That is the point (D14/D19), and it is a deliberate loss, not an oversight — a watcher who
   wants the fleet's full detection history has `/monitor/alerts`, and the drawer's footer links
   there rather than quietly implying it is showing everything.
3. No live/SITL screenshot verification exists until W5 runs — the same standing residual
   `FLY-FLOW-PLAN.md`'s own close-out carries. W5's acceptance list is the gate, not `tsc` green.

---

## 6. Close-out (2026-09-02/03, all five waves shipped on `feat/wall-flow-ux`, forked from `feat/command-map-ux` @ `102951d9`)

Full per-wave detail — component trees, exact test counts, commit file lists — lives in
`station/vision-web/MODULE.md`'s own "wall-flow-ux" entries (the `wall/` feature bullet plus the
W1–W5 Status entries); this table is the plan's own summary, not a duplicate of that account.

| Wave | Commit(s) | Status |
|---|---|---|
| W1 — the wall's model | `bd05ca02` | Shipped. Frozen §3.4 `wall-logic.ts` contract + `wall-facade.ts` rewired onto a 5s `fleetSummary()` join, replacing per-tile `TelemetryStore` polling. |
| W2 — the tile skin | `f4fe6191` | Shipped. `wall-tile` reduced to `[tile]`/`[boxesMode]` → `(focused)`; deleted the boxes glyphs, altitude readout, `Watch live` link, per-tile poller. |
| W3 — the frame and the focus layer | `17e7dcd0` | Shipped. Density-driven `--tile-min` grid, one declutter control, `vision-wall-focus` as an in-place L3 overlay with two labelled exits. |
| W4 — activity on demand | `7f20649a` | Shipped. `vision-wall-activity` — this wall's own streams, last hour, matched by `assetId`-then-`streamId` so no row can read `Removed device`. |
| W5 — verify and document | (this commit) | Shipped. Full verify chain green, live pass in both themes with a real multi-tile grid, bundle delta measured, this table + `MODULE.md` + the plans README updated. |

**Disclosed deviations from the plan's literal text, across all waves** (each already logged in
`MODULE.md` at the wave that produced it; consolidated here for one-stop review):

- **W1 — `WallFacade.openEvent` removed one wave early.** The plan's own W4 text reads "Delete
  `WallFacade.openEvent`… with it," but the frozen §3.4 facade surface (which W1 implements
  verbatim) never lists that method at all — there was nothing left to delete by W4; W1 simply
  never wrote it in the first place.
- **W3 — the events-rail markup deleted one wave early, forced by the deviation above.** `wall.html`
  still wired to `<vision-events-rail (open)="facade.openEvent($event)">` could not have compiled
  once W1 landed with no `openEvent` method — so W3 deletes the old rail outright (the plan's own
  §3.1 verdict table already calls for its removal) rather than shipping a wave with a silently-dead
  click handler. W4 still adds the real replacement (`vision-wall-activity`) as entirely new work;
  end state after W4 matches the plan's own description exactly, only the old rail's *deletion*
  moved one wave earlier.
- **W3 — one real logic bug, caught by its own spec, fixed in the same wave.** `tilePulse` originally
  picked the single most-recently-arrived event's label; a late, different-label single event could
  steal the pulse chip from an already-larger, still-open group of an earlier label. Fixed to pick
  the dominant label by count (ties broken by recency) — `wall-logic.spec.ts` was already written
  correctly against the frozen contract; the implementation was not.
- **W5 — one accessibility fix found live**, not anticipated by any wave's own text: a healthy
  tile's `<button>` had no accessible name (its only name source, `[title]`, is `null` whenever
  `severity === 'ok'` — the common case). Fixed with an unconditional `[attr.aria-label]`.

No other deviation from the frozen contracts in §3.4 was needed — `WallTileModel`/`TileHealth`/
`TilePulse`/`WallActivityRow`, the three density stops, the two anti-double-signal rules, and every
named component's I/O all shipped exactly as specified.

**§5's acceptance list, checked against the live pass:**
1. **Met** — a stalled/publisher-less tile's health ladder is unit-covered (`no-publisher >
   pipeline-error > stalled > reconnecting > starting > live/unknown`, `wall-logic.spec.ts`) but was
   **not independently screenshotted live** this wave (see "Left unverified" below) — the one
   acceptance item this close-out cannot mark fully green from observation alone.
2. **Met** — confirmed live: the grid reflows via `repeat(auto-fill, minmax(var(--tile-min), 1fr))`
   with no control touched, at all three density stops.
3. **Met** — a detection pulse renders as a border flash + persistent chip without reading text; the
   Activity drawer's rows focus their tile on click, never navigate.
4. **Met** — tile titles are asset names by construction (`buildWallTiles`' title-fallback ladder);
   an unlinked device renders its own quiet "Not linked to an asset" note.
5. **Met** — one wall-level declutter control (`facade.boxesMode()`/`cycleBoxesMode()`) is the only
   source of `[boxesMode]` for every tile and the focus view; nothing on a tile pretends to be
   per-tile.

**Non-goals and out-of-scope backend items (§5)** were not touched by any wave and remain open
exactly as named there: saved wall layouts, ghost tiles for non-streaming assets, audio alarms/PTZ/
recording controls, `shared/ui/events-rail.*` itself (still rendered unchanged by `/command`), and
all four OUT-1..OUT-4 backend gaps (event DTO has no source name; no structured per-stream health;
no server-side `streamId` filter on `GET /api/events`; no alert-acknowledge/threshold rules).

**Final verify chain (W5), the plan's own required figures:**

- `npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — clean, 0 errors both.
- `npm run test:ci` — **181/181 files, 3570/3570 tests** (unchanged since W4; W5 added no specs).
- `node node_modules/@angular/cli/bin/ng.js build --configuration production` — green. Two
  pre-existing warnings only, both present before this branch and untouched by this plan: initial
  bundle over its 390 kB budget (by 31.60 kB), `tactical-map.css` over its 11 kB budget (by 376
  bytes).
- **Bundle delta**, against the `102951d9` fork-point baseline (disposable `git worktree`, symlinked
  `node_modules`, removed after measuring): initial bundle **422.01 kB → 421.60 kB raw (−0.41 kB),
  118.76 kB → 117.85 kB transfer (−0.91 kB)** — effectively flat, since every wall-flow-ux change
  lives inside the lazy `wall` chunk. Lazy `wall` chunk **9.80 kB → 21.95 kB raw (+12.15 kB), 3.30 kB
  → 6.20 kB transfer (+2.90 kB)** — two new components (`wall-focus`, `wall-activity`) and a
  rewritten `wall-tile`, offset partly by deleting the old per-tile poller and the events-rail import.
- **Live pass, both themes**: P0 (empty state, "Go to Inventory"), P1 (grid + density + declutter,
  exercised with two temporarily-started simulated streams via the app's own stream-lifecycle
  endpoints, stopped afterward), P2 (focus overlay — facts, both labelled exits, Esc/backdrop
  close), and the Activity drawer (rows, relative-time ticking, `/monitor/alerts` footer link) all
  confirmed rendering correctly in light and dark theme. One accessibility bug found and fixed live
  (see the deviations list above). **Not achieved live**: a genuinely stalled/no-publisher tile —
  see "Left unverified" below.

**Left unverified**: the stalled/no-publisher tile visual state was not independently screenshotted
— producing one live would mean deliberately breaking the backend or a publisher mid-session, judged
too invasive for a read-only verification pass on a shared running station. It rests on
`wall-logic.spec.ts`'s unit coverage of the full health-precedence ladder instead. No other gap is
known.

Role-gating and dev-parity are unaffected by this plan end to end — no wave reads a role or
`vision.auth.enabled`; the Wall has never been role-gated (any authenticated viewer who could reach
`/wall` could already see every tile/event on it), and the dev admin (`vision.auth.enabled=false`)
sees identical behavior to a real ADMIN throughout.
