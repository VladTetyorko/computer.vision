import { describe, expect, it } from 'vitest';
import type { AssetSummary, UsageSummary } from '../../core/api/models';
import { filterUsagesByTimeRange, formatUsageDuration, usageAssetOptions } from './replay-library-logic';

function usage(partial: Partial<UsageSummary> = {}): UsageSummary {
  return {
    usageId: 'u-0',
    assetId: 'a-0',
    assetName: 'Falcon-2',
    startedAt: '2026-08-04T10:00:00.000Z',
    endedAt: '2026-08-04T10:30:00.000Z',
    durationSeconds: 1800,
    sampleCount: 120,
    ...partial,
  };
}

function asset(partial: Partial<AssetSummary> = {}): AssetSummary {
  return {
    assetId: 'a-0',
    displayName: 'Falcon-2',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

describe('formatUsageDuration', () => {
  it('renders "Flying now" for a still-open usage (durationSeconds absent)', () => {
    expect(formatUsageDuration(undefined)).toBe('Flying now');
  });

  it('never renders a negative or blank duration for an open flight', () => {
    const label = formatUsageDuration(undefined);
    expect(label).not.toMatch(/-/);
    expect(label.length).toBeGreaterThan(0);
  });

  it('formats a closed flight with stream-info-logic\'s own formatDuration rendering', () => {
    expect(formatUsageDuration(2642)).toBe('44m 02s');
  });

  it('formats an hours-long flight', () => {
    expect(formatUsageDuration(3723)).toBe('1h 02m');
  });

  it('formats a sub-minute flight', () => {
    expect(formatUsageDuration(9)).toBe('9s');
  });
});

describe('filterUsagesByTimeRange', () => {
  const nowMs = Date.parse('2026-08-04T12:00:00.000Z');

  it("'all' is a no-op — every usage passes through unchanged", () => {
    const usages = [usage({ startedAt: '2020-01-01T00:00:00.000Z' }), usage({ usageId: 'u-1' })];
    expect(filterUsagesByTimeRange(usages, 'all', nowMs)).toEqual(usages);
  });

  it("'today' keeps only usages started within the last 24h", () => {
    const recent = usage({ usageId: 'recent', startedAt: '2026-08-04T11:00:00.000Z' });
    const stale = usage({ usageId: 'stale', startedAt: '2026-08-02T11:00:00.000Z' });
    expect(filterUsagesByTimeRange([recent, stale], 'today', nowMs)).toEqual([recent]);
  });

  it("'7d' keeps a usage started exactly 6 days ago and drops one started 8 days ago", () => {
    const withinWeek = usage({ usageId: 'within', startedAt: '2026-07-29T12:00:00.000Z' });
    const beforeWeek = usage({ usageId: 'before', startedAt: '2026-07-27T12:00:00.000Z' });
    expect(filterUsagesByTimeRange([withinWeek, beforeWeek], '7d', nowMs)).toEqual([withinWeek]);
  });

  it("'30d' keeps a usage started exactly 30 days ago (inclusive boundary)", () => {
    const boundary = usage({ usageId: 'boundary', startedAt: '2026-07-05T12:00:00.000Z' });
    expect(filterUsagesByTimeRange([boundary], '30d', nowMs)).toEqual([boundary]);
  });

  it('returns an empty array unchanged', () => {
    expect(filterUsagesByTimeRange([], '7d', nowMs)).toEqual([]);
  });

  it('keeps a still-open usage whose startedAt falls in range, endedAt never enters into it', () => {
    const open = usage({ usageId: 'open', startedAt: '2026-08-04T11:00:00.000Z', endedAt: undefined, durationSeconds: undefined });
    expect(filterUsagesByTimeRange([open], 'today', nowMs)).toEqual([open]);
  });
});

describe('usageAssetOptions', () => {
  it('maps assetId/displayName and sorts alphabetically', () => {
    const options = usageAssetOptions([asset({ assetId: 'a-2', displayName: 'Zeta' }), asset({ assetId: 'a-1', displayName: 'Alpha' })]);
    expect(options).toEqual([
      { assetId: 'a-1', displayName: 'Alpha' },
      { assetId: 'a-2', displayName: 'Zeta' },
    ]);
  });

  it('returns an empty array for no assets', () => {
    expect(usageAssetOptions([])).toEqual([]);
  });
});
