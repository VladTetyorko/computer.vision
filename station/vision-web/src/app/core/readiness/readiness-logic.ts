import { HttpErrorResponse } from '@angular/common/http';
import { FEATURE_KEYS } from '../api/models';
import type { ApiErrorBody, FeatureStatus, ReadinessReport, ReadinessRow, ReadinessVerdict, RemedyKind } from '../api/models';
import { humanAge } from '../telemetry/telemetry-logic';

/**
 * Pure, Angular-free rendering/sorting helpers for the drone-onboarding readiness surfaces
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1, wave O6): the fleet readiness board
 * (`features/preflight`, `/operate/preflight`) and the per-asset readiness report
 * (`features/readiness`, `/assets/:assetId/readiness`). Every server value
 * (`ReadinessVerdict`/`FeatureStatus`/`RemedyKind`) is rendered here, never re-derived — this module
 * supplies presentation only (tone/label/sort/rollup); it never recomputes a verdict or a status the
 * backend already decided (`ReadinessService`, contexts/vision-flight).
 *
 * **This is not `derivePreflight`'s replacement.** `core/telemetry/flight-state-logic.ts#derivePreflight`
 * reads the *currently tracked live telemetry sample* for the Fly cockpit's own instrument-panel
 * checklist (video feed / telemetry link / GPS fix / battery / armable) — a ground-check glance at
 * "right now". The `ReadinessReport` this module renders reads the *last profiled MAVLink snapshot*
 * (a `VehicleProfile`, possibly stale, requires an explicit `POST .../probe`) against the seeded
 * `feature_requirements` table — a different question ("does this vehicle's link satisfy what this
 * platform's features need"), answered server-side. Both stay in the app; see
 * `station/vision-web/MODULE.md`'s O6 entry for the full split.
 */

/**
 * The exact 409 body `NoopVehicleConfigPort` (`station/vision-app`) produces when
 * `vision.onboarding.probe.enabled` is `false` (the default, D17) — verified against that class's
 * own `DISABLED_MESSAGE` constant and its wiring test's literal assertion.
 */
const PROBE_DISABLED_MESSAGE = 'vehicle probing is disabled (vision.onboarding.probe.enabled)';

/**
 * True only for the flag-off refusal — `OnboardingController`'s own javadoc documents that the same
 * `409` status also covers "candidate unreachable", so the status code alone cannot tell the two
 * apart; this checks the exact message text instead, mirroring `core/api-error.ts#serverMessage`'s
 * own defensive body-shape handling. Lets the wizard's Verify step (and any "probe now"/"remediate"
 * action) render "not enabled on this deployment" as a first-class state — the same disabled-signal
 * pattern `core/training/training-store.ts`/`features/models/models-facade.ts` established for their
 * own single flag-gated call — rather than a generic failure toast for the common, expected case
 * (probing defaults off).
 */
export function isProbeDisabledError(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse) || error.status !== 409) {
    return false;
  }
  const body = error.error as Partial<ApiErrorBody> | string | null;
  const message = typeof body === 'string' ? body : (body?.message ?? null);
  return message === PROBE_DISABLED_MESSAGE;
}

/**
 * The eleven frozen v1 features' seeded labels (`storage/persistence`'s `V18__feature_requirements.sql`,
 * verified against source) — needed because `ReadinessRowResponse` (the fleet board's own compact
 * shape) carries only `featureKey -> status`, no label, unlike the full `ReadinessReportResponse`
 * which already carries one per row. Kept here rather than duplicated per firmware: v1 seeds
 * `ardupilot` only, one label per key regardless of firmware (`FeatureRequirement`'s own javadoc:
 * the key, not the label, is what a firmware-specific row varies).
 */
const FEATURE_LABELS: Readonly<Record<string, string>> = {
  'map-position': 'Map position',
  'preflight-checks': 'Preflight checklist',
  'ground-speed': 'Ground speed',
  'link-quality': 'Link quality',
  'failsafe-banners': 'Failsafe banners',
  battery: 'Battery',
  'visual-geolocation': 'Visual geolocation',
  'fleet-identity': 'Fleet identity',
  'command-tx': 'Command TX',
  'rc-relay': 'RC relay',
  'video-ingest': 'Video ingest',
};

/** A feature key's seeded label — the key itself for anything unrecognised (a future firmware/feature this build doesn't know about yet), never a blank cell. */
export function featureLabel(key: string): string {
  return FEATURE_LABELS[key] ?? key;
}

/** Chip tone for a `ReadinessVerdict` — status meaning only, never a categorical hue (frontend-style §3). */
export function verdictTone(verdict: ReadinessVerdict): 'ok' | 'danger' | 'muted' {
  switch (verdict) {
    case 'GO':
      return 'ok';
    case 'NO_GO':
      return 'danger';
    case 'UNKNOWN':
      return 'muted';
  }
}

/** Operator-facing label for a `ReadinessVerdict`. */
export function verdictLabel(verdict: ReadinessVerdict): string {
  switch (verdict) {
    case 'GO':
      return 'Go';
    case 'NO_GO':
      return 'No-go';
    case 'UNKNOWN':
      return 'Unknown';
  }
}

/** Chip/dot tone for one feature row's `FeatureStatus`. */
export function featureStatusTone(status: FeatureStatus): 'ok' | 'warn' | 'danger' | 'muted' {
  switch (status) {
    case 'READY':
      return 'ok';
    case 'DEGRADED':
      return 'warn';
    case 'MISSING':
      return 'danger';
    case 'UNKNOWN':
      return 'muted';
  }
}

/** Operator-facing label for a `FeatureStatus`. */
export function featureStatusLabel(status: FeatureStatus): string {
  switch (status) {
    case 'READY':
      return 'Ready';
    case 'DEGRADED':
      return 'Degraded';
    case 'MISSING':
      return 'Missing';
    case 'UNKNOWN':
      return 'Unknown';
  }
}

/** Operator-facing sentence for a `RemedyKind`, or `null` when the row carries no remedy at all (a `READY` row, or one with nothing automatable — `FeatureReadiness#remedy()`'s own doc comment). */
export function remedyLabel(remedy: RemedyKind | null): string | null {
  if (remedy === null) {
    return null;
  }
  switch (remedy) {
    case 'MESSAGE_INTERVAL':
      return 'Send a live message-rate request';
    case 'PARAM_WRITE':
      return 'Write a vehicle parameter';
    case 'CLI_SCRIPT':
      return 'Generate a CLI script to run by hand';
    case 'MANUAL':
      return 'Requires manual action outside this platform';
  }
}

/**
 * Whether `remedy` is one this wave can actually dispatch from a button. `MESSAGE_INTERVAL` is the
 * only mechanism `RemediationOrchestrator` (O5) wires end to end today — `PARAM_WRITE` is
 * deliberately never dispatched yet (that orchestrator's own javadoc), `CLI_SCRIPT` only generates
 * text for the operator to run by hand, and `MANUAL` is explicitly operator-side. A row whose remedy
 * fails this check still shows {@link remedyLabel}'s sentence — it just renders no "Remediate"
 * button next to it.
 */
export function isRemediable(remedy: RemedyKind | null): remedy is 'MESSAGE_INTERVAL' {
  return remedy === 'MESSAGE_INTERVAL';
}

/** Tone for one remediation action's outcome. */
export function outcomeTone(outcome: 'ACCEPTED' | 'DENIED' | 'NO_ACK' | 'UNSUPPORTED'): 'ok' | 'danger' | 'warn' | 'muted' {
  switch (outcome) {
    case 'ACCEPTED':
      return 'ok';
    case 'DENIED':
      return 'danger';
    case 'NO_ACK':
      return 'warn';
    case 'UNSUPPORTED':
      return 'muted';
  }
}

/** Operator-facing label for a remediation outcome. */
export function outcomeLabel(outcome: 'ACCEPTED' | 'DENIED' | 'NO_ACK' | 'UNSUPPORTED'): string {
  switch (outcome) {
    case 'ACCEPTED':
      return 'Accepted';
    case 'DENIED':
      return 'Denied';
    case 'NO_ACK':
      return 'No response';
    case 'UNSUPPORTED':
      return 'Not supported';
  }
}

/** `row.features`, ordered: the frozen `FEATURE_KEYS` sequence first (so the fleet board's own rollup reads in the report's canonical order), then any unrecognised keys appended alphabetically — a `Record`'s own key order is not guaranteed stable by JSON, so this is what makes {@link fleetRowAttention} deterministic across renders. */
function orderedFeatureEntries(features: Readonly<Record<string, FeatureStatus>>): readonly { readonly feature: string; readonly status: FeatureStatus }[] {
  const known = FEATURE_KEYS.filter((key) => key in features).map((feature) => ({ feature, status: features[feature] }));
  const knownSet: readonly string[] = FEATURE_KEYS;
  const unknown = Object.keys(features)
    .filter((key) => !knownSet.includes(key))
    .sort()
    .map((feature) => ({ feature, status: features[feature] }));
  return [...known, ...unknown];
}

/**
 * The fleet board's compact per-row rollup: every non-`READY` feature's label, comma-joined, capped
 * at `limit` with a "+N more" tail — never a column per feature (`dataviz`'s own "compact rollups
 * over per-row chip clutter" — eleven per-row chips would be exactly that clutter, and "one chip per
 * row max" is this app's own table rule, frontend-style §5). `null` when every evaluated feature is
 * `READY` — the caller renders that as a plain dash, never a fabricated "All clear" label, since a
 * row with zero evaluated features (an unrecognised firmware) looks identical and is just as honest
 * an answer either way.
 */
export function fleetRowAttention(row: ReadinessRow, limit = 2): string | null {
  const notReady = orderedFeatureEntries(row.features).filter((entry) => entry.status !== 'READY');
  if (notReady.length === 0) {
    return null;
  }
  const labels = notReady.map((entry) => featureLabel(entry.feature));
  return labels.length <= limit ? labels.join(', ') : `${labels.slice(0, limit).join(', ')} +${labels.length - limit} more`;
}

const VERDICT_PRIORITY: Readonly<Record<ReadinessVerdict, number>> = { NO_GO: 0, UNKNOWN: 1, GO: 2 };

/**
 * Fleet-board row order: `NO_GO` first (needs attention), then `UNKNOWN`, then `GO` — ties broken
 * alphabetically by display name (`sortAssetsByName`'s own tie-break, mirrored). **This only orders
 * the list; it never filters or hides a row.** OQ3 (docs/plans/active/DRONE-ONBOARDING-PLAN.md §10 — "does a
 * NO-GO verdict block, or only advise?") is unanswered by the plan; this wave resolves it as
 * advisory-only pending an operator's own answer — surfacing `NO_GO` prominently (sort order, chip
 * tone) is exactly as far as that resolution goes. No control anywhere in this wave is disabled,
 * hidden, or gated on a verdict.
 */
export function sortReadinessRows(rows: readonly ReadinessRow[]): readonly ReadinessRow[] {
  return [...rows].sort((a, b) => {
    const byVerdict = VERDICT_PRIORITY[a.verdict] - VERDICT_PRIORITY[b.verdict];
    return byVerdict !== 0 ? byVerdict : a.displayName.localeCompare(b.displayName, undefined, { sensitivity: 'base' });
  });
}

/** Fleet-wide verdict counts — the board's own stat-tile row (`dataviz` skill's stat-tile-over-chart-clutter guidance for a single headline rollup). */
export interface ReadinessCounts {
  readonly go: number;
  readonly noGo: number;
  readonly unknown: number;
}

export function readinessCounts(rows: readonly ReadinessRow[]): ReadinessCounts {
  let go = 0;
  let noGo = 0;
  let unknown = 0;
  for (const row of rows) {
    if (row.verdict === 'GO') go++;
    else if (row.verdict === 'NO_GO') noGo++;
    else unknown++;
  }
  return { go, noGo, unknown };
}

// --- Per-asset probe state (docs/plans/active/OPERATOR-UX-6-PLAN.md finding R1) ----------------

/**
 * Whether this report reflects at least one completed vehicle probe. `profileObservedAt` is a
 * literal `null` on the wire (never omitted, C7) exactly when `DefaultReadinessService#evaluate`
 * found no `VehicleProfile` at all for this asset — the same condition under which *every* one of
 * the eleven frozen feature rows' own `detail` reads "Never probed." (`evaluateFeature`, verified
 * against source: `profile == null` is the one branch that returns that exact detail string, for
 * every feature key at once). Checking this single report-level fact is equivalent to, and cheaper
 * than, scanning every row's detail text — and it's the fact `ReadinessPage`'s own header needs
 * (R1: an `Evaluated …` timestamp sitting next to eleven "Never probed." rows implies something was
 * evaluated, when nothing ever was — the header now says exactly that instead, see `readiness.html`).
 */
export function hasBeenProbed(report: Pick<ReadinessReport, 'profileObservedAt'>): boolean {
  return report.profileObservedAt !== null;
}

/**
 * Why `ReadinessPage`'s "Probe now" button is disabled, or `null` when it isn't blocked (R1: "a
 * live Probe now button on a rover offline for 18h" — a probe is a live MAVLink message-interval
 * request that then waits on a fresh sample, which needs the link a non-streaming asset does not
 * have; clicking it today just times out silently). The age renders through `humanAge`
 * (`core/telemetry/telemetry-logic.ts`) — this app's one duration vocabulary, already shared by the
 * OSD, the Controller drawer and the `/fly` picker's own offline chips (`core/fleet/triage-logic.ts
 * #offlineLabel` uses the same function) — never a new formatter. `lastUsedAt` absent (an asset
 * that has never reported at all) degrades to an honest "never been online" rather than fabricating
 * an age for a timestamp that does not exist.
 */
export function probeBlockedReason(streaming: boolean, lastUsedAt: string | undefined, nowMs: number): string | null {
  if (streaming) {
    return null;
  }
  if (!lastUsedAt) {
    return 'Needs a live link — this vehicle has never been online';
  }
  const ageSeconds = Math.max(0, (nowMs - Date.parse(lastUsedAt)) / 1000);
  return `Needs a live link — vehicle is offline (${humanAge(ageSeconds)})`;
}
