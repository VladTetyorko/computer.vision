import type { IconName } from '../../shared/ui/icon-registry';

/**
 * The route→mode map, as data — the **one** source of truth for app navigation.
 *
 * **Now a single renderer (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F1, docs/extracts/design/19-hubs.md).** This array
 * used to feed *two* parallel navigation surfaces: the top bar's three mode dropdowns and the three
 * hub launcher pages (`operate-hub.ts`/`monitor-hub.ts`/`manage-hub.ts`), which rendered exactly the
 * same entries as a `vision-tile-grid`. Landing on a hub therefore cost a click and a lazy-chunk load
 * to show what the dropdown above it already listed, and the two renderers drifted — `managerOnly`
 * was honoured by `ManageHub` and ignored by the dropdown. Both surfaces are gone, replaced by
 * `shared/ui/app-sidebar/**`, the sole consumer of this file; `/operate`, `/monitor` and `/manage`
 * redirect to `primaryRoute` (see below). One renderer is what makes the drift structurally
 * impossible rather than merely fixed.
 *
 * **De-duplicated nav (docs/conclusions/UX-SIMPLIFY-REVIEW.md F1) — every destination has exactly one canonical
 * entry.** Before this task, `/command` was linked 3× (Operate's "Geofence & safety zones", Monitor's
 * "Map", Monitor's "Command dashboard"), `/wall` 2× (Operate's "Live view", Monitor's "Wall"), and
 * `/fly` 2× (Operate's "Cockpit", Operate's "Vision" — the CV console lives inline on the cockpit, so
 * "Vision" was never a different page). Fixed by picking each destination's single most-primary hub
 * and deleting the rest, **no feature lost** — the underlying page/panel is still there, just reached
 * through one door: **Cockpit** (`/fly`) and **Wall** (`/wall`) live under **Operate** only; **Command**
 * (`/command`) lives under **Monitor** only, one name, not three. Geofence/safety-zone editing still
 * exists — it's the Zones panel inside the Command page itself (`features/command/zones-panel.ts`),
 * reachable via Monitor → Command, not a separate Operate tile pointing at the same URL.
 * **`nav-entries.spec.ts`** guards this with a standing regression test: no `to` value may appear on
 * more than one `NavEntry` anywhere in `NAV_MODES`.
 *
 * **Canonical labels:** the pilot cockpit is always **"Cockpit"** (`/fly`) — never "Fly"/"Detection"/
 * "Vision" — everywhere it's linked from (top-bar dropdown, hub tile, and any in-page breadcrumb). The
 * live-stream wall is always **"Wall"** (`/wall`). The manager's map-first dashboard is always
 * **"Command"** (`/command`).
 *
 * **`badge: 'soon'`** marks every remaining pure-**SCAFFOLD** entry (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4's
 * Additions table) — its `to` still routes somewhere real (the shared `ComingSoon` placeholder,
 * `coming-soon.ts`), never a dead link (docs/plans/done/UI-REDESIGN-PLAN.md §D-G: "a scaffold never renders
 * invented rows"). Entries with no badge are functional — routed straight at a real page.
 *
 * **Assets is the one home for "what I own/fly" (docs/conclusions/UX-SIMPLIFY-REVIEW.md F2).** `Assets` → `/assets`
 * is the asset-first grid (search, filter, cards) and stays the primary, ungated Manage entry — the
 * label is deliberately unchanged ("Assets", not "Sources"/"Fleet"). Two things that used to compete
 * with it as peer "where are my cameras" doors are gone:
 * - **`Warehouse` is deleted outright** — it was a launcher page whose only job was linking to People
 *   (`/manage/roster`) and Assets (`/assets`), both already reachable directly; a third door to a
 *   concept that already had two. `/warehouse` now redirects to `/assets`
 *   (`features/warehouse/warehouse.routes.ts`, mirroring `features/map/map.routes.ts`'s own "folds
 *   into X" redirect precedent) so an old bookmark/deep link still lands somewhere real.
 * - **`Devices` demotes off the primary Manage nav** — device-level plumbing (protocol/URI/firmware)
 *   already lives *inside* an asset's own Hardware section (`features/asset-detail/**`); the `/devices`
 *   power-view page itself is untouched and still fully reachable by URL/link, just no longer a
 *   top-level Manage door. It now carries `group: 'advanced'` + `managerOnly: true` (see below) — an
 *   admin/manager still finds it one section down, a pilot never sees it at all.
 *
 * **Manage is grouped + role-scoped (docs/conclusions/UX-SIMPLIFY-REVIEW.md F3).** Two extra, Manage-only
 * `NavEntry` fields:
 * - **`group`** — `'configuration'` (Categories/Training/Firmware/Reports — set up once, not every
 *   day), `'diagnostics'` (Health/Debug), or `'advanced'` (Devices). Omitted means "everyday,
 *   ungrouped" (Assets/Add source/Pilots-roster). The sidebar reads this through `navTiers()` below:
 *   `diagnostics`/`advanced` fold into one collapsed `Advanced` disclosure, `configuration` stays
 *   visible alongside the ungrouped entries.
 * - **`managerOnly`** — hidden unless `canManageOrg(topRole)` (`core/org/org-logic.ts`, ADMIN/MANAGER)
 *   is true, the exact same gate `shared/ui/identity-chip.ts`'s Organization link and
 *   `core/org/org-guard.ts`'s route guard already use — reused here, not a new role system. Every
 *   `group`-carrying entry is `managerOnly`, with **one deliberate exception**: `System status`
 *   (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1) sits in the `diagnostics` group but is not
 *   `managerOnly` — an operator whose CV pipeline just died needs to see why, the same "don't gate
 *   the explanation behind the role that isn't looking at the failure" reasoning `/command` already
 *   applies more broadly. `Pilots / roster` is `managerOnly` too (its own route is already
 *   `orgGuard`-gated, so hiding the tile for a pilot matches where a click would land anyway, not a
 *   new restriction). **`Add source` is `managerOnly` too (docs/plans/done/OPS-UX-PLAN.md §2 A4)** — the
 *   concurrent backend wave gates `POST /api/assets` itself on `canManageOrg()`, so the nav must not
 *   dangle a door the API will now refuse. `Assets` stays ungated — reading the fleet is not a
 *   management action — as does `System status`, for the separate reason given above. Net effect: a
 *   plain PILOT sees two Manage entries (Assets and System status); an ADMIN/MANAGER sees the full
 *   grouped set. The sidebar applies this filter **once**, for the whole
 *   app — the split-brain where only the hub page filtered and the dropdown did not is gone with the
 *   hub pages themselves.
 */
export type NavModeId = 'operate' | 'monitor' | 'manage';

/**
 * Where `/operate`, `/monitor`, `/manage` now redirect (docs/extracts/design/19-hubs.md). The three hub
 * launcher pages are gone — `NAV_MODES` feeds exactly one renderer, the sidebar — but the paths stay
 * routable so old bookmarks and external links still land somewhere real, mirroring the same
 * fold-a-removed-launcher-into-its-successor precedent `features/map/map.routes.ts` (`/map` →
 * `/command`) and `features/warehouse/warehouse.routes.ts` (`/warehouse` → `/assets`) already set.
 */

export interface NavEntry {
  readonly icon: IconName;
  readonly name: string;
  readonly description: string;
  /** A real route — either an existing functional page, or (when `badge` is set) a `ComingSoon` scaffold route. */
  readonly to: string;
  /** Set to `'soon'` for every F4 scaffold entry; omitted for functional entries. */
  readonly badge?: string;
  /**
   * Manage-hub-only sub-grouping (docs/conclusions/UX-SIMPLIFY-REVIEW.md F3) — `undefined` renders flat, at the
   * top of the Manage hub, ungrouped. Ignored by Operate/Monitor (neither hub currently groups).
   */
  readonly group?: 'configuration' | 'diagnostics' | 'advanced';
  /**
   * Hidden unless `canManageOrg(topRole)` (docs/conclusions/UX-SIMPLIFY-REVIEW.md F3) — see this file's own class
   * doc comment. Only `ManageHub` reads this field today.
   */
  readonly managerOnly?: boolean;
}

export interface NavMode {
  readonly id: NavModeId;
  readonly label: string;
  readonly icon: IconName;
  /** Redirect target for the retired hub path of the same name — see `HUB_REDIRECT_NOTE` above. */
  readonly primaryRoute: string;
  readonly entries: readonly NavEntry[];
}

/**
 * How the sidebar (`shared/ui/app-sidebar/**`) splits one mode's entries into its three visual tiers
 * (docs/extracts/design/00-shell.md). Kept here, next to the data it partitions, so "which tier is this entry
 * in" has one answer rather than one per renderer — the same single-source-of-truth reasoning that
 * put `NAV_MODES` itself in this file.
 *
 * - **`primary`** — shipped, everyday entries: always visible.
 * - **`advanced`** — the `diagnostics`/`advanced` groups (Debug, Devices, Maintenance): behind a
 *   collapsed `Advanced` disclosure. `configuration` stays primary — Categories/CV training/Reports
 *   are things a manager opens, not troubleshooting tools.
 * - **`upcoming`** — every `badge: 'soon'` scaffold: behind a collapsed `Upcoming` disclosure, so an
 *   unbuilt area can never outrank a built one (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F9).
 */
export interface NavTiers {
  readonly primary: readonly NavEntry[];
  readonly advanced: readonly NavEntry[];
  readonly upcoming: readonly NavEntry[];
}

export function navTiers(entries: readonly NavEntry[]): NavTiers {
  return {
    primary: entries.filter((entry) => !entry.badge && !isAdvanced(entry)),
    advanced: entries.filter((entry) => !entry.badge && isAdvanced(entry)),
    upcoming: entries.filter((entry) => entry.badge === 'soon'),
  };
}

function isAdvanced(entry: NavEntry): boolean {
  return entry.group === 'diagnostics' || entry.group === 'advanced';
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
        name: 'Pre-flight checklist',
        description: "The live status card for any drone — saved, editable templates are coming.",
        to: '/operate/preflight',
      },
      {
        icon: 'settings',
        name: 'Detection defaults',
        description: 'Detection profile, model, and per-pipeline options applied to every new stream.',
        to: '/settings/detection',
      },
      {
        icon: 'compass',
        name: 'Flight plans / missions',
        description: 'Plan routes and upload flight plans to the aircraft.',
        to: '/operate/missions',
        badge: 'soon',
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
        icon: 'shield',
        name: 'Audit trail',
        description: 'Who changed what, fleet-wide — actor, action, target, and result.',
        to: '/monitor/audit',
        // docs/plans/done/OPS-UX-PLAN.md §3 B1: mirrors `AuditController#list`'s own
        // `canManageOrg()` gate — the manager's accountability surface, not a pilot's.
        managerOnly: true,
      },
      {
        icon: 'alert',
        name: 'Alerts center',
        description: 'The live detection-events feed — saved thresholds and acknowledgement are coming.',
        to: '/monitor/alerts',
      },
      {
        icon: 'replay',
        name: 'Replay library',
        description: 'Scrub any finished flight, frame by frame.',
        to: '/replay',
      },
      {
        icon: 'layers',
        name: 'Saved Wall layouts',
        description: 'Save named Wall tile arrangements for later.',
        to: '/monitor/layouts',
        badge: 'soon',
      },
    ],
  },
  {
    id: 'manage',
    label: 'Manage',
    icon: 'manage',
    primaryRoute: '/assets',
    entries: [
      // --- Everyday, ungrouped, ungated — every authenticated role sees these -------------------
      {
        icon: 'drone',
        name: 'Assets',
        description: 'Every asset, asset-first — search, filter, and watch, open, or archive.',
        to: '/assets',
      },
      {
        icon: 'plus',
        name: 'Add source',
        description: 'Enter an address, scan the network, listen for a drone, or simulate one — four steps.',
        to: '/add-source',
        // docs/plans/done/OPS-UX-PLAN.md §2 A4: the concurrent backend wave gates `POST /api/assets` on
        // `canManageOrg()` — the nav must not offer a door the API will now refuse. `Assets`, its
        // sibling above, stays ungated (reading the fleet is not a management action).
        managerOnly: true,
      },
      {
        icon: 'pilot',
        name: 'Pilots / roster',
        description: "A dedicated roster across every asset's pilot assignments.",
        to: '/manage/roster',
        managerOnly: true,
      },
      {
        icon: 'gamepad',
        name: 'Controller',
        description: 'What each stick, switch and button on your transmitter does — your own layouts, per vehicle kind.',
        to: '/manage/controller',
      },
      // --- Configuration — set up once, not every day; ADMIN/MANAGER only ----------------------
      {
        icon: 'category',
        name: 'Asset categories',
        description: 'Every category, with live counts — creating and editing categories is coming.',
        to: '/manage/categories',
        group: 'configuration',
        managerOnly: true,
      },
      {
        icon: 'target',
        name: 'CV training',
        description: 'Capture live frames, correct the boxes, and export YOLO datasets to improve detection models.',
        to: '/manage/training',
        group: 'configuration',
        managerOnly: true,
      },
      {
        icon: 'archive',
        name: 'CV model registry',
        description: 'Every model cv-service knows about — promote one to make it the live default for new detections.',
        to: '/manage/training/models',
        group: 'configuration',
        managerOnly: true,
      },
      {
        icon: 'satellite',
        name: 'Geo regions',
        description: 'Reference-imagery regions for visual geolocation — ingest a bounding box, then watch it index.',
        to: '/manage/geo/regions',
        group: 'configuration',
        managerOnly: true,
      },
      {
        icon: 'firmware',
        name: 'Firmware',
        description: 'Firmware inventory and update flow for every aircraft.',
        to: '/manage/firmware',
        badge: 'soon',
        group: 'configuration',
        managerOnly: true,
      },
      {
        icon: 'report',
        name: 'Inventory reports',
        description: 'A live, read-only fleet dashboard — exportable reports are coming.',
        to: '/manage/reports',
        group: 'configuration',
        managerOnly: true,
      },
      // --- Diagnostics — troubleshooting, not day-to-day management; ADMIN/MANAGER only --------
      {
        icon: 'wrench',
        name: 'Maintenance / health',
        description: 'Maintenance records and health history per asset.',
        to: '/manage/health',
        badge: 'soon',
        group: 'diagnostics',
        managerOnly: true,
      },
      {
        icon: 'gear',
        name: 'Debug',
        description: 'The raw API console — inspect requests/responses directly.',
        to: '/debug',
        group: 'diagnostics',
        managerOnly: true,
      },
      // Deliberately NOT managerOnly (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1) — the one exception to
      // "every grouped entry is managerOnly" this file's own class doc otherwise states as a rule.
      // An operator whose CV pipeline just died needs to see why the system is degraded; gating that
      // behind ADMIN/MANAGER would hide the one page that explains a problem they're already looking
      // at. `nav-entries.spec.ts` carries a named, documented carve-out for this one entry.
      {
        icon: 'signal',
        name: 'System status',
        description: 'What the platform reports about its own health — subsystems, live transport, and system events.',
        to: '/manage/system',
        group: 'diagnostics',
      },
      // --- Advanced — raw device plumbing; most device actions already live inside each asset's
      //     own Hardware section (docs/conclusions/UX-SIMPLIFY-REVIEW.md F2). ADMIN/MANAGER only. -----------
      {
        icon: 'chip',
        name: 'Devices',
        description: 'The raw device table or grid — protocol, URI, lifecycle, and the archived toggle.',
        to: '/devices',
        group: 'advanced',
        managerOnly: true,
      },
    ],
  },
];

/** Looks up one mode by id — used by each thin hub page (`operate-hub.ts` etc.) to grab just its own entries. */
export function navModeById(id: NavModeId): NavMode {
  const mode = NAV_MODES.find((candidate) => candidate.id === id);
  if (!mode) {
    throw new Error(`Unknown nav mode: ${id}`);
  }
  return mode;
}
