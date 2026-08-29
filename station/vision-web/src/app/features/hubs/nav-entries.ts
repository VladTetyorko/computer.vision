import type { IconName } from '../../shared/ui/icon-registry';

/**
 * The route→group map, as data — the **one** source of truth for app navigation.
 *
 * **Five job-based groups, not three role-based modes (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1, wave W1).**
 * This file used to hold three `NAV_MODES` (`operate`/`monitor`/`manage`), the last of which had grown
 * into a 14-entry junk drawer mixing inventory, people, a CV studio, geo tooling and diagnostics behind
 * one collapsed `Advanced` disclosure and one collapsed `Upcoming` disclosure (docs/plans/done/UI-REDESIGN-PLAN.md,
 * docs/conclusions/UX-SIMPLIFY-REVIEW.md). WAREHOUSE-UX-PLAN.md §1.3 found a manager facing 25 entries,
 * 12 of them manager-only, four of them stubs. **`manage` is split into `fleet` (things and people),
 * `vision` (the CV studio + geo — a different pipeline, a different persona) and `system` (diagnostics)**;
 * `operate`/`monitor` keep their meaning. `system` is special: it renders in the sidebar **footer**, next
 * to the identity chip, not as a sixth section of the scrollable body (`NavMode.footer`, read only by
 * `shared/ui/app-sidebar/**`). This supersedes UI-REDESIGN's three-mode Operate/Monitor/Manage split and
 * NAV-IA-REDESIGN §2.1 rule 7's `soon`-under-an-`Upcoming`-disclosure convention (WAREHOUSE-UX-PLAN.md §7)
 * — see the `badge` paragraph below.
 *
 * **No more tiers — every entry in a group's `entries` array renders, flat, always** (WAREHOUSE-UX-PLAN.md
 * §3.1 rule 1: "an unbuilt area is not a nav entry"). The old `primary`/`advanced`/`upcoming` split
 * (`navTiers()`, deleted this wave) is gone along with both collapsed disclosures:
 * - **`advanced` is gone because `system` *is* the advanced tier now** — it was already exactly
 *   Debug + System status + (now) Audit trail, promoted from a Manage sub-group to its own top-level,
 *   footer-rendered group instead of a `<details>` a manager had to know to open.
 *   `configuration` (CV training/registry/Geo regions, Categories/Reports) is gone the same way —
 *   promoted to `vision`/folded into `fleet`, never behind a disclosure to begin with.
 * - **`upcoming` is gone because every remaining `badge: 'soon'` entry left the rail outright** —
 *   Flight plans/missions, Saved Wall layouts, Firmware, Maintenance/health. Their routes and the
 *   shared `ComingSoon` placeholder (`coming-soon.ts`) are untouched — a bookmark or an old in-app link
 *   still resolves — they are simply no longer reachable *from the sidebar*, the same "an unbuilt area
 *   is not a nav entry" rule. `NavEntry.badge`/the `'soon'` literal type is deleted outright: nothing
 *   else in this file, or anywhere in `vision-web`, read it (grepped clean).
 *
 * **Only a single-source-of-truth renderer** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F1) — `shared/ui/app-sidebar/**`
 * is still the one place that reads `NAV_MODES`; the retired `/operate`/`/monitor`/`/manage` hub launcher
 * pages stay deleted, their paths still redirecting to somewhere real (`features/hubs/hubs.routes.ts`).
 *
 * **De-duplicated nav (docs/conclusions/UX-SIMPLIFY-REVIEW.md F1) — every destination has exactly one canonical
 * entry**, guarded by `nav-entries.spec.ts`'s standing regression test: no `to` value may appear on more
 * than one `NavEntry` anywhere in `NAV_MODES`.
 *
 * **Canonical labels unchanged where not explicitly renamed this wave** — the pilot cockpit is still
 * **"Cockpit"** (`/fly`), never "Fly"/"Detection"/"Vision" (docs/conclusions/UX-SIMPLIFY-REVIEW.md F1); the
 * live-stream wall is still **"Wall"** (`/wall`); the manager's map-first dashboard is still
 * **"Command"** (`/command`). Four labels change this wave, each an explicit WAREHOUSE-UX-PLAN.md §3.1
 * rename: **Assets → "Inventory"**, **Add source → "Add vehicle"**, **Pilots / roster → "Crew"**, and
 * **Pre-flight checklist → "Readiness"** (its route, `/operate/preflight`, and its page's own `<h1>`
 * "Fleet readiness" are both unchanged — only the nav label was ever "Pre-flight checklist" while the
 * page itself already said "Readiness"/"Fleet readiness", per WAREHOUSE-UX-PLAN.md §1.2 finding P3).
 *
 * **Detection defaults and Controller leave the rail entirely** (WAREHOUSE-UX-PLAN.md §3.1 rule 5) — a
 * detection profile and a transmitter layout are configuration a user visits rarely, not a
 * day-to-day Operate/Manage door. Both routes (`/settings/detection`, `/manage/controller`) are
 * untouched and ungated; they are now reached from a small "Settings" link list on `/settings`
 * (`features/settings/account-settings.html`) instead of from here.
 *
 * **Three entries have no explicit new home named in the WAREHOUSE-UX-PLAN.md §3.1 mermaid diagram —
 * Asset categories, Inventory reports, Devices.** All three are still fully built, working pages; W1 is
 * a pure IA regroup, not a feature removal, so none of them is dropped from the rail the way a genuine
 * `badge: 'soon'` stub is. They join `fleet` — "things and people" is exactly their job (categories and
 * reports are fleet-taxonomy/fleet-reporting; Devices is the raw link table WAREHOUSE-UX-PLAN.md §3.3
 * plans to fold into Inventory's own "Links" tab at wave **W4**, not this one). `nav-entries.spec.ts`
 * documents the resulting manager/pilot entry counts and flags where they diverge from the plan's own
 * illustrative "15/10" (that figure is the *eventual*, post-W7 count with Maintenance and the merged
 * Inventory page folded in — neither exists yet at W1).
 *
 * **`managerOnly`** — hidden unless `canManageOrg(topRole)` (`core/org/org-logic.ts`, ADMIN/MANAGER) is
 * true, the same gate `shared/ui/identity-chip.ts`'s Organization link and `core/org/org-guard.ts`'s
 * route guard use. Applied exactly once, in `shared/ui/app-sidebar/app-sidebar.ts#modes`. **Every route
 * whose nav entry is `managerOnly` now also carries `canActivate: [orgGuard]` on the route itself**
 * (WAREHOUSE-UX-PLAN.md §3.1 rule 6, closing PLATFORM-AUDIT-UI D2/D3: a `managerOnly` nav entry with no
 * route guard was a door a pilot could still type into) — see each touched `*.routes.ts`'s own doc
 * comment. `System status` keeps its one documented exception: `diagnostics`-flavoured but not
 * `managerOnly` (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1) — an operator whose CV pipeline just died needs to
 * see why, the same reasoning `/command` already applies more broadly. `Settings` is deliberately not
 * `managerOnly` either — every signed-in user, pilot included, owns account/detection/controller
 * preferences.
 */
export type NavModeId = 'operate' | 'monitor' | 'fleet' | 'vision' | 'system';

export interface NavEntry {
  readonly icon: IconName;
  readonly name: string;
  readonly description: string;
  /** A real, functional route — every entry left in `NAV_MODES` routes straight at a real page now
   *  that every `badge: 'soon'` stub has left the rail (see this file's own class doc). */
  readonly to: string;
  /**
   * Hidden unless `canManageOrg(topRole)` is true — see this file's own class doc. Every route this
   * gates has a matching `canActivate: [orgGuard]` on its own `*.routes.ts` entry (WAREHOUSE-UX-PLAN.md
   * §3.1 rule 6), with the two documented exceptions named there (`System status`, `Settings`).
   */
  readonly managerOnly?: boolean;
}

export interface NavMode {
  readonly id: NavModeId;
  readonly label: string;
  readonly icon: IconName;
  /** Redirect target for the retired hub path of the same name, where one still exists — see
   *  `features/hubs/hubs.routes.ts`. `fleet`/`vision`/`system` never had a hub launcher of their own
   *  (they are new top-level groups carved out of the old `manage`), so this is informational/a
   *  landing-page pointer for those three, not a live redirect target. */
  readonly primaryRoute: string;
  /**
   * `true` for exactly one group (`system`) — it renders in the sidebar **footer**, next to the
   * identity chip, instead of as a section of the scrollable body (WAREHOUSE-UX-PLAN.md §3.1: "SYSTEM
   * (footer, with identity chip)"). `shared/ui/app-sidebar/app-sidebar.ts` reads this to split
   * `NAV_MODES` into `bodyModes`/`systemMode`; both still render their group label as a plain
   * non-interactive `<div>`, never a link (NAV-IA-REDESIGN §2.1 rule 1 — unchanged by this wave).
   */
  readonly footer?: boolean;
  readonly entries: readonly NavEntry[];
}

export const NAV_MODES: readonly NavMode[] = [
  {
    id: 'operate',
    label: 'Operate',
    icon: 'operate',
    primaryRoute: '/fly',
    entries: [
      {
        icon: 'cockpit',
        name: 'Cockpit',
        description: 'Fly one drone — video, telemetry, flight control, and live CV tuning in one view.',
        to: '/fly',
      },
      {
        icon: 'grid',
        name: 'Wall',
        description: 'Every live stream at once, auto-paused off-screen — pick one to watch full-screen.',
        to: '/wall',
      },
      {
        icon: 'list',
        name: 'Readiness',
        // Renamed from "Pre-flight checklist" (WAREHOUSE-UX-PLAN.md §3.1 rule 4) — the page's own
        // `<h1>` ("Fleet readiness") already said this; only the nav label lagged. Route unchanged.
        description: "The live status card for any drone — saved, editable templates are coming.",
        to: '/operate/preflight',
      },
    ],
  },
  {
    id: 'monitor',
    label: 'Monitor',
    icon: 'monitor',
    primaryRoute: '/command',
    entries: [
      {
        icon: 'gauge',
        name: 'Command',
        description: 'Fleet-wide status, the attention queue, and the live map — one map-first view.',
        to: '/command',
      },
      {
        icon: 'history',
        name: 'Activity',
        description: 'Your own recent actions across the fleet.',
        to: '/activity',
      },
      {
        icon: 'replay',
        name: 'Replay library',
        description: 'Scrub any finished flight, frame by frame.',
        to: '/replay',
      },
      {
        icon: 'alert',
        name: 'Alerts center',
        description: 'The live detection-events feed — saved thresholds and acknowledgement are coming.',
        to: '/monitor/alerts',
      },
    ],
  },
  {
    id: 'fleet',
    label: 'Fleet',
    icon: 'warehouse',
    primaryRoute: '/assets',
    entries: [
      {
        icon: 'drone',
        name: 'Inventory',
        // Renamed from "Assets" (WAREHOUSE-UX-PLAN.md §3.1 rule 4). Still ungated — reading the
        // fleet is not a management action (docs/conclusions/UX-SIMPLIFY-REVIEW.md F3, unchanged by this wave).
        description: 'Every asset, asset-first — search, filter, and watch, open, or archive.',
        to: '/assets',
      },
      {
        icon: 'plus',
        name: 'Add vehicle',
        // Renamed from "Add source". managerOnly mirrors `POST /api/assets`'s own `canManageOrg()`
        // gate (docs/plans/done/OPS-UX-PLAN.md §2 A4) — unchanged by this wave.
        description: 'Enter an address, scan the network, listen for a drone, or simulate one — four steps.',
        to: '/add-source',
        managerOnly: true,
      },
      {
        icon: 'pilot',
        name: 'Crew',
        // Renamed from "Pilots / roster".
        description: "A dedicated roster across every asset's pilot assignments.",
        to: '/manage/roster',
        managerOnly: true,
      },
      {
        icon: 'category',
        name: 'Asset categories',
        // No new home named in WAREHOUSE-UX-PLAN.md §3.1's mermaid diagram — see this file's own
        // class doc paragraph on the three carried-over entries. Folds into Inventory's own
        // "Categories" tab at wave W4; stays its own page and nav entry until then.
        description: 'Every category, with live counts — creating and editing categories is coming.',
        to: '/manage/categories',
        managerOnly: true,
      },
      {
        icon: 'report',
        name: 'Inventory reports',
        description: 'A live, read-only fleet dashboard — exportable reports are coming.',
        to: '/manage/reports',
        managerOnly: true,
      },
      {
        icon: 'chip',
        name: 'Devices',
        // Folds into Inventory's own "Links" tab at wave W4 (WAREHOUSE-UX-PLAN.md §3.3) — stays its
        // own page and nav entry until then, same reasoning as Asset categories/Inventory reports above.
        description: 'The raw device table or grid — protocol, URI, lifecycle, and the archived toggle.',
        to: '/devices',
        managerOnly: true,
      },
    ],
  },
  {
    id: 'vision',
    label: 'Vision',
    icon: 'target',
    primaryRoute: '/manage/training',
    entries: [
      {
        icon: 'target',
        name: 'CV training',
        description: 'Capture live frames, correct the boxes, and export YOLO datasets to improve detection models.',
        to: '/manage/training',
        managerOnly: true,
      },
      {
        icon: 'archive',
        name: 'CV model registry',
        description: 'Every model cv-service knows about — promote one to make it the live default for new detections.',
        to: '/manage/training/models',
        managerOnly: true,
      },
      {
        icon: 'satellite',
        name: 'Geo regions',
        description: 'Reference-imagery regions for visual geolocation — ingest a bounding box, then watch it index.',
        to: '/manage/geo/regions',
        managerOnly: true,
      },
    ],
  },
  {
    id: 'system',
    label: 'System',
    icon: 'gear',
    primaryRoute: '/manage/system',
    // Renders in the sidebar footer, next to the identity chip — see `NavMode.footer`'s own doc comment.
    footer: true,
    entries: [
      {
        icon: 'signal',
        name: 'System status',
        // The one deliberate non-managerOnly diagnostics entry (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1) — an
        // operator whose CV pipeline just died needs to see why. Unchanged by this wave.
        description: 'What the platform reports about its own health — subsystems, live transport, and system events.',
        to: '/manage/system',
      },
      {
        icon: 'shield',
        name: 'Audit trail',
        // Moved from Monitor into System (WAREHOUSE-UX-PLAN.md §3.1) — still mirrors the backend's
        // own `AuditController#list` `canManageOrg()` gate (docs/plans/done/OPS-UX-PLAN.md §3 B1).
        description: 'Who changed what, fleet-wide — actor, action, target, and result.',
        to: '/monitor/audit',
        managerOnly: true,
      },
      {
        icon: 'gear',
        name: 'Debug',
        description: 'The raw API console — inspect requests/responses directly.',
        to: '/debug',
        managerOnly: true,
      },
      {
        icon: 'settings',
        name: 'Settings',
        // New this wave (WAREHOUSE-UX-PLAN.md §3.1 rule 5) — Account settings plus a link list to
        // Detection defaults and Controller, the two entries that just left the rail (see this
        // file's own class doc). Every signed-in user, pilot included, owns these preferences.
        description: 'Your account, plus links to detection defaults and your controller layout.',
        to: '/settings',
      },
    ],
  },
];

/** Looks up one group by id — used by `nav-entries.spec.ts`; kept for parity with the pre-W1 lookup helper. */
export function navModeById(id: NavModeId): NavMode {
  const mode = NAV_MODES.find((candidate) => candidate.id === id);
  if (!mode) {
    throw new Error(`Unknown nav mode: ${id}`);
  }
  return mode;
}
