/** The app's two user-selectable themes (docs/plans/done/VISUAL-REFRESH-PLAN.md F3) — the only two
 * values `data-theme` is ever set to; anything else read back from storage falls back to `'light'`. */
export type Theme = 'light' | 'dark';

export interface ThemeState {
  readonly theme: Theme;
}

/** Light is the default — `src/styles.css`'s bare `:root` block *is* the light theme
 * (docs/plans/done/VISUAL-REFRESH-PLAN.md's "daylight chart" direction). */
export const initialThemeState: ThemeState = { theme: 'light' };

/** Shared with `index.html`'s inline bootstrap script, which reads this exact key before Angular
 * loads to avoid a flash of the wrong theme for a returning dark-theme user. */
export const THEME_STORAGE_KEY = 'vision.theme';
