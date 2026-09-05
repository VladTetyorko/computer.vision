import { describe, expect, it } from 'vitest';
import {
  canAdvanceFromFitOut,
  canAdvanceFromFitOutProve,
  combinedSysidCollision,
  effectiveProtocol,
  emptyFitOutRow,
  emptyFitOutRows,
  fitOutDeviceSpecs,
  fitOutRowToDeviceSpec,
  isRowFilled,
  needsProve,
  roleForDevice,
  roleStatus,
  usesLegacySimulationPath,
  type FitOutRowDraft,
  type FitOutRows,
} from './fit-out-logic';
import type { ActiveStream, Device } from '../api/models';

function row(partial: Partial<FitOutRowDraft> & Pick<FitOutRowDraft, 'role'>): FitOutRowDraft {
  return { ...emptyFitOutRow(partial.role), ...partial };
}

function rows(sense: Partial<FitOutRowDraft> = {}, sight: Partial<FitOutRowDraft> = {}): FitOutRows {
  return { sense: row({ role: 'sense', ...sense }), sight: row({ role: 'sight', ...sight }) };
}

describe('effectiveProtocol', () => {
  it('returns the select value for a known protocol', () => {
    expect(effectiveProtocol(row({ role: 'sight', protocolSelect: 'rtsp' }))).toBe('rtsp');
  });

  it('falls through to the free-text field for the custom sentinel', () => {
    expect(
      effectiveProtocol(row({ role: 'sight', protocolSelect: '__custom__', customProtocol: 'onvif' })),
    ).toBe('onvif');
  });
});

describe('isRowFilled', () => {
  it('is false for a none row', () => {
    expect(isRowFilled(row({ role: 'sight', value: 'none' }))).toBe(false);
  });

  it('is true for any simulate row, unconditionally', () => {
    expect(isRowFilled(row({ role: 'sense', value: 'simulate' }))).toBe(true);
  });

  it('requires a resolved protocol and uri for a find row', () => {
    expect(isRowFilled(row({ role: 'sight', value: 'find' }))).toBe(false);
    expect(
      isRowFilled(row({ role: 'sight', value: 'find', protocolSelect: 'rtsp', uri: 'rtsp://x' })),
    ).toBe(true);
    expect(isRowFilled(row({ role: 'sight', value: 'find', protocolSelect: 'rtsp', uri: '   ' }))).toBe(false);
  });
});

describe('canAdvanceFromFitOut', () => {
  it('is false when both rows are none', () => {
    expect(canAdvanceFromFitOut(emptyFitOutRows())).toBe(false);
  });

  it('is true once exactly one row is filled', () => {
    expect(canAdvanceFromFitOut(rows({ value: 'simulate' }))).toBe(true);
    expect(canAdvanceFromFitOut(rows({}, { value: 'simulate' }))).toBe(true);
  });

  it('is true when both rows are filled', () => {
    expect(canAdvanceFromFitOut(rows({ value: 'simulate' }, { value: 'simulate' }))).toBe(true);
  });

  it('is false for both-none without the equipment flag — silence is not a deliberate answer (D3)', () => {
    expect(canAdvanceFromFitOut(emptyFitOutRows(), false)).toBe(false);
  });

  it('is true for both-none once the fork\'s equipment tile is confirmed (D3)', () => {
    expect(canAdvanceFromFitOut(emptyFitOutRows(), true)).toBe(true);
  });

  it('a filled row still advances regardless of the equipment flag', () => {
    expect(canAdvanceFromFitOut(rows({ value: 'simulate' }), false)).toBe(true);
  });
});

describe('needsProve', () => {
  it('is false when no row is a find row', () => {
    expect(needsProve(rows({ value: 'simulate' }, { value: 'none' }))).toBe(false);
  });

  it('is true when any row is a find row, resolved or not', () => {
    expect(needsProve(rows({ value: 'find' }))).toBe(true);
  });
});

describe('canAdvanceFromFitOutProve', () => {
  it('requires every find row to have probed ok', () => {
    const both = rows({ value: 'find' }, { value: 'find' });
    expect(canAdvanceFromFitOutProve(both, { sense: true, sight: undefined })).toBe(false);
    expect(canAdvanceFromFitOutProve(both, { sense: true, sight: true })).toBe(true);
  });

  it('ignores simulate/none rows entirely', () => {
    const mixed = rows({ value: 'find' }, { value: 'simulate' });
    expect(canAdvanceFromFitOutProve(mixed, { sense: true, sight: undefined })).toBe(true);
  });

  it('is vacuously true when no row needs proving', () => {
    expect(canAdvanceFromFitOutProve(emptyFitOutRows(), { sense: undefined, sight: undefined })).toBe(true);
  });
});

describe('usesLegacySimulationPath', () => {
  it('is true for the classic video-only simulate (sense none, sight simulate)', () => {
    expect(usesLegacySimulationPath(rows({ value: 'none' }, { value: 'simulate' }))).toBe(true);
  });

  it('is true for "today\'s demo drone" — both rows simulated', () => {
    expect(usesLegacySimulationPath(rows({ value: 'simulate' }, { value: 'simulate' }))).toBe(true);
  });

  it('is false when sight is not simulate', () => {
    expect(usesLegacySimulationPath(rows({ value: 'simulate' }, { value: 'find' }))).toBe(false);
    expect(usesLegacySimulationPath(rows({}, { value: 'none' }))).toBe(false);
  });

  it('is false when sense is a real find, even if sight simulates — the multi-device path handles that mix', () => {
    expect(usesLegacySimulationPath(rows({ value: 'find' }, { value: 'simulate' }))).toBe(false);
  });
});

describe('fitOutRowToDeviceSpec', () => {
  it('is null for a none row', () => {
    expect(fitOutRowToDeviceSpec(row({ role: 'sight', value: 'none' }), 'Falcon-2')).toBeNull();
  });

  it('builds a TELEMETRY-capable simulated device for a simulated sense row', () => {
    expect(fitOutRowToDeviceSpec(row({ role: 'sense', value: 'simulate' }), 'Falcon-2')).toEqual({
      name: 'Falcon-2',
      protocol: 'sim',
      uri: 'sim://telemetry',
      capabilities: ['TELEMETRY'],
      origin: 'SIMULATED',
    });
  });

  it('builds a VIDEO-capable simulated device for a simulated sight row', () => {
    expect(fitOutRowToDeviceSpec(row({ role: 'sight', value: 'simulate' }), 'Falcon-2')).toEqual({
      name: 'Falcon-2',
      protocol: 'sim',
      uri: 'sim://demo',
      capabilities: ['VIDEO'],
      origin: 'SIMULATED',
    });
  });

  it('builds a plain device spec for a resolved find row, options included when present', () => {
    const found = row({
      role: 'sight',
      value: 'find',
      protocolSelect: 'rtsp',
      uri: '  rtsp://192.168.1.50:554/stream  ',
      options: [{ key: 'rtsp_transport', value: 'tcp' }],
    });
    expect(fitOutRowToDeviceSpec(found, 'Falcon-2')).toEqual({
      name: 'Falcon-2',
      protocol: 'rtsp',
      uri: 'rtsp://192.168.1.50:554/stream',
      options: { rtsp_transport: 'tcp' },
    });
  });

  it('omits options entirely when every key is blank', () => {
    const found = row({
      role: 'sight',
      value: 'find',
      protocolSelect: 'rtsp',
      uri: 'rtsp://x',
      options: [{ key: '  ', value: 'tcp' }],
    });
    expect(fitOutRowToDeviceSpec(found, 'Falcon-2')).not.toHaveProperty('options');
  });

  it('is null for an unresolved find row (no protocol/uri yet)', () => {
    expect(fitOutRowToDeviceSpec(row({ role: 'sense', value: 'find' }), 'Falcon-2')).toBeNull();
  });
});

describe('fitOutDeviceSpecs', () => {
  it('names the single device after the asset when only one row is filled', () => {
    const specs = fitOutDeviceSpecs(rows({ value: 'none' }, { value: 'simulate' }), 'Falcon-2');
    expect(specs).toEqual([
      { name: 'Falcon-2', protocol: 'sim', uri: 'sim://demo', capabilities: ['VIDEO'], origin: 'SIMULATED' },
    ]);
  });

  it('suffixes each device with its role once both rows are filled — a camera + FC in one visit', () => {
    const specs = fitOutDeviceSpecs(rows({ value: 'simulate' }, { value: 'simulate' }), 'Falcon-2');
    expect(specs.map((s) => s.name)).toEqual(['Falcon-2 — Sense', 'Falcon-2 — Sight']);
  });

  it('is empty when nothing is filled', () => {
    expect(fitOutDeviceSpecs(emptyFitOutRows(), 'Falcon-2')).toEqual([]);
  });
});

describe('roleForDevice', () => {
  it('is sense for a TELEMETRY-capable device', () => {
    expect(roleForDevice({ capabilities: ['TELEMETRY'] })).toBe('sense');
  });

  it('is sight for every other capability set, video included', () => {
    expect(roleForDevice({ capabilities: ['VIDEO'] })).toBe('sight');
    expect(roleForDevice({ capabilities: [] })).toBe('sight');
  });
});

describe('combinedSysidCollision', () => {
  it('prefers the sense row', () => {
    expect(combinedSysidCollision({ sense: 7, sight: 9 })).toBe(7);
  });

  it('falls back to sight when sense has none', () => {
    expect(combinedSysidCollision({ sense: null, sight: 9 })).toBe(9);
  });

  it('is null when neither collided', () => {
    expect(combinedSysidCollision({ sense: null, sight: null })).toBeNull();
  });
});

describe('roleStatus', () => {
  function device(partial: Partial<Device> & Pick<Device, 'id'>): Device {
    return {
      name: 'Device',
      capabilities: ['VIDEO'],
      protocol: 'rtsp',
      uri: 'rtsp://x',
      options: {},
      state: 'ACTIVE',
      ...partial,
    } as Device;
  }

  function stream(partial: Partial<ActiveStream> & Pick<ActiveStream, 'deviceId'>): Pick<ActiveStream, 'deviceId' | 'state'> {
    return { state: 'LIVE', ...partial };
  }

  const sightDevice = device({ id: 'cam-1', capabilities: ['VIDEO'] });
  const senseDevice = device({ id: 'fc-1', capabilities: ['TELEMETRY'] });

  it('is not-fitted when no device of that role exists on the asset', () => {
    expect(roleStatus('sight', [], [], undefined)).toBe('not-fitted');
    expect(roleStatus('sense', [], [], undefined)).toBe('not-fitted');
  });

  it('Sight: fitted with no matching active stream is stopped — history-agnostic (D-honesty)', () => {
    expect(roleStatus('sight', [sightDevice], [], undefined)).toBe('stopped');
  });

  it('Sight: fitted with a stream not in the list is still stopped even alongside other streams', () => {
    expect(roleStatus('sight', [sightDevice], [stream({ deviceId: 'other-device' })], undefined)).toBe('stopped');
  });

  it('Sight: LIVE/STARTING/UNOBSERVED all read as live — cannot judge, do not invent a fault', () => {
    expect(roleStatus('sight', [sightDevice], [stream({ deviceId: 'cam-1', state: 'LIVE' })], undefined)).toBe('live');
    expect(roleStatus('sight', [sightDevice], [stream({ deviceId: 'cam-1', state: 'STARTING' })], undefined)).toBe(
      'live',
    );
    expect(
      roleStatus('sight', [sightDevice], [stream({ deviceId: 'cam-1', state: 'UNOBSERVED' })], undefined),
    ).toBe('live');
  });

  it('Sight: STALLED/RECONNECTING both read as stalled', () => {
    expect(roleStatus('sight', [sightDevice], [stream({ deviceId: 'cam-1', state: 'STALLED' })], undefined)).toBe(
      'stalled',
    );
    expect(
      roleStatus('sight', [sightDevice], [stream({ deviceId: 'cam-1', state: 'RECONNECTING' })], undefined),
    ).toBe('stalled');
  });

  it('Sense: fitted but never heard from is never-seen, not stopped — telemetry has no such state', () => {
    expect(roleStatus('sense', [senseDevice], [], undefined)).toBe('never-seen');
  });

  it('Sense: live/aging freshness both read as live (coarser vocabulary)', () => {
    expect(roleStatus('sense', [senseDevice], [], 1_000)).toBe('live');
    expect(roleStatus('sense', [senseDevice], [], 7_000)).toBe('live');
  });

  it('Sense: stale freshness reads as stale', () => {
    expect(roleStatus('sense', [senseDevice], [], 120_000)).toBe('stale');
  });
});
