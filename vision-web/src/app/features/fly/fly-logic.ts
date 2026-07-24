import type { AssetStatus, AssetSummary, AssetUsage, GeoPosition } from '../../core/api/models';
import { formatDuration } from '../../core/stream-info-logic';
import type { BoxesMode } from '../../shared/player/player';

/**
 * Pure, Angular-free logic behind `FlyPage` (docs/MVP3-PLAN.md §C-b) — split out so picker
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
 * Which asset id the cockpit should open straight into, if any: an explicit `?asset=` query param
 * (a future drill-down target — e.g. Command's own "watch this one" links, C-c) wins over the
 * remembered choice from `SettingsStore.flyAssetId`. Either is only honored when that asset is
 * still present in the freshly-fetched fleet — an archived/deleted remembered id falls back to
 * `undefined` (the picker), never a broken cockpit pointed at nothing.
 */
export function resolveActiveAssetId(
  assets: readonly AssetSummary[],
  requestedAssetId: string | undefined,
  rememberedAssetId: string | null,
): string | undefined {
  const candidate = requestedAssetId || rememberedAssetId || undefined;
  if (candidate === undefined) {
    return undefined;
  }
  return assets.some((asset) => asset.assetId === candidate) ? candidate : undefined;
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
 * `?watch=1` exactly (docs/MVP3-PLAN.md §C-b — "Watch mode `?watch=1`: hides Start/Stop"). Only
 * this literal value counts, not any other truthy-looking string — a deliberate, narrow contract
 * for a query param a future cycle (C-c) constructs itself, not one a person is expected to type.
 */
export function isWatchMode(param: string | undefined): boolean {
  return param === '1';
}

/** `B` cycles the same three states the mouse buttons already offer, in the same left-to-right order. */
const BOXES_CYCLE: readonly BoxesMode[] = ['overlay', 'burned', 'off'];

export function cycleBoxesMode(current: BoxesMode): BoxesMode {
  const index = BOXES_CYCLE.indexOf(current);
  return BOXES_CYCLE[(index + 1) % BOXES_CYCLE.length];
}

/** How many rows the events ticker overlay shows at once — glanceable, not a full feed (see the Wall rail for that). */
export const TICKER_MAX_EVENTS = 4;

/**
 * Whether `candidateAssetId` is the header switcher `<option>` that should carry the native
 * `selected` property (docs/UX-QUICKWINS-PLAN.md QF-1, BROKEN #2 — "switcher shows wrong selected
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

/**
 * Whether a tracking effect (docs/REALTIME-PLAN.md Phase R-a item 2) should re-enter its store's
 * `track()`/`reset()` this run: only when the derived id primitive actually changed from the id it
 * last acted on. `FlyPage`'s own `asset()`/`stream()` signals are fresh objects on every ~5s poll
 * tick even when nothing about the tracked device/stream changed (signals compare with
 * `Object.is`), so an effect reading them re-fires on that cadence regardless — without this check,
 * re-entering `telemetry.track()`/`detections.track()` with an *unchanged* id was the diagnosed O(N)
 * amplification bug and the detections-strip flicker (docs/REALTIME-PLAN.md §0). Mirrors
 * `core/map/map-store.ts#reconcileTrackers`'s own reconcile-by-id idiom: compare the id *value*, never
 * the enclosing object's identity.
 */
export function trackingIdChanged(nextId: string | undefined, lastActedOnId: string | undefined): boolean {
  return nextId !== lastActedOnId;
}

// --- Picker asset card (docs/UX-REWORK-PLAN.md §U-a2 §3 — "the info-less asset card on Fly ...
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

// --- Header switcher / "All drones" merge (docs/UX-REWORK-PLAN.md §U-a bullet 4 — "Merge 'All
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
