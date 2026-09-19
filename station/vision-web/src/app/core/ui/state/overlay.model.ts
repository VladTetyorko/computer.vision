/**
 * Every overlay the app **shell** owns, as opposed to a page's own `UiStore` group
 * (docs/plans/done/UI-STATE-PLAN.md §2.1's "page overlays" tier, e.g. `fly`'s tool-rail or
 * `asset-detail`'s editors). A union, not a free string, so a typo (`'idenity-menu'`) fails to
 * compile instead of silently never matching anything — §3's guardrail is that "any new
 * `GlobalOverlayId` must be registered in the store's union type", which the compiler already
 * enforces by construction.
 */
export type GlobalOverlayId = 'identity-menu' | 'notification-bell' | 'sidebar-mobile';

/**
 * The overlay slice's entire state (docs/plans/done/NGRX-MIGRATION-PLAN.md §8 "GlobalOverlayStore
 * splits in two") — just the open overlay id, or `null`. The DOM registry a `register()` call needs
 * (live `HTMLElement` refs) is **not** state at all — it would trip `strictStateSerializability` the
 * instant one was registered — and lives instead in `core/ui/overlay-host-registry.ts#OverlayHostRegistry`,
 * a small root service the effects and the facade both inject directly.
 */
export interface OverlayState {
  readonly active: GlobalOverlayId | null;
}

/** Transient by construction — no hydrator registers this slice in `core/state/app-state.ts`. A
 *  shell overlay must never survive a reload; a fresh page load always starts every one closed. */
export const initialOverlayState: OverlayState = { active: null };
