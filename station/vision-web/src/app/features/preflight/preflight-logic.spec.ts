import { describe, expect, it } from 'vitest';
import type { AssetDetails, Capability, Device, ReadinessRow } from '../../core/api/models';
import {
  applyVerdictFilter,
  blockerSummary,
  boardCounts,
  buildBoardGroups,
  buildFleetVehicleRows,
  emptyFilterTitle,
  hasTelemetryCapableDevice,
  isNeverProbedRow,
  sortWorstFirst,
  vehicleStatusOverride,
  type FleetVehicleRow,
} from './preflight-logic';

function row(partial: Partial<ReadinessRow> = {}): ReadinessRow {
  return { assetId: 'a-1', displayName: 'Drone 1', verdict: 'GO', features: {}, ...partial };
}

function vehicleRow(partial: Partial<FleetVehicleRow> = {}): FleetVehicleRow {
  return { ...row(), category: 'drones', status: 'OFFLINE', ...partial };
}

function device(capabilities: readonly Capability[]): Pick<Device, 'capabilities'> {
  return { capabilities };
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

  it('(OPERATOR-UX-7 P1) excludes never-probed rows from the UNKNOWN filter', () => {
    const mixed = [
      row({ assetId: 'never', verdict: 'UNKNOWN', features: { battery: 'UNKNOWN', 'map-position': 'UNKNOWN' } }),
      row({ assetId: 'genuinely-unknown', verdict: 'UNKNOWN', features: {} }),
    ];
    expect(applyVerdictFilter(mixed, 'UNKNOWN').map((r) => r.assetId)).toEqual(['genuinely-unknown']);
  });

  it('(OPERATOR-UX-7 P1) NOT_PROBED matches only never-probed rows', () => {
    const mixed = [
      row({ assetId: 'never', verdict: 'UNKNOWN', features: { battery: 'UNKNOWN' } }),
      row({ assetId: 'genuinely-unknown', verdict: 'UNKNOWN', features: {} }),
      row({ assetId: 'go', verdict: 'GO' }),
    ];
    expect(applyVerdictFilter(mixed, 'NOT_PROBED').map((r) => r.assetId)).toEqual(['never']);
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

  it('(OPERATOR-UX-7 P1) gives a parallel message for NOT_PROBED', () => {
    expect(emptyFilterTitle('NOT_PROBED')).toContain('No vehicles waiting on a first probe');
  });
});

describe('isNeverProbedRow (OPERATOR-UX-7 P1)', () => {
  it('is true when the verdict is UNKNOWN and every evaluated feature is UNKNOWN', () => {
    expect(isNeverProbedRow(row({ verdict: 'UNKNOWN', features: { battery: 'UNKNOWN', 'map-position': 'UNKNOWN' } }))).toBe(true);
  });

  it('is false for a GO/NO_GO row regardless of its features', () => {
    expect(isNeverProbedRow(row({ verdict: 'GO', features: { battery: 'UNKNOWN' } }))).toBe(false);
    expect(isNeverProbedRow(row({ verdict: 'NO_GO', features: { battery: 'MISSING' } }))).toBe(false);
  });

  it('is false for an UNKNOWN row that has at least one non-UNKNOWN feature (a genuinely probed-but-undecided row)', () => {
    expect(isNeverProbedRow(row({ verdict: 'UNKNOWN', features: { battery: 'UNKNOWN', 'map-position': 'DEGRADED' } }))).toBe(false);
  });

  it('is false, not vacuously true, for an UNKNOWN row with zero evaluated features', () => {
    expect(isNeverProbedRow(row({ verdict: 'UNKNOWN', features: {} }))).toBe(false);
  });
});

describe('hasTelemetryCapableDevice (OPERATOR-UX-7 P1)', () => {
  it('is true when at least one device carries TELEMETRY', () => {
    expect(hasTelemetryCapableDevice([device(['VIDEO']), device(['TELEMETRY'])])).toBe(true);
  });

  it('is false when no device carries TELEMETRY', () => {
    expect(hasTelemetryCapableDevice([device(['VIDEO']), device(['PTZ'])])).toBe(false);
  });

  it('is false for an empty device list', () => {
    expect(hasTelemetryCapableDevice([])).toBe(false);
  });
});

describe('buildFleetVehicleRows (OPERATOR-UX-7 P1)', () => {
  it('enriches a row from its matching AssetDetails', () => {
    const details = new Map<string, Pick<AssetDetails, 'category' | 'status' | 'lastUsedAt'> & { devices: readonly Pick<Device, 'capabilities'>[] }>([
      ['a-1', { category: 'simulated', status: 'STREAMING', lastUsedAt: '2026-08-29T00:00:00Z', devices: [device(['TELEMETRY'])] }],
    ]);
    const [built] = buildFleetVehicleRows([row({ assetId: 'a-1' })], details);
    expect(built.category).toBe('simulated');
    expect(built.status).toBe('STREAMING');
    expect(built.lastUsedAt).toBe('2026-08-29T00:00:00Z');
    expect(built.hasTelemetryDevice).toBe(true);
  });

  it('degrades a row with no resolved AssetDetails to safe, non-fabricated defaults', () => {
    const [built] = buildFleetVehicleRows([row({ assetId: 'missing' })], new Map());
    expect(built.category).toBe('');
    expect(built.status).toBe('OFFLINE');
    expect(built.lastUsedAt).toBeUndefined();
    expect(built.hasTelemetryDevice).toBeUndefined();
  });
});

describe('vehicleStatusOverride (OPERATOR-UX-7 P1)', () => {
  it('is undefined for a row that is not never-probed, regardless of telemetry-device data', () => {
    expect(vehicleStatusOverride(vehicleRow({ verdict: 'GO', features: {} }))).toBeUndefined();
    expect(vehicleStatusOverride(vehicleRow({ verdict: 'NO_GO', features: { battery: 'MISSING' } }))).toBeUndefined();
  });

  it('reads "Not probed yet" for a never-probed row when the telemetry-device fact is unknown or true', () => {
    const neverProbed = { verdict: 'UNKNOWN' as const, features: { battery: 'UNKNOWN' as const } };
    expect(vehicleStatusOverride(vehicleRow({ ...neverProbed, hasTelemetryDevice: undefined }))).toBe('Not probed yet');
    expect(vehicleStatusOverride(vehicleRow({ ...neverProbed, hasTelemetryDevice: true }))).toBe('Not probed yet');
  });

  it('reads "No telemetry device" for a never-probed row only once positive evidence says so', () => {
    const neverProbed = { verdict: 'UNKNOWN' as const, features: { battery: 'UNKNOWN' as const } };
    expect(vehicleStatusOverride(vehicleRow({ ...neverProbed, hasTelemetryDevice: false }))).toBe('No telemetry device');
  });
});

describe('buildBoardGroups (OPERATOR-UX-7 P1)', () => {
  it('splits real vehicles from simulated ones', () => {
    const groups = buildBoardGroups([
      vehicleRow({ assetId: 'r', category: 'drones' }),
      vehicleRow({ assetId: 's', category: 'simulated' }),
    ]);
    expect(groups.yours.map((r) => r.assetId)).toEqual(['r']);
    expect(groups.simulated.map((r) => r.assetId)).toEqual(['s']);
  });

  it('sorts each group worst-verdict-first rather than streaming-first', () => {
    const groups = buildBoardGroups([
      vehicleRow({ assetId: 'go-streaming', category: 'drones', status: 'STREAMING', verdict: 'GO' }),
      vehicleRow({ assetId: 'nogo-offline', category: 'drones', status: 'OFFLINE', verdict: 'NO_GO' }),
    ]);
    // A grounded NO_GO row must outrank a currently-streaming GO row on this severity-triage board —
    // the opposite of `core/fleet/triage-logic.ts#groupAndSort`'s own streaming-first order.
    expect(groups.yours.map((r) => r.assetId)).toEqual(['nogo-offline', 'go-streaming']);
  });
});

describe('boardCounts (OPERATOR-UX-7 P1)', () => {
  it('splits GO/NO_GO/UNKNOWN/NOT_PROBED into four independent counts', () => {
    const rows = [
      row({ assetId: 'g', verdict: 'GO' }),
      row({ assetId: 'n', verdict: 'NO_GO' }),
      row({ assetId: 'never', verdict: 'UNKNOWN', features: { battery: 'UNKNOWN' } }),
      row({ assetId: 'unknown', verdict: 'UNKNOWN', features: {} }),
    ];
    expect(boardCounts(rows)).toEqual({ go: 1, noGo: 1, unknown: 1, notProbed: 1 });
  });
});
