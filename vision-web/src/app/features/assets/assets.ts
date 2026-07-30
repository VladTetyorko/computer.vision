import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { ToastService } from '../../core/toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { describeHttpError } from '../../core/api-error';
import { buildSyntheticRegisterRequest } from '../../core/fleet/simulation-logic';
import { deriveCategoryOptions, type CategoryOption } from '../../core/fleet/category-logic';
import { RESTORE_TARGET_STATE, operatorAssetActions, type ActionAvailability } from '../../core/fleet/warehouse-logic';
import type { AssetDetails } from '../../core/api/models';
import {
  buildAssetListRows,
  filterAssetListRowsByArchived,
  filterAssetListRowsByCategory,
  filterAssetListRowsByStatus,
  filterAssetListRowsByStreaming,
  searchAssetListRowsByName,
  type AssetListRow,
  type AssetStatusFilter,
  type AssetStreamingFilter,
} from './assets-logic';

/**
 * The Assets page (`/assets`) — asset-first grid: search by name, filter by category/lifecycle/
 * streaming, one card per asset with exactly Watch · Open · Archive. Split out of the old combined
 * Devices page (docs/CYCLES-PLAN.md §11's "asset-first list") once inventory got its own dedicated
 * pages for Assets and Devices, Warehouse becoming a two-tile launcher between them — see
 * `features/warehouse/warehouse.ts` and `features/devices/devices.ts`'s own class doc comments.
 * Every asset-lifecycle action here (rename lives on the asset detail page, not this list — see
 * `operatorAssetActions`'s own doc comment for why only Archive/Restore reach this surface) is
 * unchanged from the page this was split out of, byte-for-byte in behavior.
 */
@Component({
  selector: 'vision-assets',
  imports: [FormsModule, RouterLink],
  templateUrl: './assets.html',
  styleUrl: './assets.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetsPage {
  /**
   * `?category=<slug>` — pre-filters the grid to one category (docs/UX-QUICKWINS-PLAN.md QF-2/QF-3):
   * the drill-down target for the Command dashboard's readiness tiles. Binds by name, same
   * query-param-to-input mechanism as `DevicesPage.addSource`/`category` used to — no route change
   * needed. Seeds the category `<select>` below (an `effect`, so re-navigating here with a different
   * `?category=` while already on this page keeps working, not just on first load).
   */
  readonly category = input<string | undefined>(undefined);

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);
  private readonly router = inject(Router);

  protected readonly fleet = inject(FleetStore);

  protected readonly submitting = signal(false);
  protected readonly busyAssetId = signal<string | null>(null);

  protected readonly showArchived = signal(false);
  /** Every asset the page has loaded, as full `AssetDetails` — the grid needs each asset's resolved
   *  device count/watch-target (`buildAssetListRows`). */
  protected readonly assets = signal<readonly AssetDetails[]>([]);

  // --- Search + filters ------------------------------------------------------------------------
  protected readonly searchQuery = signal('');
  protected readonly categoryFilter = signal('');
  protected readonly statusFilter = signal<AssetStatusFilter>('all');
  protected readonly streamingFilter = signal<AssetStreamingFilter>('all');

  protected readonly categoryOptions = computed<readonly CategoryOption[]>(() => {
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

  protected readonly hasActiveFilters = computed(
    () =>
      this.searchQuery().trim().length > 0 ||
      this.categoryFilter().length > 0 ||
      this.statusFilter() !== 'all' ||
      this.streamingFilter() !== 'all',
  );

  /**
   * The grid's rows: every filter narrows in sequence (archived → category → status → streaming →
   * name search) — order doesn't change the result, each is a plain array filter, but this is the
   * order the filter toolbar reads left to right.
   */
  protected readonly assetRows = computed<readonly AssetListRow[]>(() => {
    const rows = filterAssetListRowsByArchived(
      buildAssetListRows(this.assets(), this.fleet.liveDeviceIds()),
      this.showArchived(),
    );
    const byCategory = filterAssetListRowsByCategory(rows, this.categoryFilter() || undefined);
    const byStatus = filterAssetListRowsByStatus(byCategory, this.statusFilter());
    const byStreaming = filterAssetListRowsByStreaming(byStatus, this.streamingFilter());
    return searchAssetListRowsByName(byStreaming, this.searchQuery());
  });

  /** `true` once at least one asset has loaded — distinguishes "no assets exist yet" from "filters
   *  matched nothing" for the empty state (never a fabricated "no assets" when the fleet has some). */
  protected readonly hasAnyAssets = computed(() => this.assets().length > 0);

  protected clearFilters(): void {
    this.searchQuery.set('');
    this.categoryFilter.set('');
    this.statusFilter.set('all');
    this.streamingFilter.set('all');
    if (this.category()) {
      void this.router.navigate(['/assets']);
    }
  }

  /** "+ Add source" (docs/UX-REWORK-PLAN.md §U-d) — the onboarding wizard is the only way in now. */
  protected goToAddSource(): Promise<boolean> {
    return this.router.navigate(['/add-source']);
  }

  protected lifecycleLabel(state: AssetListRow['lifecycle']): string {
    switch (state) {
      case 'ACTIVE':
        return 'Active';
      case 'DEACTIVATED':
        return 'Deactivated';
      case 'DELETED':
        return 'Archived';
    }
  }

  /** The card's per-row kebab menu — just Archive/Restore, reasoned (docs/UX-REWORK-PLAN.md §U-a2 item 2). */
  protected assetActionsFor(row: AssetListRow): readonly ActionAvailability<'archive' | 'restore'>[] {
    return operatorAssetActions(row.lifecycle);
  }

  protected assetActionLabel(action: 'archive' | 'restore'): string {
    return action === 'archive' ? 'Archive asset' : 'Restore asset';
  }

  protected watchAsset(row: AssetListRow): Promise<boolean> | undefined {
    return row.watchDeviceId ? this.router.navigate(['/live', row.watchDeviceId]) : undefined;
  }

  protected openAsset(row: AssetListRow): Promise<boolean> {
    return this.router.navigate(['/assets', row.asset.assetId]);
  }

  protected onAssetAction(row: AssetListRow, action: 'archive' | 'restore'): void {
    if (action === 'archive') {
      void this.archiveAssetNow(row);
    } else {
      void this.restoreAssetNow(row.asset.assetId, row.asset.displayName);
    }
  }

  /**
   * Archive executes immediately, no confirm dialog (docs/UX-REWORK-PLAN.md §U-a2 item 3b —
   * "Undo over confirm"). Calls `VisionApi.deleteAsset` directly rather than `FleetStore.deleteAsset`
   * — that method's own `run()`-wrapped success toast has no Undo action, so this page fires its own
   * `UndoToastService` toast instead (docs/OPS-CORE-PLAN.md §Q2).
   */
  protected async archiveAssetNow(row: AssetListRow): Promise<void> {
    const assetId = row.asset.assetId;
    this.busyAssetId.set(assetId);
    try {
      const result = await this.api.deleteAsset(assetId);
      this.undoToast.showUndo(
        `Archived "${result.displayName}" — ${result.devicesDeleted} device(s) archived, ` +
          `${result.usagesRetained} usage(s) retained, ${result.streamsStopped} stream(s) stopped.`,
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
  protected async restoreAssetNow(assetId: string, displayName: string): Promise<void> {
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
  protected async undoArchiveAsset(assetId: string, displayName: string, devicesArchived: number): Promise<void> {
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
        const outcomes = await Promise.allSettled(
          archivedDevices.map((device) => this.api.setDeviceState(device.id, RESTORE_TARGET_STATE)),
        );
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
        `Restored "${displayName}", but ${devicesFailed} of its ${devicesArchived} device(s) failed to ` +
          `restore${succeeded} — retry from Devices.`,
      );
    } else if (devicesRestored > 0) {
      this.toasts.ok(`Restored "${displayName}" and ${devicesRestored} device(s).`);
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
  private async refreshAssets(): Promise<void> {
    try {
      const summaries = this.showArchived()
        ? await this.fleet.listAssetsIncludingArchived()
        : await this.api.listAssets();
      if (!summaries) {
        return; // failure already toasted by FleetStore.run() (only reachable when showArchived)
      }
      const details = await Promise.all(summaries.map((asset) => this.api.getAsset(asset.assetId)));
      this.assets.set(details);
    } catch {
      // Silent-degrade — see doc comment above.
    }
  }

  protected async toggleShowArchived(): Promise<void> {
    this.showArchived.update((value) => !value);
    await this.refreshAssets();
  }

  protected async refreshAll(): Promise<void> {
    await Promise.all([this.fleet.refresh(), this.refreshAssets()]);
  }

  /**
   * One-click way to get something on screen with no hardware, straight from the empty state —
   * distinct from the onboarding wizard's own Simulate step (docs/UX-DESIGN.md §6): this quick-add
   * has always been a single click with no name/category/photo of its own.
   */
  protected async registerSimulator(): Promise<void> {
    this.submitting.set(true);
    try {
      await this.fleet.register(buildSyntheticRegisterRequest(''));
      await this.refreshAssets();
    } finally {
      this.submitting.set(false);
    }
  }

  constructor() {
    void this.refreshAssets();
    // Seeds/updates the category filter from the query param — re-navigating here with a different
    // ?category= (e.g. a second drill-down click) while already mounted keeps working, not just on
    // first load.
    effect(() => {
      const slug = this.category();
      if (slug !== undefined) {
        this.categoryFilter.set(slug);
      }
    });
  }
}
