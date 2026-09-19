import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { ProjectedTrackResponse } from '../../api/models';

/** Demand ref-counting (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3) — mirrors `TracksStore.activate`/`release`. */
export const TracksPageActions = createActionGroup({
  source: 'Tracks Page',
  events: {
    Activated: emptyProps(),
    Released: emptyProps(),
  },
});

/** The one REST call this slice makes — the safety-net GET, fired on activation and (while
 *  live is unavailable) on the 30s poll. Live deltas are folded straight off `LiveSocketActions
 *  .envelopeReceived` in the reducer (see `tracks.reducer.ts`) — there is no separate action for
 *  them, since nothing here needs to react to "a track changed", only to store the result. */
export const TracksApiActions = createActionGroup({
  source: 'Tracks API',
  events: {
    Loaded: props<{ tracks: readonly ProjectedTrackResponse[] }>(),
    'Load Failed': emptyProps(),
  },
});
