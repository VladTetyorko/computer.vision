import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import { Store } from '@ngrx/store';
import { inject } from '@angular/core';
import { filter, map, tap } from 'rxjs';
import { writePersistedFlag } from '../../panel-state';
import { SidebarPageActions } from './sidebar.actions';
import {
  SIDEBAR_ADVANCED_OPEN_KEY,
  SIDEBAR_COLLAPSED_KEY,
  SIDEBAR_UPCOMING_OPEN_KEY,
} from './sidebar.model';
import { sidebarFeature } from './sidebar.reducer';

/**
 * Writes the cross-session default — and only for a toggle taken on an ordinary page. A full-bleed
 * route's own auto-collapse, and a manual expand over a video feed, both leave the stored key
 * untouched (the reducer already refuses to move `preference` there; this guard keeps the write out
 * of storage too, so an untouched key stays absent rather than being pinned to its default).
 */
export const persistSidebarPreference$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(SidebarPageActions.toggled),
      concatLatestFrom(() => store.select(sidebarFeature.selectSidebarState)),
      map(([, state]) => state),
      filter((state) => !state.fullBleed),
      tap((state) => writePersistedFlag(SIDEBAR_COLLAPSED_KEY, state.preference)),
    ),
  { functional: true, dispatch: false },
);

export const persistSidebarAdvanced$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(SidebarPageActions.advancedToggled, SidebarPageActions.advancedSet),
      concatLatestFrom(() => store.select(sidebarFeature.selectAdvancedOpen)),
      tap(([, open]) => writePersistedFlag(SIDEBAR_ADVANCED_OPEN_KEY, open)),
    ),
  { functional: true, dispatch: false },
);

export const persistSidebarUpcoming$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(SidebarPageActions.upcomingToggled, SidebarPageActions.upcomingSet),
      concatLatestFrom(() => store.select(sidebarFeature.selectUpcomingOpen)),
      tap(([, open]) => writePersistedFlag(SIDEBAR_UPCOMING_OPEN_KEY, open)),
    ),
  { functional: true, dispatch: false },
);

export const sidebarEffects = {
  persistSidebarPreference$,
  persistSidebarAdvanced$,
  persistSidebarUpcoming$,
};
