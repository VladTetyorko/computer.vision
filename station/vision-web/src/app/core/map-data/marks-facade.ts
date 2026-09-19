import { Injectable, inject } from '@angular/core';
import { Actions } from '@ngrx/effects';
import { Store } from '@ngrx/store';
import type { Affiliation, GeoPosition, GeolocateMarkRequest, MarkKind, VerificationState } from '../api/models';
import { dispatchAndAwait } from '../state/dispatch-bridge';
import { editMarkRequest, type MarkPalette } from './mark-logic';
import { MarksApiActions, MarksPageActions } from './state/marks.actions';
import { marksFeature } from './state/marks.reducer';

/**
 * `MarksStore`'s read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6).
 * `providedIn: 'root'`, exactly like the store it replaces — the marks list and palette are shared
 * app-wide (Fly, Command and every other map host see the same pins), only the interaction flags
 * (`armed`/`draft`) reset per genuine page change (`marks.reducer.ts#routeChanged`).
 *
 * Palette-vs-layers reconciliation and the route-change reset both moved into
 * `marks.effects.ts` (`reconcilePalette$`/`resetOnRouteChange$`) — this class does not inject
 * `LayersFacade` or `Router` at all, matching the plan's "read another slice through its own
 * selectors, never by injecting its facade/service into an effect" rule (generalized here to mean
 * this facade doesn't need either dependency to do its job).
 */
@Injectable({ providedIn: 'root' })
export class MarksFacade {
  private readonly store = inject(Store);
  private readonly actions$ = inject(Actions);

  readonly marks = this.store.selectSignal(marksFeature.selectAllMarks);
  /** `true` once the first load has settled (success or failure) — tells "loading" from "genuinely empty". */
  readonly loaded = this.store.selectSignal(marksFeature.selectLoaded);
  /** The map's own display projection — `tactical-map-logic.ts#TacticalMark`. */
  readonly displayMarks = this.store.selectSignal(marksFeature.selectDisplayMarks);
  readonly selectedMarkId = this.store.selectSignal(marksFeature.selectSelectedMarkId);
  readonly selected = this.store.selectSignal(marksFeature.selectSelected);
  readonly palette = this.store.selectSignal(marksFeature.selectPalette);
  readonly armed = this.store.selectSignal(marksFeature.selectArmed);
  /** The palette while armed, `null` otherwise — what the map cursor/ghost pin renders off. */
  readonly pendingPalette = this.store.selectSignal(marksFeature.selectPendingPalette);
  readonly draft = this.store.selectSignal(marksFeature.selectDraft);

  /** Registers demand — see `LayersFacade.activate`'s identical doc comment for the full rationale. */
  activate(): void {
    this.store.dispatch(MarksPageActions.activated());
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. */
  release(): void {
    this.store.dispatch(MarksPageActions.released());
  }

  // --- Selection ------------------------------------------------------------------------------

  select(id: string): void {
    this.store.dispatch(MarksPageActions.selected({ id }));
  }

  deselect(): void {
    this.store.dispatch(MarksPageActions.deselected());
  }

  // --- Palette + create-by-map-click -----------------------------------------------------------

  setKind(kind: MarkKind): void {
    this.store.dispatch(MarksPageActions.kindSet({ kind }));
  }

  setAffiliation(affiliation: Affiliation): void {
    this.store.dispatch(MarksPageActions.affiliationSet({ affiliation }));
  }

  setLayer(layerId: string | undefined): void {
    this.store.dispatch(MarksPageActions.layerSet({ layerId }));
  }

  arm(): void {
    this.store.dispatch(MarksPageActions.armed());
  }

  disarm(): void {
    this.store.dispatch(MarksPageActions.disarmed());
  }

  /** No-op unless armed — `MarksStore.handleMapClick`'s own contract, ported verbatim. */
  handleMapClick(position: GeoPosition): void {
    this.store.dispatch(MarksPageActions.mapClicked({ position }));
  }

  cancelDraft(): void {
    this.store.dispatch(MarksPageActions.draftCancelled());
  }

  /** `null` when there is nothing to confirm — mirrors `MarksStore.confirmDraft`'s own early return. */
  async confirmDraft(label: string, note?: string) {
    if (this.draft() === null) {
      return null;
    }
    return dispatchAndAwait(
      this.store,
      this.actions$,
      MarksPageActions.confirmDraftRequested({ label, note }),
      MarksApiActions.confirmDraftSucceeded,
      MarksApiActions.confirmDraftFailed,
      (action) => action.mark,
      () => null,
    );
  }

  // --- Geolocate / edit / clear / verify / promote / remove ------------------------------------

  async geolocate(assetId: string, overrides: Partial<GeolocateMarkRequest> = {}) {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      MarksPageActions.geolocateRequested({ assetId, overrides }),
      MarksApiActions.geolocateSucceeded,
      MarksApiActions.geolocateFailed,
      (action) => action.mark,
      () => null,
    );
  }

  async annotate(id: string, palette: MarkPalette, label: string, note?: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      MarksPageActions.patchRequested({ id, edit: editMarkRequest(palette, label, note) }),
      MarksApiActions.patchSucceeded,
      MarksApiActions.patchFailed,
      () => true,
      () => false,
    );
  }

  async clear(id: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      MarksPageActions.patchRequested({ id, edit: { status: 'CLEARED' } }),
      MarksApiActions.patchSucceeded,
      MarksApiActions.patchFailed,
      () => true,
      () => false,
    );
  }

  async verify(id: string, decision: Exclude<VerificationState, 'UNVERIFIED'>): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      MarksPageActions.verifyRequested({ id, decision }),
      MarksApiActions.verifySucceeded,
      MarksApiActions.verifyFailed,
      () => true,
      () => false,
    );
  }

  async promote(id: string, targetLayerId?: string): Promise<boolean> {
    return dispatchAndAwait(
      this.store,
      this.actions$,
      MarksPageActions.promoteRequested({ id, targetLayerId }),
      MarksApiActions.promoteSucceeded,
      MarksApiActions.promoteFailed,
      () => true,
      () => false,
    );
  }

  async remove(id: string): Promise<void> {
    await dispatchAndAwait(
      this.store,
      this.actions$,
      MarksPageActions.removeRequested({ id }),
      MarksApiActions.removeSucceeded,
      MarksApiActions.removeFailed,
      () => undefined,
      () => undefined,
    );
  }
}
