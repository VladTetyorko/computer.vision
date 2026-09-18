import type {
  AssetDetails,
  AssetSummary,
  Firmware,
  InventoryState,
  LifecycleState,
  MaintenanceRecord,
  ReadinessRow,
  ReadinessVerdict,
  UserSummary,
} from '../../core/api/models';
import { actorLabel } from '../../core/audit/summary-logic';
import { MAINTENANCE_KIND_LABELS } from '../../core/maintenance/maintenance-logic';
import { effectiveRegistration } from '../../core/fleet/asset-attributes';
import { formatFlightTime } from '../../core/fleet/asset-stats-logic';
import { isSimulated, triageOrder } from '../../core/fleet/triage-logic';
import { humanAge } from '../../core/telemetry/telemetry-logic';
import { fleetRowAttention, fleetRowBlockers } from '../../core/readiness/readiness-logic';
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
  /** How long the current custodian has held it, as a human reads it (`3h ago`); `'—'` while it is in stock ({@link custodySinceLabel}). */
  readonly sinceLabel: string;
  /** `core/fleet/triage-logic.ts#isSimulated` — the drawer's muted origin line, never a chip (one chip per row). */
  readonly simulated: boolean;
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
  /** Every blocker behind {@link readinessCause}, uncapped and in the same order
   *  (`fleetRowBlockers`) — the drawer's *Why not ready* list (INVENTORY-REWORK-PLAN.md §5.3);
   *  empty for a GO row and for one never evaluated alike. */
  readonly readinessBlockers: readonly string[];
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
 * How long a custody has stood, as a human reads it — `'3h ago'` (docs/plans/active/INVENTORY-REWORK-PLAN.md
 * §5.3's `SINCE` fact). `'—'` for an asset in stock, and for a `since` the backend sent in a shape
 * this build can't parse: the drawer's own em-dash convention, never a raw ISO timestamp in a fact
 * grid (which is what that cell rendered before this wave) and never a fabricated "just now".
 */
export function custodySinceLabel(since: string | undefined, nowMs: number): string {
  if (!since) {
    return '—';
  }
  const parsed = Date.parse(since);
  if (Number.isNaN(parsed)) {
    return '—';
  }
  return `${humanAge(Math.max(0, (nowMs - parsed) / 1000))} ago`;
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
      sinceLabel: custodySinceLabel(asset.custody?.since, input.nowMs),
      simulated: isSimulated(asset),
      registration: effectiveRegistration(asset),
      readinessVerdict: readiness?.verdict,
      readinessCause: readiness ? (fleetRowAttention(readiness, 1) ?? undefined) : undefined,
      readinessBlockers: readiness ? fleetRowBlockers(readiness) : [],
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

/**
 * Case-insensitive substring match across the three things a manager actually searches an inventory
 * by (docs/plans/active/INVENTORY-REWORK-PLAN.md §3.1 "Where is X / who has it", §5.1's search
 * placeholder): the **display name**, the **serial** (`identity.serialNumber`) and the
 * **registration** (`identity.registration`, falling back to the legacy attribute key through
 * {@link VehicleRow.registration}). It used to be name-only, which meant the one identifier
 * physically painted on the aircraft — its registration — could not be typed into the box above the
 * column that shows it.
 *
 * Renamed from `searchVehicleRowsByName` deliberately: a function that also matches serials must not
 * keep a name that says otherwise.
 */
export function searchVehicleRows(rows: readonly VehicleRow[], query: string): readonly VehicleRow[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return rows;
  }
  return rows.filter((row) =>
    [row.asset.displayName, row.asset.identity?.serialNumber, row.registration].some((field) =>
      field ? field.toLowerCase().includes(q) : false,
    ),
  );
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

// --- The drawer's Maintenance line (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.3) -------------

/** The one open record a drawer names: `kind · summary · opened by <name> · <age>`, already rendered. */
export interface OpenMaintenanceSummary {
  readonly recordId: string;
  readonly kindLabel: string;
  readonly summary: string;
  /** The opener's display name, `'Station'` for the system principal, a truncated id when no name reached (`core/audit/summary-logic.ts#actorLabel`). */
  readonly openedByLabel: string;
  readonly ageLabel: string;
}

/**
 * The **oldest still-open** record for an asset, as the drawer prints it — oldest because that is
 * the one that has been holding the vehicle down longest, and the only one a manager needs named
 * before pressing Release. `undefined` when nothing is open (the drawer then says "No open records"
 * rather than showing a closed record's history, which belongs on the full asset page).
 *
 * The kind reads through `core/maintenance/maintenance-logic.ts#MAINTENANCE_KIND_LABELS` and the
 * opener through `core/audit/summary-logic.ts#actorLabel` — the same two maps `/fleet/maintenance`
 * uses, so one record never reads "Grounded, opened by Station" on one page and
 * "GROUNDING, opened by 00000000" on another.
 */
export function openMaintenanceSummary(
  records: readonly MaintenanceRecord[],
  nameById: ReadonlyMap<string, string>,
  nowMs: number,
): OpenMaintenanceSummary | undefined {
  const open = records.filter((record) => !record.closedAt);
  if (open.length === 0) {
    return undefined;
  }
  const oldest = open.reduce((a, b) => (Date.parse(a.openedAt) <= Date.parse(b.openedAt) ? a : b));
  return {
    recordId: oldest.id,
    kindLabel: MAINTENANCE_KIND_LABELS[oldest.kind] ?? oldest.kind,
    summary: oldest.summary,
    openedByLabel: actorLabel(oldest.openedBy, nameById),
    ageLabel: custodySinceLabel(oldest.openedAt, nowMs),
  };
}
