import type { BatteryThresholds, RcThresholds } from '../../api/models';
import { DEFAULT_BATTERY_THRESHOLDS, DEFAULT_RC_THRESHOLDS } from '../thresholds-logic';

/**
 * The one served severity source for battery urgency (S3, docs/plans/active/ASSET-FLOWS-PLAN.md §2
 * D6) plus the neutral-stick arm gate's tolerance (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2) —
 * `GET /api/ops/thresholds` read once per SPA session; migrated off `ThresholdsStore` per
 * docs/plans/done/NGRX-MIGRATION-PLAN.md wave N7 (the plan's own "fetch-once, no poller" trap:
 * `vision.ops.battery.*`/`vision.ops.rc.*` are process-level config, not telemetry, so one fetch per
 * session is enough — see `thresholds.effects.ts`).
 *
 * `battery`/`rc` are **always** a real, usable value — the initial state below already carries the
 * honest defaults, and a failed `refresh()` leaves them exactly there (CLAUDE.md "degrade honestly":
 * never a blocked page, never a fabricated value).
 */
export interface ThresholdsState {
  readonly battery: BatteryThresholds;
  readonly rc: RcThresholds;
  /** `false` until the first fetch ever succeeds — `battery`/`rc` are already the honest defaults in the meantime. */
  readonly loaded: boolean;
  readonly error: string | undefined;
}

export const initialThresholdsState: ThresholdsState = {
  battery: DEFAULT_BATTERY_THRESHOLDS,
  rc: DEFAULT_RC_THRESHOLDS,
  loaded: false,
  error: undefined,
};
