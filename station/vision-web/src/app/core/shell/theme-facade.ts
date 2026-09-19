import { Injectable, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { ThemePageActions } from './state/theme.actions';
import type { Theme } from './state/theme.model';
import { themeFeature } from './state/theme.reducer';

/**
 * The theme slice's read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md §2) — the only
 * type that injects NgRx's `Store` for this slice. Components inject this, never the store: the
 * signal and the two methods are named exactly as the `ThemeStore` they replace, so a consumer's
 * whole change is which symbol it injects.
 */
@Injectable({ providedIn: 'root' })
export class ThemeFacade {
  private readonly store = inject(Store);

  readonly theme = this.store.selectSignal(themeFeature.selectTheme);

  setTheme(theme: Theme): void {
    this.store.dispatch(ThemePageActions.themeSelected({ theme }));
  }

  toggle(): void {
    this.store.dispatch(ThemePageActions.themeToggled());
  }
}
