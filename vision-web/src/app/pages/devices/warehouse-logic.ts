import type {
  AssetDetails,
  AssetEdit,
  AssetSummary,
  Device,
  DeviceEdit,
  LifecycleState,
  SettableLifecycleState,
} from '../../core/api/models';

/**
 * Pure logic behind the Devices page's warehouse view (docs/CYCLES-PLAN.md §8): the
 * lifecycle-action state machine for a device/asset row, partial-edit request builders, and the
 * device+asset → warehouse-row view model. Split out so it is unit-testable without HTTP or the
 * router — mirrors `pages/devices/simulate-logic.ts` and `core/telemetry-logic.ts`.
 *
 * The pinned REST contract (docs/CYCLES-PLAN.md §8) is coded against verbatim even though CW-a
 * (the backend half) is not live while this lands: every mutation this logic feeds goes through
 * `FleetStore`'s `run()` funnel, so a 404 today degrades to one toast, never a crash.
 */

/**
 * `DEACTIVATED` on an already-`DELETED` thing is how the pinned contract spells "restore" — there
 * is no direct `DELETED` → `ACTIVE` transition (`POST .../state` with `state: "ACTIVE"` on a
 * deleted device/asset is a 409). Every "Restore" action in the UI sends this target state.
 */
export const RESTORE_TARGET_STATE: SettableLifecycleState = 'DEACTIVATED';

/** Actions a device's row action menu can offer. */
export type DeviceLifecycleAction =
  | 'rename'
  | 'activate'
  | 'deactivate'
  | 'archive'
  | 'restore'
  | 'assign'
  | 'unassign';

/** Actions an asset card's action menu can offer. */
export type AssetLifecycleAction = 'rename' | 'activate' | 'deactivate' | 'archive' | 'restore';

/**
 * Which actions a device's row offers for a given lifecycle state, further narrowed by whether an
 * asset currently owns it. Mirrors the literal per-state matrix docs/CYCLES-PLAN.md §8 pins:
 * `ACTIVE` gets the assign/unassign slot (resolved by ownership — assigning an already-owned
 * device makes no sense, nor does unassigning one nobody owns); `DEACTIVATED` does not carry that
 * slot at all (reactivate first); `DELETED` offers only `restore`.
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
 * Which actions an asset card offers for a given lifecycle state — the same three-state shape as
 * devices, minus the assign/unassign slot (that lives on the device row, not the asset card).
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

/** Enough about a device's owning asset to render an "owned by" column and an unassign action. */
export interface DeviceOwner {
  readonly assetId: string;
  readonly assetName: string;
}

/**
 * deviceId → owning asset, derived from already-fetched `AssetDetails`.
 *
 * Generalizes `pages/devices/simulate-logic.ts#mapSimulatedDevices` (which narrows to just the
 * `simulated` category, for the wizard's own chip) to every asset the warehouse page loads —
 * ownership isn't carried on `Device` itself, so this cross-reference is the only way to know
 * which asset (if any) a given device belongs to.
 */
export function mapDeviceOwners(assets: readonly AssetDetails[]): ReadonlyMap<string, DeviceOwner> {
  const owners = new Map<string, DeviceOwner>();
  for (const asset of assets) {
    for (const device of asset.devices) {
      owners.set(device.id, { assetId: asset.assetId, assetName: asset.displayName });
    }
  }
  return owners;
}

/** One row of the warehouse device table: a device plus everything its row needs to render. */
export interface WarehouseRow {
  readonly device: Device;
  readonly lifecycle: LifecycleState;
  readonly owner?: DeviceOwner;
  readonly streaming: boolean;
  readonly archived: boolean;
}

/**
 * Devices + who owns them + who's currently streaming, combined into one row per device — the
 * warehouse table's view model.
 */
export function buildWarehouseRows(
  devices: readonly Device[],
  owners: ReadonlyMap<string, DeviceOwner>,
  liveDeviceIds: ReadonlySet<string>,
): readonly WarehouseRow[] {
  return devices.map((device) => ({
    device,
    lifecycle: device.state,
    owner: owners.get(device.id),
    streaming: liveDeviceIds.has(device.id),
    archived: device.state === 'DELETED',
  }));
}

/** Hides archived rows unless the "show archived" toggle is on — the client-side half of it. */
export function filterRowsByArchived(
  rows: readonly WarehouseRow[],
  showArchived: boolean,
): readonly WarehouseRow[] {
  return showArchived ? rows : rows.filter((row) => !row.archived);
}

/** One card in the asset section: an asset plus its resolved (possibly absent) lifecycle. */
export interface AssetRow {
  readonly asset: AssetSummary;
  readonly lifecycle: LifecycleState;
  readonly archived: boolean;
}

/**
 * Asset summaries → asset rows, defaulting a missing `lifecycle` (a backend that hasn't shipped
 * CW-a yet) to `'ACTIVE'` — the same fallback every other reader of `AssetSummary#lifecycle`
 * uses, so an asset never appears archived just because the field is absent.
 */
export function buildAssetRows(assets: readonly AssetSummary[]): readonly AssetRow[] {
  return assets.map((asset) => {
    const lifecycle = asset.lifecycle ?? 'ACTIVE';
    return { asset, lifecycle, archived: lifecycle === 'DELETED' };
  });
}

/** Hides archived asset cards unless the "show archived" toggle is on. */
export function filterAssetRowsByArchived(
  rows: readonly AssetRow[],
  showArchived: boolean,
): readonly AssetRow[] {
  return showArchived ? rows : rows.filter((row) => !row.archived);
}
