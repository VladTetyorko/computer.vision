import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { MarksStore } from './marks-store';
import { VisionApi } from '../api/vision-api';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import type { Mark, MarkEvent } from '../api/models';

function mark(partial: Partial<Mark> = {}): Mark {
  return {
    id: 'm-1',
    kind: 'TARGET',
    label: 'Bunker',
    position: { latitude: 50.45, longitude: 30.52 },
    createdBy: 'u-1',
    createdAt: '2026-07-31T10:00:00Z',
    status: 'ACTIVE',
    source: 'MANUAL',
    ...partial,
  };
}

function stubApi(overrides: Partial<Record<keyof VisionApi, ReturnType<typeof vi.fn>>> = {}) {
  return {
    listMarks: vi.fn().mockResolvedValue([]),
    createMark: vi.fn(),
    geolocateMark: vi.fn(),
    patchMark: vi.fn(),
    deleteMark: vi.fn(),
    ...overrides,
  };
}

function stubScheduler() {
  return { schedule: vi.fn().mockReturnValue(() => undefined) };
}

/** Real Angular signal so `MarksStore`'s own `effect()` reacts to it exactly as it would to the real `LiveStore` — mirrors `events-store.spec.ts#stubLiveStore`. */
function stubLiveStore() {
  const markEventsSignal = signal<readonly MarkEvent[]>([]);
  return {
    markEvents: markEventsSignal.asReadonly(),
    /** Appends, oldest-first — mirrors the real `LiveStore.markEvents`'s own accumulation contract. */
    pushMarkEvents: (events: readonly MarkEvent[]) => markEventsSignal.update((existing) => [...existing, ...events]),
  };
}

function create(
  api: ReturnType<typeof stubApi>,
  live: ReturnType<typeof stubLiveStore> = stubLiveStore(),
): {
  store: MarksStore;
  toasts: { ok: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  live: ReturnType<typeof stubLiveStore>;
} {
  const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn(), notify: vi.fn(), warn: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      MarksStore,
      { provide: VisionApi, useValue: api },
      { provide: ToastService, useValue: toasts },
      { provide: PollScheduler, useValue: stubScheduler() },
      { provide: LiveStore, useValue: live },
    ],
  });
  return { store: TestBed.inject(MarksStore), toasts, live };
}

/** Lets the fire-and-forget promise chain inside the constructor's `refresh()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('MarksStore', () => {
  describe('initial GET + live-delta merge', () => {
    it('fetches the mark list once at construction', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark()]) });
      const { store } = create(api);
      await flush();
      expect(store.marks()).toEqual([mark()]);
      expect(store.loaded()).toBe(true);
    });

    it('marks loaded even when the initial fetch fails, keeping the list empty', async () => {
      const api = stubApi({ listMarks: vi.fn().mockRejectedValue(new Error('down')) });
      const { store } = create(api);
      await flush();
      expect(store.marks()).toEqual([]);
      expect(store.loaded()).toBe(true);
    });

    it('a live "created" delta upserts on top of the initial GET', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store, live } = create(api);
      await flush();

      TestBed.tick();
      live.pushMarkEvents([{ action: 'created', mark: mark({ id: 'm-2', label: 'New target' }) }]);
      TestBed.tick();

      expect(store.marks().map((m) => m.id).sort()).toEqual(['m-1', 'm-2']);
    });

    it('a live "updated" delta replaces the matching mark', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1', label: 'Original' })]) });
      const { store, live } = create(api);
      await flush();

      TestBed.tick();
      live.pushMarkEvents([{ action: 'updated', mark: mark({ id: 'm-1', label: 'Renamed' }) }]);
      TestBed.tick();

      expect(store.marks()).toEqual([mark({ id: 'm-1', label: 'Renamed' })]);
    });

    it('a live "cleared" delta removes the mark — the pin drops', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store, live } = create(api);
      await flush();

      TestBed.tick();
      live.pushMarkEvents([{ action: 'cleared', mark: mark({ id: 'm-1', status: 'CLEARED' }) }]);
      TestBed.tick();

      expect(store.marks()).toEqual([]);
    });

    it('never reprocesses an already-folded delta on a later, unrelated signal read', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([]) });
      const { store, live } = create(api);
      await flush();

      TestBed.tick();
      live.pushMarkEvents([{ action: 'created', mark: mark({ id: 'm-1' }) }]);
      TestBed.tick();
      expect(store.marks()).toEqual([mark({ id: 'm-1' })]);

      // A second, unrelated tick with no new events must not re-add/duplicate anything.
      TestBed.tick();
      expect(store.marks()).toEqual([mark({ id: 'm-1' })]);
    });
  });

  describe('create / geolocate', () => {
    it('create() upserts the server response and selects it', async () => {
      const api = stubApi();
      const { store } = create(api);
      await flush();
      api.createMark.mockResolvedValue(mark({ id: 'm-2' }));

      const created = await store.create({ kind: 'POI', label: 'New', position: { latitude: 1, longitude: 2 } });

      expect(created).toMatchObject({ id: 'm-2' });
      expect(store.marks().map((m) => m.id)).toEqual(['m-2']);
      expect(store.selectedMarkId()).toBe('m-2');
    });

    it('create() toasts and returns null on failure, without touching the list', async () => {
      const api = stubApi();
      const { store, toasts } = create(api);
      await flush();
      api.createMark.mockRejectedValue(new Error('boom'));

      const created = await store.create({ kind: 'POI', label: 'x', position: { latitude: 1, longitude: 2 } });

      expect(created).toBeNull();
      expect(store.marks()).toEqual([]);
      expect(toasts.error).toHaveBeenCalled();
    });

    it('geolocate() sends only assetId by default and upserts+selects the response', async () => {
      const api = stubApi();
      const { store } = create(api);
      await flush();
      api.geolocateMark.mockResolvedValue(mark({ id: 'm-3', source: 'DETECTION' }));

      const created = await store.geolocate('asset-1');

      expect(api.geolocateMark).toHaveBeenCalledWith({ assetId: 'asset-1', kind: undefined, label: undefined });
      expect(created).toMatchObject({ id: 'm-3' });
      expect(store.selectedMarkId()).toBe('m-3');
    });

    it('geolocate() toasts on incomplete-telemetry (400) failure', async () => {
      const api = stubApi();
      const { store, toasts } = create(api);
      await flush();
      api.geolocateMark.mockRejectedValue(new Error('telemetry incomplete'));

      const created = await store.geolocate('asset-1');

      expect(created).toBeNull();
      expect(toasts.error).toHaveBeenCalled();
    });
  });

  describe('create-by-map-click draft flow', () => {
    it('beginPlacement arms a kind; an unrelated map click before any placement is a no-op', async () => {
      const api = stubApi();
      const { store } = create(api);
      await flush();

      store.handleMapClick({ latitude: 1, longitude: 2 });

      expect(store.draft()).toBeNull();
    });

    it('handleMapClick captures the draft and disarms', async () => {
      const api = stubApi();
      const { store } = create(api);
      await flush();

      store.beginPlacement('HAZARD');
      expect(store.pendingKind()).toBe('HAZARD');

      store.handleMapClick({ latitude: 10, longitude: 20 });

      expect(store.pendingKind()).toBeNull();
      expect(store.draft()).toEqual({ kind: 'HAZARD', position: { latitude: 10, longitude: 20 } });
    });

    it('confirmDraft creates the mark and clears the draft', async () => {
      const api = stubApi();
      const { store } = create(api);
      await flush();
      api.createMark.mockResolvedValue(mark({ id: 'm-4', kind: 'HAZARD', label: 'Downed line' }));
      store.beginPlacement('HAZARD');
      store.handleMapClick({ latitude: 10, longitude: 20 });

      const created = await store.confirmDraft('Downed line');

      expect(api.createMark).toHaveBeenCalledWith({
        kind: 'HAZARD',
        label: 'Downed line',
        note: undefined,
        position: { latitude: 10, longitude: 20 },
      });
      expect(created).toMatchObject({ id: 'm-4' });
      expect(store.draft()).toBeNull();
    });

    it('cancelDraft/cancelPlacement clear their respective state without any API call', async () => {
      const api = stubApi();
      const { store } = create(api);
      await flush();

      store.beginPlacement('TARGET');
      store.cancelPlacement();
      expect(store.pendingKind()).toBeNull();

      store.beginPlacement('TARGET');
      store.handleMapClick({ latitude: 1, longitude: 1 });
      store.cancelDraft();
      expect(store.draft()).toBeNull();
      expect(api.createMark).not.toHaveBeenCalled();
    });
  });

  describe('selection', () => {
    it('select() toggles: selecting the already-selected id deselects', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store } = create(api);
      await flush();

      store.select('m-1');
      expect(store.selectedMarkId()).toBe('m-1');
      expect(store.selected()).toEqual(mark({ id: 'm-1' }));

      store.select('m-1');
      expect(store.selectedMarkId()).toBeUndefined();
    });
  });

  describe('annotate / clear / moveTo / remove', () => {
    it('annotate() patches and upserts the response', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store } = create(api);
      await flush();
      api.patchMark.mockResolvedValue(mark({ id: 'm-1', label: 'Relabeled' }));

      const ok = await store.annotate('m-1', { label: 'Relabeled' });

      expect(ok).toBe(true);
      expect(api.patchMark).toHaveBeenCalledWith('m-1', { label: 'Relabeled' });
      expect(store.marks()).toEqual([mark({ id: 'm-1', label: 'Relabeled' })]);
    });

    it('annotate() 403 toasts and leaves the list untouched', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store, toasts } = create(api);
      await flush();
      api.patchMark.mockRejectedValue(new Error('may only be edited by its creator or a manager'));

      const ok = await store.annotate('m-1', { label: 'x' });

      expect(ok).toBe(false);
      expect(store.marks()).toEqual([mark({ id: 'm-1' })]);
      expect(toasts.error).toHaveBeenCalled();
    });

    it('clear() removes the mark from the active list and deselects it', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store } = create(api);
      await flush();
      store.select('m-1');
      api.patchMark.mockResolvedValue(mark({ id: 'm-1', status: 'CLEARED' }));

      const ok = await store.clear('m-1');

      expect(ok).toBe(true);
      expect(api.patchMark).toHaveBeenCalledWith('m-1', { status: 'CLEARED' });
      expect(store.marks()).toEqual([]);
      expect(store.selectedMarkId()).toBeUndefined();
    });

    it('moveTo() adopts the server response on success', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store } = create(api);
      await flush();
      const moved = mark({ id: 'm-1', position: { latitude: 5, longitude: 6 } });
      api.patchMark.mockResolvedValue(moved);

      const ok = await store.moveTo('m-1', { latitude: 5, longitude: 6 });

      expect(ok).toBe(true);
      expect(api.patchMark).toHaveBeenCalledWith('m-1', { position: { latitude: 5, longitude: 6 } });
      expect(store.marks()).toEqual([moved]);
    });

    it('moveTo() failure toasts, keeps the last-known-good position, and still produces a fresh array reference (forces a re-render/revert)', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store, toasts } = create(api);
      await flush();
      const before = store.marks();
      api.patchMark.mockRejectedValue(new Error('forbidden'));

      const ok = await store.moveTo('m-1', { latitude: 99, longitude: 99 });

      expect(ok).toBe(false);
      expect(toasts.error).toHaveBeenCalled();
      expect(store.marks()).toEqual([mark({ id: 'm-1' })]); // content unchanged (the honest, last-known position)
      expect(store.marks()).not.toBe(before); // but a fresh reference, so a map effect keyed on it re-runs
    });

    it('remove() deletes and drops the mark from the list', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store } = create(api);
      await flush();
      api.deleteMark.mockResolvedValue(undefined);

      await store.remove('m-1');

      expect(api.deleteMark).toHaveBeenCalledWith('m-1');
      expect(store.marks()).toEqual([]);
    });

    it('remove() 403 toasts and leaves the mark in the list', async () => {
      const api = stubApi({ listMarks: vi.fn().mockResolvedValue([mark({ id: 'm-1' })]) });
      const { store, toasts } = create(api);
      await flush();
      api.deleteMark.mockRejectedValue(new Error('forbidden'));

      await store.remove('m-1');

      expect(store.marks()).toEqual([mark({ id: 'm-1' })]);
      expect(toasts.error).toHaveBeenCalled();
    });
  });
});
