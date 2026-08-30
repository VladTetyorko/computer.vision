import { describe, expect, it } from 'vitest';
import type { MaintenanceRecord, TelemetrySample } from '../../core/api/models';
import {
  attributeRowsToRecord,
  attributesToRows,
  buildIdentityEdit,
  formatSinceService,
  freshestSample,
  groupTelemetryByDevice,
  mostRecentlyClosedRecord,
  sampleAgeLabel,
  sinceServiceTile,
  telemetryFactRows,
  withFixOnlyPosition,
} from './asset-detail-logic';

// `effectiveRegistration` itself is tested in `core/fleet/asset-attributes.spec.ts` now that it lives
// there (wave W4, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3) — this file re-exports it purely so its
// own pre-existing import site (`asset-detail-facade.ts`) keeps working, same "shim, not a second
// copy" precedent `core/fleet/triage-logic.ts`/`drone-picker-logic.ts` already set.

function sample(partial: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', ...partial };
}

function record(partial: Partial<MaintenanceRecord> = {}): MaintenanceRecord {
  return {
    id: 'rec-0',
    assetId: 'asset-0',
    kind: 'REPAIR',
    summary: 'Replaced a prop',
    openedAt: '2026-07-01T00:00:00Z',
    openedBy: 'user-0',
    ...partial,
  };
}

// `groupTelemetryByDevice`/`telemetryDevices` themselves are tested in `core/telemetry/telemetry-logic.spec.ts`
// now that they live there (docs/plans/done/MVP2-PLAN.md §R, R-b) — `groupTelemetryByDevice` is still used
// here, re-exported, purely as a test-data builder for `freshestSample`'s own coverage below.

describe('freshestSample', () => {
  it('picks the sample with the latest `at` across every device group', () => {
    const byDevice = groupTelemetryByDevice([
      sample({ deviceId: 'gps-1', at: '2026-07-22T00:00:00Z' }),
      sample({ deviceId: 'gps-2', at: '2026-07-22T00:00:05Z' }),
      sample({ deviceId: 'gps-1', at: '2026-07-22T00:00:02Z' }),
    ]);
    const freshest = freshestSample(byDevice);
    expect(freshest).toEqual(sample({ deviceId: 'gps-2', at: '2026-07-22T00:00:05Z' }));
  });

  it('returns undefined for an empty map', () => {
    expect(freshestSample(new Map())).toBeUndefined();
  });
});

describe('attributesToRows', () => {
  it('maps each attribute to a row, in insertion order', () => {
    expect(attributesToRows({ color: 'red', registrationNumber: 'N12345' })).toEqual([
      { key: 'color', value: 'red' },
      { key: 'registrationNumber', value: 'N12345' },
    ]);
  });

  it('returns an empty array for no attributes', () => {
    expect(attributesToRows({})).toEqual([]);
  });
});

describe('attributeRowsToRecord', () => {
  it('builds a record from key/value rows', () => {
    expect(attributeRowsToRecord([{ key: 'color', value: 'red' }])).toEqual({ color: 'red' });
  });

  it('trims keys', () => {
    expect(attributeRowsToRecord([{ key: '  color  ', value: 'red' }])).toEqual({ color: 'red' });
  });

  it('drops rows with a blank key', () => {
    expect(attributeRowsToRecord([{ key: '   ', value: 'red' }])).toEqual({});
  });

  it('lets a later duplicate key win', () => {
    expect(
      attributeRowsToRecord([
        { key: 'color', value: 'red' },
        { key: 'color', value: 'blue' },
      ]),
    ).toEqual({ color: 'blue' });
  });

  it('returns an empty object for no rows', () => {
    expect(attributeRowsToRecord([])).toEqual({});
  });

  it('round-trips with attributesToRows for a well-formed map', () => {
    const attributes = { color: 'red', registrationNumber: 'N12345' };
    expect(attributeRowsToRecord(attributesToRows(attributes))).toEqual(attributes);
  });
});

describe('telemetryFactRows', () => {
  it('formats a fully-populated sample, marking Position as mono', () => {
    const rows = telemetryFactRows(
      sample({ latitude: 40.7128, longitude: -74.006, altitudeMeters: 121.4, headingDegrees: 87, batteryPercent: 62 }),
    );
    expect(rows).toEqual([
      { label: 'Position', value: '40.71280, -74.00600', mono: true },
      { label: 'Altitude', value: '121 m' },
      { label: 'Heading', value: '87°' },
      { label: 'Battery', value: '62%' },
    ]);
  });

  it('degrades every field to "—" for an undefined sample — never a fabricated reading', () => {
    expect(telemetryFactRows(undefined)).toEqual([
      { label: 'Position', value: '—', mono: true },
      { label: 'Altitude', value: '—' },
      { label: 'Heading', value: '—' },
      { label: 'Battery', value: '—' },
    ]);
  });

  it('degrades only the missing fields on a partial sample', () => {
    const rows = telemetryFactRows(sample({ altitudeMeters: 50 }));
    expect(rows).toEqual([
      { label: 'Position', value: '—', mono: true },
      { label: 'Altitude', value: '50 m' },
      { label: 'Heading', value: '—' },
      { label: 'Battery', value: '—' },
    ]);
  });

  // docs/plans/active/OPERATOR-UX-4-PLAN.md finding N1 — a (0, 0) sample is a no-fix report, not a
  // real coordinate; it renders as a faint structural label, never a confident mono "0.00000, 0.00000".
  it('renders "No GPS fix yet" (faint, not mono) for a sample reporting exactly (0, 0)', () => {
    const rows = telemetryFactRows(sample({ latitude: 0, longitude: 0, batteryPercent: 90 }));
    expect(rows[0]).toEqual({ label: 'Position', value: 'No GPS fix yet', faint: true });
    expect(rows[3]).toEqual({ label: 'Battery', value: '90%' });
  });

  it('still renders a real fix that sits on one axis of the equator/prime meridian', () => {
    const rows = telemetryFactRows(sample({ latitude: 0, longitude: 30.5183 }));
    expect(rows[0]).toEqual({ label: 'Position', value: '0.00000, 30.51830', mono: true });
  });
});

describe('sampleAgeLabel', () => {
  it('renders humanAge + " ago" for a known age', () => {
    expect(sampleAgeLabel(353_099)).toBe('4d 2h ago');
  });

  it('degrades to "—" for no sample at all — never the old bare "—s ago" suffix bug', () => {
    expect(sampleAgeLabel(undefined)).toBe('—');
  });
});

describe('withFixOnlyPosition', () => {
  it('passes a real fix through unchanged', () => {
    const s = sample({ latitude: 50.4381, longitude: 30.5183, batteryPercent: 80 });
    expect(withFixOnlyPosition(s)).toBe(s);
  });

  it('strips lat/lon from a (0, 0) sample but keeps every other field', () => {
    const s = sample({ latitude: 0, longitude: 0, batteryPercent: 80, headingDegrees: 12 });
    expect(withFixOnlyPosition(s)).toEqual({ ...s, latitude: undefined, longitude: undefined });
  });

  it('passes a sample with no position at all through unchanged', () => {
    const s = sample({ batteryPercent: 80 });
    expect(withFixOnlyPosition(s)).toBe(s);
  });

  it('passes undefined through unchanged', () => {
    expect(withFixOnlyPosition(undefined)).toBeUndefined();
  });
});

describe('buildIdentityEdit', () => {
  it('trims every field', () => {
    expect(buildIdentityEdit(' SN-1 ', ' DJI ', ' Mavic 3 ', ' N12345 ')).toEqual({
      serialNumber: 'SN-1',
      make: 'DJI',
      model: 'Mavic 3',
      registration: 'N12345',
    });
  });

  it('omits a blank field rather than sending an empty string — clearing it means "now unknown"', () => {
    expect(buildIdentityEdit('', '  ', 'Mavic 3', '')).toEqual({
      serialNumber: undefined,
      make: undefined,
      model: 'Mavic 3',
      registration: undefined,
    });
  });
});

describe('mostRecentlyClosedRecord', () => {
  it('returns undefined for no records', () => {
    expect(mostRecentlyClosedRecord([])).toBeUndefined();
  });

  it('ignores still-open records', () => {
    expect(mostRecentlyClosedRecord([record({ id: 'open' })])).toBeUndefined();
  });

  it('picks the record with the latest closedAt', () => {
    const older = record({ id: 'older', closedAt: '2026-07-10T00:00:00Z' });
    const newer = record({ id: 'newer', closedAt: '2026-07-20T00:00:00Z' });
    expect(mostRecentlyClosedRecord([older, newer, record({ id: 'open' })])).toEqual(newer);
  });
});

describe('formatSinceService', () => {
  it('renders humanAge + " ago" for a known hours-since-close value', () => {
    expect(formatSinceService(98.083)).toBe('4d 2h ago');
  });

  it('degrades to "—" for an asset with no closed maintenance record', () => {
    expect(formatSinceService(undefined)).toBe('—');
  });
});

describe('sinceServiceTile', () => {
  it('degrades to "—" for an asset with no closed record', () => {
    expect(sinceServiceTile([], Date.parse('2026-07-22T00:00:00Z'))).toEqual({ label: 'Since service', value: '—' });
  });

  it('renders the most recently closed record\'s own age', () => {
    const closed = record({ closedAt: '2026-07-20T00:00:00Z' });
    const tile = sinceServiceTile([closed], Date.parse('2026-07-22T00:00:00Z'));
    expect(tile).toEqual({ label: 'Since service', value: '2d ago' });
  });
});
