import { Injectable, computed, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import type { RouteSpan } from './route-logic';
import { RouteApiActions, RoutePageActions } from './state/route.actions';
import { routeFeature } from './state/route.reducer';

/**
 * `RouteStore`'s read/dispatch boundary (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N6).
 * `@Injectable()`, **not** `providedIn: 'root'` — same posture as the store it replaces:
 * `CommandPage` lists this in its own `providers`, so a fresh instance (and the `route` slice's
 * per-page-visit lifecycle) starts the moment that page mounts. Unlike `SeatFacade`'s
 * `Record<assetId, …>` keying, `route`'s own state has nothing to key by — only one asset's route
 * is ever shown at a time (§3.4's frozen rule) — so nothing here guards against a second
 * concurrent host; there has only ever been one (`CommandPage`).
 *
 * A fresh mount dispatches {@link hide} in its constructor so a stale global slice value from a
 * *previous* visit to this page never flashes on screen before the first real `show()` call — the
 * `route` slice is registered once, app-wide, in `provideAppState()`, so without this reset a
 * second `CommandPage` visit would otherwise briefly render whatever the last visit left behind.
 */
@Injectable()
export class RouteFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  readonly routes = this.store.selectSignal(routeFeature.selectRoutes);
  private readonly loadState = this.store.selectSignal(routeFeature.selectLoadState);
  readonly state = this.loadState;
  readonly loading = computed(() => this.loadState() === 'loading');
  readonly error = computed(() => this.loadState() === 'error');
  readonly noUsages = this.store.selectSignal(routeFeature.selectNoUsages);

  constructor() {
    this.store.dispatch(RoutePageActions.hideRequested());
  }

  /** Fetches and shows `assetId`'s route(s) for `span` — see `RouteStore.show`'s own doc comment
   *  (this class ports its contract verbatim; only the mechanism moved to NgRx). */
  async show(assetId: string, span: RouteSpan): Promise<void> {
    if (span === 'off') {
      this.store.dispatch(RoutePageActions.showRequested({ assetId, span }));
      return;
    }
    await dispatchAndAwait(
      this.store,
      this.actions$,
      RoutePageActions.showRequested({ assetId, span }),
      RouteApiActions.loaded,
      RouteApiActions.loadFailed,
      () => undefined,
      () => undefined,
    );
  }

  /** Clears the map and supersedes any in-flight fetch. */
  hide(): void {
    this.store.dispatch(RoutePageActions.hideRequested());
  }
}
