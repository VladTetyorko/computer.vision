import { createFeature, createReducer, on } from '@ngrx/store';
import type { GeofenceZone, GeofenceZoneEventPayload } from '../../api/models';
import { LiveSocketActions } from '../../live/state/live.actions';
import { GeofenceApiActions, GeofencePageActions } from './geofence.actions';
import { initialGeofenceState } from './geofence.model';

/**
 * Folds one live `zones` delta onto the current zone list — ported verbatim from
 * `geofence-store.ts`'s own module-private `applyZoneEvents`, narrowed to one event at a time since
 * each `LiveSocketActions.envelopeReceived` dispatch already carries exactly one
 * `GeofenceZoneEventPayload` (`live.reducer.ts`'s own `'zones'` case appends one payload per
 * envelope, never a batch). `CREATED`/`UPDATED` upsert by id; `DELETED` removes by id — both
 * idempotent, so a duplicate delivery across a reconnect can never duplicate or resurrect a zone.
 */
function applyZoneEvent(zones: readonly GeofenceZone[], event: GeofenceZoneEventPayload): readonly GeofenceZone[] {
  if (event.action === 'DELETED') {
    return zones.filter((zone) => zone.id !== event.zone.id);
  }
  const index = zones.findIndex((zone) => zone.id === event.zone.id);
  return index === -1 ? [...zones, event.zone] : zones.map((zone, i) => (i === index ? event.zone : zone));
}

export const geofenceFeature = createFeature({
  name: 'geofence',
  reducer: createReducer(
    initialGeofenceState,
    on(GeofencePageActions.activated, (state) => ({ ...state, activeConsumers: state.activeConsumers + 1 })),
    on(GeofencePageActions.released, (state) =>
      state.activeConsumers === 0 ? state : { ...state, activeConsumers: state.activeConsumers - 1 },
    ),
    on(GeofenceApiActions.zonesLoaded, (state, { zones }) => ({ ...state, zones, loaded: true })),
    on(GeofenceApiActions.loadFailed, (state) => ({ ...state, loaded: true })),
    on(GeofenceApiActions.createSucceeded, (state, { zone }) => ({ ...state, zones: [...state.zones, zone] })),
    on(GeofenceApiActions.createFailed, (state) => state),
    on(GeofenceApiActions.replaceSucceeded, (state, { zone }) => ({
      ...state,
      zones: state.zones.map((candidate) => (candidate.id === zone.id ? zone : candidate)),
    })),
    on(GeofenceApiActions.replaceFailed, (state) => state),
    on(GeofenceApiActions.removeSucceeded, (state, { zone }) => ({
      ...state,
      zones: state.zones.filter((candidate) => candidate.id !== zone.id),
    })),
    on(GeofenceApiActions.removeFailed, (state) => state),
    // Runs unconditionally, with no active consumer required (`geofence-store.spec.ts`'s own "runs
    // the fold unconditionally, even with no active consumer" case) — every `core/map-data/**`
    // sibling slice does the same for the `map` topic; this is the `zones` topic's counterpart.
    on(LiveSocketActions.envelopeReceived, (state, { envelope }) =>
      envelope.type === 'zones' ? { ...state, zones: applyZoneEvent(state.zones, envelope.payload) } : state,
    ),
  ),
});
