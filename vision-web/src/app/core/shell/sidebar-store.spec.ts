import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { SidebarStore } from './sidebar-store';

describe('SidebarStore', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  it('starts expanded (not collapsed) and both disclosures closed with no persisted preference', () => {
    const store = TestBed.inject(SidebarStore);
    expect(store.collapsed()).toBe(false);
    expect(store.advancedOpen()).toBe(false);
    expect(store.upcomingOpen()).toBe(false);
  });

  it('toggle() flips collapsed and persists it', () => {
    const store = TestBed.inject(SidebarStore);

    store.toggle();
    expect(store.collapsed()).toBe(true);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBe('true');

    store.toggle();
    expect(store.collapsed()).toBe(false);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBe('false');
  });

  it('a collapsed preference survives into a fresh instance on the same storage', () => {
    TestBed.inject(SidebarStore).toggle();

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const restored = TestBed.inject(SidebarStore);
    expect(restored.collapsed()).toBe(true);
  });

  // --- Auto-collapse precedence (docs/design/00-shell.md; see the store's own class doc) ----------
  // Regression guard for the shipped bug where `collapsed() || fullBleed()` plus a disabled chevron
  // made the sidebar impossible to expand on /fly, /wall and /command.

  it('a full-bleed route collapses the sidebar without touching the stored preference', () => {
    const store = TestBed.inject(SidebarStore);

    store.enterRoute(true);
    expect(store.collapsed()).toBe(true);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBeNull();

    store.enterRoute(false);
    expect(store.collapsed()).toBe(false); // preference never changed, so leaving restores it
  });

  it('CAN be expanded on a full-bleed route, and that choice does not become the global default', () => {
    const store = TestBed.inject(SidebarStore);
    store.enterRoute(true);
    expect(store.collapsed()).toBe(true);

    store.toggle();
    expect(store.collapsed()).toBe(false); // the whole point: the toggle works here
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBeNull();

    // Navigating on gives the next page its own honest default rather than inheriting the override.
    store.enterRoute(false);
    expect(store.collapsed()).toBe(false);
    store.enterRoute(true);
    expect(store.collapsed()).toBe(true);
  });

  it('a manual collapse on an ordinary page persists and outranks a later route default', () => {
    const store = TestBed.inject(SidebarStore);
    store.enterRoute(false);

    store.toggle();
    expect(store.collapsed()).toBe(true);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBe('true');

    // Preference says collapsed, so a non-full-bleed route still opens collapsed…
    store.enterRoute(false);
    expect(store.collapsed()).toBe(true);
    // …and can still be expanded from there.
    store.toggle();
    expect(store.collapsed()).toBe(false);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBe('false');
  });

  it('toggleAdvanced()/toggleUpcoming() flip independently of collapsed and of each other, each persisted under its own key', () => {
    const store = TestBed.inject(SidebarStore);

    store.toggleAdvanced();
    expect(store.advancedOpen()).toBe(true);
    expect(store.upcomingOpen()).toBe(false);
    expect(store.collapsed()).toBe(false);
    expect(localStorage.getItem('vision.sidebar.advancedOpen')).toBe('true');
    expect(localStorage.getItem('vision.sidebar.upcomingOpen')).toBeNull();

    store.toggleUpcoming();
    expect(store.upcomingOpen()).toBe(true);
    expect(store.advancedOpen()).toBe(true); // unaffected by the other disclosure toggling
    expect(localStorage.getItem('vision.sidebar.upcomingOpen')).toBe('true');

    store.toggleAdvanced();
    expect(store.advancedOpen()).toBe(false);
    expect(store.upcomingOpen()).toBe(true); // still unaffected
  });

  it('setAdvancedOpen/setUpcomingOpen set an explicit value directly, mirroring a <details> toggle event', () => {
    const store = TestBed.inject(SidebarStore);

    store.setAdvancedOpen(true);
    expect(store.advancedOpen()).toBe(true);
    store.setAdvancedOpen(true); // idempotent — a second "open" toggle event must not flip it closed
    expect(store.advancedOpen()).toBe(true);

    store.setUpcomingOpen(false);
    expect(store.upcomingOpen()).toBe(false);
  });

  it('a fresh instance restores persisted advancedOpen/upcomingOpen independently', () => {
    const store = TestBed.inject(SidebarStore);
    store.setAdvancedOpen(true);
    store.setUpcomingOpen(false);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const restored = TestBed.inject(SidebarStore);
    expect(restored.advancedOpen()).toBe(true);
    expect(restored.upcomingOpen()).toBe(false);
  });
});
