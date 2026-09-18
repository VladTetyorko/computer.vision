import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import { Store } from '@ngrx/store';
import { tap } from 'rxjs';
import { SettingsPageActions } from './settings.actions';
import { SETTINGS_STORAGE_KEY, type SettingsState } from './settings.model';
import { settingsFeature } from './settings.reducer';

/** Every field lands in one JSON blob (the old `SettingsStore`'s original shape) — writing the whole state
 *  on any change is simplest and matches the old `effect(() => this.persist())`, which always
 *  serialized every field regardless of which one actually moved. */
export const persistSettings$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(
        SettingsPageActions.advancedModeSet,
        SettingsPageActions.wallDensitySet,
        SettingsPageActions.mapLayerSet,
        SettingsPageActions.eventNotificationsSet,
        SettingsPageActions.flyAssetIdSet,
        SettingsPageActions.declutterLevelSet,
        SettingsPageActions.cropFollowEnabledSet,
      ),
      concatLatestFrom(() => store.select(settingsFeature.selectSettingsState)),
      tap(([, state]: [unknown, SettingsState]) => {
        localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(state));
      }),
    ),
  { functional: true, dispatch: false },
);

export const settingsEffects = { persistSettings$ };
