import { TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { MarksStore } from './marks-store';
import { LayersStore } from './layers-store';
import { contributableLayers, defaultContributeLayerId } from './layers-logic';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import type { LiveConnectionState } from '../live/live-fallback-logic';
import type { MapEventPayload, MapLayer, MapMark } from '../api/models';

/** Routed stand-in for BUG 3's `resetOnRouteChange` coverage — `create()` always provides a router now. */
@Component({ selector: 'vision-test-stub-page', template: '' })
class StubPage {}

/**
 * Store-level coverage carried over from the deleted `core/marks/marks-store.spec.ts` and extended
 * for v2 (docs/plans/done/MAP-REWORK-PLAN.md §5.2): the initial GET + `map`-topic fold, the palette's
 * arm→click→confirm flow, verify/promote, and the error paths that must leave the list untouched.
 * The pure reducers themselves live in `mark-logic.spec.ts`; this file exercises the wiring only.
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

/**
 * Real Angular signals so the store's own `effect()`s react exactly as they would to the real
 * `LiveFacade`. `connectionState` seeded `'closed'` — reproduces today's (pre-D1) behaviour exactly,
 * so every existing assertion in this file stays green untouched
 * (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L1b); tests that care about the live gate drive
 * it explicitly via `connectionState.set(...)`.
 */
function stubLiveFacade() {
  const events = signal<readonly MapEventPayload[]>([]);
  const connectionState = signal<LiveConnectionState>('closed');
  return {
    mapEvents: events.asReadonly(),
    /** Appends, oldest-first — mirrors `LiveFacade.mapEvents`'s own accumulation contract. */
    push: (incoming: readonly MapEventPayload[]) => events.update((existing) => [...existing, ...incoming]),
    connectionState,
  };
}

/**
 * Only the surface `MarksStore` actually reads off `LayersStore`. Both derived values go through the
 * same pure helpers the real store uses, so this fake can't quietly disagree with it about which
 * layers are writable.
 */
function stubLayersStore(layers: readonly MapLayer[] = [layer()]) {
  const list = signal(layers);
  return {
    contributable: signal(contributableLayers(layers)).asReadonly(),
    defaultLayerId: signal(defaultContributeLayerId(layers)).asReadonly(),
    layers: list.asReadonly(),
    canManageLayer: (layerId: string | undefined) =>
      layers.some((l) => l.layerId === layerId && l.myAccess === 'MANAGE'),
  };
}

function create(api: ReturnType<typeof stubApi>, options: { layers?: readonly MapLayer[] } = {}) {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
  const live = stubLiveFacade();
  TestBed.configureTestingModule({
    providers: [
      MarksStore,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: PollScheduler, useValue: { schedule: vi.fn().mockReturnValue(() => undefined) } },
      { provide: LiveFacade, useValue: live },
      { provide: LayersStore, useValue: stubLayersStore(options.layers) },
      provideRouter([
        { path: 'command', component: StubPage },
        { path: 'fly/:assetId', component: StubPage },
      ]),
    ],
  });
  const store = TestBed.inject(MarksStore);
  // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: `refresh()`/the poll now only start once something calls
  // `activate()` (see `MarksStore`'s own doc comment) — every test in this file exercises an
  // already-active store, mirroring what every real consumer does in its own constructor, so this
  // one call here stands in for all of them rather than repeating it at every call site.
  store.activate();
  return { store, toasts, live };
}

/** Lets the fire-and-forget promise chain inside the constructor's `refresh()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('MarksStore', () => {
  describe('initial GET + map-topic deltas', () => {
    it('fetches the mark list once at construction', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store } = create(api);
      await flush();
      expect(store.marks()).toEqual([mark()]);
      expect(store.loaded()).toBe(true);
    });

    it('marks loaded even when the initial fetch fails, keeping the list empty', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockRejectedValue(new Error('down')) });
      const { store } = create(api);
      await flush();
      expect(store.marks()).toEqual([]);
      expect(store.loaded()).toBe(true);
    });

    it('folds created / cleared deltas and ignores another entity riding the same log', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark({ markId: 'm1' })]) });
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.push([
        { entity: 'mark', action: 'created', layerId: 'layer-a', mark: mark({ markId: 'm2' }) },
        { entity: 'drawing', action: 'created', layerId: 'layer-a' },
      ]);
      TestBed.tick();
      expect(store.marks().map((m) => m.markId).sort()).toEqual(['m1', 'm2']);

      live.push([{ entity: 'mark', action: 'cleared', layerId: 'layer-a', mark: mark({ markId: 'm1', status: 'CLEARED' }) }]);
      TestBed.tick();
      expect(store.marks().map((m) => m.markId)).toEqual(['m2']);
    });

    it('never reprocesses an already-folded delta on a later, unrelated tick', async () => {
      const api = stubApi();
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.push([{ entity: 'mark', action: 'created', layerId: 'layer-a', mark: mark() }]);
      TestBed.tick();
      expect(store.marks()).toHaveLength(1);

      TestBed.tick();
      expect(store.marks()).toHaveLength(1);
    });

    it('exposes the display projection the map binds to', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store } = create(api);
      await flush();
      expect(store.displayMarks()[0]).toMatchObject({ id: 'm1', position: { latitude: 50.45, longitude: 30.52 } });
    });
  });

  describe('palette + create-by-map-click', () => {
    it('defaults the palette layer to the first contributable layer', async () => {
      const { store } = create(stubApi());
      await flush();
      TestBed.tick();
      expect(store.palette()).toMatchObject({ kind: 'TARGET', affiliation: 'HOSTILE', layerId: 'layer-a' });
    });

    it('falls back to no layerId when nothing is writable — the server then picks', async () => {
      const { store } = create(stubApi(), { layers: [layer({ myAccess: 'VIEW' })] });
      await flush();
      TestBed.tick();
      expect(store.palette().layerId).toBeUndefined();
    });

    it('an unarmed map click is a no-op; an armed one captures a draft and disarms', async () => {
      const { store } = create(stubApi());
      await flush();

      store.handleMapClick({ latitude: 1, longitude: 2 });
      expect(store.draft()).toBeNull();

      store.setKind('HAZARD');
      store.setAffiliation('UNKNOWN');
      store.arm();
      expect(store.armed()).toBe(true);
      expect(store.pendingPalette()).toMatchObject({ kind: 'HAZARD', affiliation: 'UNKNOWN' });

      store.handleMapClick({ latitude: 10, longitude: 20 });
      expect(store.armed()).toBe(false);
      expect(store.pendingPalette()).toBeNull();
      expect(store.draft()).toMatchObject({ position: { latitude: 10, longitude: 20 }, palette: { kind: 'HAZARD' } });
    });

    it('confirmDraft posts the flat wire shape, adopts the response and selects it', async () => {
      const api = stubApi();
      const { store } = create(api);
      await flush();
      TestBed.tick();
      api.createMapMark.mockResolvedValue(mark({ markId: 'm4', kind: 'HAZARD', label: 'Downed line' }));

      store.setKind('HAZARD');
      store.setAffiliation('UNKNOWN');
      store.arm();
      store.handleMapClick({ latitude: 10, longitude: 20 });
      const created = await store.confirmDraft('Downed line');

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
      expect(store.selectedMarkId()).toBe('m4');
      expect(store.draft()).toBeNull();
    });

    it('a failed create toasts, keeps the draft and leaves the list untouched', async () => {
      const api = stubApi();
      const { store, toasts } = create(api);
      await flush();
      api.createMapMark.mockRejectedValue(new Error('boom'));

      store.arm();
      store.handleMapClick({ latitude: 1, longitude: 2 });
      expect(await store.confirmDraft('x')).toBeNull();

      expect(toasts.error).toHaveBeenCalled();
      expect(store.marks()).toEqual([]);
      expect(store.draft()).not.toBeNull();
    });
  });

  describe('geolocate', () => {
    it('sends the palette alongside the assetId (§5.2) and selects the result', async () => {
      const api = stubApi();
      const { store } = create(api);
      await flush();
      TestBed.tick();
      api.geolocateMapMark.mockResolvedValue(mark({ markId: 'm3', source: 'DETECTION' }));

      const created = await store.geolocate('asset-1');

      expect(api.geolocateMapMark).toHaveBeenCalledWith({
        assetId: 'asset-1',
        layerId: 'layer-a',
        kind: 'TARGET',
        affiliation: 'HOSTILE',
      });
      expect(created).toMatchObject({ markId: 'm3' });
      expect(store.selectedMarkId()).toBe('m3');
    });

    it('toasts on incomplete-telemetry (400) failure and drops no pin', async () => {
      const api = stubApi();
      const { store, toasts } = create(api);
      await flush();
      api.geolocateMapMark.mockRejectedValue(new Error('telemetry incomplete'));

      expect(await store.geolocate('asset-1')).toBeNull();
      expect(toasts.error).toHaveBeenCalled();
      expect(store.marks()).toEqual([]);
    });
  });

  describe('edit / clear / move / delete', () => {
    it('annotate patches kind, affiliation, label and note together', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store } = create(api);
      await flush();
      api.patchMapMark.mockResolvedValue(mark({ label: 'Relabeled' }));

      const ok = await store.annotate('m1', { kind: 'POI', affiliation: 'NEUTRAL' }, 'Relabeled');

      expect(ok).toBe(true);
      expect(api.patchMapMark).toHaveBeenCalledWith('m1', {
        kind: 'POI',
        affiliation: 'NEUTRAL',
        label: 'Relabeled',
        note: undefined,
      });
      expect(store.marks()[0].label).toBe('Relabeled');
    });

    it('clear drops the mark from the active list and deselects it', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store } = create(api);
      await flush();
      store.select('m1');
      api.patchMapMark.mockResolvedValue(mark({ status: 'CLEARED' }));

      expect(await store.clear('m1')).toBe(true);
      expect(api.patchMapMark).toHaveBeenCalledWith('m1', { status: 'CLEARED' });
      expect(store.marks()).toEqual([]);
      expect(store.selectedMarkId()).toBeUndefined();
    });

    it('remove drops the mark; a 403 leaves it in place with a toast', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store, toasts } = create(api);
      await flush();

      api.deleteMapMark.mockRejectedValue(new Error('forbidden'));
      await store.remove('m1');
      expect(store.marks()).toEqual([mark()]);
      expect(toasts.error).toHaveBeenCalled();

      api.deleteMapMark.mockResolvedValue(undefined);
      await store.remove('m1');
      expect(store.marks()).toEqual([]);
    });
  });

  describe('verify / promote', () => {
    it('verify posts the decision and adopts the stamped mark', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store, toasts } = create(api);
      await flush();
      api.verifyMapMark.mockResolvedValue(mark({ verification: 'CONFIRMED', verifiedByUserId: 'u2' }));

      expect(await store.verify('m1', 'CONFIRMED')).toBe(true);
      expect(api.verifyMapMark).toHaveBeenCalledWith('m1', { decision: 'CONFIRMED' });
      expect(store.marks()[0].verification).toBe('CONFIRMED');
      expect(toasts.ok).toHaveBeenCalled();
    });

    it('promote defaults its target (the COP layer) and adopts the moved mark', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store } = create(api);
      await flush();
      api.promoteMapMark.mockResolvedValue(mark({ layerId: 'cop', verification: 'CONFIRMED' }));

      expect(await store.promote('m1')).toBe(true);
      expect(api.promoteMapMark).toHaveBeenCalledWith('m1', { targetLayerId: undefined });
      expect(store.marks()[0].layerId).toBe('cop');
    });

    it('a 403 on verify toasts and leaves the mark exactly as it was', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store, toasts } = create(api);
      await flush();
      api.verifyMapMark.mockRejectedValue(new Error('forbidden'));

      expect(await store.verify('m1', 'CONFIRMED')).toBe(false);
      expect(store.marks()[0].verification).toBe('UNVERIFIED');
      expect(toasts.error).toHaveBeenCalled();
    });
  });

  describe('selection', () => {
    it('select toggles: selecting the already-selected id deselects', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store } = create(api);
      await flush();

      store.select('m1');
      expect(store.selected()).toMatchObject({ markId: 'm1' });
      store.select('m1');
      expect(store.selectedMarkId()).toBeUndefined();
    });
  });

  describe('BUG 3: arming/draft/palette reset on a genuine page change, not on a same-page navigation', () => {
    it('leaves an armed mark placement alone across a same-path, query-param-only navigation', async () => {
      const { store } = create(stubApi());
      await flush();
      const router = TestBed.inject(Router);

      await router.navigateByUrl('/command');
      store.arm();
      expect(store.armed()).toBe(true);

      // Mirrors `CommandFacade`'s own `?asset=` URL sync (BUG 4): same path, query-only navigation.
      await router.navigateByUrl('/command?asset=abc');
      expect(store.armed()).toBe(true);
    });

    it('disarms and drops the draft once the path actually changes (the /command → /fly/:assetId repro)', async () => {
      const { store } = create(stubApi());
      await flush();
      const router = TestBed.inject(Router);

      await router.navigateByUrl('/command');
      store.arm();
      store.handleMapClick({ latitude: 1, longitude: 2 });
      expect(store.armed()).toBe(false); // handleMapClick disarms itself, captures a draft instead
      expect(store.draft()).not.toBeNull();

      await router.navigateByUrl('/fly/asset-1');
      expect(store.armed()).toBe(false);
      expect(store.draft()).toBeNull();
    });

    it('resets the palette back to its default on a genuine page change', async () => {
      const { store } = create(stubApi());
      await flush();
      const router = TestBed.inject(Router);

      await router.navigateByUrl('/command');
      store.setKind('HAZARD');
      store.setAffiliation('FRIENDLY');
      expect(store.palette()).toMatchObject({ kind: 'HAZARD', affiliation: 'FRIENDLY' });

      await router.navigateByUrl('/fly/asset-1');
      expect(store.palette()).toMatchObject({ kind: 'TARGET', affiliation: 'HOSTILE' });
    });
  });

  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    /** Bypasses the shared `create()` helper's own `activate()` call — these tests need to observe
     *  the pre-activation state, which every other test in this file deliberately skips past. */
    function createInactive(api: ReturnType<typeof stubApi>) {
      const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
      const live = stubLiveFacade();
      const scheduleFn = vi.fn().mockReturnValue(vi.fn());
      TestBed.configureTestingModule({
        providers: [
          MarksStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: toasts },
          { provide: PollScheduler, useValue: { schedule: scheduleFn } },
          { provide: LiveFacade, useValue: live },
          { provide: LayersStore, useValue: stubLayersStore() },
          provideRouter([{ path: 'command', component: StubPage }]),
        ],
      });
      return { store: TestBed.inject(MarksStore), scheduleFn };
    }

    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      await flush();
      expect(api.listMapMarks).not.toHaveBeenCalled();
      expect(scheduleFn).not.toHaveBeenCalled();

      store.activate();
      await flush();
      expect(api.listMapMarks).toHaveBeenCalledTimes(1);
      expect(scheduleFn).toHaveBeenCalledTimes(1);
    });

    it('a second concurrent consumer neither re-fetches nor re-schedules', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      store.activate();
      await flush();
      store.activate();
      await flush();
      expect(api.listMapMarks).toHaveBeenCalledTimes(1);
      expect(scheduleFn).toHaveBeenCalledTimes(1);
    });

    it('stops the poll only once every consumer has released', async () => {
      const api = stubApi();
      const stopFn = vi.fn();
      const scheduleFn = vi.fn().mockReturnValue(stopFn);
      const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
      TestBed.configureTestingModule({
        providers: [
          MarksStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: toasts },
          { provide: PollScheduler, useValue: { schedule: scheduleFn } },
          { provide: LiveFacade, useValue: stubLiveFacade() },
          { provide: LayersStore, useValue: stubLayersStore() },
          provideRouter([{ path: 'command', component: StubPage }]),
        ],
      });
      const store = TestBed.inject(MarksStore);

      store.activate();
      store.activate();
      await flush();
      store.release();
      expect(stopFn).not.toHaveBeenCalled();
      store.release();
      expect(stopFn).toHaveBeenCalledTimes(1);
    });

    it('reactivating after a full release triggers a fresh fetch', async () => {
      const api = stubApi();
      const { store } = createInactive(api);
      store.activate();
      await flush();
      store.release();
      expect(api.listMapMarks).toHaveBeenCalledTimes(1);

      store.activate();
      await flush();
      expect(api.listMapMarks).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      expect(() => store.release()).not.toThrow();
      store.activate();
      await flush();
      expect(scheduleFn).toHaveBeenCalledTimes(1);
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
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      // Reach a known "active, live open" baseline first — this transition's own reconcile fetch is
      // not what's under test.
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
      api.listMapMarks.mockClear();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      expect(api.listMapMarks).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      // Zero further REST requests while 'open' persists — a second concurrent consumer activating
      // (row ">0, true, live -> nothing") and an equal-value re-write (signals don't re-notify on an
      // equal write) must both be no-ops.
      store.activate();
      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      expect(api.listMapMarks).toHaveBeenCalledTimes(1);

      store.release();
    });

    it('a store that activates while already live does one initial GET, not zero', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
      const live = stubLiveFacade();
      live.connectionState.set('open');
      const scheduleFn = vi.fn().mockReturnValue(vi.fn());
      TestBed.configureTestingModule({
        providers: [
          MarksStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: toasts },
          { provide: PollScheduler, useValue: { schedule: scheduleFn } },
          { provide: LiveFacade, useValue: live },
          { provide: LayersStore, useValue: stubLayersStore() },
          provideRouter([{ path: 'command', component: StubPage }]),
        ],
      });
      const store = TestBed.inject(MarksStore);

      store.activate();
      await flush();

      expect(api.listMapMarks).toHaveBeenCalledTimes(1);
      expect(scheduleFn).not.toHaveBeenCalled(); // no safety-net poll needed — live is already open
    });

    /**
     * The gap the D1 table does not describe, and the reason `applyTransport`'s zero-consumer
     * branch clears `liveGated` rather than only stopping the poll.
     *
     * A live outage that begins *and ends* while nothing is mounted delivers no deltas and leaves
     * no trace: the unconditional fold never runs (there was nothing on the wire), and the poll is
     * legitimately stopped. If the store still remembered "we were live", the next `activate()`
     * would take the `>0 | true | live → nothing` row and skip its reconcile, leaving the operator
     * looking at data missing everything the outage swallowed, with no repair until the *next*
     * disconnect. Clearing the flag on deactivate also restores `activate()`'s own documented
     * "first consumer since the last release re-fetches" contract.
     */
    it('reconciles on the next activate() when an outage began and ended while released', async () => {
      const api = stubApi({ listMapMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      store.release();
      api.listMapMarks.mockClear();

      // The whole outage happens with nothing mounted — no deltas are delivered, and a released
      // store must stay silent throughout.
      live.connectionState.set('closed');
      TestBed.tick();
      await flush();
      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      expect(api.listMapMarks).not.toHaveBeenCalled();

      store.activate();
      TestBed.tick();
      await flush();

      expect(api.listMapMarks).toHaveBeenCalledTimes(1);

      store.release();
    });

  });
});
