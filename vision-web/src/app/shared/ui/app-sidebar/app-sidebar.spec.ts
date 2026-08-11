import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { AppSidebar } from './app-sidebar';
import { AuthStore } from '../../../core/auth/auth-store';
import { FleetStore } from '../../../core/fleet/fleet-store';
import { EventsStore } from '../../../core/events/events-store';
import { LiveStore } from '../../../core/live/live-store';
import { VisionApi } from '../../../core/api/vision-api';
import { SidebarStore } from '../../../core/shell/sidebar-store';
import { ThemeStore } from '../../../core/shell/theme-store';
import { NAV_MODES, navTiers } from '../../../features/hubs/nav-entries';

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

function fakeLiveStore() {
  return { liveEvents: () => [] as unknown[] };
}

function fakeAuthStore(topRole?: 'ADMIN' | 'MANAGER' | 'PILOT') {
  return {
    user: () => (topRole ? { topRole, displayName: 'Test User', username: 'test' } : null),
    authEnabled: () => false,
  };
}

@Component({ selector: 'vision-test-stub-page', template: '' })
class StubPage {}

function render(options: {
  topRole?: 'ADMIN' | 'MANAGER' | 'PILOT';
  streams?: unknown[];
  reachable?: boolean;
  fullBleed?: boolean;
} = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'fly', component: StubPage },
        { path: 'command', component: StubPage },
        { path: 'assets', component: StubPage },
      ]),
      { provide: FleetStore, useValue: fakeFleetStore(options) },
      { provide: AuthStore, useValue: fakeAuthStore(options.topRole) },
      { provide: EventsStore, useValue: fakeEventsStore() },
      { provide: LiveStore, useValue: fakeLiveStore() },
      { provide: VisionApi, useValue: {} },
    ],
  });
  // `fullBleed` is no longer an input — the route flag reaches the sidebar through `SidebarStore`
  // (`app.ts` calls `enterRoute` on every NavigationEnd), which layers it under any manual toggle.
  if (options.fullBleed !== undefined) {
    TestBed.inject(SidebarStore).enterRoute(options.fullBleed);
  }
  const fixture = TestBed.createComponent(AppSidebar);
  fixture.detectChanges();
  return fixture;
}

function navRowHrefs(root: HTMLElement): (string | null)[] {
  return Array.from(root.querySelectorAll('a.nav-row')).map((a) => a.getAttribute('href'));
}

describe('AppSidebar — tiering + role gate', () => {
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

  it("renders each mode's primary tier as routerLink rows, in order, for a non-manager", () => {
    const fixture = render({ topRole: 'PILOT' });
    const root = fixture.nativeElement as HTMLElement;
    const groups = root.querySelectorAll('.nav-group');

    NAV_MODES.forEach((mode, i) => {
      const visible = mode.entries.filter((entry) => !entry.managerOnly);
      const primary = navTiers(visible).primary;
      const hrefs = Array.from(groups[i].querySelectorAll(':scope > a.nav-row')).map((a) => a.getAttribute('href'));
      expect(hrefs, mode.id).toEqual(primary.map((entry) => entry.to));
    });
  });

  it('folds the diagnostics/advanced group entries under a collapsed Advanced disclosure for an ADMIN', () => {
    const fixture = render({ topRole: 'ADMIN' });
    const root = fixture.nativeElement as HTMLElement;
    const manageIndex = NAV_MODES.findIndex((mode) => mode.id === 'manage');
    const manageGroup = root.querySelectorAll('.nav-group')[manageIndex];

    const advancedTier = navTiers(NAV_MODES[manageIndex].entries).advanced;
    expect(advancedTier.length).toBeGreaterThan(0); // Devices + Debug, per nav-entries.ts

    const disclosures = manageGroup.querySelectorAll('details.disclosure');
    // Manage has both an Advanced and an Upcoming disclosure for an ADMIN.
    expect(disclosures.length).toBe(2);
    const advancedDetails = disclosures[0] as HTMLDetailsElement;
    expect(advancedDetails.querySelector('summary')?.textContent).toContain('Advanced');
    // Closed by default (no persisted preference).
    expect(advancedDetails.open).toBe(false);
    const hrefs = Array.from(advancedDetails.querySelectorAll('a.nav-row')).map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(advancedTier.map((entry) => entry.to));
  });

  it('folds badge:"soon" entries under a collapsed Upcoming disclosure, each dimmed and carrying no per-row chip (docs/plans/done/VISUAL-REFRESH-PLAN.md Wave 1 — the disclosure title already says it)', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const operateIndex = NAV_MODES.findIndex((mode) => mode.id === 'operate');
    const operateGroup = root.querySelectorAll('.nav-group')[operateIndex];

    const upcomingTier = navTiers(NAV_MODES[operateIndex].entries).upcoming;
    expect(upcomingTier.length).toBeGreaterThan(0); // Flight plans / missions

    const details = operateGroup.querySelector('details.disclosure') as HTMLDetailsElement;
    expect(details.querySelector('summary')?.textContent).toContain('Upcoming');
    const rows = details.querySelectorAll('a.nav-row.dimmed');
    expect(rows.length).toBe(upcomingTier.length);
    for (const row of Array.from(rows)) {
      expect(row.querySelector('.chip')).toBeNull();
    }
  });

  it('renders every group label without an icon (docs/plans/done/VISUAL-REFRESH-PLAN.md Wave 1 — icons compete with each row\'s own icon two rows down)', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    for (const label of Array.from(root.querySelectorAll('.group-label'))) {
      expect(label.querySelector('vision-icon')).toBeNull();
      expect(label.querySelector('.group-label-text')?.textContent?.trim().length).toBeGreaterThan(0);
    }
  });

  it('a PILOT sees no managerOnly entry anywhere, an ADMIN sees the full set (F10 — filtered exactly once, here)', () => {
    const pilot = render({ topRole: 'PILOT' });
    const pilotHrefs = navRowHrefs(pilot.nativeElement as HTMLElement);
    for (const mode of NAV_MODES) {
      for (const entry of mode.entries) {
        if (entry.managerOnly) {
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
    const manage = NAV_MODES.find((mode) => mode.id === 'manage')!;
    expect(navRowHrefs(root)).toEqual(expect.arrayContaining(manage.entries.map((entry) => entry.to)));
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

  it('collapses when SidebarStore.collapsed is toggled', () => {
    const fixture = render();
    TestBed.inject(SidebarStore).toggle();
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
    expect(onlineDot!.getAttribute('aria-label')).toBe('Backend reachable');
  });

  it('shows the offline dot + label when the fleet is unreachable, with no live-count chip', () => {
    const fixture = render({ reachable: false, streams: [] });
    const root = fixture.nativeElement as HTMLElement;
    const onlineDot = root.querySelector('.status-row .dot.status-dot');
    expect(onlineDot!.classList.contains('danger')).toBe(true);
    expect(onlineDot!.getAttribute('aria-label')).toBe('Backend unreachable');
    expect(root.querySelector('.status-row .chip')).toBeNull();
  });

  /**
   * docs/plans/done/UI-STATE-PLAN.md §1 D1/D3, §2.2: the mobile sheet now shares `GlobalOverlayStore` with the
   * identity menu/notification bell it's mounted alongside, so opening one closes the other — this is
   * the same exclusivity `core/ui/overlay-store.spec.ts` proves at the store level, checked here
   * through the real rendered shell (the actual scenario the sheet and the chip share one DOM tree).
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

describe('AppSidebar — Advanced/Upcoming disclosures persist via SidebarStore', () => {
  beforeEach(() => localStorage.clear());

  it('toggling the Advanced <details> writes through to SidebarStore.advancedOpen', () => {
    const fixture = render({ topRole: 'ADMIN' });
    const store = TestBed.inject(SidebarStore);
    expect(store.advancedOpen()).toBe(false);

    const root = fixture.nativeElement as HTMLElement;
    const manageIndex = NAV_MODES.findIndex((mode) => mode.id === 'manage');
    const advancedDetails = root.querySelectorAll('.nav-group')[manageIndex].querySelectorAll('details.disclosure')[0] as HTMLDetailsElement;

    advancedDetails.open = true;
    advancedDetails.dispatchEvent(new Event('toggle'));
    fixture.detectChanges();

    expect(store.advancedOpen()).toBe(true);
  });

  it('a persisted advancedOpen=true renders the disclosure already open', () => {
    TestBed.configureTestingModule({});
    TestBed.inject(SidebarStore).setAdvancedOpen(true);
    TestBed.resetTestingModule();

    const fixture = render({ topRole: 'ADMIN' });
    const root = fixture.nativeElement as HTMLElement;
    const manageIndex = NAV_MODES.findIndex((mode) => mode.id === 'manage');
    const advancedDetails = root.querySelectorAll('.nav-group')[manageIndex].querySelectorAll('details.disclosure')[0] as HTMLDetailsElement;
    expect(advancedDetails.open).toBe(true);
  });
});

/**
 * `ThemeStore` is injected directly here (never faked) — the same "exercise the real, simple,
 * `providedIn: 'root'` store" precedent `SidebarStore` already gets throughout this file, since it
 * is a plain persisted-signal store, not something with an HTTP/SSE dependency graph worth stubbing
 * (contrast `FleetStore`/`EventsStore`/`LiveStore` above, faked purely so the tree can mount).
 */
describe('AppSidebar — theme toggle (docs/plans/done/VISUAL-REFRESH-PLAN.md F3/Wave 1)', () => {
  beforeEach(() => localStorage.clear());

  it('defaults to light, showing the sun (current theme) with a control that switches to dark', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const toggle = root.querySelector('.theme-toggle') as HTMLButtonElement;

    expect(TestBed.inject(ThemeStore).theme()).toBe('light');
    expect(toggle.getAttribute('aria-label')).toBe('Switch to dark theme');
    // Sun glyph is the <circle>-based svg; moon is a bare <path> with no circle — see this
    // component's own doc comment: the icon shows the *current* theme, not the destination.
    expect(toggle.querySelector('svg circle')).not.toBeNull();
    expect(toggle.querySelector('svg path')).toBeNull();
  });

  it('clicking the toggle flips ThemeStore.theme, the button label/glyph, and <html data-theme>', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const toggle = root.querySelector('.theme-toggle') as HTMLButtonElement;

    toggle.click();
    fixture.detectChanges();

    expect(TestBed.inject(ThemeStore).theme()).toBe('dark');
    expect(toggle.getAttribute('aria-label')).toBe('Switch to light theme');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(toggle.querySelector('svg path')).not.toBeNull();
    expect(toggle.querySelector('svg circle')).toBeNull();

    toggle.click();
    fixture.detectChanges();

    expect(TestBed.inject(ThemeStore).theme()).toBe('light');
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
    expect(TestBed.inject(ThemeStore).theme()).toBe('dark');
  });

  // Regression guard: the toggle sits inside the brand `<a routerLink="/fly">` (see this file's own
  // class doc). Without `$event.stopPropagation()` in its click handler, the click bubbles to that
  // anchor and the router navigates to /fly — which then auto-collapses the sidebar via
  // `SidebarStore.enterRoute()` (`app.ts`'s `NavigationEnd` handler), since /fly is full-bleed. A
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
    expect(TestBed.inject(ThemeStore).theme()).toBe('dark');
  });
});
