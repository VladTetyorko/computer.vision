import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { provideAppState } from '../state/app-state';
import { SidebarFacade } from './sidebar-facade';

/**
 * The sidebar slice end to end — facade → action → reducer → effect → `localStorage`. The cases
 * that only a real effect can prove are the *absent* writes: a full-bleed auto-collapse, and a
 * manual expand taken over a video feed, must both leave the stored default untouched.
 */
describe('SidebarFacade', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideAppState()] });
  });

  it('starts expanded with both disclosures closed and nothing persisted', () => {
    const facade = TestBed.inject(SidebarFacade);
    expect(facade.collapsed()).toBe(false);
    expect(facade.advancedOpen()).toBe(false);
    expect(facade.upcomingOpen()).toBe(false);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBeNull();
  });

  it('toggle() flips collapsed and persists it', () => {
    const facade = TestBed.inject(SidebarFacade);

    facade.toggle();
    expect(facade.collapsed()).toBe(true);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBe('true');

    facade.toggle();
    expect(facade.collapsed()).toBe(false);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBe('false');
  });

  it('a collapsed preference survives into a fresh store', () => {
    TestBed.inject(SidebarFacade).toggle();

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideAppState()] });
    expect(TestBed.inject(SidebarFacade).collapsed()).toBe(true);
  });

  it('a full-bleed route collapses the sidebar without writing the stored preference', () => {
    const facade = TestBed.inject(SidebarFacade);

    facade.enterRoute(true);
    expect(facade.collapsed()).toBe(true);
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBeNull();

    facade.enterRoute(false);
    expect(facade.collapsed()).toBe(false);
  });

  it('CAN be expanded on a full-bleed route, and that choice is never written to storage', () => {
    const facade = TestBed.inject(SidebarFacade);
    facade.enterRoute(true);

    facade.toggle();
    expect(facade.collapsed()).toBe(false); // the regression this shape exists to keep closed
    expect(localStorage.getItem('vision.sidebar.collapsed')).toBeNull();
  });

  it('the two disclosures persist under their own keys, each untouched until used', () => {
    const facade = TestBed.inject(SidebarFacade);

    facade.toggleAdvanced();
    expect(facade.advancedOpen()).toBe(true);
    expect(localStorage.getItem('vision.sidebar.advancedOpen')).toBe('true');
    expect(localStorage.getItem('vision.sidebar.upcomingOpen')).toBeNull();

    facade.toggleUpcoming();
    expect(facade.upcomingOpen()).toBe(true);
    expect(localStorage.getItem('vision.sidebar.upcomingOpen')).toBe('true');
  });

  it('setAdvancedOpen/setUpcomingOpen restore independently into a fresh store', () => {
    const facade = TestBed.inject(SidebarFacade);
    facade.setAdvancedOpen(true);
    facade.setUpcomingOpen(false);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideAppState()] });
    const restored = TestBed.inject(SidebarFacade);
    expect(restored.advancedOpen()).toBe(true);
    expect(restored.upcomingOpen()).toBe(false);
  });
});
