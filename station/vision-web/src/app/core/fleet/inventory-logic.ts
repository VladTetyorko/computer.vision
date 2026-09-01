import type { InventoryState, LifecycleState } from '../api/models';

/**
 * Pure, Angular-free logic shared by the Inventory page (`/assets`, docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.1 rule 3 / §3.3, wave W4) and any other surface that needs to answer the same three questions
 * without re-deriving them: which of the page's four tabs exist for a given role, what one chip a
 * vehicle/equipment row should show for its combined lifecycle + inventory state, and what filename
 * an inventory CSV export should carry. Lives in `core/fleet/` (not `features/inventory/**`) per
 * this codebase's "a second consumer moves shared logic to `core/`" precedent
 * (`core/fleet/device-logic.ts`'s own doc comment) — `features/inventory/**`'s own facade is today's
 * only consumer, but this module is deliberately placed alongside `core/fleet/warehouse-logic.ts`/
 * `core/fleet/triage-logic.ts` (the other cross-page fleet-lifecycle logic) rather than folded into
 * a page-local file, since the plan's own wave table names this exact path.
 */

// --- Tabs (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3: "Inventory is one page with tabs") ---

export type InventoryTab = 'vehicles' | 'equipment' | 'links' | 'categories';

/** The page's default tab — a bare `/assets` (no `?tab=`) has always meant "the vehicle list" (OQ4: the URL itself is unchanged). */
const DEFAULT_TAB: InventoryTab = 'vehicles';

const ALL_TABS: readonly InventoryTab[] = ['vehicles', 'equipment', 'links', 'categories'];

/**
 * Parses `?tab=`, defaulting to {@link DEFAULT_TAB} for anything absent/unrecognised — never a
 * blank page for a stale or hand-edited query string.
 */
export function parseInventoryTab(raw: string | null | undefined): InventoryTab {
  return (ALL_TABS as readonly string[]).includes(raw ?? '') ? (raw as InventoryTab) : DEFAULT_TAB;
}

/**
 * The tabs a caller with `canManageOrg` may see — Links (today's Devices content) and Categories
 * carry the same `orgGuard`-equivalent gate `nav-entries.ts`'s "Devices"/"Asset categories" entries
 * used to (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 6): a pilot's fleet is scoped to their
 * own assignments, and neither raw device wiring nor the org-wide category taxonomy is a pilot
 * concern. Vehicles/Equipment stay open to everyone, same openness `/assets` always had.
 */
export function visibleInventoryTabs(canManageOrg: boolean): readonly InventoryTab[] {
  return canManageOrg ? ALL_TABS : ['vehicles', 'equipment'];
}

/** Whether `tab` is one `canManageOrg` may actually land on — the in-page equivalent of `orgGuard`
 *  for a route that is all one path (`/assets`), never a second `canActivate`. A caller whose
 *  `?tab=` resolves to a tab this returns `false` for must fall back to {@link DEFAULT_TAB} rather
 *  than rendering gated content or crashing. */
export function isInventoryTabVisible(tab: InventoryTab, canManageOrg: boolean): boolean {
  return visibleInventoryTabs(canManageOrg).includes(tab);
}

// --- Effective inventory-state chip (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3: "Inventory state
// chip (merge with existing state chip logic: archived/deactivated win, else inventoryState)") ---

/**
 * One row's combined lifecycle + inventory-state chip — frontend-style §5's "one chip per row max"
 * rule applied to a row that now carries *two* independent state axes (`AssetSummary#lifecycle`,
 * unrelated to this wave, and the new `inventoryState`, D1/D2/D6). `archived`/`deactivated` win
 * outright (an archived or deactivated asset's inventory state is moot — it isn't in service at
 * all, regardless of who last held it), exactly mirroring `features/assets/assets-logic.ts
 * #describeAssetState`'s own priority order; only once neither applies does the row fall through to
 * its `inventoryState`. `'unknown'` is the honest fallback for a row whose `inventoryState` was
 * never fetched (see `AssetSummary#inventoryState`'s own doc comment on why it's optional in TS
 * despite always being present on the wire) — never a fabricated "In stock".
 */
export type InventoryStateChipKind =
  | 'archived'
  | 'deactivated'
  | 'in-stock'
  | 'issued'
  | 'in-field'
  | 'maintenance'
  | 'retired'
  | 'unknown';

/** Chip visual tone — `frontend-style §3`'s status-only-color rule: `muted` for the archived/steady
 *  states, `ok` for actively-in-use states, `warn` for the one state that needs attention. */
export type InventoryStateChipTone = 'muted' | 'ok' | 'warn';

export interface InventoryStateChip {
  readonly kind: InventoryStateChipKind;
  readonly label: string;
  readonly tone: InventoryStateChipTone;
  /** True only for `in-field` — mirrors the old "Live" chip's pulsing dot: an asset with an open
   *  usage really is live in the field right now, not just administratively checked out. */
  readonly live: boolean;
}

const UNKNOWN_CHIP: InventoryStateChip = { kind: 'unknown', label: '—', tone: 'muted', live: false };

export function effectiveInventoryStateChip(
  row: { readonly lifecycle: LifecycleState; readonly archived: boolean; readonly inventoryState?: InventoryState },
): InventoryStateChip {
  if (row.archived) {
    return { kind: 'archived', label: 'Archived', tone: 'muted', live: false };
  }
  if (row.lifecycle === 'DEACTIVATED') {
    return { kind: 'deactivated', label: 'Deactivated', tone: 'muted', live: false };
  }
  switch (row.inventoryState) {
    case 'IN_STOCK':
      return { kind: 'in-stock', label: 'In stock', tone: 'muted', live: false };
    case 'ISSUED':
      return { kind: 'issued', label: 'Issued', tone: 'ok', live: false };
    case 'IN_FIELD':
      return { kind: 'in-field', label: 'In field', tone: 'ok', live: true };
    case 'MAINTENANCE':
      return { kind: 'maintenance', label: 'Maintenance', tone: 'warn', live: false };
    case 'RETIRED':
      return { kind: 'retired', label: 'Retired', tone: 'muted', live: false };
    default:
      return UNKNOWN_CHIP;
  }
}

// --- CSV export filename (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's Export action, wave W4) ---

/**
 * The `download` attribute's own suggested filename for the Export CSV action's `<a>` —
 * `inventory-YYYY-MM-DD.csv`, one dash-joined ISO date so the file sorts naturally beside other
 * downloads. Cosmetic only: `InventoryExportController#export` already sends
 * `Content-Disposition: attachment; filename="inventory.csv"`, which every browser prefers over an
 * anchor's own `download` value when both are present — this is what a client falls back to on the
 * rare browser/proxy combination that drops the header, not the name a normal download will show.
 */
export function inventoryExportFilename(nowMs: number): string {
  const iso = new Date(nowMs).toISOString().slice(0, 10);
  return `inventory-${iso}.csv`;
}
