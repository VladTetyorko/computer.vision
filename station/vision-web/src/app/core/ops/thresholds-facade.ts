import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { ThresholdsApiActions, ThresholdsPageActions } from './state/thresholds.actions';
import { thresholdsFeature } from './state/thresholds.reducer';

/**
 * The ops-thresholds slice's read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md §2),
 * replacing `ThresholdsStore`. Fetches once per instance, exactly like the old `providedIn: 'root'`
 * store's own constructor-triggered `void this.refresh()` — see that class's (deleted) doc comment,
 * preserved by `thresholds.effects.ts`.
 */
@Injectable({ providedIn: 'root' })
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
