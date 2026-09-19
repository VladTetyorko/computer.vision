import { createActionGroup, props } from '@ngrx/store';
import type { AssetScopedTransport } from '../../live/live-fallback-logic';
import type { LinkGroupResponse } from '../../api/models';

/** `LinksFacade` itself, plus its own constructor `effect()`s bridging `LiveFacade` into this slice
 *  (never `@ngrx/effects` reading `LiveFacade` directly — see `links.effects.ts`'s own doc comment). */
export const LinksPageActions = createActionGroup({
  source: 'Links Page',
  events: {
    /** `initialTransport` computed synchronously by the facade — see `geo.actions.ts#trackRequested`'s identical rationale. */
    'Track Requested': props<{ hostId: string; assetId: string; initialTransport: AssetScopedTransport }>(),
    'Reset Requested': props<{ hostId: string }>(),
    'Host Released': props<{ hostId: string }>(),
    /** The one-shot Defect-A read — fired by the facade whenever the resolved transport is `'live'` and neither source has data yet. */
    'Seed Requested': props<{ hostId: string; assetId: string }>(),
    'Refresh Now Requested': props<{ hostId: string; assetId: string }>(),
    'Pin Requested': props<{ hostId: string; assetId: string; linkId: string }>(),
    'Release Pin Requested': props<{ hostId: string; assetId: string }>(),
  },
});

/** Sourced from the facade's own `effect()`s, not a component — see class doc above. */
export const LinksLiveActions = createActionGroup({
  source: 'Links Live',
  events: {
    'Transport Resolved': props<{ hostId: string; transport: AssetScopedTransport }>(),
    /** A fresh `LiveFacade.linksFor(assetId)` value — mirrors `LinksStore`'s own "mirror the live snapshot straight through" effect. */
    'Live Arrived': props<{ hostId: string; group: LinkGroupResponse }>(),
  },
});

export const LinksApiActions = createActionGroup({
  source: 'Links API',
  events: {
    'Poll Started': props<{ hostId: string }>(),
    'Poll Succeeded': props<{ hostId: string; group: LinkGroupResponse }>(),
    /** Silent-degrade — only clears `loading`, touches nothing else (see `LinksStore#pollOnce`'s own class doc). */
    'Poll Failed': props<{ hostId: string }>(),
    /** The "controller not mounted" 404 only. */
    'Poll Disabled': props<{ hostId: string }>(),
    'Pin Succeeded': props<{ hostId: string; group: LinkGroupResponse }>(),
    'Pin Failed': props<{ hostId: string; error: string }>(),
    'Release Pin Succeeded': props<{ hostId: string; group: LinkGroupResponse }>(),
    'Release Pin Failed': props<{ hostId: string; error: string }>(),
  },
});
