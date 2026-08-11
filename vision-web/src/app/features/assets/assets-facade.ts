import { Injectable, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { describeHttpError } from '../../core/api-error';
import { buildSyntheticRegisterRequest } from '../../core/fleet/simulation-logic';
import { deriveCategoryOptions, type CategoryOption } from '../../core/fleet/category-logic';
import { RESTORE_TARGET_STATE } from '../../core/fleet/warehouse-logic';
import { pluralize } from '../../shared/ui/page-bar/page-bar';
import type { AssetDetails } from '../../core/api/models';
import {
  buildAssetListRows,
  filterAssetListRowsByArchived,
  filterAssetListRowsByCategory,
  filterAssetListRowsByStatus,
  filterAssetListRowsByStreaming,
  findAssetRowById,
  searchAssetListRowsByName,
  type AssetListRow,
  type AssetStatusFilter,
  type AssetStreamingFilter,
} from './assets-logic';

/**
 * `AssetsPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — owns every store/service injection, the
 * search/filter read-model, and every command for the `/assets` grid, so the page component itself
 * only injects this class. Every filter/search signal below is public and directly settable from the
 * template (`[ngModel]="facade.searchQuery()" (ngModelChange)="facade.searchQuery.set($event)"`) —
 * the same convention `core/settings/settings-store.ts` already uses for its own plain fields — since
 * `assetRows` (the grid's one read-model) has to derive from all of them plus `assets`/`fleet` as a
 * single source, per the plan's own "derived from a SINGLE source, never duplicated per component"
 * rule. `showArchived` in particular is named explicitly by the plan as filter/toggle state that
 * "moves into its feature facade, out of the component" even though it isn't mutually-exclusive
 * overlay state (so it does **not** go through `UiStore`).
 *
 * **Wave 3 addition (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4, docs/extracts/design/04-assets.md)**: the `?sel=`-
 * addressable two-pane selection (`selectedId`/`selectedRow`/`selectRow`/`clearSelection`) and the
 * detail panel's "Open cockpit" action (`openCockpitFor`) — everything else below predates this wave
 * and, per the doc comment further down, carries over byte-for-byte.
 */
@Injectable()
export class AssetsFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);
  private readonly router = inject(Router);
  /** Scoped to this page's own route, since `AssetsFacade` is provided in `AssetsPage`'s own
   *  `providers` array — the same injector that resolves `ActivatedRoute` for the component itself.
   *  Only used as `selectRow`/`clearSelection`'s `relativeTo` anchor (docs/plans/done/NAV-IA-REDESIGN-PLAN.md
   *  §2.4) so `router.navigate([], …)` patches `?sel=` on the current URL rather than resolving `[]`
   *  against the router root. */
  private readonly route = inject(ActivatedRoute);
  private readonly settings = inject(SettingsStore);

  readonly fleet = inject(FleetStore);

  readonly submitting = signal(false);
  readonly busyAssetId = signal<string | null>(null);

  readonly showArchived = signal(false);
  /** Every asset the page has loaded, as full `AssetDetails` — the grid needs each asset's resolved
   *  device count/watch-target (`buildAssetListRows`). */
  readonly assets = signal<readonly AssetDetails[]>([]);

  // --- Search + filters ------------------------------------------------------------------------
  readonly searchQuery = signal('');
  readonly categoryFilter = signal('');
  readonly statusFilter = signal<AssetStatusFilter>('all');
  readonly streamingFilter = signal<AssetStreamingFilter>('all');

  readonly categoryOptions = computed<readonly CategoryOption[]>(() => {
    const base = deriveCategoryOptions(this.assets());
    const current = this.categoryFilter();
    // A deep-linked/previously-picked category slug with zero currently-loaded assets still gets
    // its own <option> — the select always reflects the actual active filter, never silently
    // resets to "All categories" just because nothing currently matches it.
    if (current && !base.some((option) => option.slug === current)) {
      return [...base, { slug: current, name: current }].sort((a, b) => a.name.localeCompare(b.name));
    }
    return base;
  });

  readonly hasActiveFilters = computed(
    () =>
      this.searchQuery().trim().length > 0 ||
      this.categoryFilter().length > 0 ||
      this.statusFilter() !== 'all' ||
      this.streamingFilter() !== 'all',
  );

  /**
   * Every loaded asset as a row, before any search/filter narrows it — `assetRows` (the grid) and
   * `selectedRow` (the two-pane detail panel) both read this rather than each re-deriving from
   * `assets()`/`fleet.liveDeviceIds()` independently, so a row object picked out of one is
   * reference-equal to the one found in the other.
   */
  private readonly allRows = computed<readonly AssetListRow[]>(() => buildAssetListRows(this.assets(), this.fleet.liveDeviceIds()));

  /**
   * The grid's rows: every filter narrows in sequence (archived → category → status → streaming →
   * name search) — order doesn't change the result, each is a plain array filter, but this is the
   * order the filter toolbar reads left to right.
   */
  readonly assetRows = computed<readonly AssetListRow[]>(() => {
    const rows = filterAssetListRowsByArchived(this.allRows(), this.showArchived());
    const byCategory = filterAssetListRowsByCategory(rows, this.categoryFilter() || undefined);
    const byStatus = filterAssetListRowsByStatus(byCategory, this.statusFilter());
    const byStreaming = filterAssetListRowsByStreaming(byStatus, this.streamingFilter());
    return searchAssetListRowsByName(byStreaming, this.searchQuery());
  });

  /** `true` once at least one asset has loaded — distinguishes "no assets exist yet" from "filters
   *  matched nothing" for the empty state (never a fabricated "no assets" when the fleet has some). */
  readonly hasAnyAssets = computed(() => this.assets().length > 0);

  // --- Two-pane selection (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4, docs/extracts/design/04-assets.md) ----------
  // `AssetsPage`'s own constructor `effect()` forwards its route-bound `sel` input straight into this
  // signal on every change — the same "only a component can receive a route input" split `category`
  // above already documents. Read against `allRows`, not the filtered `assetRows`, so narrowing the
  // search/filters never silently closes an already-open selection out from under the user.
  readonly selectedId = signal<string | undefined>(undefined);
  readonly selectedRow = computed<AssetListRow | undefined>(() => findAssetRowById(this.allRows(), this.selectedId()));

  /** A row was clicked/activated — opens the detail panel and mirrors the choice into `?sel=` so it
   *  survives refresh, Back and sharing. `replaceUrl: true` (not a new history entry per click) — the
   *  same choice `SettingsStore`'s own persisted-preference writes make, since row selection is
   *  browsing, not navigation Back should undo one step at a time for. */
  selectRow(assetId: string): void {
    this.selectedId.set(assetId);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { sel: assetId }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  /** The pane's close button / Esc / scrim click (`TwoPane`'s own `detailClose` output) — the pane
   *  never closes itself, so every dismissal path reaches here. */
  clearSelection(): void {
    this.selectedId.set(undefined);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { sel: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  /**
   * The detail panel's "Open cockpit" action — identical to `asset-detail-facade.ts#openCockpit`
   * (the asset detail page's own cockpit-link band): remembers this asset as Fly's active pick
   * (`SettingsStore.flyAssetId`, the same field `FlyPage#selectAsset` writes) so `/fly` lands
   * directly in the cockpit for it, then navigates. Works whether or not the asset is streaming.
   */
  openCockpitFor(assetId: string): void {
    this.settings.flyAssetId.set(assetId);
    void this.router.navigate(['/fly']);
  }

  constructor() {
    void this.refreshAssets();
  }

  /**
   * `?category=<slug>` (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2/QF-3) — the page's own constructor `effect()`
   * forwards its route-bound `category` input straight into {@link categoryFilter} on every change
   * (only a component can receive a route input, so that wiring stays there — see `assets.ts`'s own
   * doc comment); `currentCategory` is passed in imperatively here only to decide whether Clear
   * filters also needs to navigate away from the `?category=` URL, mirroring the pre-facade page's
   * own `if (this.category())` check exactly.
   */
  clearFilters(currentCategory: string | undefined): void {
    this.searchQuery.set('');
    this.categoryFilter.set('');
    this.statusFilter.set('all');
    this.streamingFilter.set('all');
    if (currentCategory) {
      void this.router.navigate(['/assets']);
    }
  }

  /** "+ Add source" (docs/plans/done/UX-REWORK-PLAN.md §U-d) — the onboarding wizard is the only way in now. */
  goToAddSource(): Promise<boolean> {
    return this.router.navigate(['/add-source']);
  }

  watchAsset(row: AssetListRow): Promise<boolean> | undefined {
    return row.watchDeviceId ? this.router.navigate(['/live', row.watchDeviceId]) : undefined;
  }

  /**
   * "Open full ›" (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4, docs/extracts/design/04-assets.md) — the two-pane
   * detail panel's own escape hatch to `/assets/:id` for deep work (rename, KPIs, recent flights,
   * pilots). Selecting a row itself (`selectRow`, above) deliberately does **not** navigate any more
   * — this is the one action that still does, kept named/shaped exactly as it was pre-Wave-3 so its
   * one remaining call site (the panel's "Open full ›" link) needs no behavior change, only a new
   * place to live.
   */
  openAsset(row: AssetListRow): Promise<boolean> {
    return this.router.navigate(['/assets', row.asset.assetId]);
  }

  onAssetAction(row: AssetListRow, action: 'archive' | 'restore'): void {
    if (action === 'archive') {
      void this.archiveAssetNow(row);
    } else {
      void this.restoreAssetNow(row.asset.assetId, row.asset.displayName);
    }
  }

  /**
   * Archive executes immediately, no confirm dialog (docs/plans/done/UX-REWORK-PLAN.md §U-a2 item 3b —
   * "Undo over confirm"). Calls `VisionApi.deleteAsset` directly rather than `FleetStore.deleteAsset`
   * — that method's own `run()`-wrapped success toast has no Undo action, so this page fires its own
   * `UndoToastService` toast instead (docs/plans/done/OPS-CORE-PLAN.md §Q2).
   */
  async archiveAssetNow(row: AssetListRow): Promise<void> {
    const assetId = row.asset.assetId;
    this.busyAssetId.set(assetId);
    try {
      const result = await this.api.deleteAsset(assetId);
      this.undoToast.showUndo(
        `Archived "${result.displayName}" — ${pluralize(result.devicesDeleted, 'device')} archived, ` +
          `${pluralize(result.usagesRetained, 'usage')} retained, ${pluralize(result.streamsStopped, 'stream')} stopped.`,
        () => void this.undoArchiveAsset(assetId, result.displayName, result.devicesDeleted),
      );
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshAssets()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  /**
   * The explicit "Restore asset" kebab entry (for an asset currently shown via "Show archived")
   * calls this — the asset's own lifecycle only, same as before the split.
   */
  async restoreAssetNow(assetId: string, displayName: string): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      await this.api.setAssetState(assetId, RESTORE_TARGET_STATE);
      this.toasts.ok(`Restored "${displayName}".`);
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshAssets()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyAssetId.set(null);
    }
  }

  /**
   * The Undo action fired from `archiveAssetNow`'s own toast: restores the asset first, then
   * restores every device Archive's own cascade left `DELETED` (`devicesArchived`, captured at the
   * moment of *this* archive). Tolerates a partial device-restore failure — the asset itself is not
   * rolled back — with a toast naming exactly what happened.
   */
  async undoArchiveAsset(assetId: string, displayName: string, devicesArchived: number): Promise<void> {
    this.busyAssetId.set(assetId);
    try {
      await this.api.setAssetState(assetId, RESTORE_TARGET_STATE);
    } catch (error) {
      this.busyAssetId.set(null);
      this.toasts.error(describeHttpError(error));
      return;
    }

    let devicesRestored = 0;
    let devicesFailed = 0;
    if (devicesArchived > 0) {
      try {
        const asset = await this.api.getAsset(assetId);
        const archivedDevices = asset.devices.filter((device) => device.state === 'DELETED');
        const outcomes = await Promise.allSettled(archivedDevices.map((device) => this.api.setDeviceState(device.id, RESTORE_TARGET_STATE)));
        devicesRestored = outcomes.filter((outcome) => outcome.status === 'fulfilled').length;
        devicesFailed = outcomes.length - devicesRestored;
      } catch {
        // Couldn't even fetch the asset's own device list — the asset itself is still restored;
        // its devices stay archived, still restorable from the Devices page.
        devicesFailed = devicesArchived;
      }
    }

    await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshAssets()]);
    this.busyAssetId.set(null);

    if (devicesFailed > 0) {
      const succeeded = devicesRestored > 0 ? ` (${devicesRestored} succeeded)` : '';
      this.toasts.error(
        `Restored "${displayName}", but ${devicesFailed} of its ${pluralize(devicesArchived, 'device')} failed to ` +
          `restore${succeeded} — retry from Devices.`,
      );
    } else if (devicesRestored > 0) {
      this.toasts.ok(`Restored "${displayName}" and ${pluralize(devicesRestored, 'device')}.`);
    } else {
      this.toasts.ok(`Restored "${displayName}".`);
    }
  }

  /**
   * Loads every asset (respecting `showArchived`) plus its resolved devices, then stores the full
   * `AssetDetails` list the grid needs (`buildAssetListRows`). Best-effort, like `TelemetryStore`'s
   * own asset lookups: this is enrichment for already-visible rows, not a user-initiated action, so
   * a failure degrades silently rather than raising a toast.
   */
  async refreshAssets(): Promise<void> {
    try {
      const summaries = this.showArchived() ? await this.fleet.listAssetsIncludingArchived() : await this.api.listAssets();
      if (!summaries) {
        return; // failure already toasted by FleetStore.run() (only reachable when showArchived)
      }
      const details = await Promise.all(summaries.map((asset) => this.api.getAsset(asset.assetId)));
      this.assets.set(details);
    } catch {
      // Silent-degrade — see doc comment above.
    }
  }

  async toggleShowArchived(): Promise<void> {
    this.showArchived.update((value) => !value);
    await this.refreshAssets();
  }

  async refreshAll(): Promise<void> {
    await Promise.all([this.fleet.refresh(), this.refreshAssets()]);
  }

  /**
   * One-click way to get something on screen with no hardware, straight from the empty state —
   * distinct from the onboarding wizard's own Simulate step: this quick-add has always been a single
   * click with no name/category/photo of its own.
   */
  async registerSimulator(): Promise<void> {
    this.submitting.set(true);
    try {
      await this.fleet.register(buildSyntheticRegisterRequest(''));
      await this.refreshAssets();
    } finally {
      this.submitting.set(false);
    }
  }
}
