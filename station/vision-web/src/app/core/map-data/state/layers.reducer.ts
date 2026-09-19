import { createFeature, createReducer, createSelector, on } from '@ngrx/store';
import { LiveSocketActions } from '../../live/state/live.actions';
import { applyLayerEvents, contributableLayers, copLayer, defaultContributeLayerId, manageableLayers } from '../layers-logic';
import { LayersApiActions, LayersPageActions } from './layers.actions';
import type { LayersState } from './layers.model';
import { initialLayersState, layersAdapter } from './layers.model';
import type { MapLayer } from '../../api/models';

const { selectAll } = layersAdapter.getSelectors();

/** Upserts `layer` through `applyLayerEvents` (a synthetic `'updated'` event, exactly like
 *  `LayersStore.adopt`'s own private method) so a create/rename/grants response re-sorts the list
 *  the same way a live delta would, then `setAll`s the result — see `layers.model.ts`'s own
 *  "no sortComparer" doc comment for why this is how ordering is preserved. */
function adoptLayer(state: LayersState, layer: MapLayer): LayersState {
  const merged = applyLayerEvents(selectAll(state), [{ entity: 'layer', action: 'updated', layerId: layer.layerId, layer }]);
  return layersAdapter.setAll([...merged], state);
}

export const layersFeature = createFeature({
  name: 'layers',
  reducer: createReducer(
    initialLayersState,
    on(LayersPageActions.activated, (state) => ({ ...state, activeConsumers: state.activeConsumers + 1 })),
    on(LayersPageActions.released, (state) =>
      state.activeConsumers === 0 ? state : { ...state, activeConsumers: state.activeConsumers - 1 },
    ),
    on(LayersApiActions.loaded, (state, { layers }) => layersAdapter.setAll([...layers], { ...state, loaded: true })),
    on(LayersApiActions.loadFailed, (state) => ({ ...state, loaded: true })),
    on(LayersApiActions.createSucceeded, (state, { layer }) => adoptLayer(state, layer)),
    on(LayersApiActions.createFailed, (state) => state),
    on(LayersApiActions.renameSucceeded, (state, { layer }) => adoptLayer(state, layer)),
    on(LayersApiActions.renameFailed, (state) => state),
    // No undo offered here (unlike geofence) — deleting a layer cascades its marks/drawings
    // (MAP-REWORK-PLAN.md §4.1), so a re-create could never restore what the delete took with it.
    on(LayersApiActions.removeSucceeded, (state, { layerId }) => layersAdapter.removeOne(layerId, state)),
    on(LayersApiActions.removeFailed, (state) => state),
    on(LayersApiActions.setGrantsSucceeded, (state, { layer }) => adoptLayer(state, layer)),
    on(LayersApiActions.setGrantsFailed, (state) => state),
    // Runs unconditionally, with no active consumer required — same posture as every other
    // `core/map-data/**` slice's `map`-topic fold (`tracks.reducer.ts`'s own identical comment).
    on(LiveSocketActions.envelopeReceived, (state, { envelope }) =>
      envelope.type === 'map' ? layersAdapter.setAll([...applyLayerEvents(selectAll(state), [envelope.payload])], state) : state,
    ),
  ),
  extraSelectors: ({ selectLayersState }) => {
    const selectAllLayers = createSelector(selectLayersState, selectAll);
    const selectContributable = createSelector(selectAllLayers, contributableLayers);
    const selectManageable = createSelector(selectAllLayers, manageableLayers);
    const selectCop = createSelector(selectAllLayers, copLayer);
    const selectDefaultLayerId = createSelector(selectAllLayers, defaultContributeLayerId);
    return { selectAllLayers, selectContributable, selectManageable, selectCop, selectDefaultLayerId };
  },
});
