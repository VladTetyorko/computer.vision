import { describe, expect, it } from 'vitest';
import type { AssetSummary } from '../api/models';
import { DEFAULT_CATEGORY_OPTIONS, deriveCategoryOptions } from './category-logic';

function assetSummary(partial: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-0',
    displayName: 'asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

describe('deriveCategoryOptions', () => {
  it('is the union of the defaults and in-use categories, deduped and sorted by name', () => {
    const assets = [
      assetSummary({ category: 'drone', categoryName: 'Drone' }),
      assetSummary({ category: 'thermal-rig', categoryName: 'Thermal Rig' }),
      assetSummary({ category: 'drone', categoryName: 'Drone' }),
    ];
    const options = deriveCategoryOptions(assets);
    // Every default stays available even when the fleet only uses one category —
    // an either/or picker offered a single option and trapped a first real-drone
    // onboarding (U-d live-walkthrough finding).
    for (const option of DEFAULT_CATEGORY_OPTIONS) {
      expect(options).toContainEqual(option);
    }
    expect(options).toContainEqual({ slug: 'thermal-rig', name: 'Thermal Rig' });
    const names = options.map((o) => o.name);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b)));
    expect(new Set(options.map((o) => o.slug)).size).toBe(options.length);
  });

  it('returns exactly the default set when no asset exists yet', () => {
    const options = deriveCategoryOptions([]);
    expect([...options].map((o) => o.slug).sort()).toEqual(
      [...DEFAULT_CATEGORY_OPTIONS].map((o) => o.slug).sort(),
    );
  });
});
