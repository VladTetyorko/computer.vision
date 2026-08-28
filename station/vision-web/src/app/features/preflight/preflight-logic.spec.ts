import { describe, expect, it } from 'vitest';
import type { ReadinessRow } from '../../core/api/models';
import { applyVerdictFilter, blockerSummary, emptyFilterTitle, sortWorstFirst } from './preflight-logic';

function row(partial: Partial<ReadinessRow> = {}): ReadinessRow {
  return { assetId: 'a-1', displayName: 'Drone 1', verdict: 'GO', features: {}, ...partial };
}

describe('blockerSummary', () => {
  it('is { first: undefined, more: 0 } when every check is READY', () => {
    expect(blockerSummary({ battery: 'READY', 'map-position': 'READY' })).toEqual({ first: undefined, more: 0 });
  });

  it('is { first: undefined, more: 0 } for zero checks (unrecognised firmware) — as honest as an all-READY row', () => {
    expect(blockerSummary({})).toEqual({ first: undefined, more: 0 });
  });

  it('names the first non-READY check, in frozen feature-key order, with the rest counted', () => {
    // frozen order: map-position, ground-speed, battery, visual-geolocation
    const checks = {
      'visual-geolocation': 'MISSING' as const,
      battery: 'MISSING' as const,
      'map-position': 'DEGRADED' as const,
      'ground-speed': 'UNKNOWN' as const,
    };
    expect(blockerSummary(checks)).toEqual({ first: 'Map position', more: 3 });
  });

  it('reads more: 0 with exactly one non-READY check', () => {
    expect(blockerSummary({ battery: 'MISSING' })).toEqual({ first: 'Battery', more: 0 });
  });

  it('falls back to the raw key for an unrecognised feature', () => {
    expect(blockerSummary({ 'future-feature': 'MISSING' })).toEqual({ first: 'future-feature', more: 0 });
  });
});

describe('sortWorstFirst', () => {
  it('orders NO_GO, then UNKNOWN, then GO', () => {
    const rows = [
      row({ assetId: 'g', displayName: 'Go drone', verdict: 'GO' }),
      row({ assetId: 'n', displayName: 'No-go drone', verdict: 'NO_GO' }),
      row({ assetId: 'u', displayName: 'Unknown drone', verdict: 'UNKNOWN' }),
    ];
    expect(sortWorstFirst(rows).map((r) => r.assetId)).toEqual(['n', 'u', 'g']);
  });

  it('within UNKNOWN, orders by most failing checks first', () => {
    const rows = [
      row({ assetId: 'few', verdict: 'UNKNOWN', features: { battery: 'MISSING' } }),
      row({ assetId: 'many', verdict: 'UNKNOWN', features: { battery: 'MISSING', 'map-position': 'UNKNOWN', 'ground-speed': 'UNKNOWN' } }),
      row({ assetId: 'none', verdict: 'UNKNOWN', features: { battery: 'READY' } }),
    ];
    expect(sortWorstFirst(rows).map((r) => r.assetId)).toEqual(['many', 'few', 'none']);
  });

  it('breaks ties alphabetically by display name, case-insensitive — including equal UNKNOWN failure counts', () => {
    const rows = [
      row({ assetId: 'b', displayName: 'bravo', verdict: 'NO_GO' }),
      row({ assetId: 'a', displayName: 'Alpha', verdict: 'NO_GO' }),
    ];
    expect(sortWorstFirst(rows).map((r) => r.assetId)).toEqual(['a', 'b']);

    const unknowns = [
      row({ assetId: 'y', displayName: 'yankee', verdict: 'UNKNOWN', features: { battery: 'MISSING' } }),
      row({ assetId: 'x', displayName: 'Xray', verdict: 'UNKNOWN', features: { battery: 'MISSING' } }),
    ];
    expect(sortWorstFirst(unknowns).map((r) => r.assetId)).toEqual(['x', 'y']);
  });

  it('never mutates the input array', () => {
    const rows = [row({ assetId: 'a', verdict: 'GO' }), row({ assetId: 'b', verdict: 'NO_GO' })];
    const sorted = sortWorstFirst(rows);
    expect(sorted).not.toBe(rows);
    expect(rows.map((r) => r.assetId)).toEqual(['a', 'b']);
  });
});

describe('applyVerdictFilter', () => {
  const rows = [row({ assetId: 'g', verdict: 'GO' }), row({ assetId: 'n', verdict: 'NO_GO' }), row({ assetId: 'u', verdict: 'UNKNOWN' })];

  it('returns every row unchanged when verdict is undefined', () => {
    expect(applyVerdictFilter(rows, undefined)).toBe(rows);
  });

  it('filters to only the matching verdict', () => {
    expect(applyVerdictFilter(rows, 'NO_GO').map((r) => r.assetId)).toEqual(['n']);
    expect(applyVerdictFilter(rows, 'GO').map((r) => r.assetId)).toEqual(['g']);
    expect(applyVerdictFilter(rows, 'UNKNOWN').map((r) => r.assetId)).toEqual(['u']);
  });

  it('is an empty array, not undefined, when nothing matches', () => {
    expect(applyVerdictFilter([row({ verdict: 'GO' })], 'NO_GO')).toEqual([]);
  });
});

describe('emptyFilterTitle', () => {
  it('gives the plan\'s own NO_GO copy verbatim', () => {
    expect(emptyFilterTitle('NO_GO')).toBe('No No-go drones — every failing check is listed under Unknown.');
  });

  it('gives a parallel message for every other verdict', () => {
    expect(emptyFilterTitle('UNKNOWN')).toContain('No Unknown drones');
    expect(emptyFilterTitle('GO')).toContain('No Go drones');
  });
});
