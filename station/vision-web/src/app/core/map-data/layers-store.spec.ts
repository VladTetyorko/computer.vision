import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { LayersStore } from './layers-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import type { MapEventPayload, MapLayer } from '../api/models';

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

function stubLiveStore() {
  const events = signal<readonly MapEventPayload[]>([]);
  return {
    mapEvents: events.asReadonly(),
    push: (incoming: readonly MapEventPayload[]) => events.update((existing) => [...existing, ...incoming]),
  };
}

function createInactive(api: ReturnType<typeof stubApi>) {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
  const live = stubLiveStore();
  const scheduleFn = vi.fn().mockReturnValue(vi.fn());
  TestBed.configureTestingModule({
    providers: [
      LayersStore,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: PollScheduler, useValue: { schedule: scheduleFn } },
      { provide: LiveStore, useValue: live },
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

      live.push([{ entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'Renamed live' }) }]);
      TestBed.tick();

      expect(store.layers()[0]?.name).toBe('Renamed live');
    });
  });
});
