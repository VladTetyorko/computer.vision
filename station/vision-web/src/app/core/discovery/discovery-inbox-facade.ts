import { Injectable, inject } from '@angular/core';
import { Actions, ofType } from '@ngrx/effects';
import type { Action, ActionCreator, Creator } from '@ngrx/store';
import { Store } from '@ngrx/store';
import { firstValueFrom } from 'rxjs';
import { filter, take } from 'rxjs/operators';
import type {
  RegisterDeviceRequest,
  RegisterDiscoveryCandidateRequest,
  RegisterDiscoveryCandidateResponse,
} from '../api/models';
import { DiscoveryApiActions, DiscoveryPageActions } from './state/discovery.actions';
import { discoveryInboxFeature } from './state/discovery.reducer';

/**
 * Replaces `DiscoveryInboxStore` (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N7) — `providedIn:
 * 'root'`, same as that class: a root singleton with `activate()`/`release()` ref-counting, since
 * `discoveryInboxFeature`'s state is genuinely app-wide (unlike `WeatherFacade`/`GeoFacade`/
 * `LinksFacade`'s per-host `byHostId` slices, which need a fresh non-root instance per mounted page).
 * See `discovery.effects.ts#poll$`'s own doc comment for exactly how the ref-count (state) and the
 * live-vs-poll timer (effect) divide the old class's `applyTransport` state machine between them.
 */
@Injectable({ providedIn: 'root' })
export class DiscoveryInboxFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  readonly candidates = this.store.selectSignal(discoveryInboxFeature.selectCandidates);
  readonly sources = this.store.selectSignal(discoveryInboxFeature.selectSources);
  readonly loading = this.store.selectSignal(discoveryInboxFeature.selectLoading);
  readonly busyId = this.store.selectSignal(discoveryInboxFeature.selectBusyId);

  /** Registers interest — call once from a consumer's constructor. */
  activate(): void {
    this.store.dispatch(DiscoveryPageActions.activated());
  }

  /** The matching teardown — call from `DestroyRef.onDestroy`. */
  release(): void {
    this.store.dispatch(DiscoveryPageActions.released());
  }

  /** Forces an immediate re-read, independent of `activate()`'s own ref-count — exposed for
   *  API-compat with `DiscoveryInboxStore#refresh`, though no current caller uses it externally
   *  (the phase-driven `poll$` effect already fires one on every `activate()`/reconnect). */
  async refresh(): Promise<void> {
    await this.awaitCandidateAction<
      ReturnType<typeof DiscoveryApiActions.pollSucceeded> | ReturnType<typeof DiscoveryApiActions.pollFailed>
    >(DiscoveryPageActions.refreshRequested(), [DiscoveryApiActions.pollSucceeded, DiscoveryApiActions.pollFailed]);
  }

  /** One-click Add — registers the candidate as a new asset, returning a pointer at the created
   *  asset for the caller to route to, or `null` on failure (already toasted). */
  async register(id: string, request: RegisterDiscoveryCandidateRequest): Promise<RegisterDiscoveryCandidateResponse | null> {
    const outcome = await this.awaitCandidateAction<
      ReturnType<typeof DiscoveryApiActions.registerSucceeded> | ReturnType<typeof DiscoveryApiActions.registerFailed>
    >(DiscoveryPageActions.registerRequested({ id, request }), [DiscoveryApiActions.registerSucceeded, DiscoveryApiActions.registerFailed], id);
    return 'result' in outcome ? outcome.result : null;
  }

  /** The legacy two-step register-device-then-assign path — see `discovery.actions.ts#attachRequested`'s own doc comment. */
  async attach(id: string, deviceSpec: RegisterDeviceRequest, assetId: string): Promise<boolean> {
    const outcome = await this.awaitCandidateAction<
      ReturnType<typeof DiscoveryApiActions.attachSucceeded> | ReturnType<typeof DiscoveryApiActions.attachFailed>
    >(DiscoveryPageActions.attachRequested({ id, deviceSpec, assetId }), [DiscoveryApiActions.attachSucceeded, DiscoveryApiActions.attachFailed], id);
    return outcome.type === DiscoveryApiActions.attachSucceeded.type;
  }

  /** The atomic "this candidate *is* that asset" path (W3) — one call, no sweep-lag wait. */
  async attachCandidate(id: string, assetId: string): Promise<boolean> {
    const outcome = await this.awaitCandidateAction<
      ReturnType<typeof DiscoveryApiActions.attachCandidateSucceeded> | ReturnType<typeof DiscoveryApiActions.attachCandidateFailed>
    >(
      DiscoveryPageActions.attachCandidateRequested({ id, assetId }),
      [DiscoveryApiActions.attachCandidateSucceeded, DiscoveryApiActions.attachCandidateFailed],
      id,
    );
    return outcome.type === DiscoveryApiActions.attachCandidateSucceeded.type;
  }

  /** No confirm — reversible in spirit (the "show dismissed" toggle still shows it). */
  async dismiss(id: string): Promise<void> {
    await this.awaitCandidateAction<
      ReturnType<typeof DiscoveryApiActions.dismissSucceeded> | ReturnType<typeof DiscoveryApiActions.dismissFailed>
    >(
      DiscoveryPageActions.dismissRequested({ id }),
      [DiscoveryApiActions.dismissSucceeded, DiscoveryApiActions.dismissFailed],
      id,
    );
  }

  /** Undoes a `dismiss` — puts a `DISMISSED` candidate back to `NEW`. */
  async restore(id: string): Promise<void> {
    await this.awaitCandidateAction<
      ReturnType<typeof DiscoveryApiActions.restoreSucceeded> | ReturnType<typeof DiscoveryApiActions.restoreFailed>
    >(
      DiscoveryPageActions.restoreRequested({ id }),
      [DiscoveryApiActions.restoreSucceeded, DiscoveryApiActions.restoreFailed],
      id,
    );
  }

  /**
   * Dispatches `dispatched` and waits for the first of `types` — filtered by candidate `id` when one
   * is given, mirroring `LinksFacade#awaitHostAction`'s identical reasoning: `found-devices.html`
   * lets an operator dismiss/restore *different* candidate rows concurrently (only the busy row's own
   * buttons disable — see `found-devices.html`'s `[busy]="store.busyId() === candidate.id"`), so two
   * in-flight mutations of the same action *type* but different ids must never resolve each other's
   * promise. `refresh()` passes no `id` — there is only ever one in-flight poll outcome to wait on.
   */
  private awaitCandidateAction<A extends { type: string }>(
    dispatched: Action,
    types: readonly ActionCreator<string, Creator<any[], A>>[],
    id?: string,
  ): Promise<A> {
    const settled = firstValueFrom(
      this.actions$.pipe(
        ofType(...types),
        filter((action) => id === undefined || (action as unknown as { id?: string }).id === id),
        take(1),
      ),
    );
    this.store.dispatch(dispatched);
    return settled;
  }
}
