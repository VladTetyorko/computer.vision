import { Actions, ROOT_EFFECTS_INIT, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import { Store } from '@ngrx/store';
import { inject } from '@angular/core';
import { tap } from 'rxjs';
import { writePersistedString } from '../../panel-state';
import { ThemePageActions } from './theme.actions';
import { THEME_STORAGE_KEY, type Theme } from './theme.model';
import { themeFeature } from './theme.reducer';

/** Both values are written explicitly — `:root` alone would already render light with no attribute
 * at all, but "the attribute always names the current theme" is the simpler contract to verify, and
 * it matches `index.html`'s own inline bootstrap script. */
function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute('data-theme', theme);
}

/**
 * Applies the hydrated theme the moment the store is alive — this is what the old `ThemeStore`'s
 * constructor side effect did, and why `app.ts` used to inject it for nothing else. Idempotent in
 * the common case: `index.html`'s inline script has usually set the same attribute already.
 */
export const applyThemeOnBoot$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(ROOT_EFFECTS_INIT),
      concatLatestFrom(() => store.select(themeFeature.selectTheme)),
      tap(([, theme]) => applyTheme(theme)),
    ),
  { functional: true, dispatch: false },
);

/** Persist + apply on every change. Reads the theme *after* the reducer ran, so `Theme Toggled`
 * needs no knowledge of the current value at the call site. */
export const persistTheme$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(ThemePageActions.themeSelected, ThemePageActions.themeToggled),
      concatLatestFrom(() => store.select(themeFeature.selectTheme)),
      tap(([, theme]) => {
        writePersistedString(THEME_STORAGE_KEY, theme);
        applyTheme(theme);
      }),
    ),
  { functional: true, dispatch: false },
);

export const themeEffects = { applyThemeOnBoot$, persistTheme$ };
