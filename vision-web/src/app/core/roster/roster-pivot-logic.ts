import type { AssetSummary, AssignedPilot, UserSummary } from '../api/models';

/**
 * Pure, Angular-free logic behind `/manage/roster`'s `By asset | By pilot` pivot
 * (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4, docs/extracts/design/13-roster.md — task 3: "the pivot itself is a pure
 * function in `core/` (unit-tested), not view logic"). The **by-asset** direction was already correct
 * and stays exactly where it was (`features/roster/roster-logic.ts#buildRosterRows`, unchanged,
 * single-consumer, per this codebase's own "single consumer stays feature-local" precedent —
 * `assets-logic.ts`'s doc comment names it explicitly). This file is the **new** direction: the same
 * source data (assets, `assetId → AssignedPilot[]`, the user directory), pivoted the other way.
 */

/** `?by=` values. Any other/missing raw value defaults to `'asset'` — the page's pre-existing view. */
export type RosterPivot = 'asset' | 'pilot';

export function parseRosterPivot(raw: string | null | undefined): RosterPivot {
  return raw === 'pilot' ? 'pilot' : 'asset';
}

/** One asset a pilot is assigned to, resolved to a display name (the "By pilot" row's own assignment list). */
export interface PilotAssetAssignment {
  readonly assetId: string;
  readonly displayName: string;
}

/** One row of the "By pilot" pivot — a user plus the assets currently assigned to them, possibly none. */
export interface RosterPilotRow {
  readonly userId: string;
  readonly displayName: string;
  readonly username: string;
  readonly assignments: readonly PilotAssetAssignment[];
}

/**
 * The reverse pivot: every user in `users` — **not just ones with ≥1 assignment**
 * (docs/extracts/design/13-roster.md: "'By pilot' lists every pilot with their assets, including pilots with
 * zero assignments — that is the question the page's own name implies and currently cannot answer")
 * — each carrying the assets currently assigned to them, alphabetical both by pilot name and, within
 * a pilot, by asset name. Built from the same three inputs `buildRosterRows` already takes (assets,
 * `pilotsByAsset`, users) — same source data, the other direction through it; an assignment whose
 * `assetId` isn't in `assets` (a stale/out-of-scope reference) is silently dropped rather than
 * shown against a name this function has nothing to resolve it to.
 */
export function buildPilotRows(
  assets: readonly AssetSummary[],
  pilotsByAsset: ReadonlyMap<string, readonly AssignedPilot[]>,
  users: readonly UserSummary[],
): readonly RosterPilotRow[] {
  const assetById = new Map(assets.map((asset) => [asset.assetId, asset]));
  const assignmentsByUser = new Map<string, PilotAssetAssignment[]>();
  for (const [assetId, pilots] of pilotsByAsset) {
    const asset = assetById.get(assetId);
    if (!asset) {
      continue;
    }
    for (const pilot of pilots) {
      const list = assignmentsByUser.get(pilot.userId) ?? [];
      list.push({ assetId, displayName: asset.displayName });
      assignmentsByUser.set(pilot.userId, list);
    }
  }
  return [...users]
    .sort((a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' }))
    .map((user) => ({
      userId: user.userId,
      displayName: user.displayName,
      username: user.username,
      assignments: (assignmentsByUser.get(user.userId) ?? [])
        .slice()
        .sort((a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' })),
    }));
}

/** Case-insensitive substring match on the pilot's own name/username, or any of their assigned assets' names. */
export function searchPilotRows(rows: readonly RosterPilotRow[], query: string): readonly RosterPilotRow[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return rows;
  }
  return rows.filter(
    (row) =>
      row.displayName.toLowerCase().includes(q) ||
      row.username.toLowerCase().includes(q) ||
      row.assignments.some((assignment) => assignment.displayName.toLowerCase().includes(q)),
  );
}
