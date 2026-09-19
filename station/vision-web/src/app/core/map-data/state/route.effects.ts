import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import type { Action } from '@ngrx/store';
import { catchError, filter, from, of, switchMap } from 'rxjs';
import { VisionApi } from '../../api/vision-api';
import { ROUTE_MAX_POINTS, buildAssetRoute, routeUsageLimit, type RouteSpan } from '../route-logic';
import { RouteApiActions, RoutePageActions } from './route.actions';

/**
 * The two-hop fetch behind `RouteStore.show`, ported verbatim (`listUsages` then one
 * `usageTimeline` per usage returned — never `usageTelemetry`, see `route-logic.ts`'s own doc
 * comment). `switchMap` replaces the old `generation` counter outright: a `Show Requested` that
 * arrives mid-fetch unsubscribes the previous inner pipeline, so a superseded fetch's eventual
 * resolution is silently dropped exactly like the old "generation !== this.generation" guard did
 * — structurally, not by hand-counted bookkeeping. `'off'` is filtered out here; the reducer alone
 * handles it (see `route.reducer.ts`'s own doc comment).
 */
export const show$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(RoutePageActions.showRequested),
      filter((action) => action.span !== 'off'),
      switchMap(({ assetId, span }) => from(fetchRoutes(api, assetId, span)).pipe(catchError(() => of(RouteApiActions.loadFailed())))),
    ),
  { functional: true },
);

async function fetchRoutes(api: VisionApi, assetId: string, span: RouteSpan): Promise<Action> {
  const usages = await api.listUsages({ assetId, limit: routeUsageLimit(span) });
  if (usages.length === 0) {
    return RouteApiActions.loaded({ routes: [], noUsages: true });
  }
  const timelines = await Promise.all(usages.map((usage) => api.usageTimeline(usage.usageId, { maxPoints: ROUTE_MAX_POINTS })));
  const routes = usages.map((usage, index) => buildAssetRoute(assetId, usage, timelines[index]));
  return RouteApiActions.loaded({ routes, noUsages: false });
}

export const routeEffects = { show$ };
