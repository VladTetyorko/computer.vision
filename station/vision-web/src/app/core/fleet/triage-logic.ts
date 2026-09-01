import type { AssetSummary } from '../api/models';
import { humanAge } from '../telemetry/telemetry-logic';

/**
 * Pure, Angular-free "how should a fleet of assets be grouped/sorted so the operator's eye lands on
 * what's flyable/actionable first" logic — originally `features/fly/drone-picker-logic.ts`'s own
 * grouping/sort/honest-age rules (docs/plans/active/OPERATOR-UX-3-PLAN.md finding T1, §2 T1, wave T1), moved
 * here (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3, §2 N3, wave W2) when the Command dashboard's
 * entity rail needed the identical "Your vehicles / Simulated" triage `/fly`'s picker already had —
 * this codebase has no precedent for one feature importing another feature's own `*-logic.ts` (see
 * `core/fleet/device-logic.ts`'s doc comment for the original precedent this follows, most recently
 * repeated by `core/fleet/attention-logic.ts`'s own move out of `features/command/command-logic.ts`)
 * — a shared `core/` home was used instead. `drone-picker-logic.ts` re-exports every name below so
 * its own pre-existing import sites (`drone-picker.ts`, `drone-picker-card.ts`, `drone-picker-facade.ts`)
 * keep working verbatim, and `/fly`'s own behavior is unchanged byte-for-byte — it never threads the
 * new `attentionRank` parameter {@link groupAndSort} gained for Command below.
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
 * waves) hasn't shipped: `AssetSummary` (the `/fly` picker's own `listAssets()` response shape)
 * carries no `devices` field at all today (only `AssetDetails` does), so the category is the only
 * signal either this function or the pre-existing `features/devices/simulate-logic.ts#isSimulatedAsset`
 * can read. Once a device-level signal exists on `AssetSummary`, prefer it here first. Structurally
 * typed on just `category` (not the whole `AssetSummary`) so a caller whose own row shape only
 * carries a `category` field of its own — `CommandFacade`'s rail rows, synthesized from
 * `AssetAttention.categoryId` — can pass one straight through without widening to a full `AssetSummary`.
 */
export function isSimulated(asset: Pick<AssetSummary, 'category'>): boolean {
  return asset.category === SIMULATED_CATEGORY;
}

// --- Grouping + sort (T1 finding: "17 identical 'Offline' cards … no order, no filter") -------

/** One grouped-and-sorted result's two triage groups — `T` defaults to `AssetSummary` (`/fly`'s own
 *  shape); `CommandFacade` instantiates it with its own synthesized rail-row shape instead (see
 *  {@link groupAndSort}'s own doc comment). */
export interface PickerGroups<T extends TriageCandidate = AssetSummary> {
  readonly yours: readonly T[];
  readonly simulated: readonly T[];
}

/**
 * The minimum shape {@link groupAndSort}/{@link sortByFreshness} need to triage a row — exactly the
 * four `AssetSummary` fields the sort/grouping logic itself reads. Any caller whose own row carries
 * these four (verbatim or synthesized from a differently-shaped source DTO) can be sorted the same
 * way without inventing a second sort — see `CommandFacade#railGroups`'s own doc comment for the
 * synthesis `AssetAttention`-backed rows need (that DTO has no `category`/`status`/`lastUsedAt` of
 * its own).
 */
export type TriageCandidate = Pick<AssetSummary, 'displayName' | 'category' | 'status' | 'lastUsedAt'>;

/**
 * An optional, per-row "how urgently does this need attention" signal (docs/plans/active/OPERATOR-UX-4-PLAN.md
 * finding N3, §2 N3) — higher ranks first; `0`/negative reads as "nothing to escalate". A plain
 * `(row) => number` rather than baking `core/fleet/attention-logic.ts`'s `AttentionReason`/severity
 * vocabulary into this file: this module has no dependency on that one, and `/fly`'s own caller (no
 * attention rows in scope) simply omits it, which reproduces the pre-N3 sort exactly.
 */
export type AttentionRank<T> = (asset: T) => number;

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
 * One flat triage comparator (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U5, §2 U5) — the same
 * per-candidate priority {@link sortByFreshness} applies *within* one of `groupAndSort`'s two groups,
 * generalized to a plain `Array#sort` comparator for a caller that wants a single ungrouped list
 * rather than the Yours/Simulated section split — `features/assets/**`, the third fleet list to need
 * this triage after `/fly`'s picker and Command's rail, and the first that has no grouped section
 * headers to carry the real-vs-simulated split visually. Real (non-simulated) rows sort ahead of
 * simulated ones as the *outermost* tier here — the flattened equivalent of `groupAndSort`'s own
 * yours-then-simulated group order, reproduced with no separate visible section. Streaming first,
 * then (when supplied) `attentionRank` descending, then `lastUsedAt` descending/never-seen-last, then
 * the same case-insensitive alphabetical tiebreak — identical to {@link sortByFreshness}'s own order,
 * which now delegates here directly: within either of its two homogeneous groups `isSimulated` never
 * differs between any two rows, so this extra outer tier is always a no-op there (verified by this
 * file's own pre-existing `groupAndSort`/`sortByFreshness` spec, which stayed green unmodified
 * through this refactor).
 */
export function triageOrder<T extends TriageCandidate>(
  a: T,
  b: T,
  nowMs: number,
  attentionRank?: AttentionRank<T>,
): number {
  const simA = isSimulated(a);
  const simB = isSimulated(b);
  if (simA !== simB) {
    return simA ? 1 : -1;
  }
  if (a.status !== b.status) {
    return a.status === 'STREAMING' ? -1 : 1;
  }
  if (attentionRank) {
    const rankDiff = attentionRank(b) - attentionRank(a);
    if (rankDiff !== 0) {
      return rankDiff;
    }
  }
  const ageA = ageMsOrInfinity(a, nowMs);
  const ageB = ageMsOrInfinity(b, nowMs);
  if (ageA !== ageB) {
    return ageA - ageB;
  }
  return a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' });
}

/**
 * One group's own order: streaming first (mirrors `features/fly/fly-logic.ts#sortAssetsForPicker`'s
 * reasoning — an operator most likely wants what's already in the air), then — when the caller
 * supplies one — `attentionRank` descending (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3: "needs
 * attention (severity desc)"), then by `lastUsedAt` **descending** (most recently seen first) within
 * each remaining tie, never-seen assets last of all, falling back to the same case-insensitive
 * alphabetical tiebreak `sortAssetsForPicker` uses — see {@link triageOrder}, which now carries this
 * comparison (plus its own extra outer real-vs-simulated tier, moot within one already-homogeneous
 * group). `Array#sort` is stable, but every branch is a real, deterministic comparison — nothing
 * relies on that stability alone.
 */
function sortByFreshness<T extends TriageCandidate>(
  assets: readonly T[],
  nowMs: number,
  attentionRank?: AttentionRank<T>,
): readonly T[] {
  return [...assets].sort((a, b) => triageOrder(a, b, nowMs, attentionRank));
}

/**
 * Splits `assets` into "your vehicles" (§2 T1: "any asset with a non-simulated device / category ≠
 * Simulated") and "simulated", each independently sorted by {@link sortByFreshness}. `nowMs` is a
 * parameter (not `Date.now()` read in here) purely so this stays deterministic under test — a
 * caller supplies the real clock, same idiom as `features/fly/fly-logic.ts#lastSeenLabel`.
 *
 * `attentionRank` (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3, new, optional) threads an
 * attention-severity tier into the sort — `CommandFacade#railGroups` is the one caller that supplies
 * it (extending this shared sort rather than the Command rail hand-rolling a second one); `/fly`'s
 * own call omits it and gets the pre-N3 order back exactly.
 */
export function groupAndSort<T extends TriageCandidate>(
  assets: readonly T[],
  nowMs: number,
  attentionRank?: AttentionRank<T>,
): PickerGroups<T> {
  const yours: T[] = [];
  const simulated: T[] = [];
  for (const asset of assets) {
    (isSimulated(asset) ? simulated : yours).push(asset);
  }
  return {
    yours: sortByFreshness(yours, nowMs, attentionRank),
    simulated: sortByFreshness(simulated, nowMs, attentionRank),
  };
}

// --- Honest age labels (T1 finding: "17 identical 'Offline' cards" — the word alone says nothing
// about how stale each one actually is; CLAUDE.md rule 9, "newest data … should be used", extends
// here to "the operator must be able to tell newest from oldest at a glance") ------------------

/**
 * The picker card's status-chip text for a non-streaming asset — `Offline · 2h 29m` / `Offline ·
 * 6d 4h`, or `Never seen` for an asset with no `lastUsedAt` at all (never a fabricated age). Replaces
 * the bare word "Offline" every card used to show regardless of whether the asset went dark 2
 * minutes or 4 days ago (T1's own finding). Streaming assets never call this — `drone-picker-card.ts`
 * keeps the live chip for those, unchanged. Keyed off `lastUsedAt` (an `AssetSummary`-shaped field);
 * `CommandFacade`'s own rail rows have no such field on their source DTO and render their offline
 * age straight off `attentionAgeLabel`'s `telemetryAgeMs` instead — see that call site's own doc
 * comment.
 */
export function offlineLabel(asset: Pick<AssetSummary, 'lastUsedAt'>, nowMs: number): string {
  if (!asset.lastUsedAt) {
    return 'Never seen';
  }
  const ageSeconds = Math.max(0, (nowMs - Date.parse(asset.lastUsedAt)) / 1000);
  return `Offline · ${humanAge(ageSeconds)}`;
}
