import type { ActiveStream, Dataset, Device } from '../../core/api/models';

/**
 * Pure logic behind `DatasetDetailPage` (`/manage/training/:datasetId`, docs/CV-TRAINING-PLAN.md
 * Wave T5) — the capture-stream picker's labels and the export button's enabled predicate.
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

/** Exporting an all-`PENDING`/`DISCARDED` dataset would produce an empty (if valid) zip — gate the
 *  button on at least one `LABELED` sample so "Export" only ever fires once there's something to fine-tune on. */
export function canExportDataset(dataset: Pick<Dataset, 'sampleCounts'> | null): boolean {
  return (dataset?.sampleCounts.LABELED ?? 0) > 0;
}

/** A sane starting point for the "Train a model" form's base-model field — a small, fast-to-fine-
 *  tune YOLO checkpoint (docs/CV-TRAINING-PLAN.md's own frozen wire-contract example). Prefilled,
 *  not forced — the field stays free text (with a `<datalist>` of whatever the CV registry already
 *  knows about) so an operator can fine-tune from an existing custom model just as easily as a
 *  stock checkpoint. */
export const DEFAULT_BASE_MODEL = 'yolo26n.pt';

/** A sane default epoch count for the same form — matches docs/CV-TRAINING-PLAN.md's own frozen
 *  wire-contract example. */
export const DEFAULT_TRAINING_EPOCHS = 50;

/** Whether "Train a model" is meaningful right now — the same ≥1-`LABELED`-sample gate
 *  {@link canExportDataset} already encodes (a fine-tune has nothing to learn from an
 *  all-`PENDING`/`DISCARDED` dataset either, matching `LabelingService#export`'s own filter that a
 *  training run would ultimately consume). Kept as its own named predicate so
 *  `DatasetDetailFacade`/`dataset-detail.html` can gate the Train card without reading "canExport"
 *  for an unrelated action. */
export function canStartTrainingDataset(dataset: Pick<Dataset, 'sampleCounts'> | null): boolean {
  return canExportDataset(dataset);
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

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB'] as const;

/** A human-readable size for `DatasetExport#sizeBytes` (e.g. "2.3 MB") — one decimal place above bytes themselves, base-1024. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < BYTE_UNITS.length - 1) {
    value /= 1024;
    unitIndex++;
  }
  return `${value.toFixed(1)} ${BYTE_UNITS[unitIndex]}`;
}
