import type { AssetAttention, BatteryThresholds } from '../api/models';
import { TELEMETRY_AGE_RED_SECONDS, humanAge } from '../telemetry/telemetry-logic';
import { gpsSeverity } from '../telemetry/flight-state-logic';
import { geofenceBreachReasonText, type GeofenceBreach } from '../geofence/geofence-logic';
import { DEFAULT_BATTERY_THRESHOLDS } from '../ops/thresholds-logic';

/**
 * Pure, Angular-free "does this asset need attention right now" rules — originally
 * `features/command/command-logic.ts`'s own "Attention rules" section, moved here
 * (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4) when `features/reports/reports-logic.ts` needed the identical
 * rules for the Inventory reports page's own attention list. This codebase has no precedent for one
 * page importing another page's module (see `core/fleet/device-logic.ts`'s doc comment for the
 * original precedent this follows, most recently repeated by `core/telemetry/telemetry-logic.ts#trackingIdChanged`) —
 * a shared `core/` home was used instead. `features/command/command-logic.ts` re-exports every name
 * below so its own pre-existing import sites (`asset-panel.ts`, `command-facade.ts`,
 * `command-logic.spec.ts`) keep working verbatim; only `EntityRow`/`buildEntityRows`/`rowRank` (the
 * entity-rail sort, Command-specific) and the layout-grid arithmetic stayed behind in that file.
 *
 * Every rule/threshold/ranking below is byte-for-byte unchanged from its pre-move behavior.
 */

// --- Attention rules --------------------------------------------------------------------------

/**
 * Telemetry older than this, on a *currently streaming* asset, is itself an attention reason.
 * Reuses `core/telemetry/telemetry-logic.ts#TELEMETRY_AGE_RED_SECONDS` directly rather than
 * re-deriving the same threshold under a new name.
 */
export const TELEMETRY_STALE_MS = TELEMETRY_AGE_RED_SECONDS * 1000;

export type AttentionReasonKind =
  | 'geofence-breach'
  | 'failsafe'
  | 'battery-critical'
  | 'telemetry-stale'
  | 'battery-low'
  | 'gps-degraded'
  | 'pipeline-error'
  | 'open-events';
export type AttentionSeverity = 'critical' | 'warning';

export interface AttentionReason {
  readonly kind: AttentionReasonKind;
  readonly severity: AttentionSeverity;
  /** A complete sentence — the panel's "why" line joins one or more of these with a space. */
  readonly text: string;
}

/**
 * How urgently each reason kind reads, highest first — an asset's overall rank is the max of its
 * own triggered reasons' ranks (see `features/command/command-logic.ts#buildEntityRows`), so an
 * asset with *any* higher-rank reason always outranks one with only lower-rank reasons, regardless
 * of how many of the latter it has.
 *
 * `geofence-breach` (docs/plans/done/OPS-CORE-PLAN.md §G-c) is the very top rank, above even `failsafe` — an
 * aircraft that has physically crossed a keep-out/keep-in boundary is an active, external,
 * safety-and-legal-exposure event happening *right now* to something a manager doesn't control the
 * way a failsafe (an onboard, self-correcting response) already is; it outranks every other signal
 * precisely because a breach can co-occur with any of them and still needs to be the first thing a
 * manager's eye lands on.
 *
 * `failsafe` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d) is the next rank, above battery-critical — a flight
 * controller reporting an active failsafe is otherwise the single most urgent "this drone needs
 * attention right now" signal this app has, worse than a low/critical battery reading alone (a
 * failsafe can itself be *caused* by one, but the failsafe state is the more actionable fact).
 * Battery-critical and telemetry-stale share the next two ranks deliberately, both above
 * battery-low: a dead battery mid-flight and a lost telemetry link mid-flight are the same kind of
 * "this drone may not come back" risk. `gps-degraded` ranks below battery-low but above
 * `pipeline-error` — a degraded fix matters, but a battery running low is the more universally
 * urgent of the two. `pipeline-error` (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.4) ranks below every
 * flight-safety reason above it — a broken detection pipeline is a perception-quality problem, not a
 * "this drone may not come back" one — but above open-events, since a stream that stopped seeing
 * *anything* is a more actionable fact than an already-open, already-triaged detection event. Open
 * detection events rank lowest — informational, not a safety condition.
 */
export const REASON_RANK: Readonly<Record<AttentionReasonKind, number>> = {
  'geofence-breach': 8,
  failsafe: 7,
  'battery-critical': 6,
  'telemetry-stale': 5,
  'battery-low': 4,
  'gps-degraded': 3,
  'pipeline-error': 2,
  'open-events': 1,
};

/** `'unknown'` (no reading yet) never triggers a reason — never a fabricated tier from no data. */
export type BatteryAttentionSeverity = 'critical' | 'warning' | 'ok' | 'unknown';

/**
 * The single battery-severity rule every consumer uses — a Command rail row's chip color, its
 * detail panel's own battery fact, and the Reports attention table all derive from this one
 * function, so "when is a battery reading worth calling out" is answered in exactly one place.
 *
 * `thresholds` defaults to {@link DEFAULT_BATTERY_THRESHOLDS} — every existing call site (and every
 * pre-S3 test) that doesn't thread the served `GET /api/ops/thresholds` value still gets a real,
 * usable classification. Boundaries are inclusive (`<=`), matching the served contract's own "at/
 * below" wording (S3, docs/plans/active/ASSET-FLOWS-PLAN.md §2 D6) — the same comparison
 * `core/telemetry/telemetry-logic.ts#batterySeverity` now uses, so the cockpit OSD and this fleet
 * rule can no longer disagree at the boundary value itself, which they did before this wave (the OSD
 * used `<=` against its own 20/45, this file used strict `<` against its own 20/10).
 */
export function batteryAttentionSeverity(
  percent: number | undefined,
  thresholds: BatteryThresholds = DEFAULT_BATTERY_THRESHOLDS,
): BatteryAttentionSeverity {
  if (percent === undefined) {
    return 'unknown';
  }
  if (percent <= thresholds.criticalPercent) {
    return 'critical';
  }
  return percent <= thresholds.warningPercent ? 'warning' : 'ok';
}

function batteryReason(percent: number | undefined, thresholds?: BatteryThresholds): AttentionReason | undefined {
  const severity = batteryAttentionSeverity(percent, thresholds);
  if (severity === 'critical') {
    return { kind: 'battery-critical', severity: 'critical', text: `Battery critical at ${Math.round(percent!)}%.` };
  }
  if (severity === 'warning') {
    return { kind: 'battery-low', severity: 'warning', text: `Battery low at ${Math.round(percent!)}%.` };
  }
  return undefined;
}

/**
 * Telemetry stale *while streaming* only — an asset that isn't currently flying reporting old
 * telemetry is expected (it landed a while ago), not itself an urgent, right-now attention item the
 * way a live drone going quiet is.
 */
function telemetryReason(asset: AssetAttention): AttentionReason | undefined {
  if (!asset.streaming || asset.telemetryAgeMs === undefined || asset.telemetryAgeMs <= TELEMETRY_STALE_MS) {
    return undefined;
  }
  const ageSeconds = Math.round(asset.telemetryAgeMs / 1000);
  return {
    kind: 'telemetry-stale',
    severity: 'critical',
    text: `Telemetry stale for ${ageSeconds}s while streaming.`,
  };
}

function openEventsReason(openEventCount: number): AttentionReason | undefined {
  if (openEventCount <= 0) {
    return undefined;
  }
  return {
    kind: 'open-events',
    severity: 'warning',
    text: `${openEventCount} open detection ${openEventCount === 1 ? 'event' : 'events'}.`,
  };
}

/**
 * `asset.failsafe` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d, `AssetAttention`'s own field) — `undefined`/
 * `false` never trigger a reason, only an explicit `true` (never fabricated from absent
 * flight-controller data). States what the aircraft is doing, not an instruction — same poka-yoke
 * rule `core/telemetry/flight-state-logic.ts#flightBanner` follows for the cockpit's own banner text.
 */
function failsafeReason(asset: AssetAttention): AttentionReason | undefined {
  if (asset.failsafe !== true) {
    return undefined;
  }
  return { kind: 'failsafe', severity: 'critical', text: 'Failsafe active — returning to home.' };
}

/**
 * `gpsFixType` is deliberately a *parameter*, not read off `AssetAttention` — the fleet-summary DTO
 * doesn't surface GPS quality (only `flightMode`/`armed`/`failsafe` do, see that interface's own doc
 * comment), so a caller with access to this asset's live marker (`core/map/map-logic.ts#FleetMarker`)
 * passes its `gpsFixType` in; a caller with no marker for this asset (not currently plotted/live)
 * simply omits it, and this reason never fires — "unknown" silently means "not evaluated", not "ok".
 * Reuses `flight-state-logic.ts#gpsSeverity` rather than re-deriving the same fix-quality tiers.
 *
 * **Live-only, like `telemetryReason`** (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N2, §2 N2) — an asset
 * that isn't currently streaming was never trying to get a fix right now, so it is never CRIT/WARN
 * for lacking one; a stale `gpsFixType` left over from an earlier session is not itself a live safety
 * condition. Only reachable in practice while `FleetMarker.gpsFixType` is populated at all (today,
 * `buildMarker`'s `offline` bucket never sets it — see that function's own doc comment), but the
 * guard is asserted here rather than left as an accident of the marker shape upstream.
 */
function gpsDegradedReason(asset: AssetAttention, gpsFixType: number | undefined): AttentionReason | undefined {
  if (!asset.streaming || gpsFixType === undefined) {
    return undefined;
  }
  const severity = gpsSeverity(gpsFixType);
  if (severity === 'ok') {
    return undefined;
  }
  return {
    kind: 'gps-degraded',
    severity: severity === 'critical' ? 'critical' : 'warning',
    text: `GPS fix degraded (fix type ${gpsFixType}).`,
  };
}

/**
 * `geofenceBreaches` (docs/plans/done/OPS-CORE-PLAN.md §G-c, optional) is, like `gpsFixType`, not carried by
 * `AssetAttention` at all — it's derived from the generic `LiveEvent` feed
 * (`core/geofence/geofence-logic.ts#activeGeofenceBreaches`, sourced from `LiveStore.liveEvents()`),
 * not the fleet-summary DTO. An empty/absent array never fires this reason — "no breach known", not
 * "definitely not breaching" (the honest-unknown rule every other optional reason input here follows).
 *
 * **Live-only, like `telemetryReason`/`gpsDegradedReason`** (docs/plans/active/OPERATOR-UX-4-PLAN.md
 * finding N2 follow-up, §2 N2, this cycle's W5 — reproduced live: an ESP32 rover offline 3 days,
 * still reading CRIT for a `KEEP-IN` breach recorded from Null Island before it went offline) — an
 * asset that isn't currently streaming is not physically crossing a boundary *right now*; a breach
 * `LiveEvent` never clears itself on its own (`activeGeofenceBreaches`'s own "an 'enter' opens the
 * concern … a matching 'exit' clears it" contract — nothing emits a synthetic 'exit' when a vehicle
 * simply goes offline mid-breach), so without this gate a historic breach outlives the session that
 * produced it and reads as an active, ongoing safety event forever. `geofence-breach` is this app's
 * single highest-ranked reason (`REASON_RANK`) precisely because it means "happening right now" —
 * an offline asset is simply offline, with its own age, exactly like `gps-degraded`/
 * `telemetry-stale` already read for the same asset state.
 */
function geofenceBreachReason(asset: AssetAttention, breaches: readonly GeofenceBreach[] | undefined): AttentionReason | undefined {
  if (!asset.streaming || !breaches || breaches.length === 0) {
    return undefined;
  }
  return { kind: 'geofence-breach', severity: 'critical', text: geofenceBreachReasonText(breaches) };
}

/**
 * `pipelineErrorDetail` is, like `gpsFixType`/`geofenceBreaches`, not carried by `AssetAttention`
 * itself — it's the asset's own entry (keyed by `asset.streamId`) in
 * `core/system-events/system-events-logic.ts#activePipelineErrorMessagesByStreamId`, itself derived
 * from the generic `LiveEvent` feed. `undefined` means "no *active* pipeline error known for this
 * stream right now" — either none ever happened, or one happened and has since decayed/cleared (see
 * that function's own doc comment for the full decay rule) — never "definitely healthy".
 *
 * No trailing period is appended after `detail` — unlike every other reason's text, `detail` is
 * `LiveEvent.message` verbatim, an arbitrary Java exception message
 * (`StreamPipeline#describeFailure`, `contexts/vision-perception`) this app does not control the
 * punctuation of; appending one blindly risks a double period on messages that already end with one.
 */
function pipelineErrorReason(detail: string | undefined): AttentionReason | undefined {
  if (!detail) {
    return undefined;
  }
  return { kind: 'pipeline-error', severity: 'warning', text: `Detection pipeline error — ${detail}` };
}

/**
 * Every reason `asset` triggers, most severe first. An asset with none of these returns an empty
 * array — "all quiet" for that asset.
 *
 * `gpsFixType` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d, optional), `geofenceBreaches`
 * (docs/plans/done/OPS-CORE-PLAN.md §G-c, optional), and `pipelineErrorDetail`
 * (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.4, optional) are the three reason inputs not carried by
 * `AssetAttention` itself — see `gpsDegradedReason`'s/`geofenceBreachReason`'s/`pipelineErrorReason`'s
 * own doc comments for where a caller sources each. A caller with none in hand (e.g.
 * `features/reports/reports-logic.ts`'s read-only dashboard, which has no live map marker or
 * geofence/pipeline feed to draw from) simply omits all three — those reason kinds never fire for
 * it, honestly "not evaluated" rather than "not present".
 *
 * `thresholds` (S3, docs/plans/active/ASSET-FLOWS-PLAN.md §2 D6) is the served `GET
 * /api/ops/thresholds` value, threaded straight through to `batteryReason` — omitted, every caller
 * still gets {@link DEFAULT_BATTERY_THRESHOLDS}'s own honest fallback (see `batteryAttentionSeverity`'s
 * own doc comment).
 */
export function attentionReasons(
  asset: AssetAttention,
  gpsFixType?: number,
  geofenceBreaches?: readonly GeofenceBreach[],
  pipelineErrorDetail?: string,
  thresholds?: BatteryThresholds,
): readonly AttentionReason[] {
  const reasons = [
    geofenceBreachReason(asset, geofenceBreaches),
    failsafeReason(asset),
    batteryReason(asset.batteryPercent, thresholds),
    telemetryReason(asset),
    gpsDegradedReason(asset, gpsFixType),
    pipelineErrorReason(pipelineErrorDetail),
    openEventsReason(asset.openEventCount),
  ].filter((reason): reason is AttentionReason => reason !== undefined);
  return [...reasons].sort((a, b) => REASON_RANK[b.kind] - REASON_RANK[a.kind]);
}

/**
 * The rail/panel's own "age" column — the freshest telemetry sample's age, or `'—'` when none
 * exists yet. Renders through `humanAge` (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N4, §2 N4 —
 * "one age vocabulary"), not `stream-info-logic.ts#formatDuration` (session *durations*, capped at
 * hours with zero-padded minutes — wrong register for a telemetry sample that can legitimately be
 * days stale, see `humanAge`'s own doc comment).
 */
export function attentionAgeLabel(asset: AssetAttention): string {
  return asset.telemetryAgeMs === undefined ? '—' : `${humanAge(asset.telemetryAgeMs / 1000)} ago`;
}
