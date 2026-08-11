# 19 — Hubs (Operate / Monitor / Manage) — removed

**Files:** `features/hubs/**`
**Wave:** 1

## Current state

Three routed pages — `/operate`, `/monitor`, `/manage` — each rendering `page-head` plus a
`vision-tile-grid` of `NAV_MODES` entries for its own mode. `/manage` additionally groups its tiles
(`Configuration`, `Diagnostics`, `Advanced`) and filters `managerOnly` entries.

Measured content:

| Hub | Tiles | What the page adds over the dropdown above it |
|---|---|---|
| `/operate` | 5 | nothing |
| `/monitor` | 5 | nothing |
| `/manage` | 10 | group headers + role filtering |

## Problems

**These pages are the duplicated half of a duplicated navigation system (F1).** Every entry they show
is already in the top-bar dropdown that sits directly above them, rendered from the same
`NAV_MODES` array. Landing on a hub costs a click and a lazy-chunk load and yields no information the
user did not already have.

Secondary consequences:

- **Two nav implementations drift.** `managerOnly` is honoured here and ignored by the dropdown
  (F10) — `manage-hub.ts`'s own doc comment records this as a known, unfixed follow-up. That drift is
  structural, not accidental: two renderers of one array will always risk it.
- **They are the app's landing pattern for a mode**, so the most common navigation act (pick a mode,
  then pick a page) is two clicks and a page load instead of one click.
- 60–70% vertical emptiness on `/operate` and `/monitor` at 1854×961.
- Tiles give `soon` scaffolds the same size and weight as shipped features (F9).
- `/manage`'s grouping and role-filtering — the only genuine value in any of the three — is exactly
  what the sidebar takes over.

## Suggested design

**Delete all three.** Their two useful behaviours move into the sidebar, which is where navigation
belongs and where they apply on every page rather than only on a hub:

| Hub behaviour | Where it goes |
|---|---|
| Grouped entries (`Configuration` / `Diagnostics` / `Advanced`) | Sidebar `⌄ Advanced` disclosure (`00-shell.md`) |
| `managerOnly` filtering | Sidebar, applied once — fixes F10 |
| `soon` badges | Sidebar `⌄ Upcoming` group, dimmed |
| Entry descriptions | Sidebar tooltips on the collapsed rail |

Routes are kept as redirects so existing bookmarks and any in-app `Back to Manage` links survive:

```ts
{ path: 'operate', pathMatch: 'full', redirectTo: 'fly' },
{ path: 'monitor', pathMatch: 'full', redirectTo: 'command' },
{ path: 'manage',  pathMatch: 'full', redirectTo: 'assets' },
```

This mirrors the precedent already in the codebase — `map.routes.ts` (`/map` → `/command`) and
`warehouse.routes.ts` (`/warehouse` → `/assets`) both fold a removed launcher page into its successor
the same way.

## Refactor list

- **Delete** `operate-hub.ts`, `monitor-hub.ts`, `manage-hub.ts`, `hub-pages.spec.ts`, `tile-accent.ts`.
- **Rewrite** `hubs.routes.ts` as the three redirects above.
- **Keep** `nav-entries.ts` (+ spec) — it becomes the sidebar's single consumer, which is the point.
- **Keep** `coming-soon.ts` and `route-audit-logic.ts` (+ spec) — used by `soon` entries and the route
  audit respectively, neither tied to the hub pages.
- **Remove** every `Back to Manage` / `Back to hub` button in feature pages (`16-training.md` names
  one; grep for others).
- **Consider** renaming the folder `features/hubs/` → `features/navigation/` once the hub pages are
  gone, since `nav-entries.ts` is no longer about hubs. Cosmetic; do it in wave 1 or not at all.

> **Wave 1 outcome.** `vision-nav-tile` **and** `vision-tile-grid` both turned out orphaned once the
> hub pages went — this file originally assumed the Wall still consumed `tile-grid`, but the Wall has
> always used its own `wall-tile.ts` plus a bespoke CSS grid. Both components and their specs were
> deleted (grep-verified: no remaining code reference, only doc-comment mentions). `navModeById()` in
> `nav-entries.ts` is now dead in production code — only its own spec calls it — and is a cleanup
> candidate for wave 2.

## Acceptance

- `/operate`, `/monitor`, `/manage` all resolve via redirect — no 404, no blank page.
- `vision-tile-grid` has no remaining consumer for navigation (Wall keeps it for video tiles).
- A PILOT session sees no `managerOnly` entry anywhere in the app.
- No page contains a link back to a hub.
