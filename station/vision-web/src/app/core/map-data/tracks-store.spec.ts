import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { TracksStore } from './tracks-store';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import type { LiveConnectionState } from '../live/live-fallback-logic';
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

/** `connectionState` seeded `'closed'` — reproduces today's (pre-D1) behaviour exactly, see
 *  `marks-store.spec.ts`'s identical `stubLiveFacade` doc comment. */
function stubLiveFacade() {
  const events = signal<readonly MapEventPayload[]>([]);
  const connectionState = signal<LiveConnectionState>('closed');
  return {
    mapEvents: events.asReadonly(),
    push: (incoming: readonly MapEventPayload[]) => events.update((existing) => [...existing, ...incoming]),
    connectionState,
  };
}

function createInactive(api: ReturnType<typeof stubApi>) {
  const live = stubLiveFacade();
  const scheduleFn = vi.fn().mockReturnValue(vi.fn());
  TestBed.configureTestingModule({
    providers: [
      TracksStore,
      { provide: VisionApi, useValue: api },
      { provide: PollScheduler, useValue: { schedule: scheduleFn } },
      { provide: LiveFacade, useValue: live },
    ],
  });
  return { store: TestBed.inject(TracksStore), live, scheduleFn };
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

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    /**
     * The plan's own frozen acceptance criterion (§5): a poll that stops must still reconcile on
     * reconnect. With the store active and live open, driving `connectionState` through
     * `open → closed → open` must issue **exactly one** REST refresh on (re-)entering `open`, and
     * **zero** REST requests for as long as `open` persists.
     */
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listMapTracks: vi.fn().mockResolvedValue({ tracks: [track()] }) });
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
      api.listMapTracks.mockClear();

      live.connectionState.set('open');
      TestBed.tick();
      await flush();

      expect(api.listMapTracks).toHaveBeenCalledTimes(1); // exactly one refresh, entering 'open'

      store.activate(); // a second concurrent consumer while already live — no further request
      TestBed.tick();
      await flush();
      expect(api.listMapTracks).toHaveBeenCalledTimes(1);
    });

    it('a store that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listMapTracks: vi.fn().mockResolvedValue({ tracks: [track()] }) });
      const { store, live, scheduleFn } = createInactive(api);
      live.connectionState.set('open');

      store.activate();
      await flush();

      expect(api.listMapTracks).toHaveBeenCalledTimes(1);
      expect(scheduleFn).not.toHaveBeenCalled();
    });
  });
});
