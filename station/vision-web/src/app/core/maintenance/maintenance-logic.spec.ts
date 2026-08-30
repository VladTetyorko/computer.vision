import { describe, expect, it } from 'vitest';
import type { AssetSummary, InventoryState, MaintenanceKind, MaintenanceRecord } from '../api/models';
import {
  MAINTENANCE_KIND_LABELS,
  groundableAssets,
  hoursSinceClose,
  isOpenRecord,
  maintenanceKpis,
  openRecordRows,
  openRecords,
  primaryOpenRecord,
  recentlyClosedRecordRows,
} from './maintenance-logic';

function asset(partial: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-0',
    displayName: 'Asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'org',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

function record(partial: Partial<MaintenanceRecord> = {}): MaintenanceRecord {
  return {
    id: 'r-0',
    assetId: 'a-0',
    kind: 'GROUNDING',
    openedAt: '2026-08-01T00:00:00Z',
    openedBy: 'u-0',
    summary: 'summary',
    ...partial,
  };
}

describe('isOpenRecord', () => {
  it('is true when closedAt is absent', () => {
    expect(isOpenRecord(record())).toBe(true);
  });

  it('is false once closedAt is set', () => {
    expect(isOpenRecord(record({ closedAt: '2026-08-02T00:00:00Z' }))).toBe(false);
  });
});

describe('primaryOpenRecord', () => {
  it('is undefined for an empty list', () => {
    expect(primaryOpenRecord([])).toBeUndefined();
  });

  it('ignores closed records', () => {
    expect(primaryOpenRecord([record({ id: 'r-1', closedAt: '2026-08-02T00:00:00Z' })])).toBeUndefined();
  });

  it('picks the most severe open kind — GROUNDING over INSPECTION_DUE over REPAIR over NOTE', () => {
    const records = [
      record({ id: 'r-note', kind: 'NOTE' }),
      record({ id: 'r-repair', kind: 'REPAIR' }),
      record({ id: 'r-inspection', kind: 'INSPECTION_DUE' }),
      record({ id: 'r-grounding', kind: 'GROUNDING' }),
    ];
    expect(primaryOpenRecord(records)?.id).toBe('r-grounding');
  });

  it('breaks a tie within the same kind by the oldest openedAt', () => {
    const records = [
      record({ id: 'r-newer', kind: 'REPAIR', openedAt: '2026-08-05T00:00:00Z' }),
      record({ id: 'r-older', kind: 'REPAIR', openedAt: '2026-08-01T00:00:00Z' }),
    ];
    expect(primaryOpenRecord(records)?.id).toBe('r-older');
  });
});

describe('openRecords', () => {
  it('flattens every asset\'s open records, worst-first, and drops closed ones', () => {
    const byAsset = new Map<string, readonly MaintenanceRecord[]>([
      ['a-1', [record({ id: 'r-1', assetId: 'a-1', kind: 'REPAIR' }), record({ id: 'r-1-closed', assetId: 'a-1', closedAt: '2026-08-02T00:00:00Z' })]],
      ['a-2', [record({ id: 'r-2', assetId: 'a-2', kind: 'GROUNDING' })]],
    ]);
    expect(openRecords(byAsset).map((r) => r.id)).toEqual(['r-2', 'r-1']);
  });
});

describe('openRecordRows', () => {
  it('joins each open record back to its asset', () => {
    const assets = [asset({ assetId: 'a-1', displayName: 'Falcon' })];
    const byAsset = new Map<string, readonly MaintenanceRecord[]>([['a-1', [record({ id: 'r-1', assetId: 'a-1' })]]]);
    const rows = openRecordRows(assets, byAsset);
    expect(rows).toEqual([{ asset: assets[0], record: byAsset.get('a-1')![0] }]);
  });

  it('skips a record whose asset is not in the given list rather than fabricating a row', () => {
    const byAsset = new Map<string, readonly MaintenanceRecord[]>([['a-missing', [record({ id: 'r-1', assetId: 'a-missing' })]]]);
    expect(openRecordRows([], byAsset)).toEqual([]);
  });
});

describe('recentlyClosedRecordRows', () => {
  it('returns only closed records, newest-closed-first', () => {
    const assets = [asset({ assetId: 'a-1' })];
    const byAsset = new Map<string, readonly MaintenanceRecord[]>([
      [
        'a-1',
        [
          record({ id: 'r-open', assetId: 'a-1' }),
          record({ id: 'r-old', assetId: 'a-1', closedAt: '2026-08-01T00:00:00Z' }),
          record({ id: 'r-new', assetId: 'a-1', closedAt: '2026-08-10T00:00:00Z' }),
        ],
      ],
    ]);
    expect(recentlyClosedRecordRows(assets, byAsset).map((row) => row.record.id)).toEqual(['r-new', 'r-old']);
  });

  it('caps at the given limit', () => {
    const assets = [asset({ assetId: 'a-1' })];
    const records = Array.from({ length: 5 }, (_, i) =>
      record({ id: `r-${i}`, assetId: 'a-1', closedAt: `2026-08-0${i + 1}T00:00:00Z` }),
    );
    const byAsset = new Map<string, readonly MaintenanceRecord[]>([['a-1', records]]);
    expect(recentlyClosedRecordRows(assets, byAsset, 2)).toHaveLength(2);
  });
});

function assetWithState(inventoryState: InventoryState, assetId = 'a-0'): AssetSummary {
  return asset({ assetId, inventoryState });
}

describe('maintenanceKpis', () => {
  it('counts RETIRED assets directly, with no record lookup', () => {
    const assets = [assetWithState('RETIRED')];
    expect(maintenanceKpis(assets, new Map())).toEqual({ grounded: 0, inspectionDue: 0, inRepair: 0, retired: 1 });
  });

  it('buckets a MAINTENANCE asset by its primary open record kind', () => {
    const cases: readonly [MaintenanceKind, keyof ReturnType<typeof maintenanceKpis>][] = [
      ['GROUNDING', 'grounded'],
      ['INSPECTION_DUE', 'inspectionDue'],
      ['REPAIR', 'inRepair'],
    ];
    for (const [kind, bucket] of cases) {
      const assets = [assetWithState('MAINTENANCE')];
      const byAsset = new Map<string, readonly MaintenanceRecord[]>([['a-0', [record({ kind })]]]);
      const kpis = maintenanceKpis(assets, byAsset);
      expect(kpis[bucket]).toBe(1);
    }
  });

  it('counts a MAINTENANCE asset with only a NOTE-kind open record toward nothing, honestly', () => {
    const assets = [assetWithState('MAINTENANCE')];
    const byAsset = new Map<string, readonly MaintenanceRecord[]>([['a-0', [record({ kind: 'NOTE' })]]]);
    expect(maintenanceKpis(assets, byAsset)).toEqual({ grounded: 0, inspectionDue: 0, inRepair: 0, retired: 0 });
  });

  it('ignores IN_STOCK/ISSUED/IN_FIELD assets entirely', () => {
    const assets = [assetWithState('IN_STOCK', 'a-1'), assetWithState('ISSUED', 'a-2'), assetWithState('IN_FIELD', 'a-3')];
    expect(maintenanceKpis(assets, new Map())).toEqual({ grounded: 0, inspectionDue: 0, inRepair: 0, retired: 0 });
  });
});

describe('hoursSinceClose', () => {
  it('is undefined for a still-open record', () => {
    expect(hoursSinceClose(record(), Date.now())).toBeUndefined();
  });

  it('computes elapsed hours since closedAt', () => {
    const closedAt = new Date('2026-08-01T00:00:00Z').getTime();
    const now = closedAt + 5 * 3_600_000;
    expect(hoursSinceClose(record({ closedAt: new Date(closedAt).toISOString() }), now)).toBe(5);
  });

  it('never goes negative for a closedAt slightly ahead of "now" (clock skew)', () => {
    const closedAt = Date.now() + 60_000;
    expect(hoursSinceClose(record({ closedAt: new Date(closedAt).toISOString() }), Date.now())).toBe(0);
  });
});

describe('groundableAssets', () => {
  it('excludes MAINTENANCE and RETIRED assets', () => {
    const assets = [
      assetWithState('IN_STOCK', 'a-1'),
      assetWithState('ISSUED', 'a-2'),
      assetWithState('IN_FIELD', 'a-3'),
      assetWithState('MAINTENANCE', 'a-4'),
      assetWithState('RETIRED', 'a-5'),
    ];
    expect(groundableAssets(assets).map((a) => a.assetId)).toEqual(['a-1', 'a-2', 'a-3']);
  });

  it('includes an asset with no known inventoryState (never fetched) rather than hiding it', () => {
    const assets = [asset({ assetId: 'a-1' })];
    expect(groundableAssets(assets).map((a) => a.assetId)).toEqual(['a-1']);
  });
});

describe('MAINTENANCE_KIND_LABELS', () => {
  it('has exactly one label per MaintenanceKind, none blank', () => {
    const kinds: MaintenanceKind[] = ['GROUNDING', 'INSPECTION_DUE', 'REPAIR', 'NOTE'];
    for (const kind of kinds) {
      expect(MAINTENANCE_KIND_LABELS[kind].length).toBeGreaterThan(0);
    }
    expect(Object.keys(MAINTENANCE_KIND_LABELS).sort()).toEqual([...kinds].sort());
  });
});
