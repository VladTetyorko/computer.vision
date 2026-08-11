import type { Category, CategoryCounts } from '../../core/api/models';

/**
 * Pure, Angular-free logic behind `CategoriesPage` (`/manage/categories`, docs/plans/done/UI-REDESIGN-PLAN.md
 * Wave 4 — **SPLIT**: the grouped/counted view below is functional, reusing `CategoryController`
 * (`GET /api/categories`, `VisionApi.listCategories`) joined against `FleetController`'s per-category
 * counts (`GET /api/fleet/summary`'s own `categories: CategoryCounts[]`); category create/edit is not
 * built — only `GET` exists server-side, named follow-up: category `POST`/`PUT`).
 */

/** One row of the grouped view: a defined category plus its live asset counts. */
export interface CategoryRow {
  readonly slug: string;
  readonly name: string;
  /** Absent for a top-level category — mirrors `Category.parent`. */
  readonly parent?: string;
  readonly total: number;
  readonly active: number;
  readonly deactivated: number;
  readonly streaming: number;
}

/**
 * Joins the defined category list against the fleet-summary counts by slug/`categoryId`. A defined
 * category with no matching count row (nothing currently registered in it) reads as all-zero counts
 * — never omitted, since "a category that exists but is empty" is exactly the honest state this page
 * should show, not silently drop. A count row for a slug `categories` doesn't know about (shouldn't
 * normally happen — `CategoryCounts.categoryId` is server-derived from the same reference data — but
 * defensive against a race between the two independent reads) is still included, named by its own
 * `categoryName`, rather than silently dropping real asset counts. Sorted by name.
 */
export function buildCategoryRows(categories: readonly Category[], counts: readonly CategoryCounts[]): readonly CategoryRow[] {
  const countBySlug = new Map(counts.map((count) => [count.categoryId, count]));
  const rows = categories.map((category) => {
    const count = countBySlug.get(category.slug);
    return {
      slug: category.slug,
      name: category.name,
      parent: category.parent,
      total: count?.total ?? 0,
      active: count?.active ?? 0,
      deactivated: count?.deactivated ?? 0,
      streaming: count?.streaming ?? 0,
    };
  });

  const knownSlugs = new Set(categories.map((category) => category.slug));
  const uncataloged = counts
    .filter((count) => !knownSlugs.has(count.categoryId))
    .map((count) => ({
      slug: count.categoryId,
      name: count.categoryName,
      parent: undefined,
      total: count.total,
      active: count.active,
      deactivated: count.deactivated,
      streaming: count.streaming,
    }));

  return [...rows, ...uncataloged].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
}

/** Case-insensitive substring match on the category's own name or slug. */
export function searchCategoryRows(rows: readonly CategoryRow[], query: string): readonly CategoryRow[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return rows;
  }
  return rows.filter((row) => row.name.toLowerCase().includes(q) || row.slug.toLowerCase().includes(q));
}
