import { ChangeDetectionStrategy, Component, DestroyRef, afterNextRender, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthStore } from './core/auth/auth-store';
import { FleetStore } from './core/fleet/fleet-store';
import { LeafletWarmup } from './core/leaflet-warmup';
import { SidebarStore } from './core/shell/sidebar-store';
import { AppSidebar } from './shared/ui/app-sidebar/app-sidebar';
import { ToastHost } from './shared/ui/toast-host';
import { UndoToast } from './shared/ui/undo-toast';

/**
 * The app shell (docs/NAV-IA-REDESIGN-PLAN.md §2.1, docs/design/00-shell.md) — a persistent left
 * sidebar (`shared/ui/app-sidebar/**`) plus a `<main>` that scrolls independently, replacing the old
 * sticky `.app-header` (three mode dropdowns) and, with it, the three `/operate`/`/monitor`/`/manage`
 * hub pages the dropdowns used to duplicate. See `AppSidebar`'s own class doc for the specific F1/
 * F2/F3/F10 findings this removes — this class only owns what a full-page shell must: showing/hiding
 * the sidebar around the session's own lifecycle, the full-bleed auto-collapse read, and the global
 * `[` shortcut. Everything about *what* the sidebar renders lives in `AppSidebar` itself.
 *
 * **Hidden while unauthenticated** (`@if (auth.user())` in `app.html`) — the exact same "renders
 * nothing while `user()` is null" rule `shared/ui/identity-chip.ts` already follows, so the login
 * page (outside `authGuard`'s route group, but still a child of this component) never shows a
 * sidebar with nowhere real to send its clicks, and there's no separate "am I on `/login`" route
 * check to keep in sync with the guard's own routing.
 *
 * **Dev parity (`vision.auth.enabled=false`)**: `AuthStore` resolves the fixed dev principal to
 * `topRole: 'ADMIN'` exactly as before this task (unchanged mechanism, `core/org/org-guard.ts`'s own
 * doc comment) — `auth.user()` is non-null the instant `loadMe()` settles, so the sidebar renders
 * with the full ADMIN-scoped set, same as a real ADMIN session. Nothing here reads `authEnabled`
 * directly.
 *
 * **Full-bleed auto-collapse** (docs/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5, F11): `/fly`, `/wall`,
 * `/command` each carry `data: { fullBleed: true }` (a concurrent task's own route change, read
 * here, never written). `routeTreeHasFullBleed` walks the *activated-route tree*, not just the
 * top-level route, because the flag can sit on any segment a lazy-loaded feature's own
 * `<name>.routes.ts` defines — reading only `router.routerState.snapshot.root.data` would miss it
 * entirely once a feature nests its real route under a path-less parent (this app's `authGuard`
 * wrapper already does exactly that for every route).
 *
 * This class owns the one router subscription and pushes each result into `SidebarStore.enterRoute`;
 * the store layers it *under* any manual toggle (see its own precedence doc). Auto-collapse is a
 * **default, not a lock** — an earlier revision expressed it as `collapsed() || fullBleed()` and
 * disabled the toggle while full-bleed, which left the sidebar impossible to expand on `/fly`,
 * `/wall` and `/command`. Both the head chevron and this class's `[` shortcut now work everywhere.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, AppSidebar, ToastHost, UndoToast],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly auth = inject(AuthStore);
  private readonly fleet = inject(FleetStore);
  private readonly sidebar = inject(SidebarStore);
  private readonly router = inject(Router);

  protected readonly offline = computed(() => this.fleet.reachable() === false);

  constructor() {
    // Warms the Leaflet chunk on idle (docs/CYCLES-PLAN.md §9, CU-b item 2) — after render so it
    // never competes with first paint or the initial fleet fetch. `inject()` is called here, in
    // the constructor's own injection context, and the resolved instance captured in a const —
    // NOT inside the `afterNextRender` callback itself, which runs *after* render completes and is
    // therefore no longer an injection context (calling `inject()` there throws NG0203 at runtime).
    const leafletWarmup = inject(LeafletWarmup);
    afterNextRender(() => leafletWarmup.schedule());

    // Full-bleed auto-collapse (docs/NAV-IA-REDESIGN-PLAN.md §2.1 rule 5, F11). This class owns the
    // single router read and pushes it into `SidebarStore`, which layers it under any manual toggle
    // (see that store's own precedence doc) — the sidebar component itself stays router-agnostic.
    // Seeded eagerly as well as on every `NavigationEnd`, so a first load straight into `/fly` never
    // renders one frame of docked sidebar before the first navigation event arrives.
    this.sidebar.enterRoute(routeTreeHasFullBleed(this.router.routerState.snapshot.root));
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        map(() => routeTreeHasFullBleed(this.router.routerState.snapshot.root)),
        takeUntilDestroyed(),
      )
      .subscribe((fullBleed) => this.sidebar.enterRoute(fullBleed));

    // `[` toggles the sidebar (docs/NAV-IA-REDESIGN-PLAN.md §2.1 rule 4) — a global `document`
    // keydown listener, the same page-scoped-listener idiom `features/fly/fly.ts`/
    // `features/live/live.ts` already use for their own keyboard shortcuts, ignored while focus is
    // in a form field or a contenteditable region so typing a literal `[` never fights the shell.
    const onKeydown = (event: KeyboardEvent): void => this.handleKeydown(event);
    document.addEventListener('keydown', onKeydown);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('keydown', onKeydown));
  }

  private handleKeydown(event: KeyboardEvent): void {
    if (event.key !== '[' || event.metaKey || event.ctrlKey || event.altKey) {
      return;
    }
    // `event.target` is only ever a real `Element` (with `.closest`/`.tagName`) when the key was
    // pressed while focus sat inside the document's own content — a shortcut fired with nothing
    // focused (or focus on `document`/`window` itself) reports the `Document` as its target, which
    // has neither member; the `instanceof Element` guard covers both cases in one check rather than
    // this needing its own separate null/type check ahead of `isEditableRegion`.
    const target = event.target;
    if (target instanceof Element && (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) || isEditableRegion(target))) {
      return;
    }
    this.sidebar.toggle();
  }
}

/**
 * Whether `target` is inside a `contenteditable` region — checked via the `contenteditable`
 * *attribute* (`closest`), not the `isContentEditable` IDL property: real browsers keep both in
 * sync, but `isContentEditable` is unimplemented in this project's jsdom test environment (returns
 * `undefined` regardless of the element's actual editable state, confirmed directly against the
 * pinned `jsdom` version), so a spec exercising the `[` shortcut's ignore-list could never observe
 * it. `closest`, not a direct check on `target` alone, also correctly covers the common case of the
 * event's own target being a child node *inside* a larger editable region (matching what
 * `isContentEditable` computes via inheritance in a real browser).
 */
function isEditableRegion(target: Element): boolean {
  return target.closest('[contenteditable=""], [contenteditable="true"]') !== null;
}

/**
 * The minimal shape this needs from `ActivatedRouteSnapshot` — a `data` bag plus a `firstChild`
 * link, expressed structurally rather than importing the real Angular type, so a spec can build one
 * by hand (a plain object literal) without any router-testing machinery.
 */
export interface RouteDataNode {
  readonly data: Readonly<Record<string, unknown>>;
  readonly firstChild: RouteDataNode | null;
}

/**
 * Walks the activated-route tree from `root` looking for `data.fullBleed === true` on any segment —
 * see this file's class doc for why the whole chain, not just the root, needs checking. Pure and
 * exported so it's directly unit-testable (`app.spec.ts`) without standing up a real `Router`.
 */
export function routeTreeHasFullBleed(root: RouteDataNode | null): boolean {
  let node = root;
  while (node) {
    if (node.data['fullBleed'] === true) {
      return true;
    }
    node = node.firstChild;
  }
  return false;
}
