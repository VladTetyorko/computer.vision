import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { App, type RouteDataNode, routeTreeHasFullBleed } from './app';
import { FleetStore } from './core/fleet/fleet-store';
import { LeafletWarmup } from './core/leaflet-warmup';
import { AuthStore } from './core/auth/auth-store';
import { EventsStore } from './core/events/events-store';
import { LiveStore } from './core/live/live-store';
import { VisionApi } from './core/api/vision-api';
import { SidebarStore } from './core/shell/sidebar-store';

/**
 * `App` pulls in `AppSidebar`, which in turn mounts `IdentityChip`/`NotificationBell`, each with
 * their own deep store graph (`AuthStore`, `FleetStore`, `EventsStore`, `LiveStore`, `VisionApi`) —
 * every one of those is overridden with a minimal, side-effect-free fake here (no HTTP, no polling,
 * no real `EventSource`) purely so the shell can mount at all; none of their own behavior is under
 * test in this file (see each store's own spec, and `shared/ui/app-sidebar/app-sidebar.spec.ts` for
 * the sidebar's own tiering/role-gate/collapse behavior). `ToastService`/`UndoToastService` are left
 * real — both are self-contained `signal()`-only state with no injected dependencies of their own.
 */
function fakeFleetStore(reachable: boolean | undefined = true) {
  return { streams: () => [] as unknown[], reachable: () => reachable };
}

function fakeEventsStore() {
  return { activate: () => {}, release: () => {}, events: () => [] as unknown[] };
}

function fakeLiveStore(connectionState: 'connecting' | 'open' | 'closed' = 'open') {
  return { liveEvents: () => [] as unknown[], connectionState: () => connectionState };
}

function fakeAuthStore(topRole?: 'ADMIN' | 'MANAGER' | 'PILOT') {
  return {
    user: () => (topRole ? { topRole, displayName: 'Test User', username: 'test' } : null),
    authEnabled: () => false,
  };
}

@Component({ selector: 'vision-test-stub-page', template: '' })
class StubPage {}

function render(
  options: {
    topRole?: 'ADMIN' | 'MANAGER' | 'PILOT';
    reachable?: boolean;
    connectionState?: 'connecting' | 'open' | 'closed';
  } = {},
) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'fly', component: StubPage, data: { fullBleed: true } },
        { path: 'assets', component: StubPage },
      ]),
      { provide: FleetStore, useValue: fakeFleetStore(options.reachable) },
      { provide: LeafletWarmup, useValue: { schedule: () => {} } },
      { provide: AuthStore, useValue: fakeAuthStore(options.topRole) },
      { provide: EventsStore, useValue: fakeEventsStore() },
      { provide: LiveStore, useValue: fakeLiveStore(options.connectionState) },
      { provide: VisionApi, useValue: {} },
    ],
  });
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  return fixture;
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
    expect((online.nativeElement as HTMLElement).querySelector('.offline-banner')).toBeNull();

    // A fresh testing module — TestBed refuses `configureTestingModule` again once a previous call's
    // component has already been instantiated (mirrors `features/hubs/hub-pages.spec.ts`'s own
    // `renderManageHub` precedent for the identical situation).
    TestBed.resetTestingModule();
    const offline = render({ topRole: 'PILOT', reachable: false });
    expect((offline.nativeElement as HTMLElement).querySelector('.offline-banner')).not.toBeNull();
  });

  it('shows the live-degraded notice only for the specific silent-degradation case: SSE closed + backend reachable (docs/plans/active/SYSTEM-STATUS-PLAN.md §3.1)', () => {
    const degraded = render({ topRole: 'PILOT', reachable: true, connectionState: 'closed' });
    const notice = (degraded.nativeElement as HTMLElement).querySelector('vision-notice');
    expect(notice).not.toBeNull();
    expect(notice?.textContent).toContain('Live updates disconnected');
    expect(notice?.textContent).toContain('5-second refresh');
    expect(notice?.textContent).toContain('Retrying every 60 s');

    TestBed.resetTestingModule();
    // Reachable but still connecting (not yet closed) — not the degraded case.
    const connecting = render({ topRole: 'PILOT', reachable: true, connectionState: 'connecting' });
    expect((connecting.nativeElement as HTMLElement).querySelector('vision-notice')).toBeNull();

    TestBed.resetTestingModule();
    // Backend unreachable — the louder offline banner covers this, not the live-degraded notice
    // (they are mutually exclusive; a closed SSE connection is an expected consequence of the
    // backend being down at all, not a second, separate problem).
    const offline = render({ topRole: 'PILOT', reachable: false, connectionState: 'closed' });
    const offlineRoot = offline.nativeElement as HTMLElement;
    expect(offlineRoot.querySelector('.offline-banner')).not.toBeNull();
    expect(offlineRoot.querySelector('vision-notice')).toBeNull();
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
    it('toggles SidebarStore.collapsed', () => {
      localStorage.clear();
      render({ topRole: 'PILOT' });
      const sidebar = TestBed.inject(SidebarStore);
      expect(sidebar.collapsed()).toBe(false);

      document.dispatchEvent(new KeyboardEvent('keydown', { key: '[' }));
      expect(sidebar.collapsed()).toBe(true);

      document.dispatchEvent(new KeyboardEvent('keydown', { key: '[' }));
      expect(sidebar.collapsed()).toBe(false);
    });

    it('is ignored while focus is inside a text input', () => {
      localStorage.clear();
      render({ topRole: 'PILOT' });
      const sidebar = TestBed.inject(SidebarStore);

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
      const sidebar = TestBed.inject(SidebarStore);

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
      const sidebar = TestBed.inject(SidebarStore);

      document.dispatchEvent(new KeyboardEvent('keydown', { key: '[', metaKey: true }));
      expect(sidebar.collapsed()).toBe(false);
    });

    it('is ignored while on a full-bleed route — mirrors AppSidebar#onToggleClick\'s own guard, closing the same "silently flips a preference with no visible effect here" bug for the keyboard path', async () => {
      localStorage.clear();
      const fixture = render({ topRole: 'PILOT' });
      const router = TestBed.inject(Router);
      const sidebar = TestBed.inject(SidebarStore);

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
