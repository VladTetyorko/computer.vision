# VISUAL-REFRESH-PLAN — daylight theme + calm content design for `vision-web`

Status: **done** (2026-08-05, W0–W5 all landed). Owner: styling. Remaining work is explicitly
deferred, not a blocker — see W5's own entry below and its full writeup in `vision-web/MODULE.md`'s
dated "VISUAL-REFRESH-PLAN Wave W5" Status entry. Amendment (same day): the app ships **both
themes, user-selectable** — light is the default; dark keeps the current values. Video surfaces
are always dark regardless of theme (F3). Builds directly on the finished
[STYLE-TOKENS-PLAN](STYLE-TOKENS-PLAN.md) two-tier token system — that contract's *names* are kept
verbatim; this plan re-values them. Companion skill (the durable rules any web-ui agent follows):
`.claude/skills/frontend-style/SKILL.md` — this plan is the migration, the skill is the law.

**Post-launch fix (2026-08-05)** — live use surfaced two real bugs W0–W5's `tsc`/test/build-only
verification never would have caught (a screenshot pass on Wall specifically, at a *wide* viewport
with only 2 tiles, is what found them — see [[visual-refresh]] on why a live check matters even
after every wave reports green): (1) **`.surface-dark` only re-declared custom properties, never
the `color`/`background` properties themselves** — a bare `color: var(--text)` on `<body>` resolves
*once*, using whatever scope `<body>` itself sits in, and just inherits down as an already-computed
value; an element lower in the tree that changes `--text` (via `.surface-dark`) doesn't retroactively
change that inherited value for descendants with no `color` of their own (Wall's `<h1
class="page-bar-title">` has none — it read as near-invisible dark-on-dark). Fixed by adding an
explicit `color: var(--text); background: var(--bg);` directly to the `:root[data-theme='dark'],
.surface-dark` block in `styles.css`, restarting inheritance at that exact boundary for any
descendant that never sets its own. (2) **Wall's root is the shared `.page` class**, which is
padding-only with no height/background of its own (unlike Fly's `.cockpit`/Command's
`.command-shell`, each bespoke roots with their own explicit `height: 100dvh`) — with few tiles its
box only grew to content height, leaving a light gap below/beside the grid where `<main>`'s
light-theme background showed through. Fixed with a `.page.surface-dark { min-height: 100dvh; }`
rule scoped to `wall.css` (not the shared `.page` class, which every light-themed page also uses and
must keep its natural content height). Re-verified: `tsc` clean, 111/1804 green, prod build
unchanged, live-checked on both Wall and Fly (no regression). The other three `.surface-dark` roots
(Replay's `.recording-card`, `vision-player`'s `.frame`, preflight's `.checklist`) were each already
audited safe — every one already sets its own explicit background via `.card`/an inline
`background: var(--black)`/`.surface-hud-strong`, so only Wall needed the height fix, and the
`styles.css` color fix benefits all of them for any bare text they might carry.

**W0 done (2026-08-05)** — `src/styles.css` F1/F2/F3 landed with **zero F1 lightness tuning**
(every contrast pairing the DoD names cleared AA on the first check); the plan's own F2 text wrote
the three `-line` tokens as bare hex, which conflicts with "raw hex ONLY in tier 1" — resolved by
adding three new tier-1 names (`--green-300`/`--amber-300`/`--red-300`, same hex values) rather than
literally inlining hex into tier 2. `ThemeStore` (`core/shell/theme-store.ts`) + `index.html`
bootstrap script landed; no toggle UI yet (W1). Full writeup: `vision-web/MODULE.md`'s dated
"VISUAL-REFRESH-PLAN Wave 0" Status entry.

**W2 done (2026-08-05)** — F5 landed on every table/dense list in scope (assets list + card grid,
devices table, roster's both pivots): Lifecycle+Streaming merged into one state chip
(archived/deactivated/live get a chip, the ordinary "active, not streaming" case is a dot + plain
text instead of a fourth chip color); category/protocol de-chipped to muted text everywhere; the
assets Devices count is right-aligned `.mono`; selected row/card is the app-wide F4 2px
`--color-info` bar + `--color-info-soft` tint, replacing each page's own bespoke treatment. F6's
key-value fact grid (two columns, fixed-width muted label column) added to the assets/devices
two-pane detail panels — previously missing entirely. Reports/Activity audited, no table present in
either (already-compliant list/log patterns); Reports' attention-row category chip de-chipped to
text for the same F5 rule. Full writeup: `vision-web/MODULE.md`'s dated "VISUAL-REFRESH-PLAN Wave
W2" Status entry.

**W5 done (2026-08-05)** — tree-wide audit + cleanup closing out the plan. Job 1 swept every
feature W0–W4 hadn't touched (settings, org-settings, alerts, debug, onboarding, warehouse,
labeling, models, training-jobs, categories, hubs, auth, not-found); most were already clean, and
the genuine stragglers were all multi-chip-per-row (F5 rule 4) gaps pre-dating this plan —
onboarding's Discover-on-network Details column (one chip per detail key, unbounded), org-settings'
Users list (group-membership chips + topRole chip + Enabled/Disabled chip all at once), and
labeling's dataset cards (a target-category chip plus one chip per class) all collapsed to muted,
comma-joined text; `shared/ui/event-row.css`'s selected state (Alerts' list) was missing the
`--color-info-soft` tint entirely, same bug W2 already fixed in Roster. Job 2's tree-wide grep found
no raw hex/`rgb()` and no `--hud-*`/`--scrim*` usage outside the already-documented exceptions (plus
one more instance of the same "Leaflet needs a literal draw color" exception,
`core/geofence/geofence-logic.ts`), and a computed AA contrast check found zero failures in either
theme among the non-faint/non-disabled pairings. **`.surface-dark` is applied at exactly the five
sites W4 documented and nowhere else.** Three more F4 selected-state gaps were found in do-not-touch
files (`features/fly/marks-panel.css`/`features/command/marks-panel.css`, `features/replay/
replay-library.css`, `shared/player/sample-box-editor.css`) and reported rather than fixed, since
they sit outside this wave's writable scope. Full writeup: `vision-web/MODULE.md`'s dated
"VISUAL-REFRESH-PLAN Wave W5" Status entry.

**Deferred as follow-up work, not a blocker:**

- **Theme-reactive canvas/Leaflet-polyline recolor.** `TRAIL_COLOR`/its siblings (`fleet-map.ts`,
  `live-map.ts`, `replay-map.ts`, `flight-plan-dialog.ts`), `DEFAULT_BOX_COLOR`/`DEFAULT_BOX_FILL`
  (`detection-overlay-logic.ts`), the canvas `ctx.strokeStyle`/`fillStyle` literals (`player.ts`,
  `sample-box-editor.ts`), and `core/geofence/geofence-logic.ts`'s `KEEP_OUT_COLOR`/`KEEP_IN_COLOR`
  all need a literal colour at draw/construction time, so they can't consume `var(--color-info)`
  directly. A real fix needs `getComputedStyle(...).getPropertyValue(...)` at draw time plus
  re-styling every already-drawn shape on a theme change (`setStyle()` for Leaflet paths, a redraw
  for canvas) — a genuine feature, not a token swap, first flagged by W3/W4 and confirmed still
  open by W5's own sweep.
- ~~Three F4 selected-state gaps in do-not-touch files~~ — **fixed directly (2026-08-05)**, after
  W5's audit surfaced them but stayed out of its writable scope: `features/fly/marks-panel.css` +
  `features/command/marks-panel.css`'s `.mark-row.selected` (was `--panel-hover`, no tint — now
  the bordered-card left-bar recipe `features/assets/assets.css`'s `.asset-card.selected`
  established: `border-left: 2px solid transparent` on the base rule, `border-left-color` +
  `--color-info-soft` on `.selected`), `features/replay/replay-library.css`'s
  `tbody tr.row-selectable.selected` (was `--panel-raised` + an inset shadow with no tint — now
  adds the `--color-info-soft` background), and `shared/player/sample-box-editor.css`'s
  `.box-row.selected` (was a full border-color change — now the same left-bar recipe). Also fixed:
  `features/replay/replay-map.ts`'s `replay-map.css` was still on the pre-F7 `--scrim`/
  `--color-warn` badge + Leaflet-attribution styling (W4 flagged it for W3 mid-flight, too late for
  W3's own concurrent run to pick up) — now the `--panel-raised`/`--border`/`--shadow` light-card
  treatment `shared/map/live-map/live-map.css` already uses. Full tree re-verified after these:
  `tsc` clean, 111 files / 1804 tests green, production build unchanged (393.31 kB, same
  pre-existing budget warning).
- `shared/ui/two-pane/two-pane.css`'s `.two-pane-scrim` (the mobile drawer backdrop) uses `--scrim`
  rather than the sanctioned `--hud-bg-strong` generic-modal-backdrop convention
  `shared/ui/confirm-dialog.css` established — both are theme-invariant, so this is a minor
  token-category mismatch, not a visible theme bug. Low priority.

## Why

Two user directives:

1. **Colour.** The app must stop looking dark and "AI-styled". Target: simple, calm,
   non-eye-damaging. Note what "non-eye-damaging" actually means in a *light* UI: the harm is
   glare from pure `#ffffff` canvases and harsh `#000` text — so the target is a **cool paper-gray
   canvas, ink (not black) text, low-saturation chrome**, not a naive white flip.
2. **Content.** Side panels, the assets map, and tables should be designed from the user's
   interest points first, then simplicity, then alignment — not accreted feature by feature.

### Design direction: "daylight chart"

The subject is a drone-fleet command point. Its daylight-world materials are aviation sectional
charts, printed checklists, instrument panels in sunlight — cool gray paper, ink, one aviation
blue, colour reserved for status. **B612 Mono stays** as the identity signature (avionics numerals
work in daylight too). Explicitly rejected: warm-cream + serif + terracotta (template look #1),
dark + neon accent (template look #2, what we have now reads close to), purple/gradient/glass "AI
product" styling anywhere outside the video HUD.

**Dark does not disappear — it retreats to where it earns its place.** Surfaces whose content is
live video (Fly cockpit, Wall) keep the dark HUD treatment: a light frame around dark video is
glare, and frosted scrims over video must stay dark regardless of app theme. Mechanism below.

## Users and interest points (drives every content decision)

| User | Comes to the app to… | Surfaces |
|---|---|---|
| Operator | fly *now*; find their asset in ≤2 clicks | Fly (dark), sidebar |
| Commander | see the whole fleet: where, live, needs-attention | Command + map, tables |
| Crew / referee | shared picture: marks, wall, replay | Wall (dark), Command |

Interest-point rankings each surface is designed around:

- **Sidebar** — (1) where am I, (2) one-click switch between ~6 frequent destinations,
  (3) ambient status (online, N live) *without stealing attention*. Everything else is secondary
  and must look secondary.
- **Detail side panel (two-pane)** — triage without navigation: (1) identity, (2) is it OK
  right now, (3) 2–3 next actions. Deep work goes to "Open full ›", never crammed here.
- **Tables** — (1) scan: find a row by name, (2) compare: read one column down many rows,
  (3) act: row actions without leaving the list.
- **Assets map (Command)** — (1) where is everything, (2) which are live, (3) which need
  attention, (4) clicking a marker selects the same thing the list selects (one selection model).

## Frozen contract

### F1 — Tier-1 light ramp additions (`src/styles.css` `:root`; raw hex allowed here only)

The existing dark ramp steps stay (the dark enclave still consumes them). New steps:

```
/* Light neutrals — paper canvas → white raised */
--gray-50:  #f9fafb   /* panel surface */
--gray-75:  #eef1f4   /* page canvas */
--gray-150: #e5e9ee   /* hover tint */
--gray-200: #d9dee5   /* border */
--gray-350: #b9c1cc   /* border-strong */
--gray-500: #5b6574   /* muted text on light */
--gray-450: #8a94a2   /* faint text on light */
--ink-900:  #1c2431   /* body text on light — ink, not black */

/* Intent hues, darkened/tinted for light backgrounds (AA against --gray-50) */
--blue-600: #2e6bcf   --blue-50: #e4edfa   --blue-800: #234f92
--green-600:#1f7f4c   --green-50:#e1f2e8   --green-700:#176038
--amber-600:#b26a00   --amber-50:#faf0dc   --amber-700:#7d5200
--red-600:  #c9333f   --red-50:  #fbe5e7   --red-700:  #a32833
--rose-600: #d81b60
```

W0 may tune any value's *lightness* while landing the theme visually; names and roles are frozen.
No component ever references a tier-1 name (unchanged rule).

### F2 — Tier-2 re-point (names 100% unchanged; this is the whole theme flip)

```
--bg:#eef1f4(gray-75)  --panel:gray-50  --panel-raised:#ffffff(--white)  --panel-hover:gray-150
--border:gray-200      --border-strong:gray-350
--text:ink-900         --text-muted:gray-500   --text-faint:gray-450
--color-info:blue-600  --color-info-soft:blue-50  --color-info-text:blue-800  --color-on-info:--white
--color-success:green-600 -soft:green-50 -line:#a9d8bc -text:green-700
--color-warn:amber-600    -soft:amber-50 -line:#e6cc93 -text:amber-700
--color-danger:red-600    -soft:red-50   -line:#efb6bb -text:red-700
--color-on-danger:--white --color-live:rose-600
--shadow: 0 4px 16px rgb(23 32 48 / 12%)
```

Elevation stays lightness-only and keeps its direction (raised = lighter): canvas gray-75 →
panel gray-50 → raised white. `color-scheme: light` on `:root`.

### F3 — Two themes + the always-dark enclave

Theme = which values the tier-2 semantic names resolve to. Components never know the theme.

- `:root` declares the **light** values (F2). Light is the default theme.
- One shared dark block, declared once in `styles.css`:
  `:root[data-theme='dark'], .surface-dark { …every tier-2 token at today's dark values…;
  color-scheme: dark; }` — the selector list is the whole mechanism: the same declarations back
  the user-chosen dark theme *and* the enclave. Inside the light theme, `.surface-dark` on an
  element out-cascades `:root`'s inherited values for its own subtree — no specificity games.
- `.surface-dark` is carried permanently by full-bleed video surfaces (Fly cockpit root, Wall
  root, replay player region) — they are dark in **both** themes; a light frame around live video
  is glare. Full-surface only — never on a lone widget inside a themed page.
- **Theme selection:** a small `ThemeStore` (`core/`, per UI-ARCHITECTURE layering) holding
  `'light' | 'dark'`, persisted to localStorage (`vision.theme`, default `light`), applied as
  `data-theme` on `<html>` at bootstrap and on change. Toggle UI: sidebar footer (W1) +
  Settings › Appearance (W1). No `prefers-color-scheme` auto mode for now — explicit choice only.
- `--hud-*` and `--scrim*` tokens are compositing-over-video values: theme-invariant, and
  **only legal inside `.surface-dark`**. Over a *map* on a themed page, floating controls use
  `--panel-raised` + `--border` + `--shadow` (which theme correctly) instead of frosted pills.
- Replay: the player region joins the enclave; the library list around it stays themed (W4
  decides the exact boundary and documents it in the component).

### F4 — Selection language (one, app-wide)

Selected/active rows everywhere (sidebar active route, table selected row, list rows, map-selected
marker's list twin): `2px` left inset bar in `--color-info` + `--color-info-soft` background tint.
No other selected-state styling may be invented per feature.

### F5 — Table rules (applied to every list page)

1. Column order: name → classification → state → numbers → timestamps → actions (kebab last,
   narrow, fixed).
2. Text left-aligned; numbers right-aligned in `.mono` (tabular-nums); nothing centered.
3. `th` and `td` share identical horizontal padding so headers sit exactly over cell content.
4. At most **one chip per row** in list view; secondary state renders as dot + plain text.
   Category/classification is muted text, not a chip.
5. Uniform row height (~36px), `.truncate` + `title` on the name cell; empty cells show a faint
   `—`, never blank.
6. Selected row per F4.

### F6 — Detail-panel anatomy (two-pane detail, every consumer)

Top→bottom, always in this order: title row (name + close) → status chip row → **key-value fact
grid** (two columns, muted label column of fixed `--space-*` width, values aligned) → sections
(`h3` + content) → actions row (one `.btn` primary max, then secondaries, "Open full ›" last).

### F7 — Map presentation (fleet map on light surfaces)

- Default basemap follows the theme: light tiles in the light theme, dark tiles in the dark
  theme and inside `.surface-dark` (`MAP_LAYERS` already supports persisted per-user choice —
  only the *default* changes; an explicit user pick always wins).
- One marker glyph; state carried by colour only: live `--color-live`/success, offline
  `--text-faint`, attention `--color-danger`; selected marker gets a `--color-info` ring.
- Overlay controls (layer picker, legend, counts): `--panel-raised` + border + shadow cards,
  corners on the 8px grid — no frosted dark pills over a light map (F3).
- Legend = the existing count chips (streaming / offline / no-position), one quiet row, nothing
  more.

## Waves (disjoint file scopes; each ends green + MODULE.md updated)

- **W0 — theme foundation + primitives (small, high-care; `src/styles.css` +
  `core/` ThemeStore + `index.html` bootstrap).**
  Add F1 ramp, re-point F2 as the `:root` light default, add the shared dark block
  (`:root[data-theme='dark'], .surface-dark`, F3), re-value `--shadow` per theme, `color-scheme`
  per scope; add `ThemeStore` (persisted, applies `data-theme` on `<html>`; unit-tested; no
  visible toggle yet — W1 owns the UI). Audit base primitives on light: `.btn` (on-info ink → white), `.chip.*` soft pairs,
  `.notice.*`, form controls (raised-white fields on gray canvas + border now do the "visible
  control" job QF-3 wanted), focus ring contrast, leaflet popup/tooltip (already tokenized —
  verify only). **Fix the `.segmented > .btn { background-color: var(--text-muted) }` anomaly**
  (inactive segments painted muted-gray — wrong in both themes; inactive = `.btn.secondary`
  surface). Everything else compiles untouched because only tier-2 values moved.
- **W1 — shell + sidebar (`app/app.css`, `shared/ui/app-sidebar/**`, `shared/ui/page-bar/**`). Done
  (2026-08-05) — see `vision-web/MODULE.md`'s dated Wave 1 entry.**
  Themed restyle plus the interest-point simplification: quiet group labels (drop their icons —
  they compete with row icons; faint small-caps text only), one row height with a strict icon
  column, active row per F4, footer consolidated to a single status line (live chip + online dot
  + bell; demo button and identity stay but visually quiet), "Upcoming" group collapsed by
  default with no per-row `soon` chips (the group title says it). **Theme toggle UI**: a compact
  light/dark switch in the sidebar footer wired to `ThemeStore`, plus the same control in
  Settings (`features/settings`, Appearance).
- **W2 — tables + two-pane (`features/assets`, `features/devices`, `features/roster`,
  `features/reports`, `features/activity`, `shared/ui/two-pane/**`, `shared/ui/side-panel.*`).**
  Apply F5 to every table (assets list: Devices column right-aligned mono; category de-chipped;
  one chip per row — merge Lifecycle+Streaming presentation into state chip + dot/text). Apply F6
  to the assets/devices detail panels (add the missing key-value fact grid). Selected row per F4.
- **W3 — command + map (`features/command/**`, `shared/map/**`). Done (2026-08-05) — see
  `vision-web/MODULE.md`'s dated Wave 3 entry.** F7: light basemap default,
  marker/selection recolour, overlay controls to light cards, legend pass. Command page itself is
  the reference light layout — align its cards/stat tiles to the new surfaces.
- **W4 — dark enclaves (`features/fly/**`, `features/wall/**`, `features/replay/**`,
  `shared/player/**`, `features/preflight/**`).** Apply `.surface-dark` at each root; sweep for
  light-token assumptions that crept in; decide + document the replay player/library boundary;
  verify HUD pills unchanged over video. **Done (2026-08-05)** — see `vision-web/MODULE.md`'s dated
  "VISUAL-REFRESH-PLAN Wave W4" Status entry.
- **W5 — audit + cleanup (tree-wide, read-mostly). Done (2026-08-05)** — see
  `vision-web/MODULE.md`'s dated "VISUAL-REFRESH-PLAN Wave W5" Status entry. Remaining features
  (settings, org, alerts, debug, onboarding, warehouse, labeling, models, training) swept for
  stragglers — most were already clean; the genuine fixes were all multi-chip-per-row (F5 rule 4)
  gaps, collapsed to muted comma-joined text. Grep-clean confirmed: no raw hex outside tier 1 (one
  more instance of the already-known canvas/Leaflet literal-color exception found and deferred, see
  above), no `--hud-*`/`--scrim*` outside `.surface-dark` scopes, no bespoke selected-state inside
  this wave's writable scope (three more found in do-not-touch files, reported as follow-up).
  Contrast audit clean in both themes (AA: normal text ≥4.5:1, muted ≥4.5:1 against `--panel`,
  faint used only for captions — no failures). `.surface-dark` confirmed applied only at W4's five
  documented roots. `vision-web/MODULE.md` + this plan's status updated.

Per-wave DoD (repo standard): `npm run test:ci` + `npx tsc --noEmit` + build green; no raw
hex/off-grid px introduced; screenshots taken for the touched surfaces; MODULE.md updated.

## Out of scope

- Any backend/Java change; any route/IA change (NAV-IA-REDESIGN owns that).
- A `prefers-color-scheme` auto mode (explicit light/dark choice only for now; auto can be
  layered on later precisely because everything is tier-2-driven).
- New fonts (2 families stay), icon redesign, chart/dataviz restyle beyond token inheritance.
