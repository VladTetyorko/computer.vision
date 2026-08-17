import { TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { DrawingsStore } from './drawings-store';
import { LayersStore } from './layers-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import type { MapEventPayload, MapDrawingResponse } from '../api/models';

/**
 * Store-level coverage, first file of its kind for `DrawingsStore` — mirrors `marks-store.spec.ts`'s
 * shape. Written alongside the BUG 3 fix (`resetOnRouteChange`), which is also why every provider set
 * below now needs a `Router`: this store injects one unconditionally from that fix onward.
 */
@Component({ selector: 'vision-test-stub-page', template: '' })
class StubPage {}

function drawing(overrides: Partial<MapDrawingResponse> = {}): MapDrawingResponse {
  return {
    drawingId: 'd1',
    layerId: 'layer-a',
    kind: 'LINE',
    label: 'A line',
    colorToken: 'accent',
    points: [
      { latitude: 50.45, longitude: 30.52 },
      { latitude: 50.46, longitude: 30.53 },
    ],
    createdByUserId: 'u1',
    createdAt: '2026-08-05T10:00:00Z',
    ...overrides,
  };
}

function stubApi(overrides: Record<string, ReturnType<typeof vi.fn>> = {}) {
  return {
    listMapDrawings: vi.fn().mockResolvedValue([]),
    createMapDrawing: vi.fn(),
    patchMapDrawing: vi.fn(),
    deleteMapDrawing: vi.fn(),
    ...overrides,
  };
}

function stubLiveStore() {
  const events = signal<readonly MapEventPayload[]>([]);
  return { mapEvents: events.asReadonly() };
}

function stubLayersStore() {
  return {
    contributable: signal([]).asReadonly(),
    defaultLayerId: signal(undefined).asReadonly(),
    canContributeTo: () => true,
  };
}

function create(api: ReturnType<typeof stubApi>) {
  TestBed.configureTestingModule({
    providers: [
      DrawingsStore,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() } },
      { provide: PollScheduler, useValue: { schedule: vi.fn().mockReturnValue(() => undefined) } },
      { provide: LiveStore, useValue: stubLiveStore() },
      { provide: LayersStore, useValue: stubLayersStore() },
      provideRouter([
        { path: 'command', component: StubPage },
        { path: 'fly/:assetId', component: StubPage },
      ]),
    ],
  });
  return TestBed.inject(DrawingsStore);
}

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('DrawingsStore', () => {
  it('fetches the drawing list once at construction', async () => {
    const store = create(stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) }));
    await flush();
    expect(store.drawings()).toEqual([drawing()]);
    expect(store.loaded()).toBe(true);
  });

  describe('BUG 3: mode disarms on a genuine page change, not on a same-page navigation', () => {
    it('leaves an armed draw mode alone across a same-path, query-param-only navigation', async () => {
      const store = create(stubApi());
      await flush();
      const router = TestBed.inject(Router);

      await router.navigateByUrl('/command');
      store.setMode('LINE');
      expect(store.mode()).toBe('LINE');

      // Mirrors `CommandFacade`'s own `?asset=` URL sync (BUG 4): same path, query-only navigation.
      await router.navigateByUrl('/command?asset=abc');
      expect(store.mode()).toBe('LINE');
    });

    it('disarms an armed draw mode once the path actually changes (the /command → /fly/:assetId repro)', async () => {
      const store = create(stubApi());
      await flush();
      const router = TestBed.inject(Router);

      await router.navigateByUrl('/command');
      store.setMode('POLYGON');
      expect(store.mode()).toBe('POLYGON');

      await router.navigateByUrl('/fly/asset-1');
      expect(store.mode()).toBeNull();
    });
  });
});
