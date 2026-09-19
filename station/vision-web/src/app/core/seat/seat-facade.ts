import { Injectable, computed, inject, signal } from '@angular/core';
import { Store } from '@ngrx/store';
import type { SeatHolderResponse, SeatsResponse } from '../api/models';
import { singleOperatorSeats } from './seat-logic';
import { SeatPageActions } from './state/seat.actions';
import { seatFeature } from './state/seat.reducer';

/**
 * The seat slice's read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md §2, and that
 * plan's own N1/N2 correction). `@Injectable()`, **not** `providedIn: 'root'` — component-provided
 * exactly like `SeatStore` was (`crew.ts`/`cockpit.ts` list it in their own `providers:` array), so a
 * fresh instance starts/stops with the route. The underlying `byAssetId` state and its effects are
 * still registered once, app-wide, in `provideAppState()` — NgRx feature state is global by name
 * regardless of where a facade is provided — but each field here reads/writes only the one entry
 * keyed by {@link currentAssetId}, so two hosts can never observe each other's asset.
 *
 * {@link currentAssetId} and the "same id is a no-op" check are legitimate page-host-local
 * bookkeeping (mirrors the old store's own `lastTrackAssetId` field) — not state that belongs in the
 * shared slice, since it answers "what is *this* host currently looking at", never "what does the
 * app know about an asset."
 */
@Injectable()
export class SeatFacade {
  private readonly store = inject(Store);
  private readonly byAssetId = this.store.selectSignal(seatFeature.selectByAssetId);

  private readonly currentAssetId = signal<string | undefined>(undefined);

  /** Always defined — see `SeatStore`'s original class doc, preserved verbatim by this facade. */
  readonly seats = computed<SeatsResponse>(() => {
    const assetId = this.currentAssetId();
    if (assetId === undefined) {
      return singleOperatorSeats('');
    }
    return this.byAssetId()[assetId] ?? singleOperatorSeats(assetId);
  });

  readonly flight = computed<SeatHolderResponse>(() => this.seats().flight);
  readonly camera = computed<SeatHolderResponse>(() => this.seats().camera);
  readonly mayTakeFlight = computed<boolean>(() => this.seats().mayTakeFlight);
  readonly mayTakeCamera = computed<boolean>(() => this.seats().mayTakeCamera);
  readonly mayForceSeat = computed<boolean>(() => this.seats().mayForceSeat);

  /** Starts tracking `assetId`'s seats. A no-op when `assetId` is unchanged from the current
   *  session — mirrors `SeatStore#track`'s own `lastTrackAssetId` guard. */
  track(assetId: string): void {
    if (this.currentAssetId() === assetId) {
      return;
    }
    this.currentAssetId.set(assetId);
    this.store.dispatch(SeatPageActions.tracked({ assetId }));
  }

  /** Stops polling and renewing, and clears seat state back to the single-operator fallback. */
  reset(): void {
    const assetId = this.currentAssetId();
    this.currentAssetId.set(undefined);
    if (assetId !== undefined) {
      this.store.dispatch(SeatPageActions.reset({ assetId }));
    }
  }

  /** Forces an immediate re-read — used after a guarded write 409s. */
  refreshNow(): void {
    const assetId = this.currentAssetId();
    if (assetId !== undefined) {
      this.store.dispatch(SeatPageActions.refreshNowRequested({ assetId }));
    }
  }
}
