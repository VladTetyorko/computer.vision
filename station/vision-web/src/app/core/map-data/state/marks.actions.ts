import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { GeolocateMarkRequest, GeoPosition, MapMark, PatchMarkRequest, VerificationState } from '../../api/models';
import type { MarkPalette } from '../mark-logic';

type VerificationDecision = Exclude<VerificationState, 'UNVERIFIED'>;

/**
 * `Marks` slice. One `'Marks Page'` source carries every user/UI-driven intent (including the two
 * facts an effect notices rather than a click: `routeChanged` off `ROUTER_NAVIGATED`, and
 * `paletteReconciled` off a cross-slice read of `layers`) — same "a noticed fact is still a page
 * event" precedent as `OverlayPageActions.navigated` (`core/ui/state/overlay.actions.ts`).
 */
export const MarksPageActions = createActionGroup({
  source: 'Marks Page',
  events: {
    Activated: emptyProps(),
    Released: emptyProps(),
    Selected: props<{ id: string }>(),
    Deselected: emptyProps(),
    'Kind Set': props<{ kind: MarkPalette['kind'] }>(),
    'Affiliation Set': props<{ affiliation: MarkPalette['affiliation'] }>(),
    'Layer Set': props<{ layerId: string | undefined }>(),
    'Palette Reconciled': props<{ palette: MarkPalette }>(),
    Armed: emptyProps(),
    Disarmed: emptyProps(),
    'Map Clicked': props<{ position: GeoPosition }>(),
    'Draft Cancelled': emptyProps(),
    'Confirm Draft Requested': props<{ label: string; note?: string }>(),
    'Geolocate Requested': props<{ assetId: string; overrides: Partial<GeolocateMarkRequest> }>(),
    'Patch Requested': props<{ id: string; edit: PatchMarkRequest }>(),
    'Verify Requested': props<{ id: string; decision: VerificationDecision }>(),
    'Promote Requested': props<{ id: string; targetLayerId: string | undefined }>(),
    'Remove Requested': props<{ id: string }>(),
    /** Dispatched by `marks.effects.ts#resetOnRouteChange$` off every `ROUTER_NAVIGATED`, carrying
     *  the query/hash-stripped path. The reducer — not the effect — decides whether this is a
     *  genuine transition (see `marks.reducer.ts`'s doc comment). */
    'Route Changed': props<{ path: string }>(),
  },
});

export const MarksApiActions = createActionGroup({
  source: 'Marks API',
  events: {
    Loaded: props<{ marks: readonly MapMark[] }>(),
    'Load Failed': emptyProps(),
    'Confirm Draft Succeeded': props<{ mark: MapMark }>(),
    'Confirm Draft Failed': props<{ error: string }>(),
    'Geolocate Succeeded': props<{ mark: MapMark }>(),
    'Geolocate Failed': props<{ error: string }>(),
    'Patch Succeeded': props<{ mark: MapMark }>(),
    'Patch Failed': props<{ error: string }>(),
    'Verify Succeeded': props<{ mark: MapMark }>(),
    'Verify Failed': props<{ error: string }>(),
    'Promote Succeeded': props<{ mark: MapMark }>(),
    'Promote Failed': props<{ error: string }>(),
    'Remove Succeeded': props<{ id: string }>(),
    'Remove Failed': props<{ error: string }>(),
  },
});
