import type { AssetSummary } from '../api/models';

/**
 * The "existing categories + fallback list" category picker (docs/UX-QUICKWINS-PLAN.md QF-2's
 * "Create asset from this device"; docs/UX-REWORK-PLAN.md §U-d item 1 — the onboarding wizard's
 * Profile step reuses the exact same picker for its own category field).
 *
 * Started life in `features/devices/devices-page-logic.ts`; moved here once the onboarding wizard
 * (`features/onboarding/**`) needed the identical picker — this codebase has no precedent for one
 * feature importing another feature's private module (see `core/fleet/device-logic.ts`'s doc
 * comment). `features/devices/devices-page-logic.ts` re-exports everything below so its own
 * pre-existing import site keeps working verbatim.
 *
 * This cycle's `vision-api.ts` still carries no `listCategories()`/`GET /api/categories` call site,
 * even though that endpoint already exists server-side (`CategoryController`) — rather than
 * hand-roll a second, out-of-scope API method, the picker derives its options from categories
 * already present among whatever assets the caller has already loaded (real, in-use categories, no
 * extra round trip) and only falls back to a small hardcoded set — mirroring
 * `InMemoryCategoryRepository`'s own dev/Phase-0 seed (vision-app/devsupport) — for a brand-new
 * install with no asset to derive from yet. A future cycle wiring `VisionApi.listCategories()`
 * should point this at the live list instead, keeping the "derive from loaded assets" fast path.
 */

/** One category the picker can offer: enough to render and to send back as `category`. */
export interface CategoryOption {
  readonly slug: string;
  readonly name: string;
}

/** Fallback options for an install with no asset yet to derive real categories from. */
export const DEFAULT_CATEGORY_OPTIONS: readonly CategoryOption[] = [
  { slug: 'drone', name: 'Drone' },
  { slug: 'ip-camera', name: 'IP Camera' },
  { slug: 'usb-camera', name: 'USB Camera' },
  { slug: 'robot', name: 'Robot' },
  { slug: 'simulated', name: 'Simulated' },
];

/**
 * The categories already in use among `assets`, deduped by slug and sorted by name. Falls back to
 * {@link DEFAULT_CATEGORY_OPTIONS} only when `assets` is empty (nothing yet to derive a real list
 * from) — an onboarding wizard run against a brand-new install with zero existing assets is exactly
 * this case.
 */
export function deriveCategoryOptions(assets: readonly AssetSummary[]): readonly CategoryOption[] {
  // Union of the defaults and whatever the fleet already uses — never either/or. With only
  // one "Simulated" asset in the fleet, an either/or picker offered exactly one category and
  // trapped a first real-drone onboarding (U-d live-walkthrough finding).
  const bySlug = new Map<string, CategoryOption>();
  for (const option of DEFAULT_CATEGORY_OPTIONS) {
    bySlug.set(option.slug, option);
  }
  for (const asset of assets) {
    if (!bySlug.has(asset.category)) {
      bySlug.set(asset.category, { slug: asset.category, name: asset.categoryName });
    }
  }
  return [...bySlug.values()].sort((a, b) => a.name.localeCompare(b.name));
}
