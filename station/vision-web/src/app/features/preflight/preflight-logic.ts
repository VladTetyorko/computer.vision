import { FEATURE_KEYS } from '../../core/api/models';
import type { FeatureStatus, ReadinessRow, ReadinessVerdict } from '../../core/api/models';
import { featureLabel } from '../../core/readiness/readiness-logic';

/**
 * Pure rendering/sorting/filtering helpers for `/operate/preflight`'s own table
 * (docs/plans/active/OPERATOR-UX-3-PLAN.md finding P1 + §2 P1). Feature-local (not `core/readiness/`)
 * per this wave's own file scope — this page is still the only consumer; `core/readiness/readiness-logic.ts`'s
 * `fleetRowAttention`/`sortReadinessRows` stay exactly as they were (unused by this page as of this
 * wave, not deleted — a future second consumer of the old comma-joined rollup or the simpler sort
 * still has them).
 *
 * Ordering a row's checks (canonical `FEATURE_KEYS` sequence first, then any unrecognised keys
 * alphabetically) is intentionally re-derived here rather than importing `readiness-logic.ts`'s own
 * private `orderedFeatureEntries` — that helper isn't exported, and this wave's own file list is
 * `features/preflight/*` only.
 */

/** `checks`, ordered: the frozen `FEATURE_KEYS` sequence first, then any unrecognised keys
 * alphabetically — mirrors `readiness-logic.ts#orderedFeatureEntries`'s own ordering rule so the
 * "first" blocker always agrees with the fleet report's canonical feature order, whichever surface
 * renders it. */
function orderedNotReadyKeys(checks: Readonly<Record<string, FeatureStatus>>): readonly string[] {
  const known = FEATURE_KEYS.filter((key) => key in checks && checks[key] !== 'READY');
  const knownSet: readonly string[] = FEATURE_KEYS;
  const unknown = Object.keys(checks)
    .filter((key) => !knownSet.includes(key) && checks[key] !== 'READY')
    .sort();
  return [...known, ...unknown];
}

/** The table's "needs attention" cell (§2 P1): the first non-`READY` check's own label, plus a count
 * of the rest — `first` is `undefined` when every check is `READY` (or `checks` is empty), the same
 * "render a dash, never a fabricated all-clear" honesty `readiness-logic.ts#fleetRowAttention`
 * already follows for its own comma-joined rollup. */
export interface BlockerSummary {
  readonly first: string | undefined;
  readonly more: number;
}

export function blockerSummary(checks: Readonly<Record<string, FeatureStatus>>): BlockerSummary {
  const notReady = orderedNotReadyKeys(checks);
  if (notReady.length === 0) {
    return { first: undefined, more: 0 };
  }
  return { first: featureLabel(notReady[0]), more: notReady.length - 1 };
}

const VERDICT_PRIORITY: Readonly<Record<ReadinessVerdict, number>> = { NO_GO: 0, UNKNOWN: 1, GO: 2 };

/** Number of non-`READY` checks a row carries — `sortWorstFirst`'s own "most failures first" tie-break
 * within the `UNKNOWN` tier. */
function failureCount(row: ReadinessRow): number {
  return orderedNotReadyKeys(row.features).length;
}

/**
 * Table row order (§2 P1): `NO_GO` first, then `UNKNOWN` — itself ordered by most failing checks
 * first (the asset with the most unresolved work is the one worth seeing first within that tier) —
 * then `GO` last. Ties (same verdict, and within `UNKNOWN` the same failure count) break
 * alphabetically by display name, stable regardless of fetch order — the same tie-break
 * `readiness-logic.ts#sortReadinessRows` uses. Sorting only; nothing here filters or hides a row
 * (OQ3, docs/plans/active/DRONE-ONBOARDING-PLAN.md §10 — advisory-only, unchanged by this wave).
 */
export function sortWorstFirst(rows: readonly ReadinessRow[]): readonly ReadinessRow[] {
  return [...rows].sort((a, b) => {
    const byVerdict = VERDICT_PRIORITY[a.verdict] - VERDICT_PRIORITY[b.verdict];
    if (byVerdict !== 0) {
      return byVerdict;
    }
    if (a.verdict === 'UNKNOWN') {
      const byFailures = failureCount(b) - failureCount(a);
      if (byFailures !== 0) {
        return byFailures;
      }
    }
    return a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' });
  });
}

/** The verdict stat cards' own filter (§2 P1): `undefined` (no card selected) returns every row
 * unchanged; otherwise only rows matching `verdict`. Filtering only, applied after {@link sortWorstFirst}
 * by the facade — this never re-sorts. */
export function applyVerdictFilter(rows: readonly ReadinessRow[], verdict: ReadinessVerdict | undefined): readonly ReadinessRow[] {
  return verdict === undefined ? rows : rows.filter((row) => row.verdict === verdict);
}

/** `vision-empty`'s title for a verdict filter that matched zero rows (§2 P1's own example, for
 * `NO_GO`, reused verbatim; `GO`/`UNKNOWN` follow the same shape). Only ever rendered once the board
 * already knows at least one asset exists (the page's pre-existing "no assets at all" empty state
 * stays first and is unaffected), so this is always a *filtered*-empty message, never confused with
 * an actually-empty fleet. */
export function emptyFilterTitle(verdict: ReadinessVerdict): string {
  switch (verdict) {
    case 'NO_GO':
      return 'No No-go drones — every failing check is listed under Unknown.';
    case 'UNKNOWN':
      return 'No Unknown drones — every drone has a definite Go or No-go verdict.';
    case 'GO':
      return 'No Go drones yet — nothing has cleared every check.';
  }
}
