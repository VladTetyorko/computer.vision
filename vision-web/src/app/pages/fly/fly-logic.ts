import type { AssetSummary, AssetUsage } from '../../core/api/models';
import type { BoxesMode } from '../../ui/player';

/**
 * Pure, Angular-free logic behind `FlyPage` (docs/MVP3-PLAN.md §C-b) — split out so picker
 * ordering, remembered/requested-asset resolution, the "Replay last flight" link, watch-mode
 * parsing, and the keyboard boxes-cycle are unit-testable without HTTP, the router, or `document`,
 * mirroring every other page's own `*-logic.ts` split (`core/map-logic.ts`,
 * `pages/replay/replay-logic.ts`, etc.).
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
 * `recentUsages`, which is already newest-first (mirrors `pages/asset-detail/asset-detail.html`'s
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
