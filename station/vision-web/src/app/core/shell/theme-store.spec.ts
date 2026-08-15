import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ThemeStore } from './theme-store';

describe('ThemeStore', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    TestBed.configureTestingModule({});
  });

  it('defaults to light with no persisted preference, and applies data-theme="light" on construction', () => {
    const store = TestBed.inject(ThemeStore);
    expect(store.theme()).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('setTheme("dark") updates the signal, persists it, and applies data-theme="dark"', () => {
    const store = TestBed.inject(ThemeStore);

    store.setTheme('dark');
    expect(store.theme()).toBe('dark');
    expect(localStorage.getItem('vision.theme')).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('setTheme("light") explicitly re-applies data-theme="light" (not just clears the attribute)', () => {
    const store = TestBed.inject(ThemeStore);
    store.setTheme('dark');

    store.setTheme('light');
    expect(store.theme()).toBe('light');
    expect(localStorage.getItem('vision.theme')).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('toggle() flips between light and dark, persisting each change', () => {
    const store = TestBed.inject(ThemeStore);

    store.toggle();
    expect(store.theme()).toBe('dark');
    expect(localStorage.getItem('vision.theme')).toBe('dark');

    store.toggle();
    expect(store.theme()).toBe('light');
    expect(localStorage.getItem('vision.theme')).toBe('light');
  });

  it('a persisted dark preference survives into a fresh instance and re-applies data-theme on construction', () => {
    TestBed.inject(ThemeStore).setTheme('dark');
    // Simulate a fresh page load before `index.html`'s own inline bootstrap script has run.
    document.documentElement.removeAttribute('data-theme');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const restored = TestBed.inject(ThemeStore);
    expect(restored.theme()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('an invalid/garbage persisted value falls back to light rather than throwing', () => {
    localStorage.setItem('vision.theme', 'purple');
    const store = TestBed.inject(ThemeStore);
    expect(store.theme()).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });
});
