import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LayersStore } from './layers-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import type { LiveConnectionState } from '../live/live-fallback-logic';
import type { LayerGrant, MapEventPayload, MapLayer } from '../api/models';

/**
 * Store-level coverage for `LayersStore`'s own initial-GET/live-fold posture (mirrors
 * `marks-store.spec.ts`'s shape) plus the ALWAYS-ON-FLOW-PLAN.md §4 Wave C3 demand-gating this
 * store gained alongside `MarksStore`/`DrawingsStore`/`TracksStore`/`GeofenceStore` — the first file
 * of its kind for this store (no prior spec existed).
 */

function layer(overrides: Partial<MapLayer> = {}): MapLayer {
  return {
    layerId: 'layer-a',
    name: 'Team',
    kind: 'TEAM',
    myAccess: 'CONTRIBUTE',
    markCount: 0,
    drawingCount: 0,
    createdAt: '2026-08-05T10:00:00Z',
    ...overrides,
  };
}

function stubApi(overrides: Record<string, ReturnType<typeof vi.fn>> = {}) {
  return {
    listMapLayers: vi.fn().mockResolvedValue([]),
    createMapLayer: vi.fn(),
    renameMapLayer: vi.fn(),
    deleteMapLayer: vi.fn(),
    setMapLayerGrants: vi.fn(),
    ...overrides,
  };
}

/** `connectionState` seeded `'closed'` — reproduces today's (pre-D1) behaviour exactly, see
 *  `marks-store.spec.ts`'s identical `stubLiveFacade` doc comment. */
function stubLiveFacade() {
  const events = signal<readonly MapEventPayload[]>([]);
  const connectionState = signal<LiveConnectionState>('closed');
  return {
    mapEvents: events.asReadonly(),
    push: (incoming: readonly MapEventPayload[]) => events.update((existing) => [...existing, ...incoming]),
    connectionState,
  };
}

function createInactive(api: ReturnType<typeof stubApi>) {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
  const live = stubLiveFacade();
  const scheduleFn = vi.fn().mockReturnValue(vi.fn());
  TestBed.configureTestingModule({
    providers: [
      LayersStore,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: PollScheduler, useValue: { schedule: scheduleFn } },
      { provide: LiveFacade, useValue: live },
    ],
  });
  return { store: TestBed.inject(LayersStore), toasts, live, scheduleFn };
}

/** Lets the fire-and-forget promise chain inside `refresh()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('LayersStore', () => {
  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      await flush();
      expect(api.listMapLayers).not.toHaveBeenCalled();
      expect(scheduleFn).not.toHaveBeenCalled();

      store.activate();
      await flush();
      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
      expect(scheduleFn).toHaveBeenCalledTimes(1);
      expect(store.loaded()).toBe(true);
    });

    it('a second concurrent consumer neither re-fetches nor re-schedules', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      store.activate();
      await flush();
      store.activate();
      await flush();
      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
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
      expect(api.listMapLayers).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { store } = createInactive(stubApi());
      expect(() => store.release()).not.toThrow();
    });
  });

  describe('initial GET + map-topic deltas (once activated)', () => {
    it('fetches the layer list and exposes the COP/contributable/manageable projections', async () => {
      // `GET /api/map/layers` already serves COP first then by name (`sortLayers`'s own doc comment)
      // — the store trusts that order on the initial fetch rather than re-sorting it itself.
      const api = stubApi({
        listMapLayers: vi.fn().mockResolvedValue([layer({ layerId: 'cop', kind: 'COP', myAccess: 'VIEW' }), layer()]),
      });
      const { store } = createInactive(api);
      store.activate();
      await flush();

      expect(store.layers().map((l) => l.layerId)).toEqual(['cop', 'layer-a']);
      expect(store.cop()?.layerId).toBe('cop');
      expect(store.contributable().map((l) => l.layerId)).toEqual(['layer-a']);
    });

    it('keeps the last-known list on a failed fetch, silent-degrade', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockRejectedValue(new Error('down')) });
      const { store } = createInactive(api);
      store.activate();
      await flush();

      expect(store.layers()).toEqual([]);
      expect(store.loaded()).toBe(true);
    });

    it('folds a live layer delta without waiting for the next poll', async () => {
      const api = stubApi();
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      TestBed.tick();

      // A `layer`-entity delta also schedules the L1c grants-reconcile debounce (see the "grants
      // revocation" describe below) — fake timers here just keep that from leaving a real 1s
      // `setTimeout` pending past the end of this test.
      vi.useFakeTimers();
      live.push([{ entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'Renamed live' }) }]);
      TestBed.tick();

      expect(store.layers()[0]?.name).toBe('Renamed live');
      vi.useRealTimers();
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    /**
     * The plan's own frozen acceptance criterion (§5): a poll that stops must still reconcile on
     * reconnect. With the store active and live open, driving `connectionState` through
     * `open → closed → open` must issue **exactly one** REST refresh on (re-)entering `open`, and
     * **zero** REST requests for as long as `open` persists.
     */
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
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
      api.listMapLayers.mockClear();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      expect(api.listMapLayers).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      store.activate(); // a second concurrent consumer while already live — no further request
      TestBed.tick();
      await flush();
      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
    });

    it('a store that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const live = stubLiveFacade();
      live.connectionState.set('open');
      const scheduleFn = vi.fn().mockReturnValue(vi.fn());
      TestBed.configureTestingModule({
        providers: [
          LayersStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() } },
          { provide: PollScheduler, useValue: { schedule: scheduleFn } },
          { provide: LiveFacade, useValue: live },
        ],
      });
      const store = TestBed.inject(LayersStore);

      store.activate();
      await flush();

      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
      expect(scheduleFn).not.toHaveBeenCalled();
    });
  });

  describe('grants revocation (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L1c)', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('a bare live LAYER delta cannot show a revoked grant, so it schedules a debounced reconcile that fixes it', async () => {
      const grant: LayerGrant = { subjectType: 'USER', subjectId: 'u1', level: 'CONTRIBUTE' };
      const withGrant = layer({ grants: [grant] });
      const withoutGrant = layer({ grants: [] });
      const api = stubApi({
        listMapLayers: vi.fn().mockResolvedValueOnce([withGrant]).mockResolvedValueOnce([withoutGrant]),
      });
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      TestBed.tick();
      expect(store.layer('layer-a')?.grants).toEqual([grant]);

      vi.useFakeTimers();
      // `LayerResponse.forEvent` never carries grants (§4.3) — `applyLayerEvents` preserves the
      // previously-known list, so the bare fold alone cannot represent a revocation.
      live.push([{ entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'Renamed' }) }]);
      TestBed.tick();
      expect(store.layer('layer-a')?.grants).toEqual([grant]); // still stale — the fold's known limit
      expect(api.listMapLayers).toHaveBeenCalledTimes(1); // no reconcile fired yet — still debouncing

      await vi.advanceTimersByTimeAsync(1_000);
      TestBed.tick();

      expect(api.listMapLayers).toHaveBeenCalledTimes(2); // the debounced reconcile GET
      expect(store.layer('layer-a')?.grants).toEqual([]); // now correctly shows the revocation
    });

    it('debounces a burst of layer deltas into a single reconcile GET', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      api.listMapLayers.mockClear();

      vi.useFakeTimers();
      live.push([{ entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'A' }) }]);
      TestBed.tick();
      await vi.advanceTimersByTimeAsync(500);

      live.push([{ entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'B' }) }]);
      TestBed.tick();
      await vi.advanceTimersByTimeAsync(500); // 1000ms since the 1st event, but only 500ms since the 2nd
      expect(api.listMapLayers).not.toHaveBeenCalled(); // the 2nd event pushed the debounce back out

      await vi.advanceTimersByTimeAsync(500); // now 1000ms since the 2nd (and last) event
      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
    });

    it('never reconciles for an unrelated entity riding the same log (mark/drawing deltas)', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      api.listMapLayers.mockClear();

      vi.useFakeTimers();
      live.push([{ entity: 'mark', action: 'created', layerId: 'layer-a' }]);
      TestBed.tick();
      await vi.advanceTimersByTimeAsync(1_500);

      expect(api.listMapLayers).not.toHaveBeenCalled();
    });
  });
});
