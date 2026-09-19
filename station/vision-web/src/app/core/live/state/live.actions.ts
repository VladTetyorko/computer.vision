import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { LiveEnvelope } from '../../api/models';

/**
 * Every ref-counted opt-in/out a consumer facade issues, plus the two connection commands
 * (`reconnect()`/`stop()`) `core/auth/state/auth.effects.ts` drives on login/logout — one action pair
 * per family, named exactly after the old `LiveStore#trackX`/`untrackX` method it replaces so
 * `live-facade.ts`'s dispatch methods stay a mechanical 1:1 mapping.
 */
export const LivePageActions = createActionGroup({
  source: 'Live Page',
  events: {
    'Reconnect Requested': emptyProps(),
    'Stop Requested': emptyProps(),
    'Telemetry Tracked': props<{ assetId: string }>(),
    'Telemetry Untracked': props<{ assetId: string }>(),
    'Detections Tracked': props<{ assetId: string }>(),
    'Detections Untracked': props<{ assetId: string }>(),
    'Geo Tracked': props<{ assetId: string }>(),
    'Geo Untracked': props<{ assetId: string }>(),
    'World Objects Tracked': props<{ assetId: string }>(),
    'World Objects Untracked': props<{ assetId: string }>(),
    'Cv Trace Tracked': props<{ assetId: string }>(),
    'Cv Trace Untracked': props<{ assetId: string }>(),
    'Links Tracked': props<{ assetId: string }>(),
    'Links Untracked': props<{ assetId: string }>(),
  },
});

/**
 * Every lifecycle event `../live-gateway.ts#LiveGateway` reports off the raw `EventSource` —
 * `'Live Socket'`, not `'Live Page'`/`'Live API'`, since none of this is a request this app made, it
 * is the server (or the browser's own native reconnect) pushing. `Retrying` is the browser's own
 * auto-retry after a transient drop (`readyState` not yet `CLOSED`); `Closed` is either that same
 * `readyState` reaching `CLOSED` for good (`LiveGateway`'s `'fatal'` event) or an explicit
 * `Stop Requested` — both leave this connection with nothing to patch until the next reconnect.
 */
export const LiveSocketActions = createActionGroup({
  source: 'Live Socket',
  events: {
    Opened: emptyProps(),
    'Handshake Received': props<{ connectionId: string; topics: readonly string[] }>(),
    'Envelope Received': props<{ envelope: LiveEnvelope }>(),
    Retrying: emptyProps(),
    Closed: emptyProps(),
  },
});

/**
 * The one HTTP call this slice makes — `PATCH /api/live/{connectionId}/topics`, fired only on a
 * ref-count transition while the connection is open (`live.effects.ts#patchOnTrack$`/`patchOnUntrack$`).
 * A failure is deliberately silent (mirrors `LiveStore#patchTopics`'s own `console.warn`, no toast —
 * the next full reconnect always rebuilds the topic list from `topicRefs` regardless of this
 * outcome), but still a modeled action per NGRX-MIGRATION-PLAN.md §3 rule 7, never a swallowed catch.
 */
export const LiveApiActions = createActionGroup({
  source: 'Live API',
  events: {
    'Topics Patch Failed': props<{ topics: readonly string[] }>(),
  },
});
