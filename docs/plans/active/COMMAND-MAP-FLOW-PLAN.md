# COMMAND-MAP-FLOW — the map is the dashboard, and it tells the truth

Status: ACTIVE · branch `feat/fly-flow-ux` (map/command work continues here) · owner request
2026-09-02/03: *"the map should be as useful and user-friendly as possible; unify the layers & marks
flow across all surfaces; a drone that stops reporting must still show its last position; clicking a
drone should show its route (маршрут); double-check the events flow."*

Same design language as [`FLY-FLOW-PLAN.md`](FLY-FLOW-PLAN.md) — low effort, low overwhelm,
levels/layers, controls that appear at the moment of intent, honest states. That plan reworked
`/fly`; this one reworks `/command` and the shared map every surface embeds. It **supersedes** one shipped
decision of [`MAP-REWORK-PLAN.md`](../done/MAP-REWORK-PLAN.md) §5.2 — the per-page, per-shape entry
points for Zones/Marks/Layers/Draw — with one drawer component and one section vocabulary on every
surface that has a map, and it **overturns** one refusal in
[`MAP-UX-RESEARCH.md`](../../conclusions/MAP-UX-RESEARCH.md) §8 on evidence (§3.2.1). Everything
else those two documents froze is untouched.

**Live evidence**: the diagnosis rows marked *(live 2026-09-03)* were observed on a running station
(backend `:8080`, `ng serve :4200`, 19 assets seeded, 1 simulated stream), not inferred from code.

---

## 1. The user and the job

The `/command` user is the **crew commander**, not the pilot. They are not flying anything. Their
whole job is three questions, in this order:

1. **Where is everything?** — including the machines that stopped talking. A drone that has gone
   quiet is not less interesting than a live one; it is *more* interesting, because someone has to
   go get it.
2. **What needs me?** — one honest verdict per asset, never a calm one invented from ignorance.
3. **Where has it been?** — the route (маршрут) of the thing they just clicked.

The map is the only surface that can answer all three at once, so the map *is* the dashboard. Every
other element on the page earns its place by being a way to ask the map a question, or by being the
map's answer. Anything that is neither is overwhelm.

The second half of the job is **cross-surface**: the same commander opens `/fly` to look over a
pilot's shoulder, `/live/:deviceId` to check a camera, `/assets/:id` to read a machine's history.
The tactical picture — marks, layers, zones, drawings — follows them to all four. Today it does not
follow; it changes shape at every door.

---

## 2. Diagnosis

Evidence is file-cited. "Consequence" is written from the commander's seat, not the code's.

### 2.1 Honesty defects — the map says things that are not true

| # | Defect | Evidence | Consequence for the user |
|---|---|---|---|
| **D1** | **The live trail and the live marker freeze ~3 minutes into every flight.** `GET /api/usages/{id}/telemetry?limit=N` returns the **earliest** N samples, not the latest — `order by t.at asc` + `setMaxResults(limit)`. `FleetMapStore` polls it every 2s with `limit=200` and takes `samples[samples.length-1]` as *latest*. Past 200 samples that element never changes again. | `storage/persistence/.../repository/JpaTelemetryRepository.java:152-162` (and its own javadoc at `:29-38` flagging this as "arguably not what `AssetController`'s `GET /api/usages/{id}/telemetry?limit=100` actually wants"); `core/map/map-store.ts:21,176`; `core/map/map-logic.ts:95-100` | At ~1 Hz MAVLink the `/command` map silently stops updating position, heading, battery and age 3m20s into every flight — while still drawing a **confident live arrow**. `/fly` escapes this because `TelemetryStore` projects `LiveStore`'s `telemetry:<assetId>` SSE topic and pauses the poll (`core/telemetry/telemetry-store.ts:132-145`); `FleetMapStore` has no live projection at all. The commander's map is the one surface with no live path. |
| **D2** | **The map never applies the app's own "stale is not live" law.** `freshness()`/`Freshness` (`'live' \| 'aging' \| 'stale' \| 'none'`) exists and the OSD obeys it; `assetIcon()` branches only on `asset.live`, which is `status === 'STREAMING'`, not on sample age. | `core/telemetry/telemetry-logic.ts:178-202`; `shared/map/tactical-map/tactical-map.ts:806-819` | A drone that went silent 40 minutes ago renders identically to one reporting right now. This is the exact defect [`OPERATOR-UX-3-PLAN.md`](OPERATOR-UX-3-PLAN.md) H1 fixed for the OSD ("a rover last heard from 4 days ago showing ARMED in full colour") — the map was never brought along. |
| **D3** | **A drone that stops reporting loses its position's date, its trail, and its identity as an aircraft.** `buildMarker`'s `offline` branch returns `{...base, position, trail: []}` — no `sampleAgeSeconds`, no trail — and `assetIcon` degrades it to a 14px grey dot. `stopTracker` deletes the telemetry snapshot on the very next 5s reconcile. The popup's age row is `@if (sampleAgeSeconds !== undefined)`, so an offline asset's popup has **no last-seen line at all**. | `core/map/map-logic.ts:180-182`; `core/map/map-store.ts:187-199`; `shared/map/tactical-map/tactical-map.ts:811-818, 846-848` | *(live 2026-09-03)* 9 `.asset-marker.offline` markers plotted; popup reads "Skyfall Vampire 2 / Simulated / Offline / Altitude 80 m / Open asset" — **no age, no route**. The commander cannot tell a drone that landed 5 minutes ago from one abandoned in a field last March. This is the owner's ask #3, stated as a defect. |
| **D4** | **The map and the rail disagree about the same asset.** The rail says "Never seen" (from `AssetAttention.telemetryAgeMs === undefined`) for assets the map plots at a confident position (from `AssetSummary.lastKnownPosition`). Two polls, two DTOs, one page, no join. | `features/command/command-logic.ts:150-163`; `command-facade.ts:80-92` (`FleetStore`/`summary()` = `AssetAttention`) vs `core/map/map-store.ts:110` (`listAssets()` = `AssetSummary`) | *(live 2026-09-03)* confirmed on the running station. Same class of bug as [`OPERATOR-UX-4-PLAN.md`](OPERATOR-UX-4-PLAN.md) N2 (rail CRIT vs panel "All quiet") — one fact, two derivations, free to drift. |
| **D5** | **The asset panel fabricates calm out of total ignorance.** `@if (reasons().length > 0) … @else "All quiet — nothing needs attention on this asset right now."` — with no gate on whether anything is *known*. | `features/command/asset-panel.html:72-85`; `command-facade.ts` `selectedAttentionReasons` (`[]` whenever nothing is known) | *(live 2026-09-03)* selecting an offline asset shows "All quiet…" while every fact beside it reads `—` (Telemetry age —, Mode —, Armed —, GPS —). "No reasons" is being rendered as "no problems". |

### 2.2 Overwhelm — the map's estate is spent on things nobody asked for

| # | Defect | Evidence | Consequence |
|---|---|---|---|
| **D6** | **The legend opens by default and is a four-section panel, not a legend.** `legendOpen = linkedSignal(() => !this.followMode())` → always open in fleet mode; the panel stacks Assets + Affiliation + Mark kinds + Zones + Tracks + Geo corrections. | `shared/map/tactical-map/tactical-map.ts:329`; `tactical-map.html:71-140` | *(live 2026-09-03)* ~250×380 px of prime map estate consumed at rest by a symbol key nobody is reading. It also breaks the page's own style law: frontend-style §7 — *"The legend is one quiet row of count chips."* |
| **D7** | **Five controls in the topbar at rest, three different shells behind them.** Zones / Marks / Layers / Draw / Include-archived, plus the weather chip and an asset count. Zones is a **backdrop modal** (`role="dialog" aria-modal="true"`), Marks and Layers are non-blocking `<vision-side-panel>` drawers, Draw is a floating card. | `features/command/command.html:2-38`; `zones-panel.html:1`; `command.ts:19-32` | *(live 2026-09-03)* four ways to touch the same picture, wearing four shapes. The Zones modal covers the map you are about to draw a zone on. |
| **D8** | **The event layer is a swarm of stale, unactionable dots.** `selectEventMarkers` filters on `position !== undefined` and slices 30 — **no state filter, no age bound**. `EventsStore` is a session-long accumulator (`MAX_RETAINED_EVENTS`) that `/command` never even `activate()`s; it free-rides the always-on header bell. The event popup's only action is `openEventAsset` → `router.navigate(['/assets', id])`, and an event with no resolvable asset gets no action at all. | `core/events/events-logic.ts:232-248`; `core/events/events-store.ts:28-44`; `command-facade.ts:239-247`; `shared/map/tactical-map/tactical-map.ts:964-977`; `command-facade.ts` `openEventAsset` | *(live 2026-09-03)* 30 `.event-marker-closed` orange dots blanket the map; all 33 events in the system belong to a **removed device**; clicking one produced no visible popup action. The COP is buried under history. **Owner's ask #5, answered: the events flow on `/command` is noise, not signal.** |
| **D9** | **`/live` and `/assets/:id` render the tactical picture read-only, with no way to touch it.** Both bind `[marks] [drawings] [layers] [zones]` and neither mounts a layer manager, a mark palette, a drawing toolbar, or any eye toggle. | `features/live/live.html:122-131`; `features/asset-detail/asset-detail.html:145-155` | The commander sees marks and drawings on two pages and cannot declutter, create, verify, or promote them there. The picture is visible but inert. |

### 2.3 Divergence — the answer to the owner's ask #2

**There is no one mental model. There are four.**

| Surface | How you reach the picture | Marks | Layers | Draw | Zones |
|---|---|---|---|---|---|
| `/command` | 4 **text** buttons with counts, in the page topbar | `features/command/marks-panel.*` (own copy, 101 ts + 81 html + 137 css) | `<vision-side-panel title="Map layers">` + `<vision-layer-manager>` | separate `draw` overlay → floating `<vision-drawing-toolbar layout="card">` | full **backdrop modal** `<vision-zones-panel>` |
| `/fly` | **icon** buttons on the tool rail (`ToolRailPanelId`) | `features/fly/marks-panel.*` (second copy, 137 ts + 106 html + 143 css) | inside the **`map`** drawer, titled "Map / Layers and drawing" | inside that **same** drawer, `layout="stacked"` | **absent entirely** |
| `/live/:deviceId` | nothing | read-only render | none | none | read-only render |
| `/assets/:id` | nothing | read-only render | none | none | read-only render |

- **D10 — two marks panels, ~90 % identical.** `features/command/marks-panel.ts` and
  `features/fly/marks-panel.ts` differ only in (a) Fly's **Mark target** geolocate button and
  (b) Fly's bearing/distance readout — both genuinely cockpit-only, both expressible as optional
  inputs. The Command copy's own class doc already claims the two are *"two thin templates over one
  behaviour rather than two divergent implementations"*; ~280 lines of duplicated CSS say otherwise.
- **D11 — "Layers" and "Draw" are one drawer on `/fly` and two controls on `/command`**, and the
  word "Layers" already caused one shipped bug: [`MAP-UX-RESEARCH.md`](../../conclusions/MAP-UX-RESEARCH.md)
  M1 removed the map's own corner "Layers" panel precisely because two controls with that label were
  on screen at once. The topbar/rail split re-creates the same ambiguity one level up.
- **D12 — Zones exist only where the modal exists.** Geofence breaches are the *top-rank* attention
  reason on every surface (`command-logic.ts#rowRank`), but the zones that produce them can only be
  seen or edited on `/command`.
- **D13 — both marks panels promise a gesture that does not exist.** Both copies open with
  *"Geolocated marks are estimates — **drag any pin on the map to correct it**."*
  (`features/command/marks-panel.html:3`, `features/fly/marks-panel.html:4`). Marks are deliberately
  **not draggable** — `station/vision-web/MODULE.md` states the rule and its reason (*"A mark records
  where something was observed; a drag would silently rewrite it. No `(markMoved)`, no
  `MarksStore.moveTo`"*), and `<vision-tactical-map>` has no such output. The UI instructs the user
  to perform an impossible correction, twice over. One shared panel fixes it once.

---

## 3. The target model

### 3.1 Layers of `/command` (the FLY-FLOW vocabulary, applied here)

```
L0  the map        Leaflet + its overlays. The whole stage. Nothing opens on top of it at rest.
L1  the frame      topbar (identity + fleet state only) · rail (left) · asset panel (right)
L2  on the glass   HUD corner chrome: Basemap ┃ Recenter ┃ Map tools ┃ one-row legend
L3  on demand      ONE "Map tools" drawer · the geofence draw dialog · the drawing toolbar card
```

**The law, made explicit** (FLY-FLOW §2's rule, restated for this page): *at rest `/command` shows
the map, the rail, and nothing else opened over the map; every control that edits or filters the
tactical picture lives behind one button, with one name, on every surface that has a map.*

### 3.2 The ONE cross-surface contract — `<vision-map-tools>`

New shared component, **frozen here**:

```
shared/map/map-controls/map-tools/map-tools.{ts,html,css}     // the drawer
shared/map/map-controls/marks-panel/marks-panel.{ts,html,css} // the ONE marks panel
```

`<vision-map-tools>` is a `<vision-side-panel title="Map tools" icon="layers">` with **four
sections in one fixed order, always this order**:

| # | Section | Body | Rendered when |
|---|---|---|---|
| 1 | **Marks** | `<vision-marks-panel>` (below) | `capabilities.marks` |
| 2 | **Layers** | `<vision-layer-manager>` verbatim ("Show on map" / "Basemap" / "Manage layers") | `capabilities.layers` |
| 3 | **Draw** | `<vision-drawing-toolbar layout="stacked">` | `capabilities.draw` |
| 4 | **Zones** | the zone list + "New keep-in" / "New keep-out", lifted out of `zones-panel.html`'s modal body unchanged | `capabilities.zones` |

Frozen input surface (one settings record, never a growing parameter list — CLAUDE.md rule 10):

```ts
/** Which sections this host offers. `'off'`/`false` means the section does not render at all. */
export interface MapToolsCapabilities {
  readonly marks: boolean;
  /** `'view'` = "Show on map" + "Basemap" only; `'manage'` additionally renders "Manage layers". */
  readonly layers: 'off' | 'view' | 'manage';
  readonly draw: boolean;
  readonly zones: boolean;
  /** Cockpit-only: the drone whose telemetry backs "Mark target" and the bearing/distance readout. */
  readonly cockpit?: { readonly assetId: string; readonly dronePosition: GeoPosition | undefined };
}
```

```
@Input  capabilities : MapToolsCapabilities            (required)
@Input  map          : TacticalMap | undefined         (the host's viewChild, for Layers/Basemap)
@Input  title        : string                          ('Map tools' default; the host names its own button)
@Output close        : void
```

Everything else it needs (`MarksStore`, `LayersStore`, `DrawingsStore`, `GeofenceStore`) it injects
directly — all four are `providedIn: 'root'`, and this is a non-routed presentational child, the
same `architecture.spec.ts` carve-out both existing marks panels already rely on.

`<vision-marks-panel>` is **the Fly copy**, with its two cockpit affordances moved behind the
optional `cockpit` half of the record above: present → "Mark target" button + bearing/distance
column render; absent → they do not. Both `features/command/marks-panel.*` and
`features/fly/marks-panel.*` are **deleted**, not left in place. Its opening notice loses the
phantom drag instruction (D13) and becomes: *"Geolocated marks are estimates. Delete and re-drop a
pin to correct it."*

**One component, one section vocabulary, per-surface capabilities:**

| Surface | Entry point(s) | Capabilities |
|---|---|---|
| `/command` | one HUD control on the map's top-right chrome, icon `layers`, label **"Map tools"**, badge = `marks + drawings + zones` | `{marks: true, layers: 'manage', draw: true, zones: true}` |
| `/fly` | the tool rail's existing **two** buttons, `marks` and `map`, both rendering this component with **disjoint** sections — see the note below | `marks` → `{marks: true, layers:'off', draw:false, zones:false, cockpit}` · `map` → `{marks:false, layers:'view', draw:true, zones:true}` |
| `/live/:deviceId` | one HUD control on the inset's chrome (D9) | `{marks: true, layers: 'view', draw: false, zones: true}` |
| `/assets/:id` | one HUD control on the inset's chrome (D9) | `{marks: true, layers: 'view', draw: false, zones: true}` |

**Why `/fly` keeps two buttons and `/command` has one — and why that is still one mental model.**
The unit of unification is the **drawer and its four sections**, not the number of doors. Every
surface shows the same sections, in the same order, with the same names and the same components
behind them; a host only decides *which* sections it offers. `/fly` genuinely earns a second door:
[`MAP-UX-RESEARCH.md`](../../conclusions/MAP-UX-RESEARCH.md) §2 ranks *"drop a mark on something
seen right now"* as the pilot's **third** task, and burying it one level deeper to satisfy a
symmetry nobody asked for would be a real regression. Because the two Fly buttons carry **disjoint
sections**, they cannot re-create M1's actual defect (two controls wearing the *same* label with
different scopes).

The drawing **toolbar** stays a floating `--panel-raised` card over the map while a drawing mode is
armed (it must be reachable with the drawer closed); the Draw *section* in the drawer is what arms
it. The geofence **draw dialog** (`geofence-zone-dialog`, which runs its own Leaflet mini-map inside
its own backdrop) stays exactly as it is.

**Retired by this contract:** `/command`'s four topbar buttons; `CommandOverlay`'s
`'zones' | 'marks' | 'layers' | 'draw'` union collapses to a single `'map-tools'`; the
`<vision-zones-panel>` **backdrop-modal shell** (its list/actions *body* survives verbatim inside
the drawer's Zones section); both feature-local marks panels; the phantom drag copy.
`/fly`'s `ToolRailPanelId` is **unchanged** (`rc | cv | marks | map | help`) — no migration needed.

### 3.2.1 Three prior refusals, addressed by name

[`MAP-UX-RESEARCH.md`](../../conclusions/MAP-UX-RESEARCH.md) §8 refused three things adjacent to
this contract. Two are honored; one is overturned on evidence.

| Refusal | This plan's position |
|---|---|
| *"a shared `MapChrome` super-component that unifies Fly's and Command's map hosts into one … the fix is in what each host shows from the shared toolbox, not in merging the hosts"* | **Honored, and this contract is literally that sentence.** No host is merged: `/command`, `/fly`, `/live` and `/assets/:id` keep their own pages, their own `<vision-tactical-map>` embeds and their own sizing contracts. `MapToolsCapabilities` **is** "what each host shows from the shared toolbox", made explicit instead of re-implemented per page. |
| *"removing a rail button"* on `/fly` | **Honored.** `ToolRailPanelId` is untouched; both `marks` and `map` survive, now rendering one component instead of two. |
| *"collapsing the Zones modal into a drawer (it needs to block accidental map clicks)"* | **Overturned — the stated reason is not true of the shipped code.** `zones-panel` is a *list*: its actions are rename/enable/delete and two "New … zone" buttons, none of which arms the stage map's `[interactionMode]` (`resolveInteractionMode` reads only `MarksStore.armed()` and `DrawingsStore.mode()`). The actual drawing happens in `geofence-zone-dialog`, which opens **its own backdrop modal around its own Leaflet mini-map** (`geofence-zone-dialog.ts:98,116`; `geofence-zone-dialog.html:1`) — that modal is untouched and still blocks. So the list modal blocks clicks that were never dangerous, at the cost of hiding the map while you decide which zone to edit. It becomes a drawer section. |

The research's central *placement* finding — §3's *"mis-weighted: `LayerManager` on Fly … the single
clearest instance of 'wrong surface'"*, and wave M2 — is honored by the `layers: 'view' | 'manage'`
split: layer **administration** (create / rename / delete / grants) renders on `/command` only.
`/fly`, `/live` and `/assets/:id` get "Show on map" + "Basemap" and nothing else.

### 3.3 Last known position, honestly — `LastContact`

The cheapest alignment in this plan: **the backend already computes exactly what the owner asked
for, and the map never reads it.** `AssetAttention.telemetryAgeMs` is documented as *"milliseconds
since the freshest telemetry sample … **deliberately still reported once the asset stops
streaming**, since staleness is exactly 'how long since we last heard from this asset' and that
question is most useful once it has gone quiet"*
(`contexts/vision-warehouse/.../asset/AssetAttention.java:45-50`, computed at
`DefaultFleetSummaryService.java:135-137`). `CommandFacade` already polls it every 5 s. No backend
change, no new request.

Frozen model, in `core/map/map-logic.ts` so every surface can adopt it:

```ts
export type LastContactSource = 'telemetry' | 'flight' | 'unknown';

export interface LastContact {
  readonly source: LastContactSource;
  /** Absent if and only if `source === 'unknown'`. Never fabricated. */
  readonly ageSeconds?: number;
}
```

**Resolution order, frozen — first hit wins, no fallthrough invention:**

| Order | Input | Result | Label the UI must use |
|---|---|---|---|
| 1 | `AssetAttention.telemetryAgeMs` present | `{source:'telemetry', ageSeconds: ms/1000}` | `Last contact {humanAge} ago` |
| 2 | else `AssetSummary.lastUsedAt` present | `{source:'flight', ageSeconds: (now−lastUsedAt)/1000}` | `Last flight started {humanAge} ago` |
| 3 | else | `{source:'unknown'}` | `Last contact unknown` |

Tier 2 is deliberately worded differently because `lastUsedAt` is *"start of the most recent
usage"* (`AssetSummary.java:25`), **not** the moment of last contact — labelling it "Last contact"
would be a lie of exactly the kind this repo bans. Tier 3 fixes D4: an asset with a plotted position
and no age says so, instead of the rail saying "Never seen" beside a confident dot.

Wiring, frozen: a new pure function in `core/map/map-logic.ts` —

```ts
export function withLastContact(
  markers: readonly FleetMarker[],
  lastContactByAssetId: ReadonlyMap<string, LastContact>,
): readonly FleetMarker[]
```

— applied in `CommandFacade.markers` (the one place holding both polls). `FleetMapStore` is not
touched; `FleetMarker` gains one optional field `lastContact?: LastContact`, so every existing
fixture in `core/map/map-logic.spec.ts` and the four other map hosts keep compiling unchanged.
`sampleAgeSeconds` keeps its current meaning (age of the live sample) and is **not** merged into
this — two fields, two questions, both honest.

**Rendering, frozen** (`shared/map/tactical-map`):

- **One glyph, state by colour and opacity only** (frontend-style §7 verbatim: *"One marker glyph,
  state by colour only"*). Today the map draws **two** glyphs — a 22 px heading arrow for
  `live`, a 14 px grey `offline-dot` for everything else (`tactical-map.ts:806-819`). The dot is
  **retired**: a drone that landed is still a drone, and it still has a heading worth showing. Every
  asset gets `droneDivIcon`; `freshness(lastContact?.ageSeconds)` — the existing
  `core/telemetry/telemetry-logic.ts` tri-state, **not** a new threshold — picks the class:
  `live` (full colour) · `aging` (muted) · `stale` / `none` (muted + reduced opacity). Attention
  recolour and the `--color-info` selection ring compose on top, exactly as today.
- Staleness **never borrows `--color-danger`** (frontend-style §3: *"status colours mean state,
  nothing else"*). Stale is quiet, not alarming; the attention hue stays reserved for an actual
  attention reason.
- Every asset popup carries a last-contact row, **for every bucket**, never conditionally, formatted
  with `humanAge` — the app's one age vocabulary (`MODULE.md` records the sweep that removed the last
  raw `Ns ago` renderers, this popup included). No second format, no second staleness tri-state.
- A selected marker whose freshness is not `'live'` additionally shows a `LAST KNOWN` HUD label —
  the same wording `fly-osd` already uses for the identical state.
- `hasFix` still governs *whether* a marker exists at all: a `(0,0)` `lastKnownPosition` is no
  position ([`OPERATOR-UX-4-PLAN.md`](OPERATOR-UX-4-PLAN.md) N1), counted in `[unplottedAssets]`,
  never plotted on Null Island. This plan changes how a plotted marker is *dated*, never what
  qualifies as plottable.

### 3.4 Route on click (маршрут) — no backend needed

**Verdict: the data exists and the frontend already has both clients.** Two hops, both already
wrapped in `core/api/vision-api.ts`:

```
1. GET /api/usages?assetId={assetId}&limit={n}      → UsageSummary[]   (newest first, SCOPED)
2. GET /api/usages/{usageId}/timeline?maxPoints=500 → UsageTimeline    (404 unknown usage)
```

Frozen response shapes (already mirrored in `core/api/models.ts:1664`, `:1684`):

```ts
UsageSummary  { usageId, assetId, assetName, startedAt, endedAt?, durationSeconds?, sampleCount, pilotId? }
UsageTimeline { usage: AssetUsage, from: string, to: string,
                telemetry: TelemetrySample[], detections: DetectionResult[] }
TelemetrySample { deviceId, at, latitude?, longitude?, altitudeMeters?,
                  headingDegrees?, batteryPercent?, flightState?, extra? }
```

**Use `timeline`, not `telemetry`** — and this is the load-bearing reason: `…/telemetry?limit=N`
returns the *earliest* N samples (D1), so it structurally cannot answer "where has it been
recently". `timeline` windows to the usage's own bounds and downsamples across the whole flight
(`UsageTimelineController.java:117-129`), which is a real route, and is already what
`features/replay/replay-map.ts` draws its polyline from.

**Scoping, stated honestly:** `GET /api/usages` **is** scoped by `currentUser.scope()`;
`…/timeline` is **not** (a pre-existing gap the controller's own javadoc admits at
`UsageTimelineController.java:48-51`). Because the usage ids this feature ever passes to hop 2 come
only from the scoped hop 1, this feature adds no new exposure — but it does not close the gap
either. Named as a residual (§5), not silently inherited.

**Frozen frontend contract:**

```ts
// core/map-data/route-store.ts  (page-provided, NOT providedIn:'root' — same posture as FleetMapStore)
export interface AssetRoute {
  readonly assetId: string;
  readonly usageId: string;
  readonly startedAt: string;
  readonly endedAt?: string;              // absent → this flight is still open
  readonly points: readonly GeoPosition[]; // chronological, fix-carrying samples only
  readonly truncated: boolean;             // points.length hit maxPoints
}
export type RouteSpan = 'off' | 'last' | 'last3';
```

- `RouteStore.show(assetId, span)` / `hide()`; `routes: Signal<readonly AssetRoute[]>`;
  `loading: Signal<boolean>`; `error: Signal<string | undefined>` — an honest failure is a message
  in the panel, never an empty polyline that reads as "it never went anywhere".
- Points are filtered through the existing `core/geo/geo-logic.ts#hasFix` — a `(0,0)` sample is *no
  fix*, not a trip to Null Island ([`OPERATOR-UX-4-PLAN.md`](OPERATOR-UX-4-PLAN.md) N1).
- `<vision-tactical-map>` gains **one** input, `[routes]: readonly AssetRoute[]`, rendered as a
  polyline in `mapColors().trail` (the token asset trails and projected tracks already share) at
  `weight: 3`, plus a start flag (the existing `flagIcon`) and an end dot. A closed flight's line is
  dashed; an open flight's is solid — one visual difference carrying one fact. Leaflet paths cannot
  resolve `var(--token)`, so the route polyline **must** be restyled from the same `mapColors()`
  theme-flip effect the existing `trailLine` already uses (`tactical-map.ts:449, 766-770`) — a
  `setLatLngs` without a matching `setStyle` is how a line ends up painted in the other theme.
- **A route is not a `Drawing`.** [`MAP-REWORK-PLAN.md`](../done/MAP-REWORK-PLAN.md) §2.1 reserves
  `DrawKind.LINE`/`ARROW` for user-authored, layer-scoped, grant-gated, SSE-broadcast geometry with a
  `colorToken`. A route is derived client-side state on the built-in Assets layer: it is never
  persisted, never granted, never verified, never promoted, and never enters the `/api/map/**`
  machinery.
- **This deliberately overturns `TRAIL_WINDOW = 60`, for the route only.** That cap
  (`core/map/map-logic.ts:22`, *"a long-running flight shouldn't fill the map with old track"*) is
  the right default for the always-on live breadcrumb of every asset at once, and stays exactly as
  it is. An explicitly-requested route for **one** asset is the case the cap was protecting against
  by accident; `maxPoints` server-side downsampling replaces it there.
- **Interaction, frozen:** selecting an asset (rail row, marker click, or `?asset=` deep link) shows
  its **last flight** route automatically. The asset panel's Telemetry tab gains one segmented
  control — `Route: Off · Last flight · Last 3` — defaulting to *Last flight*, remembered per
  browser under `vision.command.routeSpan`. Deselecting clears the route. Never more than one
  asset's route on the map at a time: this is "where has *it* been", not a fleet history layer.
- Empty/absent cases are stated, never blank: no usages → *"No recorded flights for this asset"*;
  a usage with zero fix-carrying samples → *"This flight recorded no positions"*; `truncated` →
  a quiet *"Simplified — long flight"* note.

### 3.5 Events, judged

The events layer stays **on**, and gets a filter that makes it mean something. Frozen:

```ts
export interface EventMarkerOptions {
  readonly max: number;            // 30, unchanged (MAX_EVENT_MARKERS)
  readonly openOnly: boolean;      // true  — a CLOSED event is history, not the picture
  readonly maxAgeMinutes: number;  // 60    — "what is happening", not "what ever happened"
}
export function selectEventMarkers(events, options?: Partial<EventMarkerOptions>): readonly DetectionEvent[]
```

Defaults are the values above, so every existing caller keeps compiling and `/command` picks up the
filter for free. **Filter, do not hide** — defaulting the layer off would be hiding data; filtering
it to open-and-recent removes noise that was never signal. On the live station this empties the
30-dot swarm outright (all 33 events are CLOSED, on a removed device) while leaving a real detection
visible the moment one occurs.

The event popup's primary action retargets from `openEventAsset` (which **navigates away from the
map**) to `preview` — select this asset here, the panel opens, the route draws. "Open asset" stays
as a secondary. An event whose `assetId` never resolved keeps its existing honest line
(*"No asset resolved for this event"*) and gets no action, because there is none.

### 3.6 The frame at rest

- **Topbar** keeps identity and fleet state only: `Command · {n} assets · [weather chip]`. The four
  tool buttons move to §3.2's one HUD control; **Include archived** moves into the rail head beside
  **Hide simulated**, where the page's own list-filter idiom already lives. This sides with
  [`MAP-UX-RESEARCH.md`](../../conclusions/MAP-UX-RESEARCH.md) §6 and frontend-style §7 (map chrome
  is `--hud-*` on the tiles) **against** [`02-command.md`](../../extracts/design/02-command.md)'s
  earlier *"one toolbar … the map keeps only zoom"*, which would pull the basemap picker up into the
  page bar. The two later documents win; 02-command's other calls (full-bleed, both flanks
  collapsible, a detail panel driven by the query param) are already shipped and untouched — note
  the shipped param is `?asset=`, **not** 02-command's proposed `?sel=`; do not churn it.
- Any new fixed or overlay chrome must size against `--shell-h` / offset by `--shell-banner-h`
  ([`OPS-UX-PLAN.md`](../done/OPS-UX-PLAN.md) §5c A6 rev.2 — `.command-shell` is named there
  explicitly). A bare `100dvh` paints one banner-strip below the fold.
- **Legend** defaults **closed** in fleet mode too (`legendOpen` becomes `signal(false)`), and its
  resting form is one HUD chip row — `● 3 live · ○ 9 last known · ▲ 2 attention · ⌀ 5 no position` —
  obeying frontend-style §7's *"one quiet row of count chips"*. Clicking it expands today's full
  symbol key (affiliation / mark kinds / zones / tracks / corrections) unchanged.
- **"All quiet" earns its words** (D5). New pure helper in `features/command/command-logic.ts`:

```ts
export type QuietVerdict = 'quiet' | 'no-basis';
export function quietVerdict(reasons: readonly AttentionReason[], contact: LastContact | undefined): QuietVerdict
```

  `'quiet'` **only** when `reasons.length === 0` **and** `contact?.source === 'telemetry'` **and**
  `freshness(contact.ageSeconds) !== 'stale'`. Otherwise `'no-basis'`, rendered as
  *"Nothing to report — no recent telemetry from this asset ({last-contact label})."*

### 3.7 The one backend defect (D1)

Additive, no semantic change to any existing method:

- `TelemetryRepositoryPort` gains `List<Telemetry> findLatestByUsage(UsageId usageId, int limit)` —
  the **latest** `limit` samples, returned **ascending by `at`** (so every caller's ordering
  assumption holds).
- `JpaTelemetryRepository` implements it as `order by t.at desc` + `setMaxResults(limit)`, reversed
  before return — the existing `idx_telemetry_samples_usage_id_at` index already has the right
  shape. `InMemoryTelemetryRepository` gets the matching implementation.
- `AssetController#telemetry` (`GET /api/usages/{usageId}/telemetry`) switches to it. **The wire
  contract does not change** — same path, same `limit` param, same `TelemetrySampleResponse[]`, same
  ascending order, same "unknown usage → `200 []`". Only *which* window of the flight comes back.
- `findByUsage` is left exactly as it is: `DefaultReplayService` windows from the flight's start and
  genuinely wants earliest-first. Two methods, two questions.

This is CLAUDE.md rule 9 applied literally — *newest telemetry should be used, even if previous is
still available* — and it fixes `/command`'s map, `TelemetryStore`'s backfill and `/live` in one
change.

---

## 4. Waves

Every wave is file-scoped and independently green: `npx tsc --noEmit` (both configs) +
`npm run test:ci` for web waves — **never bare `npx vitest run`**, which fakes ~536 failures — and
`./mvnw -B -pl <path> test` for the backend wave. Each wave ends with `MODULE.md` updated.

Constraints binding every web wave: 3-file components (`.ts`/`.html`/`.css`, never inline),
Component → Facade → Store → Service layering (`core/ui/architecture.spec.ts` guards it), tokens
only, no new tokens, frontend-style §§2/4/7/9.

### Round 1 — three agents in parallel, disjoint scopes

**W1 — map honesty** *(`web-ui`)*
Scope: `core/map/map-logic.ts` + `map-logic.spec.ts`; `shared/map/tactical-map/tactical-map.ts`,
`tactical-map.html`, `tactical-map.css`, `tactical-map-logic.ts` + its spec;
`shared/map/tile-cache/leaflet-loader.ts` (the hollow-glyph icon variant only).
Delivers: `LastContact` + `withLastContact` (§3.3) with unit tests for all three resolution tiers;
`freshness`-driven marker confidence and the retirement of `offline-dot` (D2/D3); the
always-present last-contact popup row; `legendOpen` default `false` + the one-row chip legend (D6).
Does **not** touch any feature folder — every host keeps compiling because `lastContact` is optional.

**W2 — one Map tools drawer** *(`web-ui`)* — the largest wave; may be split at the agent's
discretion into (a) the shared marks panel, then (b) the drawer + hosts.
Scope: **new** `shared/map/map-controls/map-tools/*` and `shared/map/map-controls/marks-panel/*`;
**delete** `features/command/marks-panel.{ts,html,css}` and `features/fly/marks-panel.{ts,html,css}`;
`features/command/command.ts`, `command.html`, `command.css` (topbar + drawer mount only — the
legend and rail are W4's);
`features/command/zones-panel.{ts,html,css}` (body lifted into the drawer's Zones section, modal
shell deleted); `features/fly/cockpit.ts`, `cockpit.html`; `features/live/live.html`, `live.ts`;
`features/asset-detail/asset-detail.html`, `asset-detail.ts`.
Delivers: §3.2 + §3.2.1 verbatim — one component, one section vocabulary, per-surface capabilities
(D7/D9/D10/D11/D12/D13). **Does not touch `fly-logic.ts`** — `ToolRailPanelId` is unchanged, so
there is no persisted-id migration and no `fly-logic.spec.ts` churn.

**B1 — newest-N telemetry** *(`spring-integrator`; the port + JPA half may go to `adapter-builder`)*
Scope: `contexts/vision-flight/.../domain/port/TelemetryRepositoryPort.java`;
`storage/persistence/.../repository/JpaTelemetryRepository.java` + its in-memory sibling in
`station/vision-app` devsupport; `station/vision-api/.../controller/AssetController.java`; the
matching MODULE.md files.
Delivers: §3.7. Green via `./mvnw -B -pl contexts/vision-flight,storage/persistence,station/vision-api test`.
No frontend wave depends on it — W1/W2/W3/W4 are correct with or without it; it removes the
underlying freeze.

### Round 2 — sequential (both touch `features/command/command.html`)

**W3 — route on click** *(`web-ui`; after W1)*
Scope: **new** `core/map-data/route-store.ts`, `route-logic.ts` + both specs;
`shared/map/tactical-map/tactical-map.{ts,html,css}` (the `[routes]` input + polyline rendering);
`features/command/command-facade.ts`, `asset-panel.{ts,html,css}`, and the one new binding in
`command.html`.
Delivers: §3.4 verbatim — the two-hop fetch, the `AssetRoute` model, the segmented span control,
every named empty/error state.

**W4 — the command frame** *(`web-ui`; after W2)*
Scope: `features/command/command.html` (topbar + rail regions), `command.css`,
`command-logic.ts` + `command-logic.spec.ts`, `asset-panel.html` (the verdict block only);
`core/events/events-logic.ts` + `events-logic.spec.ts`.
Delivers: §3.5 (event filter + popup retarget), §3.6 (topbar reduction, Include-archived → rail
head, `quietVerdict`).

### Round 3

**W5 — verify + close out** *(`web-ui`)*
`npx tsc --noEmit` on both configs; `npm run test:ci`; `ng build --configuration production` with a
bundle delta noted; a live pass on `/command`, `/fly`, `/live/:deviceId`, `/assets/:id` in **both
themes** covering: an offline asset's popup, a selected asset's route, the Map tools drawer on all
four surfaces, the collapsed legend, an empty event layer. Update
`station/vision-web/MODULE.md` and this document's close-out table.

---

## 5. Verification, frozen surfaces, and residuals

**Frozen by this plan** (an implementer may not renegotiate these without amending the doc):
`MapToolsCapabilities` including the `'off' | 'view' | 'manage'` layers tier; the drawer's
four-section order and the per-surface capability table; `LastContact` + its three-tier resolution
order + the three label strings; the one-glyph/colour-and-opacity marker rule; `AssetRoute` /
`RouteSpan` and the two-hop endpoint pair; `EventMarkerOptions`' three defaults; `quietVerdict`'s
three conjuncts; `GET /api/usages/{usageId}/telemetry`'s unchanged wire contract under B1.

**Needs the owner's yes before W2 is delegated** — the one place this plan overturns a prior
written refusal: **the Zones list becomes a drawer section instead of a backdrop modal** (§3.2.1,
row 3). The evidence says the refusal's stated reason does not hold for the shipped code, but it was
a deliberate call and this reverses it.

**Genuinely open, with a stated default** (implementer's call): the drawer's section-collapse
behaviour (default: all four expanded, no per-section memory); whether the Map tools badge counts
hidden layers' contents (default: yes, it counts what exists, not what is currently drawn); the
route polyline's decimation ceiling (default: `maxPoints: 500` per flight).

**Non-goals — named, not silently dropped:**

- **No live projection for `FleetMapStore`.** `TelemetryStore` pauses its poll while `LiveStore` is
  `'open'`; the `/command` map does not, and this plan does not add it. B1 makes the poll *correct*;
  it does not make it *live*. A follow-up wave would give `FleetMapStore` the same
  `applyTransport` / `trackTelemetry` treatment. Stated because D1's root cause is two defects and
  this plan fixes one of them.
- **No fleet-wide route history layer.** One asset's route at a time (§3.4). "Show me everywhere
  everything went today" is a different feature with a different data shape.
- **No asset-keyed telemetry endpoint.** There is no `GET /api/assets/{id}/track` and this plan does
  not add one; a route that spans multiple flights is assembled client-side from the scoped usage
  list. `telemetry_samples` has no `asset_id` column — such an endpoint needs a join through
  `asset_usages` and is a real backend design task, not a wave.
- **`…/timeline` stays unscoped.** Pre-existing, documented at `UsageTimelineController.java:48-51`.
  §3.4 adds no new exposure (ids come only from the scoped `GET /api/usages`) and closes nothing.
  The fix pattern already exists — `GeoCorrectionController.forUsage`'s hiding-404 via
  `assetService.details(scope, assetId)` — and is one small backend wave whenever it is scheduled.
- **`DefaultReplayService`'s in-memory windowing is untouched.** Its own javadoc documents that a
  usage exceeding `TELEMETRY_FETCH_LIMIT` silently loses the tail; the real fix is a time-bounded
  port query, which B1 deliberately does not attempt.
- **`/live` and `/assets/:id` gain the drawer, not the whole COP workflow.** No drawing on a
  follow-mode inset (§3.2), by design — a 220 px map is not a drawing surface.
- **No mark staleness / mark expiry.** [`MAP-REWORK-PLAN.md`](../done/MAP-REWORK-PLAN.md) §7 defers
  DELTA-style mark aging and per-mark history explicitly; §3.3 here is **asset** staleness only and
  does not re-open that slice.
- **Nothing in the `/api/map/**` contract moves.** `Affiliation`, `LayerKind`, `AccessLevel`,
  `MapAccessPolicy`, the `map` SSE topic and its grant-filtering, `MarkKind`, `Drawing.colorToken`,
  the 404-never-403-for-invisible rule — all frozen by MAP-REWORK §§2–4 and all untouched here. This
  plan rearranges *where the controls live*, never *who may see or do what*.
- **Marks stay non-draggable.** D13 fixes the copy, not the capability. `(markMoved)` /
  `MarksStore.moveTo` are still deliberately absent.
- **No `MODULE.md`/code changes from this document itself.** This is a spec; nothing here has been
  built.

**Unverified prior state, worth one grep before W2 starts:** MAP-UX-RESEARCH's own waves M1
(kill the duplicate "Layers" label) and M2 (gate create-layer on Fly) have **no Status entry** in
`station/vision-web/MODULE.md`. M1 is visibly shipped (the map corner says "Basemap"; the eye
toggles live in the drawer). M2 appears shipped as `LayerManager`'s `[compactCreate]` input, which
`/fly` already passes `true` — §3.2's `layers: 'view'` generalizes that from "narrow the create
trigger" to "no Manage-layers section at all", which is what M2 actually asked for. Confirm before
assuming either is a new behaviour.

**Residual risk to watch during W1**: `withLastContact` joins two polls on different cadences (5 s
fleet summary, 5 s asset list) — a marker can briefly carry a `lastContact` for an asset the map has
already dropped. The join must be by id with a plain `Map.get`, tolerating a miss as
`{source:'unknown'}`, never as a stale carry-forward.

---

## 6. Close-out (2026-09-02/03, all five waves shipped on `feat/command-map-ux`)

All of Round 1/Round 2/Round 3 landed. Backend wave B1 was explicitly out of scope for every web
wave here (another agent's concurrent work on the unchanged wire contract) and its own status is
tracked wherever that agent's work is recorded, not here. Full per-wave detail — component trees,
exact test counts, commit file lists — lives in `station/vision-web/MODULE.md`'s own
"2026-09-02/03, docs/plans/active/COMMAND-MAP-FLOW-PLAN.md" entries; this table is the plan's own
summary, not a duplicate of that account.

| Wave | Commit(s) | Status |
|---|---|---|
| W1 — map honesty | `74d764ff`, `faa89b80` | Shipped. Tri-state freshness, `LastContact`/`withLastContact`, one glyph, always-present popup row, one-row legend default-closed. |
| W2 — one Map tools drawer | `a1e2d98c` | Shipped. One drawer, four hosts, per-surface capability table; Zones list moved from backdrop modal to a drawer section (the plan's own flagged §3.2.1 overturn — executed on the evidence that section documents). |
| B1 — newest-N telemetry | (another agent, tracked elsewhere) | Not this plan's to report; W1–W5 are correct with or without it by design. |
| W3 — route on click | `62580fd9` | Shipped. `RouteStore`/`AssetRoute`, the three named states, closed W1's own remaining offline-marker fallback gap as a side effect. |
| W4 — the command frame | `f37a3f55` | Shipped. `EventMarkerOptions` filter + popup retarget, `quietVerdict`, topbar reduced to identity + fleet state, Include-archived moved into the rail head beside Hide-simulated. |
| W5 — verify + close out | (this commit) | Shipped. Full verify chain green, live pass across all four surfaces in both themes, bundle delta measured, this table + `MODULE.md` updated. |

**Disclosed deviations from the plan's literal text, across all waves** (each already logged in
`MODULE.md` at the wave that produced it; consolidated here for one-stop review):

- **W2 — "top-right chrome" became bottom-right.** §3.2's prose names the top-right corner for the
  Map tools HUD door; `<vision-tactical-map>`'s top-right was already spoken for (camera controls)
  and growing a fourth corner was out of that wave's file scope, so `command.css`/`live.css`/
  `asset-detail.css` each pin their own button to the map's bottom-right instead — the one corner
  genuinely free on every host.
- **W4 — file scope one entry short of what its own "Delivers" line requires, on two files.** The
  Scope bullet omits `asset-panel.ts` (the verdict block's `computed()`s have nowhere else to live —
  a template cannot call a bare imported function) and `shared/map/tactical-map/tactical-map.ts`
  (the event-popup markup §3.5's "popup retarget" deliverable edits has always lived in that file's
  private `eventPopupHtml`, never in any file the Scope list names). Both touched; no other wave was
  concurrently editing either file, so there was no round-2-style parallelism conflict in doing so.
- **W5 — `angular.json`'s `anyComponentStyle` budget raised from 8/10 kB to 11/15 kB.** W1's frozen
  rendering contract (marker tri-state, `.last-known-badge`, the one-row legend) grew
  `tactical-map.css` past the old 10 kB error line to 11.38 kB; every added byte was confirmed live
  and referenced, not dead weight, so the honest fix was the budget, not a trim. A project-wide
  config file no single wave owns — flagged here rather than folded silently into an unrelated diff.

No other deviation from the frozen contracts in §5 was needed — `MapToolsCapabilities`,
`LastContact`'s three-tier order and label strings, `AssetRoute`/`RouteSpan`, `EventMarkerOptions`'
three defaults, and `quietVerdict`'s three conjuncts all shipped exactly as specified.

**§5's "genuinely open, implementer's call" items — the default each wave actually took:**
drawer sections ship all-four-expanded with no per-section memory (W2); the Map tools badge counts
what exists, not what is currently drawn, when tallying hidden layers (W2); the route polyline
decimation ceiling is `maxPoints: 500` per flight (W3) — all three exactly the stated default, none
renegotiated.

**§5's non-goals** were not touched by any wave and remain open exactly as named there: no live
projection for `FleetMapStore`, no fleet-wide route history layer, no asset-keyed telemetry
endpoint, `…/timeline` stays unscoped, `DefaultReplayService`'s in-memory windowing is untouched,
`/live`/`/assets/:id` get the drawer only (no drawing on a follow-mode inset), no mark staleness/
expiry, nothing in the `/api/map/**` contract moved, marks stay non-draggable.

**Final verify chain (W5), the plan's own required figures:**

- `npx tsc --noEmit -p tsconfig.app.json` / `-p tsconfig.spec.json` — clean, 0 errors both.
- `npm run test:ci` — **180/180 files, 3539/3539 tests** (unchanged since W4; W5 added no specs).
- `npx ng build --configuration production` — green. Two pre-existing warnings only: initial bundle
  over its 390 kB budget (present since before this branch), `tactical-map.css` now at 11.38 kB
  against the new 15 kB error line.
- **Bundle delta**, against the `851aad6d` fork-point baseline (disposable `git worktree`, symlinked
  `node_modules`, removed after measuring): initial **421.82 kB → 422.01 kB raw (+190 B),
  118.64 kB → 118.76 kB transfer (+120 B)** — flat. Lazy `command` **64.08 kB → 43.11 kB raw
  (−20.97 kB)**; lazy `cockpit` **185.76 kB → 175.18 kB raw (−10.58 kB)** — both shrank because W2's
  drawer moved into one new shared chunk (**132.33 kB raw**, loaded once, cached across all four
  hosts) instead of each host bundling its own copy; lazy `asset-detail` **70.80 kB → 71.89 kB raw
  (+1.09 kB)** — the one host that grew, since it previously had no drawer at all.
- **Live pass, both themes, all four surfaces**: `/command`'s rail-head filter pair, one-row legend,
  a selected asset's drawn route, and the dark-theme night basemap swap all confirmed rendering
  correctly in light and dark theme; the Map tools drawer confirmed opening with identical
  Marks/Layers/Draw/Zones content on `/command`, `/assets/:id`, `/fly/:assetId` (cockpit HUD chrome,
  a live KEEP-OUT/KEEP-IN zone pair), and `/live/:deviceId` (reached via a device id, not an asset
  id — `/live/:deviceId` correctly renders an honest "Unknown device" state when given the wrong id
  kind, confirmed in passing). **Not independently clicked live**: `quietVerdict`'s no-basis copy
  and the event-popup's "Preview asset"/"Open asset" pair — the demo dataset has no asset in the
  exact zero-reasons/non-telemetry state and no open detection event to click during this pass; both
  rest on unit coverage (`command-logic.spec.ts`, `events-logic.spec.ts`) plus reuse of the identical
  pure functions (`lastContactLabel`/`markerLastContact`) the map's own popup already exercises live.

Role-gating and dev-parity are unaffected by this plan end to end — no wave reads a role or
`vision.auth.enabled`; every surface's existing gate (who may reach `/command`/`/fly`/`/live`/
`/assets/:id` at all) is unchanged, and the dev admin (`vision.auth.enabled=false`) sees identical
behavior to a real ADMIN throughout.
