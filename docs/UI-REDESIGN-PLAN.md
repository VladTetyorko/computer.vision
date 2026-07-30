# UI-REDESIGN-PLAN — hub-and-spoke IA + a grid-based cockpit/asset overhaul

Status: **draft for review** (2026-07-30). Companion to [UX-REWORK-PLAN.md](UX-REWORK-PLAN.md)
(design-token doctrine, poka-yoke rules — still in force), [UI-STRUCTURE-PLAN.md](UI-STRUCTURE-PLAN.md)
(per-feature folders + `<name>.routes.ts` convention), [CV-CONTROL-PLAN.md](CV-CONTROL-PLAN.md)
(the just-shipped Vision/detection console, folded in here as an Operate spoke).

This is a **UI-first** overhaul of the `vision-web/` Angular 21 SPA. It restructures navigation into a
hub-and-spoke IA (three modes: **Operate / Monitor / Manage**), replaces the cockpit's fragile
absolute-positioned HUD with a CSS-grid overlay + a right-edge icon tool-rail driving shared drawers,
reshapes the asset page into "Overview + drill-in", and stands up the three requested addition bundles
— each addition scoped **honestly** as *functional-now* (backend already exists) or *UI-scaffold*
(skeleton page + honest empty-state, backend follow-up named). **No product code here; docs only.**

The plan writes no Java. Every backend endpoint it references already exists (verified below); the
only new backend work is named explicitly as deferred follow-ups per addition area.

## Goal, in the user's terms

> "Give me three clear places to be — flying one drone (Operate), watching the fleet (Monitor), and
> managing inventory (Manage) — each a launcher grid *and* a top-bar dropdown. Make the cockpit a
> clean video-hero with a tidy icon tool-rail on the right that opens one panel at a time, and
> grouped instrument clusters instead of scattered floating pills. Make the asset page a compact
> overview I can drill into. And build out the extra areas I asked for — but don't fake features that
> have no backend; show me an honest 'coming soon' where the data isn't there yet."

Made precise:
- Navigation is **hub-and-spoke**: a global top bar with three mode entries, each simultaneously a
  **routerLink to a hub launcher page** (responsive grid of labeled icon tiles) and a **`<details>`
  dropdown** of the same entries. Every current URL keeps resolving (aliases/redirects; no dead links).
- The cockpit's ten `position:absolute` `.hud-*` regions become **named CSS-grid areas**; a right
  tool-rail of `vision-icon-button`s opens `vision-side-panel` drawers via a **one-open-at-a-time**
  `PanelState` service. `cv-control-panel` and `flight-command-panel` migrate **into** the shared
  drawer shell, deleting their hand-rolled chrome + frosted-pill copies. Stop/Start/Disarm keep text
  labels (safety standard). HUD telemetry groups into Power/Nav/Link/Env clusters with disclosure.
- The asset page becomes **Overview + drill-in**: a compact landing (identity, KPI summary, live/
  position, recent flights) with heavy areas (full telemetry, usage history, hardware/devices,
  attribute editors, pilots) drilling into focused sub-views or side-panel drawers; the 9 hand-written
  `<section class="card"><header><h2>` blocks collapse onto a shared `vision-section-header`.
- All three addition bundles ship, each area classified functional-vs-scaffold with named follow-ups.

---

## Current state (honest, grounded in real files)

| Area | Today | Gap this plan closes |
|---|---|---|
| **Design tokens** (`src/styles.css`, 733 lines) | Surfaces/text/intent/geometry/fonts tokens; `--header-height:52px`. **No spacing scale** (ad-hoc `rem` everywhere), **no tokenized breakpoints** (shell `640px` vs asset `900px`, inconsistent), **no app grid** (`<main>` unstyled; each `.page` owns `max-width:1400px`). | Add `--space-1..8`, `--bp-sm/md/lg`, a documented 12-col grid + `.page` grid, and **one** frosted-pill utility. Keep every existing token. |
| **Icons** | None. Every "icon" is a Unicode glyph (`‹ › × ? ▾ ✓ ✕ ⋯`). Only inline SVG in the app is `notification-bell`. | Greenfield `<vision-icon name size>` inline-SVG registry (CSP-safe, `currentColor`). |
| **Shared chrome** | No `SectionHeader`/`SidePanel`/`IconButton`/`Drawer`. Frosted-pill recipe copy-pasted in **≥8 classes across 6 files** (`fly.css` `.icon-btn`/`.switcher-control`/`.ticker-row`; `cv-control-panel.css` `.cv-toggle`/`.cv-drawer`; `flight-command-panel.css` `.command-cluster`; `fly-osd.ts` `.chip`; `diagnostics-card.ts`/`preflight-checklist.ts` inline). Three collapse/header idioms (CV drawer, DiagnosticsCard chevron, shortcuts modal). Asset page hand-writes `<header><h2>` 9×. | Promote `.segmented` (exists), the panel-shell (from `command/asset-panel.html`), and the collapsible rail (from `live.html`) into shared components. |
| **`core/panel-state.ts`** | Two free functions `readPersistedFlag`/`writePersistedFlag` (localStorage boolean per key). Not a store. Cockpit open-state is split: `mapVisible`/`detectionsStripOpen` in `fly.ts`, `cvPanelOpen` in `cv-control-panel.ts` — no one-open-at-a-time manager. | Add a `PanelState` signal service (active-panel id, `open/close/toggle`, one-open-at-a-time, persists the active id via the existing functions). |
| **App shell** (`app.ts`/`.html`/`.css`) | Flat tab row: Fly · Command · Warehouse · Settings + a "More ▾" `<details>` (Wall). Right side: identity-chip · notification-bell · live/online chips. Collapse only hides `.brand-name` at 640px. | Three modes (Operate/Monitor/Manage), each routerLink **and** dropdown; right side notifications bell + profile menu (Account/Organization/Log out); real collapse at `--bp-sm`. |
| **Cockpit** (`features/fly`, `fly.css` 552 lines) | `.cockpit position:relative`; **every `.hud-*` region `position:absolute`** with hardcoded rem offsets + a duplicate `.banner-active` shift set. Panels live inline in `.hud-header` flex row. Esc-cascade `collapseOverlays()` is an implicit z-stack. | CSS-grid named-region overlay; right tool-rail + shared drawers via `PanelState`; grouped HUD clusters. |
| **Asset page** (`asset-detail.html` 536 lines) | Already a grid (`.detail-grid repeat(2,minmax(0,1fr))` + `.span-2`, collapses @900px) but **every section is `span-2`** → effectively a single stacked column. 9 hand-written section headers. Mixes read-only display with inline editors (registration, raw attributes, device lifecycle). | Compact overview + drill-in; shared `vision-section-header`; real multi-column composition; editors behind progressive disclosure. |
| **Additions** | Many already exist as routed features (`replay`, `activity`, `org-settings`, geofence via `GeofenceController`+`command/zones-panel`, live preflight card). Others have no backend (missions, alert thresholds, firmware, maintenance, saved layouts). | Hub tiles + honest classification; scaffolds are cheap skeletons with named backend follow-ups. |

**No light mode** exists (`color-scheme: dark`) and it stays out of scope (Non-goals).

---

## Frozen contracts

Everything in this section is **frozen**. Waves code against it and parallelize; selectors, input/
output signatures, token names, CSS class names, the icon-name registry, and the route→mode map are
pinned exactly so W1/W2/W3/W4 do not drift.

### F1. Token additions to `src/styles.css` (Wave 0)

Append only; **change no existing token**. Names are frozen:

```css
:root {
  /* Spacing scale — 4px base. Replaces ad-hoc rem. */
  --space-1: 4px;  --space-2: 8px;  --space-3: 12px; --space-4: 16px;
  --space-5: 24px; --space-6: 32px; --space-7: 48px; --space-8: 64px;

  /* Breakpoints — ONE reconciled set (was: shell 640 / asset 900 / cockpit 760,1280).
     Documented as custom props for reference; @media cannot read var(), so each stylesheet
     hardcodes THESE THREE px values and cites the token name in a comment. */
  --bp-sm: 640px;   /* below: single-column, top-bar collapses, drawers become bottom-sheets */
  --bp-md: 900px;   /* below: asset grid → 1 col; cockpit map inset hides */
  --bp-lg: 1200px;  /* below: hub tile grid drops a column; cockpit tool-rail stays */

  /* Frosted-pill HUD surface — the ONE canonical recipe, replacing ≥8 copies.
     62% is the pill/button variant; a denser 88% is the drawer variant. */
  --hud-bg: rgb(6 9 14 / 62%);
  --hud-bg-strong: rgb(9 13 20 / 88%);
  --hud-border: 1px solid rgb(255 255 255 / 9%);
  --hud-blur: 6px;
  --hud-blur-strong: 10px;
}

/* Utility classes (frozen names): */
.surface-hud        { background: var(--hud-bg);        border: var(--hud-border); backdrop-filter: blur(var(--hud-blur)); }
.surface-hud-strong { background: var(--hud-bg-strong); border: var(--hud-border); backdrop-filter: blur(var(--hud-blur-strong)); }

/* 12-column grid utilities (frozen names): */
.grid12 { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); gap: var(--space-4); }
/* .col-N { grid-column: span N } for N = 1..12; below --bp-md all .col-* collapse to span 12. */

/* App-level page grid: <main> gets a max-width container; .page keeps working unchanged. */
```

Guardrail: `.page`'s `max-width:1400px` and all existing primitives (`.btn*`, `.chip*`, `.card`,
`.segmented`, `.kebab`, `.facts`, `.label`, `.empty`) stay byte-for-byte. The reduced-motion floor
(`@media (prefers-reduced-motion: reduce)`) stays and every new animation respects it.

### F2. `<vision-icon>` — inline-SVG registry (Wave 0)

```
selector: 'vision-icon'
inputs:   name = input.required<IconName>()
          size = input<number>(16)          // px; sets width/height
host:     aria-hidden="true" (decorative; the labeled control owns the accessible name)
render:   a 24×24 viewBox <svg> with fill/stroke "currentColor"; path markup pulled from a
          TS `const ICONS: Record<IconName, string>` (path/g inner markup only). No external
          font, no CDN, no data-URI — CSP-safe inline SVG in the component template.
```

`IconName` union is **frozen** (initial set — every glyph any wave needs). Adding a name later is
additive and non-breaking:

```
// modes + nav
home, cockpit, eye, scan, layers, list, help, close, plus, edit, archive, trash, kebab,
chevron-left, chevron-right, chevron-down, chevron-up, grid, bell, user, settings, gear,
operate, monitor, manage, logout, building-org,
// cockpit / telemetry clusters
drone, battery, satellite, compass, gauge, signal, wind, thermometer, map-pin, ruler, power,
// monitor / manage
alert, replay, history, wrench, chip, firmware, report, category, warehouse, source, pilot, map
```

Unicode glyphs currently in use map to: `‹`→`chevron-left`, `›`→`chevron-right`, `×`→`close`,
`?`→`help`, `▾`→`chevron-down`, `⋯`→`kebab`, `✓` stays a text check inside chips.

### F3. Shared components (Wave 0)

**`vision-section-header`** — replaces the 9 asset-page `<header><h2>` blocks and `live.html`'s
`<section class="card"><header><h2>` idiom.
```
selector: 'vision-section-header'
inputs:   title    = input.required<string>()
          eyebrow  = input<string>()        // optional uppercase kicker (.label style)
          subtitle = input<string>()        // optional muted line under title
content:  <ng-content select="[actions]"> — projected right-aligned action slot (buttons/kebab/segmented)
render:   <header class="section-head"><div><span.eyebrow?/><h2>{title}</h2><p.muted?/></div><div class="section-head-actions"><ng-content/></div></header>
a11y:     h2 is the section label; host is not itself a landmark.
```

**`vision-side-panel`** — the shared drawer shell (from `command/asset-panel.html`'s panel-shell).
```
selector: 'vision-side-panel'
inputs:   title    = input.required<string>()
          subtitle = input<string>()
          icon     = input<IconName>()      // optional leading icon in the head
outputs:  close    = output<void>()
content:  default <ng-content> is the body; <ng-content select="[footer]"> is an optional action bar.
render:   <aside class="side-panel surface-hud-strong" role="dialog" aria-label={title}>
            <header class="side-panel-head"><vision-icon?/><div class="side-panel-title">{title}<span.sub?/></div>
              <vision-icon-button icon="close" label="Close panel" (activated)="close.emit()"/></header>
            <div class="side-panel-body"><ng-content/></div>
            <div class="side-panel-foot"><ng-content select="[footer]"/></div>
          </aside>
layout:   fixed right column (width min(24rem, 92vw)); below --bp-sm becomes a bottom-sheet
          (full width, max-height 70vh, slide-up). Focus moves to the head on open; Esc emits close;
          focus returns to the invoking tool-rail button (managed by the host via PanelState).
```

**`vision-icon-button`** — the tool-rail/close/kebab trigger, replacing `.icon-btn`.
```
selector: 'vision-icon-button'
inputs:   icon    = input.required<IconName>()
          label   = input.required<string>()   // REQUIRED accessible name → title + aria-label
          active  = input<boolean>(false)       // pressed/selected state → aria-pressed
          variant = input<'ghost' | 'hud' | 'danger'>('ghost')
outputs:  activated = output<void>()
render:   <button type="button" class="icon-btn {variant}" [class.active]="active" [title]="label"
            [attr.aria-label]="label" [attr.aria-pressed]="active" (click)="activated.emit()">
            <vision-icon [name]="icon"/></button>
rule:     an icon-only button ALWAYS carries `label` (enforced as required input). Destructive
          actions that must show text (Stop/Disarm) do NOT use this — they stay labeled `.btn.danger`.
```

**`vision-nav-tile`** + **`vision-tile-grid`** — hub launcher primitives.
```
vision-nav-tile:
  selector: 'vision-nav-tile'
  inputs:  icon = input.required<IconName>(); name = input.required<string>();
           description = input<string>(); to = input.required<string | any[]>();  // routerLink
           badge = input<string>();            // optional status/count chip
           disabled = input<boolean>(false);   // scaffold tiles never disabled — they route to a "coming soon" page
  render:  <a class="nav-tile" [routerLink]="to"><vision-icon size=24/><span.tile-name/><span.tile-desc?/><span.chip?/></a>
  a11y:    a real <a>; keyboard-focusable; the whole tile is one link; description is not a second tab stop.

vision-tile-grid:
  selector: 'vision-tile-grid'   // pure layout: content-projects nav-tiles into a responsive grid
  render:  <div class="tile-grid"><ng-content/></div>
  layout:  repeat(auto-fill, minmax(13rem, 1fr)); drops density below --bp-lg / --bp-sm.
```

**`PanelState`** — one-open-at-a-time drawer manager (signal service; NOT `providedIn:'root'` —
provided per host so each cockpit/asset instance owns its own active panel).
```
class PanelState {
  readonly active: Signal<string | null>;            // active panel id, or null
  isOpen(id: string): boolean;                        // active() === id
  open(id: string): void;                             // closes any other; sets active = id
  close(): void;                                       // active = null
  toggle(id: string): void;                            // open(id) unless already active, else close()
  // persistence: constructed with an optional storageKey; when set, the active id is round-tripped
  // through readPersistedFlag/writePersistedFlag-style string I/O (a sibling readPersistedString/
  // writePersistedString added to panel-state.ts — booleans stay for existing callers).
}
```
`core/panel-state.ts` gains `readPersistedString(key, fallback)` / `writePersistedString(key, value)`
alongside the existing boolean pair (existing `live.ts` callers untouched).

Segmented/tab pickers: **reuse `.segmented role="tablist"`** (already in `styles.css` and
`command/asset-panel.html`). Do **not** build a new Tab component.

### F4. Route → mode map (Wave 1) — every current URL still resolves

New hub routes (add to `app.routes.ts` inside the `authGuard` children group):

| Path | Component | Notes |
|---|---|---|
| `/operate` | `OperateHub` | new `features/hubs/`; tile grid |
| `/monitor` | `MonitorHub` | new |
| `/manage`  | `ManageHub`  | new |

Existing routes are **unchanged**; each is assigned to a mode for the hub/dropdown listing only:

| Mode | Entry (tile + dropdown) | Route | Status |
|---|---|---|---|
| **Operate** | Cockpit | `/fly` | functional |
| Operate | Live view | `/live/:deviceId` (tile → picker/last device) | functional |
| Operate | Vision (CV console) | `/fly` (opens CV drawer) or `/settings` CV section | functional (CV-CONTROL shipped) |
| Operate | Flight & detection settings | `/settings` | functional |
| Operate | Pre-flight checklist | `/operate/preflight` | scaffold (live card functional) |
| Operate | Flight plans / missions | `/operate/missions` | **scaffold** |
| Operate | Geofence & safety zones | `/command` (Zones panel) / `/operate/zones` | functional |
| **Monitor** | Wall | `/wall` | functional |
| Monitor | Map (fleet) | `/command` (map is folded in; `/map`→`/command`) | functional |
| Monitor | Command dashboard | `/command` | functional |
| Monitor | Activity / events | `/activity` | functional |
| Monitor | Alerts center | `/monitor/alerts` | scaffold (event list functional) |
| Monitor | Replay library | `/monitor/replay` (index) → `/replay` | functional (per-asset) |
| Monitor | Saved Wall layouts | `/monitor/layouts` | **scaffold** (client-side) |
| **Manage** | Assets | `/devices` (Warehouse, asset-first) | functional |
| Manage | Devices | `/devices` | functional |
| Manage | Warehouse (sources) | `/devices` (+`/warehouse` alias) | functional |
| Manage | Add source / Discovery | `/add-source` | functional |
| Manage | Pilots / roster | `/manage/roster` | functional (AssignmentController; no UI yet) |
| Manage | Asset categories | `/manage/categories` | scaffold for CRUD (grouped view functional) |
| Manage | Maintenance / health | `/manage/health` | **scaffold** |
| Manage | Firmware | `/manage/firmware` | **scaffold** |
| Manage | Inventory reports | `/manage/reports` | functional dashboard (export scaffold) |
| (shell) | Account settings | `/settings` | via profile menu |
| (shell) | Organization | `/org` (orgGuard) | via profile menu |
| (shell) | Log out | auth action | via profile menu |
| (unlisted) | Debug | `/debug` | reachable by URL only (unchanged) |

Preserved: `''`→`/fly`, `/map`→`/command`, `/warehouse` alias, `/replay` query route, `/assets/:assetId`,
`/assets/:assetId/replay/:usageId`, `/login` (outside guard), `**`→NotFound. **No route is removed or
renamed.** New scaffold routes each own a `<name>.routes.ts` per UI-STRUCTURE-PLAN §B8.

---

## Design decisions (with rationale)

### D-A. Hub-and-spoke over a flat tab row — and the cheaper-than-it-looks reuse
The three modes map onto the three personas UX-REWORK-PLAN §0 already named (pilot / manager /
inventory-admin). The **dropdown reuses the exact `<details>` disclosure idiom** already in
`app.html`'s "More ▾" (`.tab-more`/`.tab-more-menu`) and `identity-chip` — no new dropdown component.
The **hub launcher** is just `vision-tile-grid` over `.card`-shaped tiles reusing existing surface
tokens. So the shell rebuild is mostly re-labeling + one dropdown per mode + three thin hub pages.

### D-B. One frozen frosted-pill utility kills 8 copies
The `background: rgb(6 9 14 / 62%); backdrop-filter: blur(6px)` recipe is copy-pasted across 6 files
(F-current-state). Promoting it to `.surface-hud` / `.surface-hud-strong` + `--hud-*` tokens means
the cockpit rebuild deletes those copies rather than moving them. This is the single highest-leverage
consolidation and it lands in Wave 0 so every later wave consumes the token.

### D-C. CSS-grid overlay replaces the absolute-position geometry
`.cockpit` today positions ten regions with hardcoded rem offsets **and** a duplicate `.banner-active`
set that shifts them all when the failsafe banner shows — the fragile geometry. A named-area grid
makes the banner a real grid row (content reflows, no manual offsets) and makes the tool-rail a real
column. Frozen grid (Wave 2):
```
grid-template-areas:
  "banner   banner   banner"
  "telemetry main     rail"
  "ticker   main     rail"
  ".        controls  rail";   /* map inset floats over `main` top-right; drawers overlay `main` */
grid-template-columns: minmax(0,20rem) 1fr auto;   /* rail = tool-rail width */
grid-template-rows:    auto 1fr auto auto;
```
The video hero stays `main` full-bleed; the map inset and open drawer overlay `main` (absolute within
`main`, not within `.cockpit`) so they never fight the grid. Below `--bp-md` the map inset hides
(unchanged behavior) and telemetry collapses to a single top strip.

### D-D. One-open-at-a-time tool-rail consolidates three split open-states
Cockpit open-state is split today: `mapVisible`/`detectionsStripOpen` in `fly.ts`, `cvPanelOpen` inside
`cv-control-panel.ts` — and `collapseOverlays()` is an implicit z-ordered Esc-cascade. `PanelState`
(F3) replaces all of it with a single `active` signal. Tool-rail buttons (frozen ids):
`flight` · `cv` · `detections` · `layers` · `help`. Esc calls `panels.close()`; the map inset stays a
separate persisted toggle (`M`) since it is glanceable, not a modal drawer. `B` (boxes cycle) and `F`
(fullscreen) are unchanged.

### D-E. Migrate the two cockpit panels into the drawer shell — asymmetric cost, stated honestly
- **`cv-control-panel`** is already a toggle-button-opens-drawer with its own head/close and a
  `cvPanelOpen` persisted flag. Migration = delete `.cv-toggle`/`.cv-drawer`/`.cv-drawer-head` chrome
  and the self-owned open state; keep the body (model radiogroup, class chips, sliders, detection
  toggle). Its open state moves to `PanelState` id `cv`. **Low-risk, mostly deletion.**
- **`flight-command-panel`** is a single `.command-cluster` pill island (Mode select + Set + Arm +
  Disarm), no head/close today. Moving it into a drawer is a **larger visual change**: it becomes the
  `flight` drawer body. Preserve exactly: two-tier capability gating (`canCommand()` host gate +
  per-control `capabilities()` flags), the three independent busy signals + `anyBusy`, the generic
  `vision-confirm-dialog` for Mode/Disarm, and the dedicated two-stage `vision-arm-confirm-dialog` for
  Arm. **Disarm keeps its text label.** No optimistic UI — armed state still arrives via telemetry.

### D-F. Asset page: overview + drill-in, editors behind disclosure
Every asset section is `span-2` today → a single stacked column. The overview keeps only the
glanceable cards (identity, cockpit band, KPI tiles, recent-flights sparkline, position map + freshest
summary) in a real 12-col composition; the heavy areas (full per-device telemetry, usage-history table,
hardware/devices lifecycle table, raw-attribute + registration editors, pilots roster) move into
drill-in sub-views or `vision-side-panel` drawers. The 9 `<header><h2>` blocks become
`vision-section-header`. Rationale: the pilot-viewer never needs the manager's device-lifecycle wall
on the landing, and the raw-attribute editor is an advanced-mode concern — progressive disclosure keeps
the overview compact without losing any capability.

### D-G. Additions: functional-or-scaffold, no fake data
Each addition is classified by a real backend check (see the Additions section). Functional tiles wire
existing endpoints/components; scaffold tiles are a skeleton page + one honest empty-state using the
existing `.empty` primitive, with the backend follow-up named. **A scaffold never renders invented
rows** — it states what's coming and links to the nearest real capability. Guardrail: scaffolds are
additive routes; no existing screen changes behavior.

### D-H. Guardrails — existing behavior unchanged
Wave 0 only appends tokens/components (no existing token or primitive edited) → every existing
component renders identically until it opts in. Route changes are additive/aliased/redirected → no URL
breaks. Cockpit persistence keys (`vision.fly.mapVisible`, `vision.fly.detectionsStripOpen`,
`vision.fly.cvPanelOpen`) are preserved or migrated with a documented fallback. This keeps every
existing vitest green through W0 and lets W1/W2/W3 land independently.

---

## Wireframes (ASCII)

### Top bar + Operate hub
```
┌───────────────────────────────────────────────────────────────────────────────────┐
│ ◉ Vision   [Operate ▾] [Monitor ▾] [Manage ▾]              🔔  ( ▣ you ▾ )  ● ONLINE│
└───────────────────────────────────────────────────────────────────────────────────┘
     └─ Operate ▾ dropdown ─┐        profile ▾ ─┐
        Cockpit             │        Account settings
        Live view           │        Organization
        Vision (CV)         │        Log out
        Flight & det. set.  │
        Pre-flight  · Missions · Geofence

  OPERATE                                        (hub launcher = tile grid)
  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
  │ ✈ Cockpit│ │ 👁 Live  │ │ ⌗ Vision │ │ ⚙ Settings│
  │ fly one  │ │ one dev  │ │ detection│ │ flight+det│
  └──────────┘ └──────────┘ └──────────┘ └──────────┘
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │ ☑ Preflt │ │ ⌖ Missions│ │ ⬡ Geofence│   (Missions tile = scaffold badge "soon")
  └──────────┘ └──────────┘ └──────────┘
```

### Cockpit grid + tool-rail + open drawer
```
┌──────────────────────── failsafe/RTH banner (grid row, reflows) ────────────────────────┐
├───────────────┬────────────────────────────────────────────────────────┬───────────────┤
│ TELEMETRY     │                                              [map inset] │  TOOL-RAIL    │
│ ┌Power┐┌Nav ┐ │                VIDEO HERO (main)                         │  ┌─────────┐  │
│ │87% ⚡││ALT ││                                                          │  │ ✈ flight│  │
│ └────┘└────┘ │        ┌──────── flight drawer (overlays main) ───────┐  │  │ ⌗ cv    │  │
│ ┌Link┐┌Env ┐ │        │ ✈ Flight                                [×]  │  │  │ ▤ detect│  │
│ │2s  ││6m/s││        │ Mode [Loiter ▾] [Set]                        │  │  │ ⬢ layers│  │
│ └────┘└────┘ │        │ [ Arm ]  [ Disarm ]  (labeled, gated)        │  │  │ ? help  │  │
│ (Esc closes) │        └──────────────────────────────────────────────┘  │  └─────────┘  │
├───────────────┤                                                          │               │
│ event ticker  │            [ Stop stream ]   (labeled, center)           │               │
└───────────────┴────────────────────────────────────────────────────────┴───────────────┘
   HUD cluster = one .surface-hud pill per group; secondary metrics behind a disclosure chevron.
```

### Asset overview + drill-in
```
  ‹ Warehouse                                            [Rename] [Archive]
  ┌──────────────────────────── identity band ─────────────────────────────┐
  │ [photo]  Falcon-3   ⟨quadcopter⟩  ● Streaming     Open cockpit · Watch  │
  └─────────────────────────────────────────────────────────────────────────┘
  ┌── KPI summary (col-8) ───────────────┐  ┌── Position (col-4) ──────────┐
  │ ⏱ 12.4h  ✈ 38 flights  ◷ 2d ago      │  │ [ live map ]  freshest: GCS  │
  └───────────────────────────────────────┘  └───────────────────────────────┘
  ┌── Recent flights (col-8, sparkline) ─┐  ┌── Live/status (col-4) ───────┐
  │ ▁▂▅▇▃▁▆  → drill: Usage history      │  │ armed · GPS 3D · batt 87%    │
  └───────────────────────────────────────┘  └───────────────────────────────┘
  [ Full telemetry ▸ ] [ Usage history ▸ ] [ Hardware & devices ▸ ] [ Attributes ▸ ] [ Pilots ▸ ]
        └── each opens a drill-in sub-view or a vision-side-panel drawer (SectionHeader inside) ──┘
```

---

## Implementation waves (disjoint file scopes)

Each wave ends **independently green** (`npm test` / `tsc --noEmit` / prod build via
`./mvnw -B -pl vision-web -DskipTests=false test` or the `frontend-maven-plugin` npm scripts) with
`vision-web/MODULE.md` (and any component docs) updated. **Sequencing: W0 first (unblocks all).** Then
**W1 ‖ W2 ‖ W3** parallelize against the frozen W0 contracts. **W4** runs after its hub/routes exist
(needs W1's `features/hubs/` + `NavTile`); its sub-areas may split across agents. Agent: **web-ui** for
every wave (all `vision-web/**`).

### Wave 0 — Shared foundation — `src/styles.css`, `src/app/shared/ui/**`, `src/app/core/panel-state.ts`
The frozen contract above. Deliverables:
- Token additions (F1) appended to `styles.css`; frosted-pill utilities; 12-col grid + `<main>` container.
- `<vision-icon>` + `ICONS` registry (F2) with the full frozen `IconName` set.
- `vision-section-header`, `vision-side-panel`, `vision-icon-button`, `vision-nav-tile`,
  `vision-tile-grid` (F3) as standalone components under `shared/ui/`.
- `PanelState` service + `readPersistedString`/`writePersistedString` added to `core/panel-state.ts`
  (existing boolean functions + `live.ts` callers untouched).
- **No existing component is edited** — this wave is purely additive, so every existing test stays green.
- Verify: vitest for `PanelState` (one-open-at-a-time, persistence round-trip), icon registry
  (`ICONS[name]` defined for every `IconName`), and a11y basics (`vision-icon-button` requires `label`
  → renders `aria-label`+`title`; `vision-side-panel` has `role="dialog"`+`aria-label`; `nav-tile` is a
  focusable `<a>`); `tsc` clean; prod build green. Update `vision-web/MODULE.md` (new shared-UI surface
  + token reference) and add a short `shared/ui/README`-style doc block per component.

### Wave 1 — Navigation + hubs — `src/app/app.*`, `src/app/features/hubs/**`, `app.routes.ts`, scaffold `<name>.routes.ts`
Depends on W0 (NavTile/TileGrid/icon). Deliverables:
- Rebuild `app.html`/`app.ts`/`app.css`: brand · Operate/Monitor/Manage (each `routerLink` to its hub
  **and** a `<details>` dropdown of entries, reusing the `.tab-more` idiom) · right side notification
  bell (existing `vision-notification-bell`) + a profile menu (extend/relocate `vision-identity-chip`
  to carry Account settings / Organization / Log out). Keep the live/online status chips. Real collapse
  at `--bp-sm` (dropdowns become a single "Menu" disclosure).
- `features/hubs/`: `OperateHub`, `MonitorHub`, `ManageHub` pages using `vision-tile-grid` + `NavTile`,
  listing each mode's entries per the F4 map (functional tiles route directly; scaffold tiles route to
  their scaffold page and carry a "soon" badge).
- Routing regroup in `app.routes.ts`: add `/operate`, `/monitor`, `/manage` + register the new scaffold
  `<name>.routes.ts` (W4 fills the components; W1 may land them as thin placeholders or W4 adds them —
  choose one, noted default: **W1 adds the three hub routes only; W4 adds each scaffold route with its
  page**). No existing route touched.
- Verify: a route-resolution vitest asserting **every URL in the F4 table resolves** (existing +
  hubs); shell renders three modes; dropdowns open/close; `tsc`/build green; MODULE.md updated.

### Wave 2 — Pilot cockpit — `src/app/features/fly/**`
Depends on W0. Deliverables:
- Replace `.cockpit` absolute-position layout with the D-C named-area CSS grid; failsafe banner becomes
  a grid row (delete the `.banner-active` offset set); map inset + drawers overlay `main`.
- Right tool-rail of `vision-icon-button`s (ids `flight`/`cv`/`detections`/`layers`/`help`) driving
  `vision-side-panel` drawers via a `PanelState` provided on `FlyPage`. Migrate `cv-control-panel` and
  `flight-command-panel` bodies into drawers (D-E): delete `.cv-toggle`/`.cv-drawer` chrome and
  `.command-cluster` island; delete the 6 frosted-pill copies in fly/cv/flight CSS in favor of
  `.surface-hud`. Consolidate the three split open-flags into `PanelState`; migrate the `cvPanelOpen`
  persistence key (fallback: default closed).
- Group `fly-osd` chips into Power/Nav/Link/Env clusters (each a `.surface-hud` badge) with a
  disclosure chevron for secondary metrics (reuse the `diagnostics-card` chevron idiom).
- Preserve exactly: keyboard shortcuts (`M`/`B`/`F`/`Esc`/`?` — `Esc`→`panels.close()`), watch-mode
  gating, capability gating (both tiers), all confirm dialogs, and Stop/Start/Disarm **text labels**.
- Verify: `fly-logic` + panel-migration vitest (drawer open/close one-at-a-time, capability gates,
  arm/disarm confirm flow, watch-mode hides Start/Stop + CV drawer); `tsc`/build green; MODULE.md +
  cockpit component docs updated.

### Wave 3 — Asset page — `src/app/features/asset-detail/**`
Depends on W0. Deliverables:
- Compact overview (D-F): identity band + cockpit band + KPI tiles + recent-flights sparkline +
  position map/freshest-summary + a small live/status card, composed on the frozen `.grid12`
  (`col-8`/`col-4`), collapsing to one column below `--bp-md`.
- Drill-in for the heavy areas via `vision-side-panel` drawers and/or focused sub-views: full per-device
  telemetry, usage-history table, hardware/devices lifecycle table, attribute + registration editors
  (advanced-mode progressive disclosure), pilots roster (`vision-pilots-card`, manager-only — unchanged
  backend).
- Replace all 9 `<header><h2>` blocks (8 in `asset-detail.html` + 1 in `pilots-card.html`) with
  `vision-section-header`; migrate spacing to `--space-*` tokens.
- Preserve every existing flow: registration/attribute edits (`fleet.updateAsset`), asset rename/
  category, archive/restore with undo, device lifecycle actions + attach, polling cadences, and
  `openCockpit`/`watch`/replay navigation.
- Verify: `asset-detail-logic` vitest (drill-in open/close, editors save, section headers render);
  `tsc`/build green; MODULE.md + `pilots-card` doc updated.

### Wave 4 — Additions — `src/app/features/{operate-extras,monitor-extras,manage-extras}/**` (or per-area folders)
Depends on W1 (hubs/routes/NavTile). May split across agents by mode (W4a Operate, W4b Monitor, W4c
Manage) with disjoint folders. Each area: a hub tile (already listed in W1's hub), a route, and either
functional wiring or a scaffold page. **Scaffolds use the `.empty` primitive with honest copy and a
link to the nearest real capability; no invented data.** See the Additions section for the exact
functional/scaffold split and reused endpoints/components per area.
- Verify per area: vitest for any logic; scaffold pages assert the empty-state renders and routes
  resolve; `tsc`/build green; MODULE.md updated with each new route + its functional/scaffold status +
  named backend follow-up.

---

## Additions — functional vs scaffold (grounded in a backend check)

Verified against `vision-api`, `adapters/adapter-mavlink`, and the SPA. `FUNCTIONAL-NOW` = data/
endpoints/components exist and the tile wires them; `SCAFFOLD` = skeleton + honest empty-state, backend
follow-up named. `SPLIT` = part functional, part deferred.

### Operate
| Area | Route | Verdict | Reuses / Follow-up |
|---|---|---|---|
| Pre-flight checklist | `/operate/preflight` | **SPLIT** | Live status card reuses `preflight-checklist.ts` + `derivePreflight` (functional). **Editable saved templates = scaffold**; follow-up: checklist-template entity + CRUD endpoint (zero backend hits today). |
| Flight plans / missions | `/operate/missions` | **SCAFFOLD** | `flight-plan-dialog` is an in-memory *sim-route* editor only; `MavlinkFlightCommander` declares mission/fence upload **out of scope**. Follow-up: flight-plan persistence + FC mission upload. |
| Geofence & safety zones | `/operate/zones` (or link `/command` Zones) | **FUNCTIONAL-NOW** | `GeofenceController` full CRUD (`GET/POST/PUT/DELETE /api/geofences`), `GeofenceStore`, `command/zones-panel.ts`, `geofence-zone-dialog.ts`. Tile links straight in. |

### Monitor
| Area | Route | Verdict | Reuses / Follow-up |
|---|---|---|---|
| Alerts center | `/monitor/alerts` | **SPLIT** | Event feed reuses `EventController` (`GET /api/events`, `/api/streams/{id}/events`) + `core/events/events-store.ts` (functional). **Saved threshold rules + acknowledge = scaffold**; follow-up: alert-rule persistence + acknowledge state (`eventRule` is only a stream-start param today, not a stored rule). |
| Replay library | `/monitor/replay` | **FUNCTIONAL-NOW (per-asset)** | `ReplayPage` + routes; `UsageTimelineController` (`GET /api/usages/{id}/timeline`,`/recording`); `AssetDetails.recentUsages`. Flag: **no cross-fleet "all recordings" endpoint** — a global library needs an aggregation endpoint (follow-up); per-asset library works now. |
| Saved Wall layouts | `/monitor/layouts` | **SCAFFOLD** | Only `SettingsStore.wallDensity` persists today. Pure **client-side** feature — follow-up reuses the existing `SettingsStore` localStorage pattern (expanded schema), **no backend needed**. |
| Activity / events feed | `/activity` | **FUNCTIONAL-NOW** | `ActivityPage` + `GET /api/me/activity` (`ActivityController`), `AuditController` org-wide. Tile links straight in. |

### Manage
| Area | Route | Verdict | Reuses / Follow-up |
|---|---|---|---|
| Pilots / roster | `/manage/roster` | **FUNCTIONAL-NOW** | `AssignmentController` (`PUT/DELETE/GET /api/assets/{id}/pilots/{userId}`, `GET /api/me/assignments`), `PilotResponse`, `OrgStore` users. Backend fully live; `org-settings.ts` covers only Users/Groups today — a new roster UI (or a third org-settings tab) wires the existing endpoints. |
| Asset categories | `/manage/categories` | **SPLIT** | Grouped/filtered view reuses `CategoryController` (`GET /api/categories`) + `FleetController` per-category counts + `UpdateAssetRequest.category` re-categorize (functional). **Category create/edit = scaffold** (only `GET` exists); follow-up: category `POST/PUT`. |
| Inventory reports | `/manage/reports` | **SPLIT** | Read-only stats dashboard reuses `FleetController` summary (counts + attention list) + `AssetStatsController` (`GET /api/assets/{id}/stats`) (functional). **Exportable/generated reports = scaffold**; follow-up: report/export endpoint. |
| Maintenance / health | `/manage/health` | **SCAFFOLD** | No maintenance backend. Partial read-only reuse: `FleetController` attention list + `diagnostics-card`. Follow-up: maintenance/health-record entity + endpoints. |
| Firmware | `/manage/firmware` | **SCAFFOLD** | `FlightState.firmware` is a reported telemetry string only; no inventory/update endpoint. Follow-up: firmware inventory + update flow. |

---

## Understandability & standards (applies to every wave)

- **Icon + tooltip everywhere, labeled destructive actions.** Every icon-only control is a
  `vision-icon-button` with a required `label` → `title` + `aria-label`. Destructive/high-consequence
  actions (**Stop stream**, **Disarm**, **Archive**) keep **text labels** and `.btn.danger`/
  `.danger-action` styling — never icon-only. (UX-REWORK poka-yoke rule 4.)
- **One name per action end-to-end.** Reuse the UX-REWORK verb dictionary (Watch/Open/Start/Stop/
  Archive…); a hub tile, its dropdown entry, and its page title use the **same** noun (e.g. "Cockpit"
  everywhere, not "Fly" in one place and "Cockpit" in another — pick one label per entry and keep it).
- **Consistent empty/error states.** All scaffolds and empty lists use the existing `.empty` primitive
  (`<h3>` + one-line `<p>` + a real next-step link). Scaffold copy states plainly what's coming and
  links to the nearest live capability; **never fabricated rows**. Backend-unreachable states reuse the
  existing offline-banner pattern.
- **Keyboard focus + reduced-motion floor.** Drawers move focus in on open and restore it on close;
  hub tiles and dropdowns are fully keyboard-navigable; the `prefers-reduced-motion` floor in
  `styles.css` stays and every new slide/transition respects it. Focus-visible outlines use the
  existing `:focus-visible` token.

---

## Non-goals / deferred (named, not silently dropped)

- **Light mode** — out of scope; the app stays dark-only (`color-scheme: dark`). No `@media
  (prefers-color-scheme: light)` work.
- **Flight-plan / mission persistence + FC mission upload** — scaffold only (`/operate/missions`);
  `MavlinkFlightCommander` declares it out of scope.
- **Alert-threshold rules + acknowledge** — scaffold only (`/monitor/alerts` shows the live event feed);
  no `EventRule` CRUD is built.
- **Cross-fleet Replay library aggregation endpoint** — deferred; the library is per-asset over
  `recentUsages`.
- **Saved Wall layouts backend** — none needed; deferred as a client-side `SettingsStore` extension.
- **Category create/edit, Inventory report export, Maintenance/health entity, Firmware inventory/update**
  — all scaffold; backend follow-ups named per area above.
- **Editable pre-flight checklist templates** — scaffold; the live status card is functional.
- **No new gRPC/proto/wire contract** — this plan changes no backend contract; it consumes existing
  REST endpoints only.

## Open questions / to confirm before delegating

1. **Profile menu vs identity-chip.** The right-side profile menu (Account settings / Organization /
   Log out) — extend the existing `vision-identity-chip` (which already uses `<details>` and links
   `/activity`) in place, or introduce a dedicated `vision-profile-menu`? Default: **extend
   identity-chip** (least churn); confirm.
2. **Label per entry.** Confirm one canonical label for the cockpit spoke ("Cockpit" vs "Fly") and the
   Vision spoke ("Vision" vs "Detection"), so the dictionary is fixed before W1.
3. **W4 scaffold ownership.** Confirm whether W1 lands thin scaffold-route placeholders or W4 adds each
   scaffold route with its page (plan default: **W4 owns the scaffold pages + routes**).
4. **`/operate/zones` vs deep-link.** Geofence is functional inside `/command`'s Zones panel — expose it
   as its own `/operate/zones` route (new thin page reusing `ZonesPanel`) or have the tile deep-link to
   `/command`? Default: **deep-link to `/command`** (no duplicate page); confirm if a standalone Operate
   route is wanted.
