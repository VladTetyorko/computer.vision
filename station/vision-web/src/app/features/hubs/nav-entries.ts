import type { AuthCapability } from '../../core/api/models';
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
 * **Controller leaves the rail entirely** (WAREHOUSE-UX-PLAN.md §3.1 rule 5) — a transmitter layout is
 * configuration a user visits rarely, not a day-to-day Operate/Manage door. `/manage/controller` is
 * untouched and ungated; it is reached from a small "Settings" link list on `/settings`
 * (`features/settings/account-settings.html`) instead of from here. Detection defaults
 * (`/settings/detection`) followed the same path in WAREHOUSE-UX-PLAN.md §3.1, but wave W6
 * (docs/plans/active/CV-SETTINGS-PLAN.md) supersedes that move outright: the whole surface it stood in
 * for is gone (replaced by named, bindable profiles, not a single-org form), so it comes back as a
 * first-class, `MANAGE_ORG`-gated **Vision** entry (**Profiles**, below) rather than a Settings-page link —
 * see that entry's own comment.
 *
 * **W4 folds Asset categories, Inventory reports, and Devices into Inventory itself** (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.3) — all three pages are absorbed as `InventoryPage` tabs (`?tab=categories|links`; Reports has
 * no tab of its own, its one surviving section is the KPI strip above Vehicles) and their standalone
 * routes now redirect there (`features/categories/categories.routes.ts`, `features/reports/reports.routes.ts`,
 * `features/devices/devices.routes.ts`). Their three `fleet` nav entries are deleted outright — a
 * folded-in tab is reached from inside Inventory, never a second rail door to the same content
 * (this file's own "de-duplicated nav" rule, above). **Maintenance is new this wave** (wave W7,
 * `features/maintenance/**`) — `/manage/health`'s old `ComingSoon` scaffold is a real page now,
 * so it earns the nav entry that a `badge: 'soon'` stub never got.
 *
 * **`requires`** — hidden unless the session holds the named `AuthCapability`
 * (`core/auth/auth-logic.ts#hasCapability`); every entry below names `'MANAGE_ORG'`, the same
 * capability `shared/ui/identity-chip.ts`'s Organization link and `core/org/org-guard.ts`'s route
 * guard gate on (`core/org/org-logic.ts#canManageOrg`) — granted to MANAGER/ADMIN only, never
 * PILOT/VIEWER, per the backend's own role→capability policy. **Renamed from the boolean
 * `managerOnly` (docs/plans/active/AUTH-ROLES-PLAN.md §3.2, wave W2)** once "manager-only" stopped being
 * the only shape a gate could take — `requires` names *which* capability, so a future entry needing
 * a narrower one (e.g. `MANAGE_FLEET` alone) doesn't have to grow a second boolean flag next to this
 * one. Applied exactly once, in `shared/ui/app-sidebar/app-sidebar.ts#modes`. **Every route whose nav
 * entry sets `requires` now also carries `canActivate: [orgGuard]` on the route itself**
 * (WAREHOUSE-UX-PLAN.md §3.1 rule 6, closing PLATFORM-AUDIT-UI D2/D3: a gated nav entry with no route
 * guard was a door a pilot could still type into) — see each touched `*.routes.ts`'s own doc
 * comment. `System status` keeps its one documented exception: `diagnostics`-flavoured but ungated
 * (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1) — an operator whose CV pipeline just died needs to
 * see why, the same reasoning `/command` already applies more broadly. `Settings` is deliberately
 * ungated either — every signed-in user, pilot included, owns account/controller preferences.
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
   * Hidden unless the session holds this `AuthCapability` — see this file's own class doc. Every
   * route this gates has a matching `canActivate: [orgGuard]` on its own `*.routes.ts` entry
   * (WAREHOUSE-UX-PLAN.md §3.1 rule 6), with the two documented exceptions named there (`System
   * status`, `Settings`).
   */
  readonly requires?: AuthCapability;
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
      {
        icon: 'eye',
        name: 'Crew seat',
        // New this wave (docs/plans/active/CREW-CONTROL-PLAN.md §4, wave W3) — the sensor-operator
        // seat: video, CV controls, and map-tools on an asset a pilot may already be flying, with no
        // flight verb anywhere on the page. Named "Crew seat", not the bare "Crew" (Fleet's own
        // `/manage/roster` entry already owns that name for the pilot-assignment roster — a different
        // page entirely; this file's own "no duplicate entry name within one group" guard only checks
        // per-group, but two identically-named sidebar links to different destinations is confusing
        // regardless of which test would catch it). Ungated (no `requires`) — the same "working the
        // fleet is not a management action" reasoning as Cockpit/Wall above; `/crew` itself redirects
        // to Wall until a real crew landing page exists (`crew.routes.ts`'s own doc comment).
        description: 'Watch the picture and work the camera on a drone — no flight controls.',
        to: '/crew',
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
        // Wave W4: one tabbed page now (Vehicles/Equipment/Links/Categories, `?tab=`) — Links and
        // Categories are gated inside the page itself (a pilot's `visibleInventoryTabs()` never
        // includes them), not by this nav entry, which stays open to everyone as it always was.
        description: 'Vehicles, equipment, device links, and categories — one tabbed inventory, plus CSV export.',
        to: '/assets',
      },
      {
        icon: 'plus',
        name: 'Add vehicle',
        // Renamed from "Add source". `requires: 'MANAGE_ORG'` mirrors `POST /api/assets`'s own
        // `canManageOrg()` gate (docs/plans/done/OPS-UX-PLAN.md §2 A4) — unchanged by this wave.
        description: 'Enter an address, scan the network, listen for a drone, or simulate one — four steps.',
        to: '/add-source',
        requires: 'MANAGE_ORG',
      },
      {
        icon: 'pilot',
        name: 'Crew',
        // Renamed from "Pilots / roster".
        description: "A dedicated roster across every asset's pilot assignments.",
        to: '/manage/roster',
        requires: 'MANAGE_ORG',
      },
      {
        icon: 'wrench',
        name: 'Maintenance',
        // New this wave (wave W7, `features/maintenance/**`) — `/manage/health`'s old `ComingSoon`
        // scaffold redirects here now (`features/hubs/hubs.routes.ts`).
        description: 'Open and close maintenance/grounding records across the fleet.',
        to: '/fleet/maintenance',
        requires: 'MANAGE_ORG',
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
        icon: 'layers',
        name: 'Profiles',
        // New this wave (docs/plans/active/CV-SETTINGS-PLAN.md §4, wave W6) — replaces the old
        // single-org "Detection defaults" Settings-page link outright (see this file's own class
        // doc): a named, reusable, bindable per-stream CV config, not a single fleet-wide form.
        // `/settings/detection` now redirects here (`settings.routes.ts`).
        description: 'Named CV configs — model, thresholds, tracking — bound per organization, category, or asset.',
        to: '/vision/profiles',
        requires: 'MANAGE_ORG',
      },
      {
        icon: 'target',
        name: 'CV training',
        description: 'Capture live frames, correct the boxes, and export YOLO datasets to improve detection models.',
        to: '/manage/training',
        requires: 'MANAGE_ORG',
      },
      {
        icon: 'archive',
        name: 'CV model registry',
        description: 'Every model cv-service knows about — promote one to make it the live default for new detections.',
        to: '/manage/training/models',
        requires: 'MANAGE_ORG',
      },
      {
        icon: 'satellite',
        name: 'Geo regions',
        description: 'Reference-imagery regions for visual geolocation — ingest a bounding box, then watch it index.',
        to: '/manage/geo/regions',
        requires: 'MANAGE_ORG',
      },
      {
        icon: 'chip',
        name: 'CV inspector',
        // New this wave (docs/plans/active/CV-ORCHESTRATION-PLAN.md §9 decision 3, wave W5.3) —
        // the engineer audience's own surface (§4.8): per-frame contributor list, per-object
        // evidence, gate ledger, and process facts for one picked stream. `/manage/cv` only, no
        // fly-drawer tab (the decision's own wording) — the fly cockpit keeps its one honest status
        // line instead (§4.8's Operator row).
        description: 'Per-frame contributor list, per-object evidence, gate ledger, and process facts — the CV pipeline’s own debug surface.',
        to: '/manage/cv',
        requires: 'MANAGE_ORG',
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
        // The one deliberate ungated diagnostics entry (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1) — an
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
        requires: 'MANAGE_ORG',
      },
      {
        icon: 'gear',
        name: 'Debug',
        description: 'The raw API console — inspect requests/responses directly.',
        to: '/debug',
        requires: 'MANAGE_ORG',
      },
      {
        icon: 'settings',
        name: 'Settings',
        // New this wave (WAREHOUSE-UX-PLAN.md §3.1 rule 5) — Account settings plus a link list to
        // Controller, the one entry that just left the rail (see this file's own class doc); every
        // signed-in user, pilot included, owns these preferences. Detection defaults used to be
        // listed here too — it left this link list for its own `MANAGE_ORG`-gated Vision nav entry
        // (`Profiles`, wave W6) once it stopped being a single-org form.
        description: 'Your account, plus a link to your controller layout.',
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
