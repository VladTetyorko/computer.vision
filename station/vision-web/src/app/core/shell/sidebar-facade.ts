import { Injectable, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { SidebarPageActions } from './state/sidebar.actions';
import { sidebarFeature } from './state/sidebar.reducer';

/**
 * The sidebar slice's read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md §2). Signals
 * and methods keep the `SidebarStore` names they replace, so `app.ts` and `AppSidebar` change only
 * the symbol they inject.
 */
@Injectable({ providedIn: 'root' })
export class SidebarFacade {
  private readonly store = inject(Store);

  readonly collapsed = this.store.selectSignal(sidebarFeature.selectCollapsed);
  readonly advancedOpen = this.store.selectSignal(sidebarFeature.selectAdvancedOpen);
  readonly upcomingOpen = this.store.selectSignal(sidebarFeature.selectUpcomingOpen);

  /** Called by the shell on every `NavigationEnd`. */
  enterRoute(fullBleed: boolean): void {
    this.store.dispatch(SidebarPageActions.routeEntered({ fullBleed }));
  }

  toggle(): void {
    this.store.dispatch(SidebarPageActions.toggled());
  }

  toggleAdvanced(): void {
    this.store.dispatch(SidebarPageActions.advancedToggled());
  }

  setAdvancedOpen(open: boolean): void {
    this.store.dispatch(SidebarPageActions.advancedSet({ open }));
  }

  toggleUpcoming(): void {
    this.store.dispatch(SidebarPageActions.upcomingToggled());
  }

  setUpcomingOpen(open: boolean): void {
    this.store.dispatch(SidebarPageActions.upcomingSet({ open }));
  }
}
