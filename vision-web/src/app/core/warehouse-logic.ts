import type { AssetSummary, Device, DeviceEdit, AssetEdit, LifecycleState, SettableLifecycleState } from './api/models';

/**
 * Pure, Angular-free device/asset lifecycle logic (docs/CYCLES-PLAN.md §8), shared across pages.
 *
 * Started life in `pages/devices/warehouse-logic.ts` (CW-b, the warehouse UI). CD-b's asset detail
 * page (`pages/asset-detail/asset-detail.ts`) needs the exact same lifecycle-action-menu state
 * machine and edit-request builders for its "Hardware" section and its own asset header — and
 * this codebase has no precedent for one page importing another page's module (every cross-page
 * dependency runs through `core/`, see `core/device-logic.ts`'s doc comment for the original
 * precedent) — so the generic pieces were lifted here. `pages/devices/warehouse-logic.ts` keeps
 * the Devices-page-specific view models (`WarehouseRow`/`AssetRow`/`AssetListRow` and their
 * builders/filters, `mapDeviceOwners`) that only that page's table/list rendering needs.
 */

/**
 * `DEACTIVATED` on an already-`DELETED` thing is how the pinned contract spells "restore" — there
 * is no direct `DELETED` → `ACTIVE` transition (`POST .../state` with `state: "ACTIVE"` on a
 * deleted device/asset is a 409). Every "Restore" action in the UI sends this target state.
 */
export const RESTORE_TARGET_STATE: SettableLifecycleState = 'DEACTIVATED';

/** Actions a device's row/panel action menu can offer. */
export type DeviceLifecycleAction =
  | 'rename'
  | 'activate'
  | 'deactivate'
  | 'archive'
  | 'restore'
  | 'assign'
  | 'unassign';

/** Actions an asset's action menu can offer. */
export type AssetLifecycleAction = 'rename' | 'activate' | 'deactivate' | 'archive' | 'restore';

/**
 * Which actions a device offers for a given lifecycle state, further narrowed by whether an asset
 * currently owns it. Mirrors the literal per-state matrix docs/CYCLES-PLAN.md §8 pins: `ACTIVE`
 * gets the assign/unassign slot (resolved by ownership — assigning an already-owned device makes
 * no sense, nor does unassigning one nobody owns); `DEACTIVATED` does not carry that slot at all
 * (reactivate first); `DELETED` offers only `restore`.
 */
export function availableDeviceActions(
  state: LifecycleState,
  owned: boolean,
): readonly DeviceLifecycleAction[] {
  switch (state) {
    case 'ACTIVE':
      return ['rename', 'deactivate', 'archive', owned ? 'unassign' : 'assign'];
    case 'DEACTIVATED':
      return ['rename', 'activate', 'archive'];
    case 'DELETED':
      return ['restore'];
  }
}

/**
 * Which actions an asset offers for a given lifecycle state — the same three-state shape as
 * devices, minus the assign/unassign slot (that lives on the device side, not the asset itself).
 */
export function availableAssetActions(state: LifecycleState): readonly AssetLifecycleAction[] {
  switch (state) {
    case 'ACTIVE':
      return ['rename', 'deactivate', 'archive'];
    case 'DEACTIVATED':
      return ['rename', 'activate', 'archive'];
    case 'DELETED':
      return ['restore'];
  }
}

/**
 * Builds `PATCH /api/devices/{id}`'s body for the Rename action — omits `name` entirely (rather
 * than sending a `null`/unchanged value) when the trimmed input is blank or equal to what the
 * device already has, so a no-op rename never hits the network.
 */
export function buildDeviceRenameEdit(name: string, original: Pick<Device, 'name'>): DeviceEdit {
  const trimmed = name.trim();
  return trimmed.length > 0 && trimmed !== original.name ? { name: trimmed } : {};
}

/** Form state for the asset rename/re-category card. */
export interface AssetEditForm {
  readonly displayName: string;
  readonly category: string;
}

/**
 * Builds `PATCH /api/assets/{id}`'s body — each of `displayName`/`category` is included only when
 * trimmed and different from the asset's current value; an untouched or blanked-out field is
 * omitted rather than sent as `null` or an empty string, the same `@JsonInclude(NON_NULL)`
 * convention every request builder in this app follows.
 */
export function buildAssetEdit(
  form: AssetEditForm,
  original: Pick<AssetSummary, 'displayName' | 'category'>,
): AssetEdit {
  const edit: { displayName?: string; category?: string } = {};

  const displayName = form.displayName.trim();
  if (displayName.length > 0 && displayName !== original.displayName) {
    edit.displayName = displayName;
  }

  const category = form.category.trim();
  if (category.length > 0 && category !== original.category) {
    edit.category = category;
  }

  return edit;
}
