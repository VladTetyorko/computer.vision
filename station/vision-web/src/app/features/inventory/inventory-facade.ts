import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { AuthStore } from '../../core/auth/auth-store';
import { canManageOrg as computeCanManageOrg } from '../../core/org/org-logic';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { pluralize } from '../../shared/ui/text-logic';
import { deriveCategoryOptions, type CategoryOption } from '../../core/fleet/category-logic';
import { buildSyntheticRegisterRequest } from '../../core/fleet/simulation-logic';
import { RESTORE_TARGET_STATE } from '../../core/fleet/warehouse-logic';
import {
  inventoryActor,
  inventoryExportFilename,
  parseInventoryTab,
  vehicleRowActions,
  visibleInventoryTabs,
  type InventoryActor,
  type InventoryTab,
  type VehicleRowActions,
} from '../../core/fleet/inventory-logic';
import { findVideoDevice } from '../../core/fleet/device-logic';
import { inventoryKpis, type InventoryKpis } from './inventory-page-logic';
import {
  buildVehicleRows,
  custodianFilterOptions,
  filterVehicleRowsByArchived,
  filterVehicleRowsByCategory,
  filterVehicleRowsByConnected,
  filterVehicleRowsByCustodian,
  filterVehicleRowsByInventoryState,
  filterVehicleRowsByReadiness,
  filterVehicleRowsByRetired,
  findVehicleRowById,
  searchVehicleRowsByName,
  sortVehicleRowsByTriage,
  type CustodianOption,
  type VehicleInventoryStateFilter,
  type VehicleReadinessFilter,
  type VehicleRow,
} from './vehicles-logic';
import type {
  AssetDetails,
  AssetSummary,
  Category,
  FleetSummary,
  MaintenanceKind,
  MaintenanceRecord,
  ReadinessRow,
  UserSummary,
} from '../../core/api/models';

/**
 * `InventoryPage`'s facade (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3, wave W4;
 * docs/plans/done/UI-ARCHITECTURE-PLAN.md layering) — owns every store/service injection and
 * derived read-model for the Vehicles/Equipment tabs (Links/Categories mount their own pre-existing
 * `DevicesPage`/`CategoriesPage`, each with its own facade — this one never reaches into either).
 *
 * **One fetch, two views**: `assets`/`categories`/`users`/`readiness` are fetched once; `vehicleRows`
 * and `equipmentRows` are the *same* underlying `AssetDetails[]` split by `Category#connected`
 * ({@link filterVehicleRowsByConnected}) and run through one shared filter pipeline
 * ({@link filteredRows}) — switching tabs never re-fetches, only re-filters.
 *
 * **Firmware/Hours columns** read `AssetSummary#firmware`/`#totalFlightSeconds` directly (wave W9,
 * docs/plans/active/WAREHOUSE-UX-CONTEXT.md "W8 → W9 handoff") — already present on the same
 * `listAssets` response this facade already fetches, no second call. See `vehicles-logic.ts`'s own
 * module doc comment for the render (`firmwareLabel`/`formatFlightTime`).
 *
 * **Five requests, not `5 + N`** (docs/plans/active/INVENTORY-REWORK-PLAN.md wave W3): {@link loadAll}
 * builds every row from `GET /api/assets` alone; the per-asset `GET /api/assets/{id}` runs only when
 * a row is *selected* ({@link ensureDetails}, cached per id) or when the *Watch live* verb needs a
 * device id.
 *
 * **Every verb on screen is one this session may actually use** — {@link actor} × {@link actionsFor}
 * (`core/fleet/inventory-logic.ts#vehicleRowActions`, plan §5.2). No template in this feature makes
 * its own authority decision.
 *
 * **Mutations patch in place.** `setAssetCustody`/`setAssetInventory` both return the asset's full,
 * updated `AssetDetails` — {@link patchAsset} splices it back into `assets()` directly rather than
 * re-fetching the whole fleet, so a kebab action reflects immediately without a network round trip
 * beyond the one the action itself made.
 */
@Injectable()
export class InventoryFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthStore);
  private readonly settings = inject(SettingsStore);

  readonly fleet = inject(FleetStore);

  // --- Tab + role gate (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1: a pilot only sees Vehicles/Equipment) --

  readonly tab = signal<InventoryTab>('vehicles');
  readonly canManageOrg = computed(() => computeCanManageOrg(this.auth.capabilities()));
  readonly visibleTabs = computed(() => visibleInventoryTabs(this.canManageOrg()));

  /**
   * Who is looking (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.2, wave W3) — the one place this
   * page reads the session's capabilities, threaded into every verb decision through
   * {@link actionsFor}. Recomputes on its own the moment `GET /api/auth/me` resolves, so the first
   * paint of a still-loading session offers *nothing* rather than optimistically offering
   * everything and then taking it away.
   */
  readonly actor = computed<InventoryActor>(() => inventoryActor(this.auth.capabilities(), this.auth.user()?.userId));

  /** The verb matrix for one row — every kebab/pane/card control reads this, never a bespoke `@if`. */
  actionsFor(row: VehicleRow): VehicleRowActions {
    return vehicleRowActions(row, this.actor());
  }

  /** Route-bound `?tab=` → the facade's own `tab` signal, falling back to `vehicles` for a pilot who
   *  guesses/bookmarks a `?tab=links`/`?tab=categories` URL — mirrors `AssetsPage`'s own `?category=`
   *  forwarding, just with an extra honesty clamp on top. */
  setTabFromQueryParam(raw: string | undefined): void {
    const parsed = parseInventoryTab(raw ?? null);
    this.tab.set(this.visibleTabs().includes(parsed) ? parsed : 'vehicles');
  }

  selectTab(tab: InventoryTab): void {
    this.tab.set(tab);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { tab }, queryParamsHandling: 'merge' });
  }

  // --- Raw data ----------------------------------------------------------------------------------

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly submitting = signal(false);
  readonly busyAssetId = signal<string | null>(null);

  readonly showArchived = signal(false);
  private readonly assets = signal<readonly AssetSummary[]>([]);
  private readonly categories = signal<readonly Category[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  private readonly readinessByAssetId = signal<ReadonlyMap<string, ReadinessRow>>(new Map());
  private readonly fleetSummaryData = signal<FleetSummary | undefined>(undefined);
  /** `GET /api/assets/{id}` responses fetched **on selection**, keyed by asset id — see {@link ensureDetails}. */
  private readonly detailsByAssetId = signal<ReadonlyMap<string, AssetDetails>>(new Map());

  /** "Fleet at a glance" KPI strip above the Vehicles tab (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3)
   *  — ported from the deleted Reports page's own KPI-only section, see `inventory-page-logic.ts`. */
  readonly kpis = computed<InventoryKpis>(() => inventoryKpis(this.fleetSummaryData()));

  readonly connectedCategorySlugs = computed(
    () => new Set(this.categories().filter((category) => category.connected).map((category) => category.slug)),
  );

  // --- Search + filters (shared by both Vehicles and Equipment — switching tabs keeps them) -------

  readonly searchQuery = signal('');
  readonly categoryFilter = signal('');
  readonly inventoryStateFilter = signal<VehicleInventoryStateFilter>('all');
  readonly custodianFilter = signal('');
  readonly readinessFilter = signal<VehicleReadinessFilter>('all');
  readonly showRetired = signal(false);

  readonly hasActiveFilters = computed(
    () =>
      this.searchQuery().trim().length > 0 ||
      this.categoryFilter().length > 0 ||
      this.inventoryStateFilter() !== 'all' ||
      this.custodianFilter().length > 0 ||
      this.readinessFilter() !== 'all',
  );

  /** Every loaded asset as a row, unsorted-by-filter, sorted-by-triage once — both tab views and the
   *  two-pane selection read this, never `assets()` directly (mirrors `AssetsFacade#allRows`). */
  private readonly allRows = computed<readonly VehicleRow[]>(() =>
    sortVehicleRowsByTriage(
      buildVehicleRows({
        assets: this.assets(),
        users: this.users(),
        readinessByAssetId: this.readinessByAssetId(),
        detailsByAssetId: this.detailsByAssetId(),
        nowMs: Date.now(),
      }),
      Date.now(),
    ),
  );

  private filteredRows(connected: boolean): readonly VehicleRow[] {
    const byConnected = filterVehicleRowsByConnected(this.allRows(), this.connectedCategorySlugs(), connected);
    const byArchived = filterVehicleRowsByArchived(byConnected, this.showArchived());
    const byRetired = filterVehicleRowsByRetired(byArchived, this.showRetired(), this.inventoryStateFilter());
    const byCategory = filterVehicleRowsByCategory(byRetired, this.categoryFilter() || undefined);
    const byState = filterVehicleRowsByInventoryState(byCategory, this.inventoryStateFilter());
    const byCustodian = filterVehicleRowsByCustodian(byState, this.custodianFilter() || undefined);
    const byReadiness = filterVehicleRowsByReadiness(byCustodian, this.readinessFilter());
    return searchVehicleRowsByName(byReadiness, this.searchQuery());
  }

  readonly vehicleRows = computed<readonly VehicleRow[]>(() => this.filteredRows(true));
  readonly equipmentRows = computed<readonly VehicleRow[]>(() => this.filteredRows(false));

  /** The currently-active tab's own row set — what the visible `<vision-vehicles-table>` renders. */
  readonly activeRows = computed<readonly VehicleRow[]>(() => (this.tab() === 'equipment' ? this.equipmentRows() : this.vehicleRows()));

  readonly hasAnyAssets = computed(() => this.assets().length > 0);

  readonly categoryOptions = computed<readonly CategoryOption[]>(() => {
    const seed = this.categories().map((category) => ({ slug: category.slug, name: category.name }));
    return deriveCategoryOptions(
      this.allRows()
        .filter((row) => this.connectedCategorySlugs().has(row.asset.category) === (this.tab() !== 'equipment'))
        .map((row) => row.asset),
      seed,
    );
  });

  readonly custodianOptions = computed<readonly CustodianOption[]>(() => custodianFilterOptions(this.allRows()));

  /** The full "Issue to…" picker — every *enabled* org user, not just today's existing custodians
   *  ({@link custodianOptions} is a filter's own narrower list, drawn only from rows that already
   *  have one). Not role-restricted to PILOT: a manager/admin can also carry equipment (the
   *  Equipment tab's own custodians are frequently not pilots at all), so this offers the whole
   *  enabled directory rather than guessing a role cutoff the plan never specified. */
  readonly custodianDirectory = computed<readonly UserSummary[]>(() =>
    [...this.users()].filter((user) => user.enabled).sort((a, b) => a.displayName.localeCompare(b.displayName)),
  );

  // --- Two-pane selection (`?sel=<assetId>`, mirrors `AssetsFacade`) ------------------------------

  readonly selectedId = signal<string | undefined>(undefined);
  readonly selectedRow = computed<VehicleRow | undefined>(() => findVehicleRowById(this.allRows(), this.selectedId()));

  selectRow(assetId: string): void {
    this.selectedId.set(assetId);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { sel: assetId }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  clearSelection(): void {
    this.selectedId.set(undefined);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { sel: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  openCockpitFor(assetId: string): void {
    this.settings.flyAssetId.set(assetId);
    void this.router.navigate(['/fly']);
  }

  /**
   * The *Watch live* verb (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.2 column 4) — the
   * lightweight read-only `/live/:deviceId` page, the same destination `asset-detail-facade.ts#watch`
   * uses. A row carries no device id (that is exactly the `getAsset` this wave stopped issuing per
   * row), so the click resolves one through {@link ensureDetails}: one request, cached, and only for
   * the asset actually clicked. An asset with no video device says so rather than navigating
   * nowhere — the verb only ever renders for a `STREAMING` asset, so this is the honest report of a
   * genuinely odd state, not a routine path.
   */
  async watchLive(assetId: string): Promise<void> {
    const details = await this.ensureDetails(assetId);
    const device = findVideoDevice(details?.devices ?? []);
    if (!device) {
      this.toasts.error('No video device is linked to this vehicle.');
      return;
    }
    await this.router.navigate(['/live', device.id]);
  }

  openAsset(assetId: string): Promise<boolean> {
    return this.router.navigate(['/assets', assetId]);
  }

  /** "+ Add source" (docs/plans/done/UX-REWORK-PLAN.md §U-d) — the onboarding wizard is the only way in now;
   *  mirrors `AssetsFacade#goToAddSource` exactly (that page is deleted this wave). */
  goToAddSource(): Promise<boolean> {
    return this.router.navigate(['/add-source']);
  }

  // --- Maintenance drawer (detail pane) — lazy per-asset, never a fleet-wide fetch ----------------

  readonly maintenanceRecords = signal<readonly MaintenanceRecord[]>([]);
  readonly loadingMaintenance = signal(false);

  // --- Details, on selection (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.3, context §3 defect D) --

  readonly loadingDetails = signal(false);

  /** The selected asset's devices/usages — `undefined` until its own `GET /api/assets/{id}` lands (or if it failed). The detail pane renders identity/custody/state from the row meanwhile; only device- and usage-level facts wait. */
  readonly selectedDetails = computed<AssetDetails | undefined>(() => {
    const id = this.selectedId();
    return id ? this.detailsByAssetId().get(id) : undefined;
  });

  /**
   * One asset's `AssetDetails`, fetched at most once per id and reused thereafter (mutations
   * refresh the entry through {@link patchAsset}; a full {@link loadAll} empties the cache). Returns
   * `undefined` on failure — the caller degrades, never blocks: the pane keeps rendering the row's
   * own facts and the device list simply stays empty rather than the selection failing.
   */
  private async ensureDetails(assetId: string): Promise<AssetDetails | undefined> {
    const cached = this.detailsByAssetId().get(assetId);
    if (cached) {
      return cached;
    }
    this.loadingDetails.set(true);
    try {
      const details = await this.api.getAsset(assetId);
      this.detailsByAssetId.update((map) => new Map(map).set(assetId, details));
      return details;
    } catch {
      return undefined;
    } finally {
      this.loadingDetails.set(false);
    }
  }

  constructor() {
    void this.loadAll();

    // The detail pane's per-asset reads — the asset's own record (devices/usages) and its
    // maintenance history — are both fetched on *selection*, cleared on deselection, and never kept
    // warm for the whole table: 20 rows used to cost 20 `getAsset` calls nobody had asked for
    // (docs/plans/active/INVENTORY-REWORK-CONTEXT.md §3 defect D). Maintenance is deliberately still
    // `listAssetMaintenance` (per-asset), not the fleet-wide `fleetMaintenance` `/fleet/maintenance`
    // itself uses (wave W9) — this drawer is already scoped to one asset, so the fleet-wide read would
    // fetch every other asset's records only to discard them.
    effect(() => {
      const assetId = this.selectedId();
      if (assetId) {
        void this.ensureDetails(assetId);
        void this.loadMaintenance(assetId);
      } else {
        this.maintenanceRecords.set([]);
      }
    });
  }

  private async loadMaintenance(assetId: string): Promise<void> {
    this.loadingMaintenance.set(true);
    try {
      this.maintenanceRecords.set(await this.api.listAssetMaintenance(assetId));
    } catch {
      this.maintenanceRecords.set([]);
    } finally {
      this.loadingMaintenance.set(false);
    }
  }

  async openMaintenanceRecord(assetId: string, kind: MaintenanceKind, summary: string): Promise<void> {
    this.submitting.set(true);
    try {
      await this.api.createMaintenanceRecord(assetId, { kind, summary });
      await this.loadMaintenance(assetId);
      this.toasts.ok('Maintenance record opened.');
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.submitting.set(false);
    }
  }

  async closeMaintenanceRecordNow(assetId: string, recordId: string): Promise<void> {
    this.submitting.set(true);
    try {
      await this.api.closeMaintenanceRecord(assetId, recordId);
      await this.loadMaintenance(assetId);
      this.toasts.ok('Maintenance record closed.');
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.submitting.set(false);
    }
  }

  // --- Row verbs — custody/inventory actions (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3/§3.4) ----

  /**
   * Splices one asset's freshly-returned `AssetDetails` back into the loaded list **and** the
   * on-selection detail cache — every mutation below reflects immediately without a full-fleet
   * re-fetch, and an open detail pane never shows a pre-mutation custody picture.
   *
   * **Merged, not replaced.** `AssetInventoryController#detailsResponse` deliberately omits
   * `firmware`/`totalFlightSeconds` (and, from W1, `deviceCount`) from a custody/inventory response
   * — it has no join to offer there, documented on `AssetSummary#firmware` itself. Overwriting the
   * row wholesale would therefore blank the Firmware/Hours/Links columns the moment somebody
   * pressed Issue; spreading over the previous record keeps the last honest value for exactly the
   * fields this response says nothing about, while every field it *does* carry wins.
   */
  private patchAsset(updated: AssetDetails): void {
    this.assets.update((list) => list.map((asset) => (asset.assetId === updated.assetId ? { ...asset, ...updated } : asset)));
    this.detailsByAssetId.update((map) => {
      const merged = { ...(map.get(updated.assetId) ?? {}), ...updated };
      return new Map(map).set(updated.assetId, merged);
    });
  }

  async issueTo(assetId: string, custodianId: string, location: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      const updated = await this.api.setAssetCustody(assetId, { action: 'ISSUE', custodianId, location: location.trim() || undefined });
      this.patchAsset(updated);
      this.toasts.ok(`Issued "${updated.displayName}".`);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  async returnAsset(assetId: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      const updated = await this.api.setAssetCustody(assetId, { action: 'RETURN' });
      this.patchAsset(updated);
      this.toasts.ok(`Returned "${updated.displayName}" to stock.`);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  async ground(assetId: string, kind: MaintenanceKind, summary: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      const updated = await this.api.setAssetInventory(assetId, { action: 'GROUND', kind, summary });
      this.patchAsset(updated);
      this.toasts.ok(`Grounded "${updated.displayName}".`);
      if (this.selectedId() === assetId) {
        await this.loadMaintenance(assetId);
      }
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  async release(assetId: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      const updated = await this.api.setAssetInventory(assetId, { action: 'RELEASE' });
      this.patchAsset(updated);
      this.toasts.ok(`Released "${updated.displayName}" back to stock.`);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  async retire(assetId: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      const updated = await this.api.setAssetInventory(assetId, { action: 'RETIRE' });
      this.patchAsset(updated);
      this.toasts.ok(`Retired "${updated.displayName}".`);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  async restoreAssetNow(assetId: string, displayName: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      await this.api.setAssetState(assetId, RESTORE_TARGET_STATE);
      this.toasts.ok(`Restored "${displayName}".`);
      await this.loadAll();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  /** Archive executes immediately, no confirm dialog (docs/plans/done/UX-REWORK-PLAN.md §U-a2 item
   *  3b — "Undo over confirm"), mirroring `AssetsFacade#archiveAssetNow` exactly. */
  async archiveNow(row: VehicleRow): Promise<void> {
    const assetId = row.asset.assetId;
    this.busyAssetId.set(assetId);
    try {
      const result = await this.api.deleteAsset(assetId);
      this.undoToast.showUndo(
        `Archived "${result.displayName}" — ${pluralize(result.devicesDeleted, 'device')} archived, ` +
          `${pluralize(result.usagesRetained, 'usage')} retained, ${pluralize(result.streamsStopped, 'stream')} stopped.`,
        () => void this.restoreAssetNow(assetId, result.displayName),
      );
      await Promise.all([this.fleet.refresh({ quiet: true }), this.loadAll()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  // --- CSV export (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's Export action) -------------------

  exportUrl(): string {
    return this.api.inventoryExportUrl('csv');
  }

  exportFilename(): string {
    return inventoryExportFilename(Date.now());
  }

  // --- Load ---------------------------------------------------------------------------------------

  /**
   * The page's whole fleet read — **five requests, flat, regardless of fleet size**
   * (docs/plans/active/INVENTORY-REWORK-PLAN.md wave W3, context §3 defect D: this used to be
   * `5 + one GET /api/assets/{id} per asset`, 25 requests for the dev fleet's 20 vehicles, every one
   * of them re-fetching data `GET /api/assets` had already returned). Per-asset details now load on
   * selection ({@link ensureDetails}).
   *
   * `GET /api/users` is skipped entirely for an `ASSIGNED_ASSETS` scope — a pilot-only session, for
   * which `UserAdminController` answers `[]` by design (context §3). It is a *fallback* join now
   * anyway: names travel on the wire (`AssetCustody#custodianName`, D3), so this only backfills a
   * pre-W1 backend, and it is allowed to fail without failing the page.
   */
  async loadAll(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [summaries, categories, users, readiness, summary] = await Promise.all([
        this.showArchived() ? this.fleet.listAssetsIncludingArchived() : this.api.listAssets(),
        this.api.listCategories(),
        this.loadUserNames(),
        this.api.fleetReadiness(),
        this.api.fleetSummary(),
      ]);
      if (summaries) {
        this.assets.set(summaries);
      }
      this.categories.set(categories);
      this.users.set(users);
      this.readinessByAssetId.set(new Map(readiness.assets.map((row) => [row.assetId, row])));
      this.fleetSummaryData.set(summary);
      // A refresh must not leave a stale device/usage picture behind the pane; re-fetch only the one
      // asset that is actually open, if any.
      this.detailsByAssetId.set(new Map());
      const selected = this.selectedId();
      if (selected) {
        void this.ensureDetails(selected);
      }
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** See {@link loadAll} — `[]` for a pilot (no request at all), `[]` again if the listing fails. */
  private loadUserNames(): Promise<readonly UserSummary[]> {
    if (this.auth.scopeKind() === 'ASSIGNED_ASSETS') {
      return Promise.resolve([]);
    }
    return this.api.listUsers().catch(() => []);
  }

  async toggleShowArchived(): Promise<void> {
    this.showArchived.update((value) => !value);
    await this.loadAll();
  }

  clearFilters(): void {
    this.searchQuery.set('');
    this.categoryFilter.set('');
    this.inventoryStateFilter.set('all');
    this.custodianFilter.set('');
    this.readinessFilter.set('all');
  }

  /** One-click quick-add with no hardware, straight from the empty state — mirrors
   *  `AssetsFacade#registerSimulator` exactly (that page is being deleted this wave; this is its
   *  replacement, not a second copy of a surviving one). */
  async registerSimulator(): Promise<void> {
    this.submitting.set(true);
    try {
      await this.fleet.register(buildSyntheticRegisterRequest(''));
      await this.loadAll();
    } finally {
      this.submitting.set(false);
    }
  }
}
