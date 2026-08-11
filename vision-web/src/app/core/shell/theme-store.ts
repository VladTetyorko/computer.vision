import { Injectable, signal } from '@angular/core';
import { readPersistedString, writePersistedString } from '../panel-state';

const THEME_KEY = 'vision.theme';

/** The app's two user-selectable themes (docs/plans/done/VISUAL-REFRESH-PLAN.md F3) — the only two values
 * `data-theme` is ever set to; anything else read back from storage falls back to `'light'`. */
export type Theme = 'light' | 'dark';

/**
 * `ThemeStore` — the persisted theme choice behind `src/styles.css`'s two-theme token system
 * (docs/plans/done/VISUAL-REFRESH-PLAN.md F3, `.claude/skills/frontend-style/SKILL.md` §2). One
 * `localStorage`-backed value, `'light' | 'dark'`, mirroring `core/shell/sidebar-store.ts#SidebarStore`'s
 * shape for a single persisted preference (`readPersistedString`/`writePersistedString` — the
 * string-valued sibling of that store's own `readPersistedFlag`/`writePersistedFlag`, `core/panel-state.ts`)
 * — a second, smaller store rather than a third field bolted onto `SidebarStore`, since theme has
 * nothing to do with navigation layout and a future dark-mode toggle should not need to pull in the
 * sidebar's own full-bleed/disclosure machinery to depend on it.
 *
 * **Light is the default** (`docs/plans/done/VISUAL-REFRESH-PLAN.md`'s "daylight chart" direction) — an absent
 * or unrecognized stored value (a stale key from before this store existed, or hand-edited
 * `localStorage`) resolves to `'light'`, never to whatever `:root`'s own bare declarations happen to
 * be; `readTheme()` below is the one place that fallback lives.
 *
 * **Applies `data-theme` on `document.documentElement`, explicitly, for both values** — not just
 * "set the attribute for dark, remove it for light". `:root` alone already resolves the light
 * values with no attribute present (`src/styles.css`'s own `:root { ... }` block *is* the light
 * theme), so an implicit clear-on-light would still render correctly, but explicit-both keeps this
 * store's own contract simple to state and verify ("the attribute always names the current theme")
 * and matches `index.html`'s own inline bootstrap script, which sets the same attribute explicitly
 * before Angular loads at all (see that file's own comment) to avoid a flash of the wrong theme for
 * a returning dark-theme user — this store re-applies the identical value once Angular boots, a
 * deliberately idempotent no-op in the common case rather than something that needs to special-case
 * "did the inline script already do this".
 *
 * **`providedIn: 'root'`, one shared instance** — same reasoning as `SidebarStore`: there is exactly
 * one theme for the whole session, and it must be injected eagerly by `app.ts` (constructing this
 * store's side effect — applying `data-theme` — the instant the app boots, before any component
 * that might read a themed colour renders) the same way `SidebarStore`/`FleetStore` already are.
 *
 * No visible toggle UI here — this store only holds and applies the value. Wave 1
 * (docs/plans/done/VISUAL-REFRESH-PLAN.md) adds the sidebar-footer/Settings switch that calls `setTheme`/`toggle`.
 */
@Injectable({ providedIn: 'root' })
export class ThemeStore {
  private readonly themeSignal = signal<Theme>(readTheme());

  readonly theme = this.themeSignal.asReadonly();

  constructor() {
    applyTheme(this.themeSignal());
  }

  /** Sets the theme outright and persists it — the eventual toggle UI's "light"/"dark" click handler. */
  setTheme(value: Theme): void {
    this.themeSignal.set(value);
    writePersistedString(THEME_KEY, value);
    applyTheme(value);
  }

  /** Flips the theme — the eventual sidebar-footer/Settings switch's own click handler. */
  toggle(): void {
    this.setTheme(this.themeSignal() === 'light' ? 'dark' : 'light');
  }
}

function readTheme(): Theme {
  return readPersistedString(THEME_KEY, 'light') === 'dark' ? 'dark' : 'light';
}

function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute('data-theme', theme);
}
