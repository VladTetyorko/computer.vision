import { inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatLatestFrom } from '@ngrx/operators';
import { ROUTER_NAVIGATED } from '@ngrx/router-store';
import { Store, type Action } from '@ngrx/store';
import { filter, fromEvent, map, tap } from 'rxjs';
import { OverlayHostRegistry } from '../overlay-host-registry';
import { OverlayPageActions } from './overlay.actions';
import type { GlobalOverlayId } from './overlay.model';
import { overlayFeature } from './overlay.reducer';

/**
 * The three lifecycle rules the shell needs and no routed page does — reproduced from the old
 * `GlobalOverlayStore`'s own (long, careful) class doc, which this file's tests still cite by
 * section:
 *
 *  1. **Closes on every completed navigation** (docs/plans/done/UI-STATE-PLAN.md §1 D2 — "navigate
 *     `/assets` → `/devices`: BOTH STILL OPEN"). Listens for `@ngrx/router-store`'s own
 *     `ROUTER_NAVIGATED` action rather than injecting `Router` directly — `provideRouterStore()` is
 *     already registered app-wide in `app.config.ts`, so in the running app this fires exactly when
 *     `NavigationEnd` used to. The point of going through the `Actions` stream instead of `Router`
 *     is test isolation: `core/state/app-state.ts` registers this slice for every spec that calls
 *     `provideAppState()` (`theme-facade.spec.ts`/`sidebar-facade.spec.ts` included), and neither
 *     provides a `Router` — injecting `Router` here unconditionally would throw a `NullInjectorError`
 *     the moment `ROOT_EFFECTS_INIT` subscribed this effect in *any* of them.
 *  2. **Closes on `Escape`, returning focus to the trigger** (§4 a11y bullet) — one `document`-level
 *     `keydown` listener, not one per overlay component.
 *  3. **Closes on a click outside the open overlay, without fighting the trigger's own handler**
 *     (§2.2 rule 3). A component registers its *whole* host element as `root`
 *     (`OverlayHostRegistry#register`), which already contains its own trigger button. This effect
 *     only ever closes on a click landing **outside** `root` — a click on the trigger itself lands
 *     inside `root`, so this effect no-ops for it and leaves the open/close decision entirely to the
 *     trigger's own `(click)` handler, which — by ordinary DOM bubble order — always runs first.
 *
 * All three read `overlayFeature.selectActive` before deciding whether to act, matching the old
 * store's own `if (!openId) return;` guards — a `document`/`Actions` listener that fires constantly
 * (every click, every keydown, every navigation, across the whole app) must never dispatch a no-op
 * `*Failed`-shaped churn into the store when nothing is open.
 */
export const closeOnNavigation$ = createEffect(
  (actions$ = inject(Actions), store = inject(Store)) =>
    actions$.pipe(
      ofType(ROUTER_NAVIGATED),
      concatLatestFrom(() => store.select(overlayFeature.selectActive)),
      filter((tuple): tuple is [Action, GlobalOverlayId] => tuple[1] !== null),
      map(() => OverlayPageActions.navigated()),
    ),
  { functional: true },
);

export const closeOnEscape$ = createEffect(
  (store = inject(Store), registry = inject(OverlayHostRegistry)) =>
    fromEvent<KeyboardEvent>(document, 'keydown').pipe(
      filter((event) => event.key === 'Escape'),
      concatLatestFrom(() => store.select(overlayFeature.selectActive)),
      filter((tuple): tuple is [KeyboardEvent, GlobalOverlayId] => tuple[1] !== null),
      tap(([, id]) => {
        // §4 "Escape must return focus to the trigger" — a keyboard/screen-reader user who dismisses
        // a dropdown must land back on the control that opened it, not lose focus into the page
        // body. `isConnected` guards the one host whose trigger can legitimately be gone at this
        // exact instant (the sidebar's mobile hamburger swaps out for a scrim while the sheet is
        // open) — a harmless no-op rather than focusing a detached node.
        const host = registry.get(id);
        if (host?.trigger.isConnected) {
          host.trigger.focus();
        }
      }),
      map(() => OverlayPageActions.escapePressed()),
    ),
  { functional: true },
);

export const closeOnOutsideClick$ = createEffect(
  (store = inject(Store), registry = inject(OverlayHostRegistry)) =>
    fromEvent<MouseEvent>(document, 'click').pipe(
      concatLatestFrom(() => store.select(overlayFeature.selectActive)),
      filter((tuple): tuple is [MouseEvent, GlobalOverlayId] => tuple[1] !== null),
      filter(([event, id]) => {
        // No registered host ⇒ every click is "outside" — fails safe (closes), never fails open.
        const host = registry.get(id);
        const target = event.target;
        return !(host && target instanceof Node && host.root.contains(target));
      }),
      map(() => OverlayPageActions.outsideClicked()),
    ),
  { functional: true },
);

export const overlayEffects = {
  closeOnNavigation$,
  closeOnEscape$,
  closeOnOutsideClick$,
};
