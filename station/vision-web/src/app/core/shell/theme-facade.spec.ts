import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { provideAppState } from '../state/app-state';
import { ThemeFacade } from './theme-facade';

/**
 * The theme slice end to end — facade → action → reducer → effect → `localStorage` + `data-theme`.
 * Replaces the old `ThemeStore` spec case for case, through the real store rather than a mock: the
 * behaviour that matters here (the attribute actually reaching `<html>`, the key actually being
 * written) lives in the effect, so a `provideMockStore` version would assert nothing real.
 */
describe('ThemeFacade', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    TestBed.configureTestingModule({ providers: [provideAppState()] });
  });

  it('defaults to light with no persisted preference, and applies data-theme="light" on boot', () => {
    const facade = TestBed.inject(ThemeFacade);
    expect(facade.theme()).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('setTheme("dark") updates the signal, persists it, and applies data-theme="dark"', () => {
    const facade = TestBed.inject(ThemeFacade);

    facade.setTheme('dark');
    expect(facade.theme()).toBe('dark');
    expect(localStorage.getItem('vision.theme')).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('setTheme("light") explicitly re-applies data-theme="light" (not just clears the attribute)', () => {
    const facade = TestBed.inject(ThemeFacade);
    facade.setTheme('dark');

    facade.setTheme('light');
    expect(facade.theme()).toBe('light');
    expect(localStorage.getItem('vision.theme')).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('toggle() flips between light and dark, persisting each change', () => {
    const facade = TestBed.inject(ThemeFacade);

    facade.toggle();
    expect(facade.theme()).toBe('dark');
    expect(localStorage.getItem('vision.theme')).toBe('dark');

    facade.toggle();
    expect(facade.theme()).toBe('light');
    expect(localStorage.getItem('vision.theme')).toBe('light');
  });

  it('a persisted dark preference survives into a fresh store and re-applies data-theme on boot', () => {
    TestBed.inject(ThemeFacade).setTheme('dark');
    // Simulate a fresh page load before `index.html`'s own inline bootstrap script has run.
    document.documentElement.removeAttribute('data-theme');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideAppState()] });
    const restored = TestBed.inject(ThemeFacade);
    expect(restored.theme()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('an invalid/garbage persisted value falls back to light rather than throwing', () => {
    localStorage.setItem('vision.theme', 'purple');
    const facade = TestBed.inject(ThemeFacade);
    expect(facade.theme()).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });
});
