import { type Signal, type WritableSignal, signal } from '@angular/core';

/**
 * Per-user `localStorage` persistence for a single boolean panel-state toggle (collapsed/pinned/
 * shown) — docs/UX-REWORK-PLAN.md §U-b item 7 ("panel state persists per user via localStorage,
 * Traccar-style: explicit collapse, reopen via toggle chip").
 *
 * Deliberately **not** routed through `core/settings/settings-store.ts`: that store owns
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
 * The string-valued sibling of `readPersistedFlag`/`writePersistedFlag` above (docs/UI-REDESIGN-PLAN.md
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

/**
 * `PanelState` — one-open-at-a-time drawer manager (docs/UI-REDESIGN-PLAN.md Frozen contract F3),
 * replacing the split open-state this codebase has today (`fly.ts`'s `mapVisible`/
 * `detectionsStripOpen`, `cv-control-panel.ts`'s own `cvPanelOpen`, each independently persisted and
 * with no shared "closing one implicitly closes another" rule) with a single `active: string | null`
 * signal. Not built by this wave's own consumers yet — Wave 2 (Fly's tool-rail) and Wave 3
 * (asset-detail's drill-ins) are the first real hosts; this wave only adds the class + its tests.
 *
 * **Deliberately a plain class, not an `@Injectable()` DI service** — the frozen contract's own
 * "constructed with an optional storageKey" usage doesn't fit Angular's constructor-DI cleanly (a
 * bare `string` constructor parameter has no injectable token for Angular to resolve through a
 * `providers: [PanelState]` array without an `InjectionToken` + a second per-host provider just to
 * carry one string — real ceremony for what this codebase already has a stated bias against, see
 * `core/panel-state.ts`'s own doc comment above on `readPersistedFlag`/`writePersistedFlag`'s "no
 * shared service/store class" reasoning). A host page instead owns one instance directly as a plain
 * field — `protected readonly panels = new PanelState('vision.fly.activePanel');` — which is exactly
 * "provided per host" in the sense that matters (never a shared singleton, never `providedIn:
 * 'root'`), fully unit-testable on its own with no `TestBed`, and identical from every consumer's
 * point of view to a DI-provided instance (same public `active`/`isOpen`/`open`/`close`/`toggle`
 * surface either way) — if a future host prefers Angular's injector instead, decorating this same
 * class with `@Injectable()` and constructing it via a factory provider remains a non-breaking
 * addition, not a rewrite.
 */
export class PanelState {
  private readonly storageKey?: string;
  private readonly activeSignal: WritableSignal<string | null>;

  /** The active panel id, or `null` when every panel is closed. */
  readonly active: Signal<string | null>;

  constructor(storageKey?: string) {
    this.storageKey = storageKey;
    const initial = storageKey ? readPersistedString(storageKey, null) : null;
    this.activeSignal = signal(initial);
    this.active = this.activeSignal.asReadonly();
  }

  /** `true` exactly when `id` is the currently active panel. */
  isOpen(id: string): boolean {
    return this.activeSignal() === id;
  }

  /** Opens `id`, implicitly closing whichever other panel (if any) was open — never two at once. */
  open(id: string): void {
    this.setActive(id);
  }

  /** Closes whichever panel is open; a no-op if none is. */
  close(): void {
    this.setActive(null);
  }

  /** Opens `id` unless it's already the active one, in which case it closes instead. */
  toggle(id: string): void {
    this.setActive(this.isOpen(id) ? null : id);
  }

  private setActive(id: string | null): void {
    this.activeSignal.set(id);
    if (this.storageKey) {
      writePersistedString(this.storageKey, id);
    }
  }
}
