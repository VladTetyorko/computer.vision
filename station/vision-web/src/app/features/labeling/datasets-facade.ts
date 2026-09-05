import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { TrainingStore } from '../../core/training/training-store';
import { AuthStore } from '../../core/auth/auth-store';
import { UiStore } from '../../core/ui/ui-store';
import { canManageOrg } from '../../core/org/org-logic';
import type { Category } from '../../core/api/models';
import { canSubmitDataset, parseClassesInput } from './datasets-logic';

/**
 * `DatasetsPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — the `/manage/training` dataset list +
 * "New dataset" form. `TrainingStore` (`training`) is the shared dataset-list source of truth;
 * everything else here (the create form's own draft fields, the category picker, per-row delete
 * confirm) is page-local.
 *
 * **Role gating mirrors the backend exactly**: `DatasetController#create`/`#delete` both require
 * "may manage the organization" (a `403` otherwise — vision-api/MODULE.md's own
 * `DatasetController`/`LabelingController` subsection), so {@link canManage} (`canManageOrg`,
 * `core/org/org-logic.ts` — the same pure predicate `org-guard.ts`/`identity-chip.ts` already use)
 * hides the create form and every row's delete action from a PILOT client-side too, rather than
 * showing a button that would only ever 403. Capture/label (this page's own `Open` link leads to
 * `DatasetDetailPage`/`SampleEditorPage`) are **not** manage-org-gated — any user who can see the
 * dataset may contribute samples, so those two pages don't re-check this at all. **Dev parity**:
 * `authEnabled=false`'s dev principal resolves to `ADMIN`, so `canManageOrg` is `true` and this page
 * behaves exactly as it does for a real admin.
 */
@Injectable()
export class DatasetsFacade {
  private readonly auth = inject(AuthStore);
  private readonly api = inject(VisionApi);

  readonly training = inject(TrainingStore);
  readonly canManage = computed(() => canManageOrg(this.auth.capabilities()));

  /** Per-row delete confirm — one group, so at most one row's confirm is ever open at once. */
  readonly dialogs = new UiStore();

  readonly categories = signal<readonly Category[]>([]);

  readonly newName = signal('');
  /** `''` = no target category. */
  readonly newTargetCategory = signal('');
  readonly newClassesText = signal('');
  readonly creating = signal(false);

  readonly parsedClasses = computed(() => parseClassesInput(this.newClassesText()));
  readonly canSubmit = computed(() => canSubmitDataset(this.newName(), this.parsedClasses(), this.creating()));

  constructor() {
    void this.training.refresh();
    void this.loadCategories();
  }

  async submitCreate(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    this.creating.set(true);
    const created = await this.training.createDataset({
      name: this.newName().trim(),
      targetCategory: this.newTargetCategory() || undefined,
      classes: this.parsedClasses(),
    });
    this.creating.set(false);
    if (created) {
      this.newName.set('');
      this.newTargetCategory.set('');
      this.newClassesText.set('');
    }
  }

  confirmDelete(datasetId: string): void {
    this.dialogs.open(datasetId);
  }

  cancelDelete(): void {
    this.dialogs.close();
  }

  async deleteConfirmed(datasetId: string, name: string): Promise<void> {
    this.dialogs.close(datasetId);
    await this.training.deleteDataset(datasetId, name);
  }

  private async loadCategories(): Promise<void> {
    try {
      this.categories.set(await this.api.listCategories());
    } catch {
      // Best-effort — the target-category picker just offers "None" only; never blocks dataset creation.
    }
  }
}
