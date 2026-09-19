import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import type { ControlProfile, CreateControlProfileRequest, UpdateControlProfileRequest } from '../api/models';
import { ControlProfileApiActions, ControlProfilePageActions } from './state/control-profile.actions';
import { controlProfileFeature } from './state/control-profile.reducer';

/**
 * Replaces `ControlProfileStore` (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N7).
 *
 * **Page-provided since wave N-split** (NGRX-MIGRATION-PLAN.md §9), where the old store — and this
 * facade until then — was `providedIn: 'root'` so that the `/manage/controller` setup page and the
 * Fly cockpit's Controller drawer shared one load. Both hosts (`ControllerSetupPage`, `CockpitPage`)
 * now list this in their own `providers:` and register the `controlProfile` slice on their own
 * routes. **Behaviour change this carries:** each of those two pages loads the profile list for
 * itself, so opening the cockpit after saving a layout on the setup page re-reads it from the server
 * rather than reusing the setup page's copy. That is the direction CLAUDE.md architecture rule 7
 * asks for — the newest data wins — and it is what makes a `/settings` visitor stop downloading the
 * RC slice at all.
 *
 * <h2>Mutations still throw the real error — deliberately, unlike every other N7 facade</h2>
 * `ControllerSetupFacade` (the one feature-level caller of every write below) already owns turning a
 * failure into a toast — its own `save`/`createFrom`/`activate`/`remove` each `catch` and call
 * `describeHttpError(error)` themselves, and `rc-monitor.ts`/`fly-hud.ts` call `.load().catch(() =>
 * undefined)`, silently swallowing a failed load on purpose. Both were true of `ControlProfileStore`
 * itself (`@throws whatever the requests throw; callers own the message`), so this facade preserves
 * it rather than adding this slice's own `notifyFailure$` (NGRX-MIGRATION-PLAN.md §3 rule 6) on top
 * of an existing caller-owned toast — that would report one failure twice.
 *
 * The failure is still a dispatched, selectable `*Failed` action (rule 7, never a swallowed catch) —
 * `control-profile.effects.ts` computes `describeHttpError(error)` there, exactly like every other
 * slice's effects. What differs is only the last step: instead of a `notifyFailure$` toasting
 * `action.error`, the methods below re-throw it as a plain `Error`, so a caller's own
 * `describeHttpError(error)` call keeps working unchanged — `describeHttpError` on a non-
 * `HttpErrorResponse` `Error` returns `error.message` verbatim, so the composition is exact:
 * `describeHttpError(new Error(describeHttpError(real))) === describeHttpError(real)`. A raw
 * `HttpErrorResponse` instance could never have survived the trip through an action in the first
 * place — `strictActionSerializability` rejects any class instance whose prototype isn't
 * `Object.prototype` (`@ngrx/store`'s own `isPlainObject`), so preserving *that* exact instance
 * end-to-end was never on the table; preserving its rendered *message* is, and this does.
 */
@Injectable()
export class ControlProfileFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  /** Every layout this operator has, saved and built-in, newest saved first (server order). */
  readonly profiles = this.store.selectSignal(controlProfileFeature.selectProfiles);
  /** `undefined` until the first successful load — every picker that reads it renders empty, not wrong. */
  readonly catalog = this.store.selectSignal(controlProfileFeature.selectCatalog);
  readonly loading = this.store.selectSignal(controlProfileFeature.selectLoading);
  /** Whether a load has ever completed — distinguishes "no profiles" from "not asked yet". */
  readonly loaded = this.store.selectSignal(controlProfileFeature.selectLoaded);
  /** The catalogue's own danger flags and switch levels, for `ControlActionDispatcher`. */
  readonly rules = this.store.selectSignal(controlProfileFeature.selectRules);

  /**
   * Loads the layouts and the catalogue, once.
   *
   * @param force re-read even if a load already completed — what a setup page passes after a write
   * @throws whatever the request throws; callers own the message an operator sees
   */
  async load(force = false): Promise<void> {
    if (this.loading() || (this.loaded() && !force)) {
      return;
    }
    await dispatchAndAwait(
      this.store,
      this.actions$,
      ControlProfilePageActions.loadRequested({ force }),
      ControlProfileApiActions.loadSucceeded,
      ControlProfileApiActions.loadFailed,
      () => undefined,
      (action) => {
        throw new Error(action.error);
      },
    );
  }

  /** Starts a copy of the built-in for a vehicle kind, and returns it for the caller to select. */
  async create(request: CreateControlProfileRequest): Promise<ControlProfile> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      ControlProfilePageActions.createRequested({ request }),
      ControlProfileApiActions.createSucceeded,
      ControlProfileApiActions.createFailed,
      (action) => action.profile,
      (action) => {
        throw new Error(action.error);
      },
    );
  }

  /** Replaces one saved layout wholesale (the endpoint takes no partial write — see its DTO). */
  async update(id: string, request: UpdateControlProfileRequest): Promise<ControlProfile> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      ControlProfilePageActions.updateRequested({ id, request }),
      ControlProfileApiActions.updateSucceeded,
      ControlProfileApiActions.updateFailed,
      (action) => action.profile,
      (action) => {
        throw new Error(action.error);
      },
    );
  }

  /** Makes one layout the one a session on its vehicle kind engages with. */
  async activate(id: string): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      ControlProfilePageActions.activateRequested({ id }),
      ControlProfileApiActions.activateSucceeded,
      ControlProfileApiActions.activateFailed,
      () => undefined,
      (action) => {
        throw new Error(action.error);
      },
    );
  }

  async delete(id: string): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      ControlProfilePageActions.deleteRequested({ id }),
      ControlProfileApiActions.deleteSucceeded,
      ControlProfileApiActions.deleteFailed,
      () => undefined,
      (action) => {
        throw new Error(action.error);
      },
    );
  }
}
