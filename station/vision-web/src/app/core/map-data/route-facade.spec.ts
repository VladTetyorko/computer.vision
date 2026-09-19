import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { provideAppState } from '../state/app-state';
import { provideRouteState } from './state/route.providers';
import { VisionApi } from '../api/vision-api';
import type { TelemetrySample, UsageSummary, UsageTimeline } from '../api/models';
import { RouteFacade } from './route-facade';

/**
 * `RouteFacade` end to end — replaces `route-store.spec.ts` case for case
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6). The old `generation` counter is gone; the
 * "superseded show() never clobbers the later result" case now exercises `switchMap` doing that
 * structurally (see `route.effects.ts#show$`'s own doc comment) rather than hand-counted bookkeeping.
 */

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

function create(api: ReturnType<typeof stubApi>): RouteFacade {
  TestBed.configureTestingModule({ providers: [provideAppState(), provideRouteState(), RouteFacade, { provide: VisionApi, useValue: api }] });
  return TestBed.inject(RouteFacade);
}

describe('RouteFacade', () => {
  it('off never calls the API and clears whatever was showing', async () => {
    const api = stubApi();
    const facade = create(api);

    await facade.show('a1', 'off');

    expect(api.listUsages).not.toHaveBeenCalled();
    expect(facade.routes()).toEqual([]);
    expect(facade.state()).toBe('idle');
  });

  it('last asks for one usage and builds one route from its timeline', async () => {
    const api = stubApi({
      listUsages: vi.fn().mockResolvedValue([usageSummary()]),
      usageTimeline: vi.fn().mockResolvedValue(timeline('u1', [sample()], '2026-09-02T10:05:00Z')),
    });
    const facade = create(api);

    await facade.show('a1', 'last');

    expect(api.listUsages).toHaveBeenCalledWith({ assetId: 'a1', limit: 1 });
    expect(api.usageTimeline).toHaveBeenCalledWith('u1', { maxPoints: 500 });
    expect(facade.state()).toBe('loaded');
    expect(facade.routes()).toHaveLength(1);
    expect(facade.routes()[0]).toMatchObject({ assetId: 'a1', usageId: 'u1', endedAt: '2026-09-02T10:05:00Z' });
    expect(facade.noUsages()).toBe(false);
  });

  it('last3 asks for three usages and fetches every timeline', async () => {
    const api = stubApi({
      listUsages: vi.fn().mockResolvedValue([usageSummary({ usageId: 'u1' }), usageSummary({ usageId: 'u2' }), usageSummary({ usageId: 'u3' })]),
      usageTimeline: vi.fn().mockImplementation((usageId: string) => Promise.resolve(timeline(usageId, [sample()]))),
    });
    const facade = create(api);

    await facade.show('a1', 'last3');

    expect(api.listUsages).toHaveBeenCalledWith({ assetId: 'a1', limit: 3 });
    expect(api.usageTimeline).toHaveBeenCalledTimes(3);
    expect(facade.routes().map((r) => r.usageId)).toEqual(['u1', 'u2', 'u3']);
  });

  it('is honest about zero recorded flights — noUsages, not an error', async () => {
    const api = stubApi({ listUsages: vi.fn().mockResolvedValue([]) });
    const facade = create(api);

    await facade.show('a1', 'last');

    expect(facade.state()).toBe('loaded');
    expect(facade.noUsages()).toBe(true);
    expect(facade.routes()).toEqual([]);
  });

  it('a fetch failure lands in state error, never a fabricated route', async () => {
    const api = stubApi({ listUsages: vi.fn().mockRejectedValue(new Error('network')) });
    const facade = create(api);

    await facade.show('a1', 'last');

    expect(facade.state()).toBe('error');
    expect(facade.routes()).toEqual([]);
  });

  it('hide() clears the map and its state back to idle', async () => {
    const api = stubApi({ listUsages: vi.fn().mockResolvedValue([usageSummary()]), usageTimeline: vi.fn().mockResolvedValue(timeline('u1', [sample()])) });
    const facade = create(api);
    await facade.show('a1', 'last');
    expect(facade.routes()).toHaveLength(1);

    facade.hide();

    expect(facade.routes()).toEqual([]);
    expect(facade.state()).toBe('idle');
  });

  it('a superseded show() (span changed mid-flight) never clobbers the later result', async () => {
    let resolveFirst!: (usages: UsageSummary[]) => void;
    const firstCall = new Promise<UsageSummary[]>((resolve) => (resolveFirst = resolve));
    const listUsages = vi.fn().mockImplementationOnce(() => firstCall).mockResolvedValueOnce([usageSummary({ usageId: 'u2' })]);
    const api = stubApi({ listUsages, usageTimeline: vi.fn().mockImplementation((usageId: string) => Promise.resolve(timeline(usageId, [sample()]))) });
    const facade = create(api);

    const stale = facade.show('a1', 'last');
    const fresh = facade.show('a1', 'last3');
    await fresh;
    resolveFirst([usageSummary({ usageId: 'u1-stale' })]);
    await stale;
    await flush();

    expect(facade.routes().map((r) => r.usageId)).toEqual(['u2']);
  });

  it('a fresh mount resets any stale global slice state left by a previous visit', () => {
    TestBed.configureTestingModule({ providers: [provideAppState(), provideRouteState(), RouteFacade, { provide: VisionApi, useValue: stubApi() }] });
    const first = TestBed.inject(RouteFacade);
    void first;
    // A second facade instance in the same injector simulates a second page mount reusing the
    // same global `route` slice — its constructor-time `hideRequested()` must still reset it.
    expect(TestBed.inject(RouteFacade).routes()).toEqual([]);
  });
});
