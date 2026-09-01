import { describe, expect, it } from 'vitest';
import type { AssetSummary, UsageSummary } from '../../core/api/models';
import {
  filterUsagesByTimeRange,
  formatUsageDuration,
  OPEN_USAGE_STALE_AFTER_SECONDS,
  sortUsagesForDisplay,
  usageAssetOptions,
  usageStatus,
} from './replay-library-logic';

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

describe('usageStatus (OPERATOR-UX-5-PLAN.md finding U1)', () => {
  const nowMs = Date.parse('2026-08-29T12:00:00.000Z');

  it('a closed flight reads its own formatUsageDuration text, plain (non-chip) register', () => {
    const closed = usage({ endedAt: '2026-08-29T11:30:00.000Z', durationSeconds: 1800 });
    expect(usageStatus(closed, nowMs)).toEqual({
      kind: 'closed',
      label: '30m 00s',
      tone: 'neutral',
      live: false,
      isReplayLink: true,
    });
  });

  it('sampleCount === 0 always reads "No samples" and is not a replay link, even for a closed flight', () => {
    const empty = usage({ endedAt: '2026-08-29T11:30:00.000Z', durationSeconds: 1800, sampleCount: 0 });
    expect(usageStatus(empty, nowMs)).toEqual({ kind: 'no-samples', label: 'No samples', tone: 'neutral', live: false, isReplayLink: false });
  });

  it('sampleCount === 0 wins even for a still-open usage — not "Flying now"', () => {
    const openEmpty = usage({ startedAt: '2026-08-29T11:59:50.000Z', endedAt: undefined, durationSeconds: undefined, sampleCount: 0 });
    expect(usageStatus(openEmpty, nowMs).kind).toBe('no-samples');
  });

  it('an open usage whose last activity is fresh (startedAt just now, durationSeconds absent) reads "Flying now", live, ok tone', () => {
    const open = usage({ startedAt: '2026-08-29T11:59:50.000Z', endedAt: undefined, durationSeconds: undefined });
    expect(usageStatus(open, nowMs)).toEqual({ kind: 'flying', label: 'Flying now', tone: 'ok', live: true, isReplayLink: true });
  });

  it('an open usage exactly at the stale boundary is still "Flying now" (strictly greater-than triggers stale)', () => {
    const boundary = usage({
      startedAt: new Date(nowMs - OPEN_USAGE_STALE_AFTER_SECONDS * 1000).toISOString(),
      endedAt: undefined,
      durationSeconds: undefined,
    });
    expect(usageStatus(boundary, nowMs).kind).toBe('flying');
  });

  it("an open usage last active 4d 2h ago reads a neutral 'Open · last sample …' chip, never Flying now", () => {
    const staleOpen = usage({
      startedAt: new Date(nowMs - 353_099 * 1000).toISOString(), // the H1 rover's own exact age (telemetry-logic.spec.ts)
      endedAt: undefined,
      durationSeconds: undefined,
    });
    expect(usageStatus(staleOpen, nowMs)).toEqual({
      kind: 'open-stale',
      label: 'Open · last sample 4d 2h ago',
      tone: 'neutral',
      live: false,
      isReplayLink: true,
    });
  });

  it('an open usage with a durationSeconds (a live elapsed-duration field, if the wire ever adds one) uses startedAt + durationSeconds, not startedAt alone', () => {
    // started 20 minutes ago, durationSeconds says 60s of it was real activity — last activity was
    // 19 minutes ago (startedAt + 60s), not 20 minutes ago (startedAt alone).
    const started20mAgo = new Date(nowMs - 20 * 60 * 1000).toISOString();
    const open = usage({ startedAt: started20mAgo, endedAt: undefined, durationSeconds: 60 });
    const status = usageStatus(open, nowMs);
    expect(status.kind).toBe('open-stale');
    expect(status.label).toBe('Open · last sample 19m ago');
  });
});

describe('sortUsagesForDisplay (OPERATOR-UX-5-PLAN.md finding U1)', () => {
  it('demotes every sampleCount === 0 row to the end, stably preserving order otherwise', () => {
    const a = usage({ usageId: 'a', sampleCount: 10 });
    const empty1 = usage({ usageId: 'empty-1', sampleCount: 0 });
    const b = usage({ usageId: 'b', sampleCount: 5 });
    const empty2 = usage({ usageId: 'empty-2', sampleCount: 0 });
    expect(sortUsagesForDisplay([a, empty1, b, empty2]).map((u) => u.usageId)).toEqual(['a', 'b', 'empty-1', 'empty-2']);
  });

  it('is a no-op when nothing has zero samples', () => {
    const usages = [usage({ usageId: 'a' }), usage({ usageId: 'b' })];
    expect(sortUsagesForDisplay(usages).map((u) => u.usageId)).toEqual(['a', 'b']);
  });

  it('returns an empty array unchanged', () => {
    expect(sortUsagesForDisplay([])).toEqual([]);
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
