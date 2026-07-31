import { type Signal, type WritableSignal, signal } from '@angular/core';

/**
 * `UiStore` — the single coordinator for a group of **mutually-exclusive overlays** (docs/
 * UI-ARCHITECTURE-PLAN.md). Generalizes `core/panel-state.ts#PanelState` (one-open-at-a-time drawers)
 * into the app-wide primitive that makes overlay state *consistent by construction*: within one
 * `UiStore` instance, opening any overlay closes whichever other was open, so "two menus open at
 * once / a stale confirm left behind" cannot happen — it is not a discipline, it is unrepresentable.
 *
 * **Scoped by instance.** A feature provides one `UiStore` per independent overlay *group*:
 *   - a `tool-rail` group (its drawers), persisted via `storageKey` so the open drawer survives reload
 *   - a separate `dialog` group (arm/disarm/mode/stop confirms, inline editors), transient (no
 *     `storageKey`), so a confirm can open *over* a drawer, but two confirms can never coexist.
 * Overlays that must be mutually exclusive share one instance; overlays that may legitimately overlap
 * get separate instances. Ids are plain strings, ideally a feature-local union type so the template
 * can't typo one past the compiler (see `fly-logic.ts#ToolRailPanelId` for the precedent).
 *
 * Deliberately a plain class, not `@Injectable()` — same reasoning as the `PanelState` it supersedes
 * (a bare `string` constructor arg has no DI token; a host owns one as a plain field,
 * `readonly dialogs = new UiStore()`, fully unit-testable with no `TestBed`, "provided per host" in
 * the only sense that matters). A future host preferring the injector can add `@Injectable()` + a
 * factory provider without a rewrite.
 */
export class UiStore {
  private readonly storageKey?: string;
  private readonly activeSignal: WritableSignal<string | null>;

  /** The one open overlay id in this group, or `null` when every overlay is closed. */
  readonly active: Signal<string | null>;

  /**
   * @param storageKey when given, the active id is round-tripped through `localStorage` under this
   *   key so a persistent overlay group (e.g. a tool-rail) survives reload. Omit for transient
   *   groups (confirms/editors), which should never outlive the page.
   */
  constructor(storageKey?: string) {
    this.storageKey = storageKey;
    this.activeSignal = signal(storageKey ? UiStore.read(storageKey) : null);
    this.active = this.activeSignal.asReadonly();
  }

  /** `true` exactly when `id` is the currently open overlay in this group. */
  isOpen(id: string): boolean {
    return this.activeSignal() === id;
  }

  /** Opens `id`, implicitly closing whichever other overlay (if any) was open — never two at once. */
  open(id: string): void {
    this.setActive(id);
  }

  /**
   * Closes the open overlay. With no argument, closes whichever is open; with `id`, closes only if
   * `id` is the one currently open (so a stale close from a since-replaced overlay is a no-op).
   */
  close(id?: string): void {
    if (id === undefined || this.isOpen(id)) {
      this.setActive(null);
    }
  }

  /** Opens `id` unless it's already open, in which case it closes instead. */
  toggle(id: string): void {
    this.setActive(this.isOpen(id) ? null : id);
  }

  private setActive(id: string | null): void {
    this.activeSignal.set(id);
    if (this.storageKey) {
      if (id === null) {
        localStorage.removeItem(this.storageKey);
      } else {
        localStorage.setItem(this.storageKey, id);
      }
    }
  }

  private static read(key: string): string | null {
    return localStorage.getItem(key);
  }
}
