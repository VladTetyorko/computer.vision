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
 * alongside the rules themselves (no test content left behind here — nothing here is this file's
 * own any more).
 *
 * Deliberately does **not** touch `fly-logic.ts#sortAssetsForPicker` — that function also backs
 * `cockpit-facade.ts#orderedSwitcherAssets` (the cockpit header's own drone switcher `<select>`),
 * which stays a flat streaming-then-alphabetical list outside this wave's scope. `groupAndSort` is
 * this page's own, separate sort with its own contract (grouped, `lastSeen`-aware).
 */
export {
  SIMULATED_CATEGORY,
  isSimulated,
  groupAndSort,
  offlineLabel,
  type PickerGroups,
} from '../../core/fleet/triage-logic';
