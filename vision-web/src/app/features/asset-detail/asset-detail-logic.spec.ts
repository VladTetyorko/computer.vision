import { describe, expect, it } from 'vitest';
import type { TelemetrySample } from '../../core/api/models';
import { attributeRowsToRecord, attributesToRows, freshestSample, groupTelemetryByDevice } from './asset-detail-logic';

function sample(partial: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', ...partial };
}

// `groupTelemetryByDevice`/`telemetryDevices` themselves are tested in `core/telemetry/telemetry-logic.spec.ts`
// now that they live there (docs/MVP2-PLAN.md §R, R-b) — `groupTelemetryByDevice` is still used
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
