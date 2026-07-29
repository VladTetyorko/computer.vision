import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { GeofenceStore } from './geofence-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { PollScheduler } from '../poll-scheduler';
import type { GeofenceZone } from '../api/models';

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

function create(api: ReturnType<typeof stubApi>): {
  store: GeofenceStore;
  toasts: { ok: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  undoToast: { showUndo: ReturnType<typeof vi.fn> };
} {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn() };
  const undoToast = { showUndo: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      GeofenceStore,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: UndoToastService, useValue: undoToast },
      { provide: PollScheduler, useValue: stubScheduler() },
    ],
  });
  return { store: TestBed.inject(GeofenceStore), toasts, undoToast };
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
});
