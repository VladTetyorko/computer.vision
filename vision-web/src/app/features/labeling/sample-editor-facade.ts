import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { pluralize } from '../../shared/ui/page-bar/page-bar';
import type { Annotation, Dataset, TrainingSample } from '../../core/api/models';
import { validateAnnotations } from './sample-editor-logic';

/** Stable per-file console tag, mirroring every other store/facade in this app. */
const LOG_PREFIX = '[labeling]';

/** The sample list is fetched unfiltered (every status) at this cap purely to look one sample up by
 *  id for a direct navigation — there's no `GET /api/samples/{id}` on the frozen contract, only the
 *  dataset-scoped list (see this class's own {@link load} doc comment). */
const SAMPLE_LOOKUP_LIMIT = 500;

/**
 * `SampleEditorPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — the confirm/correct step: load one
 * sample + its dataset's class vocabulary, host `vision-sample-box-editor`'s working annotation set,
 * and save it as `LABELED` (confirm) or `DISCARDED`. Injects `VisionApi` directly, same "page-local
 * state" reasoning as `DatasetDetailFacade`'s own doc comment.
 */
@Injectable()
export class SampleEditorFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);

  readonly dataset = signal<Dataset | null>(null);
  readonly sample = signal<TrainingSample | null>(null);
  readonly loading = signal(true);
  /** `true` once a load has confirmed the sample/dataset is unknown, out of scope, or the sample id isn't in this dataset. */
  readonly notFound = signal(false);
  readonly saving = signal(false);

  /** The box editor's working copy — seeded from `sample().annotations` on load, updated on every committed edit via {@link setAnnotations}. */
  readonly annotations = signal<readonly Annotation[]>([]);

  readonly validation = computed(() => validateAnnotations(this.annotations(), this.dataset()?.classes ?? []));
  readonly canConfirm = computed(() => this.validation().valid);
  /** "N box(es) use a label…" was a literal placeholder string — `pluralize` (`shared/ui/page-bar`,
   *  docs/NAV-IA-REDESIGN-PLAN.md §2.2) is this app's one regular-English pluralisation helper;
   *  "boxes" is irregular, so it's passed explicitly rather than the default `${singular}s`. */
  readonly invalidLabelsMessage = computed(
    () =>
      `${pluralize(this.validation().invalidLabels.length, 'box', 'boxes')} use a label not in this ` +
      `dataset's vocabulary — pick one from the dropdown before confirming.`,
  );

  private currentDatasetId = '';

  imageUrl(sampleId: string): string {
    return this.api.sampleImageUrl(sampleId);
  }

  setAnnotations(next: readonly Annotation[]): void {
    this.annotations.set(next);
  }

  /**
   * Called once by the page's own constructor `effect()` on every `datasetId`/`sampleId` route-input
   * change. `GET /api/datasets/{id}/samples` (no `status` filter, a generous limit) is the closest
   * thing to a by-id lookup the frozen contract offers — see this file's own `SAMPLE_LOOKUP_LIMIT`.
   */
  load(datasetId: string, sampleId: string): void {
    this.currentDatasetId = datasetId;
    void this.fetch(datasetId, sampleId);
  }

  async confirm(): Promise<void> {
    await this.save('LABELED', 'Saved — sample labeled.');
  }

  async discardSample(): Promise<void> {
    await this.save('DISCARDED', 'Discarded.');
  }

  private async save(status: 'LABELED' | 'DISCARDED', okMessage: string): Promise<void> {
    const sample = this.sample();
    if (!sample || this.saving()) {
      return;
    }
    if (status === 'LABELED' && !this.canConfirm()) {
      return;
    }
    this.saving.set(true);
    try {
      await this.api.putSampleAnnotations(sample.id, { status, annotations: this.annotations() });
      this.toasts.ok(okMessage);
      await this.router.navigate(['/manage/training', this.currentDatasetId]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.saving.set(false);
    }
  }

  private async fetch(datasetId: string, sampleId: string): Promise<void> {
    this.loading.set(true);
    this.notFound.set(false);
    try {
      const [dataset, samplesResponse] = await Promise.all([
        this.api.getDataset(datasetId),
        this.api.datasetSamples(datasetId, undefined, SAMPLE_LOOKUP_LIMIT),
      ]);
      this.dataset.set(dataset);
      const found = samplesResponse.samples.find((candidate) => candidate.id === sampleId);
      if (!found) {
        this.notFound.set(true);
      } else {
        this.sample.set(found);
        this.annotations.set(found.annotations);
      }
    } catch (error) {
      if (error instanceof HttpErrorResponse && (error.status === 404 || error.status === 403)) {
        this.notFound.set(true);
      } else {
        console.warn(`${LOG_PREFIX} failed to load sample ${sampleId}`, { error });
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.loading.set(false);
    }
  }
}
