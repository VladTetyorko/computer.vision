import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { ThresholdsApiActions, ThresholdsPageActions } from './state/thresholds.actions';
import { thresholdsFeature } from './state/thresholds.reducer';

/**
 * The ops-thresholds slice's read/dispatch boundary (docs/plans/done/NGRX-MIGRATION-PLAN.md §2),
 * replacing `ThresholdsStore`. Fetches once per instance, exactly like the old store's own
 * constructor-triggered `void this.refresh()` — see that class's (deleted) doc comment, preserved by
 * `thresholds.effects.ts`.
 *
 * **Page-provided, not `providedIn: 'root'`** (wave N-split, NGRX-MIGRATION-PLAN.md §9). Its only
 * consumers are `FlyHud`/`FlyOsd`, both children of `CockpitPage`, which lists this in its own
 * `providers:`; that shared lifetime is what lets the `thresholds` slice be registered by
 * `features/fly/fly.page-routes.ts` instead of shipping in everyone's initial bundle. **Behaviour
 * change this carries:** "fetch once per instance" now means once per cockpit entry rather than once
 * per session. Thresholds are small, rarely-changing ops config, so a refetch on entering the
 * cockpit is both cheap and fresher — the honest direction (CLAUDE.md architecture rule 7).
 */
@Injectable()
export class ThresholdsFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  /** Always a real, usable value — see `thresholds.model.ts`'s "degrade honestly" note. */
  readonly battery = this.store.selectSignal(thresholdsFeature.selectBattery);
  /** Always a real, usable value — degrades to `DEFAULT_RC_THRESHOLDS` on a failed fetch *or* a
   *  response that simply doesn't carry `rc` yet. */
  readonly rc = this.store.selectSignal(thresholdsFeature.selectRc);
  /** `false` until the first fetch ever succeeds. */
  readonly loaded = this.store.selectSignal(thresholdsFeature.selectLoaded);
  readonly error = this.store.selectSignal(thresholdsFeature.selectError);

  constructor() {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      ThresholdsPageActions.refreshRequested(),
      ThresholdsApiActions.refreshSucceeded,
      ThresholdsApiActions.refreshFailed,
      () => undefined,
      () => undefined,
    );
  }
}
