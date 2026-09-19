import { describe, expect, it } from 'vitest';
import type { GeofenceZone } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { GeofenceApiActions, GeofencePageActions } from './geofence.actions';
import { initialGeofenceState } from './geofence.model';
import { geofenceFeature } from './geofence.reducer';

const { reducer } = geofenceFeature;

function zone(overrides: Partial<GeofenceZone> = {}): GeofenceZone {
  return { id: 'z-1', name: 'No-fly', kind: 'KEEP_OUT', polygon: [], enabled: true, ...overrides };
}

describe('geofence reducer', () => {
  it('starts empty, not loaded, with no active consumers', () => {
    expect(initialGeofenceState).toEqual({ zones: [], loaded: false, activeConsumers: 0 });
  });

  it('activated/released ref-count demand, floored at zero', () => {
    const first = reducer(initialGeofenceState, GeofencePageActions.activated());
    expect(first.activeConsumers).toBe(1);
    const second = reducer(first, GeofencePageActions.activated());
    expect(second.activeConsumers).toBe(2);
    const releasedOnce = reducer(second, GeofencePageActions.released());
    expect(releasedOnce.activeConsumers).toBe(1);
    const releasedTwice = reducer(releasedOnce, GeofencePageActions.released());
    expect(releasedTwice.activeConsumers).toBe(0);
    const releasedThrice = reducer(releasedTwice, GeofencePageActions.released());
    expect(releasedThrice.activeConsumers).toBe(0); // never negative
  });

  it('zonesLoaded replaces the list and flips loaded', () => {
    const state = reducer(initialGeofenceState, GeofenceApiActions.zonesLoaded({ zones: [zone()] }));
    expect(state.zones).toEqual([zone()]);
    expect(state.loaded).toBe(true);
  });

  it('loadFailed flips loaded without touching the (possibly stale) zone list', () => {
    const loaded = reducer(initialGeofenceState, GeofenceApiActions.zonesLoaded({ zones: [zone()] }));
    const failed = reducer(loaded, GeofenceApiActions.loadFailed());
    expect(failed.zones).toEqual([zone()]);
    expect(failed.loaded).toBe(true);
  });

  it('createSucceeded appends; createFailed is a no-op', () => {
    const created = reducer(initialGeofenceState, GeofenceApiActions.createSucceeded({ zone: zone() }));
    expect(created.zones).toEqual([zone()]);

    const failed = reducer(created, GeofenceApiActions.createFailed({ error: 'boom' }));
    expect(failed).toBe(created);
  });

  it('replaceSucceeded swaps only the matching zone by id; replaceFailed is a no-op', () => {
    const seeded = reducer(initialGeofenceState, GeofenceApiActions.zonesLoaded({ zones: [zone(), zone({ id: 'z-2' })] }));
    const replaced = reducer(seeded, GeofenceApiActions.replaceSucceeded({ zone: zone({ name: 'Renamed' }) }));
    expect(replaced.zones).toEqual([zone({ name: 'Renamed' }), zone({ id: 'z-2' })]);

    const failed = reducer(replaced, GeofenceApiActions.replaceFailed({ error: 'boom' }));
    expect(failed).toBe(replaced);
  });

  it('removeSucceeded drops only the matching zone by id; removeFailed is a no-op', () => {
    const seeded = reducer(initialGeofenceState, GeofenceApiActions.zonesLoaded({ zones: [zone(), zone({ id: 'z-2' })] }));
    const removed = reducer(seeded, GeofenceApiActions.removeSucceeded({ zone: zone() }));
    expect(removed.zones).toEqual([zone({ id: 'z-2' })]);

    const failed = reducer(removed, GeofenceApiActions.removeFailed({ error: 'boom' }));
    expect(failed).toBe(removed);
  });

  it('a live zones envelope upserts CREATED/UPDATED and drops DELETED, unconditionally of activeConsumers', () => {
    const created = reducer(
      initialGeofenceState,
      LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'zones', payload: { action: 'CREATED', zone: zone() } } }),
    );
    expect(created.zones).toEqual([zone()]);

    const updated = reducer(
      created,
      LiveSocketActions.envelopeReceived({
        envelope: { seq: 2, type: 'zones', payload: { action: 'UPDATED', zone: zone({ enabled: false }) } },
      }),
    );
    expect(updated.zones).toEqual([zone({ enabled: false })]);

    const deleted = reducer(
      updated,
      LiveSocketActions.envelopeReceived({ envelope: { seq: 3, type: 'zones', payload: { action: 'DELETED', zone: zone() } } }),
    );
    expect(deleted.zones).toEqual([]);
  });

  it('ignores an envelope of a different topic', () => {
    const state = reducer(
      initialGeofenceState,
      LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [] } }),
    );
    expect(state).toBe(initialGeofenceState);
  });
});
