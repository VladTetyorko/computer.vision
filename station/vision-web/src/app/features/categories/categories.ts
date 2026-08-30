import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { CategoriesFacade } from './categories-facade';
import { canCreateCategory, isValidCategoryId, isValidCategoryName, type CategoryRow } from './categories-logic';

/**
 * The Inventory page's Categories tab (`/assets?tab=categories`, docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.3, wave W4 — the grouped/counted view (every defined category, joined against live per-category
 * asset counts, each linking into the filtered Vehicles/Equipment tab) plus the write half: Create,
 * Rename, and Set-connected all now work (`POST`/`PUT /api/categories`), replacing the "coming"
 * `<vision-notice>` this page used to carry (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4).
 *
 * No role gate of its own — a pilot can never mount this component at all (the Inventory page hides
 * the Categories tab entirely, `core/fleet/inventory-logic.ts#visibleInventoryTabs`); the write
 * endpoints are `canManageOrg`-gated server-side regardless, belt-and-suspenders.
 *
 * The create/edit forms are local, template-branch UI state (not the facade's) — same "doesn't feed
 * any facade computed" reasoning `AssetsPage`'s own `viewMode` doc comment gives — the facade owns
 * only the HTTP-backed `createCategory`/`updateCategory` calls themselves.
 *
 * **`embedded` (docs/plans/active/WAREHOUSE-UX-PLAN.md §4 wave W9).** Mounted as the Inventory
 * page's "Categories" tab (`features/inventory/inventory.ts`) with `[embedded]="true"` — same
 * `OrgSettingsPage`/`DevicesPage` pattern (see either's own `embedded` doc comment): Inventory's own
 * single sticky `vision-page-bar` already carries the page's title/tab bar, so a second stacked
 * sticky bar here would be duplicate chrome. `embedded` true swaps `<vision-page-bar>` for a plain,
 * non-sticky `.embedded-toolbar` row carrying the identical search input + "+ Add category" button —
 * one `<ng-template>` per slot fed to both header shapes via `NgTemplateOutlet`, so the two never
 * drift. Defaults to `false` (the standalone route still renders its own bar).
 */
@Component({
  selector: 'vision-categories',
  imports: [FormsModule, RouterLink, PageBar, SectionHeader, EmptyState, NgTemplateOutlet],
  templateUrl: './categories.html',
  styleUrl: './categories.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [CategoriesFacade],
})
export class CategoriesPage {
  protected readonly facade = inject(CategoriesFacade);

  /** `true` when mounted inside `InventoryPage`'s "Categories" tab — see this class's own doc comment. */
  readonly embedded = input(false);

  // --- Create form -----------------------------------------------------------------------------

  protected readonly showCreate = signal(false);
  protected readonly createId = signal('');
  protected readonly createName = signal('');
  protected readonly createParent = signal('');
  protected readonly createConnected = signal(true);

  protected readonly canCreateCategory = canCreateCategory;
  protected readonly isValidCategoryId = isValidCategoryId;
  protected readonly isValidCategoryName = isValidCategoryName;

  protected openCreate(): void {
    this.showCreate.set(true);
  }

  protected cancelCreate(): void {
    this.showCreate.set(false);
    this.createId.set('');
    this.createName.set('');
    this.createParent.set('');
    this.createConnected.set(true);
  }

  protected async submitCreate(): Promise<void> {
    if (!canCreateCategory(this.createId(), this.createName())) {
      return;
    }
    const ok = await this.facade.createCategory(
      this.createId(),
      this.createName(),
      this.createParent() || undefined,
      this.createConnected(),
    );
    if (ok) {
      this.cancelCreate();
    }
  }

  // --- Rename / set-connected (inline row edit) -------------------------------------------------

  protected readonly editingSlug = signal<string | undefined>(undefined);
  protected readonly editName = signal('');
  protected readonly editParent = signal('');
  protected readonly editConnected = signal(true);

  protected startEdit(row: CategoryRow): void {
    this.editingSlug.set(row.slug);
    this.editName.set(row.name);
    this.editParent.set(row.parent ?? '');
    this.editConnected.set(row.connected);
  }

  protected cancelEdit(): void {
    this.editingSlug.set(undefined);
  }

  protected async submitEdit(): Promise<void> {
    const slug = this.editingSlug();
    if (!slug || !isValidCategoryName(this.editName())) {
      return;
    }
    const ok = await this.facade.updateCategory(slug, this.editName(), this.editParent() || undefined, this.editConnected());
    if (ok) {
      this.cancelEdit();
    }
  }
}
