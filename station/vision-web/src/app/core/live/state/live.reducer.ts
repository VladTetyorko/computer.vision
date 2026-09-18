import { createFeature, createReducer, on } from '@ngrx/store';
import type { LiveEnvelope } from '../../api/models';
import {
  cvTraceTopic,
  decrementTopicRef,
  detectionsTopic,
  geoTopic,
  incrementTopicRef,
  linksTopic,
  mergeTelemetrySamples,
  telemetryTopic,
  tracksTopic,
} from '../live-fallback-logic';
import { LiveApiActions, LivePageActions, LiveSocketActions } from './live.actions';
import {
  MAX_LIVE_DETECTION_EVENTS,
  MAX_LIVE_DISCOVERY_EVENTS,
  MAX_LIVE_EVENTS,
  MAX_LIVE_MAP_EVENTS,
  MAX_LIVE_ZONE_EVENTS,
  initialLiveState,
  type LiveState,
  type LiveTopicFamily,
} from './live.model';

/** The wire topic string one family's ref-count/PATCH traffic uses — reuses the pure builders
 *  `live-fallback-logic.ts` already ships (NGRX-MIGRATION-PLAN.md §8: "reuse them, don't rewrite
 *  them"), shared with `live.effects.ts` so the two files can never drift on the mapping. */
export function topicFor(family: LiveTopicFamily, assetId: string): string {
  switch (family) {
    case 'telemetry':
      return telemetryTopic(assetId);
    case 'detections':
      return detectionsTopic(assetId);
    case 'geo':
      return geoTopic(assetId);
    case 'worldObjects':
      return tracksTopic(assetId);
    case 'cvTrace':
      return cvTraceTopic(assetId);
    case 'links':
      return linksTopic(assetId);
  }
}

/** A retired asset's residual per-family entry, dropped on the last unsubscribe — mirrors
 *  `LiveStore#untrack`'s own `signals.delete(assetId)`, so a future re-`track()` of the same asset
 *  (or a *different* consumer's `xFor` read in the gap) never sees stale data through the `?? []`/
 *  `?? undefined` fallback `live-facade.ts`'s readers apply. */
function clearFamilyEntry(state: LiveState, family: LiveTopicFamily, assetId: string): LiveState {
  switch (family) {
    case 'telemetry': {
      const { [assetId]: _removed, ...rest } = state.telemetryByAssetId;
      return { ...state, telemetryByAssetId: rest };
    }
    case 'detections': {
      const { [assetId]: _removed, ...rest } = state.detectionsByAssetId;
      return { ...state, detectionsByAssetId: rest };
    }
    case 'geo': {
      const { [assetId]: _removed, ...rest } = state.geoByAssetId;
      return { ...state, geoByAssetId: rest };
    }
    case 'worldObjects': {
      const { [assetId]: _removed, ...rest } = state.tracksByAssetId;
      return { ...state, tracksByAssetId: rest };
    }
    case 'cvTrace': {
      const { [assetId]: _removed, ...rest } = state.cvTraceByAssetId;
      return { ...state, cvTraceByAssetId: rest };
    }
    case 'links': {
      const { [assetId]: _removed, ...rest } = state.linksByAssetId;
      return { ...state, linksByAssetId: rest };
    }
  }
}

/** `topicRefs` is a plain `Record` in state (a `Map` would trip `strictStateSerializability`), so
 *  every ref-count change round-trips through the pure `Map`-based helpers via a throwaway `Map`. */
function withRefIncrement(state: LiveState, topic: string): LiveState {
  const { count } = incrementTopicRef(new Map(Object.entries(state.topicRefs)), topic);
  return { ...state, topicRefs: { ...state.topicRefs, [topic]: count } };
}

function withRefDecrement(state: LiveState, topic: string, family: LiveTopicFamily, assetId: string): LiveState {
  const { count, lastSubscriber } = decrementTopicRef(new Map(Object.entries(state.topicRefs)), topic);
  const { [topic]: _removedTopic, ...withoutTopic } = state.topicRefs;
  const topicRefs = count === 0 ? withoutTopic : { ...state.topicRefs, [topic]: count };
  const next = { ...state, topicRefs };
  return lastSubscriber ? clearFamilyEntry(next, family, assetId) : next;
}

function applyEnvelope(state: LiveState, envelope: LiveEnvelope): LiveState {
  switch (envelope.type) {
    case 'fleet':
      return { ...state, fleet: envelope.payload };
    case 'telemetry':
      return {
        ...state,
        telemetryByAssetId: {
          ...state.telemetryByAssetId,
          [envelope.assetId]: mergeTelemetrySamples(state.telemetryByAssetId[envelope.assetId] ?? [], envelope.payload),
        },
      };
    case 'detections':
      return { ...state, detectionsByAssetId: { ...state.detectionsByAssetId, [envelope.assetId]: envelope.payload } };
    case 'event':
      return { ...state, liveEvents: [envelope.payload, ...state.liveEvents].slice(0, MAX_LIVE_EVENTS) };
    case 'devices':
      return { ...state, devices: envelope.payload };
    case 'detection-events':
      // Chronological append, trimmed from the front on overflow — see `LiveState.liveEvents`'s
      // sibling doc comment (live.model.ts) for why this must stay oldest-first, unlike `liveEvents`.
      return {
        ...state,
        detectionEvents: [...state.detectionEvents, envelope.payload].slice(-MAX_LIVE_DETECTION_EVENTS),
      };
    case 'map':
      // Chronological append — identical reasoning to `detection-events` above.
      return { ...state, mapEvents: [...state.mapEvents, envelope.payload].slice(-MAX_LIVE_MAP_EVENTS) };
    case 'geo':
      // Latest-wins, like `detections` above — the server's own ring capacity 1 means this is never
      // a batch to merge, just the freshest correction replacing the last one.
      return { ...state, geoByAssetId: { ...state.geoByAssetId, [envelope.assetId]: envelope.payload } };
    case 'discovery':
      // Chronological append — identical reasoning to `map`/`detection-events` above.
      return {
        ...state,
        discoveryEvents: [...state.discoveryEvents, envelope.payload].slice(-MAX_LIVE_DISCOVERY_EVENTS),
      };
    case 'zones':
      // Chronological append — identical reasoning to `map`/`discovery` above.
      return { ...state, zoneEvents: [...state.zoneEvents, envelope.payload].slice(-MAX_LIVE_ZONE_EVENTS) };
    case 'system':
      // Latest-wins, like `devices`/`detections` above — the server's own ring capacity 1 means this
      // is never a batch to merge, just the freshest sample replacing the last one.
      return { ...state, systemStatus: envelope.payload };
    case 'tracks':
      // Latest-wins snapshot, like `detections`/`geo` above — server ring capacity 1, never a batch
      // to merge. Backs both `tracksFor` and `worldObjectsFor` (its own `.objects` derivation).
      return { ...state, tracksByAssetId: { ...state.tracksByAssetId, [envelope.assetId]: envelope.payload } };
    case 'cv-trace':
      // Latest-wins, like `detections`/`geo` above — `core/cv-trace/cv-trace-store.ts` is what
      // accumulates the capped ring an inspector actually reads from.
      return { ...state, cvTraceByAssetId: { ...state.cvTraceByAssetId, [envelope.assetId]: envelope.payload } };
    case 'links':
      // Latest-wins snapshot — §3.4's own "payload is the whole list snapshot" rule, same posture as
      // `geo`/`cv-trace` above.
      return { ...state, linksByAssetId: { ...state.linksByAssetId, [envelope.assetId]: envelope.payload } };
  }
}

export const liveFeature = createFeature({
  name: 'live',
  reducer: createReducer(
    initialLiveState,

    // A fresh attempt (the very first connect, or an explicit `reconnect()`) resets synchronously —
    // mirrors `LiveStore#connect`'s own `this.stateSignal.set('connecting'); this.connectionId =
    // undefined;` at the top of every attempt, before `live.effects.ts#connectWithRetry` even opens
    // the gateway. `Stop Requested` sets `'closed'` synchronously too, since a manually-closed
    // `EventSource` never fires its own `onerror` — `Live Socket Closed` alone could never cover it.
    on(LivePageActions.reconnectRequested, (state) => ({ ...state, connectionState: 'connecting', connectionId: undefined })),
    on(LivePageActions.stopRequested, (state) => ({ ...state, connectionState: 'closed', connectionId: undefined })),

    on(LivePageActions.telemetryTracked, (state, { assetId }) => withRefIncrement(state, telemetryTopic(assetId))),
    on(LivePageActions.telemetryUntracked, (state, { assetId }) =>
      withRefDecrement(state, telemetryTopic(assetId), 'telemetry', assetId),
    ),
    on(LivePageActions.detectionsTracked, (state, { assetId }) => withRefIncrement(state, detectionsTopic(assetId))),
    on(LivePageActions.detectionsUntracked, (state, { assetId }) =>
      withRefDecrement(state, detectionsTopic(assetId), 'detections', assetId),
    ),
    on(LivePageActions.geoTracked, (state, { assetId }) => withRefIncrement(state, geoTopic(assetId))),
    on(LivePageActions.geoUntracked, (state, { assetId }) => withRefDecrement(state, geoTopic(assetId), 'geo', assetId)),
    on(LivePageActions.worldObjectsTracked, (state, { assetId }) => withRefIncrement(state, tracksTopic(assetId))),
    on(LivePageActions.worldObjectsUntracked, (state, { assetId }) =>
      withRefDecrement(state, tracksTopic(assetId), 'worldObjects', assetId),
    ),
    on(LivePageActions.cvTraceTracked, (state, { assetId }) => withRefIncrement(state, cvTraceTopic(assetId))),
    on(LivePageActions.cvTraceUntracked, (state, { assetId }) =>
      withRefDecrement(state, cvTraceTopic(assetId), 'cvTrace', assetId),
    ),
    on(LivePageActions.linksTracked, (state, { assetId }) => withRefIncrement(state, linksTopic(assetId))),
    on(LivePageActions.linksUntracked, (state, { assetId }) =>
      withRefDecrement(state, linksTopic(assetId), 'links', assetId),
    ),

    on(LiveSocketActions.opened, (state) => ({ ...state, connectionState: 'open' })),
    on(LiveSocketActions.handshakeReceived, (state, { connectionId }) => ({ ...state, connectionId })),
    on(LiveSocketActions.retrying, (state) => ({ ...state, connectionState: 'connecting' })),
    on(LiveSocketActions.closed, (state) => ({ ...state, connectionState: 'closed', connectionId: undefined })),
    on(LiveSocketActions.envelopeReceived, (state, { envelope }) => applyEnvelope(state, envelope)),

    // Deliberately a no-op — see `LiveApiActions.topicsPatchFailed`'s own doc comment.
    on(LiveApiActions.topicsPatchFailed, (state) => state),
  ),
});
