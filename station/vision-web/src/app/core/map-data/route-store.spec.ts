import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { RouteStore } from './route-store';
import { VisionApi } from '../api/vision-api';
import type { TelemetrySample, UsageSummary, UsageTimeline } from '../api/models';

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function usageSummary(overrides: Partial<UsageSummary> = {}): UsageSummary {
  return { usageId: 'u1', assetId: 'a1', assetName: 'Asset 1', startedAt: '2026-09-02T10:00:00Z', sampleCount: 3, ...overrides };
}

function sample(overrides: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'd1', at: '2026-09-02T10:00:00Z', latitude: 50.45, longitude: 30.52, ...overrides };
}

function timeline(usageId: string, telemetry: readonly TelemetrySample[], endedAt?: string): UsageTimeline {
  return {
    usage: { usageId, startedAt: '2026-09-02T10:00:00Z', endedAt, sampleCount: telemetry.length },
    from: '2026-09-02T10:00:00Z',
    to: '2026-09-02T10:05:00Z',
    telemetry,
    detections: [],
  };
}

function stubApi(overrides: Partial<Record<'listUsages' | 'usageTimeline', ReturnType<typeof vi.fn>>> = {}) {
  return {
    listUsages: vi.fn().mockResolvedValue([]),
    usageTimeline: vi.fn().mockResolvedValue(timeline('u1', [])),
    ...overrides,
  };
}

function create(api: ReturnType<typeof stubApi>): RouteStore {
  TestBed.configureTestingModule({ providers: [RouteStore, { provide: VisionApi, useValue: api }] });
  return TestBed.inject(RouteStore);
}

describe('RouteStore', () => {
  it('off never calls the API and clears whatever was showing', async () => {
    const api = stubApi();
    const store = create(api);

    await store.show('a1', 'off');

    expect(api.listUsages).not.toHaveBeenCalled();
    expect(store.routes()).toEqual([]);
    expect(store.state()).toBe('idle');
  });

  it('last asks for one usage and builds one route from its timeline', async () => {
    const api = stubApi({
      listUsages: vi.fn().mockResolvedValue([usageSummary()]),
      usageTimeline: vi.fn().mockResolvedValue(timeline('u1', [sample()], '2026-09-02T10:05:00Z')),
    });
    const store = create(api);

    await store.show('a1', 'last');

    expect(api.listUsages).toHaveBeenCalledWith({ assetId: 'a1', limit: 1 });
    expect(api.usageTimeline).toHaveBeenCalledWith('u1', { maxPoints: 500 });
    expect(store.state()).toBe('loaded');
    expect(store.routes()).toHaveLength(1);
    expect(store.routes()[0]).toMatchObject({ assetId: 'a1', usageId: 'u1', endedAt: '2026-09-02T10:05:00Z' });
    expect(store.noUsages()).toBe(false);
  });

  it('last3 asks for three usages and fetches every timeline', async () => {
    const api = stubApi({
      listUsages: vi.fn().mockResolvedValue([usageSummary({ usageId: 'u1' }), usageSummary({ usageId: 'u2' }), usageSummary({ usageId: 'u3' })]),
      usageTimeline: vi.fn().mockImplementation((usageId: string) => Promise.resolve(timeline(usageId, [sample()]))),
    });
    const store = create(api);

    await store.show('a1', 'last3');

    expect(api.listUsages).toHaveBeenCalledWith({ assetId: 'a1', limit: 3 });
    expect(api.usageTimeline).toHaveBeenCalledTimes(3);
    expect(store.routes().map((r) => r.usageId)).toEqual(['u1', 'u2', 'u3']);
  });

  it('is honest about zero recorded flights — noUsages, not an error', async () => {
    const api = stubApi({ listUsages: vi.fn().mockResolvedValue([]) });
    const store = create(api);

    await store.show('a1', 'last');

    expect(store.state()).toBe('loaded');
    expect(store.noUsages()).toBe(true);
    expect(store.routes()).toEqual([]);
  });

  it('a fetch failure lands in state error, never a fabricated route', async () => {
    const api = stubApi({ listUsages: vi.fn().mockRejectedValue(new Error('network')) });
    const store = create(api);

    await store.show('a1', 'last');

    expect(store.state()).toBe('error');
    expect(store.routes()).toEqual([]);
  });

  it('hide() clears the map and its state back to idle', async () => {
    const api = stubApi({ listUsages: vi.fn().mockResolvedValue([usageSummary()]), usageTimeline: vi.fn().mockResolvedValue(timeline('u1', [sample()])) });
    const store = create(api);
    await store.show('a1', 'last');
    expect(store.routes()).toHaveLength(1);

    store.hide();

    expect(store.routes()).toEqual([]);
    expect(store.state()).toBe('idle');
  });

  it('a superseded show() (span changed mid-flight) never clobbers the later result', async () => {
    let resolveFirst!: (usages: UsageSummary[]) => void;
    const firstCall = new Promise<UsageSummary[]>((resolve) => (resolveFirst = resolve));
    const listUsages = vi.fn().mockImplementationOnce(() => firstCall).mockResolvedValueOnce([usageSummary({ usageId: 'u2' })]);
    const api = stubApi({ listUsages, usageTimeline: vi.fn().mockImplementation((usageId: string) => Promise.resolve(timeline(usageId, [sample()]))) });
    const store = create(api);

    const stale = store.show('a1', 'last');
    const fresh = store.show('a1', 'last3');
    await fresh;
    resolveFirst([usageSummary({ usageId: 'u1-stale' })]);
    await stale;
    await flush();

    expect(store.routes().map((r) => r.usageId)).toEqual(['u2']);
  });
});
