import { TestBed } from '@angular/core/testing';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { MapEventPayload, ProjectedTrackResponse } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { LiveSocketActions } from '../live/state/live.actions';
import { PollScheduler } from '../poll-scheduler';
import { provideAppState } from '../state/app-state';
import { provideTracksState } from './state/tracks.providers';
import { TracksFacade } from './tracks-facade';

/**
 * `TracksFacade` end to end — facade → action → effect (real HTTP call through a stub `VisionApi`,
 * real polling through a stub `PollScheduler`, real `live` slice reducer) → reducer → facade
 * signals. Replaces `tracks-store.spec.ts` case for case (docs/plans/done/NGRX-MIGRATION-PLAN.md
 * wave N6); the live gate is driven by dispatching real `LiveSocketActions` against the actually
 * registered `live` slice rather than a stub `LiveFacade` — this wave's effects read `live` only
 * through `liveFeature`'s own selectors (§9), so there is nothing left to stub.
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

function stubScheduler() {
  return { schedule: vi.fn().mockReturnValue(vi.fn()) };
}

function create(api: ReturnType<typeof stubApi>, scheduler = stubScheduler()) {
  TestBed.configureTestingModule({
    providers: [provideAppState(), provideTracksState(), TracksFacade, { provide: VisionApi, useValue: api }, { provide: PollScheduler, useValue: scheduler }],
  });
  const facade = TestBed.inject(TracksFacade);
  const store = TestBed.inject(Store);
  return { facade, store, scheduler };
}

function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** Simulates one `map` SSE arrival — mirrors the old `stubLiveFacade().push` helper, but dispatched
 *  as the real `LiveSocketActions.envelopeReceived` action the live gateway would produce. */
let nextSeq = 1;
function pushMapEvent(store: Store, payload: MapEventPayload) {
  store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: nextSeq++, type: 'map', payload } }));
}

describe('TracksFacade', () => {
  describe('activate/release (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3)', () => {
    it('never fetches or schedules a poll until the first activate()', async () => {
      const api = stubApi();
      const { facade, scheduler } = create(api);
      await flush();
      expect(api.listMapTracks).not.toHaveBeenCalled();
      expect(scheduler.schedule).not.toHaveBeenCalled();

      facade.activate();
      await flush();
      expect(api.listMapTracks).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).toHaveBeenCalledTimes(1);
      expect(facade.loaded()).toBe(true);
    });

    it('a second concurrent consumer neither re-fetches nor re-schedules', async () => {
      const api = stubApi();
      const { facade, scheduler } = create(api);
      facade.activate();
      await flush();
      facade.activate();
      await flush();
      expect(api.listMapTracks).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).toHaveBeenCalledTimes(1);
    });

    it('stops the poll only once every consumer has released, then refreshes again on reactivation', async () => {
      const api = stubApi();
      const stopFn = vi.fn();
      const scheduler = stubScheduler();
      scheduler.schedule.mockReturnValue(stopFn);
      const { facade } = create(api, scheduler);

      facade.activate();
      facade.activate();
      await flush();
      facade.release();
      expect(stopFn).not.toHaveBeenCalled();
      facade.release();
      expect(stopFn).toHaveBeenCalledTimes(1);

      facade.activate();
      await flush();
      expect(api.listMapTracks).toHaveBeenCalledTimes(2);
    });

    it('an unmatched release is a defensive no-op, never going negative', () => {
      const { facade } = create(stubApi());
      expect(() => facade.release()).not.toThrow();
    });
  });

  describe('initial GET (once activated)', () => {
    it('fetches the track list', async () => {
      const api = stubApi({ listMapTracks: vi.fn().mockResolvedValue({ tracks: [track()] }) });
      const { facade } = create(api);
      facade.activate();
      await flush();

      expect(facade.tracks()).toEqual([track()]);
      expect(facade.loaded()).toBe(true);
    });

    it('flag-off (409) degrades to an empty list, not an error state', async () => {
      const api = stubApi({ listMapTracks: vi.fn().mockRejectedValue(new Error('409')) });
      const { facade } = create(api);
      facade.activate();
      await flush();

      expect(facade.tracks()).toEqual([]);
      expect(facade.loaded()).toBe(true);
    });
  });

  describe('the `map` SSE topic', () => {
    it('folds a TRACK delta unconditionally, even with no active consumer', () => {
      const { facade, store } = create(stubApi());

      pushMapEvent(store, {
        entity: 'track',
        action: 'created',
        layerId: 'layer-a',
        track: { assetId: 'asset-9', trackId: 1, label: 'Live', layerId: 'layer-a', latitude: 1, longitude: 2 },
      });

      expect(facade.tracks().map((t) => t.assetId)).toEqual(['asset-9']);
    });
  });

  describe('live gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)', () => {
    it('reconciles exactly once on reconnect, and stays silent for as long as live holds', async () => {
      const api = stubApi({ listMapTracks: vi.fn().mockResolvedValue({ tracks: [track()] }) });
      const { facade, store } = create(api);
      facade.activate();
      await flush();

      store.dispatch(LiveSocketActions.opened());
      await flush();
      // (the first entry into live already reconciled once here — not the segment under test)

      store.dispatch(LiveSocketActions.closed());
      await flush();
      api.listMapTracks.mockClear();

      store.dispatch(LiveSocketActions.opened());
      await flush();

      expect(api.listMapTracks).toHaveBeenCalledTimes(1);

      facade.activate();
      await flush();
      expect(api.listMapTracks).toHaveBeenCalledTimes(1);
    });

    it('a facade that activates while already live does one initial GET, not zero, and never schedules the poll', async () => {
      const api = stubApi({ listMapTracks: vi.fn().mockResolvedValue({ tracks: [track()] }) });
      const { facade, store, scheduler } = create(api);
      store.dispatch(LiveSocketActions.opened());

      facade.activate();
      await flush();

      expect(api.listMapTracks).toHaveBeenCalledTimes(1);
      expect(scheduler.schedule).not.toHaveBeenCalled();
    });
  });
});
