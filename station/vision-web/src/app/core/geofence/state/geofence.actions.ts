import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { GeofenceZone, GeofenceZoneRequest } from '../../api/models';

/** Demand ref-counting (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3) plus every CRUD command
 *  `GeofenceFacade` issues. `'Replace Requested'` is the one shape behind
 *  `rename`/`setEnabled`/`redraw` — `GeofenceStore.replace`'s own "resend the full body" private
 *  method, ported as the single action all three facade methods dispatch. */
export const GeofencePageActions = createActionGroup({
  source: 'Geofence Page',
  events: {
    Activated: emptyProps(),
    Released: emptyProps(),
    'Create Requested': props<{ request: GeofenceZoneRequest }>(),
    'Replace Requested': props<{ zone: GeofenceZone; edit: Partial<GeofenceZoneRequest> }>(),
    'Remove Requested': props<{ zone: GeofenceZone }>(),
  },
});

/** Every outcome. `Remove Succeeded` carries the removed `zone` back so `undoOnRemove$` can offer
 *  an undo without the effect needing to remember what it just deleted. */
export const GeofenceApiActions = createActionGroup({
  source: 'Geofence API',
  events: {
    'Zones Loaded': props<{ zones: readonly GeofenceZone[] }>(),
    'Load Failed': emptyProps(),
    'Create Succeeded': props<{ zone: GeofenceZone }>(),
    'Create Failed': props<{ error: string }>(),
    'Replace Succeeded': props<{ zone: GeofenceZone }>(),
    'Replace Failed': props<{ error: string }>(),
    'Remove Succeeded': props<{ zone: GeofenceZone }>(),
    'Remove Failed': props<{ error: string }>(),
  },
});
