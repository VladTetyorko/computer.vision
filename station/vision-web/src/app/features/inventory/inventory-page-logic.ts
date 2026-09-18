import type { ReadinessVerdict } from '../../core/api/models';
import type { InventoryStateChipKind } from '../../core/fleet/inventory-logic';

/**
 * Pure, Angular-free logic behind `InventoryPage`'s **view row** — the five toggleable stats that
 * replaced the old read-only "Fleet at a glance" KPI strip (docs/plans/active/INVENTORY-REWORK-PLAN.md
 * §2 D7 / §5.1, wave W4).
 *
 * The strip used to answer *Total · Streaming · Active · Deactivated · Needs attention* off
 * `GET /api/fleet/summary` and do nothing when clicked — five numbers, none of which narrowed the
 * table under them, and one of which ("Needs attention 2") named a queue with no way to open it
 * (that plan's context §4, live evidence). It is now the page's **view switcher**: five states a
 * fleet manager actually works in, each a filter over the rows already on screen.
 *
 * **Counts are of the rows a click would show, not of the fleet.** Every count is computed over the
 * tab's rows *after* search/category/More-filters/retired/archived and *before* the view filter
 * itself — so a tile reading `3` always yields exactly three rows, and the number never disagrees
 * with the table under it. `inventoryKpis`, the old `FleetSummary`-derived rollup this module used
 * to export, is gone with the strip it fed; the facade no longer fetches `GET /api/fleet/summary`
 * at all.
 */

/** One selectable view. `null` (never a member of this union) is the "All" state — reached by deselecting. */
export type InventoryView = 'needs-attention' | 'in-field' | 'issued' | 'in-stock' | 'maintenance';

/** Left→right order of the view row, exactly as §5.1 prints it. */
export const INVENTORY_VIEWS: readonly InventoryView[] = [
  'needs-attention',
  'in-field',
  'issued',
  'in-stock',
  'maintenance',
];

const VIEW_LABELS: Readonly<Record<InventoryView, string>> = {
  'needs-attention': 'Needs attention',
  'in-field': 'In field',
  issued: 'Issued',
  'in-stock': 'In stock',
  maintenance: 'Maintenance',
};

/**
 * The facts a view predicate reads — a structural subset of `features/inventory/vehicles-logic.ts
 * #VehicleRow`, so the table's own rows satisfy it without this module importing that one (and W5's
 * card model will satisfy it too).
 */
export interface InventoryViewRow {
  /** The merged lifecycle+inventory-state chip — four of the five views *are* its `kind`. */
  readonly stateChip: { readonly kind: InventoryStateChipKind };
  readonly readinessVerdict?: ReadinessVerdict;
  /** The first readiness blocker, already worded (`core/readiness/readiness-logic.ts#fleetRowAttention`); absent for a row with nothing to say. */
  readonly readinessCause?: string;
}

/**
 * Does this row belong in `view`?
 *
 * Four of the five are the row's own effective state, so a row's chip and the view it lands under
 * can never disagree. **Needs attention** is the union §3.1 describes — *grounded · open maintenance
 * · stale/never probed* — expressed as the two facts a row actually carries: it is in `MAINTENANCE`
 * (an open flight-blocking record; the same condition that makes `engage` refuse), or readiness has
 * something to say about it (a `NO_GO` verdict, or any non-`READY` feature, which is what
 * `readinessCause` being present means — including "never probed"). A row whose readiness was never
 * fetched at all carries neither and is *not* counted: absence of evidence is not a blocker.
 */
export function rowMatchesInventoryView(row: InventoryViewRow, view: InventoryView): boolean {
  switch (view) {
    case 'needs-attention':
      return row.stateChip.kind === 'maintenance' || row.readinessVerdict === 'NO_GO' || row.readinessCause !== undefined;
    case 'in-field':
      return row.stateChip.kind === 'in-field';
    case 'issued':
      return row.stateChip.kind === 'issued';
    case 'in-stock':
      return row.stateChip.kind === 'in-stock';
    case 'maintenance':
      return row.stateChip.kind === 'maintenance';
  }
}

/** Narrows rows to one view; `null` ("All") is the identity — the same array back, never a copy. */
export function filterRowsByInventoryView<T extends InventoryViewRow>(
  rows: readonly T[],
  view: InventoryView | null,
): readonly T[] {
  return view === null ? rows : rows.filter((row) => rowMatchesInventoryView(row, view));
}

/** One tile of the view row — everything `<vision-stat>` needs, decided here rather than in a template `@switch`. */
export interface InventoryViewTile {
  readonly view: InventoryView;
  readonly label: string;
  readonly count: number;
  /** Status tone only, never decoration (frontend-style §3): the two views that mean "something is wrong" colour their number, the three neutral ones don't. */
  readonly tone: 'default' | 'ok' | 'warn' | 'danger';
  /** The pulsing dot, for the one view whose assets really are airborne right now. */
  readonly live: boolean;
}

/** The five tiles, in {@link INVENTORY_VIEWS} order, counted over `rows`. */
export function inventoryViewTiles(rows: readonly InventoryViewRow[]): readonly InventoryViewTile[] {
  return INVENTORY_VIEWS.map((view) => {
    const count = rows.filter((row) => rowMatchesInventoryView(row, view)).length;
    return { view, label: VIEW_LABELS[view], count, tone: toneFor(view, count), live: view === 'in-field' && count > 0 };
  });
}

function toneFor(view: InventoryView, count: number): InventoryViewTile['tone'] {
  if (count === 0) {
    return 'default';
  }
  if (view === 'needs-attention') {
    return 'danger';
  }
  if (view === 'maintenance') {
    return 'warn';
  }
  return view === 'in-field' ? 'ok' : 'default';
}

/**
 * The view a session lands on when it has never chosen one (§5.1): **Needs attention** while
 * anything is in it, otherwise All. Applied exactly once, after the first load resolves — not
 * re-evaluated as counts change, so fixing the last grounded vehicle never yanks the table out from
 * under whoever is looking at it.
 */
export function defaultInventoryView(needsAttentionCount: number): InventoryView | null {
  return needsAttentionCount > 0 ? 'needs-attention' : null;
}

/**
 * A persisted view choice, three-valued: an `InventoryView`, `null` for "the operator explicitly
 * chose All", and `undefined` for "never chosen" — the only one of the three that lets
 * {@link defaultInventoryView} speak. Keeping explicit-All distinct from never-chosen is the whole
 * reason this is not a plain `string | null`: without it, deselecting Needs attention would be
 * undone by the next page load.
 */
export type InventoryViewSelection = InventoryView | null | undefined;

/** What "explicitly All" is written as in storage — a value, so the key's absence keeps meaning "never chosen". */
export const INVENTORY_VIEW_ALL = 'all';

/** Storage/query string → {@link InventoryViewSelection}; anything unrecognised (a stale or hand-edited value) reads as never-chosen. */
export function parseInventoryView(raw: string | null | undefined): InventoryViewSelection {
  if (raw === INVENTORY_VIEW_ALL) {
    return null;
  }
  return (INVENTORY_VIEWS as readonly string[]).includes(raw ?? '') ? (raw as InventoryView) : undefined;
}

/** {@link InventoryViewSelection} → the string {@link parseInventoryView} reads back. */
export function serializeInventoryView(view: InventoryView | null): string {
  return view ?? INVENTORY_VIEW_ALL;
}

/**
 * Clicking the selected view deselects it (§5.1: "All is reached by deselecting"), clicking any
 * other selects it — the toggle rule, written once so the facade's own click handler is one line.
 */
export function toggleInventoryView(current: InventoryView | null, clicked: InventoryView): InventoryView | null {
  return current === clicked ? null : clicked;
}
