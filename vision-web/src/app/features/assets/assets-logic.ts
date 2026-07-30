import type { AssetDetails, AssetSummary, LifecycleState } from '../../core/api/models';
import { findVideoDevice } from '../../core/fleet/device-logic';

/**
 * Pure logic behind the Assets page (this cycle's Assets/Devices/Warehouse inventory restructure —
 * no dedicated `docs/*-PLAN.md`, see `vision-web/MODULE.md`'s own changelog entry for the full
 * writeup: Assets and Devices became separate pages, Warehouse became a two-tile launcher): the
 * asset → view-model builder plus every search/filter this page's grid needs. Moved wholesale out of
 * `features/devices/devices-page-logic.ts` (where it lived as the "asset-first primary list"'s own
 * logic, docs/CYCLES-PLAN.md §11 CD-b) once the Devices page stopped rendering assets at all — this
 * codebase's own precedent (`core/fleet/device-logic.ts`'s doc comment) is that cross-page logic
 * moves to `core/`, but here there is exactly **one** consumer left (this page), so it stays local,
 * unit-tested, Angular-free, mirroring `features/devices/devices-page-logic.ts`'s own split.
 */

/**
 * One row of the Assets grid: an asset plus exactly what the card-level "Watch · Open · Archive"
 * actions need — `watchDeviceId` is the device `Watch` navigates to (`undefined` when the asset has
 * no VIDEO-capable device at all, in which case the card hides its Watch button), `deviceCount`/
 * `streaming` are the card's status decoration. Built from `AssetDetails` (not just `AssetSummary`)
 * because `deviceCount` needs the resolved device list.
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

/** Hides archived rows unless the "show archived" toggle is on — the client-side half of it. */
export function filterAssetListRowsByArchived(
  rows: readonly AssetListRow[],
  showArchived: boolean,
): readonly AssetListRow[] {
  return showArchived ? rows : rows.filter((row) => !row.archived);
}

/**
 * `?category=<slug>` filter (docs/UX-QUICKWINS-PLAN.md QF-2/QF-3 — the Command dashboard's
 * readiness-tile drill-down target; also the Assets page's own category `<select>`). Blank/absent
 * leaves every row; an unknown slug legitimately narrows to zero rows rather than falling back to
 * "show everything", since a caller linking here already knows the category exists.
 */
export function filterAssetListRowsByCategory(
  rows: readonly AssetListRow[],
  category: string | undefined,
): readonly AssetListRow[] {
  const slug = category?.trim();
  return slug ? rows.filter((row) => row.asset.category === slug) : rows;
}

/** The Assets page's lifecycle filter — deliberately only `active`/`deactivated`: `DELETED` rows
 *  are gated by the separate "show archived" toggle, not this control, so `'all'` here still means
 *  "every non-archived asset currently visible", not "every lifecycle state including archived". */
export type AssetStatusFilter = 'all' | 'active' | 'deactivated';

export function filterAssetListRowsByStatus(
  rows: readonly AssetListRow[],
  status: AssetStatusFilter,
): readonly AssetListRow[] {
  if (status === 'all') {
    return rows;
  }
  const target: LifecycleState = status === 'active' ? 'ACTIVE' : 'DEACTIVATED';
  return rows.filter((row) => row.lifecycle === target);
}

/** The Assets page's streaming filter — reads the same `row.streaming` the card's Live/Offline chip does. */
export type AssetStreamingFilter = 'all' | 'streaming' | 'offline';

export function filterAssetListRowsByStreaming(
  rows: readonly AssetListRow[],
  streaming: AssetStreamingFilter,
): readonly AssetListRow[] {
  if (streaming === 'all') {
    return rows;
  }
  return rows.filter((row) => (streaming === 'streaming' ? row.streaming : !row.streaming));
}

/** Case-insensitive substring match on the asset's display name — the Assets page's search box. */
export function searchAssetListRowsByName(
  rows: readonly AssetListRow[],
  query: string,
): readonly AssetListRow[] {
  const q = query.trim().toLowerCase();
  return q ? rows.filter((row) => row.asset.displayName.toLowerCase().includes(q)) : rows;
}
