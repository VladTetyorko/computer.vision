import { readPersistedString } from '../../panel-state';
import type { StateHydrator } from '../../state/hydration';
import { THEME_STORAGE_KEY, type ThemeState } from './theme.model';
import { themeFeature } from './theme.reducer';

/**
 * Restores the persisted choice, and *only* a recognized one: a stale key from before this slice
 * existed, or a hand-edited `localStorage`, resolves to `undefined` so the reducer's own `'light'`
 * default stands rather than `data-theme="purple"` reaching the DOM.
 */
export const themeHydrator: StateHydrator<ThemeState> = {
  featureKey: themeFeature.name,
  read: () => {
    const persisted = readPersistedString(THEME_STORAGE_KEY, null);
    return persisted === 'dark' || persisted === 'light' ? { theme: persisted } : undefined;
  },
};
