/**
 * Pure, Angular-free logic behind `DronePickerPage` (docs/plans/active/OPERATOR-UX-3-PLAN.md finding T1,
 * §2 T1, wave T1) — grouping the `/fly` picker into "your vehicles" vs. "simulated", sorting each
 * group so the operator's eye lands on what's actually flyable, and labelling an offline card's age
 * honestly instead of the bare word "Offline".
 *
 * **Moved to `core/fleet/triage-logic.ts` in docs/plans/active/OPERATOR-UX-4-PLAN.md finding N3, §2 N3, wave
 * W2** — the Command dashboard's entity rail needed the identical grouping/sort/simulated-detection/
 * honest-age rules this file originally built for `/fly` (see that module's own doc comment for the
 * full "why `core/`" account, mirroring `core/fleet/device-logic.ts`'s original precedent). Every
 * name below is re-exported so this file's own pre-existing import sites (`drone-picker.ts`,
 * `drone-picker-card.ts`, `drone-picker-facade.ts`) keep working verbatim — `/fly`'s own behavior is
 * unchanged byte-for-byte, it never threads the new `attentionRank` parameter `groupAndSort` gained
 * for Command. `drone-picker-logic.spec.ts`'s cases moved to `core/fleet/triage-logic.spec.ts`
 * alongside the rules themselves.
 *
 * Deliberately does **not** touch `fly-logic.ts#sortAssetsForPicker` — that function also backs
 * `cockpit-facade.ts#orderedSwitcherAssets` (the cockpit header's own drone switcher `<select>`),
 * which stays a flat streaming-then-alphabetical list outside this wave's scope. `groupAndSort` is
 * this page's own, separate sort with its own contract (grouped, `lastSeen`-aware).
 *
 * **Gained its own logic again in B4 (docs/plans/active/ASSET-FLOWS-PLAN.md §3 WB2)**:
 * {@link myAssignedAssets} below is genuinely new to this file, not re-exported from
 * `triage-logic.ts` — it's a `/fly`-picker-specific "My assigned" grouping layered on top of the
 * shared `groupAndSort` split, not a rule Command's own rail needs (Command has no per-pilot
 * assignment concept in its rail today), so it stays local rather than joining the re-export list
 * above. This file's own `drone-picker-logic.spec.ts` carries this function's tests.
 */
export {
  SIMULATED_CATEGORY,
  isSimulated,
  groupAndSort,
  offlineLabel,
  type PickerGroups,
} from '../../core/fleet/triage-logic';

/**
 * Which of `assets` the acting pilot is personally assigned to (B4, docs/plans/active/
 * ASSET-FLOWS-PLAN.md §3 WB2) — `GET /api/me/assignments`'s own row set, intersected against
 * `groups().yours` so a "My assigned" section can sit above the picker's existing "Your vehicles"
 * group as a shortcut, not a replacement: `drone-picker.html`'s own "Your vehicles" section stays
 * completely untouched by this function, still listing every real asset (assigned or not) exactly as
 * it did before B4 — this only adds an extra section above it. Preserves `assets`' own order
 * (already freshness-sorted by `groupAndSort`).
 *
 * An empty `assignedAssetIds` — no assignments yet, or `DronePickerFacade`'s own `GET
 * /api/me/assignments` read failed (see that facade's doc comment) — returns `[]`, which the
 * template reads as "don't show this section at all": the same flat, ungrouped-by-assignment picker
 * this page rendered before B4, never a blocked page or a fabricated grouping.
 */
export function myAssignedAssets<T extends { assetId: string }>(
  assets: readonly T[],
  assignedAssetIds: ReadonlySet<string>,
): readonly T[] {
  if (assignedAssetIds.size === 0) {
    return [];
  }
  return assets.filter((asset) => assignedAssetIds.has(asset.assetId));
}
