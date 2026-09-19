import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import { ROUTER_NAVIGATED, type RouterNavigatedAction } from '@ngrx/router-store';
import type { Action } from '@ngrx/store';
import { Store } from '@ngrx/store';
import {
  EMPTY,
  Observable,
  catchError,
  combineLatest,
  concat,
  distinctUntilChanged,
  exhaustMap,
  from,
  map,
  of,
  switchMap,
  tap,
} from 'rxjs';
import { describeHttpError } from '../../api-error';
import { VisionApi } from '../../api/vision-api';
import { isLiveAvailable } from '../../live/live-fallback-logic';
import { liveFeature } from '../../live/state/live.reducer';
import { PollScheduler } from '../../poll-scheduler';
import { ToastService } from '../../toast.service';
import { layersFeature } from './layers.reducer';
import { createMarkRequest, reconcilePaletteLayer } from '../mark-logic';
import { MarksApiActions, MarksPageActions } from './marks.actions';
import { MARKS_POLL_INTERVAL_MS } from './marks.model';
import { marksFeature } from './marks.reducer';

/** Wraps `PollScheduler.schedule` as a cold tick `Observable` — copied per effects file, matching
 *  `seat.effects.ts#ticks$`'s own established precedent. */
function ticks$(scheduler: PollScheduler, periodMs: number): Observable<void> {
  return new Observable<void>((subscriber) => {
    const stop = scheduler.schedule(periodMs, () => subscriber.next());
    return () => stop();
  });
}

/** No query/hash — `MarksStore#resetOnRouteChange`'s own "path" concept, ported verbatim. */
function pathOf(url: string): string {
  return url.split('?')[0].split('#')[0];
}

function fetchMarks(api: VisionApi) {
  return from(api.listMapMarks()).pipe(
    map((marks) => MarksApiActions.loaded({ marks })),
    catchError(() => of(MarksApiActions.loadFailed())),
  );
}

type TransportMode = 'idle' | 'live' | 'poll';

/**
 * The demand/live gate — same continuous mode derivation as `tracks.effects.ts#gate$`/
 * `layers.effects.ts#gate$`/`geofence.effects.ts#gate$` (see any of those for the full rationale).
 */
export const gate$ = createEffect(
  (store = inject(Store), api = inject(VisionApi), scheduler = inject(PollScheduler)) => {
    const hasDemand$ = store.select(marksFeature.selectActiveConsumers).pipe(
      map((count) => count > 0),
      distinctUntilChanged(),
    );
    const liveAvailable$ = store.select(liveFeature.selectConnectionState).pipe(map(isLiveAvailable), distinctUntilChanged());
    const mode$ = combineLatest([hasDemand$, liveAvailable$]).pipe(
      map(([hasDemand, liveAvailable]): TransportMode => (!hasDemand ? 'idle' : liveAvailable ? 'live' : 'poll')),
      distinctUntilChanged(),
    );
    return mode$.pipe(
      switchMap((mode): Observable<Action> => {
        if (mode === 'idle') {
          return EMPTY;
        }
        if (mode === 'live') {
          return fetchMarks(api);
        }
        return concat(fetchMarks(api), ticks$(scheduler, MARKS_POLL_INTERVAL_MS).pipe(exhaustMap(() => fetchMarks(api))));
      }),
    );
  },
  { functional: true },
);

/**
 * BUG 3's translation half (see `marks.reducer.ts#routeChanged`'s own doc comment for the decision
 * half). Listens for `@ngrx/router-store`'s own `ROUTER_NAVIGATED` — same test-isolation rationale
 * as `overlay.effects.ts#closeOnNavigation$` (no `Router` injected here, so a spec that never
 * provides one is unaffected) — and translates every navigation into a domain fact the reducer
 * alone interprets. Fires on **every** navigation, query-param-only included; only the reducer
 * decides whether the path actually changed.
 */
export const resetOnRouteChange$ = createEffect(
  (actions$ = inject(Actions)) =>
    actions$.pipe(
      ofType(ROUTER_NAVIGATED),
      map((action) => MarksPageActions.routeChanged({ path: pathOf((action as RouterNavigatedAction).payload.event.urlAfterRedirects) })),
    ),
  { functional: true },
);

/**
 * `MarksStore`'s palette-reconcile `effect()`, ported to a continuous cross-slice read
 * (NGRX-MIGRATION-PLAN.md §9 — read another slice through its own selectors, never by injecting its
 * facade into an effect). `reconcilePaletteLayer` returns the *same* object when nothing needs to
 * change, so `distinctUntilChanged` (reference equality) suppresses the redundant dispatch — no
 * risk of looping back on the state write this same effect causes.
 */
export const reconcilePalette$ = createEffect(
  (store = inject(Store)) => {
    const contributableIds$ = store.select(layersFeature.selectContributable).pipe(map((layers) => layers.map((l) => l.layerId)));
    const fallback$ = store.select(layersFeature.selectDefaultLayerId);
    const palette$ = store.select(marksFeature.selectPalette);
    return combineLatest([palette$, contributableIds$, fallback$]).pipe(
      map(([palette, contributableIds, fallback]) => reconcilePaletteLayer(palette, contributableIds, fallback)),
      distinctUntilChanged(),
      map((palette) => MarksPageActions.paletteReconciled({ palette })),
    );
  },
  { functional: true },
);

/** Guards against a stray `Confirm Draft Requested` racing a `Draft Cancelled`/route reset — the
 *  facade already short-circuits on a null draft (see `marks-facade.ts#confirmDraft`), this is the
 *  defensive twin so the effect never trusts a caller to have enforced that itself. */
export const confirmDraft$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(MarksPageActions.confirmDraftRequested),
      concatLatestFrom(() => store.select(marksFeature.selectDraft)),
      switchMap(([{ label, note }, draft]) => {
        if (draft === null) {
          return EMPTY;
        }
        return from(api.createMapMark(createMarkRequest(draft, label, note))).pipe(
          map((mark) => MarksApiActions.confirmDraftSucceeded({ mark })),
          catchError((error: unknown) => of(MarksApiActions.confirmDraftFailed({ error: describeHttpError(error) }))),
        );
      }),
    ),
  { functional: true },
);

export const geolocate$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(MarksPageActions.geolocateRequested),
      concatLatestFrom(() => store.select(marksFeature.selectPalette)),
      switchMap(([{ assetId, overrides }, palette]) =>
        from(api.geolocateMapMark({ assetId, layerId: palette.layerId, kind: palette.kind, affiliation: palette.affiliation, ...overrides })).pipe(
          map((mark) => MarksApiActions.geolocateSucceeded({ mark })),
          catchError((error: unknown) => of(MarksApiActions.geolocateFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

/** Backs both `annotate()` (kind/affiliation/label/note) and `clear()` (`{status:'CLEARED'}`) —
 *  same unification as `MarksStore#applyPatch` itself, one PATCH endpoint underneath both. */
export const patch$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(MarksPageActions.patchRequested),
      switchMap(({ id, edit }) =>
        from(api.patchMapMark(id, edit)).pipe(
          map((mark) => MarksApiActions.patchSucceeded({ mark })),
          catchError((error: unknown) => of(MarksApiActions.patchFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const verify$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(MarksPageActions.verifyRequested),
      switchMap(({ id, decision }) =>
        from(api.verifyMapMark(id, { decision })).pipe(
          map((mark) => MarksApiActions.verifySucceeded({ mark })),
          catchError((error: unknown) => of(MarksApiActions.verifyFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const promote$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(MarksPageActions.promoteRequested),
      switchMap(({ id, targetLayerId }) =>
        from(api.promoteMapMark(id, { targetLayerId })).pipe(
          map((mark) => MarksApiActions.promoteSucceeded({ mark })),
          catchError((error: unknown) => of(MarksApiActions.promoteFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const remove$ = createEffect(
  (actions$ = inject(Actions), api = inject(VisionApi)) =>
    actions$.pipe(
      ofType(MarksPageActions.removeRequested),
      switchMap(({ id }) =>
        from(api.deleteMapMark(id)).pipe(
          map(() => MarksApiActions.removeSucceeded({ id })),
          catchError((error: unknown) => of(MarksApiActions.removeFailed({ error: describeHttpError(error) }))),
        ),
      ),
    ),
  { functional: true },
);

export const notifyVerifySuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(MarksApiActions.verifySucceeded),
      tap(({ mark }) => toasts.ok(mark.verification === 'CONFIRMED' ? `Confirmed "${mark.label}".` : `Rejected "${mark.label}".`)),
    ),
  { functional: true, dispatch: false },
);

export const notifyPromoteSuccess$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(MarksApiActions.promoteSucceeded),
      tap(({ mark }) => toasts.ok(`Promoted "${mark.label}" to the common picture.`)),
    ),
  { functional: true, dispatch: false },
);

/** `Load Failed` is deliberately excluded — `MarksStore.refresh`'s own silent background-poll degrade. */
export const notifyFailure$ = createEffect(
  (actions$ = inject(Actions), toasts = inject(ToastService)) =>
    actions$.pipe(
      ofType(
        MarksApiActions.confirmDraftFailed,
        MarksApiActions.geolocateFailed,
        MarksApiActions.patchFailed,
        MarksApiActions.verifyFailed,
        MarksApiActions.promoteFailed,
        MarksApiActions.removeFailed,
      ),
      tap((action) => toasts.error(action.error)),
    ),
  { functional: true, dispatch: false },
);

export const marksEffects = {
  gate$,
  resetOnRouteChange$,
  reconcilePalette$,
  confirmDraft$,
  geolocate$,
  patch$,
  verify$,
  promote$,
  remove$,
  notifyVerifySuccess$,
  notifyPromoteSuccess$,
  notifyFailure$,
};
