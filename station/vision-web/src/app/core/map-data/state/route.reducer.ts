import { createFeature, createReducer, on } from '@ngrx/store';
import { RouteApiActions, RoutePageActions } from './route.actions';
import { initialRouteState } from './route.model';

/**
 * `route-store.ts#RouteStore.show`'s own logic, ported verbatim: `routes` is cleared
 * unconditionally the instant a `Show Requested` arrives, `'off'` short-circuits straight to
 * `idle` with no fetch, and anything else moves to `loading` — the effect (`route.effects.ts`)
 * decides only *whether* to fetch, this reducer decides *what the state looks like meanwhile*.
 */
export const routeFeature = createFeature({
  name: 'route',
  reducer: createReducer(
    initialRouteState,
    on(RoutePageActions.showRequested, (state, { span }) =>
      span === 'off'
        ? { ...state, routes: [], loadState: 'idle', noUsages: false }
        : { ...state, routes: [], loadState: 'loading', noUsages: false },
    ),
    on(RoutePageActions.hideRequested, () => initialRouteState),
    on(RouteApiActions.loaded, (state, { routes, noUsages }) => ({ ...state, routes, loadState: 'loaded', noUsages })),
    on(RouteApiActions.loadFailed, (state) => ({ ...state, routes: [], loadState: 'error' })),
  ),
});
