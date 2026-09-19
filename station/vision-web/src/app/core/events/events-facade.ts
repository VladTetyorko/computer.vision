import { Injectable, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { EventsPageActions } from './state/events.actions';
import { eventsFeature } from './state/events.reducer';

/**
 * Replaces `EventsStore` (docs/plans/active/NGRX-MIGRATION-PLAN.md wave N7) — `providedIn: 'root'`,
 * same as that class: a root singleton with `activate()`/`release()` ref-counting, since
 * `eventsFeature`'s state is genuinely app-wide (the Wall rail, the asset detail Events section, the
 * fleet-map/command markers, and the always-on app-shell notification bell all read the *same*
 * shared feed — that class's own doc comment's whole reason for being one singleton rather than
 * three page-scoped instances). See `events.effects.ts#poll$`'s own doc comment for exactly how the
 * ref-count (state) and the poll-vs-live decision (effect) divide the old class's `applyTransport`
 * state machine between them, and `events.effects.ts#notify$` for where the browser-notification
 * side effect now lives.
 *
 * **No `refresh()`** — unlike `DiscoveryInboxFacade`, `EventsStore` never exposed one; this facade's
 * public surface is intentionally just `events`/`activate()`/`release()`, matching that class's own
 * public API exactly (every real consumer — grepped before writing this file — only ever reads
 * `events()` and calls `activate()`/`release()`).
 */
@Injectable({ providedIn: 'root' })
export class EventsFacade {
  private readonly store = inject(Store);

  readonly events = this.store.selectSignal(eventsFeature.selectEvents);

  /** Registers interest — call once from a consumer's constructor. */
  activate(): void {
    this.store.dispatch(EventsPageActions.activated());
  }

  /** The matching teardown — call from `DestroyRef.onDestroy`. */
  release(): void {
    this.store.dispatch(EventsPageActions.released());
  }
}
