import type { BatteryThresholds } from '../api/models';

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
