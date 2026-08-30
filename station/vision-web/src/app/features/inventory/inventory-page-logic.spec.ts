import { describe, expect, it } from 'vitest';
import type { AssetAttention, CategoryCounts, FleetSummary } from '../../core/api/models';
import { inventoryKpis } from './inventory-page-logic';

function asset(partial: Partial<AssetAttention> = {}): AssetAttention {
  return {
    assetId: 'a-0',
    displayName: 'Asset',
    categoryId: 'drone',
    categoryName: 'Drone',
    lifecycle: 'ACTIVE',
    streaming: false,
    openEventCount: 0,
    ...partial,
  };
}

function counts(partial: Partial<CategoryCounts> = {}): CategoryCounts {
  return {
    categoryId: 'drone',
    categoryName: 'Drone',
    total: 0,
    active: 0,
    deactivated: 0,
    deleted: 0,
    streaming: 0,
    inStock: 0,
    issued: 0,
    inField: 0,
    maintenance: 0,
    retired: 0,
    ...partial,
  };
}

function summary(partial: Partial<FleetSummary> = {}): FleetSummary {
  return { categories: [], assets: [], totalAssets: 0, ...partial };
}

describe('inventoryKpis', () => {
  it('degrades to all-zero with no summary loaded yet', () => {
    expect(inventoryKpis(undefined)).toEqual({ totalAssets: 0, streaming: 0, active: 0, deactivated: 0, needsAttention: 0 });
  });

  it('counts streaming assets and sums active/deactivated across categories', () => {
    const kpis = inventoryKpis(
      summary({
        totalAssets: 3,
        assets: [asset({ streaming: true }), asset({ streaming: false })],
        categories: [counts({ active: 2, deactivated: 1 }), counts({ categoryId: 'cam', active: 1, deactivated: 0 })],
      }),
    );
    expect(kpis).toEqual({ totalAssets: 3, streaming: 1, active: 3, deactivated: 1, needsAttention: 0 });
  });

  it('counts an asset with any triggered attention reason toward needsAttention', () => {
    const kpis = inventoryKpis(summary({ assets: [asset({ batteryPercent: 5 }), asset({ batteryPercent: 90 })] }));
    expect(kpis.needsAttention).toBe(1);
  });
});
