import type { AssetSummary, AssignedPilot, UserSummary } from '../../core/api/models';

/**
 * Pure, Angular-free logic behind `RosterPage` (`/manage/roster`, docs/plans/done/UI-REDESIGN-PLAN.md Wave 4
 * — FUNCTIONAL-NOW per the plan's own Additions table: `AssignmentController`/`PilotResponse`/
 * `OrgFacade` are all already live, this is the first dedicated frontend surface for them). Mirrors
 * `features/asset-detail/pilots-card.ts`'s own `assigned` computed (userId → display name, falling
 * back to a short id fragment for an unresolvable user) at the *fleet* scope instead of one asset.
 */

/** One row of the roster: an asset plus the display names of its currently-assigned pilots. */
export interface RosterRow {
  readonly asset: AssetSummary;
  readonly pilotNames: readonly string[];
}

/** Alphabetical by display name, case-insensitive — the roster's own default order. */
export function sortAssetsByName(assets: readonly AssetSummary[]): readonly AssetSummary[] {
  return [...assets].sort((a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' }));
}

/**
 * Joins each asset to its assigned pilots' resolved names — `pilotsByAsset` keyed by `assetId`,
 * `users` the full user list (for display-name resolution only; an id with no matching user, e.g. a
 * since-removed account, falls back to a short id fragment, mirroring `pilots-card.ts`'s own rule).
 * Sorted by {@link sortAssetsByName}; an asset with no entry in `pilotsByAsset` (not yet fetched, or
 * genuinely zero pilots) reads as an empty `pilotNames` array either way — the caller distinguishes
 * "still loading" via its own loading state, not by inspecting this array.
 */
export function buildRosterRows(
  assets: readonly AssetSummary[],
  pilotsByAsset: ReadonlyMap<string, readonly AssignedPilot[]>,
  users: readonly UserSummary[],
): readonly RosterRow[] {
  const nameById = new Map(users.map((user) => [user.userId, user.displayName]));
  return sortAssetsByName(assets).map((asset) => ({
    asset,
    pilotNames: (pilotsByAsset.get(asset.assetId) ?? []).map(
      (pilot) => nameById.get(pilot.userId) ?? pilot.userId.slice(0, 8),
    ),
  }));
}

/** Case-insensitive substring match on the asset's own name or any assigned pilot's resolved name. */
export function searchRosterRows(rows: readonly RosterRow[], query: string): readonly RosterRow[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return rows;
  }
  return rows.filter(
    (row) =>
      row.asset.displayName.toLowerCase().includes(q) ||
      row.pilotNames.some((name) => name.toLowerCase().includes(q)),
  );
}

/**
 * The fleet-level gap indicator (docs/extracts/design/13-roster.md: "⚠ N assets have no pilot" — "makes the
 * page answer a real management question at a glance"). Counts against **every** row this facade
 * loaded, not the search-filtered subset — the gap is a fleet fact, not something a typed query
 * should be able to hide.
 *
 * `connectedCategorySlugs` scopes the count to categories that can actually carry a pilot
 * (`Category#connected` — the same set `vehicles-logic.ts#filterVehicleRowsByConnected` splits the
 * Inventory page's Vehicles/Equipment tabs on) — a non-connected asset (a battery, a spare gimbal)
 * has no pilot concept at all, so counting it as "without a pilot" alongside a genuinely unassigned
 * drone was the exact bug WAREHOUSE-UX-CONTEXT.md's W10 finding C1(b) named: the tile read one
 * higher than the number of drones a manager could actually go assign someone to. A category not
 * yet loaded (an empty slug set, mid-fetch) counts nothing — the same honest "reads as zero until
 * resolved" transient {@link filterVehicleRowsByConnected}'s own doc comment describes, never a
 * fabricated "every asset is equipment".
 */
export function countAssetsWithoutPilot(
  rows: readonly RosterRow[],
  connectedCategorySlugs: ReadonlySet<string>,
): number {
  return rows.filter((row) => connectedCategorySlugs.has(row.asset.category) && row.pilotNames.length === 0).length;
}

/**
 * The "By asset" pivot's custody column (WAREHOUSE-UX-PLAN.md §3.2 D3, wave W7) — "may fly"
 * (`pilotNames`) vs. "has it" (this). One of four mutually exclusive states, in priority order:
 *
 * - `'maintenance'` / `'retired'` — the asset's **effective** `AssetSummary#inventoryState`
 *   (`InventoryStates#effective` server-side, never a stored/stale field) wins outright over
 *   whatever `custody` happens to still carry. This is WAREHOUSE-UX-CONTEXT.md's W10 finding C1(c):
 *   a grounded rover can still have a leftover `custody.custodianId` from before it was grounded (or
 *   none at all, if it was grounded straight out of stock), and reading custody first — as the
 *   pre-fix `custodianLabel` did — rendered a grounded asset as either a stale custodian name or
 *   plain "In stock", never the one fact that actually matters here: it isn't flyable right now
 *   regardless of who last held it.
 * - `'held'` — an active custodian (`ISSUED`/`IN_FIELD`), resolved to a display name the same way
 *   `pilotNames` resolves a pilot: the org's user list, falling back to a short id fragment for an
 *   unresolvable/deactivated account.
 * - `'in-stock'` — nobody has it and it isn't grounded or retired; the honest default.
 */
export type CustodyStatus =
  | { readonly kind: 'maintenance' }
  | { readonly kind: 'retired' }
  | { readonly kind: 'held'; readonly custodianName: string }
  | { readonly kind: 'in-stock' };

export function custodyStatusFor(asset: AssetSummary, nameById: ReadonlyMap<string, string>): CustodyStatus {
  if (asset.inventoryState === 'MAINTENANCE') {
    return { kind: 'maintenance' };
  }
  if (asset.inventoryState === 'RETIRED') {
    return { kind: 'retired' };
  }
  const custodianId = asset.custody?.custodianId;
  if (custodianId) {
    return { kind: 'held', custodianName: nameById.get(custodianId) ?? custodianId.slice(0, 8) };
  }
  return { kind: 'in-stock' };
}

/** {@link custodyStatusFor}'s own row text — `roster.html`'s "Has: X" / "In maintenance" / "Retired" /
 *  "In stock", one quiet muted string, never a second chip (D3's own "no new chip colour" rule). */
export function custodyStatusLabel(status: CustodyStatus): string {
  switch (status.kind) {
    case 'held':
      return `Has: ${status.custodianName}`;
    case 'maintenance':
      return 'In maintenance';
    case 'retired':
      return 'Retired';
    case 'in-stock':
      return 'In stock';
  }
}

/** {@link custodyStatusFor}'s own `title` attribute text — one sentence longer than the row label,
 *  matching the pre-fix `custodianLabel`'s own "In stock — nobody currently has it" tooltip register. */
export function custodyStatusTitle(status: CustodyStatus): string {
  switch (status.kind) {
    case 'held':
      return `Has: ${status.custodianName}`;
    case 'maintenance':
      return 'Grounded for maintenance — nobody currently has it';
    case 'retired':
      return 'Retired — no longer in active service';
    case 'in-stock':
      return 'In stock — nobody currently has it';
  }
}
