import { Injectable, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { OverlayHostRegistry } from './overlay-host-registry';
import { OverlayPageActions } from './state/overlay.actions';
import type { GlobalOverlayId } from './state/overlay.model';
import { overlayFeature } from './state/overlay.reducer';

/**
 * The overlay slice's read/dispatch boundary, replacing `GlobalOverlayStore`
 * (docs/plans/done/NGRX-MIGRATION-PLAN.md §8). Signals and methods keep that class's exact names —
 * `active`, `isOpen`, `open`, `close`, `toggle`, `register` — so every consumer's change is
 * `inject(GlobalOverlayStore)` → `inject(OverlayFacade)` and nothing else.
 *
 * `register()` is the one method that never touches the store: an `HTMLElement` is not serializable
 * state, so it goes straight to `OverlayHostRegistry`, the small root service `overlay.effects.ts`
 * also injects for its own Escape/outside-click handling. Everything else — exclusivity,
 * close-on-navigation, close-on-`Escape`, close-on-outside-click — is that file's job; this facade
 * only reads the resulting `active` id and dispatches the three page-intent actions a consumer can
 * still trigger directly (open/close/toggle a specific overlay by clicking its trigger).
 */
@Injectable({ providedIn: 'root' })
export class OverlayFacade {
  private readonly store = inject(Store);
  private readonly registry = inject(OverlayHostRegistry);

  /** The one open shell overlay, or `null`. */
  readonly active = this.store.selectSignal(overlayFeature.selectActive);

  isOpen(id: GlobalOverlayId): boolean {
    return this.active() === id;
  }

  open(id: GlobalOverlayId): void {
    this.store.dispatch(OverlayPageActions.opened({ id }));
  }

  /** Closes `id` if it is the one open; with no argument, closes whichever is open. */
  close(id?: GlobalOverlayId): void {
    this.store.dispatch(OverlayPageActions.closed({ id }));
  }

  toggle(id: GlobalOverlayId): void {
    this.store.dispatch(OverlayPageActions.toggled({ id }));
  }

  /** See `OverlayHostRegistry#register`'s own doc comment for the containment contract this relies on. */
  register(id: GlobalOverlayId, root: HTMLElement, trigger: HTMLElement): void {
    this.registry.register(id, root, trigger);
  }
}
