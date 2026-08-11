import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { FleetStore } from '../../core/fleet/fleet-store';
import { AuthStore } from '../../core/auth/auth-store';
import { canManageOrg } from '../../core/org/org-logic';
import type { Dataset, SampleStatus, TrainingJobResponse, TrainingSample } from '../../core/api/models';
import {
  DEFAULT_BASE_MODEL,
  DEFAULT_TRAINING_EPOCHS,
  canStartTrainingDataset,
  canSubmitTrainingRequest,
  streamCaptureLabel,
} from './dataset-detail-logic';

/** Stable per-file console tag, mirroring every other store/facade in this app. */
const LOG_PREFIX = '[labeling]';

/**
 * `DatasetDetailPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — one dataset's own sample grid,
 * capture flow, and train action. Injects `VisionApi` directly rather than `TrainingStore` (the
 * same "a routed page's facade may talk to a service directly for page-local state" shape
 * `RosterFacade` already uses) — this dataset's own record and its samples are both single-consumer
 * state no other page reads, so growing the shared store with them would be unjustified ceremony.
 * `FleetStore` (already app-wide, `providedIn: 'root'`) supplies the capture stream picker's live
 * stream list for free — no second poller.
 *
 * **Capture entry point lives here, not in Fly/Live/Replay** (docs/plans/done/CV-TRAINING-PLAN.md Wave T5's own
 * task brief flags the collision with parallel `features/fly/**` work) — the operator picks *which*
 * active stream to capture from right here, inside the dataset they're building, rather than a
 * scattered "Add to dataset" button on every video surface. One coherent place to do the whole
 * capture → correct → train loop, and zero risk of touching a file another task owns. (A second,
 * replay-driven capture entry point lives in `features/replay/**` — see that facade's own doc
 * comment; docs/plans/done/CV-TRAINING-V2-PLAN.md §8.)
 *
 * **"Train a model" (docs/plans/done/CV-TRAINING-PLAN.md Phase 2's last web wave; the manual export step this
 * doc comment used to describe was deleted in docs/plans/done/CV-TRAINING-V2-PLAN.md — `POST
 * /api/datasets/{id}/train` now uploads the dataset itself, no separate export artifact) lives here
 * too, not a separate page of its own** — starting a fine-tune is one more action against *this*
 * dataset, the same footing as capture. `TrainingJobController#start` requires `canManageOrg` (a
 * `403` otherwise, mirroring `ModelRegistryController#promote`'s own gate — starting a training run
 * is a privileged control-plane action), so {@link canManage} hides the whole "Train a model" card
 * for a PILOT, unlike capture which stays open to anyone who can see the dataset. On success,
 * {@link startTraining} navigates straight to the freshly started job's own progress page
 * (`/manage/training/jobs/:jobId`, `features/training-jobs/**`) — the operator lands on the run
 * they just started rather than a static confirmation. {@link recentJobs} is a best-effort,
 * point-in-time list (this dataset's own jobs from `GET /api/training/jobs`, filtered client-side —
 * the endpoint is unscoped/global) so a returning operator can find a run they started earlier
 * without memorizing its URL; it does not poll itself (the dedicated job page already does that),
 * refreshed only on {@link load}.
 */
@Injectable()
export class DatasetDetailFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthStore);

  readonly fleet = inject(FleetStore);

  readonly dataset = signal<Dataset | null>(null);
  readonly loading = signal(true);
  /** `true` once a load has confirmed the dataset is unknown or out of scope (404/403) — a `vision-empty`, never a blocked page. */
  readonly notFound = signal(false);

  readonly statusFilter = signal<SampleStatus>('PENDING');
  readonly samples = signal<readonly TrainingSample[]>([]);
  readonly samplesLoading = signal(false);

  readonly captureStreamId = signal('');
  readonly capturing = signal(false);

  /** `TrainingJobController#start`'s own manager gate, mirrored client-side — see this class's own doc comment. */
  readonly canManage = computed(() => canManageOrg(this.auth.user()?.topRole));

  readonly baseModel = signal(DEFAULT_BASE_MODEL);
  /** `number | null` because that's what an emptied `type="number"` `ngModel` actually produces. */
  readonly epochs = signal<number | null>(DEFAULT_TRAINING_EPOCHS);
  readonly starting = signal(false);
  private readonly baseModelOptionsSignal = signal<readonly string[]>([]);
  /** Suggestions for the base-model field's `<datalist>` — every id the CV registry currently
   *  reports (`GET /api/cv/registry/models`), best-effort (see {@link loadBaseModelOptions}). */
  readonly baseModelOptions = this.baseModelOptionsSignal.asReadonly();

  /** Whether "Train a model" is meaningful for this dataset right now — see `canStartTrainingDataset`'s own doc comment. */
  readonly canStartTraining = computed(() => canStartTrainingDataset(this.dataset()));
  /** The "Start training" button's own form-validity gate — independent of {@link canStartTraining} so the template can explain *why* it's disabled (no labeled samples vs. an invalid form) without conflating the two. */
  readonly canSubmitTraining = computed(() => canSubmitTrainingRequest(this.baseModel(), this.epochs(), this.starting()));

  private readonly recentJobsSignal = signal<readonly TrainingJobResponse[]>([]);
  readonly recentJobs = this.recentJobsSignal.asReadonly();

  private currentDatasetId = '';

  constructor() {
    void this.loadBaseModelOptions();
  }

  streamLabel(stream: { readonly streamId: string; readonly deviceId: string }): string {
    return streamCaptureLabel(stream, this.fleet.devices());
  }

  sampleImageUrl(sampleId: string): string {
    return this.api.sampleImageUrl(sampleId);
  }

  /** Called once by the page's own constructor `effect()` on every `datasetId` route-input change. */
  load(datasetId: string): void {
    this.currentDatasetId = datasetId;
    this.captureStreamId.set('');
    this.statusFilter.set('PENDING');
    void this.fetchDataset(datasetId);
    void this.fetchSamples(datasetId, 'PENDING');
    void this.loadRecentJobs(datasetId);
  }

  setStatusFilter(status: SampleStatus): void {
    this.statusFilter.set(status);
    void this.fetchSamples(this.currentDatasetId, status);
  }

  async capture(): Promise<void> {
    const streamId = this.captureStreamId();
    if (!streamId || !this.currentDatasetId || this.capturing()) {
      return;
    }
    this.capturing.set(true);
    try {
      await this.api.captureSample(streamId, this.currentDatasetId);
      this.toasts.ok('Captured a frame — review it below.');
      await this.fetchDataset(this.currentDatasetId);
      if (this.statusFilter() === 'PENDING') {
        await this.fetchSamples(this.currentDatasetId, 'PENDING');
      }
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.capturing.set(false);
    }
  }

  /** Quick "not useful" skip straight from the grid — keeps the sample's existing (unreviewed) annotations, only flips its status, mirroring the sample editor's own Discard action. */
  async discard(sample: TrainingSample): Promise<void> {
    try {
      await this.api.putSampleAnnotations(sample.id, { status: 'DISCARDED', annotations: sample.annotations });
      await this.fetchDataset(this.currentDatasetId);
      await this.fetchSamples(this.currentDatasetId, this.statusFilter());
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }

  /** Re-reads the current sample list — the sample editor calls this (via the page's own navigation back) so a save made there is reflected the moment the operator returns to the grid. */
  async refreshSamples(): Promise<void> {
    await this.fetchSamples(this.currentDatasetId, this.statusFilter());
    await this.fetchDataset(this.currentDatasetId);
  }

  /**
   * Starts a fine-tune run against this dataset, then navigates to its own progress page. Guarded
   * by the same predicates the "Start training" button's `[disabled]` binding reads, so a stray
   * call (e.g. a double-submit racing the navigation) is a safe no-op rather than a duplicate
   * request.
   */
  async startTraining(): Promise<void> {
    const dataset = this.dataset();
    const epochs = this.epochs();
    if (!dataset || epochs === null || !this.canStartTraining() || !this.canSubmitTraining()) {
      return;
    }
    this.starting.set(true);
    try {
      const job = await this.api.startTrainingJob(dataset.id, { baseModel: this.baseModel().trim(), epochs });
      this.toasts.ok('Training started — watching progress…');
      await this.router.navigate(['/manage/training/jobs', job.jobId]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.starting.set(false);
    }
  }

  private async fetchDataset(datasetId: string): Promise<void> {
    this.loading.set(true);
    this.notFound.set(false);
    try {
      this.dataset.set(await this.api.getDataset(datasetId));
    } catch (error) {
      if (error instanceof HttpErrorResponse && (error.status === 404 || error.status === 403)) {
        this.notFound.set(true);
      } else {
        console.warn(`${LOG_PREFIX} failed to load dataset ${datasetId}`, { error });
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async fetchSamples(datasetId: string, status: SampleStatus): Promise<void> {
    this.samplesLoading.set(true);
    try {
      const response = await this.api.datasetSamples(datasetId, status);
      this.samples.set(response.samples);
    } catch (error) {
      console.warn(`${LOG_PREFIX} failed to load samples for dataset ${datasetId}`, { error });
      this.toasts.error(describeHttpError(error));
    } finally {
      this.samplesLoading.set(false);
    }
  }

  /** Best-effort — the base-model field just falls back to free text with no suggestions on
   *  failure; never blocks the page (mirrors `DatasetsFacade#loadCategories`'s own posture). Fetched
   *  once per page instance, not per `datasetId`, since the registry is dataset-independent. */
  private async loadBaseModelOptions(): Promise<void> {
    try {
      const response = await this.api.registryModels();
      this.baseModelOptionsSignal.set(response.models.map((model) => model.id));
    } catch {
      // Left at [] — see this method's own doc comment.
    }
  }

  /** Best-effort — the "Training jobs" card just doesn't render on failure; never blocks the page.
   *  `GET /api/training/jobs` is unscoped/global, so this filters to the dataset actually being
   *  viewed client-side. Not polled — see this class's own doc comment for why. */
  private async loadRecentJobs(datasetId: string): Promise<void> {
    try {
      const response = await this.api.trainingJobs();
      this.recentJobsSignal.set(response.jobs.filter((job) => job.datasetId === datasetId));
    } catch {
      this.recentJobsSignal.set([]);
    }
  }
}
