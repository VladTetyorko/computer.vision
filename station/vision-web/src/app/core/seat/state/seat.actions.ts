import { createActionGroup, props } from '@ngrx/store';
import type { SeatsResponse } from '../../api/models';

/** Every command a `SeatFacade` host issues — `track`/`reset`/`refreshNow`, each carrying the
 *  `assetId` explicitly so the effects (registered once, app-wide) can key their own per-asset work
 *  off the action alone, never off which facade instance happened to dispatch it. */
export const SeatPageActions = createActionGroup({
  source: 'Seat Page',
  events: {
    Tracked: props<{ assetId: string }>(),
    Reset: props<{ assetId: string }>(),
    'Refresh Now Requested': props<{ assetId: string }>(),
  },
});

/** Every outcome. `Seats Received` covers both a successful poll *and* a successful renewal — the
 *  old `SeatStore` applied the exact same `this.seatsSignal.set(response)` for both. A read failure
 *  is deliberately silent (no toast, no error message carried) — see `SeatStore`'s own "always a
 *  usable value" class doc — but still becomes a modeled action per docs/plans/active/
 *  NGRX-MIGRATION-PLAN.md §3 rule 7, rather than a swallowed catch. */
export const SeatApiActions = createActionGroup({
  source: 'Seat API',
  events: {
    'Seats Received': props<{ assetId: string; response: SeatsResponse }>(),
    'Read Failed': props<{ assetId: string }>(),
  },
});
