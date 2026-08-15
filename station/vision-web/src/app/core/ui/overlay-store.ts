import { DestroyRef, Injectable, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { UiStore } from './ui-store';

/**
 * Every overlay the app **shell** owns, as opposed to a page's own `UiStore` group
 * (docs/plans/done/UI-STATE-PLAN.md §2.1's "page overlays" tier, e.g. `fly`'s tool-rail or `asset-detail`'s
 * editors). A union, not a free string, so a typo (`'idenity-menu'`) fails to compile instead of
 * silently never matching anything — §3's guardrail is that "any new `GlobalOverlayId` must be
 * registered in the store's union type", which the compiler already enforces by construction.
 */
export type GlobalOverlayId = 'identity-menu' | 'notification-bell' | 'sidebar-mobile';

/**
 * The DOM one overlay owns, registered once by its own component (`register()` below) — `root` is
 * whatever element the outside-click listener should treat as "inside" this overlay (trigger *and*
 * dropdown alike), `trigger` is where focus returns when `Escape` closes it.
 */
interface OverlayHost {
  readonly root: HTMLElement;
  readonly trigger: HTMLElement;
}

/**
 * `GlobalOverlayStore` — the missing piece docs/plans/done/UI-STATE-PLAN.md §2.2 names. Reproduced live (§1):
 * open the notification bell, then the identity menu — both stay open; select an asset — both are
 * still open over the side panel; navigate `/assets` → `/devices` — both are **still open** on the
 * new page. Root cause (§1 D4/D5): `identity-chip`/`notification-bell` used to be native `<details>`
 * (state lives in the DOM, invisible to any store) mounted in the always-on shell (`app.html`), which
 * never unmounts — so "the page component is destroyed on navigation", this app's only cleanup
 * mechanism, never fires for exactly the two overlays that leak.
 *
 * This store gives the shell the three lifecycle rules a routed page gets for free and never needed
 * to ask for:
 *
 *  1. **Exclusive** (§1 D1 — "open the bell, then the identity menu: BOTH show open"). Composes
 *     `core/ui/ui-store.ts#UiStore` rather than reimplementing one-open-at-a-time — that store is
 *     already this app's single answer to "two overlays open together"; this class only adds the
 *     lifecycle rules below on top of it, per the plan's own explicit instruction not to re-solve
 *     what `UiStore` already solves.
 *  2. **Closes on every `NavigationEnd`** (§1 D2 — "navigate /assets → /devices: BOTH STILL OPEN").
 *     One `Router.events` subscription, owned **here**, not by `app.ts` — unlike
 *     `core/shell/sidebar-store.ts#SidebarStore.enterRoute` (which needs the route's own
 *     `data.fullBleed`, a per-navigation *value* only `app.ts`'s own router read has), this rule
 *     needs nothing but the event itself, so the store can be fully self-contained.
 *  3. **Closes on `Escape` and on a click outside the open overlay** (§2.2 rule 3) — one
 *     `document`-level listener pair, registered exactly once, here — not one pair per component.
 *     Three shell overlays each wiring their own `document.addEventListener` would reinvent this
 *     contract three slightly-different ways, exactly the kind of drift §3's guardrail exists to stop
 *     (the plan's own callout: "the `managerOnly` filter drifted between two nav renderers before
 *     NAV-IA-REDESIGN collapsed them into one").
 *
 * **Outside-click without fighting the trigger.** A component registers its *whole* host element as
 * `root` (`register()`), which already contains its own trigger button. The click listener below only
 * ever closes on a click landing **outside** `root` — a click on the trigger itself lands inside
 * `root`, so this listener no-ops for it and leaves the open/close decision entirely to the trigger's
 * own `(click)` handler. That handler is bound directly on the trigger element, so by normal DOM
 * bubble-order it always runs *before* this store's `document`-level listener sees the same click
 * (target-phase listeners fire before any ancestor's bubble-phase listener, and `document` is the
 * outermost ancestor there is) — so by the time this listener reads `active()`, it is already
 * whatever the trigger's own click just set it to. A click that just closed the dropdown is read as
 * "nothing is open" here and does nothing further (not closed a second time); a click that just
 * opened it is inside `root` and is left alone. No extra "was this the trigger" bookkeeping needed —
 * see `overlay-store.spec.ts`'s "outside click" suite for this traced through an actual dispatched
 * event, not just asserted.
 *
 * **`providedIn: 'root'`**, like `SidebarStore` (one instance for the one session) — but unlike
 * `SidebarStore`, this class owns its own `Router`/`document` wiring rather than leaning on `app.ts`,
 * since none of it depends on anything only `app.ts` already has to compute; the store is entirely
 * self-sufficient the moment anything injects it (which happens naturally the first time
 * `identity-chip`/`notification-bell`/the sidebar's mobile sheet mount — all three only ever mount
 * once authenticated, so there is nothing for this store to coordinate before then either).
 * `inject(Router)`/`inject(DestroyRef)` are called here, in the constructor's own injection context —
 * **not** inside the `keydown`/`click` callbacks, which run long after construction and are therefore
 * not an injection context (`app.ts`'s own doc comment on its `afterNextRender` call records this
 * repo's prior NG0203 from getting exactly this wrong).
 */
@Injectable({ providedIn: 'root' })
export class GlobalOverlayStore {
  /** Transient by construction — no `storageKey` — a global overlay must never survive a reload; a
   *  fresh page load always starts with every shell overlay closed. */
  private readonly ui = new UiStore();
  private readonly hosts = new Map<GlobalOverlayId, OverlayHost>();

  /** The one open overlay id, or `null`. Re-typed from `UiStore.active`'s bare `string | null` — sound,
   *  not just asserted, because every write below (`open`/`toggle`) only ever passes a `GlobalOverlayId`. */
  readonly active = computed(() => this.ui.active() as GlobalOverlayId | null);

  constructor() {
    inject(Router)
      .events.pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.ui.close());

    const onKeydown = (event: KeyboardEvent): void => this.handleKeydown(event);
    const onClick = (event: MouseEvent): void => this.handleDocumentClick(event);
    document.addEventListener('keydown', onKeydown);
    document.addEventListener('click', onClick);
    inject(DestroyRef).onDestroy(() => {
      document.removeEventListener('keydown', onKeydown);
      document.removeEventListener('click', onClick);
    });
  }

  isOpen(id: GlobalOverlayId): boolean {
    return this.ui.isOpen(id);
  }

  open(id: GlobalOverlayId): void {
    this.ui.open(id);
  }

  /** Closes `id` if it is the one open; with no argument, closes whichever is open. Mirrors
   *  `UiStore.close`'s own "a stale close from an already-replaced overlay is a no-op" semantic —
   *  preserved deliberately, not reimplemented, since `open`/`close` here are thin pass-throughs. */
  close(id?: GlobalOverlayId): void {
    this.ui.close(id);
  }

  toggle(id: GlobalOverlayId): void {
    this.ui.toggle(id);
  }

  /**
   * Registers `id`'s owning DOM — called once by each overlay's own component as soon as its trigger
   * exists (`identity-chip.ts`/`notification-bell.ts` do it from an `effect()` over a `viewChild`,
   * since the shell only renders once `AuthStore.user()` resolves, so the trigger isn't there on the
   * very first tick). `root` must contain `trigger` — see the class doc's "outside-click without
   * fighting the trigger" paragraph for why that containment is the entire mechanism. Re-registering
   * the same `id` (e.g. the sidebar's mobile-sheet hamburger, which is removed from the DOM and
   * recreated by its own `@if`/`@else` swap every time the sheet opens/closes) simply overwrites the
   * previous entry — safe, since a stale reference left behind for one closed-to-open cycle is used
   * for nothing but a best-effort `.focus()` call that already no-ops harmlessly on a detached node.
   */
  register(id: GlobalOverlayId, root: HTMLElement, trigger: HTMLElement): void {
    this.hosts.set(id, { root, trigger });
  }

  private handleKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape') {
      return;
    }
    const openId = this.active();
    if (!openId) {
      return;
    }
    this.ui.close();
    // §4 "Escape must return focus to the trigger" — a keyboard/screen-reader user who dismisses a
    // dropdown must land back on the control that opened it, not lose focus into the page body.
    // `isConnected` guards the one host whose trigger can legitimately be gone at this exact instant
    // (the sidebar's mobile hamburger swaps out for a scrim while the sheet is open) — a harmless
    // no-op rather than focusing a detached node.
    const host = this.hosts.get(openId);
    if (host?.trigger.isConnected) {
      host.trigger.focus();
    }
  }

  private handleDocumentClick(event: MouseEvent): void {
    const openId = this.active();
    if (!openId) {
      return;
    }
    const host = this.hosts.get(openId);
    const target = event.target;
    if (host && target instanceof Node && host.root.contains(target)) {
      return;
    }
    this.ui.close();
  }
}
