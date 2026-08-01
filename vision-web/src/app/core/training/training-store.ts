import { Injectable, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../api/vision-api';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import type { CreateDatasetRequest, Dataset } from '../api/models';

/** Stable per-file console tag, mirroring `[fleet]`/`[org]`/`[auth]` — no shared logging service in this app. */
const LOG_PREFIX = '[training]';

/**
 * The `features/labeling/**` surface's source of truth for the dataset list (docs/CV-TRAINING-PLAN.md
 * Wave T5) — `providedIn: 'root'`, mirroring `OrgStore`'s posture: lazy, not self-initializing
 * (`DatasetsPage`'s own facade calls {@link refresh} once it's actually reached, the same "don't hit
 * an admin-flavored endpoint from app boot" reasoning `OrgStore`'s own doc comment gives, even though
 * this surface itself isn't role-gated — see below).
 *
 * **Feature gating, done honestly, with no dedicated "is training enabled" endpoint** (the plan names
 * none): `vision.training.enabled=false` removes `DatasetController`/`LabelingController` from the
 * app entirely, so every one of their routes 404s exactly like any unmapped path
 * (vision-api/MODULE.md's own `DatasetController`/`LabelingController` subsection). `GET
 * /api/datasets` is the one call in this whole feature that can **only** mean "the controller isn't
 * here" when it 404s — every other endpoint's 404 can legitimately mean "unknown id" instead, so only
 * this one is trustworthy as the disabled signal. {@link refresh} turns that specific 404 into
 * {@link disabled} — read by `DatasetsPage` (and, via a direct-navigation dataset/sample-detail
 * facade's own 404 handling, degraded the same honest way) to render `vision-empty`, never a blocked
 * page or a fabricated dataset list (CLAUDE.md's own "degrade honestly" rule). Every *other* failure
 * (network down, 5xx, a genuine 403) stays a toast, exactly like `OrgStore`/`FleetStore`.
 *
 * Only the dataset **list** + create/delete live here — one dataset's samples and one sample's
 * annotations are page-local, single-consumer state, so `DatasetDetailFacade`/`SampleEditorFacade`
 * call `VisionApi` directly for their own reads/writes (the same "a routed page's facade may inject a
 * service directly, not only a store" shape `RosterFacade` already uses for `listAssetPilots`) rather
 * than growing this store with state only one page ever reads.
 */
@Injectable({ providedIn: 'root' })
export class TrainingStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  private readonly datasetsSignal = signal<readonly Dataset[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly loadedSignal = signal(false);
  private readonly disabledSignal = signal(false);

  readonly datasets = this.datasetsSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  /** `false` until the first `refresh()` settles — lets a page tell "still loading" from "genuinely no datasets". */
  readonly loaded = this.loadedSignal.asReadonly();
  /** `true` once a `refresh()` has confirmed `vision.training.enabled=false` on this deployment — see class doc. */
  readonly disabled = this.disabledSignal.asReadonly();

  /** Re-reads the dataset list. Toasts once on a genuine failure unless `quiet`; a 404 sets {@link disabled} instead of toasting (that's an expected, first-class outcome here, not an error). */
  async refresh(options: { quiet?: boolean } = {}): Promise<void> {
    this.loadingSignal.set(true);
    try {
      const response = await this.api.listDatasets();
      this.datasetsSignal.set(response.datasets);
      this.disabledSignal.set(false);
      this.loadedSignal.set(true);
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 404) {
        this.datasetsSignal.set([]);
        this.disabledSignal.set(true);
        this.loadedSignal.set(true);
      } else {
        console.warn(`${LOG_PREFIX} failed to load datasets`, { error });
        if (!options.quiet) {
          this.toasts.error(describeHttpError(error));
        }
      }
    } finally {
      this.loadingSignal.set(false);
    }
  }

  async createDataset(request: CreateDatasetRequest): Promise<Dataset | null> {
    return this.run(async () => {
      const dataset = await this.api.createDataset(request);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Created dataset "${dataset.name}".`);
      return dataset;
    });
  }

  async deleteDataset(id: string, name: string): Promise<boolean> {
    const result = await this.run(async () => {
      await this.api.deleteDataset(id);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Deleted "${name}".`);
      return true;
    });
    return result ?? false;
  }

  /** Runs a mutation, turning any failure into one explained toast (mirrors `OrgStore.run`/`FleetStore.run`). */
  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    try {
      return await action();
    } catch (error) {
      console.warn(`${LOG_PREFIX} action failed`, { error });
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}
