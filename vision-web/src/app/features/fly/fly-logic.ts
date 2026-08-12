import type { AssetStatus, AssetSummary, AssetUsage, GeoPosition } from '../../core/api/models';
import { formatDuration } from '../../core/stream-info-logic';

/**
 * Re-exported from `core/telemetry/telemetry-logic.ts`, which is now its canonical home
 * (docs/plans/done/REALTIME-PLAN.md §4 Phase R-c follow-up — see that function's own doc comment for why:
 * `features/asset-detail/asset-detail.ts` needed the identical guard). Kept here too so this page's
 * own existing `trackingIdChanged` import site keeps working verbatim.
 */
export { trackingIdChanged } from '../../core/telemetry/telemetry-logic';

/**
 * Re-exported from `shared/player/detection-overlay-logic.ts`, its canonical home since
 * docs/plans/active/MEDIA-SOT-PLAN.md §8 wave M8 — `WallTile`'s own per-tile cycle button needed the
 * identical burnedIn-aware cycle, so the function (plus its `BOXES_CYCLE` constant) moved there
 * rather than staying duplicated. Kept here too so `CockpitFacade`'s existing import site, and this
 * file's own `cycleBoxesMode` tests, keep working verbatim.
 */
export { cycleBoxesMode } from '../../shared/player/detection-overlay-logic';

/**
 * Pure, Angular-free logic behind `FlyPage` (docs/plans/done/MVP3-PLAN.md §C-b) — split out so picker
 * ordering, remembered/requested-asset resolution, the "Replay last flight" link, watch-mode
 * parsing, and the keyboard boxes-cycle are unit-testable without HTTP, the router, or `document`,
 * mirroring every other page's own `*-logic.ts` split (`core/map/map-logic.ts`,
 * `features/replay/replay-logic.ts`, etc.).
 */

/**
 * The asset picker's own order: streaming assets first (an operator most likely wants to jump
 * straight into what's already in the air), then alphabetical by display name — a stable, obvious
 * secondary sort once the streaming/not split is applied. `Array#sort` is stable, so two assets
 * with the same `status` keep their relative order from the second comparison alone.
 */
export function sortAssetsForPicker(assets: readonly AssetSummary[]): readonly AssetSummary[] {
  return [...assets].sort((a, b) => {
    if (a.status !== b.status) {
      return a.status === 'STREAMING' ? -1 : 1;
    }
    return a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' });
  });
}

/**
 * The one auto-redirect `/fly` performs on its own (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12,
 * docs/extracts/design/01-fly.md — "skip the picker when it has nothing to ask"): the operator's remembered
 * drone (`SettingsStore.flyAssetId`) only counts as "nothing to ask" while it is still actually
 * **streaming** — merely still existing (the pre-split `resolveActiveAssetId`'s own bar, back when
 * one component quietly switched between picker/cockpit with no URL change at all) is not enough. A
 * remembered drone that has simply landed is exactly the case the picker should still ask about,
 * not silently re-enter. `undefined` (render the picker) covers "nothing remembered", "remembered
 * id no longer exists" (archived/deleted since) and "remembered id exists but isn't streaming"
 * alike — `fly-redirect-guard.ts`'s own caller doesn't need to tell those apart, only whether it has
 * something to skip straight into.
 *
 * An explicit `?asset=` query param (Command's/Alerts' own drill-down links) is a *stronger* signal
 * than "remembered" and is handled separately, unconditionally, by the guard itself — see that
 * file's own doc comment for why it never calls this function at all.
 */
export function rememberedStreamingAssetId(
  assets: readonly AssetSummary[],
  rememberedAssetId: string | null,
): string | undefined {
  if (!rememberedAssetId) {
    return undefined;
  }
  const asset = assets.find((candidate) => candidate.assetId === rememberedAssetId);
  return asset?.status === 'STREAMING' ? rememberedAssetId : undefined;
}

/**
 * The most recent *finished* usage — "Replay last flight" — backing `assetId`'s
 * `recentUsages`, which is already newest-first (mirrors `features/asset-detail/asset-detail.html`'s
 * identical "Replay only makes sense for a usage that's actually done" rule).
 */
export function latestFinishedUsage(usages: readonly AssetUsage[]): AssetUsage | undefined {
  return usages.find((usage) => usage.endedAt !== undefined);
}

/**
 * `?watch=1` exactly (docs/plans/done/MVP3-PLAN.md §C-b — "Watch mode `?watch=1`: hides Start/Stop"). Only
 * this literal value counts, not any other truthy-looking string — a deliberate, narrow contract
 * for a query param a future cycle (C-c) constructs itself, not one a person is expected to type.
 */
export function isWatchMode(param: string | undefined): boolean {
  return param === '1';
}

/** How many rows the events ticker overlay shows at once — glanceable, not a full feed (see the Wall rail for that). */
export const TICKER_MAX_EVENTS = 4;

// --- Tool-rail / drawer wiring (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2, D-D) ---------------------------
// The right-edge icon tool-rail replaces the split `mapVisible`/`detectionsStripOpen`/`cvPanelOpen`/
// `shortcutsOpen` open-flags with a single per-page `PanelState` (`core/panel-state.ts`). The ids
// below are the frozen set (D-D) — typed here (not just inline string literals in `fly.html`/
// `fly.ts`) purely so a typo in a rail button's `panels.toggle(...)`/`panels.isOpen(...)` call is a
// compile error, not a silently-dead button; `PanelState` itself stays a generic `string` id (it has
// no reason to know this page's specific ids) per its own doc comment.

/** The tool-rail's frozen ids (docs/plans/done/UI-REDESIGN-PLAN.md D-D; `rc` added by docs/plans/active/RC-CONTROL-PLAN.md
 * Phase 0 — the read-only RC transmitter monitor; `marks` added by docs/plans/done/TACTICAL-MARKS-PLAN.md M5
 * — the shared tactical-marks operational picture). This union's own declaration order is no longer
 * the rail's visual order: docs/conclusions/UX-SIMPLIFY-REVIEW.md F4 groups the rail by job — Control (flight,
 * rc), Vision (cv, detections), Situational (marks), Help (pinned last, separated) — see fly.html's
 * own comment above `.grid-rail` for the full grouping. Every id/gate/behavior below is unchanged;
 * only where each button sits in the rail moved. **`layers` was removed** (per direct user request)
 * — the detection-boxes rendering-mode control it used to hold its own drawer for now lives inside
 * the `cv` (Detection) drawer instead (`cv-control-panel.html`'s own "Boxes rendering" section). */
export type ToolRailPanelId = 'flight' | 'rc' | 'cv' | 'detections' | 'marks' | 'map' | 'help';

/**
 * `Esc`'s own "closest thing open, first" priority (docs/plans/done/UI-REDESIGN-PLAN.md D-D: "Esc calls
 * `panels.close()`") — extracted from `fly.ts#collapseOverlays()` so the cascade order itself (any
 * open tool-rail drawer, then the Stop-stream confirm, then the map inset) is unit-testable without
 * a real `PanelState`/DOM. Mirrors the pre-Wave-2 cascade's own order (shortcuts/CV/detections were
 * already the drawers-in-training even then, just modeled as separate flags) with one change: the
 * map inset moves to *last* rather than sharing the old detections-strip slot, since it's the one
 * overlay this wave deliberately keeps outside `PanelState` (D-D: "the map inset stays a separate
 * persisted toggle … since it is glanceable, not a modal drawer") and is the least "in the way" of
 * the three.
 */
export function nextCollapseAction(state: {
  readonly panelOpen: boolean;
  readonly stopConfirmOpen: boolean;
  readonly mapVisible: boolean;
}): 'panel' | 'stop-confirm' | 'map' | null {
  if (state.panelOpen) {
    return 'panel';
  }
  if (state.stopConfirmOpen) {
    return 'stop-confirm';
  }
  if (state.mapVisible) {
    return 'map';
  }
  return null;
}

/**
 * Whether `candidateAssetId` is the header switcher `<option>` that should carry the native
 * `selected` property (docs/plans/done/UX-QUICKWINS-PLAN.md QF-1, BROKEN #2 — "switcher shows wrong selected
 * drone when the active asset isn't first in option order"). Extracted so the fix is unit-testable
 * without a real `<select>`/`<option>` DOM.
 *
 * **Root cause this replaces**: `fly.html` used to bind `[value]="activeAssetId()"` on the
 * `<select>` itself, with every `<option>` built by the same `@for` that is the select's own
 * content. Angular applies an element's own property bindings before it patches that element's
 * child content, so on first paint the `<select>`'s `value` property was written before any
 * `<option>` existed to match it. Per the HTML spec, setting a `<select>`'s `value` to a string
 * with no matching `<option>` selects nothing, and the browser then falls back to whichever
 * `<option>` is first in *document* order — not the active asset. Because Angular only re-writes a
 * property binding when the bound expression's value actually changes, and `activeAssetId()`
 * hadn't changed since that failed first write, nothing ever corrected it afterwards: the switcher
 * stuck on the first-listed drone (per `sortAssetsForPicker`'s streaming-then-alphabetical order)
 * whenever the active asset wasn't that one.
 *
 * Binding `[selected]` per `<option>` (via this function) instead sidesteps the ordering race
 * entirely: each option applies its own `selected` property the moment *that node* is created or
 * re-checked, never depending on a sibling — least of all the `<select>` itself — existing first.
 */
export function isSwitcherOptionSelected(candidateAssetId: string, activeAssetId: string | undefined): boolean {
  return candidateAssetId === activeAssetId;
}

// --- Picker asset card (docs/plans/done/UX-REWORK-PLAN.md §U-a2 §3 — "the info-less asset card on Fly ...
// becomes an asset card") ------------------------------------------------------------------------
//
// The picker card used to show only a `displayName` and, while streaming, a bare "Streaming" chip
// — nothing else, silently whole-card-clickable (the plan's own screenshot evidence). These three
// pure derivations back the rebuilt card's extra facts. **No new HTTP call backs any of them** —
// every input is a field `AssetSummary` (the picker's existing `listAssets()` response) already
// carries. Battery is deliberately **not** among them: `AssetSummary` has no such field, and the
// per-asset battery reading this app does have (`TelemetryStore`) only exists for the one asset
// currently tracked by the cockpit (component-provided, one instance per route) — showing it on
// every picker card would mean one more `track()`-worth of polling per card, which is exactly the
// new-request fan-out this task was told to avoid. Omitted rather than fabricated, same posture as
// this app's other honest gaps (e.g. `fly-osd.ts`'s missing speed chip).

/** The picker/asset card's stream-state word (U-a2 §1: "stream state with the word", never color-only). */
export function streamStateLabel(status: AssetStatus): 'Streaming' | 'Offline' {
  return status === 'STREAMING' ? 'Streaming' : 'Offline';
}

/**
 * The card's "last seen" fact — elapsed time since `lastUsedAt`, reusing
 * `core/stream-info-logic.ts#formatDuration` (this app's one duration renderer) rather than a
 * second one, e.g. `"4m 07s ago"`. `undefined` when the asset has never been used — the card omits
 * the fact entirely rather than showing a fabricated placeholder. `nowMs` is a parameter (not
 * `Date.now()` read in here) purely so this stays deterministic under test; `FlyPage` supplies the
 * real clock.
 */
export function lastSeenLabel(lastUsedAt: string | undefined, nowMs: number): string | undefined {
  if (!lastUsedAt) {
    return undefined;
  }
  const elapsedSeconds = Math.max(0, (nowMs - Date.parse(lastUsedAt)) / 1000);
  return `${formatDuration(elapsedSeconds)} ago`;
}

/**
 * The card's "position" fact — `lat, lon` to 4 decimal places (~11m precision, plenty for a
 * glance), `undefined` when the asset has never reported a fix. Altitude is deliberately left out
 * here — the card states *where*, not *how high*; altitude already has its own home in the cockpit
 * OSD once actually flying (`fly-osd.ts`).
 */
export function positionLabel(position: GeoPosition | undefined): string | undefined {
  if (!position) {
    return undefined;
  }
  return `${position.latitude.toFixed(4)}, ${position.longitude.toFixed(4)}`;
}

// --- Header switcher / "All drones" merge (docs/plans/done/UX-REWORK-PLAN.md §U-a bullet 4 — "Merge 'All
// drones' + drone <select> into one switcher control") -------------------------------------------

/**
 * Sentinel `<option>` value for "exit to the full picker", folded into the header's own drone
 * switcher `<select>` as its last entry instead of a separate "All drones" button sitting next to
 * it (the redundancy the plan names). No real asset id can ever equal this string — asset ids are
 * `AssetId.random()`-shaped UUIDs, never this literal — so it can share the same `(change)` handler
 * unambiguously with every genuine asset option.
 */
export const ALL_DRONES_OPTION_VALUE = '__all-drones__';

/** Whether the switcher's `(change)` value is the "All drones" sentinel rather than a real asset id. */
export function isAllDronesOption(value: string): boolean {
  return value === ALL_DRONES_OPTION_VALUE;
}
