/**
 * Per-user `localStorage` persistence for a single boolean panel-state toggle (collapsed/pinned/
 * shown) — docs/plans/done/UX-REWORK-PLAN.md §U-b item 7 ("panel state persists per user via localStorage,
 * Traccar-style: explicit collapse, reopen via toggle chip").
 *
 * Deliberately **not** routed through `core/settings/settings-facade.ts`: that store owns
 * account-level choices restored/persisted together as one JSON blob (the active pipeline profile,
 * the map layer, `wallDensity` — the last of which already persisted before this task and needed
 * no change here). A panel's open/closed flag is a much smaller, purely cosmetic per-page memory
 * that doesn't need a place in that blob's shape, so each consuming component just calls these two
 * functions directly against its own key — no shared service/store class, per this task's own
 * "small, per-component, no new framework" instruction. Applied only where a collapse/show-hide
 * toggle already existed: `features/live/live.ts`'s `railOpen`/`mapInsetVisible` and
 * `features/fly/fly.ts`'s `mapVisible`/`detectionsStripOpen`.
 */
export function readPersistedFlag(key: string, fallback: boolean): boolean {
  const raw = localStorage.getItem(key);
  return raw === null ? fallback : raw === 'true';
}

export function writePersistedFlag(key: string, value: boolean): void {
  localStorage.setItem(key, String(value));
}

/**
 * The string-valued sibling of `readPersistedFlag`/`writePersistedFlag` above (docs/plans/done/UI-REDESIGN-PLAN.md
 * Frozen contract F3) — added for `PanelState`'s own active-panel-id persistence below, generalized
 * enough for any future plain string preference. `fallback`/the return type are `string | null`
 * (not just `string`) so "nothing persisted yet" and "explicitly persisted as closed" can both be
 * represented as `null` without a magic sentinel string; `writePersistedString(key, null)` removes
 * the key outright rather than writing the literal text `"null"`, so a later `readPersistedString`
 * call correctly falls back to its own `fallback` argument instead of reading back a stale string.
 */
export function readPersistedString(key: string, fallback: string | null): string | null {
  const raw = localStorage.getItem(key);
  return raw === null ? fallback : raw;
}

export function writePersistedString(key: string, value: string | null): void {
  if (value === null) {
    localStorage.removeItem(key);
  } else {
    localStorage.setItem(key, value);
  }
}

/*
 * The `PanelState` class that used to live here — the one-open-at-a-time overlay coordinator — has
 * been generalized and moved to `core/ui/ui-store.ts` as `UiStore` (docs/plans/done/UI-ARCHITECTURE-PLAN.md).
 * This file now holds only the small per-key `localStorage` persistence helpers above, which several
 * facades still use directly for non-overlay toggles (a persisted `railOpen`, `mapInsetVisible`, a
 * remembered tab). The filename is kept to avoid churning those import sites.
 */
