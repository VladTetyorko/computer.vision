import { Injectable, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import type { UsageSummary } from '../../core/api/models';
import {
  filterUsagesByTimeRange,
  sortUsagesForDisplay,
  usageAssetOptions,
  type TimeRangeFilter,
  type UsageAssetOption,
} from './replay-library-logic';

/** The server's own page size — the newest N flights fleet-wide, or per-asset once filtered (docs/extracts/design/10-replay.md's frozen contract). No pagination beyond it. */
const USAGE_LIMIT = 50;

/**
 * `ReplayLibraryPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — the `/replay` library's read
 * model: the `GET /api/usages` fetch, the asset-filter dropdown's own options (`VisionApi.
 * listAssets`, loaded independently so an asset with zero flights in the current page is still
 * choosable), the client-side time-range narrowing (`replay-library-logic.ts#filterUsagesByTimeRange`
 * — the frozen contract has no server-side date-range param), and the two-pane `?sel=` selection
 * (mirrors `AssetsFacade`'s own identical convention — see that class's doc comment for why
 * selection is looked up against the unfiltered list, never the filter-narrowed one).
 *
 * **Honest states, never fabricated (docs/extracts/design/10-replay.md, F8's own "no navigation path
 * reaches an error state passed off as a bug" bar).** `loading` is true only until the *first*
 * fetch settles; `errorMessage` is set the moment that fetch rejects — a genuine backend failure,
 * including a plain 404 while a concurrent backend wave hasn't shipped `GET /api/usages` yet — and
 * `usages` is reset to `[]` in the same branch so a failed request can never leave a stale or
 * partial row on screen. `loaded && !errorMessage && usages().length === 0` is the one legitimate
 * "no finished flights yet" case; `replay-library.html` renders exactly one of the three states,
 * never blending them.
 */
@Injectable()
export class ReplayLibraryFacade {
  private readonly api = inject(VisionApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly loading = signal(true);
  readonly loaded = signal(false);
  readonly errorMessage = signal<string | undefined>(undefined);
  readonly usages = signal<readonly UsageSummary[]>([]);

  readonly assetOptions = signal<readonly UsageAssetOption[]>([]);
  readonly assetFilter = signal('');
  readonly timeRangeFilter = signal<TimeRangeFilter>('all');
  readonly hasActiveFilters = computed(() => this.assetFilter().length > 0 || this.timeRangeFilter() !== 'all');

  /** The list the template actually renders — `usages` (server-filtered by asset already) narrowed
   *  by the client-side time-range filter, then triaged (OPERATOR-UX-5-PLAN.md finding U1, §2 U1):
   *  a `sampleCount === 0` row sorts last, everything else keeps the server's newest-first order. */
  readonly filteredUsages = computed(() =>
    sortUsagesForDisplay(filterUsagesByTimeRange(this.usages(), this.timeRangeFilter(), Date.now())),
  );

  // --- Two-pane selection (`?sel=<usageId>`, docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4) ------------------
  // `ReplayLibraryPage`'s own constructor `effect()` forwards its route-bound `sel` input straight
  // into this signal — the same split `AssetsPage`/`AssetsFacade` already establish. Looked up
  // against `usages` (every loaded row), not `filteredUsages`, so narrowing the time-range filter
  // never silently closes an already-open selection out from under the user.
  readonly selectedId = signal<string | undefined>(undefined);
  readonly selectedUsage = computed(() => this.usages().find((usage) => usage.usageId === this.selectedId()));

  constructor() {
    void this.refresh();
    void this.loadAssetOptions();
  }

  /** The asset filter select's own handler — unlike the time-range filter (a plain client-side
   *  narrowing, set directly from the template), changing *this* one re-fetches: the frozen
   *  contract's own `assetId` query param exists so a quiet asset's older flights aren't crowded
   *  out of the fleet-wide top `USAGE_LIMIT` before this page ever sees them. */
  async setAssetFilter(assetId: string): Promise<void> {
    this.assetFilter.set(assetId);
    await this.refresh();
  }

  async clearFilters(): Promise<void> {
    this.timeRangeFilter.set('all');
    await this.setAssetFilter('');
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    this.errorMessage.set(undefined);
    try {
      const usages = await this.api.listUsages({ limit: USAGE_LIMIT, assetId: this.assetFilter() || undefined });
      this.usages.set(usages);
    } catch (error) {
      this.usages.set([]); // never fabricate rows on a failed fetch — see class doc.
      this.errorMessage.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
      this.loaded.set(true);
    }
  }

  /** The asset-filter dropdown's own options — deliberately independent of `usages` (see class
   *  doc). Silent-degrade on failure: the dropdown just stays "All assets" only, the list itself
   *  is unaffected — this is enrichment for a filter control, not a user-initiated action. */
  private async loadAssetOptions(): Promise<void> {
    try {
      this.assetOptions.set(usageAssetOptions(await this.api.listAssets()));
    } catch {
      // Silent-degrade — see doc comment above.
    }
  }

  /** A row was clicked/activated — opens the two-pane preview and mirrors the choice into `?sel=`
   *  so it survives refresh, Back and sharing, exactly like `AssetsFacade#selectRow`. */
  selectRow(usageId: string): void {
    this.selectedId.set(usageId);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { sel: usageId }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  /** The pane's close button / Esc / scrim click (`TwoPane`'s own `detailClose` output). */
  clearSelection(): void {
    this.selectedId.set(undefined);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { sel: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }
}
