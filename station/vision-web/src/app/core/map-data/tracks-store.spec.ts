import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { TracksStore } from './tracks-store';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import type { MapEventPayload, ProjectedTrackResponse } from '../api/models';

/**
 * Store-level coverage for `TracksStore` — the first file of its kind for this store (no prior spec
 * existed). Scoped to the initial-GET/flag-off-degrades posture the class doc describes plus the
 * ALWAYS-ON-FLOW-PLAN.md §4 Wave C3 demand-gating this store gained alongside its four
 * `core/map-data/**`/`core/geofence/**` siblings.
 */

function track(overrides: Partial<ProjectedTrackResponse> = {}): ProjectedTrackResponse {
  return {
    assetId: 'asset-1',
    trackId: 7,
    label: 'T7',
    layerId: 'layer-a',
    latitude: 50.45,
    longitude: 30.52,
    rangeMeters: 120,
    errorRadiusMeters: 15,
    updatedAt: '2026-09-06T10:00:00Z',
    trail: [],
    ...overrides,
  };
}

function stubApi(overrides: Record<string, ReturnType<typeof vi.fn>> = {}) {
  return {
    listMapTracks: vi.fn().mockResolvedValue({ tracks: [] }),
    ...overrides,
  };
}

function stubLiveStore() {
  const events = signal<readonly MapEventPayload[]>([]);
  return { mapEvents: events.asReadonly() };
}

function createInactive(api: ReturnType<typeof stubApi>) {
  const scheduleFn = vi.fn().mockReturnValue(vi.fn());
  TestBed.configureTestingModule({
    providers: [
      TracksStore,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: { schedule: scheduleFn } },
      { provide: LiveStore, useValue: stubLiveStore() },
    ],
  });
  return { store: TestBed.inject(TracksStore), scheduleFn };
}

/** Lets the fire-and-forget promise chain inside `refresh()` settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('TracksStore', () => {
  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { store, scheduleFn } = createInactive(api);
      await flush();
      expect(api.listMapTracks).not.toHaveBeenCalled();
      expect(scheduleFn).not.toHaveBeenCalled();

      store.activate();
      await flush();
      expect(api.listMapTracks).toHaveBeenCalledTimes(1);
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
      expect(api.listMapTracks).toHaveBeenCalledTimes(1);
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
      expect(api.listMapTracks).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { store } = createInactive(stubApi());
      expect(() => store.release()).not.toThrow();
    });
  });

  describe('initial GET (once activated)', () => {
    it('fetches the track list', async () => {
      const api = stubApi({ listMapTracks: vi.fn().mockResolvedValue({ tracks: [track()] }) });
      const { store } = createInactive(api);
      store.activate();
      await flush();

      expect(store.tracks()).toEqual([track()]);
      expect(store.loaded()).toBe(true);
    });

    it('flag-off (409) degrades to an empty list, not an error state', async () => {
      const api = stubApi({ listMapTracks: vi.fn().mockRejectedValue(new Error('409')) });
      const { store } = createInactive(api);
      store.activate();
      await flush();

      expect(store.tracks()).toEqual([]);
      expect(store.loaded()).toBe(true);
    });
  });
});
