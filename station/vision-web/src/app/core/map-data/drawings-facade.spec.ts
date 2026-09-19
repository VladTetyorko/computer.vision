import { TestBed } from '@angular/core/testing';
import { ROUTER_NAVIGATED } from '@ngrx/router-store';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { MapDrawingResponse, MapLayer } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveSocketActions } from '../live/state/live.actions';
import { LayersApiActions } from './state/layers.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { LayersFacade } from './layers-facade';
import { provideDrawingsState } from './state/drawings.providers';
import { provideLayersState } from './state/layers.providers';
import { ToastService } from '../toast.service';
import { DrawingsFacade } from './drawings-facade';

/**
 * `DrawingsFacade` end to end — replaces `drawings-store.spec.ts` case for case
 * (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N6), plus new coverage for the CRUD methods and
 * `canEditSelected`/`targetLayerId`, which the old store-level spec never exercised (only
 * `marks-store.spec.ts` did, for its own sibling methods). Live gate driven by dispatching real
 * `LiveSocketActions`; the layer cross-slice reads by seeding the actually-registered `layers` slice.
 */

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
    listMapDrawings: vi.fn().mockResolvedValue([]),
    createMapDrawing: vi.fn(),
    patchMapDrawing: vi.fn(),
    deleteMapDrawing: vi.fn(),
    ...overrides,
  };
}

function stubScheduler() {
  return { schedule: vi.fn().mockReturnValue(vi.fn()) };
}

function createInactive(api: ReturnType<typeof stubApi>, scheduler = stubScheduler()) {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      provideDrawingsState(),
      // `DrawingsFacade` injects `LayersFacade` (the default target layer), and both are
      // page-provided since wave N4 — so this spec must register the pair the route does.
      provideLayersState(),
      DrawingsFacade,
      LayersFacade,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { facade: TestBed.inject(DrawingsFacade), store: TestBed.inject(Store), toasts, scheduler };
}

function seedLayers(store: Store, layers: readonly MapLayer[] = [layer()]): void {
  store.dispatch(LayersApiActions.loaded({ layers }));
}

function create(api: ReturnType<typeof stubApi>, options: { layers?: readonly MapLayer[] } = {}) {
  const context = createInactive(api);
  seedLayers(context.store, options.layers ?? [layer()]);
  context.facade.activate();
  return context;
}

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function navigate(store: Store, url: string): void {
  store.dispatch({ type: ROUTER_NAVIGATED, payload: { routerState: {}, event: { urlAfterRedirects: url } } });
}

describe('DrawingsFacade', () => {
  it('fetches the drawing list once on activation', async () => {
    const { facade } = create(stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) }));
    await flush();
    expect(facade.drawings()).toEqual([drawing()]);
    expect(facade.loaded()).toBe(true);
  });

  it('exposes the display projection the map binds to', async () => {
    const { facade } = create(stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) }));
    await flush();
    expect(facade.displayDrawings()[0]).toMatchObject({ id: 'd1', kind: 'LINE' });
  });

  describe('mode + colour', () => {
    it('setMode toggles: setting the already-active kind turns drawing off', async () => {
      const { facade } = create(stubApi());
      await flush();

      facade.setMode('LINE');
      expect(facade.mode()).toBe('LINE');
      expect(facade.interactionMode()).toBe('draw-line');
      facade.setMode('LINE');
      expect(facade.mode()).toBeNull();
      expect(facade.interactionMode()).toBe('view');
    });

    it('stopDrawing always clears the mode regardless of what was armed', async () => {
      const { facade } = create(stubApi());
      await flush();
      facade.setMode('POLYGON');
      facade.stopDrawing();
      expect(facade.mode()).toBeNull();
    });

    it('setColorToken changes the colour the next drawing gets', async () => {
      const { facade } = create(stubApi());
      await flush();
      expect(facade.colorToken()).toBe('accent');
      facade.setColorToken('danger');
      expect(facade.colorToken()).toBe('danger');
    });
  });

  describe('completeDraft / setDetails / setGeometry / remove', () => {
    it('completeDraft posts on the default layer with the current colour and selects the response', async () => {
      const api = stubApi();
      const { facade } = create(api);
      await flush();
      api.createMapDrawing.mockResolvedValue(drawing({ drawingId: 'd2' }));

      const created = await facade.completeDraft({
        kind: 'LINE',
        points: [
          { latitude: 1, longitude: 2 },
          { latitude: 3, longitude: 4 },
        ],
      });

      expect(api.createMapDrawing).toHaveBeenCalledWith({
        layerId: 'layer-a',
        kind: 'LINE',
        label: undefined,
        colorToken: 'accent',
        points: [
          { latitude: 1, longitude: 2 },
          { latitude: 3, longitude: 4 },
        ],
      });
      expect(created).toMatchObject({ drawingId: 'd2' });
      expect(facade.selectedDrawingId()).toBe('d2');
    });

    it('completeDraft seeds a TEXT drawing with a default label when none is given', async () => {
      const api = stubApi();
      const { facade } = create(api);
      await flush();
      api.createMapDrawing.mockResolvedValue(drawing({ drawingId: 'd3', kind: 'TEXT', label: 'Label' }));

      await facade.completeDraft({ kind: 'TEXT', points: [{ latitude: 1, longitude: 2 }] });

      expect(api.createMapDrawing).toHaveBeenCalledWith(expect.objectContaining({ label: 'Label' }));
    });

    it('a failed completeDraft toasts and leaves the list untouched', async () => {
      const api = stubApi();
      const { facade, toasts } = create(api);
      await flush();
      api.createMapDrawing.mockRejectedValue(new Error('boom'));

      const created = await facade.completeDraft({ kind: 'LINE', points: [{ latitude: 1, longitude: 2 }] });

      expect(created).toBeNull();
      expect(toasts.error).toHaveBeenCalled();
      expect(facade.drawings()).toEqual([]);
    });

    it('setDetails patches label/colour and adopts the response', async () => {
      const api = stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) });
      const { facade } = create(api);
      await flush();
      api.patchMapDrawing.mockResolvedValue(drawing({ label: 'Renamed', colorToken: 'danger' }));

      const ok = await facade.setDetails('d1', { label: 'Renamed', colorToken: 'danger' });

      expect(ok).toBe(true);
      expect(api.patchMapDrawing).toHaveBeenCalledWith('d1', { label: 'Renamed', colorToken: 'danger' });
      expect(facade.drawings()[0]).toMatchObject({ label: 'Renamed', colorToken: 'danger' });
    });

    it('setGeometry replaces the points wholesale', async () => {
      const api = stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) });
      const { facade } = create(api);
      await flush();
      const points = [
        { latitude: 9, longitude: 9 },
        { latitude: 10, longitude: 10 },
      ];
      api.patchMapDrawing.mockResolvedValue(drawing({ points }));

      const ok = await facade.setGeometry('d1', points);

      expect(ok).toBe(true);
      expect(api.patchMapDrawing).toHaveBeenCalledWith('d1', { points });
      expect(facade.drawings()[0].points).toEqual(points);
    });

    it('remove drops the drawing; a 403 leaves it in place with a toast', async () => {
      const api = stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) });
      const { facade, toasts } = create(api);
      await flush();

      api.deleteMapDrawing.mockRejectedValue(new Error('forbidden'));
      await facade.remove('d1');
      expect(facade.drawings()).toEqual([drawing()]);
      expect(toasts.error).toHaveBeenCalled();

      api.deleteMapDrawing.mockResolvedValue(undefined);
      await facade.remove('d1');
      expect(facade.drawings()).toEqual([]);
    });
  });

  describe('selection + canEditSelected/targetLayerId', () => {
    it('select toggles: selecting the already-selected id deselects', async () => {
      const { facade } = create(stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) }));
      await flush();

      facade.select('d1');
      expect(facade.selected()).toMatchObject({ drawingId: 'd1' });
      facade.select('d1');
      expect(facade.selectedDrawingId()).toBeUndefined();
    });

    it('canEditSelected is false with nothing selected, true once a contributable drawing is', async () => {
      const { facade } = create(stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) }));
      await flush();
      expect(facade.canEditSelected()).toBe(false);

      facade.select('d1');
      expect(facade.canEditSelected()).toBe(true);
    });

    it('canEditSelected is false for a drawing on a layer the viewer cannot contribute to', async () => {
      const { facade } = create(stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing({ layerId: 'layer-b' })]) }), {
        layers: [layer({ layerId: 'layer-b', myAccess: 'VIEW' })],
      });
      await flush();
      facade.select('d1');
      expect(facade.canEditSelected()).toBe(false);
    });

    it('targetLayerId mirrors the layers slice default', async () => {
      const { facade } = create(stubApi());
      await flush();
      expect(facade.targetLayerId()).toBe('layer-a');
    });
  });

  describe('BUG 3: mode disarms on a genuine page change, not on a same-page navigation', () => {
    it('leaves an armed draw mode alone across a same-path, query-param-only navigation', async () => {
      const { facade, store } = create(stubApi());
      await flush();

      navigate(store, '/command');
      facade.setMode('LINE');
      expect(facade.mode()).toBe('LINE');

      navigate(store, '/command?asset=abc');
      expect(facade.mode()).toBe('LINE');
    });

    it('disarms an armed draw mode once the path actually changes (the /command → /fly/:assetId repro)', async () => {
      const { facade, store } = create(stubApi());
      await flush();

      navigate(store, '/command');
      facade.setMode('POLYGON');
      expect(facade.mode()).toBe('POLYGON');

      navigate(store, '/fly/asset-1');
      expect(facade.mode()).toBeNull();
    });
  });

  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { facade, scheduler } = createInactive(api);
      await flush();
      expect(api.listMapDrawings).not.toHaveBeenCalled();
      expect(scheduler.schedule).not.toHaveBeenCalled();

      facade.activate();
      await flush();
      expect(api.listMapDrawings).toHaveBeenCalledTimes(1);
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
      expect(api.listMapDrawings).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { facade } = createInactive(stubApi());
      expect(() => facade.release()).not.toThrow();
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) });
      const { facade, store } = createInactive(api);
      facade.activate();
      await flush();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      store.dispatch(LiveSocketActions.closed());
      await flush();
      api.listMapDrawings.mockClear();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      expect(api.listMapDrawings).toHaveBeenCalledTimes(1);

      facade.activate();
      store.dispatch(LiveSocketActions.opened());
      await flush();
      expect(api.listMapDrawings).toHaveBeenCalledTimes(1);
      facade.release();
    });

    it('a facade that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listMapDrawings: vi.fn().mockResolvedValue([drawing()]) });
      const { facade, store, scheduler } = createInactive(api);
      store.dispatch(LiveSocketActions.opened());

      facade.activate();
      await flush();

      expect(api.listMapDrawings).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).not.toHaveBeenCalled();
    });
  });
});
