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
import type { LiveConnectionState } from '../live/live-fallback-logic';
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

/** `connectionState` seeded `'closed'` — reproduces today's (pre-D1) behaviour exactly, see
 *  `marks-store.spec.ts`'s identical `stubLiveStore` doc comment. */
function stubLiveStore() {
  const events = signal<readonly MapEventPayload[]>([]);
  const connectionState = signal<LiveConnectionState>('closed');
  return {
    mapEvents: events.asReadonly(),
    push: (incoming: readonly MapEventPayload[]) => events.update((existing) => [...existing, ...incoming]),
    connectionState,
  };
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
  const store = TestBed.inject(DrawingsStore);
  // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: see `marks-store.spec.ts`'s identical `create()` comment.
  store.activate();
  return store;
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

  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    function createInactive(api: ReturnType<typeof stubApi>) {
      const scheduleFn = vi.fn().mockReturnValue(vi.fn());
      TestBed.configureTestingModule({
        providers: [
          DrawingsStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() } },
          { provide: PollScheduler, useValue: { schedule: scheduleFn } },
          { provide: LiveStore, useValue: stubLiveStore() },
          { provide: LayersStore, useValue: stubLayersStore() },
          provideRouter([{ path: 'command', component: StubPage }]),
        ],
      });
      return { store: TestBed.inject(DrawingsStore), scheduleFn };
    }

    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      await flush();
      expect(api.listMapDrawings).not.toHaveBeenCalled();
      expect(scheduleFn).not.toHaveBeenCalled();

      store.activate();
      await flush();
      expect(api.listMapDrawings).toHaveBeenCalledTimes(1);
      expect(scheduleFn).toHaveBeenCalledTimes(1);
    });

    it('stops the poll only once every consumer has released, then refreshes again on reactivation', async () => {
      const api = stubApi();
      const stopFn = vi.fn();
      const { store, scheduleFn } = createInactive(api);
      scheduleFn.mockReturnValue(stopFn);

      store.activate();
      store.activate();
      await flush();
      store.release();
      expect(stopFn).not.toHaveBeenCalled();
      store.release();
      expect(stopFn).toHaveBeenCalledTimes(1);

      store.activate();
      await flush();
      expect(api.listMapDrawings).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { store } = createInactive(stubApi());
      expect(() => store.release()).not.toThrow();
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    function createInactive(api: ReturnType<typeof stubApi>) {
      const live = stubLiveStore();
      const scheduleFn = vi.fn().mockReturnValue(vi.fn());
      TestBed.configureTestingModule({
        providers: [
          DrawingsStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() } },
          { provide: PollScheduler, useValue: { schedule: scheduleFn } },
          { provide: LiveStore, useValue: live },
          { provide: LayersStore, useValue: stubLayersStore() },
          provideRouter([{ path: 'command', component: StubPage }]),
        ],
      });
      return { store: TestBed.inject(DrawingsStore), live, scheduleFn };
    }

    /**
     * The plan's own frozen acceptance criterion (§5): a poll that stops must still reconcile on
     * reconnect. With the store active and live open, driving `connectionState` through
     * `open → closed → open` must issue **exactly one** REST refresh on (re-)entering `open`, and
     * **zero** REST requests for as long as `open` persists.
     */
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) });
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      TestBed.tick();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      // (the first entry into live already reconciled once here — not the segment under test)

      live.connectionState.set('closed');
      TestBed.tick();
      await flush();
      // Falling back to polling refreshes immediately too (the D1 table's own
      // `>0 | false | live → refresh once, then start poll` row) — a separate, legitimate call,
      // also not the segment under test. Only now do we isolate "entering open".
      api.listMapDrawings.mockClear();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      expect(api.listMapDrawings).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      store.activate(); // a second concurrent consumer while already live — no further request
      TestBed.tick();
      await flush();
      expect(api.listMapDrawings).toHaveBeenCalledTimes(1);
    });

    it('a store that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) });
      const { store, live, scheduleFn } = createInactive(api);
      live.connectionState.set('open');

      store.activate();
      await flush();

      expect(api.listMapDrawings).toHaveBeenCalledTimes(1);
      expect(scheduleFn).not.toHaveBeenCalled();
    });
  });
});
