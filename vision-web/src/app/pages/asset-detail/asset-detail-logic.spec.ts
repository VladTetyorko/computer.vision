import { describe, expect, it } from 'vitest';
import type { TelemetrySample } from '../../core/api/models';
import { freshestSample, groupTelemetryByDevice } from './asset-detail-logic';

function sample(partial: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', ...partial };
}

// `groupTelemetryByDevice`/`telemetryDevices` themselves are tested in `core/telemetry-logic.spec.ts`
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
