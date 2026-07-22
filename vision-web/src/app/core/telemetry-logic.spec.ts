import { describe, expect, it } from 'vitest';
import type { AssetDetails, AssetUsage, TelemetrySample } from './api/models';
import {
  ageSeconds,
  deriveTrail,
  findOwningAsset,
  isStale,
  selectOpenUsage,
  shouldPoll,
} from './telemetry-logic';

function usage(partial: Partial<AssetUsage>): AssetUsage {
  return { usageId: 'u-0', startedAt: '2026-07-22T00:00:00Z', sampleCount: 0, ...partial };
}

function asset(partial: Partial<AssetDetails>): AssetDetails {
  return {
    assetId: 'a-0',
    displayName: 'asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    state: 'ACTIVE',
    attributes: {},
    devices: [],
    recentUsages: [],
    ...partial,
  };
}

describe('findOwningAsset', () => {
  it('finds the asset whose devices include the given id', () => {
    const target = asset({ assetId: 'a-1', devices: [{ id: 'dev-1' } as never] });
    const other = asset({ assetId: 'a-2', devices: [{ id: 'dev-2' } as never] });
    expect(findOwningAsset([other, target], 'dev-1')).toBe(target);
  });

  it('returns undefined when no asset owns the device', () => {
    const other = asset({ assetId: 'a-2', devices: [{ id: 'dev-2' } as never] });
    expect(findOwningAsset([other], 'dev-unknown')).toBeUndefined();
  });
});

describe('selectOpenUsage', () => {
  it('picks the entry with no endedAt', () => {
    const closed = usage({ usageId: 'u-closed', endedAt: '2026-07-22T00:05:00Z' });
    const open = usage({ usageId: 'u-open' });
    expect(selectOpenUsage([closed, open])).toBe(open);
  });

  it('returns undefined when every usage has ended', () => {
    const closed = usage({ usageId: 'u-closed', endedAt: '2026-07-22T00:05:00Z' });
    expect(selectOpenUsage([closed])).toBeUndefined();
  });

  it('returns undefined for an empty history', () => {
    expect(selectOpenUsage([])).toBeUndefined();
  });
});

describe('deriveTrail', () => {
  function sample(partial: Partial<TelemetrySample>): TelemetrySample {
    return { at: '2026-07-22T00:00:00Z', ...partial };
  }

  it('keeps chronological order and maps lat/lon/altitude', () => {
    const samples: TelemetrySample[] = [
      sample({ at: '2026-07-22T00:00:00Z', latitude: 1, longitude: 2, altitudeMeters: 10 }),
      sample({ at: '2026-07-22T00:00:02Z', latitude: 3, longitude: 4 }),
    ];
    expect(deriveTrail(samples)).toEqual([
      { latitude: 1, longitude: 2, altitudeMeters: 10 },
      { latitude: 3, longitude: 4, altitudeMeters: undefined },
    ]);
  });

  it('skips samples without a position instead of inserting a gap', () => {
    const samples: TelemetrySample[] = [
      sample({ latitude: 1, longitude: 2 }),
      sample({ batteryPercent: 80 }), // no lat/lon this tick
      sample({ latitude: 3, longitude: 4 }),
    ];
    expect(deriveTrail(samples)).toEqual([
      { latitude: 1, longitude: 2, altitudeMeters: undefined },
      { latitude: 3, longitude: 4, altitudeMeters: undefined },
    ]);
  });

  it('returns an empty trail when no sample carries a position', () => {
    expect(deriveTrail([sample({ batteryPercent: 50 })])).toEqual([]);
  });
});

describe('ageSeconds / isStale', () => {
  it('is undefined with no sample yet', () => {
    expect(ageSeconds(undefined, Date.now())).toBeUndefined();
    expect(isStale(undefined)).toBe(false);
  });

  it('computes elapsed seconds since the sample', () => {
    const at = '2026-07-22T00:00:00.000Z';
    const now = Date.parse(at) + 3_000;
    expect(ageSeconds(at, now)).toBe(3);
  });

  it('never goes negative for a clock-skewed future sample', () => {
    const at = '2026-07-22T00:00:10.000Z';
    const now = Date.parse(at) - 5_000;
    expect(ageSeconds(at, now)).toBe(0);
  });

  it('is stale only past the threshold', () => {
    expect(isStale(5)).toBe(false);
    expect(isStale(5.01)).toBe(true);
    expect(isStale(0)).toBe(false);
  });
});

describe('shouldPoll', () => {
  it('polls while the tab is visible', () => {
    expect(shouldPoll(false)).toBe(true);
  });

  it('pauses while the tab is hidden', () => {
    expect(shouldPoll(true)).toBe(false);
  });
});
