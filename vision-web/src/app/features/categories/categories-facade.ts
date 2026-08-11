import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import type { Category, CategoryCounts } from '../../core/api/models';
import { buildCategoryRows, searchCategoryRows, type CategoryRow } from './categories-logic';

/**
 * `CategoriesPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — loads the defined category list
 * (`VisionApi.listCategories`) and the fleet-summary counts (`VisionApi.fleetSummary`) once, in
 * parallel, and joins them ({@link buildCategoryRows}). No role gate — reachable by any signed-in
 * user, same openness as `/assets` (browsing/filtering categories is not a management action; only
 * create/edit would be, and that isn't built).
 */
@Injectable()
export class CategoriesFacade {
  private readonly api = inject(VisionApi);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly searchQuery = signal('');

  private readonly categories = signal<readonly Category[]>([]);
  private readonly counts = signal<readonly CategoryCounts[]>([]);

  readonly hasAny = computed(() => this.categories().length + this.counts().length > 0);
  readonly rows = computed<readonly CategoryRow[]>(() =>
    searchCategoryRows(buildCategoryRows(this.categories(), this.counts()), this.searchQuery()),
  );

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [categories, summary] = await Promise.all([this.api.listCategories(), this.api.fleetSummary()]);
      this.categories.set(categories);
      this.counts.set(summary.categories);
    } catch (error) {
      this.error.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }
}
