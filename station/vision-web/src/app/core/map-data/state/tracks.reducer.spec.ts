import { describe, expect, it } from 'vitest';
import type { ProjectedTrackResponse } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { TracksApiActions, TracksPageActions } from './tracks.actions';
import { initialTracksState } from './tracks.model';
import { tracksFeature } from './tracks.reducer';

const { reducer } = tracksFeature;

function track(overrides: Partial<ProjectedTrackResponse> = {}): ProjectedTrackResponse {
  return {
    assetId: 'a-1',
    trackId: 1,
    label: 'Truck',
    layerId: 'l-1',
    latitude: 1,
    longitude: 2,
    rangeMeters: 100,
    errorRadiusMeters: 5,
    updatedAt: '2026-09-01T00:00:00Z',
    trail: [],
    ...overrides,
  };
}

describe('tracks reducer', () => {
  it('starts with no entities, not loaded, no active consumers', () => {
    expect(initialTracksState.ids).toEqual([]);
    expect(initialTracksState.loaded).toBe(false);
    expect(initialTracksState.activeConsumers).toBe(0);
  });

  it('activated/released ref-count demand, floored at zero', () => {
    const first = reducer(initialTracksState, TracksPageActions.activated());
    expect(first.activeConsumers).toBe(1);
    const released = reducer(first, TracksPageActions.released());
    expect(released.activeConsumers).toBe(0);
    expect(reducer(released, TracksPageActions.released()).activeConsumers).toBe(0);
  });

  it('loaded setAlls the entities keyed by assetId:trackId, and flips loaded', () => {
    const state = reducer(initialTracksState, TracksApiActions.loaded({ tracks: [track()] }));
    expect(state.ids).toEqual(['a-1:1']);
    expect(state.loaded).toBe(true);
  });

  it('loadFailed (the flag-off 409 degrade) flips loaded without producing an error state', () => {
    const state = reducer(initialTracksState, TracksApiActions.loadFailed());
    expect(state.loaded).toBe(true);
    expect(state.ids).toEqual([]);
  });

  it('a live map track event upserts, preserving any existing trail, unconditionally of activeConsumers', () => {
    const seeded = reducer(
      initialTracksState,
      TracksApiActions.loaded({ tracks: [track({ trail: [{ latitude: 1, longitude: 2, at: '2026-09-01T00:00:00Z' }] })] }),
    );
    const updated = reducer(
      seeded,
      LiveSocketActions.envelopeReceived({
        envelope: {
          seq: 1,
          type: 'map',
          payload: { entity: 'track', action: 'updated', layerId: 'l-1', track: { assetId: 'a-1', trackId: 1, latitude: 9, longitude: 9 } },
        },
      }),
    );
    expect(updated.entities['a-1:1']?.latitude).toBe(9);
    expect(updated.entities['a-1:1']?.trail).toEqual([{ latitude: 1, longitude: 2, at: '2026-09-01T00:00:00Z' }]); // trail survives — live never carries one
  });

  it('a cleared track event removes it outright — nothing lingers on the map', () => {
    const seeded = reducer(initialTracksState, TracksApiActions.loaded({ tracks: [track()] }));
    const cleared = reducer(
      seeded,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, type: 'map', payload: { entity: 'track', action: 'cleared', layerId: 'l-1', track: { assetId: 'a-1', trackId: 1 } } },
      }),
    );
    expect(cleared.ids).toEqual([]);
  });

  it('ignores a non-track map event and any other topic', () => {
    const markEvent = reducer(
      initialTracksState,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 1, type: 'map', payload: { entity: 'mark', action: 'created', layerId: 'l-1' } },
      }),
    );
    expect(markEvent).toEqual(initialTracksState); // adapter.setAll always rebuilds the state object, even as a content no-op

    const otherTopic = reducer(
      initialTracksState,
      LiveSocketActions.envelopeReceived({ envelope: { seq: 2, type: 'fleet', payload: [] } }),
    );
    expect(otherTopic).toBe(initialTracksState);
  });

  it('selectAllTracks derives the entity list', () => {
    const state = reducer(initialTracksState, TracksApiActions.loaded({ tracks: [track()] }));
    expect(tracksFeature.selectAllTracks.projector(state)).toEqual([track()]);
  });
});
