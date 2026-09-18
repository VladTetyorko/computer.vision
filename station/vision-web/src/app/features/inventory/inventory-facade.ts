import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { AuthFacade } from '../../core/auth/auth-facade';
import { canManageOrg as computeCanManageOrg } from '../../core/org/org-logic';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsFacade } from '../../core/settings/settings-facade';
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
import { creatorOwnershipGroup, custodianPickerGroups, type CustodianPickerGroups } from '../../core/org/pilot-logic';
import { InventoryViewStore } from './inventory-view-store';
import {
  defaultInventoryView,
  filterRowsByInventoryView,
  inventoryViewTiles,
  toggleInventoryView,
  type InventoryView,
  type InventoryViewSelection,
  type InventoryViewTile,
} from './inventory-page-logic';
import {
  buildVehicleRows,
  custodianFilterOptions,
  custodianLabel,
  filterVehicleRowsByArchived,
  filterVehicleRowsByCategory,
  filterVehicleRowsByConnected,
  filterVehicleRowsByCustodian,
  filterVehicleRowsByInventoryState,
  filterVehicleRowsByReadiness,
  filterVehicleRowsByRetired,
  findVehicleRowById,
  openMaintenanceSummary,
  searchVehicleRows,
  sortVehicleRowsByTriage,
  type CustodianOption,
  type OpenMaintenanceSummary,
  type VehicleInventoryStateFilter,
  type VehicleReadinessFilter,
  type VehicleRow,
} from './vehicles-logic';
import type {
  Assignment,
  AssetDetails,
  AssetSummary,
  AssignedPilot,
  AssignmentRole,
  Category,
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
 * **Four requests, not `5 + N`** (docs/plans/active/INVENTORY-REWORK-PLAN.md wave W3): {@link loadAll}
 * builds every row from `GET /api/assets` alone; the per-asset `GET /api/assets/{id}` runs only when
 * a row is *selected* ({@link ensureDetails}, cached per id), when the *Watch live* verb needs a
 * device id, or once after a custody/inventory write ({@link refreshAsset}).
 *
 * **The stats are the filter** (plan §5.1, D7, wave W4): {@link viewTiles} counts the five views over
 * the tab's rows *after* every other filter and *before* {@link view} narrows them, so a tile reading
 * `3` always yields exactly three rows. The pick persists per browser through `InventoryViewStore`.
 *
 * **Every verb on screen is one this session may actually use** — {@link actor} × {@link actionsFor}
 * (`core/fleet/inventory-logic.ts#vehicleRowActions`, plan §5.2). No template in this feature makes
 * its own authority decision.
 *
 * **"My vehicles" (plan §5.4, wave W5)** — a genuinely `ASSIGNED_ASSETS`-scoped session
 * (`showsManagerView() === false`) reads {@link myVehicleRows}/{@link myEquipmentRows}, deliberately
 * the *pre-view* rows (see their own doc comments for why), and {@link myRoleFor} for the one
 * additional per-asset fact the cards need that the table never did: this session's own
 * `AssignmentRole` on the asset, so a `CREW`-assigned card can never offer Fly.
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
  private readonly auth = inject(AuthFacade);
  private readonly settings = inject(SettingsFacade);
  private readonly viewStore = inject(InventoryViewStore);

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
  /** `GET /api/assets/{id}` responses fetched **on selection**, keyed by asset id — see {@link ensureDetails}. */
  private readonly detailsByAssetId = signal<ReadonlyMap<string, AssetDetails>>(new Map());

  /** `userId → displayName` for every user this session may list — the fallback join behind
   *  {@link custodianLabel} and the *opened by* name in the drawer's Maintenance line. Empty for a
   *  pilot's `ASSIGNED_ASSETS` scope, where `GET /api/users` is never issued (see {@link loadAll}). */
  readonly userNameById = computed<ReadonlyMap<string, string>>(
    () => new Map(this.users().map((user) => [user.userId, user.displayName])),
  );

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

  /**
   * How many of the three filters that now live behind the page bar's **More filters** disclosure
   * are actually narrowing the list (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.1, wave W4) — the
   * badge on the collapsed summary. Search and Category stay on the bar itself and are deliberately
   * *not* counted: a badge exists to say "something you cannot see right now is hiding rows", and
   * those two are always visible.
   */
  readonly moreFilterCount = computed(
    () =>
      (this.inventoryStateFilter() !== 'all' ? 1 : 0) +
      (this.custodianFilter().length > 0 ? 1 : 0) +
      (this.readinessFilter() !== 'all' ? 1 : 0),
  );

  readonly hasActiveFilters = computed(
    () => this.searchQuery().trim().length > 0 || this.categoryFilter().length > 0 || this.moreFilterCount() > 0,
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

  /**
   * Everything the tab's filters do **except** the view row — the row set the five view tiles count
   * over (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.1, D7). Counting here rather than over the
   * raw fleet is what makes the tiles trustworthy: a tile reading `3` always yields exactly three
   * rows when clicked, because it counted the same rows the click will leave standing.
   */
  private filteredRows(connected: boolean): readonly VehicleRow[] {
    const byConnected = filterVehicleRowsByConnected(this.allRows(), this.connectedCategorySlugs(), connected);
    const byArchived = filterVehicleRowsByArchived(byConnected, this.showArchived());
    const byRetired = filterVehicleRowsByRetired(byArchived, this.showRetired(), this.inventoryStateFilter());
    const byCategory = filterVehicleRowsByCategory(byRetired, this.categoryFilter() || undefined);
    const byState = filterVehicleRowsByInventoryState(byCategory, this.inventoryStateFilter());
    const byCustodian = filterVehicleRowsByCustodian(byState, this.custodianFilter() || undefined);
    const byReadiness = filterVehicleRowsByReadiness(byCustodian, this.readinessFilter());
    return searchVehicleRows(byReadiness, this.searchQuery());
  }

  private readonly preViewVehicleRows = computed<readonly VehicleRow[]>(() => this.filteredRows(true));
  private readonly preViewEquipmentRows = computed<readonly VehicleRow[]>(() => this.filteredRows(false));

  /** The tab's own pre-view rows — what the view tiles count, and what the view then narrows. */
  private readonly preViewActiveRows = computed<readonly VehicleRow[]>(() =>
    this.tab() === 'equipment' ? this.preViewEquipmentRows() : this.preViewVehicleRows(),
  );

  // --- The view row: five stats that are also the filter (INVENTORY-REWORK-PLAN.md §5.1, D7) ------

  /**
   * What this browser last chose — `undefined` until somebody chooses (or when storage is blocked),
   * which is what lets {@link view} fall through to §5.1's own default. Seeded once, from
   * `InventoryViewStore`, so a reload lands the operator back on the view they were working in.
   */
  private readonly viewSelection = signal<InventoryViewSelection>(this.viewStore.read());

  /**
   * The five stat tiles that *are* the view switcher — label, count and tone, computed over the
   * tab's own pre-view rows ({@link preViewActiveRows}) so every count matches what selecting it
   * shows. `inventory-page-logic.ts#inventoryViewTiles` owns the predicates; nothing here decides.
   */
  readonly viewTiles = computed<readonly InventoryViewTile[]>(() => inventoryViewTiles(this.preViewActiveRows()));

  private readonly needsAttentionCount = computed(
    () => this.viewTiles().find((tile) => tile.view === 'needs-attention')?.count ?? 0,
  );

  /**
   * The active view; `null` is All. Deliberately *not* a computed that keeps re-deriving §5.1's
   * default — that would move the table under whoever is reading it every time a vehicle went NO_GO.
   * The default is latched exactly once, by {@link latchDefaultView}, when the first fleet read lands
   * with nothing yet chosen.
   */
  readonly view = computed<InventoryView | null>(() => this.viewSelection() ?? null);

  /**
   * §5.1's opening view — **Needs attention while anything is in it, All otherwise** — applied once,
   * only for a session that has never picked one (a fresh browser, or one whose storage is blocked).
   * The latch is not written back to storage: a default this page chose is not a preference the
   * operator expressed, and persisting it would make the very next load look like a deliberate pick.
   */
  private latchDefaultView(): void {
    if (this.viewSelection() === undefined) {
      this.viewSelection.set(defaultInventoryView(this.needsAttentionCount()));
    }
  }

  /** Clicking a tile selects it; clicking the selected one deselects back to All. Persisted either way. */
  selectView(view: InventoryView): void {
    const next = toggleInventoryView(this.view(), view);
    this.viewSelection.set(next);
    this.viewStore.write(next);
  }

  readonly vehicleRows = computed<readonly VehicleRow[]>(() => filterRowsByInventoryView(this.preViewVehicleRows(), this.view()));
  readonly equipmentRows = computed<readonly VehicleRow[]>(() => filterRowsByInventoryView(this.preViewEquipmentRows(), this.view()));

  /** The currently-active tab's own row set — what the visible `<vision-vehicles-table>` renders. */
  readonly activeRows = computed<readonly VehicleRow[]>(() => (this.tab() === 'equipment' ? this.equipmentRows() : this.vehicleRows()));

  /**
   * "My vehicles" cards' own row sets (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.4, wave W5) —
   * deliberately the **pre-view** rows ({@link preViewVehicleRows}/{@link preViewEquipmentRows}),
   * never {@link vehicleRows}/{@link equipmentRows}. The view row (Needs attention/In field/…) is a
   * manager-only control, rendered only inside `showsManagerView()` in `inventory.html` — a pilot or
   * crew session has no way to see or change it, yet `view()`'s own default-view latch
   * (`latchDefaultView`) still runs unconditionally in {@link loadAll}. Reading the post-view signals
   * here would silently hide a pilot's own *fine* vehicles the moment any one of theirs needed
   * attention (the tile that would explain why is a control they never see), and worse, `view()` is
   * remembered per **browser** (`InventoryViewStore`), not per session — a manager's last pick on a
   * shared station would leak into whatever a pilot logging in next sees. Search/category still
   * apply (both tabs' filter pipeline runs before the view split), so the page bar's search box
   * (§5's own gate) narrows these exactly as it narrows the manager's table.
   */
  readonly myVehicleRows = computed<readonly VehicleRow[]>(() => this.preViewVehicleRows());
  readonly myEquipmentRows = computed<readonly VehicleRow[]>(() => this.preViewEquipmentRows());

  /** The page bar's own count for a "My vehicles" session — vehicles **and** equipment together, since both render as one stacked card list (§5.4), not a tab switch. */
  readonly myRowsCount = computed(() => this.myVehicleRows().length + this.myEquipmentRows().length);

  /**
   * Whether this session gets the manager's table at all (docs/plans/active/INVENTORY-REWORK-PLAN.md
   * §4, wave W5's slot). A `MANAGE_FLEET` holder always does; so does anyone whose visibility scope
   * is wider than their own assignments (a viewer reads the same table, read-only — the verb matrix
   * already leaves their kebab with nothing loud in it). Only a genuinely `ASSIGNED_ASSETS`-scoped
   * pilot falls through to the "My vehicles" cards W5 builds — for them a dense eleven-column
   * inventory table of the two aircraft they fly is the wrong shape entirely.
   */
  readonly showsManagerView = computed(() => this.actor().canManageFleet || this.auth.scopeKind() !== 'ASSIGNED_ASSETS');

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

  /**
   * The session's own ownership group — the best stand-in this client has for *the asset's* group,
   * which never reaches the wire (`AssetSummaryResponse.owner` is the owning **user** id; nothing
   * serialises `Ownership#groupId`). `core/org/pilot-logic.ts#custodianPickerGroups` is built around
   * exactly that limitation: an unresolved or mismatched group costs a *less sorted* picker, never a
   * shorter one.
   */
  readonly ownershipGroupId = computed<string | undefined>(
    () => creatorOwnershipGroup(this.auth.user()?.memberships ?? [])?.groupId,
  );

  /**
   * The Issue dialog's grouped picker for one asset — **Assigned pilots** → **Other pilots** →
   * everyone else (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.5; context §3 defect E was a flat
   * 36-row directory). The grouping rule itself lives in `core/org/pilot-logic.ts`, lifted there from
   * the onboarding wizard's Hand-over step so both pickers answer "is Anna a pilot?" identically.
   *
   * Reads the asset's pilots out of the same cache the drawer's *Pilots* section uses
   * ({@link pilotsFor}) — the dialog calls {@link ensurePilots} when it opens, and renders the
   * two-group shape as soon as that lands. Until then "Assigned pilots" is simply empty and everyone
   * is reachable under the other two headings: never a blocked dialog.
   */
  custodianGroupsFor(assetId: string | undefined): CustodianPickerGroups {
    return custodianPickerGroups({
      users: this.users(),
      assignedPilots: assetId ? this.pilotsFor(assetId) : [],
      groupId: this.ownershipGroupId(),
    });
  }

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

  /** The one open record the drawer names, already rendered (`vehicles-logic.ts#openMaintenanceSummary`);
   *  `undefined` when nothing is open — the drawer then says "No open records" rather than listing a
   *  closed record's history, which belongs on the full asset page. */
  readonly openMaintenance = computed<OpenMaintenanceSummary | undefined>(() =>
    openMaintenanceSummary(this.maintenanceRecords(), this.userNameById(), Date.now()),
  );

  // --- Pilots, on selection (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.3's `Pilots` section) ---

  /**
   * `GET /api/assets/{id}/pilots` responses, keyed by asset id — cached exactly like
   * {@link detailsByAssetId}. **`null` is a third state, not an empty list**: it records that the
   * read was attempted and failed, so the drawer can say "couldn't load" instead of the *claim*
   * "nobody is assigned to fly this", which a failed request has no standing to make.
   */
  private readonly pilotsByAssetId = signal<ReadonlyMap<string, readonly AssignedPilot[] | null>>(new Map());
  readonly loadingPilots = signal(false);

  /** One asset's assigned pilots as far as this session knows them — `[]` while unfetched or if the read failed. */
  pilotsFor(assetId: string): readonly AssignedPilot[] {
    return this.pilotsByAssetId().get(assetId) ?? [];
  }

  /** The open drawer's own pilots — `[]` with nothing selected, so the section renders its empty line, never a spinner that never ends. */
  readonly selectedPilots = computed<readonly AssignedPilot[]>(() => {
    const id = this.selectedId();
    return id ? this.pilotsFor(id) : [];
  });

  /** Whether the open drawer's pilot read actually failed — see {@link pilotsByAssetId}'s third state. */
  readonly selectedPilotsUnavailable = computed(() => {
    const id = this.selectedId();
    return id ? this.pilotsByAssetId().get(id) === null : false;
  });

  /**
   * One asset's pilot list, fetched at most once per id and reused thereafter. The read is
   * scope-gated only (`AssignmentController#pilots` 404s an out-of-scope asset and requires no
   * capability), so a pilot sees the crew of the aircraft they fly. A failure is recorded as `null`
   * and degrades to a "couldn't load" line — never a blocked drawer and never an invented roster.
   * {@link invalidatePilots} drops one entry after an Issue, whose server side also grants a seat.
   */
  async ensurePilots(assetId: string): Promise<void> {
    if (this.pilotsByAssetId().has(assetId)) {
      return;
    }
    this.loadingPilots.set(true);
    try {
      const pilots = await this.api.listAssetPilots(assetId);
      this.pilotsByAssetId.update((map) => new Map(map).set(assetId, pilots));
    } catch {
      this.pilotsByAssetId.update((map) => new Map(map).set(assetId, null));
    } finally {
      this.loadingPilots.set(false);
    }
  }

  private async invalidatePilots(assetId: string): Promise<void> {
    this.pilotsByAssetId.update((map) => {
      const next = new Map(map);
      next.delete(assetId);
      return next;
    });
    await this.ensurePilots(assetId);
  }

  // --- My own assignments (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.4, wave W5) --------------

  /** `GET /api/me/assignments` by asset id — the acting user's **own** `AssignmentRole`, read once
   *  for a genuinely `ASSIGNED_ASSETS`-scoped session (a pilot/crew member; see {@link loadMyAssignments}).
   *  A manager/viewer session never reads this — they never render "My vehicles" cards at all. */
  private readonly myAssignmentRoleByAssetId = signal<ReadonlyMap<string, AssignmentRole>>(new Map());

  /** This session's own `AssignmentRole` for one asset — `undefined` when unassigned or not yet
   *  loaded. "My vehicles" cards read this to decide the crew-seat gate (§5.4: "Crew never gets
   *  Fly") — `my-vehicles-logic.ts#myVehicleActions`. */
  myRoleFor(assetId: string): AssignmentRole | undefined {
    return this.myAssignmentRoleByAssetId().get(assetId);
  }

  /**
   * Self-scoped, `@OpenByDesign` (INVENTORY-REWORK-PLAN.md §6) — read once per {@link loadAll},
   * **only** for a session whose visibility scope is `ASSIGNED_ASSETS` (the only persona "My
   * vehicles" ever renders for; a manager/viewer's own assignments, if any, are not this page's
   * concern). Not part of the four-request `Promise.all` in {@link loadAll}: it is a fifth request
   * that exists for a different persona than the one that comment describes, and keeping it separate
   * means a slow/failed assignments read can never hold up the manager table's own load. A failure
   * degrades to an empty map — every card then falls through to the plain pilot verb set, never a
   * blocked page.
   */
  private async loadMyAssignments(): Promise<void> {
    if (this.auth.scopeKind() !== 'ASSIGNED_ASSETS') {
      return;
    }
    try {
      const assignments = await this.api.myAssignments();
      this.myAssignmentRoleByAssetId.set(new Map(assignments.map((assignment: Assignment) => [assignment.assetId, assignment.role])));
    } catch {
      this.myAssignmentRoleByAssetId.set(new Map());
    }
  }

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
        void this.ensurePilots(assetId);
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

  // The drawer's own "open a record" form is gone (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.3,
  // wave W4): grounding a vehicle already *is* opening a record, through the Ground modal that names
  // a kind and a reason and moves the vehicle's state with it. A second, near-identical form in the
  // drawer offered a record that changed nothing and a `NOTE` kind nothing on this page could read —
  // so `createMaintenanceRecord` is no longer called from Inventory at all (the full asset page keeps
  // its own history editor). Closing one stays here: it is the counterpart to Release.

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

  /**
   * One asset's full record, re-read after a custody/inventory write and spliced back in
   * (docs/plans/active/INVENTORY-REWORK-PLAN.md wave W1's wire note). {@link patchAsset} alone is not
   * enough for the *nested* records: `AssetInventoryController#detailsResponse` builds its
   * `CustodyResponse` without the name join, so the object that replaces `custody` carries a
   * `custodianId` and **no `custodianName`** — the row would fall straight back to a truncated id the
   * instant somebody pressed Issue, on a station where the fleet listing had just rendered the real
   * name. One `GET /api/assets/{id}` per action, on the one asset that changed, restores it.
   *
   * A failed re-read is silent on purpose: the mutation itself succeeded and is already reflected;
   * the row simply keeps the shorter-labelled version until the next refresh, which is a degraded
   * label, not a wrong one.
   */
  private async refreshAsset(assetId: string): Promise<void> {
    try {
      this.patchAsset(await this.api.getAsset(assetId));
    } catch {
      // Keep the mutation response's own picture — see above.
    }
  }

  /**
   * Issue (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.5) — custody **and**, server-side, a `PILOT`
   * assignment. The confirmation says so only when it is true: W1's `HandoverService` skips the grant
   * whenever the asset already has any seat, so this compares the asset's pilot list either side of
   * the write and appends `· assigned as pilot` only when a `PILOT` seat genuinely appeared for this
   * person. Undo returns it to stock — the reverse of the custody half; a seat the server granted
   * stays granted, which is why the toast never promises to take one back.
   */
  async issueTo(assetId: string, custodianId: string, location: string): Promise<void> {
    this.busyAssetId.set(assetId);
    await this.ensurePilots(assetId);
    const hadSeat = this.pilotsFor(assetId).some((pilot) => pilot.userId === custodianId && pilot.role === 'PILOT');
    try {
      const updated = await this.api.setAssetCustody(assetId, { action: 'ISSUE', custodianId, location: location.trim() || undefined });
      this.patchAsset(updated);
      await Promise.all([this.refreshAsset(assetId), this.invalidatePilots(assetId)]);
      const gotSeat = !hadSeat && this.pilotsFor(assetId).some((pilot) => pilot.userId === custodianId && pilot.role === 'PILOT');
      const name = this.custodianNameFor(assetId, custodianId);
      this.undoToast.showUndo(`Issued to ${name}${gotSeat ? ' · assigned as pilot' : ''}`, () => void this.returnAsset(assetId, { quiet: true }));
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  /** The name a custody confirmation calls somebody — the wire's own resolved name, else the user-list
   *  join, else a truncated id (`vehicles-logic.ts#custodianLabel`, the exact rule the table cell uses). */
  private custodianNameFor(assetId: string, custodianId: string): string {
    const custody = this.assets().find((asset) => asset.assetId === assetId)?.custody;
    const resolved = custody?.custodianId === custodianId ? custody : { custodianId };
    return custodianLabel(resolved, this.userNameById()).name ?? custodianId;
  }

  /**
   * Return to stock. Undo re-issues to **the same person, at the same location** — captured before
   * the write, since the response deliberately clears both. An asset that somehow had no custodian
   * recorded gets a plain toast with nothing to undo: offering an Undo that would re-issue to nobody
   * is worse than offering none.
   *
   * `quiet` suppresses the toast for the one caller that already has one on screen — Issue's own
   * Undo, which must not answer a click on "Undo" by opening a second undo window offering to redo it.
   */
  async returnAsset(assetId: string, options?: { readonly quiet?: boolean }): Promise<void> {
    this.busyAssetId.set(assetId);
    const previous = this.assets().find((asset) => asset.assetId === assetId)?.custody;
    try {
      const updated = await this.api.setAssetCustody(assetId, { action: 'RETURN' });
      this.patchAsset(updated);
      await this.refreshAsset(assetId);
      if (options?.quiet) {
        return;
      }
      const custodianId = previous?.custodianId;
      if (custodianId) {
        this.undoToast.showUndo('Returned to stock', () => void this.issueTo(assetId, custodianId, previous?.location ?? ''));
      } else {
        this.toasts.ok('Returned to stock.');
      }
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
      await this.refreshAsset(assetId);
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
      await this.refreshAsset(assetId);
      this.toasts.ok(`Released "${updated.displayName}" back to stock.`);
      if (this.selectedId() === assetId) {
        await this.loadMaintenance(assetId);
      }
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
      await this.refreshAsset(assetId);
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

  /**
   * **Whole scope, always.** `InventoryExportController` takes exactly one parameter — `format` — and
   * exports every asset the session may see; there is no filter to pass, so passing one would be a
   * lie told by a query string. Rather than silently exporting more than the screen shows, the button
   * says what it does: `Export all (CSV)` (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.1, wave W4).
   * A filtered export needs a server change, and is not one this wave invents client-side.
   */
  exportUrl(): string {
    return this.api.inventoryExportUrl('csv');
  }

  exportFilename(): string {
    return inventoryExportFilename(Date.now());
  }

  // --- Load ---------------------------------------------------------------------------------------

  /**
   * The page's whole fleet read — **four requests, flat, regardless of fleet size**
   * (docs/plans/active/INVENTORY-REWORK-PLAN.md wave W3, context §3 defect D: this used to be
   * `5 + one GET /api/assets/{id} per asset`, 25 requests for the dev fleet's 20 vehicles, every one
   * of them re-fetching data `GET /api/assets` had already returned). Per-asset details now load on
   * selection ({@link ensureDetails}).
   *
   * `GET /api/fleet/summary` is gone with the KPI strip it fed (wave W4, D7): the five tiles above
   * the table are now views over *these* rows, so a fleet-wide count computed a second time
   * server-side could only disagree with what the table underneath it shows.
   *
   * `GET /api/users` is skipped entirely for an `ASSIGNED_ASSETS` scope — a pilot-only session, for
   * which `UserAdminController` answers `[]` by design (context §3). It is a *fallback* join now
   * anyway: names travel on the wire (`AssetCustody#custodianName`, D3), so this only backfills a
   * pre-W1 backend, and it is allowed to fail without failing the page.
   *
   * **A fifth, persona-scoped request rides alongside these four, not inside their `Promise.all`**
   * (wave W5): {@link loadMyAssignments} — `GET /api/me/assignments` — fires only for an
   * `ASSIGNED_ASSETS` session ("My vehicles" cards' own crew-seat gate), fire-and-forget, so it can
   * never slow down or fail the manager table's own four-request load.
   */
  async loadAll(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [summaries, categories, users, readiness] = await Promise.all([
        this.showArchived() ? this.fleet.listAssetsIncludingArchived() : this.api.listAssets(),
        this.api.listCategories(),
        this.loadUserNames(),
        this.api.fleetReadiness(),
      ]);
      if (summaries) {
        this.assets.set(summaries);
      }
      this.categories.set(categories);
      this.users.set(users);
      this.readinessByAssetId.set(new Map(readiness.assets.map((row) => [row.assetId, row])));
      this.latchDefaultView();
      void this.loadMyAssignments();
      // A refresh must not leave a stale device/usage/pilot picture behind the pane; re-fetch only
      // the one asset that is actually open, if any.
      this.detailsByAssetId.set(new Map());
      this.pilotsByAssetId.set(new Map());
      const selected = this.selectedId();
      if (selected) {
        void this.ensureDetails(selected);
        void this.ensurePilots(selected);
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
