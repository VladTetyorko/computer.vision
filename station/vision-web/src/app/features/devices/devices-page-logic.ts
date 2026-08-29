import type { AssetDetails, CreateAssetRequest, Device, LifecycleState } from '../../core/api/models';

/**
 * Pure logic behind the Devices page's raw-devices table/grid (docs/main/CYCLES-PLAN.md §8, §11; the
 * asset-first list this file used to also back moved wholesale to `features/assets/assets-logic.ts`
 * once Assets and Devices became separate pages — see that file's own doc comment). Split out so it
 * is unit-testable without HTTP or the router — mirrors `features/devices/simulate-logic.ts` and
 * `core/telemetry/telemetry-logic.ts`.
 *
 * The generic lifecycle-action-menu state machine and edit-request builders
 * (`availableDeviceActions`/`buildDeviceRenameEdit`/`RESTORE_TARGET_STATE`) moved to
 * `core/fleet/warehouse-logic.ts` in docs/main/CYCLES-PLAN.md §11 (CD-b) — the asset detail page needs
 * them too, and this codebase has no precedent for one page importing another page's module (see
 * `core/fleet/device-logic.ts`'s doc comment). Re-exported here so every import site this page
 * already had keeps working verbatim; the asset-level exports (`ASSET_ACTION_LABELS`/
 * `buildAssetEdit`/`operatorAssetActions`/`reasonedAssetActions`/`availableAssetActions`/
 * `AssetLifecycleAction`) are **not** re-exported any more — this page no longer renders assets at
 * all, `features/assets/assets.ts` imports those directly from `core/fleet/warehouse-logic.ts`.
 */
export {
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  availableDeviceActions,
  buildDeviceRenameEdit,
  reasonedDeviceActions,
  type ActionAvailability,
  type DeviceLifecycleAction,
} from '../../core/fleet/warehouse-logic';

/**
 * The "existing categories + backend seed list" category picker moved to
 * `core/fleet/category-logic.ts` (docs/plans/done/UX-REWORK-PLAN.md §U-d) once the onboarding wizard's Profile
 * step needed the identical picker — same cross-feature-module rule as the re-export block above.
 * Re-exported here so this page's own pre-existing import site keeps working verbatim.
 */
export { deriveCategoryOptions, type CategoryOption } from '../../core/fleet/category-logic';

/**
 * The pinned REST contract (docs/main/CYCLES-PLAN.md §8) is coded against verbatim even though CW-a
 * (the backend half) is not live while this lands: every mutation this logic feeds goes through
 * `FleetStore`'s `run()` funnel, so a 404 today degrades to one toast, never a crash.
 */

/**
 * Enough about a device's owning asset to render an "owned by" column, an unassign action, and
 * (docs/plans/done/UX-REWORK-PLAN.md §U-a2 item 3a) whether unassigning it would leave that asset with none —
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

/**
 * docs/plans/active/OPERATOR-UX-7-PLAN.md finding D1: only one device can actually own a given
 * `protocol`+`uri` pair — a UDP listener, an RTSP path — but the table showed two or three
 * identically-configured devices as unrelated rows, and the operator learned which one was
 * authoritative by starting one and watching the others fail. This is the missing cross-reference:
 * deviceId → the *other* ACTIVE (non-archived) devices sharing its exact `protocol`+`uri`.
 *
 * Deliberately narrow: normalises only trailing whitespace (a copy-pasted URI with a stray
 * trailing space is still the same endpoint) — no case-folding, no scheme-aware parsing. The
 * backend treats `protocol`+`uri` as an opaque connection string, so this compares it the same
 * way. A `DELETED` device neither contributes to nor appears in another device's conflict list —
 * an archived listener isn't competing for the endpoint any more.
 *
 * No blocking, no backend change: the operator may well intend a shared listener (docs/plans/active/
 * OPERATOR-UX-7-PLAN.md's own §2 D1 design note) — this only makes the fact visible.
 */
export function endpointConflicts(devices: readonly Device[]): ReadonlyMap<string, readonly string[]> {
  const active = devices.filter((device) => device.state !== 'DELETED');
  const groupsByEndpoint = new Map<string, Device[]>();
  for (const device of active) {
    // A NUL separator, not a plain concatenation or a space — a protocol/uri pair split
    // differently (e.g. protocol 'a b' + uri 'c' vs. protocol 'a' + uri 'b c') must never
    // collide into the same key.
    const key = `${device.protocol.replace(/\s+$/, '')}\u0000${device.uri.replace(/\s+$/, '')}`;
    const group = groupsByEndpoint.get(key);
    if (group) {
      group.push(device);
    } else {
      groupsByEndpoint.set(key, [device]);
    }
  }

  const conflicts = new Map<string, readonly string[]>();
  for (const group of groupsByEndpoint.values()) {
    if (group.length < 2) {
      continue;
    }
    for (const device of group) {
      conflicts.set(
        device.id,
        group.filter((other) => other.id !== device.id).map((other) => other.name),
      );
    }
  }
  return conflicts;
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
 * Advanced/raw-devices table's view model (docs/main/CYCLES-PLAN.md §11 demoted this from the page's
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

/**
 * The Devices page's search box (this cycle's Assets/Devices/Warehouse inventory restructure — no
 * dedicated `docs/*-PLAN.md`, see `vision-web/MODULE.md`'s own changelog entry): case-insensitive
 * substring match on device name, protocol, URI, or (when owned) the owning asset's name — a
 * device row carries no single "name" field a user would search by; matching all four is what
 * makes "search for a camera by its IP" or "search by owning asset" both actually work.
 */
export function searchWarehouseRowsByQuery(
  rows: readonly WarehouseRow[],
  query: string,
): readonly WarehouseRow[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return rows;
  }
  return rows.filter(
    (row) =>
      row.device.name.toLowerCase().includes(q) ||
      row.device.protocol.toLowerCase().includes(q) ||
      row.device.uri.toLowerCase().includes(q) ||
      (row.owner?.assetName.toLowerCase().includes(q) ?? false),
  );
}

/**
 * docs/plans/done/VISUAL-REFRESH-PLAN.md F5's "at most one chip per row" — collapses a row's lifecycle +
 * streaming facts into the one state a glance actually needs, priority archived > deactivated >
 * live > the default "stopped" (an active device with no running stream — rendered as a dot +
 * plain text everywhere it's used rather than a fourth chip color, so the merely-normal case
 * doesn't compete for attention with the three exception states that do get a chip). "Simulated"
 * is a separate call-out (a device's origin, not its lifecycle/streaming state) and stays its own
 * chip alongside this one, unmerged.
 *
 * **Deliberately duplicated** in `features/assets/assets-logic.ts#describeAssetState`
 * (byte-similar, `'offline'`/`'Offline'` instead of `'stopped'`/`'Stopped'`) — see that function's
 * own doc comment for why this isn't lifted to `core/fleet/warehouse-logic.ts` this task.
 */
export type DeviceStateKind = 'archived' | 'deactivated' | 'live' | 'stopped';

export interface DeviceStateDescriptor {
  readonly kind: DeviceStateKind;
  readonly label: string;
}

export function describeDeviceState(row: Pick<WarehouseRow, 'lifecycle' | 'archived' | 'streaming'>): DeviceStateDescriptor {
  if (row.archived) {
    return { kind: 'archived', label: 'Archived' };
  }
  if (row.lifecycle === 'DEACTIVATED') {
    return { kind: 'deactivated', label: 'Deactivated' };
  }
  if (row.streaming) {
    return { kind: 'live', label: 'Live' };
  }
  return { kind: 'stopped', label: 'Stopped' };
}

/**
 * Resolves the two-pane detail panel's row from `?sel=<deviceId>` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4,
 * docs/extracts/design/06-devices.md) — looked up against **every** loaded row, not the search-narrowed
 * `warehouseRows()`, so typing into the search box never silently evicts an already-open selection.
 * Returns `undefined` for a missing/blank id and equally for one matching no currently-loaded row (a
 * device archived by someone else since the link was shared) — the caller (`DevicesFacade#selectedRow`)
 * treats both identically: the panel just doesn't open, never a crash or a blank panel.
 */
export function findWarehouseRowById(rows: readonly WarehouseRow[], id: string | undefined): WarehouseRow | undefined {
  if (!id) {
    return undefined;
  }
  return rows.find((row) => row.device.id === id);
}

/**
 * Builds the `POST /api/assets` body for "Promote to asset…" (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2's
 * orphaned-device quick fix, renamed to its outcome per docs/plans/done/UX-REWORK-PLAN.md §U-a2 §3): assigns
 * `device` — the existing, already-registered device, by id — to the new asset via `deviceIds`.
 *
 * **No new `Device` row, no archive step** (docs/plans/done/REALTIME-PLAN.md §4's backend follow-up batch,
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
