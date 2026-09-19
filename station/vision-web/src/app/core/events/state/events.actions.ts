import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { DetectionEvent } from '../../api/models';

/** `EventsStore#activate`/`#release`'s ref-count — see `events.model.ts#EventsState.activeConsumers`. */
export const EventsPageActions = createActionGroup({
  source: 'Events Page',
  events: {
    Activated: emptyProps(),
    Released: emptyProps(),
  },
});

/** `events: readonly DetectionEvent[]` is already oldest-first on this action — the one contract
 *  `events-logic.ts#mergeEvents`/`#advanceCursor` both expect, matching `EventsStore#pollOnce`'s
 *  own `[...incoming].reverse()` before ever touching `applyIncoming`. */
export const EventsApiActions = createActionGroup({
  source: 'Events API',
  events: {
    'Poll Succeeded': props<{ events: readonly DetectionEvent[] }>(),
    /** Silent degrade — mirrors `EventsStore#pollOnce`'s bare `catch {}`, no error surfaced anywhere. */
    'Poll Failed': emptyProps(),
  },
});
