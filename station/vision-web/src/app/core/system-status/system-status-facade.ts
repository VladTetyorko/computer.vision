import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { SystemStatusApiActions, SystemStatusPageActions } from './state/system-status.actions';
import { systemStatusFeature } from './state/system-status.reducer';

/**
 * `SystemStatusStore`'s read/dispatch boundary (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N4b).
 * `providedIn: 'root'` and polling unconditionally from the moment `system-status.effects.ts#gate$`
 * is wired up at boot — **not** gated behind `/manage/system` being open — because
 * `shared/ui/app-sidebar/app-sidebar.ts`'s shell rollup dot needs {@link overall} on every page. The
 * routed page's own facade (`features/system-status/system-status-facade.ts`) reads this same
 * singleton rather than re-fetching, exactly as it did against the old store.
 *
 * See `system-status.reducer.ts`'s own doc comment for the "stale-but-present, never wiped" degrade
 * contract, and `system-status.effects.ts#gate$`'s for why there is no `activeConsumers` ref-count
 * term here — this store has always had exactly one consumer posture, unconditional.
 */
@Injectable({ providedIn: 'root' })
export class SystemStatusFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  /** `undefined` until the first fetch ever succeeds, or if it never has — see class doc. */
  readonly status = this.store.selectSignal(systemStatusFeature.selectStatus);
  readonly loading = this.store.selectSignal(systemStatusFeature.selectLoading);
  /** Present while the most recent poll failed — `status` (if any) is still the last-known-good value. */
  readonly error = this.store.selectSignal(systemStatusFeature.selectError);
  /** `undefined` before the first successful fetch — the shell rollup dot's own §5.2 input. */
  readonly overall = this.store.selectSignal(systemStatusFeature.selectOverall);

  /**
   * Re-reads `GET /api/system/status`. Silent-degrade on failure (see class doc) — never toasts,
   * mirroring `SystemStatusStore.refresh()`'s own unconditional "background poller" posture, kept
   * even for this explicit call since the routed page already shows {@link error} inline.
   */
  async refresh(): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      SystemStatusPageActions.refreshRequested(),
      SystemStatusApiActions.refreshSucceeded,
      SystemStatusApiActions.refreshFailed,
      () => undefined,
      () => undefined,
    );
  }
}
