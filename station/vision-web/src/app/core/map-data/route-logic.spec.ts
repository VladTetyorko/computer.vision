import { describe, expect, it } from 'vitest';
import type { TelemetrySample, UsageSummary, UsageTimeline } from '../api/models';
import { ROUTE_MAX_POINTS, buildAssetRoute, routeUsageLimit } from './route-logic';

function sample(overrides: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'd1', at: '2026-09-02T10:00:00Z', latitude: 50.45, longitude: 30.52, ...overrides };
}

function summary(overrides: Partial<UsageSummary> = {}): UsageSummary {
  return {
    usageId: 'u1',
    assetId: 'a1',
    assetName: 'Asset 1',
    startedAt: '2026-09-02T10:00:00Z',
    sampleCount: 10,
    ...overrides,
  };
}

function timeline(telemetry: readonly TelemetrySample[], overrides: Partial<UsageTimeline['usage']> = {}): UsageTimeline {
  return {
    usage: { usageId: 'u1', startedAt: '2026-09-02T10:00:00Z', sampleCount: telemetry.length, ...overrides },
    from: '2026-09-02T10:00:00Z',
    to: '2026-09-02T10:05:00Z',
    telemetry,
    detections: [],
  };
}

describe('routeUsageLimit', () => {
  it('asks for nothing while off, one for the last flight, three for the last three', () => {
    expect(routeUsageLimit('off')).toBe(0);
    expect(routeUsageLimit('last')).toBe(1);
    expect(routeUsageLimit('last3')).toBe(3);
  });
});

describe('buildAssetRoute', () => {
  it('carries the usage identity straight through from the timeline, not the summary', () => {
    const route = buildAssetRoute('a1', summary(), timeline([sample()], { endedAt: '2026-09-02T10:05:00Z' }));
    expect(route).toMatchObject({ assetId: 'a1', usageId: 'u1', startedAt: '2026-09-02T10:00:00Z', endedAt: '2026-09-02T10:05:00Z' });
  });

  it('leaves endedAt absent for a still-open flight', () => {
    const route = buildAssetRoute('a1', summary(), timeline([sample()]));
    expect(route.endedAt).toBeUndefined();
  });

  it('drops every sample with no fix — missing lat/lon and (0,0) alike', () => {
    const route = buildAssetRoute(
      'a1',
      summary(),
      timeline([sample({ latitude: 0, longitude: 0 }), sample({ latitude: undefined, longitude: undefined }), sample({ latitude: 51, longitude: 31 })]),
    );
    expect(route.points).toEqual([{ latitude: 51, longitude: 31, altitudeMeters: undefined }]);
  });

  it('is honestly empty, never fabricated, for a usage that recorded no fixes at all', () => {
    const route = buildAssetRoute('a1', summary(), timeline([sample({ latitude: 0, longitude: 0 })]));
    expect(route.points).toEqual([]);
  });

  it('flags truncated only when the usage recorded more samples than the request cap', () => {
    const underCap = buildAssetRoute('a1', summary({ sampleCount: ROUTE_MAX_POINTS }), timeline([sample()]));
    expect(underCap.truncated).toBe(false);

    const overCap = buildAssetRoute('a1', summary({ sampleCount: ROUTE_MAX_POINTS + 1 }), timeline([sample()]));
    expect(overCap.truncated).toBe(true);
  });

  it('respects a caller-supplied maxPoints instead of the default cap', () => {
    const route = buildAssetRoute('a1', summary({ sampleCount: 50 }), timeline([sample()]), 10);
    expect(route.truncated).toBe(true);
  });
});
