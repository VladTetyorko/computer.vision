import { describe, expect, it } from 'vitest';
import type { AssetRoute } from '../route-logic';
import { RouteApiActions, RoutePageActions } from './route.actions';
import { initialRouteState } from './route.model';
import { routeFeature } from './route.reducer';

const { reducer } = routeFeature;

function route(overrides: Partial<AssetRoute> = {}): AssetRoute {
  return { assetId: 'a-1', usageId: 'u-1', startedAt: '2026-09-01T00:00:00Z', points: [], truncated: false, ...overrides };
}

describe('route reducer', () => {
  it('starts idle, no routes, noUsages false', () => {
    expect(initialRouteState).toEqual({ routes: [], loadState: 'idle', noUsages: false });
  });

  it('showRequested with span "off" clears to idle with no fetch pending', () => {
    const loaded = reducer(initialRouteState, RouteApiActions.loaded({ routes: [route()], noUsages: false }));
    const state = reducer(loaded, RoutePageActions.showRequested({ assetId: 'a-1', span: 'off' }));
    expect(state).toEqual({ routes: [], loadState: 'idle', noUsages: false });
  });

  it('showRequested with a real span clears routes unconditionally and moves to loading', () => {
    const loaded = reducer(initialRouteState, RouteApiActions.loaded({ routes: [route()], noUsages: true }));
    const state = reducer(loaded, RoutePageActions.showRequested({ assetId: 'a-1', span: 'last' }));
    expect(state).toEqual({ routes: [], loadState: 'loading', noUsages: false });
  });

  it('loaded stores the routes and the honest noUsages flag, moving to loaded', () => {
    const populated = reducer(initialRouteState, RouteApiActions.loaded({ routes: [route()], noUsages: false }));
    expect(populated).toEqual({ routes: [route()], loadState: 'loaded', noUsages: false });

    const empty = reducer(initialRouteState, RouteApiActions.loaded({ routes: [], noUsages: true }));
    expect(empty).toEqual({ routes: [], loadState: 'loaded', noUsages: true });
  });

  it('loadFailed clears routes and moves to error', () => {
    const loading = reducer(initialRouteState, RoutePageActions.showRequested({ assetId: 'a-1', span: 'last' }));
    const failed = reducer(loading, RouteApiActions.loadFailed());
    expect(failed).toEqual({ routes: [], loadState: 'error', noUsages: false });
  });

  it('hideRequested resets to the initial state outright', () => {
    const loaded = reducer(initialRouteState, RouteApiActions.loaded({ routes: [route()], noUsages: false }));
    expect(reducer(loaded, RoutePageActions.hideRequested())).toEqual(initialRouteState);
  });
});
