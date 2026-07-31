import { describe, expect, it } from 'vitest';
import type { AssetAttention, CategoryCounts, FleetSummary } from '../../core/api/models';
import { attentionRows, categoryBars, reportKpis } from './reports-logic';

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
  return { categoryId: 'drone', categoryName: 'Drone', total: 0, active: 0, deactivated: 0, deleted: 0, streaming: 0, ...partial };
}

function summary(partial: Partial<FleetSummary> = {}): FleetSummary {
  return { categories: [], assets: [], totalAssets: 0, ...partial };
}

describe('reportKpis', () => {
  it('degrades to all-zero with no summary loaded yet', () => {
    expect(reportKpis(undefined)).toEqual({ totalAssets: 0, streaming: 0, active: 0, deactivated: 0, needsAttention: 0 });
  });

  it('counts streaming assets and sums active/deactivated across categories', () => {
    const kpis = reportKpis(
      summary({
        totalAssets: 3,
        assets: [asset({ streaming: true }), asset({ streaming: false })],
        categories: [counts({ active: 2, deactivated: 1 }), counts({ categoryId: 'cam', active: 1, deactivated: 0 })],
      }),
    );
    expect(kpis).toEqual({ totalAssets: 3, streaming: 1, active: 3, deactivated: 1, needsAttention: 0 });
  });

  it('counts an asset with any triggered attention reason toward needsAttention', () => {
    const kpis = reportKpis(summary({ assets: [asset({ batteryPercent: 5 }), asset({ batteryPercent: 90 })] }));
    expect(kpis.needsAttention).toBe(1);
  });
});

describe('categoryBars', () => {
  it('scales each bar against the largest total', () => {
    const bars = categoryBars([counts({ categoryId: 'a', categoryName: 'A', total: 10 }), counts({ categoryId: 'b', categoryName: 'B', total: 5 })]);
    expect(bars[0]).toEqual({ categoryId: 'a', name: 'A', total: 10, percentOfMax: 100 });
    expect(bars[1]).toEqual({ categoryId: 'b', name: 'B', total: 5, percentOfMax: 50 });
  });

  it('sorts largest total first', () => {
    const bars = categoryBars([counts({ categoryId: 'small', total: 1 }), counts({ categoryId: 'big', total: 9 })]);
    expect(bars.map((b) => b.categoryId)).toEqual(['big', 'small']);
  });

  it('floors a non-zero bar to stay visible, but a zero total renders zero', () => {
    const bars = categoryBars([counts({ categoryId: 'tiny', total: 1 }), counts({ categoryId: 'huge', total: 1000 }), counts({ categoryId: 'empty', total: 0 })]);
    const tiny = bars.find((b) => b.categoryId === 'tiny')!;
    const empty = bars.find((b) => b.categoryId === 'empty')!;
    expect(tiny.percentOfMax).toBeGreaterThanOrEqual(4);
    expect(empty.percentOfMax).toBe(0);
  });

  it('handles an empty category list without dividing by zero', () => {
    expect(categoryBars([])).toEqual([]);
  });
});

describe('attentionRows', () => {
  it('excludes an asset with nothing wrong', () => {
    expect(attentionRows([asset({ batteryPercent: 90 })])).toEqual([]);
  });

  it('includes only flagged assets, most simultaneous reasons first', () => {
    const quiet = asset({ assetId: 'q', displayName: 'Quiet', batteryPercent: 90 });
    const oneReason = asset({ assetId: 'one', displayName: 'One', batteryPercent: 5 });
    const twoReasons = asset({ assetId: 'two', displayName: 'Two', batteryPercent: 5, openEventCount: 2 });
    const rows = attentionRows([quiet, oneReason, twoReasons]);
    expect(rows.map((r) => r.asset.assetId)).toEqual(['two', 'one']);
  });

  it('never fires gps-degraded/geofence-breach reasons — this dashboard has neither input to draw from', () => {
    const rows = attentionRows([asset({ batteryPercent: 5 })]);
    expect(rows[0].reasons.map((r) => r.kind)).not.toContain('gps-degraded');
    expect(rows[0].reasons.map((r) => r.kind)).not.toContain('geofence-breach');
  });
});
