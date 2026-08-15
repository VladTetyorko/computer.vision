import type { AssetSummary } from '../api/models';

/**
 * The "existing categories + backend seed list" category picker (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2's
 * "Create asset from this device"; docs/plans/done/UX-REWORK-PLAN.md §U-d item 1 — the onboarding wizard's
 * Profile step reuses the exact same picker for its own category field).
 *
 * Started life in `features/devices/devices-page-logic.ts`; moved here once the onboarding wizard
 * (`features/onboarding/**`) needed the identical picker — this codebase has no precedent for one
 * feature importing another feature's private module (see `core/fleet/device-logic.ts`'s doc
 * comment). `features/devices/devices-page-logic.ts` re-exports everything below so its own
 * pre-existing import site keeps working verbatim.
 *
 * `deriveCategoryOptions` used to fall back to a hand-maintained `DEFAULT_CATEGORY_OPTIONS` constant
 * — a small hardcoded list the doc comment here openly admitted was "mirroring
 * `InMemoryCategoryRepository`'s own dev/Phase-0 seed" from memory, with no mechanism keeping the two
 * in sync. Now that `VisionApi.listCategories()` (`GET /api/categories`) exists, callers fetch the
 * backend's own defined-category list once and pass it in as `seedCategories` — the *real* seed,
 * never a copy that can drift from it. `deriveCategoryOptions` itself stays a pure function: it only
 * unions whatever seed list it's handed with the categories actually in use among `assets`.
 */

/** One category the picker can offer: enough to render and to send back as `category`. */
export interface CategoryOption {
  readonly slug: string;
  readonly name: string;
}

/**
 * The categories already in use among `assets`, unioned with `seedCategories` (the backend's own
 * defined-category list, `VisionApi.listCategories()`), deduped by slug and sorted by name.
 *
 * Union, never either/or: with only one "Simulated" asset in the fleet, an either/or picker offered
 * exactly one category and trapped a first real-drone onboarding (U-d live-walkthrough finding) —
 * every seed category stays available even when the fleet only uses one of them.
 */
export function deriveCategoryOptions(
  assets: readonly AssetSummary[],
  seedCategories: readonly CategoryOption[] = [],
): readonly CategoryOption[] {
  const bySlug = new Map<string, CategoryOption>();
  for (const option of seedCategories) {
    bySlug.set(option.slug, { slug: option.slug, name: option.name });
  }
  for (const asset of assets) {
    if (!bySlug.has(asset.category)) {
      bySlug.set(asset.category, { slug: asset.category, name: asset.categoryName });
    }
  }
  return [...bySlug.values()].sort((a, b) => a.name.localeCompare(b.name));
}
