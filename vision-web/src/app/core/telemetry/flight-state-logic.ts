import type { FlightState, TelemetrySample } from '../api/models';
import { BATTERY_LOW_PERCENT, STALE_AFTER_SECONDS, ageSeconds, isStale } from './telemetry-logic';

/**
 * Pure, Angular-free flight-controller-state derivations (docs/FC-INTEGRATIONS-PLAN.md F-d) — GPS
 * fix labeling/severity, the Fly cockpit's failsafe/RTH/landing banner, and the pre-flight
 * checklist — split out so every rule is unit-testable without HTTP or a component, mirroring
 * `core/telemetry/telemetry-logic.ts`'s own split of pure logic from its injectable store.
 *
 * **Poka-yoke, throughout this file**: a field the current sample never reported renders as
 * `'unknown'`/`'—'`, never a fabricated `'ok'`/pass — this codebase refuses to fake a green
 * checkmark from absent data (see `derivePreflight`'s own doc comment, and this app's
 * `AssetAttention`/`FlightState` doc comments in `core/api/models.ts` for the same rule stated at
 * the wire-contract level).
 */

// --- GPS fix (docs/FC-INTEGRATIONS-PLAN.md's research section — `GPS_RAW_INT.fix_type`) --------

/**
 * `GPS_FIX_TYPE`'s own ordinal → a human label. `undefined` (no reading yet) and any value this
 * app doesn't recognize both render `'—'` rather than guessing — an out-of-range value is more
 * likely a future firmware's own extension than something safe to label confidently.
 */
export function gpsFixLabel(fixType: number | undefined): string {
  switch (fixType) {
    case 0:
      return 'No GPS';
    case 1:
      return 'No fix';
    case 2:
      return '2D';
    case 3:
      return '3D';
    case 4:
      return 'DGPS';
    case 5:
      return 'RTK float';
    case 6:
      return 'RTK fixed';
    default:
      return '—';
  }
}

export type GpsSeverity = 'critical' | 'warn' | 'ok';

/**
 * `'critical'` for no reading at all (`undefined`) as much as an explicit 0/1 (no GPS/no fix) —
 * deliberately **not** `'unknown'` the way most other absent-data reads in this app render:
 * this is a live flight instrument (the OSD chip), and "we have no idea where the GPS stands"
 * while telemetry is otherwise flowing is itself the dangerous state, worth the same red as an
 * explicit no-fix. `derivePreflight`'s own GPS row is deliberately different — see its doc comment.
 */
export function gpsSeverity(fixType: number | undefined): GpsSeverity {
  if (fixType === undefined || fixType <= 1) {
    return 'critical';
  }
  return fixType === 2 ? 'warn' : 'ok';
}

// --- Flight banner (docs/FC-INTEGRATIONS-PLAN.md F-d — the Fly cockpit's failsafe/RTH strip) ----

export type FlightBannerKind = 'failsafe' | 'rth' | 'landing';

export interface FlightBanner {
  readonly kind: FlightBannerKind;
  /**
   * States what the *aircraft* is doing, not an instruction to the operator (poka-yoke rule) —
   * e.g. `'FAILSAFE — RETURNING TO HOME'`, never `'Land the drone now'`.
   */
  readonly text: string;
}

/** ArduPilot/INAV RTL-family mode names (docs/FC-INTEGRATIONS-PLAN.md's copter/plane mode tables). */
const RTH_MODES: ReadonlySet<string> = new Set(['RTL', 'SmartRTL', 'QRTL', 'AutoRTL']);

/** ArduPilot/INAV landing-family mode names. */
const LANDING_MODES: ReadonlySet<string> = new Set(['Land', 'QLand', 'AutoLand']);

/**
 * The Fly cockpit's failsafe/RTH/landing banner, derived from the latest sample's `flightState`
 * alone — `null` (never shown) when there is no `flightState` yet (never fabricated from a
 * telemetry-less sample), or when the aircraft is doing nothing banner-worthy.
 *
 * Priority: `failsafe === true` always wins, regardless of `mode` — a failsafe-triggered RTH still
 * reads as `'failsafe'` (red, most severe), not `'rth'` (amber); the message itself still names the
 * mode distinction (an RTL-family mode under failsafe reads "RETURNING TO HOME", any other mode
 * under failsafe — e.g. a Betaflight `FAILSAFE` custom mode with no RTL leg at all — reads the
 * plainer "FAILSAFE ACTIVE" instead of implying a return leg that isn't actually happening).
 * Absent failsafe, a pilot-commanded RTL-family mode is `'rth'` (amber, advisory); a landing-family
 * mode is `'landing'` (amber). Every other mode (including `undefined`) renders no banner at all —
 * ordinary flight is not itself banner-worthy.
 */
export function flightBanner(sample: TelemetrySample | undefined): FlightBanner | null {
  const state = sample?.flightState;
  if (!state) {
    return null;
  }
  if (state.failsafe === true) {
    const isRth = state.mode !== undefined && RTH_MODES.has(state.mode);
    return { kind: 'failsafe', text: isRth ? 'FAILSAFE — RETURNING TO HOME' : 'FAILSAFE ACTIVE' };
  }
  if (state.mode !== undefined && RTH_MODES.has(state.mode)) {
    return { kind: 'rth', text: 'Return to home active' };
  }
  if (state.mode !== undefined && LANDING_MODES.has(state.mode)) {
    return { kind: 'landing', text: 'Landing' };
  }
  return null;
}

// --- Pre-flight checklist (docs/FC-INTEGRATIONS-PLAN.md F-d) ------------------------------------

export type PreflightState = 'ok' | 'fail' | 'unknown';

export interface PreflightItem {
  readonly label: string;
  readonly state: PreflightState;
  readonly detail?: string;
}

function videoFeedItem(hasVideo: boolean, streaming: boolean): PreflightItem {
  const label = 'Video feed';
  if (!hasVideo) {
    return { label, state: 'fail', detail: 'No camera device on this drone.' };
  }
  if (streaming) {
    return { label, state: 'ok' };
  }
  return { label, state: 'unknown', detail: 'Not streaming yet.' };
}

/** Reuses `telemetry-logic.ts#STALE_AFTER_SECONDS`/`isStale` — the exact staleness bar the OSD's own age chip already uses. */
function telemetryLinkItem(sample: TelemetrySample | undefined, ageSecondsValue: number | undefined): PreflightItem {
  const label = 'Telemetry link';
  if (sample === undefined) {
    return { label, state: 'unknown', detail: 'No telemetry received yet.' };
  }
  if (isStale(ageSecondsValue)) {
    return { label, state: 'fail', detail: `Stale for ${Math.round(ageSecondsValue!)}s (past ${STALE_AFTER_SECONDS}s).` };
  }
  return { label, state: 'ok' };
}

function gpsFixItem(fixType: number | undefined): PreflightItem {
  const label = 'GPS fix';
  if (fixType === undefined) {
    return { label, state: 'unknown', detail: 'No GPS reading yet.' };
  }
  if (fixType >= 3) {
    return { label, state: 'ok', detail: gpsFixLabel(fixType) };
  }
  return { label, state: 'fail', detail: gpsFixLabel(fixType) };
}

/** Reuses `telemetry-logic.ts#BATTERY_LOW_PERCENT` (45%) — the same bar `batterySeverity`'s own "low" tier uses. */
function batteryItem(batteryPercent: number | undefined): PreflightItem {
  const label = 'Battery';
  if (batteryPercent === undefined) {
    return { label, state: 'unknown', detail: 'No battery reading yet.' };
  }
  if (batteryPercent >= BATTERY_LOW_PERCENT) {
    return { label, state: 'ok', detail: `${batteryPercent.toFixed(0)}%` };
  }
  return { label, state: 'fail', detail: `${batteryPercent.toFixed(0)}% — below ${BATTERY_LOW_PERCENT}% minimum.` };
}

function armableItem(flightState: FlightState | undefined): PreflightItem {
  const label = 'Armable';
  if (flightState === undefined) {
    return { label, state: 'unknown', detail: 'No flight-controller data yet.' };
  }
  const blockers = flightState.armingBlockers ?? [];
  if (blockers.length > 0) {
    return { label, state: 'fail', detail: blockers.join('; ') };
  }
  return { label, state: 'ok', detail: flightState.armed === true ? 'Armed.' : undefined };
}

/**
 * The Fly cockpit's pre-flight checklist (shown pre-arm/pre-stream, not during flight): exactly 5
 * rows, always in this order — Video feed, Telemetry link, GPS fix, Battery, Armable. Every row is
 * `'unknown'`, never a fabricated `'ok'`/`'fail'`, whenever the underlying reading hasn't arrived
 * yet (poka-yoke — see this file's own top doc comment).
 *
 * `nowMs` is a parameter, not a `Date.now()` read in here, purely so this stays deterministic under
 * test — mirrors `features/fly/fly-logic.ts#lastSeenLabel`'s identical convention; `FlyPage` supplies
 * the real clock (via a `computed()` that re-runs whenever `telemetry.latest()` itself changes, i.e.
 * roughly every poll tick — this checklist is a pre-flight glance, not a live-ticking instrument,
 * which is what the OSD's own age chip already is).
 */
export function derivePreflight(
  sample: TelemetrySample | undefined,
  hasVideo: boolean,
  streaming: boolean,
  nowMs: number,
): readonly PreflightItem[] {
  const age = ageSeconds(sample?.at, nowMs);
  return [
    videoFeedItem(hasVideo, streaming),
    telemetryLinkItem(sample, age),
    gpsFixItem(sample?.flightState?.gpsFixType),
    batteryItem(sample?.batteryPercent),
    armableItem(sample?.flightState),
  ];
}

// --- Diagnostics (docs/FC-INTEGRATIONS-PLAN.md F-e — ArduPilot-only RX extras) ------------------
// Everything below rides `TelemetrySample.extra` (the frozen wire-contract keys, decoded
// server-side only when the source firmware is ArduPilot and actually emits the underlying MAVLink
// message — `WIND`/`VIBRATION`/`EKF_STATUS_REPORT`/`MISSION_CURRENT`/`RANGEFINDER`). A non-ArduPilot
// firmware, or an ArduPilot that simply hasn't sent one of these messages yet, never populates the
// corresponding key(s) — `deriveDiagnostics` omits that row entirely rather than fabricating a
// reading, the same poka-yoke rule as this file's every other derivation.

export type DiagnosticSeverity = 'ok' | 'warn' | 'bad';

/**
 * EKF variance thresholds (docs/FC-INTEGRATIONS-PLAN.md F-e, QGC/ArduPilot convention): `ok` below
 * 0.5, `warn` 0.5–1.0 inclusive, `bad` above 1.0.
 */
export function ekfSeverity(variance: number): DiagnosticSeverity {
  if (variance > 1.0) {
    return 'bad';
  }
  return variance >= 0.5 ? 'warn' : 'ok';
}

/**
 * Vibration thresholds in m/s² (same source): `ok` below 30, `warn` 30–60 inclusive, `bad` above 60.
 */
export function vibeSeverity(ms2: number): DiagnosticSeverity {
  if (ms2 > 60) {
    return 'bad';
  }
  return ms2 >= 30 ? 'warn' : 'ok';
}

/** One row of the Diagnostics card/Status-tab addition — see `deriveDiagnostics`'s own doc comment. */
export interface DiagnosticRow {
  readonly key: 'wind' | 'vibration' | 'ekf' | 'rangefinder' | 'mission';
  readonly label: string;
  readonly value: string;
  /** Absent for the two rows F-e defines no threshold for (rangefinder, mission progress) — plain text, never a fabricated `'ok'`. */
  readonly severity?: DiagnosticSeverity;
}

const SEVERITY_RANK: Record<DiagnosticSeverity, number> = { ok: 0, warn: 1, bad: 2 };

/** The worst (highest-severity, ties broken by raw value) of a non-empty list. */
function worstOf<T extends { readonly value: number }>(entries: readonly T[], severityOf: (value: number) => DiagnosticSeverity): T {
  return entries.reduce((worst, candidate) => {
    const worstRank = SEVERITY_RANK[severityOf(worst.value)];
    const candidateRank = SEVERITY_RANK[severityOf(candidate.value)];
    if (candidateRank !== worstRank) {
      return candidateRank > worstRank ? candidate : worst;
    }
    return candidate.value > worst.value ? candidate : worst;
  });
}

/** `EKF_STATUS_REPORT`'s four variances, each labeled for the row's "label which" requirement. */
const EKF_FIELDS: readonly { readonly key: string; readonly label: string }[] = [
  { key: 'ekfVelocityVariance', label: 'velocity' },
  { key: 'ekfPosHorizVariance', label: 'position (horiz)' },
  { key: 'ekfPosVertVariance', label: 'position (vert)' },
  { key: 'ekfCompassVariance', label: 'compass' },
];

/**
 * F-e's diagnostics rows, derived from `TelemetrySample.extra` alone (both `features/fly/**`'s
 * Diagnostics card and `features/command/asset-panel.ts`'s Status tab call this — the former with a
 * live `TelemetryStore` sample's `extra`, the latter with `FleetMarker.extra`, sourced from the same
 * place; see that interface's own doc comment). Row order is fixed: wind, vibration, EKF,
 * rangefinder, mission — each independently omitted (not `undefined`-valued, entirely absent from
 * the array) when its own key(s) aren't present in `extra`.
 *
 * Vibration's severity is governed by whichever axis (X/Y/Z) reads worst; EKF's by whichever of the
 * four variances reads worst, with that one's own label (`velocity`/`position (horiz)`/
 * `position (vert)`/`compass`) named in the row's value — never averaged, never silently picking the
 * first one present.
 */
export function deriveDiagnostics(extra: Record<string, number> | undefined): readonly DiagnosticRow[] {
  if (!extra) {
    return [];
  }
  const rows: DiagnosticRow[] = [];

  const windSpeed = extra['windSpeedMps'];
  if (windSpeed !== undefined) {
    const direction = extra['windDirectionDegrees'];
    rows.push({
      key: 'wind',
      label: 'Wind',
      value:
        direction === undefined
          ? `${windSpeed.toFixed(1)} m/s`
          : `${windSpeed.toFixed(1)} m/s @ ${Math.round(direction)}°`,
    });
  }

  const vibeAxes: readonly { readonly label: string; readonly value: number | undefined }[] = [
    { label: 'X', value: extra['vibeXMs2'] },
    { label: 'Y', value: extra['vibeYMs2'] },
    { label: 'Z', value: extra['vibeZMs2'] },
  ];
  const presentVibeAxes = vibeAxes.filter(
    (axis): axis is { readonly label: string; readonly value: number } => axis.value !== undefined,
  );
  if (presentVibeAxes.length > 0) {
    const worst = worstOf(presentVibeAxes, vibeSeverity);
    rows.push({
      key: 'vibration',
      label: 'Vibration',
      value: `${presentVibeAxes.map((axis) => `${axis.label} ${axis.value.toFixed(1)}`).join(' · ')} m/s²`,
      severity: vibeSeverity(worst.value),
    });
  }

  const ekfEntries = EKF_FIELDS.map((field) => ({ label: field.label, value: extra[field.key] })).filter(
    (entry): entry is { readonly label: string; readonly value: number } => entry.value !== undefined,
  );
  if (ekfEntries.length > 0) {
    const worst = worstOf(ekfEntries, ekfSeverity);
    rows.push({
      key: 'ekf',
      label: 'EKF',
      value: `${worst.label} ${worst.value.toFixed(2)}`,
      severity: ekfSeverity(worst.value),
    });
  }

  const rangefinder = extra['rangefinderDistanceM'];
  if (rangefinder !== undefined) {
    rows.push({ key: 'rangefinder', label: 'Rangefinder', value: `${rangefinder.toFixed(1)} m` });
  }

  const missionSeq = extra['missionSeq'];
  if (missionSeq !== undefined) {
    rows.push({ key: 'mission', label: 'Mission', value: `Waypoint ${missionSeq}` });
  }

  return rows;
}
