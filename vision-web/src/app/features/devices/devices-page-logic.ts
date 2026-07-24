import type {
  AssetDetails,
  AssetSummary,
  CreateAssetRequest,
  Device,
  LifecycleState,
} from '../../core/api/models';
import { findVideoDevice } from '../../core/fleet/device-logic';

/**
 * Pure logic behind the Devices page's warehouse view (docs/CYCLES-PLAN.md §8, §11): the
 * device+asset → view-model builders this page's table/list rendering needs. Split out so it is
 * unit-testable without HTTP or the router — mirrors `features/devices/simulate-logic.ts` and
 * `core/telemetry/telemetry-logic.ts`.
 *
 * The generic lifecycle-action-menu state machine and edit-request builders
 * (`availableDeviceActions`/`availableAssetActions`/`buildDeviceRenameEdit`/`buildAssetEdit`/
 * `RESTORE_TARGET_STATE`) moved to `core/fleet/warehouse-logic.ts` in docs/CYCLES-PLAN.md §11 (CD-b) —
 * the new asset detail page needs them too, and this codebase has no precedent for one page
 * importing another page's module (see `core/fleet/device-logic.ts`'s doc comment). Re-exported here so
 * every import site this page already had keeps working verbatim.
 */
export {
  ASSET_ACTION_LABELS,
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  availableAssetActions,
  availableDeviceActions,
  buildAssetEdit,
  buildDeviceRenameEdit,
  operatorAssetActions,
  reasonedAssetActions,
  reasonedDeviceActions,
  type ActionAvailability,
  type AssetEditForm,
  type AssetLifecycleAction,
  type DeviceLifecycleAction,
} from '../../core/fleet/warehouse-logic';

/**
 * The "existing categories + fallback list" category picker moved to `core/fleet/category-logic.ts`
 * (docs/UX-REWORK-PLAN.md §U-d) once the onboarding wizard's Profile step needed the identical
 * picker — same cross-feature-module rule as the re-export block above. Re-exported here so this
 * page's own pre-existing import site keeps working verbatim.
 */
export { DEFAULT_CATEGORY_OPTIONS, deriveCategoryOptions, type CategoryOption } from '../../core/fleet/category-logic';

/**
 * The pinned REST contract (docs/CYCLES-PLAN.md §8) is coded against verbatim even though CW-a
 * (the backend half) is not live while this lands: every mutation this logic feeds goes through
 * `FleetStore`'s `run()` funnel, so a 404 today degrades to one toast, never a crash.
 */

/**
 * Enough about a device's owning asset to render an "owned by" column, an unassign action, and
 * (docs/UX-REWORK-PLAN.md §U-a2 item 3a) whether unassigning it would leave that asset with none —
 * `deviceCount` is what lets `reasonedDeviceActions`'s `ownerDeviceCount` parameter disable
 * Unassign *before* the click instead of only after the backend's own 409.
 */
export interface DeviceOwner {
  readonly assetId: string;
  readonly assetName: string;
  readonly deviceCount: number;
}

/**
 * deviceId → owning asset, derived from already-fetched `AssetDetails`.
 *
 * Generalizes `features/devices/simulate-logic.ts#mapSimulatedDevices` (which narrows to just the
 * `simulated` category, for the wizard's own chip) to every asset the warehouse page loads —
 * ownership isn't carried on `Device` itself, so this cross-reference is the only way to know
 * which asset (if any) a given device belongs to.
 */
export function mapDeviceOwners(assets: readonly AssetDetails[]): ReadonlyMap<string, DeviceOwner> {
  const owners = new Map<string, DeviceOwner>();
  for (const asset of assets) {
    for (const device of asset.devices) {
      owners.set(device.id, {
        assetId: asset.assetId,
        assetName: asset.displayName,
        deviceCount: asset.devices.length,
      });
    }
  }
  return owners;
}

/** One row of the Advanced/raw-devices table: a device plus everything its row needs to render. */
export interface WarehouseRow {
  readonly device: Device;
  readonly lifecycle: LifecycleState;
  readonly owner?: DeviceOwner;
  readonly streaming: boolean;
  readonly archived: boolean;
}

/**
 * Devices + who owns them + who's currently streaming, combined into one row per device — the
 * Advanced/raw-devices table's view model (docs/CYCLES-PLAN.md §11 demoted this from the page's
 * primary surface to a collapsed "Advanced" area; the row shape itself is unchanged from CW-b).
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

/** One card in the (pre-CD-b) asset section: an asset plus its resolved (possibly absent) lifecycle. */
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

/**
 * One row of the Devices page's *primary* asset list (docs/CYCLES-PLAN.md §11, CD-b item 1): an
 * asset plus exactly what the list-level "Watch · Open · Archive" actions need — `watchDeviceId`
 * is the device `Watch` navigates to (`undefined` when the asset has no VIDEO-capable device at
 * all, in which case the list hides the Watch button), `deviceCount`/`streaming` are the row's
 * status decoration. Built from `AssetDetails` (not just `AssetSummary`) because `deviceCount`
 * needs the resolved device list — the page already fetches `AssetDetails` for every asset for the
 * Advanced table's "owned by" column, so this reuses that same fetch rather than a second one.
 */
export interface AssetListRow {
  readonly asset: AssetSummary;
  readonly lifecycle: LifecycleState;
  readonly archived: boolean;
  readonly deviceCount: number;
  readonly streaming: boolean;
  readonly watchDeviceId?: string;
}

export function buildAssetListRows(
  assets: readonly AssetDetails[],
  liveDeviceIds: ReadonlySet<string>,
): readonly AssetListRow[] {
  return assets.map((asset) => {
    const lifecycle = asset.lifecycle ?? 'ACTIVE';
    return {
      asset,
      lifecycle,
      archived: lifecycle === 'DELETED',
      deviceCount: asset.devices.length,
      streaming: asset.devices.some((device) => liveDeviceIds.has(device.id)),
      watchDeviceId: findVideoDevice(asset.devices)?.id,
    };
  });
}

/** Hides archived asset rows unless the "show archived" toggle is on — mirrors `filterAssetRowsByArchived`. */
export function filterAssetListRowsByArchived(
  rows: readonly AssetListRow[],
  showArchived: boolean,
): readonly AssetListRow[] {
  return showArchived ? rows : rows.filter((row) => !row.archived);
}

/**
 * `/devices?category=<slug>` pre-filter (docs/UX-QUICKWINS-PLAN.md QF-2/QF-3) — the drill-down
 * target for the Command dashboard's readiness tiles ("this category's assets", not the whole
 * fleet). Blank/absent leaves every row; an unknown slug legitimately narrows to zero rows rather
 * than falling back to "show everything", since a tile linking here already knows the category
 * exists.
 */
export function filterAssetListRowsByCategory(
  rows: readonly AssetListRow[],
  category: string | undefined,
): readonly AssetListRow[] {
  const slug = category?.trim();
  return slug ? rows.filter((row) => row.asset.category === slug) : rows;
}

/**
 * Builds the `POST /api/assets` body for "Promote to asset…" (docs/UX-QUICKWINS-PLAN.md QF-2's
 * orphaned-device quick fix, renamed to its outcome per docs/UX-REWORK-PLAN.md §U-a2 §3): assigns
 * `device` — the existing, already-registered device, by id — to the new asset via `deviceIds`.
 *
 * **No new `Device` row, no archive step** (docs/REALTIME-PLAN.md §4's backend follow-up batch,
 * `CreateAssetRequest#deviceIds`): this used to be impossible — `CreateAssetRequest` had no
 * "existing device id" field, so `devices.ts#confirmCreateAsset` registered a brand-new device
 * wrapping `device`'s own connection details, then archived `device` itself, so the orphan didn't
 * linger side-by-side with its own now-owned duplicate. `deviceIds` closes that gap directly:
 * `device.id` is assigned to the new asset in the same call the asset is created with, keeping its
 * own id/history/connection details exactly as they were — see `CreateAssetRequest`'s own doc
 * comment in `models.ts` for the validation this goes through (must exist, not soft-deleted, not
 * already owned).
 */
export function buildCreateAssetRequestForDevice(
  device: Device,
  displayName: string,
  category: string,
): CreateAssetRequest {
  const trimmedName = displayName.trim();
  return {
    displayName: trimmedName.length > 0 ? trimmedName : device.name,
    category,
    deviceIds: [device.id],
  };
}
