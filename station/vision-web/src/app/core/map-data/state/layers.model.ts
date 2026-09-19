import type { EntityState } from '@ngrx/entity';
import { createEntityAdapter } from '@ngrx/entity';
import type { MapLayer } from '../../api/models';

/**
 * `layers` slice model (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6) — replaces
 * `LayersStore`'s own `layersSignal`. `@ngrx/entity` is mandatory here (§3 rule 4).
 *
 * **No `sortComparer`**: `GET /api/map/layers` already serves COP-first-then-name
 * (`layers-logic.ts#sortLayers`'s own doc comment), and every mutation that can reorder the list
 * (an SSE upsert, a create/rename/grants response) runs through `applyLayerEvents`, which
 * re-sorts the plain array itself before the reducer calls `adapter.setAll(...)` on the result —
 * `setAll` then simply preserves that given order. A field-based `sortComparer` cannot express
 * "COP first, then localeCompare by name" as cleanly as reusing the existing pure sort, and would
 * fight with `applyLayerEvents` re-deriving that same order on every fold.
 */
export const layersAdapter = createEntityAdapter<MapLayer>({
  selectId: (layer) => layer.layerId,
});

export interface LayersState extends EntityState<MapLayer> {
  /** `true` once the first load has settled (success or failure) — tells "loading" from "genuinely no layers". */
  readonly loaded: boolean;
  /** Ref-count of live consumers — see `LayersFacade.activate`/`.release`. */
  readonly activeConsumers: number;
}

export const initialLayersState: LayersState = layersAdapter.getInitialState({
  loaded: false,
  activeConsumers: 0,
});

/**
 * Safety-net poll cadence — see `LayersStore`'s own doc comment (ported verbatim): the `map` live
 * topic is always-on and every layer delta is folded as it arrives, so this poll exists purely to
 * reconcile a connection that is genuinely down (D1 gate in `layers.effects.ts#gate$`).
 */
export const LAYERS_POLL_INTERVAL_MS = 30_000;

/**
 * How long the grants-reconcile effect waits for a *quiet* period after the last `layer`-entity
 * live delta before re-reading grants — see `LayersStore.scheduleGrantsReconcile`'s own doc
 * comment (ported verbatim): grants never travel over SSE (MAP-REWORK-PLAN.md §4.3), so a
 * revocation can only ever be observed through this debounced `GET`.
 */
export const GRANTS_RECONCILE_DEBOUNCE_MS = 1_000;
