import { createActionGroup, emptyProps, props } from '@ngrx/store';
import type { CreateLayerRequest, LayerGrant, MapLayer } from '../../api/models';

/** Demand ref-counting (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3) plus every CRUD command
 *  `LayersFacade` issues — one-to-one with `LayersStore`'s own public methods. */
export const LayersPageActions = createActionGroup({
  source: 'Layers Page',
  events: {
    Activated: emptyProps(),
    Released: emptyProps(),
    'Create Requested': props<{ request: CreateLayerRequest }>(),
    'Rename Requested': props<{ layerId: string; name: string }>(),
    'Remove Requested': props<{ layerId: string }>(),
    'Set Grants Requested': props<{ layerId: string; grants: readonly LayerGrant[] }>(),
  },
});

/** Every outcome, plus the initial/reconcile `GET`'s own two terminal actions. */
export const LayersApiActions = createActionGroup({
  source: 'Layers API',
  events: {
    Loaded: props<{ layers: readonly MapLayer[] }>(),
    'Load Failed': emptyProps(),
    'Create Succeeded': props<{ layer: MapLayer }>(),
    'Create Failed': props<{ error: string }>(),
    'Rename Succeeded': props<{ layer: MapLayer }>(),
    'Rename Failed': props<{ error: string }>(),
    'Remove Succeeded': props<{ layerId: string }>(),
    'Remove Failed': props<{ error: string }>(),
    'Set Grants Succeeded': props<{ layer: MapLayer }>(),
    'Set Grants Failed': props<{ error: string }>(),
  },
});
