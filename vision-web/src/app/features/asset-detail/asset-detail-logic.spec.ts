import { describe, expect, it } from 'vitest';
import type { TelemetrySample } from '../../core/api/models';
import {
  attributeRowsToRecord,
  attributesToRows,
  freshestSample,
  groupTelemetryByDevice,
  telemetryFactRows,
} from './asset-detail-logic';

function sample(partial: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', ...partial };
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
});
