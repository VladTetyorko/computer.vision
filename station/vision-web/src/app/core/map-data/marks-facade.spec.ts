import { TestBed } from '@angular/core/testing';
import { ROUTER_NAVIGATED } from '@ngrx/router-store';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { MapLayer, MapMark } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveSocketActions } from '../live/state/live.actions';
import { LayersApiActions } from './state/layers.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { provideMarksState } from './state/marks.providers';
import { provideLayersState } from './state/layers.providers';
import { ToastService } from '../toast.service';
import { MarksFacade } from './marks-facade';

/**
 * `MarksFacade` end to end — replaces `marks-store.spec.ts` case for case
 * (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6). The live gate is driven by dispatching real
 * `LiveSocketActions` against the actually-registered `live` slice, the palette-vs-layers
 * reconciliation by seeding the actually-registered `layers` slice, and BUG 3 by dispatching a real
 * `ROUTER_NAVIGATED` (§9 — no stubbed `LiveFacade`/`LayersStore`/`Router` anywhere in this file).
 */

function mark(overrides: Partial<MapMark> = {}): MapMark {
  return {
    markId: 'm1',
    layerId: 'layer-a',
    latitude: 50.45,
    longitude: 30.52,
    kind: 'TARGET',
    affiliation: 'HOSTILE',
    label: 'Bunker',
    createdByUserId: 'u1',
    createdAt: '2026-08-05T10:00:00Z',
    status: 'ACTIVE',
    source: 'MANUAL',
    verification: 'UNVERIFIED',
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
    listMapMarks: vi.fn().mockResolvedValue([]),
    createMapMark: vi.fn(),
    geolocateMapMark: vi.fn(),
    patchMapMark: vi.fn(),
    verifyMapMark: vi.fn(),
    promoteMapMark: vi.fn(),
    deleteMapMark: vi.fn(),
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
      provideMarksState(),
      // `MarksFacade` selects the `layers` slice directly (the palette's default target layer), so
      // that slice has to be registered here too now that neither is root-registered (wave N4).
      provideLayersState(),
      MarksFacade,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { facade: TestBed.inject(MarksFacade), store: TestBed.inject(Store), toasts, scheduler };
}

function seedLayers(store: Store, layers: readonly MapLayer[] = [layer()]): void {
  store.dispatch(LayersApiActions.loaded({ layers }));
}

/** ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: `activate()` stands in for what every real map host does in
 *  its own constructor — every test in this file exercises an already-active facade unless it says
 *  otherwise. */
function create(api: ReturnType<typeof stubApi>, options: { layers?: readonly MapLayer[] } = {}) {
  const context = createInactive(api);
  seedLayers(context.store, options.layers ?? [layer()]);
  context.facade.activate();
  return context;
}

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** `RouterNavigatedAction`'s minimal shape this effect actually reads — `payload.event.urlAfterRedirects`. */
function navigate(store: Store, url: string): void {
  store.dispatch({ type: ROUTER_NAVIGATED, payload: { routerState: {}, event: { urlAfterRedirects: url } } });
}

describe('MarksFacade', () => {
  describe('initial GET + map-topic deltas', () => {
    it('fetches the mark list once on activation', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade } = create(api);
      await flush();
      expect(facade.marks()).toEqual([mark()]);
      expect(facade.loaded()).toBe(true);
    });

    it('marks loaded even when the initial fetch fails, keeping the list empty', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockRejectedValue(new Error('down')) });
      const { facade } = create(api);
      await flush();
      expect(facade.marks()).toEqual([]);
      expect(facade.loaded()).toBe(true);
    });

    it('folds created / cleared deltas and ignores another entity riding the same log', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark({ markId: 'm1' })]) });
      const { facade, store } = create(api);
      await flush();

      store.dispatch(
        LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'map', payload: { entity: 'mark', action: 'created', layerId: 'layer-a', mark: mark({ markId: 'm2' }) } } }),
      );
      store.dispatch(
        LiveSocketActions.envelopeReceived({ envelope: { seq: 2, type: 'map', payload: { entity: 'drawing', action: 'created', layerId: 'layer-a' } } }),
      );
      expect(
        facade
          .marks()
          .map((m) => m.markId)
          .sort(),
      ).toEqual(['m1', 'm2']);

      store.dispatch(
        LiveSocketActions.envelopeReceived({
          envelope: { seq: 3, type: 'map', payload: { entity: 'mark', action: 'cleared', layerId: 'layer-a', mark: mark({ markId: 'm1', status: 'CLEARED' }) } },
        }),
      );
      expect(facade.marks().map((m) => m.markId)).toEqual(['m2']);
    });

    it('a double-delivered CREATED never duplicates the mark (idempotent upsert)', async () => {
      const api = stubApi();
      const { facade, store } = create(api);
      await flush();

      const envelope = { seq: 1, type: 'map' as const, payload: { entity: 'mark' as const, action: 'created' as const, layerId: 'layer-a', mark: mark() } };
      store.dispatch(LiveSocketActions.envelopeReceived({ envelope }));
      store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { ...envelope, seq: 2 } }));

      expect(facade.marks()).toHaveLength(1);
    });

    it('exposes the display projection the map binds to', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade } = create(api);
      await flush();
      expect(facade.displayMarks()[0]).toMatchObject({ id: 'm1', position: { latitude: 50.45, longitude: 30.52 } });
    });
  });

  describe('palette + create-by-map-click', () => {
    it('defaults the palette layer to the first contributable layer', async () => {
      const { facade } = create(stubApi());
      await flush();
      expect(facade.palette()).toMatchObject({ kind: 'TARGET', affiliation: 'HOSTILE', layerId: 'layer-a' });
    });

    it('falls back to no layerId when nothing is writable — the server then picks', async () => {
      const { facade } = create(stubApi(), { layers: [layer({ myAccess: 'VIEW' })] });
      await flush();
      expect(facade.palette().layerId).toBeUndefined();
    });

    it('an unarmed map click is a no-op; an armed one captures a draft and disarms', async () => {
      const { facade } = create(stubApi());
      await flush();

      facade.handleMapClick({ latitude: 1, longitude: 2 });
      expect(facade.draft()).toBeNull();

      facade.setKind('HAZARD');
      facade.setAffiliation('UNKNOWN');
      facade.arm();
      expect(facade.armed()).toBe(true);
      expect(facade.pendingPalette()).toMatchObject({ kind: 'HAZARD', affiliation: 'UNKNOWN' });

      facade.handleMapClick({ latitude: 10, longitude: 20 });
      expect(facade.armed()).toBe(false);
      expect(facade.pendingPalette()).toBeNull();
      expect(facade.draft()).toMatchObject({ position: { latitude: 10, longitude: 20 }, palette: { kind: 'HAZARD' } });
    });

    it('confirmDraft posts the flat wire shape, adopts the response and selects it', async () => {
      const api = stubApi();
      const { facade } = create(api);
      await flush();
      api.createMapMark.mockResolvedValue(mark({ markId: 'm4', kind: 'HAZARD', label: 'Downed line' }));

      facade.setKind('HAZARD');
      facade.setAffiliation('UNKNOWN');
      facade.arm();
      facade.handleMapClick({ latitude: 10, longitude: 20 });
      const created = await facade.confirmDraft('Downed line');

      expect(api.createMapMark).toHaveBeenCalledWith({
        layerId: 'layer-a',
        latitude: 10,
        longitude: 20,
        altitudeMeters: undefined,
        kind: 'HAZARD',
        affiliation: 'UNKNOWN',
        label: 'Downed line',
        note: undefined,
      });
      expect(created).toMatchObject({ markId: 'm4' });
      expect(facade.selectedMarkId()).toBe('m4');
      expect(facade.draft()).toBeNull();
    });

    it('a failed create toasts, keeps the draft and leaves the list untouched', async () => {
      const api = stubApi();
      const { facade, toasts } = create(api);
      await flush();
      api.createMapMark.mockRejectedValue(new Error('boom'));

      facade.arm();
      facade.handleMapClick({ latitude: 1, longitude: 2 });
      expect(await facade.confirmDraft('x')).toBeNull();

      expect(toasts.error).toHaveBeenCalled();
      expect(facade.marks()).toEqual([]);
      expect(facade.draft()).not.toBeNull();
    });
  });

  describe('geolocate', () => {
    it('sends the palette alongside the assetId (§5.2) and selects the result', async () => {
      const api = stubApi();
      const { facade } = create(api);
      await flush();
      api.geolocateMapMark.mockResolvedValue(mark({ markId: 'm3', source: 'DETECTION' }));

      const created = await facade.geolocate('asset-1');

      expect(api.geolocateMapMark).toHaveBeenCalledWith({
        assetId: 'asset-1',
        layerId: 'layer-a',
        kind: 'TARGET',
        affiliation: 'HOSTILE',
      });
      expect(created).toMatchObject({ markId: 'm3' });
      expect(facade.selectedMarkId()).toBe('m3');
    });

    it('toasts on incomplete-telemetry (400) failure and drops no pin', async () => {
      const api = stubApi();
      const { facade, toasts } = create(api);
      await flush();
      api.geolocateMapMark.mockRejectedValue(new Error('telemetry incomplete'));

      expect(await facade.geolocate('asset-1')).toBeNull();
      expect(toasts.error).toHaveBeenCalled();
      expect(facade.marks()).toEqual([]);
    });
  });

  describe('edit / clear / move / delete', () => {
    it('annotate patches kind, affiliation, label and note together', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade } = create(api);
      await flush();
      api.patchMapMark.mockResolvedValue(mark({ label: 'Relabeled' }));

      const ok = await facade.annotate('m1', { kind: 'POI', affiliation: 'NEUTRAL' }, 'Relabeled');

      expect(ok).toBe(true);
      expect(api.patchMapMark).toHaveBeenCalledWith('m1', {
        kind: 'POI',
        affiliation: 'NEUTRAL',
        label: 'Relabeled',
        note: undefined,
      });
      expect(facade.marks()[0].label).toBe('Relabeled');
    });

    it('clear drops the mark from the active list and deselects it', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade } = create(api);
      await flush();
      facade.select('m1');
      api.patchMapMark.mockResolvedValue(mark({ status: 'CLEARED' }));

      expect(await facade.clear('m1')).toBe(true);
      expect(api.patchMapMark).toHaveBeenCalledWith('m1', { status: 'CLEARED' });
      expect(facade.marks()).toEqual([]);
      expect(facade.selectedMarkId()).toBeUndefined();
    });

    it('remove drops the mark; a 403 leaves it in place with a toast', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade, toasts } = create(api);
      await flush();

      api.deleteMapMark.mockRejectedValue(new Error('forbidden'));
      await facade.remove('m1');
      expect(facade.marks()).toEqual([mark()]);
      expect(toasts.error).toHaveBeenCalled();

      api.deleteMapMark.mockResolvedValue(undefined);
      await facade.remove('m1');
      expect(facade.marks()).toEqual([]);
    });
  });

  describe('verify / promote', () => {
    it('verify posts the decision and adopts the stamped mark', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade, toasts } = create(api);
      await flush();
      api.verifyMapMark.mockResolvedValue(mark({ verification: 'CONFIRMED', verifiedByUserId: 'u2' }));

      expect(await facade.verify('m1', 'CONFIRMED')).toBe(true);
      expect(api.verifyMapMark).toHaveBeenCalledWith('m1', { decision: 'CONFIRMED' });
      expect(facade.marks()[0].verification).toBe('CONFIRMED');
      expect(toasts.ok).toHaveBeenCalled();
    });

    it('promote defaults its target (the COP layer) and adopts the moved mark', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade } = create(api);
      await flush();
      api.promoteMapMark.mockResolvedValue(mark({ layerId: 'cop', verification: 'CONFIRMED' }));

      expect(await facade.promote('m1')).toBe(true);
      expect(api.promoteMapMark).toHaveBeenCalledWith('m1', { targetLayerId: undefined });
      expect(facade.marks()[0].layerId).toBe('cop');
    });

    it('a 403 on verify toasts and leaves the mark exactly as it was', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade, toasts } = create(api);
      await flush();
      api.verifyMapMark.mockRejectedValue(new Error('forbidden'));

      expect(await facade.verify('m1', 'CONFIRMED')).toBe(false);
      expect(facade.marks()[0].verification).toBe('UNVERIFIED');
      expect(toasts.error).toHaveBeenCalled();
    });
  });

  describe('selection', () => {
    it('select toggles: selecting the already-selected id deselects', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade } = create(api);
      await flush();

      facade.select('m1');
      expect(facade.selected()).toMatchObject({ markId: 'm1' });
      facade.select('m1');
      expect(facade.selectedMarkId()).toBeUndefined();
    });
  });

  describe('BUG 3: arming/draft/palette reset on a genuine page change, not on a same-page navigation', () => {
    it('leaves an armed mark placement alone across a same-path, query-param-only navigation', async () => {
      const { facade, store } = create(stubApi());
      await flush();

      navigate(store, '/command');
      facade.arm();
      expect(facade.armed()).toBe(true);

      // Mirrors `CommandFacade`'s own `?asset=` URL sync (BUG 4): same path, query-only navigation.
      navigate(store, '/command?asset=abc');
      expect(facade.armed()).toBe(true);
    });

    it('disarms and drops the draft once the path actually changes (the /command → /fly/:assetId repro)', async () => {
      const { facade, store } = create(stubApi());
      await flush();

      navigate(store, '/command');
      facade.arm();
      facade.handleMapClick({ latitude: 1, longitude: 2 });
      expect(facade.armed()).toBe(false); // handleMapClick disarms itself, captures a draft instead
      expect(facade.draft()).not.toBeNull();

      navigate(store, '/fly/asset-1');
      expect(facade.armed()).toBe(false);
      expect(facade.draft()).toBeNull();
    });

    it('resets the palette back to its default on a genuine page change', async () => {
      const { facade, store } = create(stubApi());
      await flush();

      navigate(store, '/command');
      facade.setKind('HAZARD');
      facade.setAffiliation('FRIENDLY');
      expect(facade.palette()).toMatchObject({ kind: 'HAZARD', affiliation: 'FRIENDLY' });

      navigate(store, '/fly/asset-1');
      expect(facade.palette()).toMatchObject({ kind: 'TARGET', affiliation: 'HOSTILE' });
    });
  });

  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { facade, scheduler } = createInactive(api);
      await flush();
      expect(api.listMapMarks).not.toHaveBeenCalled();
      expect(scheduler.schedule).not.toHaveBeenCalled();

      facade.activate();
      await flush();
      expect(api.listMapMarks).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).toHaveBeenCalledTimes(1);
    });

    it('a second concurrent consumer neither re-fetches nor re-schedules', async () => {
      const api = stubApi();
      const { facade, scheduler } = createInactive(api);
      facade.activate();
      await flush();
      facade.activate();
      await flush();
      expect(api.listMapMarks).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).toHaveBeenCalledTimes(1);
    });

    it('stops the poll only once every consumer has released', async () => {
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
    });

    it('reactivating after a full release triggers a fresh fetch', async () => {
      const api = stubApi();
      const { facade } = createInactive(api);
      facade.activate();
      await flush();
      facade.release();
      expect(api.listMapMarks).toHaveBeenCalledTimes(1);

      facade.activate();
      await flush();
      expect(api.listMapMarks).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', async () => {
      const api = stubApi();
      const { facade, scheduler } = createInactive(api);
      expect(() => facade.release()).not.toThrow();
      facade.activate();
      await flush();
      expect(scheduler.schedule).toHaveBeenCalledTimes(1);
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade, store } = createInactive(api);
      facade.activate();
      await flush();

      store.dispatch(LiveSocketActions.opened());
      await flush();
      // (the first entry into live already reconciled once here — not the segment under test)

      store.dispatch(LiveSocketActions.closed());
      await flush();
      api.listMapMarks.mockClear();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      expect(api.listMapMarks).toHaveBeenCalledTimes(1);

      facade.activate();
      store.dispatch(LiveSocketActions.opened());
      await flush();
      expect(api.listMapMarks).toHaveBeenCalledTimes(1);

      facade.release();
    });

    it('a facade that activates while already live does one initial GET, not zero', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade, store, scheduler } = createInactive(api);
      store.dispatch(LiveSocketActions.opened());

      facade.activate();
      await flush();

      expect(api.listMapMarks).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).not.toHaveBeenCalled();
    });

    it('reconciles on the next activate() when an outage began and ended while released', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { facade, store } = createInactive(api);
      facade.activate();
      await flush();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      facade.release();
      api.listMapMarks.mockClear();

      store.dispatch(LiveSocketActions.closed());
      await flush();
      store.dispatch(LiveSocketActions.opened());
      await flush();
      expect(api.listMapMarks).not.toHaveBeenCalled();

      facade.activate();
      await flush();

      expect(api.listMapMarks).toHaveBeenCalledTimes(1);
      facade.release();
    });
  });
});
