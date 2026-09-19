import { TestBed } from '@angular/core/testing';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { GeofenceZone } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveSocketActions } from '../live/state/live.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { provideGeofenceState } from './state/geofence.providers';
import { ToastService } from '../toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { GeofenceFacade } from './geofence-facade';

/**
 * `GeofenceFacade` end to end — replaces `geofence-store.spec.ts` case for case
 * (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N6). The live gate is driven by dispatching real
 * `LiveSocketActions` against the actually-registered `live` slice (§9 — effects read `live` only
 * through its own selectors), never by stubbing `LiveFacade`.
 */

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

function stubApi(overrides: Record<string, ReturnType<typeof vi.fn>> = {}) {
  return {
    listGeofences: vi.fn().mockResolvedValue([]),
    createGeofence: vi.fn(),
    updateGeofence: vi.fn(),
    deleteGeofence: vi.fn(),
    ...overrides,
  };
}

function stubScheduler() {
  return { schedule: vi.fn().mockReturnValue(vi.fn()) };
}

function createInactive(api: ReturnType<typeof stubApi>, scheduler = stubScheduler()) {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn() };
  const undoToast = { showUndo: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      provideGeofenceState(),
      GeofenceFacade,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: UndoToastService, useValue: undoToast },
      { provide: PollScheduler, useValue: scheduler },
    ],
  });
  return { facade: TestBed.inject(GeofenceFacade), store: TestBed.inject(Store), toasts, undoToast, scheduler };
}

function create(api: ReturnType<typeof stubApi>) {
  const context = createInactive(api);
  // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3: see `marks-store.spec.ts`'s identical `create()` comment.
  context.facade.activate();
  return context;
}

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('GeofenceFacade', () => {
  it('fetches the zone list once on activation', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { facade } = create(api);
    await flush();
    expect(facade.zones()).toEqual([zone()]);
    expect(facade.loaded()).toBe(true);
  });

  it('marks loaded even when the initial fetch fails, keeping the list empty', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockRejectedValue(new Error('down')) });
    const { facade } = create(api);
    await flush();
    expect(facade.zones()).toEqual([]);
    expect(facade.loaded()).toBe(true);
  });

  it('create() appends the server response to the list', async () => {
    const api = stubApi();
    const { facade } = create(api);
    await flush();
    api.createGeofence.mockResolvedValue(zone({ id: 'z-2', name: 'New zone' }));

    const created = await facade.create({ name: 'New zone', kind: 'KEEP_OUT', polygon: zone().polygon, enabled: true });

    expect(created).toMatchObject({ id: 'z-2' });
    expect(facade.zones().map((z) => z.id)).toEqual(['z-2']);
  });

  it('create() toasts and returns null on failure, without touching the list', async () => {
    const api = stubApi();
    const { facade, toasts } = create(api);
    await flush();
    api.createGeofence.mockRejectedValue(new Error('boom'));

    const created = await facade.create({ name: 'x', kind: 'KEEP_OUT', polygon: zone().polygon, enabled: true });

    expect(created).toBeNull();
    expect(facade.zones()).toEqual([]);
    expect(toasts.error).toHaveBeenCalled();
  });

  it('rename() resends the full body with only the name changed, and adopts the response', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { facade } = create(api);
    await flush();
    const renamed = zone({ name: 'Renamed' });
    api.updateGeofence.mockResolvedValue(renamed);

    const ok = await facade.rename(zone(), 'Renamed');

    expect(ok).toBe(true);
    expect(api.updateGeofence).toHaveBeenCalledWith('z-1', {
      name: 'Renamed',
      kind: 'KEEP_OUT',
      polygon: zone().polygon,
      maxAltitudeMeters: undefined,
      enabled: true,
    });
    expect(facade.zones()).toEqual([renamed]);
  });

  it('setEnabled() resends the full body with only enabled changed', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { facade } = create(api);
    await flush();
    api.updateGeofence.mockResolvedValue(zone({ enabled: false }));

    await facade.setEnabled(zone(), false);

    expect(api.updateGeofence).toHaveBeenCalledWith(
      'z-1',
      expect.objectContaining({ enabled: false, name: 'North perimeter' }),
    );
  });

  it('update failure toasts and returns false, leaving the list untouched', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { facade, toasts } = create(api);
    await flush();
    api.updateGeofence.mockRejectedValue(new Error('conflict'));

    const ok = await facade.setEnabled(zone(), false);

    expect(ok).toBe(false);
    expect(facade.zones()).toEqual([zone()]);
    expect(toasts.error).toHaveBeenCalled();
  });

  it('remove() deletes immediately, drops the zone from the list, and offers an undo toast', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { facade, undoToast } = create(api);
    await flush();
    api.deleteGeofence.mockResolvedValue(undefined);

    await facade.remove(zone());

    expect(api.deleteGeofence).toHaveBeenCalledWith('z-1');
    expect(facade.zones()).toEqual([]);
    expect(undoToast.showUndo).toHaveBeenCalledWith('Deleted zone "North perimeter".', expect.any(Function));
  });

  it("remove()'s undo action re-creates an equivalent zone via the API", async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { facade, undoToast } = create(api);
    await flush();
    api.deleteGeofence.mockResolvedValue(undefined);
    api.createGeofence.mockResolvedValue(zone({ id: 'z-new' }));

    await facade.remove(zone());
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
    expect(facade.zones().map((z) => z.id)).toEqual(['z-new']);
  });

  it('remove() failure toasts and leaves the zone in the list, with no undo offered', async () => {
    const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
    const { facade, toasts, undoToast } = create(api);
    await flush();
    api.deleteGeofence.mockRejectedValue(new Error('locked'));

    await facade.remove(zone());

    expect(facade.zones()).toEqual([zone()]);
    expect(toasts.error).toHaveBeenCalled();
    expect(undoToast.showUndo).not.toHaveBeenCalled();
  });

  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { facade, scheduler } = createInactive(api);
      await flush();
      expect(api.listGeofences).not.toHaveBeenCalled();
      expect(scheduler.schedule).not.toHaveBeenCalled();

      facade.activate();
      await flush();
      expect(api.listGeofences).toHaveBeenCalledTimes(1);
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
      expect(api.listGeofences).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { facade } = createInactive(stubApi());
      expect(() => facade.release()).not.toThrow();
    });
  });

  describe('the `zones` SSE topic', () => {
    let nextSeq = 1;
    function push(store: Store, event: { action: 'CREATED' | 'UPDATED' | 'DELETED'; zone: GeofenceZone }) {
      store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: nextSeq++, type: 'zones', payload: event } }));
    }

    it('CREATED inserts a new zone the poll has not seen yet', async () => {
      const api = stubApi();
      const { facade, store } = create(api);
      await flush();

      push(store, { action: 'CREATED', zone: zone({ id: 'z-live', name: 'Live zone' }) });

      expect(facade.zones().map((z) => z.id)).toEqual(['z-live']);
    });

    it('UPDATED replaces the existing zone in place, by id', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { facade, store } = create(api);
      await flush();

      push(store, { action: 'UPDATED', zone: zone({ name: 'Renamed via live' }) });

      expect(facade.zones()).toEqual([zone({ name: 'Renamed via live' })]);
    });

    it('DELETED removes the zone by id', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone(), zone({ id: 'z-2' })]) });
      const { facade, store } = create(api);
      await flush();

      push(store, { action: 'DELETED', zone: zone() });

      expect(facade.zones().map((z) => z.id)).toEqual(['z-2']);
    });

    it('a double-delivered CREATED never duplicates the zone (idempotent upsert)', async () => {
      const api = stubApi();
      const { facade, store } = create(api);
      await flush();

      push(store, { action: 'CREATED', zone: zone({ id: 'z-live' }) });
      push(store, { action: 'CREATED', zone: zone({ id: 'z-live' }) }); // the same event, replayed

      expect(facade.zones().map((z) => z.id)).toEqual(['z-live']);
    });

    it('a double-delivered DELETED is a safe no-op the second time', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { facade, store } = create(api);
      await flush();

      push(store, { action: 'DELETED', zone: zone() });
      push(store, { action: 'DELETED', zone: zone() }); // the same delete, replayed

      expect(facade.zones()).toEqual([]);
    });

    it('runs the fold unconditionally, even with no active consumer', () => {
      const { facade, store } = createInactive(stubApi());
      // deliberately never activate() — the fold is documented as running from construction alone.

      push(store, { action: 'CREATED', zone: zone() });

      expect(facade.zones()).toEqual([zone()]);
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { facade, store } = createInactive(api);
      facade.activate();
      await flush();

      store.dispatch(LiveSocketActions.opened());
      await flush();
      // (the first entry into live already reconciled once here — not the segment under test)

      store.dispatch(LiveSocketActions.closed());
      await flush();
      api.listGeofences.mockClear();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      expect(api.listGeofences).toHaveBeenCalledTimes(1);

      facade.activate();
      await flush();
      expect(api.listGeofences).toHaveBeenCalledTimes(1);
    });

    it('reconciles on the next activate() when an outage began and ended while released', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { facade, store } = createInactive(api);
      facade.activate();
      await flush();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      facade.release();
      api.listGeofences.mockClear();

      store.dispatch(LiveSocketActions.closed());
      await flush();
      store.dispatch(LiveSocketActions.opened());
      await flush();
      expect(api.listGeofences).not.toHaveBeenCalled();

      facade.activate();
      await flush();

      expect(api.listGeofences).toHaveBeenCalledTimes(1);
      facade.release();
    });

    it('a facade that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listGeofences: vi.fn().mockResolvedValue([zone()]) });
      const { facade, store, scheduler } = createInactive(api);
      store.dispatch(LiveSocketActions.opened());

      facade.activate();
      await flush();

      expect(api.listGeofences).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).not.toHaveBeenCalled();
    });
  });
});
