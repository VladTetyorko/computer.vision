import { Injectable, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { TracksPageActions } from './state/tracks.actions';
import { tracksFeature } from './state/tracks.reducer';

/**
 * `TracksStore`'s read/dispatch boundary (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N6).
 * `providedIn: 'root'`, exactly like the store it replaces — started at boot alongside
 * `MarksFacade`/`LayersFacade`/`DrawingsFacade`, one poller for every host.
 */
@Injectable({ providedIn: 'root' })
export class TracksFacade {
  private readonly store = inject(Store);

  readonly tracks = this.store.selectSignal(tracksFeature.selectAllTracks);
  readonly loaded = this.store.selectSignal(tracksFeature.selectLoaded);

  /** Registers demand — see `TracksStore.activate`'s original doc comment for the full rationale. */
  activate(): void {
    this.store.dispatch(TracksPageActions.activated());
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. */
  release(): void {
    this.store.dispatch(TracksPageActions.released());
  }
}
