import { Injectable } from '@angular/core';
import type { GlobalOverlayId } from './state/overlay.model';

/**
 * The DOM one overlay owns, registered once by its own component (`register()` below) — `root` is
 * whatever element the outside-click listener should treat as "inside" this overlay (trigger *and*
 * dropdown alike), `trigger` is where focus returns when `Escape` closes it.
 */
export interface OverlayHost {
  readonly root: HTMLElement;
  readonly trigger: HTMLElement;
}

/**
 * The non-serializable half of the old `GlobalOverlayStore` (docs/plans/active/NGRX-MIGRATION-PLAN.md
 * §8 "GlobalOverlayStore splits in two"). Live `HTMLElement` references can never go into NgRx
 * state — `strictStateSerializability` would trip on the first `register()` call, and rightly so —
 * so this stays a small, plain `providedIn: 'root'` service the overlay slice's effects and facade
 * inject directly, entirely outside the store.
 *
 * Named without a `Store` suffix on purpose: `core/ui/architecture.spec.ts`'s NgRx layering guard
 * (and the routed-page guard) both flag anything ending in `Store`, and this class is neither NgRx
 * state nor the app's other per-instance `UiStore` — it is one root-wide DOM lookup table.
 */
@Injectable({ providedIn: 'root' })
export class OverlayHostRegistry {
  private readonly hosts = new Map<GlobalOverlayId, OverlayHost>();

  /**
   * Registers `id`'s owning DOM — called once by each overlay's own component as soon as its
   * trigger exists (`identity-chip.ts`/`notification-bell.ts` do it from an `effect()` over a
   * `viewChild`). `root` must contain `trigger` — see `overlay.effects.ts`'s outside-click effect for
   * why that containment is the entire "don't fight the trigger's own click handler" mechanism.
   * Re-registering the same `id` (e.g. the sidebar's mobile-sheet hamburger, removed from the DOM
   * and recreated by its own `@if`/`@else` swap every time the sheet opens/closes) simply overwrites
   * the previous entry.
   */
  register(id: GlobalOverlayId, root: HTMLElement, trigger: HTMLElement): void {
    this.hosts.set(id, { root, trigger });
  }

  /** `undefined` when `id` was never registered — every caller degrades safely for that case (a
   *  harmless no-op focus, or "every click reads as outside"), never a thrown lookup error. */
  get(id: GlobalOverlayId): OverlayHost | undefined {
    return this.hosts.get(id);
  }
}
