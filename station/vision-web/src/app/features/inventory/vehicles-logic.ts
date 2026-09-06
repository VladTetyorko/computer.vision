import type {
  AssetDetails,
  AssetSummary,
  Firmware,
  InventoryState,
  LifecycleState,
  ReadinessRow,
  ReadinessVerdict,
  UserSummary,
} from '../../core/api/models';
import { effectiveRegistration } from '../../core/fleet/asset-attributes';
import { formatFlightTime } from '../../core/fleet/asset-stats-logic';
import { triageOrder } from '../../core/fleet/triage-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';
import { fleetRowAttention } from '../../core/readiness/readiness-logic';
import { effectiveInventoryStateChip, shortIdLabel, type InventoryStateChip } from '../../core/fleet/inventory-logic';

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
 *
 * **A row is built from `AssetSummary`, never `AssetDetails`** (docs/plans/active/INVENTORY-REWORK-PLAN.md
 * wave W3, context §3 defect D). `GET /api/assets` alone carries everything ten of the eleven
 * columns need; the per-asset `GET /api/assets/{id}` that used to run once per row — 20 assets, 25
 * requests — now runs **once, on selection**, purely to fill the detail pane
 * ({@link BuildVehicleRowsInput.detailsByAssetId} lets an already-fetched one sharpen the Links
 * column, but no row ever *waits* on one).
 */

/** One row of the Vehicles/Equipment table. */
export interface VehicleRow {
  /**
   * The list-level asset record. Deliberately `AssetSummary`, not `AssetDetails` — see this module's
   * own doc comment; a caller that needs devices/usages reads them from the detail cache instead
   * (`InventoryFacade#selectedDetails`).
   */
  readonly asset: AssetSummary;
  readonly lifecycle: LifecycleState;
  readonly archived: boolean;
  /** `AssetSummary#status === 'STREAMING'` — gates the *Watch live* verb (`core/fleet/inventory-logic.ts#vehicleRowActions`). */
  readonly streaming: boolean;
  /** How many devices are linked, already rendered: `deviceCount` from the wire, else an
   *  already-loaded `AssetDetails#devices.length`, else `'—'` — never a fabricated `0`
   *  ({@link linksLabel}). */
  readonly links: string;
  readonly inventoryState?: InventoryState;
  /** The merged lifecycle+inventory-state chip (`core/fleet/inventory-logic.ts#effectiveInventoryStateChip`) — one chip per row max. */
  readonly stateChip: InventoryStateChip;
  /** `custody.custodianId` — the matrix's own input, unrendered (the cell shows {@link custodianName}). */
  readonly custodianId?: string;
  /** Who holds it, as a human reads it: the wire's own `custodianName`, else the org user-list join,
   *  else a truncated id ({@link custodianLabel}). `undefined` when the asset is in stock. */
  readonly custodianName?: string;
  /** The full custodian id whenever {@link custodianName} is a truncation of it — the cell's `title`, so the value stays copyable. `undefined` when the name is a real name. */
  readonly custodianTitle?: string;
  /** Where it physically is (`custody.location`) — INVENTORY-REWORK-PLAN.md §5.1's new column. */
  readonly location?: string;
  /** `identity.registration`, falling back to the legacy `attributes.registrationNumber` key
   *  (`core/fleet/asset-attributes.ts#effectiveRegistration` — the same fallback the asset detail
   *  page's own Identity fact group uses, so a pre-D1 asset's registration reads identically in both
   *  places rather than only surfacing once someone re-saves it through the newer editor). */
  readonly registration?: string;
  /** Joined from `GET /api/fleet/readiness` by `assetId`; `undefined` when the asset has never been evaluated. */
  readonly readinessVerdict?: ReadinessVerdict;
  /** *Why* the verdict is what it is — the first non-`READY` feature, `+N more` for the rest
   *  (`core/readiness/readiness-logic.ts#fleetRowAttention`). `undefined` for a GO row and for one
   *  never evaluated: a verdict with no cause is the honest render of both. */
  readonly readinessCause?: string;
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

/** The Links column's own render (docs/plans/active/INVENTORY-REWORK-PLAN.md §6 row 3, context §3
 *  defect D): the wire's `deviceCount` first; an already-fetched `AssetDetails`' own device list
 *  second (a station on a pre-W1 backend still gets a real number for the row it has open); `'—'`
 *  otherwise. Never `0` from an absent field — "no devices linked" is a claim, and this row has no
 *  evidence for it. */
export function linksLabel(deviceCount: number | undefined, details: AssetDetails | undefined): string {
  const count = deviceCount ?? details?.devices.length;
  return count === undefined ? '—' : String(count);
}

/** One custodian, named the best way the data allows (docs/plans/active/INVENTORY-REWORK-PLAN.md §6
 *  row 1, decision D3): the name the wire resolved, else the org user-list join (which returns `[]`
 *  for a pilot's `ASSIGNED_ASSETS` scope, hence the third rung), else a truncated id paired with a
 *  `title` carrying the whole thing. Fixes context §3 defect C — a pilot used to read raw 36-character
 *  UUIDs in the Custodian column. */
export function custodianLabel(
  custody: { readonly custodianId?: string; readonly custodianName?: string } | undefined,
  nameById: ReadonlyMap<string, string>,
): { readonly name?: string; readonly title?: string } {
  const id = custody?.custodianId;
  if (!id) {
    return {};
  }
  const resolved = custody?.custodianName ?? nameById.get(id);
  return resolved ? { name: resolved } : { name: shortIdLabel(id), title: id };
}

/** Everything {@link buildVehicleRows} joins, as one record — a settings object rather than a sixth
 *  positional parameter (CLAUDE.md rule 10's spirit: a new collaborator updates the record, never
 *  grows an argument list). Every join is optional-empty-safe: a missing user, readiness row, or
 *  cached detail degrades that one field, never the row. */
export interface BuildVehicleRowsInput {
  readonly assets: readonly AssetSummary[];
  /** `GET /api/users`, or `[]` — deliberately not fetched at all for an `ASSIGNED_ASSETS` scope, where the server answers `[]` anyway (`InventoryFacade#loadAll`). */
  readonly users: readonly UserSummary[];
  /** `GET /api/fleet/readiness` rows by `assetId` — the whole row, not just its verdict, so {@link VehicleRow.readinessCause} can name the first blocker. */
  readonly readinessByAssetId: ReadonlyMap<string, ReadinessRow>;
  /** Details fetched on selection so far (`InventoryFacade#detailsByAssetId`) — only ever *sharpens* a row (today: the Links column on a pre-W1 backend); no row waits on one. */
  readonly detailsByAssetId: ReadonlyMap<string, AssetDetails>;
  readonly nowMs: number;
}

/** `AssetSummary[]` + the joins in {@link BuildVehicleRowsInput} → the table's row model. */
export function buildVehicleRows(input: BuildVehicleRowsInput): readonly VehicleRow[] {
  const nameById = new Map(input.users.map((user) => [user.userId, user.displayName]));
  return input.assets.map((asset) => {
    const lifecycle = asset.lifecycle ?? 'ACTIVE';
    const archived = lifecycle === 'DELETED';
    const readiness = input.readinessByAssetId.get(asset.assetId);
    const custodian = custodianLabel(asset.custody, nameById);
    return {
      asset,
      lifecycle,
      archived,
      streaming: asset.status === 'STREAMING',
      links: linksLabel(asset.deviceCount, input.detailsByAssetId.get(asset.assetId)),
      inventoryState: asset.inventoryState,
      stateChip: effectiveInventoryStateChip({ lifecycle, archived, inventoryState: asset.inventoryState }),
      custodianId: asset.custody?.custodianId,
      custodianName: custodian.name,
      custodianTitle: custodian.title,
      location: asset.custody?.location,
      registration: effectiveRegistration(asset),
      readinessVerdict: readiness?.verdict,
      readinessCause: readiness ? (fleetRowAttention(readiness, 1) ?? undefined) : undefined,
      firmware: firmwareLabel(asset.firmware),
      hours: formatFlightTime(asset.totalFlightSeconds ?? null),
      lastFlownLabel: vehicleLastFlownLabel(asset.lastUsedAt, input.nowMs),
    };
  });
}

/** The table's default row order — reuses `core/fleet/triage-logic.ts#triageOrder` directly on each
 *  row's own `asset` (an `AssetSummary` structurally satisfies `TriageCandidate`), the same triage
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
  return custodianId ? rows.filter((row) => row.custodianId === custodianId) : rows;
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
    const id = row.custodianId;
    if (id && !byId.has(id)) {
      byId.set(id, row.custodianName ?? id);
    }
  }
  return [...byId.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name));
}
