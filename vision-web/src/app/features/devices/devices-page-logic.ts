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

// --- Category picker (docs/UX-QUICKWINS-PLAN.md QF-2's "Create asset from this device") --------
// This cycle's `vision-api.ts` change is scoped to `createAsset` only (see the plan) — no
// `listCategories()`/`GET /api/categories` call site exists yet, even though that endpoint already
// exists server-side (`CategoryController`). Rather than hand-roll a second, out-of-scope API
// method, the picker derives its options from categories already present among the assets this
// page has already loaded (real, in-use categories, no extra round trip) and only falls back to a
// small hardcoded set — mirroring `InMemoryCategoryRepository`'s own dev/Phase-0 seed
// (vision-app/devsupport) — for a brand-new install with no asset to derive from yet. A future
// cycle wiring `VisionApi.listCategories()` should point this at the live list instead, keeping the
// "derive from loaded assets" fast path.

/** One category the "Create asset" picker can offer: enough to render and to send back as `category`. */
export interface CategoryOption {
  readonly slug: string;
  readonly name: string;
}

/** Fallback options for an install with no asset yet to derive real categories from. */
export const DEFAULT_CATEGORY_OPTIONS: readonly CategoryOption[] = [
  { slug: 'drone', name: 'Drone' },
  { slug: 'ip-camera', name: 'IP Camera' },
  { slug: 'usb-camera', name: 'USB Camera' },
  { slug: 'robot', name: 'Robot' },
  { slug: 'simulated', name: 'Simulated' },
];

/**
 * The categories already in use among `assets`, deduped by slug and sorted by name — the "existing
 * categories" the create-asset picker offers. Falls back to {@link DEFAULT_CATEGORY_OPTIONS} only
 * when `assets` is empty (nothing yet to derive a real list from).
 */
export function deriveCategoryOptions(assets: readonly AssetSummary[]): readonly CategoryOption[] {
  const bySlug = new Map<string, CategoryOption>();
  for (const asset of assets) {
    if (!bySlug.has(asset.category)) {
      bySlug.set(asset.category, { slug: asset.category, name: asset.categoryName });
    }
  }
  if (bySlug.size === 0) {
    return DEFAULT_CATEGORY_OPTIONS;
  }
  return [...bySlug.values()].sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Builds the `POST /api/assets` body for "Create asset from this device" (docs/UX-QUICKWINS-PLAN.md
 * QF-2's orphaned-device quick fix): wraps `device`'s own connection details (name/protocol/uri/
 * options/capabilities) as the new asset's one device.
 *
 * **This registers a brand-new `Device`, not a reference to `device` itself** — `CreateAssetRequest`
 * carries no "existing device id" field (verified against `AssetController#create`/
 * `CreateAssetRequest.java`/`AssetSpec.java`: every entry in `devices` always goes through
 * `deviceService.register(...)`, unconditionally). The caller is expected to archive the original
 * `device` (e.g. `FleetStore#deleteDevice`) once this succeeds, so the orphan doesn't linger
 * side-by-side with its own now-owned duplicate — `devices.ts#confirmCreateAsset` does exactly
 * that, and the confirm panel's own copy tells the user this up front.
 */
export function buildCreateAssetRequestForDevice(
  device: Device,
  displayName: string,
  category: string,
): CreateAssetRequest {
  const trimmedName = displayName.trim();
  const hasOptions = Object.keys(device.options).length > 0;
  return {
    displayName: trimmedName.length > 0 ? trimmedName : device.name,
    category,
    devices: [
      {
        name: device.name,
        protocol: device.protocol,
        uri: device.uri,
        ...(hasOptions ? { options: device.options } : {}),
        capabilities: device.capabilities,
      },
    ],
  };
}
