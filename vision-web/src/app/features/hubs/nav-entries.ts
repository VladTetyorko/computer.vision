import type { IconName } from '../../shared/ui/icon-registry';

/**
 * The frozen F4 route→mode map (docs/UI-REDESIGN-PLAN.md Wave 1), as data — the **one** source of
 * truth for both the app-shell top bar's three mode dropdowns (`app.ts`/`app.html`) and the three
 * hub launcher pages (`operate-hub.ts`/`monitor-hub.ts`/`manage-hub.ts`, each just filters this array
 * down to its own mode and renders it as a `vision-tile-grid`). Keeping this in one file — rather
 * than the dropdown and its hub page each carrying their own copy — is what makes "add/rename an
 * entry" a one-line edit instead of two edits that can silently drift apart (docs/UI-REDESIGN-PLAN.md
 * §D-A's own "the shell rebuild is mostly re-labeling" framing).
 *
 * **Canonical labels** (docs/UI-REDESIGN-PLAN.md Wave 1 task brief, "approved"): the pilot cockpit is
 * always **"Cockpit"** (`/fly`), the CV console is always **"Vision"** — one name per destination,
 * everywhere it's linked from (top-bar dropdown, hub tile, and — once a later wave gets there — any
 * in-page breadcrumb).
 *
 * **`badge: 'soon'`** marks every F4 "scaffold" entry — its `to` still routes somewhere real (the
 * shared `ComingSoon` placeholder, `coming-soon.ts`), never a dead link (docs/UI-REDESIGN-PLAN.md
 * §D-G: "a scaffold never renders invented rows"). Entries with no badge are F4 "functional" —
 * routed straight at the real, already-shipped page.
 *
 * **`Assets`/`Devices`/`Warehouse` now resolve to three distinct pages** (this cycle's inventory
 * restructure, no dedicated `docs/*-PLAN.md` — superseding this wave's original single `DevicesPage`
 * composition described just above; see `vision-web/MODULE.md`'s Assets/Devices/Warehouse sections
 * for the full writeup): `Assets` → `/assets` (the asset-first
 * grid — search, filter, cards), `Devices` → `/devices` (the raw device table/grid), `Warehouse` →
 * `/warehouse` (a two-tile launcher between People and Assets). **`Map`/`Command dashboard` still
 * intentionally share one destination** (`/command`) — the frozen F4 table lists them as distinct
 * rows because a manager thinks about "the map" and "the fleet dashboard" as different jobs even
 * though one screen now serves both; that pairing is unchanged by this task.
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
        description: 'Fly one drone — video, telemetry, and flight control in one view.',
        to: '/fly',
      },
      {
        icon: 'eye',
        name: 'Live view',
        description: 'Pick any live stream from Wall and watch it full-screen.',
        to: '/wall',
      },
      {
        icon: 'scan',
        name: 'Vision',
        description: 'Live per-stream detection tuning — model, confidence, classes.',
        to: '/fly',
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
        description: "Saved, editable checklist templates — today's live status card runs on the cockpit.",
        to: '/operate/preflight',
        badge: 'soon',
      },
      {
        icon: 'compass',
        name: 'Flight plans / missions',
        description: 'Plan routes and upload flight plans to the aircraft.',
        to: '/operate/missions',
        badge: 'soon',
      },
      {
        icon: 'map-pin',
        name: 'Geofence & safety zones',
        description: 'Draw and manage no-fly boundaries on the fleet map.',
        to: '/command',
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
        icon: 'grid',
        name: 'Wall',
        description: 'Every live stream at once, auto-paused off-screen.',
        to: '/wall',
      },
      {
        icon: 'map',
        name: 'Map',
        description: 'Fleet positions and flight paths on one live map.',
        to: '/command',
      },
      {
        icon: 'gauge',
        name: 'Command dashboard',
        description: 'Fleet-wide status, attention queue, and the map together.',
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
        description: 'Saved alert thresholds and acknowledgement — the live feed already streams via the bell.',
        to: '/monitor/alerts',
        badge: 'soon',
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
      {
        icon: 'drone',
        name: 'Assets',
        description: 'Every asset, asset-first — search, filter, and watch, open, or archive.',
        to: '/assets',
      },
      {
        icon: 'chip',
        name: 'Devices',
        description: 'The raw device table or grid — search, lifecycle actions, and the archived toggle.',
        to: '/devices',
      },
      {
        icon: 'warehouse',
        name: 'Warehouse',
        description: 'The inventory landing spot — jump to People or Assets.',
        to: '/warehouse',
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
        badge: 'soon',
      },
      {
        icon: 'category',
        name: 'Asset categories',
        description: 'Create and edit the categories assets are grouped by.',
        to: '/manage/categories',
        badge: 'soon',
      },
      {
        icon: 'wrench',
        name: 'Maintenance / health',
        description: 'Maintenance records and health history per asset.',
        to: '/manage/health',
        badge: 'soon',
      },
      {
        icon: 'firmware',
        name: 'Firmware',
        description: 'Firmware inventory and update flow for every aircraft.',
        to: '/manage/firmware',
        badge: 'soon',
      },
      {
        icon: 'report',
        name: 'Inventory reports',
        description: 'Exportable, fleet-wide inventory and utilization reports.',
        to: '/manage/reports',
        badge: 'soon',
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
