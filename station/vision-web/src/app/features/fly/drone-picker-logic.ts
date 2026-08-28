import type { AssetSummary } from '../../core/api/models';

/**
 * Pure, Angular-free logic behind `DronePickerPage` (docs/plans/active/OPERATOR-UX-3-PLAN.md finding T1,
 * §2 T1, wave T1) — grouping the `/fly` picker into "your vehicles" vs. "simulated", sorting each
 * group so the operator's eye lands on what's actually flyable, and labelling an offline card's age
 * honestly instead of the bare word "Offline". Split out from `drone-picker.ts`/`-facade.ts` so this
 * is unit-testable without HTTP, `Date.now()`, or a component — mirrors every other page's own
 * `*-logic.ts` split (`features/fly/fly-logic.ts`, `core/telemetry/telemetry-logic.ts`).
 *
 * Deliberately does **not** touch `fly-logic.ts#sortAssetsForPicker` — that function also backs
 * `cockpit-facade.ts#orderedSwitcherAssets` (the cockpit header's drone switcher `<select>`), which
 * stays a flat streaming-then-alphabetical list outside this wave's scope. `groupAndSort` below is
 * this page's own, separate sort with its own contract (grouped, `lastSeen`-aware).
 */

// --- "Simulated" detection (T1 finding: "3 real ESP32 rovers mixed with simulated aircraft, no
// order, no filter") --------------------------------------------------------------------------

/**
 * The category `DefaultSimulationService` stamps every simulated asset under
 * (`features/devices/simulate-logic.ts#SIMULATED_CATEGORY`, `SOURCE-ONBOARDING-CONTEXT.md` §4 —
 * `DefaultSimulationService#simulate` always creates a **new** asset under this fixed category).
 * Duplicated here rather than imported: this codebase has no precedent for one feature importing
 * another feature's own `*-logic.ts` (see `core/telemetry/telemetry-logic.ts`'s doc comment on
 * `trackingIdChanged` for the "moves to `core/` once a second consumer needs it" precedent this
 * would otherwise follow) — a two-line string constant duplicated once is cheaper than a new
 * cross-feature import or a `core/` move for a single extra reader.
 */
export const SIMULATED_CATEGORY = 'simulated';

/**
 * Whether an asset is simulated. `SOURCE-ONBOARDING-CONTEXT.md` §4 documents this as a known
 * coupling defect — "simulated" is meant to eventually be a per-*device* fact (a `SIMULATED`
 * device origin), not stamped on the whole asset via its category — but that split (its own S2/S5
 * waves) hasn't shipped: `AssetSummary` (this picker's own `listAssets()` response shape) carries
 * no `devices` field at all today (only `AssetDetails` does), so the category is the only signal
 * either this function or the pre-existing `features/devices/simulate-logic.ts#isSimulatedAsset`
 * can read. Once a device-level signal exists on `AssetSummary`, prefer it here first.
 */
export function isSimulated(asset: Pick<AssetSummary, 'category'>): boolean {
  return asset.category === SIMULATED_CATEGORY;
}

// --- Grouping + sort (T1 finding: "17 identical 'Offline' cards … no order, no filter") -------

/** The picker's two groups — `drone-picker.html`'s only source for what to render in each section. */
export interface PickerGroups {
  readonly yours: readonly AssetSummary[];
  readonly simulated: readonly AssetSummary[];
}

/**
 * `lastUsedAt`'s age at `nowMs`, clamped to never go negative (a clock-skewed or future-dated
 * timestamp reads as "just now", never as "in the future") — `Infinity` for an asset that has
 * never been used, so {@link sortByFreshness}'s numeric comparison sorts it after every asset with
 * a real timestamp without a separate branch.
 */
function ageMsOrInfinity(asset: Pick<AssetSummary, 'lastUsedAt'>, nowMs: number): number {
  if (!asset.lastUsedAt) {
    return Infinity;
  }
  return Math.max(0, nowMs - Date.parse(asset.lastUsedAt));
}

/**
 * One group's own order: streaming first (mirrors `fly-logic.ts#sortAssetsForPicker`'s reasoning —
 * an operator most likely wants what's already in the air), then by `lastUsedAt` **descending**
 * (most recently seen first) within each streaming/offline split, never-seen assets last of all.
 * Equal ages (including two never-seen assets, both `Infinity`) fall back to the same
 * case-insensitive alphabetical tiebreak `sortAssetsForPicker` uses, so ordering never depends on
 * fetch/insertion order. `Array#sort` is stable, but every branch here is a real, deterministic
 * comparison — nothing relies on that stability alone.
 */
function sortByFreshness(assets: readonly AssetSummary[], nowMs: number): readonly AssetSummary[] {
  return [...assets].sort((a, b) => {
    if (a.status !== b.status) {
      return a.status === 'STREAMING' ? -1 : 1;
    }
    const ageA = ageMsOrInfinity(a, nowMs);
    const ageB = ageMsOrInfinity(b, nowMs);
    if (ageA !== ageB) {
      return ageA - ageB;
    }
    return a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' });
  });
}

/**
 * Splits `assets` into "your vehicles" (§2 T1: "any asset with a non-simulated device / category ≠
 * Simulated") and "simulated", each independently sorted by {@link sortByFreshness}. `nowMs` is a
 * parameter (not `Date.now()` read in here) purely so this stays deterministic under test — the
 * facade supplies the real clock, same idiom as `fly-logic.ts#lastSeenLabel`.
 */
export function groupAndSort(assets: readonly AssetSummary[], nowMs: number): PickerGroups {
  const yours: AssetSummary[] = [];
  const simulated: AssetSummary[] = [];
  for (const asset of assets) {
    (isSimulated(asset) ? simulated : yours).push(asset);
  }
  return { yours: sortByFreshness(yours, nowMs), simulated: sortByFreshness(simulated, nowMs) };
}

// --- Honest age labels (T1 finding: "17 identical 'Offline' cards" — the word alone says nothing
// about how stale each one actually is; CLAUDE.md rule 9, "newest data … should be used", extends
// here to "the operator must be able to tell newest from oldest at a glance") ------------------

/**
 * A coarse, human-scale age label: `45s`, `12m`, `2h 29m`, `6d`. Written locally for this wave
 * rather than reused from `core/telemetry/telemetry-logic.ts#humanAge` (H1's own finding) even
 * though H1 landed *during* this task (a genuine race on the shared working tree — `git log`/`grep`
 * found no `humanAge` when this wave started; `git diff` later showed H1's own uncommitted edit to
 * that file). Not reused because the two contracts actively **disagree**, not just differ in
 * precision: past a minute, H1's `humanAge` always renders both units of its tier, remainder or not
 * (`4h 0m`, `6d 4h`), while T1's own §2 spec
 * gives `offlineLabel`'s example output as bare `Offline · 6d`, no hours remainder. Reusing H1's
 * function verbatim would silently break T1's own stated contract. **Flagged for the plan author**:
 * either T1's example is loosened to accept `6d 4h`-style output (then this function should be
 * deleted and `offlineLabel` should import H1's `humanAge` instead), or H1's function gains an
 * "omit a zero/coarse remainder" mode T1 can opt into — this file does not decide that unilaterally.
 * Until resolved, this is a deliberate, documented duplication, not an oversight.
 */
export function humanAge(ageSeconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(ageSeconds));
  const days = Math.floor(totalSeconds / 86_400);
  if (days >= 1) {
    return `${days}d`;
  }
  const hours = Math.floor(totalSeconds / 3600);
  if (hours >= 1) {
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  if (minutes >= 1) {
    return `${minutes}m`;
  }
  return `${totalSeconds}s`;
}

/**
 * The picker card's status-chip text for a non-streaming asset — `Offline · 2h 29m` / `Offline ·
 * 6d`, or `Never seen` for an asset with no `lastUsedAt` at all (never a fabricated age). Replaces
 * the bare word "Offline" every card used to show regardless of whether the asset went dark 2
 * minutes or 4 days ago (T1's own finding). Streaming assets never call this — `drone-picker-card.ts`
 * keeps the live chip for those, unchanged.
 */
export function offlineLabel(asset: Pick<AssetSummary, 'lastUsedAt'>, nowMs: number): string {
  if (!asset.lastUsedAt) {
    return 'Never seen';
  }
  const ageSeconds = Math.max(0, (nowMs - Date.parse(asset.lastUsedAt)) / 1000);
  return `Offline · ${humanAge(ageSeconds)}`;
}
