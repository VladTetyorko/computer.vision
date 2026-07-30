import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { App } from './app';
import { FleetStore } from './core/fleet/fleet-store';
import { LeafletWarmup } from './core/leaflet-warmup';
import { AuthStore } from './core/auth/auth-store';
import { EventsStore } from './core/events/events-store';
import { LiveStore } from './core/live/live-store';
import { VisionApi } from './core/api/vision-api';
import { NAV_MODES } from './features/hubs/nav-entries';

/**
 * The Wave 1 "shell renders three modes; dropdowns open/close" spec (docs/UI-REDESIGN-PLAN.md
 * Wave 1's own Verify bullet). `App` pulls in `IdentityChip`/`NotificationBell`, each with their own
 * deep store graph (`AuthStore`, `FleetStore`, `EventsStore`, `LiveStore`, `VisionApi`) — every one
 * of those is overridden with a minimal, side-effect-free fake here (no HTTP, no polling, no real
 * `PollScheduler`) purely so the shell can mount at all; none of their own behavior is under test in
 * this file (see each store's own spec for that). `ToastService`/`UndoToastService` are left real —
 * both are self-contained `signal()`-only state with no injected dependencies of their own, so
 * there's nothing to fake.
 */
function fakeFleetStore() {
  return { streams: () => [] as unknown[], reachable: () => true };
}

function fakeEventsStore() {
  return { activate: () => {}, release: () => {}, events: () => [] as unknown[] };
}

function fakeLiveStore() {
  return { liveEvents: () => [] as unknown[] };
}

function fakeAuthStore() {
  return { user: () => null, authEnabled: () => false };
}

function render() {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: FleetStore, useValue: fakeFleetStore() },
      { provide: LeafletWarmup, useValue: { schedule: () => {} } },
      { provide: AuthStore, useValue: fakeAuthStore() },
      { provide: EventsStore, useValue: fakeEventsStore() },
      { provide: LiveStore, useValue: fakeLiveStore() },
      { provide: VisionApi, useValue: {} },
    ],
  });
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  return fixture;
}

describe('App shell — hub-and-spoke mode nav', () => {
  it('renders exactly the three frozen modes as a hub routerLink + a details dropdown each', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;

    const modeLinks = Array.from(root.querySelectorAll('.modes-wide .mode-link')) as HTMLAnchorElement[];
    expect(modeLinks.map((a) => a.getAttribute('href'))).toEqual(NAV_MODES.map((mode) => mode.hubRoute));
    expect(modeLinks.map((a) => a.textContent?.trim())).toEqual(NAV_MODES.map((mode) => mode.label));

    expect(root.querySelectorAll('.modes-wide .mode-more').length).toBe(3);
  });

  it("each mode's dropdown lists exactly that mode's own entries, in order", () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const modeEls = root.querySelectorAll('.modes-wide .mode');

    NAV_MODES.forEach((mode, i) => {
      const links = Array.from(modeEls[i].querySelectorAll('.mode-more-menu a')) as HTMLAnchorElement[];
      expect(links.map((a) => a.getAttribute('href')), mode.id).toEqual(mode.entries.map((entry) => entry.to));
      const names = links.map((a) => a.querySelector('.entry-name')?.textContent?.trim());
      expect(names, mode.id).toEqual(mode.entries.map((entry) => entry.name));
    });
  });

  it('a scaffold entry renders its "soon" badge inside the dropdown', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;

    const chips = Array.from(root.querySelectorAll('.modes-wide .mode-more-menu .chip')) as HTMLElement[];
    expect(chips.length).toBeGreaterThan(0);
    for (const chip of chips) {
      expect(chip.textContent?.trim()).toBe('soon');
    }
  });

  it("clicking inside a mode's dropdown menu closes it (existing .tab-more idiom, click delegated at the menu container)", () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const details = root.querySelector('.modes-wide .mode-more') as HTMLDetailsElement;

    details.open = true;
    fixture.detectChanges();
    expect(details.open).toBe(true);

    // Dispatched on the menu container itself, not the anchor — this exercises the same
    // `(click)="modeMenu.open = false"` delegation `app.html` binds on `.mode-more-menu` (mirroring
    // `.tab-more-menu`/`identity-chip`'s own idiom) without also invoking `RouterLink`'s real
    // navigation, which `provideRouter([])`'s empty route table can't resolve.
    const menu = details.querySelector('.mode-more-menu') as HTMLElement;
    menu.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(details.open).toBe(false);
  });

  it('the narrow "Menu" disclosure groups every mode + its entries, labeled (no icon-only reduction)', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    const groups = root.querySelectorAll('.modes-narrow-group');

    expect(groups.length).toBe(3);
    NAV_MODES.forEach((mode, i) => {
      expect(groups[i].querySelector('.modes-narrow-mode')?.textContent).toContain(mode.label);
      const entries = groups[i].querySelectorAll('.modes-narrow-entry');
      expect(entries.length).toBe(mode.entries.length);
    });
  });

  it('preserves the existing status chips + notification bell + identity chip', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('vision-notification-bell')).not.toBeNull();
    expect(root.querySelector('vision-identity-chip')).not.toBeNull();
    expect(root.querySelector('.status .chip')?.textContent).toContain('ONLINE');
  });
});
