import { createActionGroup, props } from '@ngrx/store';
import type { AssetScopedTransport } from '../../live/live-fallback-logic';
import type { CorrectionResponse } from '../../api/models';

/**
 * `GeoFacade` itself, plus its own constructor `effect()` bridging `LiveFacade.connectionState()`
 * into this slice (never `@ngrx/effects` reading `LiveFacade` directly — see `geo.effects.ts`'s own
 * doc comment).
 */
export const GeoPageActions = createActionGroup({
  source: 'Geo Page',
  events: {
    /**
     * `initialTransport` is computed by the facade **synchronously**, at the same moment as the old
     * `GeoStore#track`'s own inline `resolveAssetScopedTransport(...)` call — carrying it on the
     * action (rather than always seeding the reducer at `'poll'` and correcting a beat later) is
     * what keeps a `track()` call that starts out already live from ever registering a poll at all,
     * exactly the old class's synchronous behaviour (`geo-store.spec.ts` "no poll registered while
     * live").
     */
    'Track Requested': props<{ hostId: string; assetId: string; initialTransport: AssetScopedTransport }>(),
    'Reset Requested': props<{ hostId: string }>(),
    /** This `GeoFacade` instance was destroyed — see `weather.actions.ts#hostReleased`'s identical rationale. */
    'Host Released': props<{ hostId: string }>(),
  },
});

/** Sourced from the facade's own `effect()`, not a component — see class doc. Still a "page" fact
 *  (a transport decision made in response to user-visible connectivity), never an API response. */
export const GeoLiveActions = createActionGroup({
  source: 'Geo Live',
  events: {
    'Transport Resolved': props<{ hostId: string; transport: AssetScopedTransport }>(),
  },
});

export const GeoApiActions = createActionGroup({
  source: 'Geo API',
  events: {
    /** `correction` is `undefined` when the tracked asset has no row in the fleet-wide response yet — the ordinary "no fix" case, never an error. */
    'Poll Succeeded': props<{ hostId: string; correction: CorrectionResponse | undefined }>(),
    /** The D9 flag-off 409 only — every other poll failure silent-degrades with no action at all (see `geo.effects.ts#pollOnce`). */
    'Poll Disabled': props<{ hostId: string }>(),
  },
});
