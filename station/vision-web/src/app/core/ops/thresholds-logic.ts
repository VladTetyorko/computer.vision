import type { BatteryThresholds, RcThresholds } from '../api/models';

/**
 * Pure, Angular-free constants behind `core/ops/thresholds-store.ts` — the fallback battery severity
 * thresholds (S3, docs/plans/active/ASSET-FLOWS-PLAN.md §2 D6) used only while `GET
 * /api/ops/thresholds` hasn't answered yet or the fetch failed. Mirrored here as a literal, not read
 * off the wire, so `core/fleet/attention-logic.ts#batteryAttentionSeverity` and
 * `core/telemetry/telemetry-logic.ts#batterySeverity` stay pure and synchronously callable with no
 * default at all before the first HTTP round trip resolves — every existing `.spec.ts` boundary test
 * for either function calls them with no `thresholds` argument.
 *
 * These are the same numbers the backend itself ships as `vision.ops.battery.*`'s own defaults
 * (`OpsWiringConfiguration`) — a genuine mismatch here would only ever matter for the instant between
 * app boot and the first `ThresholdsStore` fetch resolving, or for a caller running with the backend
 * unreachable entirely (CLAUDE.md "degrade honestly": a real, usable value, never a blocked page).
 */
export const DEFAULT_BATTERY_THRESHOLDS: BatteryThresholds = Object.freeze({
  warningPercent: 25,
  criticalPercent: 10,
});

/**
 * The neutral-stick arm gate's fallback tolerance (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2) —
 * used while `GET /api/ops/thresholds`'s `rc` field hasn't answered yet, failed, or (BK1 landing in
 * parallel with this wave) simply isn't served yet. Mirrors the backend's own
 * `vision.ops.rc.neutral-tolerance-percent` default of `5`, the same "same number on both sides of
 * the wire, one written as a literal here" convention {@link DEFAULT_BATTERY_THRESHOLDS} already
 * follows above.
 */
export const DEFAULT_RC_THRESHOLDS: RcThresholds = Object.freeze({
  neutralTolerancePercent: 5,
});
