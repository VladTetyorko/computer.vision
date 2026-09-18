import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { GeofenceStore } from './geofence-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import type { LiveConnectionState } from '../live/live-fallback-logic';
import type { GeofenceZone, GeofenceZoneEventPayload } from '../api/models';

function zone(partial: Partial<GeofenceZone> = {}): GeofenceZone {
  return {
    id: 'z-1',
    name: 'North perimeter',
    kind: 'KEEP_OUT',
    polygon: [
      { latitude: 1, longitude: 1 },
      { latitude: 2, longitude: 2 },
      { latitude: 3, longitude: 1 },
    ],
    enabled: true,
    ...partial,
  };
}

function stubApi(overrides: Partial<Record<keyof VisionApi, ReturnType<typeof vi.fn>>> = {}) {
  return {
    listGeofences: vi.fn().mockResolvedValue([]),
    createGeofence: vi.fn(),
    updateGeofence: vi.fn(),
    deleteGeofence: vi.fn(),
    ...overrides,
  };
}

function stubScheduler() {
  return { schedule: vi.fn().mockReturnValue(() => undefined) };
}

/** `connectionState` seeded `'closed'` — reproduces today's (pre-D1) behaviour exactly, see
 *  `marks-store.spec.ts`'s identical `stubLiveFacade` doc comment. */
function stubLiveFacade() {
  const events = signal<readonly GeofenceZoneEventPayload[]>([]);
  const connectionState = signal<LiveConnectionState>('closed');
  return {
    zoneEvents: events.asReadonly(),
    push: (incoming: readonly GeofenceZoneEventPayload[]) => events.update((existing) => [...existing, ...incoming]),
    connectionState,
  };
}

function create(api: ReturnType<typeof stubApi>): {
  store: GeofenceStore;
  toasts: { ok: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  undoToast: { showUndo: ReturnType<typeof vi.fn> };
  live: ReturnType<typeof stubLiveFacade>;
} {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn() };
  const undoToast = { showUndo: vi.fn() };
  const live = stubLiveFacade();
  TestBed.configureTestingModule({
    providers: [
      GeofenceStore,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: UndoToastService, useValue: undoToast },
      { provide: PollScheduler, useValue: stubScheduler() },
      { provide: LiveFacade, useValue: live },
    ],
  });
  const store = TestBed.inject(GeofenceStore);
  // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: see `marks-store.spec.ts`'s identical `create()` comment.
  store.activate();
  return { store, toasts, undoToast, live };
}

/** Lets the fire-and-forget promise chain inside the constructor's `refresh()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('GeofenceStore', () => {
  it('fetches the zone list once at construction', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { store } = create(api);
    await flush();
    expect(store.zones()).toEqual([zone()]);
    expect(store.loaded()).toBe(true);
  });

  it('marks loaded even when the initial fetch fails, keeping the list empty', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockRejectedValue(new Error('down')) });
    const { store } = create(api);
    await flush();
    expect(store.zones()).toEqual([]);
    expect(store.loaded()).toBe(true);
  });

  it('create() appends the server response to the list', async () => {
    const api = stubApi();
    const { store } = create(api);
    await flush();
    api.createGeofence.mockResolvedValue(zone({ id: 'z-2', name: 'New zone' }));

    const created = await store.create({ name: 'New zone', kind: 'KEEP_OUT', polygon: zone().polygon, enabled: true });

    expect(created).toMatchObject({ id: 'z-2' });
    expect(store.zones().map((z) => z.id)).toEqual(['z-2']);
  });

  it('create() toasts and returns null on failure, without touching the list', async () => {
    const api = stubApi();
    const { store, toasts } = create(api);
    await flush();
    api.createGeofence.mockRejectedValue(new Error('boom'));

    const created = await store.create({ name: 'x', kind: 'KEEP_OUT', polygon: zone().polygon, enabled: true });

    expect(created).toBeNull();
    expect(store.zones()).toEqual([]);
    expect(toasts.error).toHaveBeenCalled();
  });

  it('rename() resends the full body with only the name changed, and adopts the response', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { store } = create(api);
    await flush();
    const renamed = zone({ name: 'Renamed' });
    api.updateGeofence.mockResolvedValue(renamed);

    const ok = await store.rename(zone(), 'Renamed');

    expect(ok).toBe(true);
    expect(api.updateGeofence).toHaveBeenCalledWith('z-1', {
      name: 'Renamed',
      kind: 'KEEP_OUT',
      polygon: zone().polygon,
      maxAltitudeMeters: undefined,
      enabled: true,
    });
    expect(store.zones()).toEqual([renamed]);
  });

  it('setEnabled() resends the full body with only enabled changed', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { store } = create(api);
    await flush();
    api.updateGeofence.mockResolvedValue(zone({ enabled: false }));

    await store.setEnabled(zone(), false);

    expect(api.updateGeofence).toHaveBeenCalledWith(
      'z-1',
      expect.objectContaining({ enabled: false, name: 'North perimeter' }),
    );
  });

  it('update failure toasts and returns false, leaving the list untouched', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { store, toasts } = create(api);
    await flush();
    api.updateGeofence.mockRejectedValue(new Error('conflict'));

    const ok = await store.setEnabled(zone(), false);

    expect(ok).toBe(false);
    expect(store.zones()).toEqual([zone()]);
    expect(toasts.error).toHaveBeenCalled();
  });

  it('remove() deletes immediately, drops the zone from the list, and offers an undo toast', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { store, undoToast } = create(api);
    await flush();
    api.deleteGeofence.mockResolvedValue(undefined);

    await store.remove(zone());

    expect(api.deleteGeofence).toHaveBeenCalledWith('z-1');
    expect(store.zones()).toEqual([]);
    expect(undoToast.showUndo).toHaveBeenCalledWith('Deleted zone "North perimeter".', expect.any(Function));
  });

  it('remove()\'s undo action re-creates an equivalent zone via the API', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { store, undoToast } = create(api);
    await flush();
    api.deleteGeofence.mockResolvedValue(undefined);
    api.createGeofence.mockResolvedValue(zone({ id: 'z-new' }));

    await store.remove(zone());
    const undoFn = undoToast.showUndo.mock.calls[0][1] as () => void;
    undoFn();
    await flush();

    expect(api.createGeofence).toHaveBeenCalledWith({
      name: 'North perimeter',
      kind: 'KEEP_OUT',
      polygon: zone().polygon,
      maxAltitudeMeters: undefined,
      enabled: true,
    });
    expect(store.zones().map((z) => z.id)).toEqual(['z-new']);
  });

  it('remove() failure toasts and leaves the zone in the list, with no undo offered', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { store, toasts, undoToast } = create(api);
    await flush();
    api.deleteGeofence.mockRejectedValue(new Error('locked'));

    await store.remove(zone());

    expect(store.zones()).toEqual([zone()]);
    expect(toasts.error).toHaveBeenCalled();
    expect(undoToast.showUndo).not.toHaveBeenCalled();
  });

  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    function createInactive(api: ReturnType<typeof stubApi>) {
      const scheduleFn = vi.fn().mockReturnValue(vi.fn());
      const live = stubLiveFacade();
      TestBed.configureTestingModule({
        providers: [
          GeofenceStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn() } },
          { provide: UndoToastService, useValue: { showUndo: vi.fn() } },
          { provide: PollScheduler, useValue: { schedule: scheduleFn } },
          { provide: LiveFacade, useValue: live },
        ],
      });
      return { store: TestBed.inject(GeofenceStore), scheduleFn, live };
    }

    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      await flush();
      expect(api.listGeofences).not.toHaveBeenCalled();
      expect(scheduleFn).not.toHaveBeenCalled();

      store.activate();
      await flush();
      expect(api.listGeofences).toHaveBeenCalledTimes(1);
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
      expect(api.listGeofences).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { store } = createInactive(stubApi());
      expect(() => store.release()).not.toThrow();
    });
  });

  describe('the `zones` SSE topic', () => {
    it('CREATED inserts a new zone the poll has not seen yet', async () => {
      const api = stubApi();
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.push([{ action: 'CREATED', zone: zone({ id: 'z-live', name: 'Live zone' }) }]);
      TestBed.tick();

      expect(store.zones().map((z) => z.id)).toEqual(['z-live']);
    });

    it('UPDATED replaces the existing zone in place, by id', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.push([{ action: 'UPDATED', zone: zone({ name: 'Renamed via live' }) }]);
      TestBed.tick();

      expect(store.zones()).toEqual([zone({ name: 'Renamed via live' })]);
    });

    it('DELETED removes the zone by id', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone(), zone({ id: 'z-2' })]) });
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.push([{ action: 'DELETED', zone: zone() }]);
      TestBed.tick();

      expect(store.zones().map((z) => z.id)).toEqual(['z-2']);
    });

    it('a double-delivered CREATED/UPDATED never duplicates the zone (idempotent upsert)', async () => {
      const api = stubApi();
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.push([{ action: 'CREATED', zone: zone({ id: 'z-live' }) }]);
      TestBed.tick();
      live.push([{ action: 'CREATED', zone: zone({ id: 'z-live' }) }]); // the same event, replayed
      TestBed.tick();

      expect(store.zones().map((z) => z.id)).toEqual(['z-live']);
    });

    it('a double-delivered DELETED is a safe no-op the second time', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { store, live } = create(api);
      await flush();
      TestBed.tick();

      live.push([{ action: 'DELETED', zone: zone() }]);
      TestBed.tick();
      live.push([{ action: 'DELETED', zone: zone() }]); // the same delete, replayed
      TestBed.tick();

      expect(store.zones()).toEqual([]);
    });

    it('runs the fold unconditionally, even with no active consumer', () => {
      const api = stubApi();
      const live = stubLiveFacade();
      TestBed.configureTestingModule({
        providers: [
          GeofenceStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn() } },
          { provide: UndoToastService, useValue: { showUndo: vi.fn() } },
          { provide: PollScheduler, useValue: { schedule: vi.fn().mockReturnValue(vi.fn()) } },
          { provide: LiveFacade, useValue: live },
        ],
      });
      const store = TestBed.inject(GeofenceStore);
      // deliberately never activate() — the fold is documented as running from construction alone.

      live.push([{ action: 'CREATED', zone: zone() }]);
      TestBed.tick();

      expect(store.zones()).toEqual([zone()]);
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    function createInactive(api: ReturnType<typeof stubApi>) {
      const live = stubLiveFacade();
      const scheduleFn = vi.fn().mockReturnValue(vi.fn());
      TestBed.configureTestingModule({
        providers: [
          GeofenceStore,
          { provide: VisionApi, useValue: api },
          { provide: ToastService, useValue: { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn() } },
          { provide: UndoToastService, useValue: { showUndo: vi.fn() } },
          { provide: PollScheduler, useValue: { schedule: scheduleFn } },
          { provide: LiveFacade, useValue: live },
        ],
      });
      return { store: TestBed.inject(GeofenceStore), live, scheduleFn };
    }

    /**
     * The plan's own frozen acceptance criterion (§5): a poll that stops must still reconcile on
     * reconnect. With the store active and live open, driving `connectionState` through
     * `open → closed → open` must issue **exactly one** REST refresh on (re-)entering `open`, and
     * **zero** REST requests for as long as `open` persists.
     */
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
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
      api.listGeofences.mockClear();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      expect(api.listGeofences).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      store.activate(); // a second concurrent consumer while already live — no further request
      TestBed.tick();
      await flush();
      expect(api.listGeofences).toHaveBeenCalledTimes(1);
    });

    /**
     * The companion to the criterion above, and the exact defect wave L1 found in the four
     * `core/map-data/**` stores: with `activeConsumers === 0` this store keeps folding `zones`
     * deltas but receives none during an outage, so a `liveGated` left stale at `true` would send
     * the next `activate()` down `applyTransport`'s `>0 | true | live → nothing` row and skip its
     * reconcile — leaving the operator looking at a zone list missing everything the outage
     * swallowed, on a safety-adjacent layer, with no repair until the next disconnect. The
     * zero-consumer branch clears the flag, which is what makes the final `activate()` re-fetch.
     */
    it('reconciles on the next activate() when an outage began and ended while released', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { store, live } = createInactive(api);
      store.activate();
      await flush();
      TestBed.tick();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      store.release();
      api.listGeofences.mockClear();

      // The whole outage happens with nothing mounted — no deltas are delivered, and a released
      // store must stay silent throughout.
      live.connectionState.set('closed');
      TestBed.tick();
      await flush();
      live.connectionState.set('open');
      TestBed.tick();
      await flush();
      expect(api.listGeofences).not.toHaveBeenCalled();

      store.activate();
      TestBed.tick();
      await flush();

      expect(api.listGeofences).toHaveBeenCalledTimes(1);

      store.release();
    });

    it('a store that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { store, live, scheduleFn } = createInactive(api);
      live.connectionState.set('open');

      store.activate();
      await flush();

      expect(api.listGeofences).toHaveBeenCalledTimes(1);
      expect(scheduleFn).not.toHaveBeenCalled();
    });
  });
});
