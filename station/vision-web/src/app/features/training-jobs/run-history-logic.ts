import type { TrainingRun } from '../../core/api/models';
import { formatMetric, jobStateLabel } from './training-job-logic';

/**
 * Pure logic behind `RunHistoryPage`/`RunDetailPage` (`/manage/training/runs`,
 * `/manage/training/runs/:runId`, docs/plans/active/CV-SETTINGS-PLAN.md §3.3/§6 wave W8) — the
 * **persisted** training-run history, distinct from `training-job-logic.ts`'s live in-flight poll
 * view (`TrainingRun` vs `TrainingJobResponse`, see that interface's own doc comment in
 * `core/api/models.ts`). Reuses {@link jobStateLabel}/{@link formatMetric} from that sibling file
 * rather than a second near-identical copy — same feature folder, same three-state vocabulary; only
 * the null-handling around `loss`/`map50` differs (a persisted run's metrics are `number | null`,
 * not the in-flight `0`-until-reported sentinel), which is what {@link formatRunMetric} adds on top.
 */

/** Newest-first by `startedAt` — the list page's own display order (task brief: "list newest-first").
 *  `VisionApi#getTrainingRuns` already documents the same order server-side; this re-sorts
 *  defensively rather than trusting that blindly, the same posture `sortModelsForDisplay` takes for
 *  the model registry. */
export function sortRunsNewestFirst(runs: readonly TrainingRun[]): readonly TrainingRun[] {
  return [...runs].sort((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt));
}

/** One human word for a run's state chip — delegates to {@link jobStateLabel}, the same three-state vocabulary. */
export function runStateLabel(state: TrainingRun['state']): string {
  return jobStateLabel(state);
}

/** `"<epoch> / <totalEpochs>"` progress reading, mono/telemetry register. */
export function runProgressLabel(run: Pick<TrainingRun, 'epoch' | 'totalEpochs'>): string {
  return `${run.epoch} / ${run.totalEpochs}`;
}

/** A fixed 3-decimal reading for a persisted run's `loss`/`map50`, or `'—'` when not yet reported
 *  (`null` — a run that hasn't produced its first progress message, or never will because it never
 *  started training). Never a fabricated `0.000`. */
export function formatRunMetric(value: number | null): string {
  return value === null ? '—' : formatMetric(value);
}

/** The dataset name to show in the list, falling back to a short id prefix for a blank/whitespace-only name. */
export function runDatasetLabel(run: Pick<TrainingRun, 'datasetName' | 'datasetId'>): string {
  const trimmed = run.datasetName.trim();
  return trimmed.length > 0 ? trimmed : run.datasetId.slice(0, 8);
}

/** `true` once the run has actually produced a model (`SUCCEEDED` with a non-blank `outputModelId`)
 *  — gates the run-detail page's "produced model" link so a `SUCCEEDED` run whose model was since
 *  deleted from the registry, or a run that hasn't reached that state yet, never renders a dead link. */
export function hasProducedModel(run: Pick<TrainingRun, 'outputModelId'>): boolean {
  return typeof run.outputModelId === 'string' && run.outputModelId.trim().length > 0;
}
