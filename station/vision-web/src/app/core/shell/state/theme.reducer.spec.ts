import { describe, expect, it } from 'vitest';
import { ThemePageActions } from './theme.actions';
import { themeHydrator } from './theme.hydration';
import { initialThemeState, THEME_STORAGE_KEY } from './theme.model';
import { themeFeature } from './theme.reducer';

/** Pure spec — reducer, selector and hydrator, no TestBed. The slice end-to-end (effects, storage,
 * `data-theme`) is covered by `theme-facade.spec.ts`. */
describe('theme reducer', () => {
  it('defaults to light', () => {
    expect(initialThemeState.theme).toBe('light');
    expect(themeFeature.reducer(undefined, { type: 'init' }).theme).toBe('light');
  });

  it('Theme Selected sets the named theme', () => {
    const state = themeFeature.reducer(initialThemeState, ThemePageActions.themeSelected({ theme: 'dark' }));
    expect(state.theme).toBe('dark');
  });

  it('Theme Toggled flips, in both directions', () => {
    const dark = themeFeature.reducer(initialThemeState, ThemePageActions.themeToggled());
    expect(dark.theme).toBe('dark');
    expect(themeFeature.reducer(dark, ThemePageActions.themeToggled()).theme).toBe('light');
  });

  it('does not mutate the state it was handed', () => {
    const before = { ...initialThemeState };
    themeFeature.reducer(before, ThemePageActions.themeSelected({ theme: 'dark' }));
    expect(before.theme).toBe('light');
  });
});

describe('theme hydrator', () => {
  it('restores a persisted dark choice', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    expect(themeHydrator.read()).toEqual({ theme: 'dark' });
  });

  it('leaves the reducer default standing when nothing is persisted', () => {
    localStorage.removeItem(THEME_STORAGE_KEY);
    expect(themeHydrator.read()).toBeUndefined();
  });

  it('leaves the reducer default standing for a garbage value rather than passing it through', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    expect(themeHydrator.read()).toBeUndefined();
  });
});
