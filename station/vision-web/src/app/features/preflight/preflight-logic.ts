import type { AssetSummary } from '../../core/api/models';

/**
 * Pure, Angular-free logic behind `PreflightPage` (`/operate/preflight`, docs/plans/done/UI-REDESIGN-PLAN.md
 * Wave 4 — **SPLIT**: the live status card is functional, reusing `core/telemetry/flight-state-logic.ts#derivePreflight`
 * and `features/fly/preflight-checklist.ts` unmodified; saved, editable checklist templates are not
 * built — no checklist-template entity/endpoint exists).
 */

/** Alphabetical by display name, case-insensitive — the drone picker's own order. */
export function sortAssetsByName(assets: readonly AssetSummary[]): readonly AssetSummary[] {
  return [...assets].sort((a, b) => a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' }));
}

/**
 * The drone to preselect: `rememberedAssetId` (`SettingsStore.flyAssetId`, the cockpit's own
 * remembered drone — read-only here, this page never writes it back) when it's still in the fleet,
 * else the first asset alphabetically, else `undefined` for an empty fleet. Mirrors
 * `SettingsStore.flyAssetId`'s own doc comment: "a stale id for an archived/deleted asset falls back
 * to the picker" — this function is that same fallback rule, just for this page's own picker instead
 * of the cockpit's.
 */
export function defaultPreflightAssetId(
  assets: readonly AssetSummary[],
  rememberedAssetId: string | null,
): string | undefined {
  const sorted = sortAssetsByName(assets);
  if (rememberedAssetId && sorted.some((asset) => asset.assetId === rememberedAssetId)) {
    return rememberedAssetId;
  }
  return sorted[0]?.assetId;
}
