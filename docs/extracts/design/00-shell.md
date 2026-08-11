# 00 — App shell / navigation

**Files:** `app.ts`, `app.html`, `app.css`, `features/hubs/nav-entries.ts`, `styles.css`
**Wave:** 1

## Current state

A 40px top bar: brand · three mode triggers each with a `<details>` dropdown · status cluster
(identity chip, notification bell, `N live`, `ONLINE`). Below 640px all three modes collapse into one
labelled `Menu` disclosure. `<main>` holds a `router-outlet`; `.page` inside each feature caps at
1400px.

`NAV_MODES` is consumed by exactly two things: these dropdowns, and the three hub pages.

## Problems

- **Duplicated navigation** (F1) — the dropdowns and the hub pages render the same array; the hub
  pages add a click and a chunk load for nothing.
- **No sense of place** (F2) — siblings invisible, active page unmarked outside its own `<h1>`.
- **Clipping** (F3) — the Manage panel shows 7 of 10 entries at 961px height, no scroll affordance.
- **Role filter applied in one copy only** (F10) — `managerOnly` is honoured by `ManageHub`, ignored
  by the dropdown.
- **Full-bleed views pay for global chrome** (F11) — cockpit loses 72px to header + failsafe banner.
- Three separate nav markup blocks (`.modes-wide`, `.modes-narrow`, hub tiles) to keep in sync.

## Suggested design

A persistent left sidebar, single source of nav truth, no dropdowns.

```
 240px expanded                    56px collapsed
┌────────────────┐                ┌────┐
│ ◉ Vision     ⟨ │                │ ◉ ⟩│
├────────────────┤                ├────┤
│ OPERATE        │                │ ── │
│ ▎🛩 Cockpit  ●2│  ← active      │ ▎🛩 │
│   ▦ Wall       │                │  ▦ │
│   ☑ Pre-flight │                │  ☑ │
│ MONITOR        │                │ ── │
│   ◎ Command    │                │  ◎ │
│   ⚠ Alerts   ⁴ │                │  ⚠ │
│   ⟲ Activity   │                │  ⟲ │
│ MANAGE         │                │ ── │
│   ✦ Assets     │                │  ✦ │
│   + Add source │                │  + │
│   ⌄ Advanced   │                │  ⋯ │
├────────────────┤                ├────┤
│ ⚙ Settings     │                │  ⚙ │
│ AD Admin     ⌄ │                │ AD │
└────────────────┘                └────┘
```

**Structure**

| Region | Contents |
|---|---|
| Head | Brand + collapse toggle (`[`) |
| Body (scrolls) | Group label → leaves, ×3. `⌄ Advanced` disclosure holds `diagnostics` + `advanced` groups; `⌄ Upcoming` holds `badge:'soon'` entries |
| Foot (pinned) | Settings, identity chip w/ menu, `ONLINE`/offline dot |

**Behaviour**

1. Group headers are non-interactive labels — this deletes the hub pages.
2. Active leaf: 2px accent left bar, raised background, `aria-current="page"`.
3. Collapse state in `localStorage['vision.sidebar']`, toggled by `[` and the chevron.
4. On `/fly`, `/wall`, `/command` the sidebar starts collapsed and expands on hover as an overlay —
   it never reflows the video (F11). Manual toggle wins over the auto-rule for the session.
5. `managerOnly` filtered once, here (F10). `soon` entries are dimmed and grouped last (F9).
6. Badges: live-stream count on Cockpit/Wall, unacknowledged count on Alerts. The header's `N live`
   chip moves here, next to what it describes.
7. Keyboard: `[` toggle, `g` then `c/w/m/a` jumps to Cockpit/Wall/Command/Assets.

**Top bar** shrinks to a page-scoped bar owned by each page (see `design/02-page-bar` rules in
`NAV-IA-REDESIGN-PLAN.md` §2.2). The only globals left are the offline banner, toast host and undo
toast, which move to the shell's right edge.

**Responsive**

| Breakpoint | Sidebar |
|---|---|
| ≥1024px (`--bp-md`) | Docked, expanded or rail |
| 640–1024px | Docked rail; hover/click overlays the expanded panel |
| <640px (`--bp-sm`) | Hidden; hamburger in the page bar opens a full-height sheet |

## Refactor list

- **Add** `shared/ui/app-sidebar.{ts,html,css}` + `app-sidebar.spec.ts`.
- **Add** to `nav-entries.ts`: `NavMode.entries` gains nothing new, but `NavMode` gains
  `collapsedGroups: readonly ('configuration'|'diagnostics'|'advanced')[]` so the sidebar knows what
  to fold. `hubRoute` becomes `primaryRoute` (where the group header would go if it were a link — used
  only by the redirect in `app.routes.ts`).
- **Delete** `features/hubs/{operate-hub,monitor-hub,manage-hub,hub-pages.spec,tile-accent}.ts` and
  `hubs.routes.ts`'s three hub entries. Keep `nav-entries.ts`, `nav-entries.spec.ts`, `coming-soon.ts`,
  `route-audit-logic.ts`.
- **Rewrite** `app.html` — sidebar + `<main>`; drop `.modes-wide`, `.modes-narrow`, `.status`.
- **Rewrite** `app.css` — shell grid `grid-template-columns: var(--sidebar-w) 1fr`.
- **Edit** `app.routes.ts` — `/operate`→`/fly`, `/monitor`→`/command`, `/manage`→`/assets` redirects
  (keeps old bookmarks alive, mirrors the `/map`→`/command` precedent).
- **Edit** `styles.css` — `.page` fluid; add `--bp-md: 1024px`, `--sidebar-w`, `--sidebar-w-rail`.
- **Edit** `app.routes.spec.ts` for the redirects.

## Acceptance

- Every `NAV_MODES` leaf reachable in one click from any page, at 1280×720 with no clipping.
- `/operate`, `/monitor`, `/manage` still resolve (redirect), no 404.
- A PILOT session sees no `managerOnly` entry anywhere.
- Cockpit at 1280×720 shows no global header above the failsafe banner.
