import type { Category, CategoryCounts } from '../../core/api/models';

/**
 * Pure, Angular-free logic behind `CategoriesPage`, mounted as the Inventory page's Categories tab
 * (`/assets?tab=categories`, docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3, wave W4 — the grouped/counted
 * view below is joined `CategoryController` (`GET /api/categories`, `VisionApi.listCategories`)
 * against `FleetController`'s per-category counts (`GET /api/fleet/summary`'s own
 * `categories: CategoryCounts[]`). Create/Rename/Set-connected now write through `POST`/`PUT
 * /api/categories` (`CategoriesFacade#createCategory`/`updateCategory`) — the "coming" notice this
 * page used to carry (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4) is deleted this wave.
 */

/** One row of the grouped view: a defined category plus its live asset counts. */
export interface CategoryRow {
  readonly slug: string;
  readonly name: string;
  /** Absent for a top-level category — mirrors `Category.parent`. */
  readonly parent?: string;
  /** Drives the Inventory page's Vehicles/Equipment split (`Category#connected`, D-something wave
   *  W1) — carried through so the row's own "Edit" form can show/change it without a second lookup.
   *  An "uncataloged" row (a `CategoryCounts` slug with no matching `Category`, the defensive edge
   *  case `buildCategoryRows`' own doc comment names) has no real answer here; defaults `true`
   *  (Vehicles) since that's the overwhelmingly common category kind, and the row is degenerate
   *  either way — no `Category` exists yet to edit or delete. */
  readonly connected: boolean;
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
      connected: category.connected,
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
      connected: true,
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

// --- Create/rename/set-connected (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's Categories table
// write half, wave W4) ---------------------------------------------------------------------------

const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

/**
 * `CategoryId` is a kebab-case slug (CLAUDE.md's own "Ids" rule) — checked client-side before the
 * create form submits, so a typo comes back as an inline field error rather than a round trip to
 * the server's own `400` (`CategoryController#create` validates the identical shape server-side;
 * this is a UX nicety on top, never the only gate).
 */
export function isValidCategoryId(id: string): boolean {
  return SLUG_PATTERN.test(id.trim());
}

/** Blank/whitespace-only is the one client-checkable name rule — the server has no further
 *  constraint on the display name. */
export function isValidCategoryName(name: string): boolean {
  return name.trim().length > 0;
}

/** `true` once both the id and name fields hold something submittable — gates the create form's
 *  own submit button, mirroring `onboarding-logic.ts#canAdvanceFromIdentify`'s identical shape. */
export function canCreateCategory(id: string, name: string): boolean {
  return isValidCategoryId(id) && isValidCategoryName(name);
}
