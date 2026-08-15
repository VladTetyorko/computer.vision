import type { ActiveStream, Dataset, Device } from '../../core/api/models';

/**
 * Pure logic behind `DatasetDetailPage` (`/manage/training/:datasetId`, docs/plans/done/CV-TRAINING-PLAN.md
 * Wave T5) — the capture-stream picker's labels and the "Train a model" button's enabled predicate.
 */

/**
 * A human label for the capture stream picker — the device's own name when it can be resolved
 * (`FleetStore.devices()`, already loaded app-wide for the header's live count), falling back to a
 * short stream id so an orphaned/renamed-mid-flight stream still shows *something* rather than
 * disappearing from the picker.
 */
export function streamCaptureLabel(
  stream: Pick<ActiveStream, 'streamId' | 'deviceId'>,
  devices: readonly Pick<Device, 'id' | 'name'>[],
): string {
  const device = devices.find((candidate) => candidate.id === stream.deviceId);
  return device ? device.name : `Stream ${stream.streamId.slice(0, 8)}`;
}

/** A sane starting point for the "Train a model" form's base-model field — a small, fast-to-fine-
 *  tune YOLO checkpoint (docs/plans/done/CV-TRAINING-PLAN.md's own frozen wire-contract example). Prefilled,
 *  not forced — the field stays free text (with a `<datalist>` of whatever the CV registry already
 *  knows about) so an operator can fine-tune from an existing custom model just as easily as a
 *  stock checkpoint. */
export const DEFAULT_BASE_MODEL = 'yolo26n.pt';

/** A sane default epoch count for the same form — matches docs/plans/done/CV-TRAINING-PLAN.md's own frozen
 *  wire-contract example. */
export const DEFAULT_TRAINING_EPOCHS = 50;

/** Whether "Train a model" is meaningful right now — an all-`PENDING`/`DISCARDED` dataset has
 *  nothing to fine-tune on (`POST /api/datasets/{id}/train` 400s an all-`PENDING` dataset server-side
 *  too, docs/plans/done/CV-TRAINING-V2-PLAN.md §5), so the button stays gated on at least one `LABELED` sample. */
export function canStartTrainingDataset(dataset: Pick<Dataset, 'sampleCounts'> | null): boolean {
  return (dataset?.sampleCounts.LABELED ?? 0) > 0;
}

/**
 * The "Start training" button's enabled predicate — mirrors `canSubmitDataset`'s own shape: a
 * non-blank base-model checkpoint and a positive integer epoch count are both required
 * (`TrainingJobSpec`'s own compact-constructor checks, mirrored client-side so the button is
 * disabled before the request ever goes out), and never while a previous start is still in flight.
 * `epochs` is `number | null` because that's what an emptied `type="number"` `ngModel` actually
 * produces — treated the same as any other invalid value, not a special case.
 */
export function canSubmitTrainingRequest(baseModel: string, epochs: number | null, starting: boolean): boolean {
  return !starting && baseModel.trim().length > 0 && epochs !== null && Number.isInteger(epochs) && epochs >= 1;
}
