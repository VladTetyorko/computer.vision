import { describe, expect, it } from 'vitest';
import type { AssetDetails, AssetUsage, Device, TelemetrySample } from '../api/models';
import {
  ageSeconds,
  batterySeverity,
  deriveTrail,
  findOwningAsset,
  groupTelemetryByDevice,
  isStale,
  selectOpenUsage,
  shouldPoll,
  telemetryAgeSeverity,
  telemetryDevices,
  trackingIdChanged,
} from './telemetry-logic';

function usage(partial: Partial<AssetUsage>): AssetUsage {
  return { usageId: 'u-0', startedAt: '2026-07-22T00:00:00Z', sampleCount: 0, ...partial };
}

function device(partial: Partial<Device> = {}): Device {
  return {
    id: 'dev-0',
    name: 'device',
    capabilities: ['VIDEO'],
    protocol: 'sim',
    uri: 'sim://demo',
    options: {},
    state: 'ACTIVE',
    ...partial,
  };
}

function asset(partial: Partial<AssetDetails>): AssetDetails {
  return {
    assetId: 'a-0',
    displayName: 'asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    lifecycle: 'ACTIVE',
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
    return { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', ...partial };
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

describe('groupTelemetryByDevice', () => {
  function sample(partial: Partial<TelemetrySample> = {}): TelemetrySample {
    return { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', ...partial };
  }

  it('groups samples by deviceId, preserving order within each group', () => {
    const samples = [
      sample({ deviceId: 'gps-1', at: '2026-07-22T00:00:00Z' }),
      sample({ deviceId: 'gps-2', at: '2026-07-22T00:00:01Z' }),
      sample({ deviceId: 'gps-1', at: '2026-07-22T00:00:02Z' }),
    ];
    const grouped = groupTelemetryByDevice(samples);
    expect(grouped.size).toBe(2);
    expect(grouped.get('gps-1')?.map((s) => s.at)).toEqual([
      '2026-07-22T00:00:00Z',
      '2026-07-22T00:00:02Z',
    ]);
    expect(grouped.get('gps-2')?.map((s) => s.at)).toEqual(['2026-07-22T00:00:01Z']);
  });

  it('returns an empty map for no samples', () => {
    expect(groupTelemetryByDevice([]).size).toBe(0);
  });
});

describe('telemetryDevices', () => {
  it('keeps only TELEMETRY-capable devices', () => {
    const video = device({ id: 'd1', capabilities: ['VIDEO'] });
    const gps = device({ id: 'd2', capabilities: ['TELEMETRY'] });
    const both = device({ id: 'd3', capabilities: ['VIDEO', 'TELEMETRY'] });
    expect(telemetryDevices([video, gps, both]).map((d) => d.id)).toEqual(['d2', 'd3']);
  });

  it('returns an empty array when no device reports telemetry', () => {
    expect(telemetryDevices([device({ capabilities: ['VIDEO'] })])).toEqual([]);
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

describe('batterySeverity', () => {
  it('is unknown with no reading yet', () => {
    expect(batterySeverity(undefined)).toBe('unknown');
  });

  it('is ok above the low threshold', () => {
    expect(batterySeverity(46)).toBe('ok');
  });

  it('is low at and below the low threshold, above critical', () => {
    expect(batterySeverity(45)).toBe('low');
    expect(batterySeverity(21)).toBe('low');
  });

  it('is critical at and below the critical threshold', () => {
    expect(batterySeverity(20)).toBe('critical');
    expect(batterySeverity(0)).toBe('critical');
  });
});

describe('telemetryAgeSeverity', () => {
  it('is fresh at and below the amber threshold', () => {
    expect(telemetryAgeSeverity(0)).toBe('fresh');
    expect(telemetryAgeSeverity(5)).toBe('fresh');
  });

  it('is amber past the amber threshold, at and below the red one', () => {
    expect(telemetryAgeSeverity(5.01)).toBe('amber');
    expect(telemetryAgeSeverity(10)).toBe('amber');
  });

  it('is red past the red threshold', () => {
    expect(telemetryAgeSeverity(10.01)).toBe('red');
    expect(telemetryAgeSeverity(60)).toBe('red');
  });
});

describe('trackingIdChanged (docs/plans/done/REALTIME-PLAN.md Phase R-a item 2; moved here Phase R-c follow-up)', () => {
  it('is false when the same id is derived again — the re-entry guard\'s whole point', () => {
    expect(trackingIdChanged('dev-1', 'dev-1')).toBe(false);
  });

  it('is true the first time an id is ever derived (nothing tracked yet)', () => {
    expect(trackingIdChanged('dev-1', undefined)).toBe(true);
  });

  it('is true when the tracked id genuinely switches to a different device/stream', () => {
    expect(trackingIdChanged('dev-2', 'dev-1')).toBe(true);
  });

  it('is true when the id disappears (asset lost telemetry / stream stopped)', () => {
    expect(trackingIdChanged(undefined, 'dev-1')).toBe(true);
  });

  it('is false when nothing was ever tracked and still is not', () => {
    expect(trackingIdChanged(undefined, undefined)).toBe(false);
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
