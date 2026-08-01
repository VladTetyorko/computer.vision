import type { IconName } from '../../shared/ui/icon-registry';

/**
 * The route→mode map (docs/UI-REDESIGN-PLAN.md Wave 1), as data — the **one** source of truth for
 * both the app-shell top bar's three mode dropdowns (`app.ts`/`app.html`) and the three hub launcher
 * pages (`operate-hub.ts`/`monitor-hub.ts`/`manage-hub.ts`, each just filters this array down to its
 * own mode and renders it as a `vision-tile-grid`). Keeping this in one file — rather than the
 * dropdown and its hub page each carrying their own copy — is what makes "add/rename an entry" a
 * one-line edit instead of two edits that can silently drift apart (docs/UI-REDESIGN-PLAN.md §D-A's
 * own "the shell rebuild is mostly re-labeling" framing).
 *
 * **De-duplicated nav (docs/UX-SIMPLIFY-REVIEW.md F1) — every destination has exactly one canonical
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
 * **`badge: 'soon'`** marks every remaining pure-**SCAFFOLD** entry (docs/UI-REDESIGN-PLAN.md Wave 4's
 * Additions table) — its `to` still routes somewhere real (the shared `ComingSoon` placeholder,
 * `coming-soon.ts`), never a dead link (docs/UI-REDESIGN-PLAN.md §D-G: "a scaffold never renders
 * invented rows"). Entries with no badge are functional — routed straight at a real page.
 *
 * **Assets is the one home for "what I own/fly" (docs/UX-SIMPLIFY-REVIEW.md F2).** `Assets` → `/assets`
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
 * **Manage is grouped + role-scoped (docs/UX-SIMPLIFY-REVIEW.md F3).** Two extra, Manage-only
 * `NavEntry` fields:
 * - **`group`** — `'configuration'` (Categories/Training/Firmware/Reports — set up once, not every
 *   day), `'diagnostics'` (Health/Debug), or `'advanced'` (Devices). Omitted means "everyday,
 *   ungrouped" (Assets/Add source/Pilots-roster) — `manage-hub.ts` renders those first, flat, then one
 *   labelled `vision-section-header` + `vision-tile-grid` per group that still has a visible entry.
 * - **`managerOnly`** — hidden unless `canManageOrg(topRole)` (`core/org/org-logic.ts`, ADMIN/MANAGER)
 *   is true, the exact same gate `shared/ui/identity-chip.ts`'s Organization link and
 *   `core/org/org-guard.ts`'s route guard already use — reused here, not a new role system. Every
 *   `group`-carrying entry is `managerOnly`; so is `Pilots / roster` (its own route is already
 *   `orgGuard`-gated, so hiding the tile for a pilot matches where a click would land anyway, not a
 *   new restriction). `Assets`/`Add source` stay ungated — every authenticated role can already reach
 *   both today. Net effect: a plain PILOT's `/manage` hub renders just two tiles (Assets, Add source);
 *   an ADMIN/MANAGER sees the full grouped set. Only `ManageHub` applies this filter today — the
 *   top-bar Manage dropdown (`app.html`) still lists every entry unfiltered (out of this task's own
 *   file scope; a follow-up, not an oversight — see `manage-hub.ts`'s own class doc comment).
 */
export type NavModeId = 'operate' | 'monitor' | 'manage';

export interface NavEntry {
  readonly icon: IconName;
  readonly name: string;
  readonly description: string;
  /** A real route — either an existing functional page, or (when `badge` is set) a `ComingSoon` scaffold route. */
  readonly to: string;
  /** Set to `'soon'` for every F4 scaffold entry; omitted for functional entries. */
  readonly badge?: string;
  /**
   * Manage-hub-only sub-grouping (docs/UX-SIMPLIFY-REVIEW.md F3) — `undefined` renders flat, at the
   * top of the Manage hub, ungrouped. Ignored by Operate/Monitor (neither hub currently groups).
   */
  readonly group?: 'configuration' | 'diagnostics' | 'advanced';
  /**
   * Hidden unless `canManageOrg(topRole)` (docs/UX-SIMPLIFY-REVIEW.md F3) — see this file's own class
   * doc comment. Only `ManageHub` reads this field today.
   */
  readonly managerOnly?: boolean;
}

export interface NavMode {
  readonly id: NavModeId;
  readonly label: string;
  readonly icon: IconName;
  readonly hubRoute: string;
  readonly entries: readonly NavEntry[];
}

export const NAV_MODES: readonly NavMode[] = [
  {
    id: 'operate',
    label: 'Operate',
    icon: 'operate',
    hubRoute: '/operate',
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
        icon: 'settings',
        name: 'Flight & detection settings',
        description: 'Defaults for detection profile, notifications, and per-pipeline options.',
        to: '/settings',
      },
      {
        icon: 'list',
        name: 'Pre-flight checklist',
        description: "The live status card for any drone — saved, editable templates are coming.",
        to: '/operate/preflight',
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
    hubRoute: '/monitor',
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
        icon: 'alert',
        name: 'Alerts center',
        description: 'The live detection-events feed — saved thresholds and acknowledgement are coming.',
        to: '/monitor/alerts',
      },
      {
        icon: 'replay',
        name: 'Replay library',
        description: 'Scrub any finished flight, frame by frame.',
        to: '/monitor/replay',
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
    hubRoute: '/manage',
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
        description: 'Register, discover, or simulate a new device in three steps.',
        to: '/add-source',
      },
      {
        icon: 'pilot',
        name: 'Pilots / roster',
        description: "A dedicated roster across every asset's pilot assignments.",
        to: '/manage/roster',
        managerOnly: true,
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
      // --- Advanced — raw device plumbing; most device actions already live inside each asset's
      //     own Hardware section (docs/UX-SIMPLIFY-REVIEW.md F2). ADMIN/MANAGER only. -----------
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
