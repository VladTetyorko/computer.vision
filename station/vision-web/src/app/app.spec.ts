import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { App, type RouteDataNode, routeTreeHasFullBleed } from './app';
import { FleetStore } from './core/fleet/fleet-store';
import { LeafletWarmup } from './core/leaflet-warmup';
import { AuthFacade } from './core/auth/auth-facade';
import { hasCapability } from './core/auth/auth-logic';
import type { AuthCapability } from './core/api/models';
import { EventsStore } from './core/events/events-store';
import { LiveFacade } from './core/live/live-facade';
import { VisionApi } from './core/api/vision-api';
import { SidebarFacade } from './core/shell/sidebar-facade';
import { provideAppState } from './core/state/app-state';

/**
 * `App` pulls in `AppSidebar`, which in turn mounts `IdentityChip`/`NotificationBell`, each with
 * their own deep store graph (`AuthFacade`, `FleetStore`, `EventsStore`, `LiveFacade`, `VisionApi`) —
 * every one of those is overridden with a minimal, side-effect-free fake here (no HTTP, no polling,
 * no real `EventSource`) purely so the shell can mount at all; none of their own behavior is under
 * test in this file (see each store's own spec, and `shared/ui/app-sidebar/app-sidebar.spec.ts` for
 * the sidebar's own tiering/role-gate/collapse behavior). `ToastService`/`UndoToastService` are left
 * real — both are self-contained `signal()`-only state with no injected dependencies of their own.
 * `AppSidebar` also constructs a real `SystemStatusStore` for the shell health dot (§5.2),
 * which since docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md wave L5 reads `LiveFacade.systemStatus()`
 * — `fakeLiveFacade` below carries that member too, purely so `SystemStatusStore` can construct
 * without throwing; nothing in this file exercises its value.
 */
function fakeFleetStore(reachable: boolean | undefined = true) {
  return { streams: () => [] as unknown[], reachable: () => reachable };
}

function fakeEventsStore() {
  return { activate: () => {}, release: () => {}, events: () => [] as unknown[] };
}

function fakeLiveFacade(connectionState: 'connecting' | 'open' | 'closed' = 'open') {
  return {
    liveEvents: () => [] as unknown[],
    connectionState: () => connectionState,
    systemStatus: () => undefined,
  };
}

/** Mirrors the real policy table closely enough for a fixture (docs/plans/active/AUTH-ROLES-PLAN.md §3.2,
 *  wave W2) — `AppSidebar`'s `modes` computed now calls `AuthFacade.can(entry.requires)`, so this fake
 *  needs a `capabilities()`/`can()` pair even though nothing in this file exercises role-gating
 *  itself (`app-sidebar.spec.ts` owns that). This file's `topRole` type never includes `VIEWER`, so
 *  the table doesn't need that row either. */
const ROLE_CAPABILITIES: Record<'ADMIN' | 'MANAGER' | 'PILOT', readonly AuthCapability[]> = {
  PILOT: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT'],
  MANAGER: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'],
  ADMIN: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'],
};

/** `authEnabled` defaults to `false` — this app's own real default (`vision.auth.enabled=false`),
 *  so every existing call site here keeps exercising dev parity unless a test opts into a "real"
 *  secured session (docs/plans/done/OPS-UX-PLAN.md §2 A6's own dedicated tests below). */
function fakeAuthFacade(topRole?: 'ADMIN' | 'MANAGER' | 'PILOT', authEnabled = false) {
  const capabilities = topRole ? ROLE_CAPABILITIES[topRole] : [];
  return {
    user: () => (topRole ? { topRole, displayName: 'Test User', username: 'test' } : null),
    authEnabled: () => authEnabled,
    capabilities: () => capabilities,
    can: (capability: AuthCapability) => hasCapability(capabilities, capability),
    // `<vision-reauth-overlay>` (wave W1) is mounted unconditionally in `app.html`, alongside every
    // App render this file exercises — `false` here keeps it rendering nothing, exactly as every
    // test in this file already assumes with no session ever having 401'd.
    reauthRequired: () => false,
    loginBusy: () => false,
    loginError: () => null,
    login: () => Promise.resolve(false),
    // `<vision-force-password-change>` (wave W3) is likewise mounted unconditionally in `app.html`
    // — `false` here keeps it rendering nothing, same reasoning as `reauthRequired` above (neither
    // component has a dedicated spec, mirroring `reauth-overlay.ts`'s own untested-directly
    // precedent — see that file's doc comment).
    mustChangePassword: () => false,
    changePassword: () => Promise.resolve(null),
    logout: () => Promise.resolve(),
  };
}

@Component({ selector: 'vision-test-stub-page', template: '' })
class StubPage {}

function render(
  options: {
    topRole?: 'ADMIN' | 'MANAGER' | 'PILOT';
    reachable?: boolean;
    authEnabled?: boolean;
    connectionState?: 'connecting' | 'open' | 'closed';
  } = {},
) {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      provideRouter([
        { path: 'fly', component: StubPage, data: { fullBleed: true } },
        { path: 'assets', component: StubPage },
      ]),
      { provide: FleetStore, useValue: fakeFleetStore(options.reachable) },
      { provide: LeafletWarmup, useValue: { schedule: () => {} } },
      { provide: AuthFacade, useValue: fakeAuthFacade(options.topRole, options.authEnabled) },
      { provide: EventsStore, useValue: fakeEventsStore() },
      { provide: LiveFacade, useValue: fakeLiveFacade(options.connectionState) },
      { provide: VisionApi, useValue: {} },
    ],
  });
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  return fixture;
}

/**
 * Finds one banner in the shell stack by its own text. Banners are told apart by what they say
 * rather than by a per-banner class because they deliberately share `.shell-banner--warn`
 * (OPS-UX-PLAN §2 A6 rev.2: severity is the only distinction the stack makes), so a class-based
 * query cannot tell "offline" from "live degraded" — and asserting on the first match would pass
 * for the wrong banner.
 */
function bannerWith(fixture: { nativeElement: unknown }, text: string): Element | null {
  const banners = (fixture.nativeElement as HTMLElement).querySelectorAll('.shell-banner');
  return Array.from(banners).find((banner) => banner.textContent?.includes(text)) ?? null;
}

describe('routeTreeHasFullBleed (pure)', () => {
  function node(data: Record<string, unknown>, firstChild: RouteDataNode | null = null): RouteDataNode {
    return { data, firstChild };
  }

  it('is false for a root with no data anywhere', () => {
    expect(routeTreeHasFullBleed(node({}))).toBe(false);
  });

  it('is false for null (no route resolved yet)', () => {
    expect(routeTreeHasFullBleed(null)).toBe(false);
  });

  it('is true when the root itself carries fullBleed', () => {
    expect(routeTreeHasFullBleed(node({ fullBleed: true }))).toBe(true);
  });

  it('is true when a nested child carries it — the authGuard wrapper route sits above every real page', () => {
    const tree = node({}, node({}, node({ fullBleed: true })));
    expect(routeTreeHasFullBleed(tree)).toBe(true);
  });

  it('is false when fullBleed is present but not exactly true (e.g. omitted/falsy)', () => {
    expect(routeTreeHasFullBleed(node({ fullBleed: false }))).toBe(false);
    expect(routeTreeHasFullBleed(node({ preload: false }))).toBe(false);
  });
});

describe('App shell', () => {
  it('hides the sidebar entirely while unauthenticated (no session yet / login)', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('vision-app-sidebar')).toBeNull();
  });

  it('shows the sidebar once a session resolves', () => {
    const fixture = render({ topRole: 'PILOT' });
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('vision-app-sidebar')).not.toBeNull();
  });

  it('dev parity: the dev ADMIN principal (authEnabled=false) also shows the sidebar', () => {
    const fixture = render({ topRole: 'ADMIN' });
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('vision-app-sidebar')).not.toBeNull();
  });

  it('shows the offline banner only when the fleet is unreachable', () => {
    const online = render({ topRole: 'PILOT', reachable: true });
    expect((online.nativeElement as HTMLElement).querySelector('.shell-banner--warn')).toBeNull();

    // A fresh testing module — TestBed refuses `configureTestingModule` again once a previous call's
    // component has already been instantiated (mirrors `features/hubs/hub-pages.spec.ts`'s own
    // `renderManageHub` precedent for the identical situation).
    TestBed.resetTestingModule();
    const offline = render({ topRole: 'PILOT', reachable: false });
    expect((offline.nativeElement as HTMLElement).querySelector('.shell-banner--warn')).not.toBeNull();
  });

  /**
   * docs/plans/done/OPS-UX-PLAN.md §2 A6, docs/conclusions/OPS-UX-REVIEW.md §O3 — the persistent,
   * non-dismissable "this station has no login" strip.
   */
  describe('the unsecured-station banner', () => {
    it('shows once a session resolves with authEnabled=false (dev parity, this app\'s own real default)', () => {
      const fixture = render({ topRole: 'ADMIN', authEnabled: false });
      const root = fixture.nativeElement as HTMLElement;
      const banner = root.querySelector('.shell-banner--danger');
      expect(banner).not.toBeNull();
      expect(banner?.textContent).toContain('This station is unsecured');
    });

    it('is absent once auth is actually enabled — a real session needs no such warning', () => {
      const fixture = render({ topRole: 'ADMIN', authEnabled: true });
      const root = fixture.nativeElement as HTMLElement;
      expect(root.querySelector('.shell-banner--danger')).toBeNull();
    });

    it('never shows before a session has resolved, even though authEnabled defaults to false pre-boot (no flash of a claim about a session that hasn\'t loaded yet)', () => {
      const fixture = render(); // no topRole → user() is null, exactly the pre-boot/unauthenticated shape
      const root = fixture.nativeElement as HTMLElement;
      expect(root.querySelector('.shell-banner--danger')).toBeNull();
    });

    it('shows for every role, not just ADMIN — the fact is about the station, not the viewer', () => {
      for (const topRole of ['PILOT', 'MANAGER', 'ADMIN'] as const) {
        TestBed.resetTestingModule();
        const fixture = render({ topRole, authEnabled: false });
        expect((fixture.nativeElement as HTMLElement).querySelector('.shell-banner--danger'), topRole).not.toBeNull();
      }
    });

    it('renders on a full-bleed route without shifting the cockpit stub out of its own layout (position: fixed, never in-flow above <router-outlet>)', async () => {
      const fixture = render({ topRole: 'PILOT', authEnabled: false });
      const router = TestBed.inject(Router);
      await router.navigateByUrl('/fly');
      fixture.detectChanges();

      const root = fixture.nativeElement as HTMLElement;
      expect(root.querySelector('.shell-banner--danger')).not.toBeNull();
      // The *stack* is what's `position: fixed` — out of normal flow, so its presence contributes no
      // height to `main`'s box and the routed stub page still mounts inside `main` unaffected.
      const main = root.querySelector('main') as HTMLElement;
      expect(main.querySelector('vision-test-stub-page')).not.toBeNull();
      expect(getComputedStyle(root.querySelector('.shell-banners') as HTMLElement).position).toBe('fixed');
    });
  });

  /**
   * The regression this stack exists for (docs/plans/done/OPS-UX-PLAN.md §2 A6 rev.2): the first
   * revision made the unsecured banner its own `position: fixed` strip at the viewport top, which
   * painted it over the sidebar's brand row, over an open drawer's title, and over the offline
   * banner — two independent fixed strips at `inset: 0 0 auto 0` occupy the *same* pixels. Both
   * banners sharing one flow-laid-out parent is what makes stacking, rather than overlapping, the
   * only thing they can do.
   */
  describe('banner stacking', () => {
    it('puts every showing banner in the one stack, in severity order, never on top of each other', () => {
      const fixture = render({ topRole: 'PILOT', authEnabled: false, reachable: false });
      const root = fixture.nativeElement as HTMLElement;
      const stack = root.querySelector('.shell-banners') as HTMLElement;

      expect(stack.querySelectorAll('.shell-banner')).toHaveLength(2);
      expect([...stack.children].map((child) => child.className.split(' ')[1])).toEqual([
        'shell-banner--danger',
        'shell-banner--warn',
      ]);
      // A flex column: the second banner is pushed *below* the first rather than layered over it.
      expect(getComputedStyle(stack).flexDirection).toBe('column');
    });

    it('leaves no banner outside the stack — nothing renders a strip of its own inside <main>', () => {
      const fixture = render({ topRole: 'PILOT', authEnabled: false, reachable: false });
      const root = fixture.nativeElement as HTMLElement;
      expect((root.querySelector('main') as HTMLElement).querySelector('.shell-banner')).toBeNull();
    });

    it('publishes the stack height as --shell-banner-h so the rest of the shell can reserve it', () => {
      render({ topRole: 'PILOT', authEnabled: false });
      // jsdom reports 0 for every measured box, so the assertion is that the token is *written* at
      // all (an unwritten token leaves `--shell-h` referencing an undefined value, which makes every
      // `height: var(--shell-h)` in the app invalid-at-computed-value-time rather than merely wrong).
      expect(document.documentElement.style.getPropertyValue('--shell-banner-h')).toMatch(/^\d+px$/);
    });
  });

  it('shows the live-degraded banner only for the specific silent-degradation case: SSE closed + backend reachable (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.1)', () => {
    const degraded = bannerWith(render({ topRole: 'PILOT', reachable: true, connectionState: 'closed' }),
      'Live updates disconnected');
    expect(degraded).not.toBeNull();
    expect(degraded?.textContent).toContain('5-second refresh');
    expect(degraded?.textContent).toContain('Retrying every 60 s');

    TestBed.resetTestingModule();
    // Reachable but still connecting (not yet closed) — not the degraded case.
    const connecting = render({ topRole: 'PILOT', reachable: true, connectionState: 'connecting' });
    expect(bannerWith(connecting, 'Live updates disconnected')).toBeNull();

    TestBed.resetTestingModule();
    // Backend unreachable — the louder offline banner covers this, not the live-degraded one
    // (they are mutually exclusive; a closed SSE connection is an expected consequence of the
    // backend being down at all, not a second, separate problem).
    const offline = render({ topRole: 'PILOT', reachable: false, connectionState: 'closed' });
    expect(bannerWith(offline, 'Cannot reach the Vision backend')).not.toBeNull();
    expect(bannerWith(offline, 'Live updates disconnected')).toBeNull();
  });

  it('preserves the toast host and undo toast', () => {
    const fixture = render({ topRole: 'PILOT' });
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('vision-toast-host')).not.toBeNull();
    expect(root.querySelector('vision-undo-toast')).not.toBeNull();
  });

  it('feeds the resolved route\'s fullBleed flag into the sidebar, which then auto-collapses (checked through the DOM, not a protected field — `fullBleed` is `App`\'s own internal state)', async () => {
    localStorage.clear();
    const fixture = render({ topRole: 'PILOT' });
    const router = TestBed.inject(Router);
    const sidebarEl = () => (fixture.nativeElement as HTMLElement).querySelector('.sidebar')!;

    await router.navigateByUrl('/assets');
    fixture.detectChanges();
    expect(sidebarEl().classList.contains('collapsed')).toBe(false);

    await router.navigateByUrl('/fly');
    fixture.detectChanges();
    expect(sidebarEl().classList.contains('collapsed')).toBe(true);
  });

  describe('the "[" shortcut', () => {
    it('toggles SidebarFacade.collapsed', () => {
      localStorage.clear();
      render({ topRole: 'PILOT' });
      const sidebar = TestBed.inject(SidebarFacade);
      expect(sidebar.collapsed()).toBe(false);

      document.dispatchEvent(new KeyboardEvent('keydown', { key: '[' }));
      expect(sidebar.collapsed()).toBe(true);

      document.dispatchEvent(new KeyboardEvent('keydown', { key: '[' }));
      expect(sidebar.collapsed()).toBe(false);
    });

    it('is ignored while focus is inside a text input', () => {
      localStorage.clear();
      render({ topRole: 'PILOT' });
      const sidebar = TestBed.inject(SidebarFacade);

      const input = document.createElement('input');
      document.body.appendChild(input);
      input.focus();
      input.dispatchEvent(new KeyboardEvent('keydown', { key: '[', bubbles: true }));
      expect(sidebar.collapsed()).toBe(false);
      input.remove();
    });

    it('is ignored while focus is inside a contenteditable region', () => {
      localStorage.clear();
      render({ topRole: 'PILOT' });
      const sidebar = TestBed.inject(SidebarFacade);

      const div = document.createElement('div');
      // `setAttribute`, not the `.contentEditable` IDL property — see `app.ts#isEditableRegion`'s
      // own doc comment for why (jsdom doesn't reliably reflect the property to the attribute).
      div.setAttribute('contenteditable', 'true');
      document.body.appendChild(div);
      div.focus();
      div.dispatchEvent(new KeyboardEvent('keydown', { key: '[', bubbles: true }));
      expect(sidebar.collapsed()).toBe(false);
      div.remove();
    });

    it('is ignored when a modifier key is held', () => {
      localStorage.clear();
      render({ topRole: 'PILOT' });
      const sidebar = TestBed.inject(SidebarFacade);

      document.dispatchEvent(new KeyboardEvent('keydown', { key: '[', metaKey: true }));
      expect(sidebar.collapsed()).toBe(false);
    });

    it('is ignored while on a full-bleed route — mirrors AppSidebar#onToggleClick\'s own guard, closing the same "silently flips a preference with no visible effect here" bug for the keyboard path', async () => {
      localStorage.clear();
      const fixture = render({ topRole: 'PILOT' });
      const router = TestBed.inject(Router);
      const sidebar = TestBed.inject(SidebarFacade);

      await router.navigateByUrl('/fly');
      fixture.detectChanges();

      document.dispatchEvent(new KeyboardEvent('keydown', { key: '[' }));
      expect(sidebar.collapsed()).toBe(false);

      // Off the full-bleed route, the exact same shortcut works normally again.
      await router.navigateByUrl('/assets');
      fixture.detectChanges();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: '[' }));
      expect(sidebar.collapsed()).toBe(true);
    });
  });
});
