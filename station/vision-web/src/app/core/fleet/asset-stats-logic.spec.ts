import { describe, expect, it } from 'vitest';
import type { AssetStats, AssetUsage } from '../api/models';
import { flightBars, formatFlightTime, kpiTiles, usageDurationSeconds } from './asset-stats-logic';

const NOW = Date.parse('2026-07-30T12:00:00Z');

function usage(partial: Partial<AssetUsage> & { usageId: string; startedAt: string }): AssetUsage {
  return { sampleCount: 0, ...partial };
}

function stats(partial: Partial<AssetStats> = {}): AssetStats {
  return { totalFlightSeconds: 0, flightCount: 0, flightInProgress: false, ...partial };
}

describe('usageDurationSeconds', () => {
  it('measures a closed usage from start to end', () => {
    const u = usage({ usageId: 'u1', startedAt: '2026-07-30T11:00:00Z', endedAt: '2026-07-30T11:05:00Z' });
    expect(usageDurationSeconds(u, NOW)).toBe(300);
  });

  it('measures an open usage up to now', () => {
    const u = usage({ usageId: 'u1', startedAt: '2026-07-30T11:59:00Z' });
    expect(usageDurationSeconds(u, NOW)).toBe(60);
  });

  it('never goes negative', () => {
    const u = usage({ usageId: 'u1', startedAt: '2026-07-30T12:05:00Z', endedAt: '2026-07-30T12:00:00Z' });
    expect(usageDurationSeconds(u, NOW)).toBe(0);
  });
});

describe('formatFlightTime', () => {
  it('renders "—" for null (honestly unknown)', () => {
    expect(formatFlightTime(null)).toBe('—');
  });

  it('renders an honest zero as "0m", not "—"', () => {
    expect(formatFlightTime(0)).toBe('0m');
  });

  it('renders minutes-only under an hour', () => {
    expect(formatFlightTime(125)).toBe('2m');
  });

  it('renders hours and minutes, zero-padded', () => {
    expect(formatFlightTime(45_240)).toBe('12h 34m'); // 12h34m00s
  });

  it('renders a whole hour as "1h 00m"', () => {
    expect(formatFlightTime(3600)).toBe('1h 00m');
  });
});

describe('kpiTiles', () => {
  it('degrades every tile to "—" when stats failed to load (undefined), including Flights', () => {
    const tiles = kpiTiles(undefined, NOW);
    expect(tiles.map((t) => t.value)).toEqual(['—', '—', '—', '—', '—']);
    expect(tiles.map((t) => t.label)).toEqual([
      'Total flight time',
      'Flights',
      'Last flown',
      'Avg flight',
      'Battery',
    ]);
  });

  it('renders "—" for total/last-flown/avg/battery when the asset has never flown', () => {
    const tiles = kpiTiles(stats({ totalFlightSeconds: 0, flightCount: 0 }), NOW);
    expect(tiles).toEqual([
      { label: 'Total flight time', value: '—', sub: undefined, live: false },
      { label: 'Flights', value: '0' },
      { label: 'Last flown', value: '—' },
      { label: 'Avg flight', value: '—' },
      { label: 'Battery', value: '—' },
    ]);
  });

  it('maps a fully-populated stats response', () => {
    const tiles = kpiTiles(
      stats({
        totalFlightSeconds: 45_240,
        flightCount: 7,
        lastFlownAt: '2026-07-30T11:50:00Z',
        avgFlightSeconds: 600,
        lastKnownBatteryPercent: 76,
        flightInProgress: false,
      }),
      NOW,
    );
    expect(tiles).toEqual([
      { label: 'Total flight time', value: '12h 34m', sub: undefined, live: false },
      { label: 'Flights', value: '7' },
      { label: 'Last flown', value: '10m ago' },
      { label: 'Avg flight', value: '10m' },
      { label: 'Battery', value: '76%' },
    ]);
  });

  it('marks the Total flight time tile live, with a "Flying now" sub, when a flight is in progress', () => {
    const tiles = kpiTiles(stats({ totalFlightSeconds: 120, flightCount: 1, flightInProgress: true }), NOW);
    expect(tiles[0]).toEqual({ label: 'Total flight time', value: '2m', sub: 'Flying now', live: true });
  });

  it('renders an honest "0m" total when flights were fetched but summed to zero seconds', () => {
    const tiles = kpiTiles(stats({ totalFlightSeconds: 0, flightCount: 3 }), NOW);
    expect(tiles[0].value).toBe('0m');
  });
});

describe('flightBars', () => {
  it('returns an empty array for no usages', () => {
    expect(flightBars([], NOW)).toEqual([]);
  });

  it('builds one bar for a single open usage, counted to now', () => {
    const bars = flightBars(
      [usage({ usageId: 'u1', startedAt: '2026-07-30T11:59:00Z' })],
      NOW,
    );
    expect(bars).toEqual([
      // The only bar in the set is also its own max, so it scales to 100%, not the floor.
      { usageId: 'u1', startedAt: '2026-07-30T11:59:00Z', durationSeconds: 60, open: true, heightPercent: 100 },
    ]);
  });

  it('reverses newest-first input to oldest-first (chronological) order', () => {
    const bars = flightBars(
      [
        usage({ usageId: 'newer', startedAt: '2026-07-30T10:00:00Z', endedAt: '2026-07-30T10:10:00Z' }),
        usage({ usageId: 'older', startedAt: '2026-07-30T08:00:00Z', endedAt: '2026-07-30T08:05:00Z' }),
      ],
      NOW,
    );
    expect(bars.map((b) => b.usageId)).toEqual(['older', 'newer']);
  });

  it('scales bar heights against the set\'s own max duration, flooring the shortest at the minimum', () => {
    const bars = flightBars(
      [
        usage({ usageId: 'long', startedAt: '2026-07-30T09:00:00Z', endedAt: '2026-07-30T10:00:00Z' }), // 3600s
        usage({ usageId: 'short', startedAt: '2026-07-30T08:00:00Z', endedAt: '2026-07-30T08:00:36Z' }), // 36s = 1% of max
      ],
      NOW,
    );
    const long = bars.find((b) => b.usageId === 'long')!;
    const short = bars.find((b) => b.usageId === 'short')!;
    expect(long.heightPercent).toBe(100);
    expect(short.heightPercent).toBe(6); // floored at MIN_BAR_HEIGHT_PERCENT, not 1%
  });

  it('floors every bar at the minimum when every duration is zero', () => {
    const bars = flightBars(
      [usage({ usageId: 'u1', startedAt: '2026-07-30T11:00:00Z', endedAt: '2026-07-30T11:00:00Z' })],
      NOW,
    );
    expect(bars[0].heightPercent).toBe(6);
    expect(bars[0].durationSeconds).toBe(0);
  });
});
