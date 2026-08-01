import type { TrainingJobResponse, TrainingJobState } from '../../core/api/models';

/**
 * Pure logic behind `TrainingJobPage` (`/manage/training/jobs/:jobId`, docs/CV-TRAINING-PLAN.md
 * Phase 2's last web wave) — the live progress view for one fine-tune run: how far along it is,
 * whether it's still worth polling, and how its raw numbers read as plain language. Split out per
 * this app's own convention of keeping component/facade logic thin and unit-testing the
 * framework-free parts directly.
 */

/** `RUNNING` is the only state a poller keeps chasing — `SUCCEEDED`/`FAILED` are settled
 *  (`TrainingJobState`'s own doc comment). `TrainingJobFacade`'s poll callback no-ops once this is
 *  true, so a finished job never keeps generating requests. */
export function isTerminalJobState(state: TrainingJobState): boolean {
  return state !== 'RUNNING';
}

/**
 * `epoch/totalEpochs` as a `0..100` progress-bar percentage. `totalEpochs` is `0` until the first
 * progress message arrives (`TrainingJobResponse`'s own doc comment) — reads as `0%`, not `NaN`, so
 * the bar renders a flat, empty track while the run is still spinning up rather than erroring.
 * Clamped defensively so a stale/late message reporting `epoch > totalEpochs` never overshoots the
 * track. `null` (not yet loaded) also reads as `0%`.
 */
export function jobProgressPercent(job: Pick<TrainingJobResponse, 'epoch' | 'totalEpochs'> | null): number {
  if (!job || job.totalEpochs <= 0) {
    return 0;
  }
  return Math.max(0, Math.min(100, (job.epoch / job.totalEpochs) * 100));
}

/** `true` once at least one epoch has actually reported — the point `loss`/`map50` stop being the
 *  "nothing has arrived yet" placeholder `0`/`0.0` (`TrainingJobResponse`'s own doc comment) and
 *  start being real measurements worth showing instead of a misleadingly literal zero. */
export function hasReportedProgress(job: Pick<TrainingJobResponse, 'epoch'> | null): boolean {
  return (job?.epoch ?? 0) > 0;
}

/** One human word for the job's state chip. */
export function jobStateLabel(state: TrainingJobState): string {
  switch (state) {
    case 'RUNNING':
      return 'Training…';
    case 'SUCCEEDED':
      return 'Trained';
    case 'FAILED':
      return 'Failed';
  }
}

/**
 * The produced model's registry id, once training succeeds (`TrainingJobResponse#message`'s own
 * doc comment: "the produced model's registry id on SUCCEEDED") — `null` for any other state, and
 * for a blank/whitespace-only message (reads as "not reported" rather than an empty chip on the
 * done state).
 */
export function producedModelId(job: Pick<TrainingJobResponse, 'state' | 'message'> | null): string | null {
  if (!job || job.state !== 'SUCCEEDED') {
    return null;
  }
  const trimmed = job.message.trim();
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * The failure reason for a `FAILED` job (`TrainingJobResponse#message`'s own doc comment: "the
 * failure reason on FAILED"). `null` for any other state. Falls back to a plain sentence for the
 * (unexpected) case the backend ever reports a blank one, so the failure notice is never an empty
 * banner.
 */
export function failureMessage(job: Pick<TrainingJobResponse, 'state' | 'message'> | null): string | null {
  if (!job || job.state !== 'FAILED') {
    return null;
  }
  const trimmed = job.message.trim();
  return trimmed.length > 0 ? trimmed : 'Training failed for an unknown reason.';
}

/** A fixed 3-decimal reading for `loss`/`map50`, rendered in the mono/telemetry register
 *  (docs/CV-TRAINING-PLAN.md Phase 2's own task brief: "loss/map50 (mono/telemetry style)") —
 *  callers gate this behind {@link hasReportedProgress} so a real `0.000` is never confused with
 *  "not reported yet". */
export function formatMetric(value: number): string {
  return value.toFixed(3);
}
