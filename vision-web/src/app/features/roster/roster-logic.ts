import type { AssetSummary, AssignedPilot, UserSummary } from '../../core/api/models';

/**
 * Pure, Angular-free logic behind `RosterPage` (`/manage/roster`, docs/UI-REDESIGN-PLAN.md Wave 4
 * — FUNCTIONAL-NOW per the plan's own Additions table: `AssignmentController`/`PilotResponse`/
 * `OrgStore` are all already live, this is the first dedicated frontend surface for them). Mirrors
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
 * The fleet-level gap indicator (docs/design/13-roster.md: "⚠ N assets have no pilot" — "makes the
 * page answer a real management question at a glance"). Counts against **every** row this facade
 * loaded, not the search-filtered subset — the gap is a fleet fact, not something a typed query
 * should be able to hide.
 */
export function countAssetsWithoutPilot(rows: readonly RosterRow[]): number {
  return rows.filter((row) => row.pilotNames.length === 0).length;
}
