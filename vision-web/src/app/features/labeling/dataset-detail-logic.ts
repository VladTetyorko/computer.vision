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
