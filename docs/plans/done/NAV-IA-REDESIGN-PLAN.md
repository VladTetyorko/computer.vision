# NAV-IA-REDESIGN-PLAN

Authoritative spec for the navigation + layout rework of `vision-web`. Grounded in a full
click-through of the running app (localhost:4200, admin session, 1854×961 viewport) on 2026-08-04.

Per-page design files live in [`docs/extracts/design/`](../../extracts/design/). This file owns the **shell**, the
**information architecture**, and the **wave plan**; each page file owns its own refactor list.

---

## 1. What the walkthrough found

### F1 — Navigation is duplicated, and one of the two copies costs a page load

`features/hubs/nav-entries.ts#NAV_MODES` is rendered **twice**, identically:

- as three top-bar dropdowns (`app.html`, `.mode-more-menu`), and
- as three whole **hub pages** — `/operate`, `/monitor`, `/manage` — which are nothing but a
  `vision-tile-grid` of the same entries (`operate-hub.ts`, `monitor-hub.ts`, `manage-hub.ts`).

Clicking `OPERATE` in the top bar navigates to a full page whose only content is five link tiles that
the dropdown right above it already listed. Every task that starts from a mode costs **one extra
click and one extra lazy-chunk load** for zero information gained.

### F2 — You cannot see where you are, or what is next to you

The top bar shows three labels. Within a mode, the current page and its siblings are invisible
unless you open a dropdown — which closes on click, so it never persists. There is no breadcrumb,
no active-section indicator beyond an underline on the mode. On `/manage/reports` the only on-screen
evidence of location is the page's own `<h1>`.

### F3 — The dropdown is the only path to most pages, and it clips

`Manage` has 10 entries. Opened at 961px viewport height, the panel renders 7 and cuts the rest
(`Maintenance / health`, `Debug`, `Devices` fall below the fold with no scroll affordance).

### F4 — Horizontal space is wasted; vertical space is spent on prose

`.page { max-width: 1400px }` centered in 1854px leaves 227px dead on each side, and most pages then
use only the left third of what remains:

| Page | Content actually drawn | Viewport used |
|---|---|---|
| `/fly` (picker) | one 210×140 card | ~13% |
| `/operate/preflight` | one 195px checklist | ~11% |
| `/assets` | one 260px asset card | ~14% |
| `/wall` | one 270px tile + a 230px rail | ~27% |

Meanwhile every page opens with `page-head` = `<h1>` + a two-line explanatory paragraph, costing
~130px of vertical space before the first control. On `/alerts` that is 130px of instructions above
a feed whose rows are 1000px wide and hold ~40 characters each.

### F5 — Card grids are used where lists belong

`vision-tile-grid` is correct for the Wall (video tiles are inherently 2-D). It is used for
navigation menus (the hubs) and for record browsing (`/assets`), where it costs density and
scan-ability: `/assets` spends a 260×120 card on what a table row shows in 32px.

### F6 — Detail always means "leave the page"

`/assets` → **Open** → full navigation → browser Back. `/monitor/alerts` → **Details ›**.
`/devices` → kebab. `/assets/:id` → six **drill-in** buttons, each a separate page. The list context
is destroyed every time. `shared/ui/side-panel.ts` already exists and is not used for any of this.

### F7 — One destination, several names and doors

- `/settings` is **"Flight & detection settings"** in the Operate dropdown and **"Account settings"**
  in the avatar menu — the same page, two mental models, and it genuinely mixes both concerns
  (Interface + Notifications are per-account; Detection profile is fleet-wide).
- `/activity` is in the Monitor dropdown **and** the avatar menu ("My activity").
- `/devices` is a Manage entry **and** a prose link in the `/assets` subtitle.

### F8 — `/replay` is a broken navigation destination

Monitor → **Replay library** ("Scrub any finished flight, frame by frame") lands on
**"Replay unavailable — No usage specified."** `ReplayPage` is a detail view that requires a
`usageId`; there is no library. The nav promises a page that does not exist.

### F9 — Scaffolds carry the same visual weight as shipped features

5 of ~20 destinations are `badge: 'soon'` placeholders (Missions, Firmware, Maintenance/health,
Saved Wall layouts) or partial (Alerts, Categories, Reports render a "coming" notice). In both the
hub grids and the dropdowns they are full-size, equal-weight entries.

### F10 — Role filtering is applied in one of the two navigation copies

`managerOnly` is honoured by `ManageHub` and **not** by the top-bar dropdown (`manage-hub.ts`'s own
doc comment records this as a known follow-up). A PILOT sees two tiles on the hub and ten entries in
the dropdown.

### F11 — Full-bleed operational views still pay for global chrome

`/fly`'s cockpit, `/wall` and `/command` are the views that want the whole screen. All three render
under the 40px global header. In the cockpit the header sits above a `FAILSAFE ACTIVE` banner, so
72px of a piloting view is chrome. Additionally `Bring home` is clipped at the right edge by the
`DRONE` selector, and the telemetry overlay's first glyph is cut by the collapse chevron
(`lat` renders as `at`).

### F12 — The cockpit is not addressable

Entering the cockpit from `/fly` does not change the URL. It stays `/fly`, so the cockpit cannot be
bookmarked, refreshed into, or shared, and Back does not leave it.

---

## 2. The target model

### 2.1 One navigation surface: a persistent left sidebar

Replace the top-bar modes **and** the three hub pages with a single vertical sidebar that is always
visible and always shows the full tree.

```
┌────────────────┬──────────────────────────────────────────────────────┐
│  ◉ Vision      │  ⌂ Assets · 12          [search]      [+ Add source] │  ← page bar (48px)
├────────────────┼──────────────────────────────────────────────────────┤
│ OPERATE        │                                                      │
│  ▸ Cockpit     │                                                      │
│    Wall        │                                                      │
│    Pre-flight  │                     content                          │
│ MONITOR        │                   (fluid width)                      │
│    Command     │                                                      │
│    Alerts    ⁴ │                                                      │
│    Activity    │                                                      │
│ MANAGE         │                                                      │
│    Assets      │                                                      │
│    Add source  │                                                      │
│    Pilots      │                                                      │
│    Categories  │                                                      │
│    CV training │                                                      │
│    Reports     │                                                      │
│  ⌄ Advanced    │                                                      │
├────────────────┤                                                      │
│ ⚙  Settings    │                                                      │
│ AD Administrator                                                      │
└────────────────┴──────────────────────────────────────────────────────┘
```

Rules:

1. **Group headers (`OPERATE` / `MONITOR` / `MANAGE`) are labels, not links.** They have no route.
   This is what removes the hub pages entirely.
2. **Every leaf is one click from every page.** No dropdown, no hover-reveal, no clipping.
3. **The active leaf is marked** with an accent bar + background; its group header brightens. Sense
   of place is permanent (fixes F2).
4. **Width 240px expanded, 56px collapsed** to an icon rail. The toggle persists in `localStorage`
   and is bound to `[`. Collapsed rail shows tooltips on hover.
5. **Auto-collapse on full-bleed routes** (`/fly`, `/wall`, `/command`) — the rail appears, expands
   on hover, and never reflows the video (fixes F11 without hiding navigation).
6. **`managerOnly` is applied once, in the sidebar** — the single consumer of `NAV_MODES`, so F10
   cannot recur.
7. **`soon` entries collapse under a `⌄ Upcoming` disclosure** at the end of their group, and the
   `advanced` / `diagnostics` Manage groups collapse under `⌄ Advanced` (fixes F9, F3's length).
8. **Live counters ride the sidebar** — `Alerts` carries the unacknowledged count, `Cockpit`/`Wall`
   carry the live-stream count. The header's `1 live` chip moves here where it is next to what it
   describes.

Below `--bp-md` (1024px, new token) the sidebar becomes an overlay drawer behind a hamburger;
below `--bp-sm` (640px) it is a full-height sheet. The existing `.modes-narrow` markup is replaced,
not kept in parallel.

### 2.2 A compact page bar replaces `page-head`

One 48px row, not a 130px block:

```
[icon] Title · <count>           [contextual filters]        [primary action]
```

- The description paragraph is **deleted** where the title is self-explanatory (`Assets`, `Devices`,
  `Activity`, `Alerts center`, `Organization`, `Debug`). Where it carries real instruction it becomes
  a `?` popover on the title.
- The `Refresh` buttons scattered across pages are folded into the bar as one icon button.
- `soon` "coming" notices stay, but as a single dismissible line, not a boxed panel above the fold.

Net: ~90px of vertical space returned to content on every list page.

### 2.3 Content goes fluid; prose keeps its measure

`.page` loses `max-width: 1400px` and becomes `padding: var(--space-16) var(--space-32)` with
`max-width: none`. Prose keeps `max-width: 62ch` where it appears. Pages that genuinely want a
centered column (`/add-source`, `/settings`) opt in with `.page--form { max-width: 880px }` — which
also fixes the 1075px-wide "display name" input on `/add-source`.

### 2.4 Lists, not card grids, for records — with a side panel for detail

Adopt the two-pane pattern the `/command` page already proves works (asset list + map):

```
┌───────────────────────────┬──────────────────────┐
│  list / table (fluid)     │  side panel (400px)  │
│  ▸ selected row           │  detail for the      │
│    row                    │  selected row        │
│    row                    │  [actions]           │
└───────────────────────────┴──────────────────────┘
```

- Selection updates a `?sel=<id>` query param — addressable, Back-able, refresh-safe.
- The panel is `shared/ui/side-panel.ts` (already built, currently unused for this).
- Applies to `/assets`, `/devices`, `/monitor/alerts`, `/activity`, `/manage/roster`.
- Full-page detail routes (`/assets/:id`) stay for deep work; the panel handles triage.

### 2.5 IA corrections

| Fix | Change |
|---|---|
| F7 settings split | `/settings` keeps **account** prefs (Interface, Notifications). Detection profile + model move to `/settings/detection`, listed under **Operate** as "Detection defaults". The avatar menu links `/settings`; the sidebar links `/settings/detection`. Two names stop describing one page. |
| F7 activity | `/activity` is a **Monitor** entry only. The avatar menu keeps its link (it is a shortcut, not a second door — acceptable and conventional). |
| F7 devices | `/devices` stays under `⌄ Advanced`. The prose link in the `/assets` subtitle is removed with the subtitle itself (§2.2). |
| F8 replay | Build the actual library: `/replay` lists finished usages (asset, start, duration) → `/assets/:assetId/replay/:usageId`. Until then the nav entry gets `badge: 'soon'` and routes to `ComingSoon`, so it stops lying. |
| F12 cockpit | Cockpit becomes `/fly/:assetId`; `/fly` stays the picker and redirects to the remembered drone when one exists. |

### 2.6 What is explicitly **not** changing

- `NAV_MODES` stays the single source of truth — it gains a `NavGroup` level, it is not replaced.
- The design-token system, `vision-icon`, `side-panel`, `tile-grid`, `stat`, `empty-state`,
  `section-header` all stay. This is a shell + layout change, not a component-library rewrite.
- The Component→Facade→Store→Service layering (`docs/plans/done/UI-ARCHITECTURE-PLAN.md`) and
  `architecture.spec.ts` continue to apply; the sidebar is a shell component reading a store.
- No backend change in waves 1–3. Wave 4 (`/replay` library) needs a usages-list endpoint; that is
  scoped in its own page file.

---

## 3. Waves

Disjoint file scopes, each ending with `npm test` green in `vision-web` and MODULE.md updated.

| Wave | Scope | Files |
|---|---|---|
| **1 — Shell** | Sidebar component, `NAV_MODES` gains groups/collapse metadata, hub pages deleted, `/operate|/monitor|/manage` redirect, `.page` goes fluid, `--bp-md` token | `app.{ts,html,css}`, `shared/ui/app-sidebar.*`, `features/hubs/**`, `styles.css`, `app.routes.ts` |
| **2 — Page bar** | `vision-page-bar` component; every `page-head` migrated; descriptions pruned | `shared/ui/page-bar.*`, `features/*/*.html` |
| **3 — Two-pane** | List+side-panel on Assets, Devices, Alerts, Activity, Roster; `?sel=` param | `features/{assets,devices,alerts,activity,roster}/**` |
| **4 — IA fixes** | Settings split, cockpit `/fly/:assetId`, replay library, cockpit overlay clipping | `features/{settings,fly,replay}/**`, `nav-entries.ts` |

Wave 1 is the one that removes the duplication and is implemented first.

---

## 4. Page design files

| Page | File |
|---|---|
| App shell / navigation | [`design/00-shell.md`](../../extracts/design/00-shell.md) |
| Fly — picker + cockpit | [`design/01-fly.md`](../../extracts/design/01-fly.md) |
| Command | [`design/02-command.md`](../../extracts/design/02-command.md) |
| Wall | [`design/03-wall.md`](../../extracts/design/03-wall.md) |
| Assets | [`design/04-assets.md`](../../extracts/design/04-assets.md) |
| Asset detail | [`design/05-asset-detail.md`](../../extracts/design/05-asset-detail.md) |
| Devices | [`design/06-devices.md`](../../extracts/design/06-devices.md) |
| Add source | [`design/07-add-source.md`](../../extracts/design/07-add-source.md) |
| Alerts center | [`design/08-alerts.md`](../../extracts/design/08-alerts.md) |
| Activity | [`design/09-activity.md`](../../extracts/design/09-activity.md) |
| Replay | [`design/10-replay.md`](../../extracts/design/10-replay.md) |
| Settings | [`design/11-settings.md`](../../extracts/design/11-settings.md) |
| Organization | [`design/12-org.md`](../../extracts/design/12-org.md) |
| Pilots / roster | [`design/13-roster.md`](../../extracts/design/13-roster.md) |
| Asset categories | [`design/14-categories.md`](../../extracts/design/14-categories.md) |
| Inventory reports | [`design/15-reports.md`](../../extracts/design/15-reports.md) |
| CV training | [`design/16-training.md`](../../extracts/design/16-training.md) |
| Pre-flight | [`design/17-preflight.md`](../../extracts/design/17-preflight.md) |
| Debug | [`design/18-debug.md`](../../extracts/design/18-debug.md) |
| Hubs (removed) | [`design/19-hubs.md`](../../extracts/design/19-hubs.md) |
