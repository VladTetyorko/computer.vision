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
  inventoryExportFilename,
  parseInventoryTab,
  visibleInventoryTabs,
  type InventoryTab,
} from '../../core/fleet/inventory-logic';
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
  Category,
  FleetSummary,
  MaintenanceKind,
  MaintenanceRecord,
  ReadinessVerdict,
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
 * **Firmware/Hours columns are always `'—'`** — see `vehicles-logic.ts`'s own module doc comment for
 * the two discrepancies this documents (no fleet-wide firmware or flight-hours source exists yet).
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
  readonly canManageOrg = computed(() => computeCanManageOrg(this.auth.user()?.topRole));
  readonly visibleTabs = computed(() => visibleInventoryTabs(this.canManageOrg()));

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
  private readonly assets = signal<readonly AssetDetails[]>([]);
  private readonly categories = signal<readonly Category[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  private readonly readinessByAssetId = signal<ReadonlyMap<string, ReadinessVerdict>>(new Map());
  private readonly fleetSummaryData = signal<FleetSummary | undefined>(undefined);

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
      buildVehicleRows(this.assets(), this.users(), this.readinessByAssetId(), Date.now()),
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

  constructor() {
    void this.loadAll();

    // The detail pane's Maintenance drawer only ever needs the *selected* asset's own records —
    // fetched on selection, cleared on deselection, never kept warm for the whole table (the same
    // "no fleet-wide maintenance endpoint" constraint `core/maintenance/maintenance-logic.ts`'s own
    // doc comment names for wave W7's page).
    effect(() => {
      const assetId = this.selectedId();
      if (assetId) {
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

  /** Splices one asset's freshly-returned `AssetDetails` back into the loaded list — every mutation
   *  below reflects immediately without a full-fleet re-fetch. */
  private patchAsset(updated: AssetDetails): void {
    this.assets.update((list) => list.map((asset) => (asset.assetId === updated.assetId ? updated : asset)));
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

  async loadAll(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [summaries, categories, users, readiness, summary] = await Promise.all([
        this.showArchived() ? this.fleet.listAssetsIncludingArchived() : this.api.listAssets(),
        this.api.listCategories(),
        this.api.listUsers(),
        this.api.fleetReadiness(),
        this.api.fleetSummary(),
      ]);
      if (summaries) {
        const details = await Promise.all(summaries.map((asset) => this.api.getAsset(asset.assetId)));
        this.assets.set(details);
      }
      this.categories.set(categories);
      this.users.set(users);
      this.readinessByAssetId.set(new Map(readiness.assets.map((row) => [row.assetId, row.verdict])));
      this.fleetSummaryData.set(summary);
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
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
