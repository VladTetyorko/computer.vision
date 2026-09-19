import { TestBed } from '@angular/core/testing';
import { Store } from '@ngrx/store';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LayerGrant, MapEventPayload, MapLayer } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveSocketActions } from '../live/state/live.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { provideLayersState } from './state/layers.providers';
import { ToastService } from '../toast.service';
import { LayersFacade } from './layers-facade';

/**
 * `LayersFacade` end to end — replaces `layers-store.spec.ts` case for case
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6). The live gate is driven by dispatching real
 * `LiveSocketActions` against the actually-registered `live` slice (§9 — effects read `live` only
 * through its own selectors), never by stubbing `LiveFacade`.
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

function stubScheduler() {
  return { schedule: vi.fn().mockReturnValue(vi.fn()) };
}

function createInactive(api: ReturnType<typeof stubApi>, scheduler = stubScheduler()) {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      provideLayersState(),
      LayersFacade,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { facade: TestBed.inject(LayersFacade), store: TestBed.inject(Store), toasts, scheduler };
}

function create(api: ReturnType<typeof stubApi>) {
  const context = createInactive(api);
  context.facade.activate();
  return context;
}

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

let nextSeq = 1;
function push(store: Store, payload: MapEventPayload) {
  store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: nextSeq++, type: 'map', payload } }));
}

describe('LayersFacade', () => {
  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { facade, scheduler } = createInactive(api);
      await flush();
      expect(api.listMapLayers).not.toHaveBeenCalled();
      expect(scheduler.schedule).not.toHaveBeenCalled();

      facade.activate();
      await flush();
      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).toHaveBeenCalledTimes(1);
      expect(facade.loaded()).toBe(true);
    });

    it('a second concurrent consumer neither re-fetches nor re-schedules', async () => {
      const api = stubApi();
      const { facade, scheduler } = createInactive(api);
      facade.activate();
      await flush();
      facade.activate();
      await flush();
      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).toHaveBeenCalledTimes(1);
    });

    it('stops the poll only once every consumer has released, then refreshes again on reactivation', async () => {
      const api = stubApi();
      const stopFn = vi.fn();
      const scheduler = stubScheduler();
      scheduler.schedule.mockReturnValue(stopFn);
      const { facade } = createInactive(api, scheduler);

      facade.activate();
      facade.activate();
      await flush();
      facade.release();
      expect(stopFn).not.toHaveBeenCalled();
      facade.release();
      expect(stopFn).toHaveBeenCalledTimes(1);

      facade.activate();
      await flush();
      expect(api.listMapLayers).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { facade } = createInactive(stubApi());
      expect(() => facade.release()).not.toThrow();
    });
  });

  describe('initial GET + map-topic deltas (once activated)', () => {
    it('fetches the layer list and exposes the COP/contributable/manageable projections', async () => {
      const api = stubApi({
        listMapLayers: vi.fn().mockResolvedValue([layer({ layerId: 'cop', kind: 'COP', myAccess: 'VIEW' }), layer()]),
      });
      const { facade } = create(api);
      await flush();

      expect(facade.layers().map((l) => l.layerId)).toEqual(['cop', 'layer-a']);
      expect(facade.cop()?.layerId).toBe('cop');
      expect(facade.contributable().map((l) => l.layerId)).toEqual(['layer-a']);
    });

    it('keeps the last-known list on a failed fetch, silent-degrade', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockRejectedValue(new Error('down')) });
      const { facade } = create(api);
      await flush();

      expect(facade.layers()).toEqual([]);
      expect(facade.loaded()).toBe(true);
    });

    it('folds a live layer delta without waiting for the next poll', async () => {
      const api = stubApi();
      const { facade, store } = create(api);
      await flush();

      push(store, { entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'Renamed live' }) });

      expect(facade.layers()[0]?.name).toBe('Renamed live');
    });

    it('folds a LAYER delta unconditionally, even with no active consumer', () => {
      const { facade, store } = createInactive(stubApi());
      // deliberately never activate() — the fold is documented as running from construction alone.

      push(store, { entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer() });

      expect(facade.layers().map((l) => l.layerId)).toEqual(['layer-a']);
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { facade, store } = createInactive(api);
      facade.activate();
      await flush();

      store.dispatch(LiveSocketActions.opened());
      await flush();
      // (the first entry into live already reconciled once here — not the segment under test)

      store.dispatch(LiveSocketActions.closed());
      await flush();
      api.listMapLayers.mockClear();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      expect(api.listMapLayers).toHaveBeenCalledTimes(1);

      facade.activate();
      await flush();
      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
    });

    it('a facade that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { facade, store, scheduler } = createInactive(api);
      store.dispatch(LiveSocketActions.opened());

      facade.activate();
      await flush();

      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).not.toHaveBeenCalled();
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
      const { facade, store } = create(api);
      await flush();
      expect(facade.layer('layer-a')?.grants).toEqual([grant]);

      vi.useFakeTimers();
      push(store, { entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'Renamed' }) });
      expect(facade.layer('layer-a')?.grants).toEqual([grant]); // still stale — the fold's known limit
      expect(api.listMapLayers).toHaveBeenCalledTimes(1); // no reconcile fired yet — still debouncing

      await vi.advanceTimersByTimeAsync(1_000);

      expect(api.listMapLayers).toHaveBeenCalledTimes(2); // the debounced reconcile GET
      expect(facade.layer('layer-a')?.grants).toEqual([]); // now correctly shows the revocation
    });

    it('debounces a burst of layer deltas into a single reconcile GET', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { facade, store } = create(api);
      await flush();
      api.listMapLayers.mockClear();
      void facade;

      vi.useFakeTimers();
      push(store, { entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'A' }) });
      await vi.advanceTimersByTimeAsync(500);

      push(store, { entity: 'layer', action: 'updated', layerId: 'layer-a', layer: layer({ name: 'B' }) });
      await vi.advanceTimersByTimeAsync(500); // 1000ms since the 1st event, but only 500ms since the 2nd
      expect(api.listMapLayers).not.toHaveBeenCalled(); // the 2nd event pushed the debounce back out

      await vi.advanceTimersByTimeAsync(500); // now 1000ms since the 2nd (and last) event
      expect(api.listMapLayers).toHaveBeenCalledTimes(1);
    });

    it('never reconciles for an unrelated entity riding the same log (mark/drawing deltas)', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { store } = create(api);
      await flush();
      api.listMapLayers.mockClear();

      vi.useFakeTimers();
      push(store, { entity: 'mark', action: 'created', layerId: 'layer-a' });
      await vi.advanceTimersByTimeAsync(1_500);

      expect(api.listMapLayers).not.toHaveBeenCalled();
    });
  });

  describe('CRUD', () => {
    it('create() adopts the response and toasts success', async () => {
      const api = stubApi();
      const { facade, toasts } = create(api);
      await flush();
      const created = layer({ layerId: 'layer-new', name: 'New team' });
      api.createMapLayer.mockResolvedValue(created);

      const result = await facade.create({ name: 'New team', kind: 'TEAM' });

      expect(result).toEqual(created);
      expect(facade.layers().map((l) => l.layerId)).toContain('layer-new');
      expect(toasts.ok).toHaveBeenCalledWith('Created layer "New team".');
    });

    it('create() toasts and returns null on failure', async () => {
      const api = stubApi();
      const { facade, toasts } = create(api);
      await flush();
      api.createMapLayer.mockRejectedValue(new Error('boom'));

      const result = await facade.create({ name: 'x', kind: 'TEAM' });

      expect(result).toBeNull();
      expect(toasts.error).toHaveBeenCalled();
    });

    it('rename() adopts the response', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { facade } = create(api);
      await flush();
      api.renameMapLayer.mockResolvedValue(layer({ name: 'Renamed' }));

      const ok = await facade.rename('layer-a', 'Renamed');

      expect(ok).toBe(true);
      expect(facade.layer('layer-a')?.name).toBe('Renamed');
    });

    it('remove() drops the layer, with no undo offered', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { facade } = create(api);
      await flush();
      api.deleteMapLayer.mockResolvedValue(undefined);

      const ok = await facade.remove('layer-a');

      expect(ok).toBe(true);
      expect(facade.layers()).toEqual([]);
    });

    it('setGrants() adopts the response and toasts success', async () => {
      const api = stubApi({ listMapLayers: vi.fn().mockResolvedValue([layer()]) });
      const { facade, toasts } = create(api);
      await flush();
      const grant: LayerGrant = { subjectType: 'USER', subjectId: 'u1', level: 'VIEW' };
      api.setMapLayerGrants.mockResolvedValue(layer({ grants: [grant] }));

      const ok = await facade.setGrants('layer-a', [grant]);

      expect(ok).toBe(true);
      expect(facade.layer('layer-a')?.grants).toEqual([grant]);
      expect(toasts.ok).toHaveBeenCalledWith('Updated access for "Team".');
    });
  });

  describe('access lookups', () => {
    it('resolve view/contribute/manage/name/cop against the current list', async () => {
      const api = stubApi({
        listMapLayers: vi.fn().mockResolvedValue([layer({ layerId: 'cop', kind: 'COP', myAccess: 'MANAGE' }), layer()]),
      });
      const { facade } = create(api);
      await flush();

      expect(facade.access('layer-a')).toBe('CONTRIBUTE');
      expect(facade.canContributeTo('layer-a')).toBe(true);
      expect(facade.canManageLayer('layer-a')).toBe(false);
      expect(facade.canManageLayer('cop')).toBe(true);
      expect(facade.nameOf('layer-a')).toBe('Team');
      expect(facade.isCop('cop')).toBe(true);
      expect(facade.isCop('layer-a')).toBe(false);
      expect(facade.access('missing')).toBeUndefined();
    });
  });
});
