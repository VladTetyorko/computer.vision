import { describe, expect, it } from 'vitest';
import type { AssetSummary } from '../api/models';
import { deriveCategoryOptions, type CategoryOption } from './category-logic';

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

const SEED_CATEGORIES: readonly CategoryOption[] = [
  { slug: 'drone', name: 'Drone' },
  { slug: 'ip-camera', name: 'IP Camera' },
];

describe('deriveCategoryOptions', () => {
  it('is the union of the seed categories and in-use categories, deduped and sorted by name', () => {
    const assets = [
      assetSummary({ category: 'drone', categoryName: 'Drone' }),
      assetSummary({ category: 'thermal-rig', categoryName: 'Thermal Rig' }),
      assetSummary({ category: 'drone', categoryName: 'Drone' }),
    ];
    const options = deriveCategoryOptions(assets, SEED_CATEGORIES);
    // Every seed category stays available even when the fleet only uses one of them — an either/or
    // picker offered a single option and trapped a first real-drone onboarding (U-d live-walkthrough
    // finding).
    for (const option of SEED_CATEGORIES) {
      expect(options).toContainEqual(option);
    }
    expect(options).toContainEqual({ slug: 'thermal-rig', name: 'Thermal Rig' });
    const names = options.map((o) => o.name);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b)));
    expect(new Set(options.map((o) => o.slug)).size).toBe(options.length);
  });

  it('returns exactly the seed set when no asset exists yet', () => {
    const options = deriveCategoryOptions([], SEED_CATEGORIES);
    expect([...options].map((o) => o.slug).sort()).toEqual([...SEED_CATEGORIES].map((o) => o.slug).sort());
  });

  it('returns only in-use categories when no seed list is supplied', () => {
    const assets = [assetSummary({ category: 'thermal-rig', categoryName: 'Thermal Rig' })];
    expect(deriveCategoryOptions(assets)).toEqual([{ slug: 'thermal-rig', name: 'Thermal Rig' }]);
  });

  it('returns an empty list when both the seed and the fleet are empty', () => {
    expect(deriveCategoryOptions([])).toEqual([]);
  });
});
