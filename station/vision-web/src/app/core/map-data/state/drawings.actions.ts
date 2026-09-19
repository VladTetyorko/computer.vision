import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { DrawKind, MapDrawingResponse, PatchDrawingRequest } from '../../api/models';
import type { DrawingDraft } from '../../../shared/map/tactical-map/tactical-map-logic';

/** `Drawings` slice — same one-`'... Page'`-source shape as `marks.actions.ts` (see that file's own
 *  doc comment for why `routeChanged` belongs here despite being effect-noticed, not clicked). */
export const DrawingsPageActions = createActionGroup({
  source: 'Drawings Page',
  events: {
    Activated: emptyProps(),
    Released: emptyProps(),
    /** Passing the kind already active turns drawing off — `setMode`'s own toggle, decided in the reducer. */
    'Mode Set': props<{ kind: DrawKind | null }>(),
    'Drawing Stopped': emptyProps(),
    'Color Token Set': props<{ token: string }>(),
    Selected: props<{ id: string }>(),
    Deselected: emptyProps(),
    'Complete Draft Requested': props<{ draft: DrawingDraft; label?: string; colorToken?: string }>(),
    'Patch Requested': props<{ id: string; edit: PatchDrawingRequest }>(),
    'Remove Requested': props<{ id: string }>(),
    /** Dispatched by `drawings.effects.ts#resetOnRouteChange$` off every `ROUTER_NAVIGATED` — see
     *  `drawings.reducer.ts`'s own doc comment for the decision half. */
    'Route Changed': props<{ path: string }>(),
  },
});

export const DrawingsApiActions = createActionGroup({
  source: 'Drawings API',
  events: {
    Loaded: props<{ drawings: readonly MapDrawingResponse[] }>(),
    'Load Failed': emptyProps(),
    'Complete Draft Succeeded': props<{ drawing: MapDrawingResponse }>(),
    'Complete Draft Failed': props<{ error: string }>(),
    'Patch Succeeded': props<{ drawing: MapDrawingResponse }>(),
    'Patch Failed': props<{ error: string }>(),
    'Remove Succeeded': props<{ id: string }>(),
    'Remove Failed': props<{ error: string }>(),
  },
});
