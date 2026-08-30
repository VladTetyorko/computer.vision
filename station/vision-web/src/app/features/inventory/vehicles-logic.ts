import type { AssetDetails, Firmware, InventoryState, LifecycleState, ReadinessVerdict, UserSummary } from '../../core/api/models';
import { effectiveRegistration } from '../../core/fleet/asset-attributes';
import { formatFlightTime } from '../../core/fleet/asset-stats-logic';
import { triageOrder } from '../../core/fleet/triage-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';
import { effectiveInventoryStateChip, type InventoryStateChip } from '../../core/fleet/inventory-logic';

/**
 * Pure, Angular-free logic behind the Inventory page's Vehicles/Equipment tabs
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3, wave W4) — one row shape + one filter/sort pipeline
 * shared by both tabs (`InventoryFacade#vehicleRows`/`equipmentRows`, `VehiclesTable` renders
 * whichever it's handed). The only thing distinguishing the two tabs is which categories the row set
 * is drawn from ({@link filterVehicleRowsByConnected}, joined against `Category#connected`) — the
 * table component itself decides which columns to render for which (`connected` input hides
 * Readiness/Links for Equipment).
 *
 * **Firmware/Hours** ({@link firmwareLabel}, `core/fleet/asset-stats-logic.ts#formatFlightTime`) —
 * wave W9 (docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W8 → W9 handoff") wires both to
 * `AssetSummary#firmware`/`#totalFlightSeconds`, joined server-side at the vision-api layer
 * (`AssetRowFacts`, WAREHOUSE-UX-PLAN.md D5) and already present on every `GET /api/assets`/
 * `GET /api/assets/{id}` response this page already fetches — no new call, no N+1 (the W4→W7
 * "always '—', no fleet-wide source" gap `station/vision-web/MODULE.md`'s W4 entry documented is
 * closed). Both still degrade honestly to `'—'` when the underlying asset was never probed /
 * never flown — see {@link firmwareLabel} and `formatFlightTime`'s own `null` case.
 */

/** One row of the Vehicles/Equipment table. */
export interface VehicleRow {
  readonly asset: AssetDetails;
  readonly lifecycle: LifecycleState;
  readonly archived: boolean;
  readonly deviceCount: number;
  readonly inventoryState?: InventoryState;
  /** The merged lifecycle+inventory-state chip (`core/fleet/inventory-logic.ts#effectiveInventoryStateChip`) — one chip per row max. */
  readonly stateChip: InventoryStateChip;
  /** Resolved against the org's user list; falls back to the raw id when the custodian isn't found (a deactivated/deleted user). `undefined` when the asset is in stock. */
  readonly custodianName?: string;
  /** `identity.registration`, falling back to the legacy `attributes.registrationNumber` key
   *  (`core/fleet/asset-attributes.ts#effectiveRegistration` — the same fallback the asset detail
   *  page's own Identity fact group uses, so a pre-D1 asset's registration reads identically in both
   *  places rather than only surfacing once someone re-saves it through the newer editor). */
  readonly registration?: string;
  /** Joined from `GET /api/fleet/readiness` by `assetId`; `undefined` when the asset has never been evaluated. */
  readonly readinessVerdict?: ReadinessVerdict;
  /** `{@link firmwareLabel}` of `asset.firmware` — `'—'` when never probed. */
  readonly firmware: string;
  /** `formatFlightTime` of `asset.totalFlightSeconds` — `'—'` when absent (no join to offer), never for a genuine zero (renders `'0m'`). */
  readonly hours: string;
  readonly lastFlownLabel: string;
}

/**
 * Human display names for `Firmware#name`'s wire codes (`FirmwareResponse`'s own doc comment:
 * `"ardupilot" | "generic" | "px4"`) — an unrecognized code (a future firmware this file hasn't
 * heard of yet) renders verbatim rather than being hidden, the same "render it, don't invent its
 * meaning" rule `readiness-logic.ts#featureLabel` uses for an unknown key.
 */
const FIRMWARE_NAME_LABELS: Record<string, string> = {
  ardupilot: 'ArduPilot',
  px4: 'PX4',
  generic: 'Generic',
};

/**
 * The Firmware column's own render: `"<name> <version>"` when both are known (e.g. `"ArduPilot
 * 4.7.0"`), whichever one alone is known when only one is, and `'—'` when the asset was never
 * probed at all (`firmware` absent) or the probe answered with neither field identified.
 */
export function firmwareLabel(firmware: Firmware | undefined): string {
  const name = firmware?.name ? (FIRMWARE_NAME_LABELS[firmware.name] ?? firmware.name) : undefined;
  const version = firmware?.version;
  if (name && version) {
    return `${name} ${version}`;
  }
  return name ?? version ?? '—';
}

/** `<humanAge> ago` / `'Never flown'` — this table's own register, distinct from
 *  `features/assets/assets-logic.ts#lastSeenLabel`'s wording (that page is being deleted this wave;
 *  this is its replacement, not a second copy of a surviving one). */
export function vehicleLastFlownLabel(lastUsedAt: string | undefined, nowMs: number): string {
  if (!lastUsedAt) {
    return 'Never flown';
  }
  const ageSeconds = Math.max(0, (nowMs - Date.parse(lastUsedAt)) / 1000);
  return `${humanAge(ageSeconds)} ago`;
}

/**
 * `AssetDetails[]` (+ the org's user list, + a readiness join map) → the table's row model. `users`/
 * `readinessByAssetId` are optional-empty-safe: a custodian id with no matching user, or an asset
 * with no readiness row yet, both degrade to `undefined`/the raw id rather than throwing.
 */
export function buildVehicleRows(
  assets: readonly AssetDetails[],
  users: readonly UserSummary[],
  readinessByAssetId: ReadonlyMap<string, ReadinessVerdict>,
  nowMs: number,
): readonly VehicleRow[] {
  const nameById = new Map(users.map((user) => [user.userId, user.displayName]));
  return assets.map((asset) => {
    const lifecycle = asset.lifecycle ?? 'ACTIVE';
    const archived = lifecycle === 'DELETED';
    const custodianId = asset.custody?.custodianId;
    return {
      asset,
      lifecycle,
      archived,
      deviceCount: asset.devices.length,
      inventoryState: asset.inventoryState,
      stateChip: effectiveInventoryStateChip({ lifecycle, archived, inventoryState: asset.inventoryState }),
      custodianName: custodianId ? (nameById.get(custodianId) ?? custodianId) : undefined,
      registration: effectiveRegistration(asset),
      readinessVerdict: readinessByAssetId.get(asset.assetId),
      firmware: firmwareLabel(asset.firmware),
      hours: formatFlightTime(asset.totalFlightSeconds ?? null),
      lastFlownLabel: vehicleLastFlownLabel(asset.lastUsedAt, nowMs),
    };
  });
}

/** The table's default row order — reuses `core/fleet/triage-logic.ts#triageOrder` directly on each
 *  row's own `asset` (an `AssetDetails` structurally satisfies `TriageCandidate`), the same triage
 *  `/fly`'s picker and Command's rail already give a fleet list — streaming first, last-seen
 *  descending, real vehicles ahead of simulated. */
export function sortVehicleRowsByTriage(rows: readonly VehicleRow[], nowMs: number): readonly VehicleRow[] {
  return [...rows].sort((a, b) => triageOrder(a.asset, b.asset, nowMs));
}

/** The Vehicles/Equipment split itself — `connectedCategorySlugs` is every `Category#connected`
 *  category's own slug; `wantConnected: true` = Vehicles, `false` = Equipment. A category not yet
 *  loaded (an empty slug set, mid-fetch) reads every asset as Equipment until it resolves — a brief,
 *  honest transient, never a fabricated Vehicles placement. */
export function filterVehicleRowsByConnected(
  rows: readonly VehicleRow[],
  connectedCategorySlugs: ReadonlySet<string>,
  wantConnected: boolean,
): readonly VehicleRow[] {
  return rows.filter((row) => connectedCategorySlugs.has(row.asset.category) === wantConnected);
}

/** Hides archived (lifecycle `DELETED`) rows unless the "show archived" toggle is on. */
export function filterVehicleRowsByArchived(rows: readonly VehicleRow[], showArchived: boolean): readonly VehicleRow[] {
  return showArchived ? rows : rows.filter((row) => !row.archived);
}

/**
 * Hides `RETIRED` rows unless the "show retired" toggle is on, **or** the inventory-state filter is
 * explicitly set to `RETIRED` — an explicit "show me retired vehicles" pick always wins over the
 * checkbox's own default, the same "an explicit filter pick is never silently overridden" rule
 * `features/assets/assets-logic.ts#filterAssetListRowsByCategory`'s own doc comment states for its
 * `?category=` filter.
 */
export function filterVehicleRowsByRetired(
  rows: readonly VehicleRow[],
  showRetired: boolean,
  inventoryStateFilter: VehicleInventoryStateFilter,
): readonly VehicleRow[] {
  if (showRetired || inventoryStateFilter === 'RETIRED') {
    return rows;
  }
  return rows.filter((row) => row.inventoryState !== 'RETIRED');
}

/** `?category=<slug>` filter — blank/absent leaves every row. */
export function filterVehicleRowsByCategory(rows: readonly VehicleRow[], category: string | undefined): readonly VehicleRow[] {
  const slug = category?.trim();
  return slug ? rows.filter((row) => row.asset.category === slug) : rows;
}

export type VehicleInventoryStateFilter = 'all' | InventoryState;

export function filterVehicleRowsByInventoryState(
  rows: readonly VehicleRow[],
  filter: VehicleInventoryStateFilter,
): readonly VehicleRow[] {
  return filter === 'all' ? rows : rows.filter((row) => row.inventoryState === filter);
}

/** Filters to one custodian's own issued/in-field assets. */
export function filterVehicleRowsByCustodian(rows: readonly VehicleRow[], custodianId: string | undefined): readonly VehicleRow[] {
  return custodianId ? rows.filter((row) => row.asset.custody?.custodianId === custodianId) : rows;
}

/** `'UNKNOWN'` here means "never evaluated" (no readiness row at all) — distinct from the wire's own
 *  `ReadinessVerdict` `'UNKNOWN'` value (a row that *was* evaluated but couldn't reach a verdict),
 *  which this filter also matches under the same option since both read as "not GO/NO-GO" to a
 *  manager scanning the fleet. */
export type VehicleReadinessFilter = 'all' | ReadinessVerdict | 'UNEVALUATED';

export function filterVehicleRowsByReadiness(rows: readonly VehicleRow[], filter: VehicleReadinessFilter): readonly VehicleRow[] {
  if (filter === 'all') {
    return rows;
  }
  if (filter === 'UNEVALUATED') {
    return rows.filter((row) => row.readinessVerdict === undefined);
  }
  return rows.filter((row) => row.readinessVerdict === filter);
}

/** Case-insensitive substring match on the asset's display name. */
export function searchVehicleRowsByName(rows: readonly VehicleRow[], query: string): readonly VehicleRow[] {
  const q = query.trim().toLowerCase();
  return q ? rows.filter((row) => row.asset.displayName.toLowerCase().includes(q)) : rows;
}

/** Resolves the two-pane detail panel's row from `?sel=<assetId>`, against every loaded row (not the
 *  filter-narrowed set) — see `features/assets/assets-logic.ts#findAssetRowById`'s identical doc
 *  comment for why. */
export function findVehicleRowById(rows: readonly VehicleRow[], id: string | undefined): VehicleRow | undefined {
  if (!id) {
    return undefined;
  }
  return rows.find((row) => row.asset.assetId === id);
}

/** One custodian the "Custodian" filter can offer. */
export interface CustodianOption {
  readonly id: string;
  readonly name: string;
}

/** Every custodian currently holding ≥1 asset among `rows`, deduped, sorted by name — the filter's own options list. */
export function custodianFilterOptions(rows: readonly VehicleRow[]): readonly CustodianOption[] {
  const byId = new Map<string, string>();
  for (const row of rows) {
    const id = row.asset.custody?.custodianId;
    if (id && !byId.has(id)) {
      byId.set(id, row.custodianName ?? id);
    }
  }
  return [...byId.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name));
}

/** Which of the row kebab's seven verbs apply to one row — poka-yoke availability, not
 *  enable-then-error (mirrors `core/fleet/warehouse-logic.ts`'s own "reasoned action availability"
 *  pattern, simplified to a plain boolean set since none of these seven carry a distinct disabled
 *  *reason* string worth surfacing). An asset whose `inventoryState` hasn't been fetched at all
 *  (`undefined` — shouldn't happen once `AssetDetails` has loaded, but never assumed) hides every
 *  inventory-mutating verb rather than guessing one is safe; Open/Fly never depend on it. */
export interface VehicleRowActions {
  readonly issue: boolean;
  readonly return: boolean;
  readonly ground: boolean;
  readonly release: boolean;
  readonly retire: boolean;
  readonly fly: boolean;
}

export function vehicleRowActions(row: Pick<VehicleRow, 'archived' | 'lifecycle' | 'inventoryState'>): VehicleRowActions {
  const outOfService = row.archived || row.lifecycle === 'DEACTIVATED';
  const state = row.inventoryState;
  const known = state !== undefined;
  const retired = state === 'RETIRED';
  const maintenance = state === 'MAINTENANCE';
  const issued = state === 'ISSUED' || state === 'IN_FIELD';
  const inStock = state === 'IN_STOCK';
  return {
    issue: known && !outOfService && inStock,
    return: known && !outOfService && issued,
    ground: known && !outOfService && !retired && !maintenance,
    release: known && !outOfService && maintenance,
    retire: known && !outOfService && !retired,
    fly: !outOfService,
  };
}
