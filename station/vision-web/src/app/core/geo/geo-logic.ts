import { HttpErrorResponse } from '@angular/common/http';
import type {
  ApiErrorBody,
  CorrectionResponse,
  GeoPosition,
  RegionIngestPhase,
  RegionIngestRequest,
  RegionProgressResponse,
  RegionStatus,
} from '../api/models';
import { VISUAL_GEO_DISABLED_MESSAGE } from '../api/models';

// --- Fix validity (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N1 — "a fix is a fix") -----------

/**
 * Whether `position` is a legitimate vehicle fix — `false` for `undefined`, a non-finite lat/lon,
 * or exactly `(0, 0)`. A MAVLink `GLOBAL_POSITION_INT` with no GPS lock reports `(0, 0)` — "Null
 * Island" — not a vehicle actually parked at 0°N 0°E in the Gulf of Guinea; rendering that literal
 * wire value as a real position is what recentred the fleet map on open ocean and printed a
 * confident-looking `0.00000, 0.00000` coordinate for data that was never acquired (N1's own
 * live-app finding). Every reader of a raw position — `AssetSummary.lastKnownPosition`, a
 * `TelemetrySample`'s own lat/lon — should gate through this before plotting or displaying it;
 * `core/map/map-logic.ts#bucketForAsset`/`buildMarker` and `features/asset-detail/**`'s position
 * card are this predicate's first two callers. Deliberately structural (an object shape, not a
 * `GeoPosition`/`TelemetrySample` type import) so it reads either without a cast.
 */
export function hasFix(position: { latitude?: number; longitude?: number } | undefined): boolean {
  if (!position) {
    return false;
  }
  const { latitude, longitude } = position;
  if (latitude === undefined || longitude === undefined) {
    return false;
  }
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    return false;
  }
  return !(latitude === 0 && longitude === 0);
}

/**
 * Pure, Angular-free logic behind wave H6 of `docs/plans/done/VISUAL-GEO-V2-PLAN.md` — the cockpit's
 * divergence chip + detail popover (`features/fly/cockpit`), the `TacticalMap` corrected-track layer
 * (`shared/map/tactical-map/tactical-map.ts`), the replay corrected polyline + divergence band
 * (`features/replay/**`), and the region-manager page (`features/geo/**`). Built entirely against
 * §3.3/§3.4/§3.8's frozen wire contract — H5 (persistence/REST/SSE) lands concurrently — mirroring
 * `core/camera-geo/camera-geo-logic.ts`'s own "one core/<feature>/<feature>-logic.ts per feature
 * area, everything pure lives here regardless of which page/component consumes it" precedent, and
 * its "presentation only, never re-derive a verdict the backend already decided" discipline: every
 * status/divergent/refusal value rendered here is the server's own, this module only shapes it.
 */

// --- Flag-disabled 409 (D9) -----------------------------------------------------------------------

/**
 * True only for the D9 flag-off refusal on any `/api/geo/**` call — mirrors
 * `core/camera-geo/camera-geo-logic.ts#isFixedCameraGeoDisabledError`'s exact shape (status `409` +
 * an exact message match). Unlike that function, there is **no** `detail`-vs-`message` ambiguity to
 * guard against here: D9's own frozen example (`{"error":"CONFLICT","message":"…"}`) is already this
 * app's one real, shipped `ApiErrorBody` envelope, so this only reads `message`.
 */
export function isVisualGeoDisabledError(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse) || error.status !== 409) {
    return false;
  }
  const body = error.error as Partial<ApiErrorBody> | string | null;
  const message = typeof body === 'string' ? body : (body?.message ?? null);
  return message === VISUAL_GEO_DISABLED_MESSAGE;
}

// --- Cockpit divergence chip (§3.8) ----------------------------------------------------------------
//
// "The chip is the alarm's only always-visible surface. Never red for PROBABLE." — satisfied by
// construction: {@link GeoChipTone} has no `'danger'` member at all, so no caller can ever paint this
// chip red regardless of `status`. Only a rising-edge `divergent: true` (§4.5 — CONFIRMED-only by the
// gate's own construction, but this reads the wire's own flag rather than re-deriving it) reaches the
// warning tone.

export type GeoChipTone = 'ok' | 'dim' | 'warn';

/** `'GEO ok'` / `'GEO —'` (no correction yet, or a `NO_FIX`) / `'GEO Δ 84 m'` (divergent) — §3.8's three frozen strings. */
export function geoChipLabel(correction: CorrectionResponse | undefined): string {
  if (!correction || correction.status === 'NO_FIX') {
    return 'GEO —';
  }
  if (correction.divergent) {
    return correction.separationMeters !== undefined ? `GEO Δ ${Math.round(correction.separationMeters)} m` : 'GEO Δ —';
  }
  return 'GEO ok';
}

export function geoChipTone(correction: CorrectionResponse | undefined): GeoChipTone {
  if (!correction || correction.status === 'NO_FIX') {
    return 'dim';
  }
  return correction.divergent ? 'warn' : 'ok';
}

// --- Cockpit detail popover (§3.8 — "why is always one click away", D5) ---------------------------

/** One row of the detail popover's fact grid — the same `{label, value, mono?}` shape `core/camera-geo/camera-geo-logic.ts#FactRow` already established for this app's `<dl class="facts">` convention. */
export interface GeoFactRow {
  readonly label: string;
  readonly value: string;
  readonly mono?: boolean;
}

/**
 * Every §3.8-named popover fact, always rendered (an absent optional reads `'—'`, never a fabricated
 * number) — `status`, `separationMeters`, `radiusMeters`, `inlierCount`, `inlierRatio`,
 * `sequenceSpreadMeters`, `cellCalibrated`, `sequenceConverged`, `regionId`, plus the verbatim
 * `refusal` string, appended only on a `NO_FIX` row. `refusal` is never paraphrased or re-worded —
 * the D5 rule this app already applies to `core/camera-geo/camera-geo-logic.ts#calibrationSummary`'s
 * own solve-refusal reasons.
 *
 * `cellCalibrated`/`sequenceConverged` join the list in H8. They are the two booleans the backend's
 * own gate reads to choose PROBABLE over CONFIRMED, and until H8 they reached neither the database
 * nor the wire — so a `PROBABLE` row could never answer "why not CONFIRMED?" (§9.11 defect 4). They
 * are shown as plain facts, never re-derived into a verdict here: this module shapes the server's
 * answer, it does not second-guess it.
 */
export function geoDetailRows(correction: CorrectionResponse): readonly GeoFactRow[] {
  const rows: GeoFactRow[] = [
    { label: 'Status', value: correction.status },
    { label: 'Separation', value: formatMeters(correction.separationMeters), mono: true },
    { label: 'Radius', value: formatMeters(correction.radiusMeters), mono: true },
    { label: 'Inliers', value: correction.inlierCount !== undefined ? `${correction.inlierCount}` : '—', mono: true },
    {
      label: 'Inlier ratio',
      value: correction.inlierRatio !== undefined ? `${(correction.inlierRatio * 100).toFixed(0)}%` : '—',
      mono: true,
    },
    { label: 'Sequence spread', value: formatMeters(correction.sequenceSpreadMeters), mono: true },
    { label: 'Cell calibrated', value: formatFlag(correction.cellCalibrated) },
    { label: 'Sequence converged', value: formatFlag(correction.sequenceConverged) },
    { label: 'Region', value: correction.regionId ?? '—' },
  ];
  if (correction.status === 'NO_FIX' && correction.refusal) {
    rows.push({ label: 'Refusal', value: correction.refusal });
  }
  return rows;
}

function formatMeters(value: number | undefined): string {
  return value !== undefined ? `${value.toFixed(1)} m` : '—';
}

/** A wire boolean as a fact; `'—'` for absent, so "not reported" never reads as a confident `'no'`. */
function formatFlag(value: boolean | undefined): string {
  if (value === undefined) {
    return '—';
  }
  return value ? 'yes' : 'no';
}

// --- TacticalMap corrected-track layer (§3.8 — the FIXED-CAMERA D6 rule, verbatim) ------------------

/**
 * The error circle's own radius, drawn **always** under the corrected marker (D6, extended verbatim
 * from `core/camera-geo/camera-geo-logic.ts#trackErrorRadiusMeters`) — floored at 0, and `0` (not
 * drawn meaningfully, but never skipped) for a `NO_FIX` row with no `radiusMeters` at all. "An 18 m
 * estimate must never render like a 2 m one" — nothing here ever substitutes a guessed radius for an
 * absent one.
 */
export function correctionRadiusMeters(correction: Pick<CorrectionResponse, 'radiusMeters'>): number {
  return Math.max(correction.radiusMeters ?? 0, 0);
}

/** Whether `correction` carries a plottable position at all — `false` on every `NO_FIX` row (domain: `position` null iff `NO_FIX`). */
export function hasCorrectionFix(correction: Pick<CorrectionResponse, 'latitude' | 'longitude'>): boolean {
  return correction.latitude !== undefined && correction.longitude !== undefined;
}

/** The corrected marker/error-circle's colour role — `'warn'` while `divergent`, `'info'` otherwise (never `'danger'`, the same anti-alarm-fatigue posture the chip above states explicitly). */
export function correctionToneKey(correction: Pick<CorrectionResponse, 'divergent'>): 'info' | 'warn' {
  return correction.divergent ? 'warn' : 'info';
}

// --- Replay corrected polyline + divergence band (§3.8 — "reads GET .../corrections, no new backend surface") ---

/**
 * The corrected track's own polyline points, truncated to the scrub position exactly like the raw
 * track's `replay-logic.ts#trailPrefix` — so the two polylines are always compared "as of now",
 * the same instant, rather than one being a static full route and the other a moving prefix.
 * `NO_FIX` rows contribute nothing (no position to plot).
 */
export function correctedTrailPoints(corrections: readonly CorrectionResponse[], atMs: number): readonly GeoPosition[] {
  return corrections
    .filter((correction) => hasCorrectionFix(correction) && Date.parse(correction.frameAt) <= atMs)
    .map((correction) => ({ latitude: correction.latitude as number, longitude: correction.longitude as number }));
}

/** One contiguous run of `divergent: true` corrections, as a `[fromMs, toMs]` span for the scrub bar's divergence band. */
export interface DivergenceBand {
  readonly fromMs: number;
  readonly toMs: number;
}

/**
 * Every contiguous divergent run in `corrections` (assumed oldest→newest, §3.3's own ordering for
 * `GET /api/geo/corrections?usageId=`) — a band starts at the first divergent correction's `frameAt`
 * and ends at the first *non*-divergent correction's `frameAt` after it (or the list's own last
 * `frameAt`, if it never clears before the data ends).
 */
export function divergenceBands(corrections: readonly CorrectionResponse[]): readonly DivergenceBand[] {
  const bands: DivergenceBand[] = [];
  let bandStart: number | undefined;
  for (const correction of corrections) {
    const atMs = Date.parse(correction.frameAt);
    if (correction.divergent) {
      if (bandStart === undefined) {
        bandStart = atMs;
      }
    } else if (bandStart !== undefined) {
      bands.push({ fromMs: bandStart, toMs: atMs });
      bandStart = undefined;
    }
  }
  if (bandStart !== undefined && corrections.length > 0) {
    bands.push({ fromMs: bandStart, toMs: Date.parse(corrections[corrections.length - 1].frameAt) });
  }
  return bands;
}

// --- Region manager (§3.8 — "NEVER_ACCEPT is a first-class honest state, never hidden") -------------

const REGION_STATUS_LABELS: Record<RegionStatus, string> = {
  BUILDING: 'Building',
  READY: 'Ready',
  NEVER_ACCEPT: 'Never accept',
  FAILED: 'Failed',
};

export function regionStatusLabel(status: RegionStatus): string {
  return REGION_STATUS_LABELS[status];
}

export type RegionStatusTone = 'ok' | 'warn' | 'danger' | 'muted';

/**
 * `NEVER_ACCEPT` is `'warn'`, not `'danger'` — the ingest itself succeeded, the region's own
 * self-calibration just concluded it can never trust a match against these tiles (D8/§4.2 G-e); a
 * genuine ingest `FAILED` is the actual danger state. Both render, neither is hidden (§3.8).
 */
const REGION_STATUS_TONES: Record<RegionStatus, RegionStatusTone> = {
  BUILDING: 'muted',
  READY: 'ok',
  NEVER_ACCEPT: 'warn',
  FAILED: 'danger',
};

export function regionStatusTone(status: RegionStatus): RegionStatusTone {
  return REGION_STATUS_TONES[status];
}

const REGION_PHASE_LABELS: Record<RegionIngestPhase, string> = {
  receiving: 'Receiving imagery',
  extracting: 'Extracting tiles',
  encoding: 'Encoding descriptors',
  indexing: 'Indexing',
  calibrating: 'Calibrating',
  done: 'Done',
};

export function regionPhaseLabel(phase: RegionIngestPhase): string {
  return REGION_PHASE_LABELS[phase];
}

/** `undefined` (an indeterminate progress bar) when `total` is `0` — §3.1's own "0 when the phase has no countable unit", never a fabricated percent. */
export function regionProgressPercent(progress: Pick<RegionProgressResponse, 'done' | 'total'>): number | undefined {
  return progress.total > 0 ? Math.min(100, Math.round((progress.done / progress.total) * 100)) : undefined;
}

/** The ingest form's own string-valued draft — mirrors `core/camera-geo/camera-geo-logic.ts#ManualPoseDraft`'s plain-`<input>`-binding idiom. */
export interface RegionIngestDraft {
  readonly name: string;
  readonly zoom: string;
  readonly north: string;
  readonly south: string;
  readonly east: string;
  readonly west: string;
}

/** `zoom` defaults to `'17'` — §3.3's own example region, and the middle of the `[15,19]` legal range. */
export const BLANK_REGION_INGEST_DRAFT: RegionIngestDraft = {
  name: '',
  zoom: '17',
  north: '',
  south: '',
  east: '',
  west: '',
};

/**
 * Parses the ingest form into a §3.3 `POST` body, or `null` if the name is blank or any bound isn't a
 * finite number — the form's own "can I submit" gate. Range validation (zoom `[15,19]`, valid
 * bounds, the tile-count ceiling) is deliberately left to the server's own `400`, exactly like
 * `core/camera-geo/camera-geo-logic.ts#manualPoseRequest`'s own documented reasoning: this only
 * guards against genuinely unparseable input.
 */
export function regionIngestRequest(draft: RegionIngestDraft): RegionIngestRequest | null {
  const name = draft.name.trim();
  const parse = (raw: string): number => Number(raw.trim() === '' ? NaN : raw);
  const zoom = parse(draft.zoom);
  const north = parse(draft.north);
  const south = parse(draft.south);
  const east = parse(draft.east);
  const west = parse(draft.west);
  if (name.length === 0 || ![zoom, north, south, east, west].every(Number.isFinite)) {
    return null;
  }
  return { name, zoom, north, south, east, west };
}
