import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { AppSidebar } from './app-sidebar';
import { AuthFacade } from '../../../core/auth/auth-facade';
import { FleetStore } from '../../../core/fleet/fleet-store';
import { EventsFacade } from '../../../core/events/events-facade';
import { LiveFacade } from '../../../core/live/live-facade';
import { VisionApi } from '../../../core/api/vision-api';
import { SidebarFacade } from '../../../core/shell/sidebar-facade';
import { provideAppState } from '../../../core/state/app-state';
import { ThemeFacade } from '../../../core/shell/theme-facade';
import { SystemStatusStore } from '../../../core/system-status/system-status-store';
import { NAV_MODES } from '../../../features/hubs/nav-entries';
import { hasCapability } from '../../../core/auth/auth-logic';
import type { AuthCapability, OverallHealth, Role } from '../../../core/api/models';

/** Mirrors the real `RoleAuthority`/`DefaultScopeResolver` policy table closely enough for a
 *  fixture — see `core/auth/auth-logic.spec.ts`'s identical helper for the full reasoning. */
const ROLE_CAPABILITIES: Record<Role, readonly AuthCapability[]> = {
  VIEWER: [],
  PILOT: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT'],
  MANAGER: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'],
  ADMIN: ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'],
};

/**
 * `AppSidebar` mounts `<vision-identity-chip>`/`<vision-notification-bell>` in its foot, each with
 * their own deep store graph — every one of those is faked here (no HTTP, no polling, no real
 * `EventSource`), mirroring the pre-existing shell spec's own "fake every transitive dependency
 * purely so the tree can mount" approach (see `app.spec.ts`).
 */
function fakeFleetStore(overrides: { streams?: unknown[]; reachable?: boolean | undefined } = {}) {
  return {
    streams: () => overrides.streams ?? [],
    reachable: () => (overrides.reachable === undefined ? true : overrides.reachable),
  };
}

function fakeEventsStore() {
  return { activate: () => {}, release: () => {}, events: () => [] as unknown[] };
}

function fakeLiveFacade(connectionState: 'connecting' | 'open' | 'closed' = 'open') {
  return { liveEvents: () => [] as unknown[], connectionState: () => connectionState };
}

/** Only `overall` is read by `AppSidebar` (`system-status.overall()`). */
function fakeSystemStatusStore(overall: OverallHealth | undefined) {
  return { overall: () => overall };
}

function fakeAuthFacade(topRole?: 'ADMIN' | 'MANAGER' | 'PILOT') {
  const capabilities = topRole ? ROLE_CAPABILITIES[topRole] : [];
  return {
    user: () => (topRole ? { topRole, displayName: 'Test User', username: 'test' } : null),
    authEnabled: () => false,
    capabilities: () => capabilities,
    can: (capability: AuthCapability) => hasCapability(capabilities, capability),
  };
}

@Component({ selector: 'vision-test-stub-page', template: '' })
class StubPage {}

function render(options: {
  topRole?: 'ADMIN' | 'MANAGER' | 'PILOT';
  streams?: unknown[];
  reachable?: boolean;
  fullBleed?: boolean;
  connectionState?: 'connecting' | 'open' | 'closed';
  overall?: OverallHealth | undefined;
} = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideAppState(),
      provideRouter([
        { path: 'fly', component: StubPage },
        { path: 'command', component: StubPage },
        { path: 'assets', component: StubPage },
        { path: 'manage/system', component: StubPage },
      ]),
      { provide: FleetStore, useValue: fakeFleetStore(options) },
      { provide: AuthFacade, useValue: fakeAuthFacade(options.topRole) },
      { provide: EventsFacade, useValue: fakeEventsStore() },
      { provide: LiveFacade, useValue: fakeLiveFacade(options.connectionState) },
      // `overall` defaults to `'OK'` — not `undefined` — so every pre-existing test in this file
      // (written before the shell rollup dot read this third axis, docs/plans/done/SYSTEM-STATUS-PLAN.md
      // §5.2) keeps its original "everything is fine" baseline unless a test explicitly opts into
      // `overall: undefined` (the pre-first-fetch state) or a degraded/down value.
      { provide: SystemStatusStore, useValue: fakeSystemStatusStore('overall' in options ? options.overall : 'OK') },
      { provide: VisionApi, useValue: {} },
    ],
  });
  // `fullBleed` is no longer an input — the route flag reaches the sidebar through `SidebarFacade`
  // (`app.ts` calls `enterRoute` on every NavigationEnd), which layers it under any manual toggle.
  if (options.fullBleed !== undefined) {
    TestBed.inject(SidebarFacade).enterRoute(options.fullBleed);
  }
  const fixture = TestBed.createComponent(AppSidebar);
  fixture.detectChanges();
  return fixture;
}

function navRowHrefs(root: HTMLElement): (string | null)[] {
  return Array.from(root.querySelectorAll('a.nav-row')).map((a) => a.getAttribute('href'));
}

describe('AppSidebar — nav groups + role gate (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1, wave W1)', () => {
  it('renders every group as a non-link label (docs/plans/done/NAV-IA-REDESIGN-PLAN.md F1 — no /operate, /monitor, /manage destination anywhere)', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;

    const labels = Array.from(root.querySelectorAll('.group-label'));
    expect(labels.length).toBe(NAV_MODES.length);
    for (const label of labels) {
      expect(label.tagName).toBe('DIV');
      expect(label.querySelector('a')).toBeNull();
    }
    expect(navRowHrefs(root)).not.toContain('/operate');
    expect(navRowHrefs(root)).not.toContain('/monitor');
    expect(navRowHrefs(root)).not.toContain('/manage');
  });

  /**
   * The one WAREHOUSE-UX-PLAN.md §3.1 structural claim this suite can check without duplicating
   * `nav-entries.spec.ts`'s own data-level assertions: `system` (and only `system`) renders in
   * `.sidebar-foot`, the other four groups render in `.sidebar-body`, and both containers preserve
   * `NAV_MODES`'s own order.
   */
  it('renders SYSTEM in the sidebar foot, next to the identity chip — every other group in the body', () => {
    const fixture = render({ topRole: 'ADMIN' });
    const root = fixture.nativeElement as HTMLElement;

    const bodyGroups = root.querySelectorAll('.sidebar-body .nav-group');
    const footGroups = root.querySelectorAll('.sidebar-foot .nav-group');
    const nonFooterModes = NAV_MODES.filter((mode) => !mode.footer);
    const footerModes = NAV_MODES.filter((mode) => mode.footer);

    expect(footerModes.map((mode) => mode.id)).toEqual(['system']);
    expect(bodyGroups.length).toBe(nonFooterModes.length);
    expect(footGroups.length).toBe(1);
    expect(Array.from(bodyGroups).map((g) => g.getAttribute('aria-label'))).toEqual(nonFooterModes.map((m) => m.label));
    expect(footGroups[0].getAttribute('aria-label')).toBe('System');

    // The foot group sits before the identity chip in DOM order (WAREHOUSE-UX-PLAN.md §3.1: "footer,
    // with the identity chip") — never after the status row at the very bottom.
    const foot = root.querySelector('.sidebar-foot')!;
    const children = Array.from(foot.children);
    const navGroupIdx = children.findIndex((el) => el.classList.contains('nav-group'));
    const identityIdx = children.findIndex((el) => el.tagName.toLowerCase() === 'vision-identity-chip');
    expect(navGroupIdx).toBeGreaterThanOrEqual(0);
    expect(identityIdx).toBeGreaterThan(navGroupIdx);
  });

  it("renders each group's entries as routerLink rows, in order, for a non-manager (no more primary/advanced/upcoming tiers — docs/plans/active/WAREHOUSE-UX-PLAN.md wave W1)", () => {
    const fixture = render({ topRole: 'PILOT' });
    const root = fixture.nativeElement as HTMLElement;
    const groups = root.querySelectorAll('.nav-group');

    NAV_MODES.forEach((mode, i) => {
      const visible = mode.entries.filter((entry) => !entry.requires);
      const hrefs = Array.from(groups[i].querySelectorAll(':scope > a.nav-row')).map((a) => a.getAttribute('href'));
      expect(hrefs, mode.id).toEqual(visible.map((entry) => entry.to));
    });
  });

  it('renders no disclosure and no dimmed row anywhere — the Advanced/Upcoming disclosures and the badge:"soon" tier are retired (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 1)', () => {
    const fixture = render({ topRole: 'ADMIN' });
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('details.disclosure').length).toBe(0);
    expect(root.querySelectorAll('a.nav-row.dimmed').length).toBe(0);
  });

  it('renders every group label without an icon (docs/plans/done/VISUAL-REFRESH-PLAN.md Wave 1 — icons compete with each row\'s own icon two rows down)', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    for (const label of Array.from(root.querySelectorAll('.group-label'))) {
      expect(label.querySelector('vision-icon')).toBeNull();
      expect(label.querySelector('.group-label-text')?.textContent?.trim().length).toBeGreaterThan(0);
    }
  });

  it('a PILOT sees no requires-gated entry anywhere, an ADMIN sees the full set (F10 — filtered exactly once, here)', () => {
    const pilot = render({ topRole: 'PILOT' });
    const pilotHrefs = navRowHrefs(pilot.nativeElement as HTMLElement);
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        if (entry.requires) {
          expect(pilotHrefs, `PILOT should not see ${entry.name}`).not.toContain(entry.to);
        }
      }
    }

    // A fresh testing module — TestBed refuses `configureTestingModule` again once a previous call's
    // component has already been instantiated (mirrors `features/hubs/hub-pages.spec.ts`'s own
    // `renderManageHub` precedent for the identical situation).
    TestBed.resetTestingModule();
    const admin = render({ topRole: 'ADMIN' });
    const adminHrefs = navRowHrefs(admin.nativeElement as HTMLElement);
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        expect(adminHrefs, `ADMIN should see ${entry.name}`).toContain(entry.to);
      }
    }
  });

  it('dev-parity: an ADMIN dev principal (authEnabled=false) sees the same full set as a real ADMIN session', () => {
    const fixture = render({ topRole: 'ADMIN' });
    const root = fixture.nativeElement as HTMLElement;
    const everyEntry = NAV_MODES.flatMap((mode) => mode.entries);
    expect(navRowHrefs(root)).toEqual(expect.arrayContaining(everyEntry.map((entry) => entry.to)));
  });
});

describe('AppSidebar — active row', () => {
  it('marks the current route active with the routerLinkActive class and aria-current="page"', async () => {
    const fixture = render();
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/fly');
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const cockpitRow = Array.from(root.querySelectorAll('a.nav-row')).find((a) => a.getAttribute('href') === '/fly')!;
    expect(cockpitRow.classList.contains('active')).toBe(true);
    expect(cockpitRow.getAttribute('aria-current')).toBe('page');

    const assetsRow = Array.from(root.querySelectorAll('a.nav-row')).find((a) => a.getAttribute('href') === '/assets')!;
    expect(assetsRow.classList.contains('active')).toBe(false);
    expect(assetsRow.getAttribute('aria-current')).toBeNull();
  });
});

describe('AppSidebar — collapse', () => {
  beforeEach(() => localStorage.clear());

  it('is expanded by default', () => {
    const fixture = render();
    const aside = (fixture.nativeElement as HTMLElement).querySelector('.sidebar')!;
    expect(aside.classList.contains('collapsed')).toBe(false);
  });

  it('collapses when SidebarFacade.collapsed is toggled', () => {
    const fixture = render();
    TestBed.inject(SidebarFacade).toggle();
    fixture.detectChanges();

    const aside = (fixture.nativeElement as HTMLElement).querySelector('.sidebar')!;
    expect(aside.classList.contains('collapsed')).toBe(true);
  });

  it('collapses on a full-bleed route even with no persisted user preference', () => {
    const fixture = render({ fullBleed: true });
    const aside = (fixture.nativeElement as HTMLElement).querySelector('.sidebar')!;
    expect(aside.classList.contains('collapsed')).toBe(true);
  });

  // Regression guard for the shipped bug: `collapsed() || fullBleed()` plus `[disabled]="fullBleed()"`
  // made the sidebar impossible to expand on /fly, /wall and /command — the three screens an operator
  // spends most of their time on. Auto-collapse is a default, not a lock.
  it('CAN be expanded on a full-bleed route — the toggle is never disabled', () => {
    const fixture = render({ fullBleed: true });
    const root = fixture.nativeElement as HTMLElement;
    const toggle = root.querySelector('.collapse-toggle') as HTMLButtonElement;
    expect(toggle.disabled).toBe(false);

    toggle.click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('collapsed')).toBe(false);
  });

  it('toggling on an ordinary route collapses it', () => {
    const fixture = render({ fullBleed: false });
    const root = fixture.nativeElement as HTMLElement;
    const toggle = root.querySelector('.collapse-toggle') as HTMLButtonElement;
    expect(toggle.disabled).toBe(false);

    toggle.click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('collapsed')).toBe(true);
  });
});

describe('AppSidebar — mobile off-canvas sheet + foot', () => {
  it('starts closed; the hamburger opens it, the scrim closes it', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.sidebar')!.classList.contains('mobile-open')).toBe(false);
    expect(root.querySelector('.sidebar-scrim')).toBeNull();

    (root.querySelector('.sidebar-hamburger') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('mobile-open')).toBe(true);
    const scrim = root.querySelector('.sidebar-scrim') as HTMLElement;
    expect(scrim).not.toBeNull();

    scrim.click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('mobile-open')).toBe(false);
  });

  it('clicking a nav row closes the mobile sheet', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    (root.querySelector('.sidebar-hamburger') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('mobile-open')).toBe(true);

    (root.querySelector('a.nav-row') as HTMLAnchorElement).click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('mobile-open')).toBe(false);
  });

  it('preserves the identity chip and notification bell, and consolidates live count + online status into one quiet line (docs/plans/done/VISUAL-REFRESH-PLAN.md Wave 1)', () => {
    const fixture = render({ streams: [{}, {}] });
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('.sidebar-foot vision-identity-chip')).not.toBeNull();
    expect(root.querySelector('.sidebar-foot vision-notification-bell')).not.toBeNull();
    expect(root.querySelector('.status-row .chip')?.textContent).toContain('2 live');
    const onlineDot = root.querySelector('.status-row .dot.status-dot');
    expect(onlineDot).not.toBeNull();
    expect(onlineDot!.classList.contains('ok')).toBe(true);
    expect(onlineDot!.getAttribute('aria-label')).toBe('System status: all clear');
  });

  it('shows the offline dot + label when the fleet is unreachable, with no live-count chip', () => {
    const fixture = render({ reachable: false, streams: [] });
    const root = fixture.nativeElement as HTMLElement;
    const onlineDot = root.querySelector('.status-row .dot.status-dot');
    expect(onlineDot!.classList.contains('danger')).toBe(true);
    expect(onlineDot!.getAttribute('aria-label')).toBe('System status: down');
    expect(root.querySelector('.status-row .chip')).toBeNull();
  });

  /**
   * docs/plans/done/UI-STATE-PLAN.md §1 D1/D3, §2.2: the mobile sheet now shares `OverlayFacade` (an
   * NgRx slice, docs/plans/active/NGRX-MIGRATION-PLAN.md §8) with the identity menu/notification bell
   * it's mounted alongside, so opening one closes the other — this is the same exclusivity
   * `core/ui/overlay-facade.spec.ts` proves at the facade level, checked here through the real
   * rendered shell (the actual scenario the sheet and the chip share one DOM tree).
   */
  it('opening the identity menu (a sibling shell overlay) closes an open mobile sheet, and vice versa', () => {
    const fixture = render({ topRole: 'PILOT' });
    const root = fixture.nativeElement as HTMLElement;

    (root.querySelector('.sidebar-hamburger') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('mobile-open')).toBe(true);

    (root.querySelector('.identity-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('mobile-open')).toBe(false);
    expect(root.querySelector('.identity-trigger')!.getAttribute('aria-expanded')).toBe('true');

    // And the reverse: reopening the sheet closes the menu it just displaced. The hamburger is back
    // in the DOM here (not the scrim) — the sheet closed the moment the identity menu opened, above.
    (root.querySelector('.sidebar-hamburger') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelector('.sidebar')!.classList.contains('mobile-open')).toBe(true);
    expect(root.querySelector('.identity-trigger')!.getAttribute('aria-expanded')).toBe('false');
  });
});

describe('AppSidebar — live-transport indicator (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.1)', () => {
  it('reads "Live" with an ok dot while the SSE connection is open', () => {
    const fixture = render({ connectionState: 'open' });
    const root = fixture.nativeElement as HTMLElement;
    const indicator = root.querySelector('.status-row .live-transport')!;
    expect(indicator.querySelector('.live-transport-text')?.textContent?.trim()).toBe('Live');
    expect(indicator.querySelector('.dot')!.classList.contains('ok')).toBe(true);
    expect(indicator.querySelector('.dot')!.classList.contains('warn')).toBe(false);
  });

  it('reads "Connecting" with a bare (neutral) dot mid-handshake', () => {
    const fixture = render({ connectionState: 'connecting' });
    const root = fixture.nativeElement as HTMLElement;
    const indicator = root.querySelector('.status-row .live-transport')!;
    expect(indicator.querySelector('.live-transport-text')?.textContent?.trim()).toBe('Connecting');
    expect(indicator.querySelector('.dot')!.classList.contains('ok')).toBe(false);
    expect(indicator.querySelector('.dot')!.classList.contains('warn')).toBe(false);
  });

  it('reads "Polling" with a warn dot once the SSE connection has closed', () => {
    const fixture = render({ connectionState: 'closed' });
    const root = fixture.nativeElement as HTMLElement;
    const indicator = root.querySelector('.status-row .live-transport')!;
    expect(indicator.querySelector('.live-transport-text')?.textContent?.trim()).toBe('Polling');
    expect(indicator.querySelector('.dot')!.classList.contains('warn')).toBe(true);
  });

  it('is a quiet dot + plain text, never a second .chip (frontend-style §5 — one chip per row max)', () => {
    const fixture = render({ connectionState: 'closed', streams: [{}] });
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('.status-row .chip')).toHaveLength(1); // only the pre-existing "N live" chip
    expect(root.querySelector('.status-row .live-transport')?.classList.contains('chip')).toBe(false);
  });
});

/**
 * The shell rollup dot (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.2) — the direct fix for §1.2's finding
 * that this dot answered only "did the last device poll succeed" and stayed green through a closed
 * live-transport connection or a degraded platform subsystem. These specs exercise the worst-of-three
 * combination through the real rendered shell, complementing `system-status-logic.spec.ts`'s own
 * unit coverage of `shellStatusSeverity` in isolation.
 */
describe('AppSidebar — shell rollup dot (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.2)', () => {
  function statusDot(root: HTMLElement): HTMLElement {
    return root.querySelector('.status-row .dot.status-dot')!;
  }

  it('a closed live transport degrades the dot to warn even with a reachable backend and an OK overall', () => {
    const fixture = render({ connectionState: 'closed', overall: 'OK' });
    const root = fixture.nativeElement as HTMLElement;
    const dot = statusDot(root);
    expect(dot.classList.contains('warn')).toBe(true);
    expect(dot.classList.contains('ok')).toBe(false);
    expect(dot.getAttribute('aria-label')).toBe('System status: degraded');
  });

  it('a DOWN overall wins even when the backend is reachable and live is open', () => {
    const fixture = render({ overall: 'DOWN' });
    const root = fixture.nativeElement as HTMLElement;
    const dot = statusDot(root);
    expect(dot.classList.contains('danger')).toBe(true);
    expect(dot.getAttribute('aria-label')).toBe('System status: down');
  });

  it('a DEGRADED overall alone reads as warn, not danger', () => {
    const fixture = render({ overall: 'DEGRADED' });
    const root = fixture.nativeElement as HTMLElement;
    const dot = statusDot(root);
    expect(dot.classList.contains('warn')).toBe(true);
    expect(dot.classList.contains('danger')).toBe(false);
  });

  it('overall not yet loaded (undefined) never claims ok on its own — reads as the bare neutral dot', () => {
    const fixture = render({ overall: undefined });
    const root = fixture.nativeElement as HTMLElement;
    const dot = statusDot(root);
    expect(dot.classList.contains('ok')).toBe(false);
    expect(dot.classList.contains('warn')).toBe(false);
    expect(dot.classList.contains('danger')).toBe(false);
    expect(dot.getAttribute('aria-label')).toBe('System status: checking…');
  });

  it('is a real routerLink to /manage/system, not a bare span with a click handler', async () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const dot = statusDot(root);
    expect(dot.tagName).toBe('A');

    dot.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/manage/system');
  });
});

/**
 * `ThemeFacade` is injected directly here (never faked) — the same "exercise the real, simple,
 * `providedIn: 'root'` boundary" precedent `SidebarFacade` already gets throughout this file, since it
 * is a plain persisted-signal store, not something with an HTTP/SSE dependency graph worth stubbing
 * (contrast `FleetStore`/`EventsFacade`/`LiveFacade` above, faked purely so the tree can mount).
 */
describe('AppSidebar — theme toggle (docs/plans/done/VISUAL-REFRESH-PLAN.md F3/Wave 1)', () => {
  beforeEach(() => localStorage.clear());

  it('defaults to light, showing the sun (current theme) with a control that switches to dark', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const toggle = root.querySelector('.theme-toggle') as HTMLButtonElement;

    expect(TestBed.inject(ThemeFacade).theme()).toBe('light');
    expect(toggle.getAttribute('aria-label')).toBe('Switch to dark theme');
    // Sun glyph is the <circle>-based svg; moon is a bare <path> with no circle — see this
    // component's own doc comment: the icon shows the *current* theme, not the destination.
    expect(toggle.querySelector('svg circle')).not.toBeNull();
    expect(toggle.querySelector('svg path')).toBeNull();
  });

  it('clicking the toggle flips ThemeFacade.theme, the button label/glyph, and <html data-theme>', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const toggle = root.querySelector('.theme-toggle') as HTMLButtonElement;

    toggle.click();
    fixture.detectChanges();

    expect(TestBed.inject(ThemeFacade).theme()).toBe('dark');
    expect(toggle.getAttribute('aria-label')).toBe('Switch to light theme');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(toggle.querySelector('svg path')).not.toBeNull();
    expect(toggle.querySelector('svg circle')).toBeNull();

    toggle.click();
    fixture.detectChanges();

    expect(TestBed.inject(ThemeFacade).theme()).toBe('light');
    expect(toggle.getAttribute('aria-label')).toBe('Switch to dark theme');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(toggle.querySelector('svg circle')).not.toBeNull();
    expect(toggle.querySelector('svg path')).toBeNull();
  });

  it('remains present and clickable on a full-bleed (rail-collapsed) route', () => {
    const fixture = render({ fullBleed: true });
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.sidebar')!.classList.contains('collapsed')).toBe(true);

    const toggle = root.querySelector('.theme-toggle') as HTMLButtonElement;
    expect(toggle).not.toBeNull();
    toggle.click();
    fixture.detectChanges();
    expect(TestBed.inject(ThemeFacade).theme()).toBe('dark');
  });

  // Regression guard: the toggle sits inside the brand `<a routerLink="/fly">` (see this file's own
  // class doc). Without `$event.stopPropagation()` in its click handler, the click bubbles to that
  // anchor and the router navigates to /fly — which then auto-collapses the sidebar via
  // `SidebarFacade.enterRoute()` (`app.ts`'s `NavigationEnd` handler), since /fly is full-bleed. A
  // theme click must never double as a navigation.
  it('does not navigate — it sits inside the brand <a routerLink="/fly"> and must stop click propagation', async () => {
    const fixture = render();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/assets');
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const toggle = root.querySelector('.theme-toggle') as HTMLButtonElement;
    toggle.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(router.url).toBe('/assets');
    expect(TestBed.inject(ThemeFacade).theme()).toBe('dark');
  });
});
