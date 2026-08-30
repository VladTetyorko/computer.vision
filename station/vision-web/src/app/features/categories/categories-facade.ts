import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import type { Category, CategoryCounts } from '../../core/api/models';
import { buildCategoryRows, searchCategoryRows, type CategoryRow } from './categories-logic';

/**
 * `CategoriesPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — loads the defined category list
 * (`VisionApi.listCategories`) and the fleet-summary counts (`VisionApi.fleetSummary`) once, in
 * parallel, and joins them ({@link buildCategoryRows}). No route-level role gate of its own — the
 * page is reachable by any signed-in user (browsing/filtering categories is not a management
 * action) — but `createCategory`/`updateCategory` are `canManageOrg`-gated server-side, and the
 * Inventory page's own Categories *tab* is hidden from a pilot entirely
 * (`core/fleet/inventory-logic.ts#visibleInventoryTabs`), so the write half's own affordances never
 * render for a role that can't use them.
 */
@Injectable()
export class CategoriesFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly searchQuery = signal('');
  readonly submitting = signal(false);

  private readonly categories = signal<readonly Category[]>([]);
  private readonly counts = signal<readonly CategoryCounts[]>([]);

  readonly hasAny = computed(() => this.categories().length + this.counts().length > 0);
  readonly rows = computed<readonly CategoryRow[]>(() =>
    searchCategoryRows(buildCategoryRows(this.categories(), this.counts()), this.searchQuery()),
  );

  /** The create form's "Parent" picker — every defined category, name-sorted. */
  readonly parentOptions = computed<readonly Category[]>(() =>
    [...this.categories()].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })),
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

  /** `POST /api/categories` (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's write half, wave W4) —
   *  `id`/`name` are pre-validated by the form (`categories-logic.ts#canCreateCategory`) before this
   *  is ever called; a server-side rejection (duplicate id, bad shape) still surfaces honestly via
   *  `describeHttpError`. Returns whether it succeeded, so the form knows whether to clear itself. */
  async createCategory(id: string, name: string, parentId: string | undefined, connected: boolean): Promise<boolean> {
    this.submitting.set(true);
    try {
      const created = await this.api.createCategory({ id: id.trim(), name: name.trim(), parentId, connected });
      this.categories.update((list) => [...list, created]);
      this.toasts.ok(`Created "${created.name}".`);
      return true;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return false;
    } finally {
      this.submitting.set(false);
    }
  }

  /**
   * `PUT /api/categories/{id}` — a whole-record replacement ({@link UpdateCategoryRequest}'s own doc
   * comment), so this preserves the category's existing `attributeHints` (not editable by this form)
   * rather than silently wiping them on every rename/reconnect.
   */
  async updateCategory(slug: string, name: string, parentId: string | undefined, connected: boolean): Promise<boolean> {
    const existing = this.categories().find((category) => category.slug === slug);
    this.submitting.set(true);
    try {
      const updated = await this.api.updateCategory(slug, {
        name: name.trim(),
        parentId,
        connected,
        attributeHints: existing?.attributeHints,
      });
      this.categories.update((list) => list.map((category) => (category.slug === slug ? updated : category)));
      this.toasts.ok(`Updated "${updated.name}".`);
      return true;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return false;
    } finally {
      this.submitting.set(false);
    }
  }
}
