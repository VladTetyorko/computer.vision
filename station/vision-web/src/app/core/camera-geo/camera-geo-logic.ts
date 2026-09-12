import { HttpErrorResponse } from '@angular/common/http';
import type {
  ApiErrorBody,
  CalibrateCameraPoseRequest,
  CalibrationResult,
  CameraPoseRequest,
  CameraPoseResponse,
  DetectionState,
  MapEventPayload,
  ProjectedTrackResponse,
} from '../api/models';

/**
 * Pure, Angular-free logic behind wave G5 of `docs/plans/done/FIXED-CAMERA-GEO-PLAN.md` — the pose panel
 * (`features/camera-geo/camera-pose-panel.ts`) and the calibration wizard
 * (`features/camera-geo/camera-calibration-wizard.ts`) on the asset manager page, plus the map track
 * layer's own reducer/derivations consumed by `core/map-data/tracks-store.ts` and
 * `shared/map/tactical-map/tactical-map.ts`. Built entirely against §5's frozen wire contract — the
 * backend does not exist yet as of this wave (G4 is blocked on G2+G3) — mirroring
 * `core/readiness/readiness-logic.ts`'s own "presentation only, never re-derive a verdict the
 * backend already decided" discipline: every solve/pose value rendered here is the server's own, this
 * module only shapes it for display or folds a wire event into local state.
 */

// --- Flag-disabled 409 (D8) ----------------------------------------------------------------------

/**
 * The frozen flag-off refusal text (§5's preamble: "Every endpoint below answers `409` … while the
 * flag is off"). §5 states the endpoint status/message are frozen but is internally inconsistent
 * about the *body key* carrying it — see this function's own defensive read below.
 */
const DISABLED_MESSAGE = 'fixed-camera geolocation is disabled (vision.geo.fixed-camera.enabled)';

/**
 * True only for the flag-off refusal on any `/api/assets/{id}/camera-pose…` or `/api/map/tracks`
 * call, mirroring `core/readiness/readiness-logic.ts#isProbeDisabledError`'s exact shape (status
 * `409` + an exact message match, since the status code alone is reused for other 409s in this app).
 *
 * **Reads both `message` and `detail`, deliberately.** §5's own preamble illustrates the flag-off
 * body as `{"detail": "…"}`, but this app's one real, shipped error envelope
 * (`core/api/models.ts#ApiErrorBody`, `{error, message}`) is what every other flag-gated 409 here
 * actually uses. Rather than silently picking one and risking a false negative once G4 ships, this
 * checks both keys for the frozen message text — a caller that finds `false` here still degrades
 * safely (falls through to `describeHttpError`'s generic sentence), so a wrong guess here is a worse
 * *message*, never a broken page.
 */
export function isFixedCameraGeoDisabledError(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse) || error.status !== 409) {
    return false;
  }
  const body = error.error as (Partial<ApiErrorBody> & { readonly detail?: string }) | string | null;
  const message = typeof body === 'string' ? body : (body?.message ?? body?.detail ?? null);
  return message === DISABLED_MESSAGE;
}

// --- Pose display (the pose panel's fact grid) ----------------------------------------------------

/** One row of the pose panel's fact grid — the same `{label, value, mono?}` shape `features/asset-detail/asset-detail-logic.ts#TelemetryFactRow` already established for this app's `<dl class="facts">` convention. */
export interface FactRow {
  readonly label: string;
  readonly value: string;
  readonly mono?: boolean;
}

/** `pose.source`, plus the residual for a calibrated pose when the server reported one — "Calibrated · 7.3px residual" beats a bare "Calibrated" once a number exists to back the confidence. */
export function poseSourceLabel(pose: Pick<CameraPoseResponse, 'source' | 'rmsErrorPixels'>): string {
  if (pose.source === 'MANUAL') {
    return 'Manual entry';
  }
  return pose.rmsErrorPixels !== undefined ? `Calibrated · ${pose.rmsErrorPixels.toFixed(1)}px residual` : 'Calibrated';
}

/** The pose panel's own fact rows — every §5 `CameraPoseResponse` field that isn't bookkeeping (`assetId`/`updatedAt`, rendered by the host separately alongside a relative-time stamp). */
export function poseFactRows(pose: CameraPoseResponse): readonly FactRow[] {
  return [
    { label: 'Position', value: `${pose.latitude.toFixed(5)}, ${pose.longitude.toFixed(5)}`, mono: true },
    { label: 'Height AGL', value: `${pose.aglMeters.toFixed(1)} m` },
    { label: 'Yaw', value: `${pose.yawDegrees.toFixed(1)}°` },
    { label: 'Pitch', value: `${pose.pitchDegrees.toFixed(1)}°` },
    { label: 'Horizontal FOV', value: `${pose.hfovDegrees.toFixed(1)}°` },
    { label: 'Source', value: poseSourceLabel(pose) },
  ];
}

// --- Detection-state explanation (D9) -------------------------------------------------------------
//
// D9: "The pose panel surfaces the stream's DetectionState so 'calibrated but detection off' is a
// visible, explained state, not a silent dead map." `DetectionState` itself already exists
// (`core/api/models.ts`, sourced from `GET /api/streams/{id}/tracks`); this only maps it to the
// panel's own label/tone/explanation, the same split `core/readiness/readiness-logic.ts` uses for
// `FeatureStatus`.

export type DetectionStateTone = 'ok' | 'warn' | 'muted';

// D1 follow-up (docs/plans/active/CV-ORCHESTRATION-PLAN.md §7): `RUNNING_UNWATCHED` (a
// `DetectionPolicy.ALWAYS` asset inferring with no current viewer) gets its own case in all three
// functions below rather than falling into `default`/`'RUNNING'`'s case together. This panel's own
// question is narrower than the Fly hero's — "is the detector producing tracks for this map to
// project" — and the answer is yes regardless of whether anyone is watching (durable
// persistence/events proceed exactly as `RUNNING`, per `DetectionState`'s own javadoc), so tone
// matches `RUNNING` (`'ok'`, not `'warn'`: this is not the idle case). The label and explanation
// still name the "no viewer" fact plainly rather than silently reusing `RUNNING`'s wording, since
// this file's whole point (D9) is an explained state, never a silently-collapsed one.

export function detectionStateLabel(state: DetectionState | undefined): string {
  switch (state) {
    case 'RUNNING':
      return 'Detecting';
    case 'RUNNING_UNWATCHED':
      return 'Detecting (no viewer)';
    case 'IDLE_NO_VIEWERS':
      return 'Detection idle';
    case 'OFF':
      return 'Detection off';
    default:
      return 'Detection unknown';
  }
}

export function detectionStateTone(state: DetectionState | undefined): DetectionStateTone {
  switch (state) {
    case 'RUNNING':
    case 'RUNNING_UNWATCHED':
      return 'ok';
    case 'IDLE_NO_VIEWERS':
      return 'warn';
    default:
      return 'muted';
  }
}

/** The sentence under the chip — D9's own "explained state, not a silent dead map". */
export function detectionStateExplanation(state: DetectionState | undefined): string {
  switch (state) {
    case 'RUNNING':
      return 'Detection is running — tracks from this stream project onto the map, subject to calibration and range.';
    case 'RUNNING_UNWATCHED':
      return 'Detection is running for this asset even without a viewer (always-on policy) — tracks from this stream still project onto the map, subject to calibration and range.';
    case 'IDLE_NO_VIEWERS':
      return 'Detection is enabled but currently idle (no recent viewer) — it resumes automatically when needed.';
    case 'OFF':
      return "This stream's detection is turned off — no tracks will project onto the map until it's enabled.";
    default:
      return "This stream hasn't reported a detection state yet.";
  }
}

// --- Calibration wizard: the camera's own measured position ----------------------------------------

/** The wizard's own "where is this camera" sub-form — the operator's GPS/height measurement, distinct from the landmark points below. Same string-draft idiom as {@link ManualPoseDraft}. */
export interface MeasuredPositionDraft {
  readonly latitude: string;
  readonly longitude: string;
  readonly aglMeters: string;
}

/** Parses the measured-position sub-form, or `null` if any field isn't a finite number — mirrors {@link manualPoseRequest}'s own blank-vs-finite parse exactly (the same `Number('')` gotcha applies here). */
export function measuredPosition(
  draft: MeasuredPositionDraft,
): { readonly latitude: number; readonly longitude: number; readonly aglMeters: number } | null {
  const parse = (raw: string): number => Number(raw.trim() === '' ? NaN : raw);
  const latitude = parse(draft.latitude);
  const longitude = parse(draft.longitude);
  const aglMeters = parse(draft.aglMeters);
  if (![latitude, longitude, aglMeters].every(Number.isFinite)) {
    return null;
  }
  return { latitude, longitude, aglMeters };
}

// --- Calibration wizard: point pairing (D5 — "click a landmark in the frame, click the same point
// on the map, repeat") -----------------------------------------------------------------------------

/** One completed frame↔map correspondence in the wizard's own draft — the UI-side shape; {@link toCalibrationRequest} narrows it to the wire's `CalibrationPointRequest`. */
export interface CalibrationPointDraft {
  readonly u: number;
  readonly v: number;
  readonly latitude: number;
  readonly longitude: number;
}

/** A frame click waiting for its paired map click, or `null` between pairs. */
export type PendingFrameClick = { readonly u: number; readonly v: number } | null;

/** A frame click always (re-)arms the pending half — a second frame click before its map pair replaces the first rather than stacking, since a misclick shouldn't force starting the whole pair over. */
export function armFrameClick(u: number, v: number): PendingFrameClick {
  return { u, v };
}

/** A map click completes the pending pair, or is silently ignored if no frame click is armed yet (D5's own click-frame-then-click-map order — a map click alone means nothing). */
export function pairMapClick(
  pending: PendingFrameClick,
  points: readonly CalibrationPointDraft[],
  latitude: number,
  longitude: number,
): { readonly points: readonly CalibrationPointDraft[]; readonly pending: PendingFrameClick } {
  if (!pending) {
    return { points, pending };
  }
  return { points: [...points, { ...pending, latitude, longitude }], pending: null };
}

export function removeCalibrationPoint(
  points: readonly CalibrationPointDraft[],
  index: number,
): readonly CalibrationPointDraft[] {
  return points.filter((_, i) => i !== index);
}

/** D5's own bounds: `N ∈ [2,8]`. */
export const MIN_CALIBRATION_POINTS = 2;
export const MAX_CALIBRATION_POINTS = 8;

/** Whether the wizard may call `calibrateCameraPose` right now — the solve endpoint's own `400` guard, checked client-side first so a request that can only ever 400 is never sent. */
export function canRunCalibration(points: readonly CalibrationPointDraft[]): boolean {
  return points.length >= MIN_CALIBRATION_POINTS && points.length <= MAX_CALIBRATION_POINTS;
}

/** Whether the wizard should still accept a new frame click — the `MAX_CALIBRATION_POINTS` ceiling. */
export function canAddCalibrationPoint(points: readonly CalibrationPointDraft[]): boolean {
  return points.length < MAX_CALIBRATION_POINTS;
}

/** Assembles the §5 `CalibrateCameraPoseRequest` body from the wizard's own measured-position fields and its accumulated point draft. */
export function toCalibrationRequest(
  latitude: number,
  longitude: number,
  aglMeters: number,
  imageWidth: number,
  imageHeight: number,
  points: readonly CalibrationPointDraft[],
): CalibrateCameraPoseRequest {
  return {
    latitude,
    longitude,
    aglMeters,
    imageWidth,
    imageHeight,
    points: points.map((point) => ({ u: point.u, v: point.v, latitude: point.latitude, longitude: point.longitude })),
  };
}

// --- Calibration wizard: reading the solve result back (D5's honesty rule) -------------------------

/** A solved result the wizard may offer to save — narrows {@link CalibrationResult} by its own `solved` discriminant. */
export type SolvedCalibrationResult = Extract<CalibrationResult, { readonly solved: true }>;

export function calibrationResultTone(result: CalibrationResult): 'ok' | 'warn' | 'danger' {
  if (!result.solved) {
    return 'danger';
  }
  return result.quality === 'UNDETERMINED' ? 'warn' : 'ok';
}

/**
 * Whether the wizard's own "Save pose" button may be enabled — `false` for `solved:false` (D5: "an
 * honest cannot-solve beats a confident wrong pose", G5's own exit criterion: "an solved:false
 * response … offers no save"). A `SolvedCalibrationResult` type guard, so a caller that branches on
 * this can read `result.pose` without a further null check.
 */
export function canSaveCalibration(result: CalibrationResult): result is SolvedCalibrationResult {
  return result.solved;
}

/**
 * The wizard's one summary line under the result. For `solved:false` this is `result.reason`
 * **verbatim** — never paraphrased or re-worded (D5's own frozen examples: `"residual 41.0px exceeds
 * 25.0px"`, `"landmarks span only 6° of bearing"`, `"landmark 2 is 1.4m from the camera"`; this
 * wave's brief: "refusal reasons shown verbatim"). For `quality: 'UNDETERMINED'` (an exact 2-point
 * fit) this states the residual is meaningless rather than reporting a falsely-precise number (D5:
 * "add a third point to verify").
 */
export function calibrationSummary(result: CalibrationResult): string {
  if (!result.solved) {
    return result.reason;
  }
  if (result.quality === 'UNDETERMINED') {
    return 'Exact fit from 2 points — the residual is not meaningful yet. Add a third point to verify before saving.';
  }
  return `Solved — ${result.rmsErrorPixels.toFixed(1)}px residual.`;
}

/** Turns a solved result's own `pose` into the §5 `PUT` body that saves it — `source: 'CALIBRATED'`, carrying the solve's residual even if the embedded pose omitted it. */
export function calibratedPoseRequest(result: SolvedCalibrationResult): CameraPoseRequest {
  const pose = result.pose;
  return {
    latitude: pose.latitude,
    longitude: pose.longitude,
    aglMeters: pose.aglMeters,
    yawDegrees: pose.yawDegrees,
    pitchDegrees: pose.pitchDegrees,
    hfovDegrees: pose.hfovDegrees,
    targetLayerId: pose.targetLayerId ?? null,
    source: 'CALIBRATED',
    rmsErrorPixels: pose.rmsErrorPixels ?? result.rmsErrorPixels,
  };
}

// --- Manual pose entry -----------------------------------------------------------------------------

/** The manual-edit form's own string-valued draft — plain `<input>` bindings, parsed only on submit ({@link manualPoseRequest}), mirroring every other numeric-draft form in this app (e.g. `features/command/geofence-zone-dialog.ts`'s own vertex drafts). */
export interface ManualPoseDraft {
  readonly latitude: string;
  readonly longitude: string;
  readonly aglMeters: string;
  readonly yawDegrees: string;
  readonly pitchDegrees: string;
  readonly hfovDegrees: string;
}

const BLANK_MANUAL_POSE_DRAFT: ManualPoseDraft = {
  latitude: '',
  longitude: '',
  aglMeters: '',
  yawDegrees: '',
  pitchDegrees: '',
  hfovDegrees: '',
};

/** Seeds the manual-edit form from the current pose (an edit), or blank (a first-time manual entry). */
export function draftFromPose(pose: CameraPoseResponse | null): ManualPoseDraft {
  if (!pose) {
    return BLANK_MANUAL_POSE_DRAFT;
  }
  return {
    latitude: String(pose.latitude),
    longitude: String(pose.longitude),
    aglMeters: String(pose.aglMeters),
    yawDegrees: String(pose.yawDegrees),
    pitchDegrees: String(pose.pitchDegrees),
    hfovDegrees: String(pose.hfovDegrees),
  };
}

/**
 * Parses a manual-entry draft into a §5 `PUT` body, or `null` if any field isn't a finite number —
 * the form's own "can I submit" gate. Range validation (§5: `aglMeters ≥ 0`, `pitchDegrees ∈
 * [-10,90]`, `hfovDegrees ∈ (10,160)`) is deliberately left to the server's own `400` rather than
 * duplicated here — this only guards against genuinely unparseable input (an empty/non-numeric
 * field), the one failure a `400` couldn't explain any better than a disabled button already does.
 */
export function manualPoseRequest(draft: ManualPoseDraft, targetLayerId: string | null): CameraPoseRequest | null {
  // `Number('')` is `0`, not `NaN` — an empty field must fail this parse, so blank-vs-finite is
  // checked as one step (`Number(x.trim())`, `''` → `Number.isFinite(NaN)` → false) rather than
  // trusting `Number.isFinite` alone on the raw string.
  const parse = (raw: string): number => Number(raw.trim() === '' ? NaN : raw);
  const latitude = parse(draft.latitude);
  const longitude = parse(draft.longitude);
  const aglMeters = parse(draft.aglMeters);
  const yawDegrees = parse(draft.yawDegrees);
  const pitchDegrees = parse(draft.pitchDegrees);
  const hfovDegrees = parse(draft.hfovDegrees);
  if (![latitude, longitude, aglMeters, yawDegrees, pitchDegrees, hfovDegrees].every(Number.isFinite)) {
    return null;
  }
  return {
    latitude,
    longitude,
    aglMeters,
    yawDegrees,
    pitchDegrees,
    hfovDegrees,
    targetLayerId,
    source: 'MANUAL',
    rmsErrorPixels: null,
  };
}

// --- Map track layer: live-event reducer (D3, D11) ------------------------------------------------

/** The stable identity of one projected track — an `(assetId, trackId)` pair, same composite key idiom `core/map-data/mark-logic.ts` uses for a mark's own id. */
export function trackKey(assetId: string, trackId: number): string {
  return `${assetId}:${trackId}`;
}

/**
 * Folds one live `track` {@link MapEventPayload} onto the current track list — the pure reducer
 * `core/map-data/tracks-store.ts` calls once per SSE arrival, mirroring
 * `core/map-data/marks-store.ts`'s own `applyMarkEvents` idiom (initial `GET` first, then fold
 * deltas — §5's own "not snapshot-on-connect" note). Ignores any payload for another entity.
 *
 * `created`/`updated` upsert, preserving the existing `trail` — the live event never carries one
 * (§5: "trail via GET after reload"). `cleared` removes the track outright, never left stale on the
 * map (D3's own "nothing lingers, nothing pretends" — a track whose id expires or whose stream stops
 * gets a `cleared` and leaves the map).
 */
export function applyTrackEvent(
  tracks: readonly ProjectedTrackResponse[],
  payload: MapEventPayload,
): readonly ProjectedTrackResponse[] {
  if (payload.entity !== 'track' || !payload.track) {
    return tracks;
  }
  const live = payload.track;
  const key = trackKey(live.assetId, live.trackId);
  if (payload.action === 'cleared') {
    return tracks.filter((track) => trackKey(track.assetId, track.trackId) !== key);
  }
  const existing = tracks.find((track) => trackKey(track.assetId, track.trackId) === key);
  const merged: ProjectedTrackResponse = {
    assetId: live.assetId,
    trackId: live.trackId,
    label: live.label ?? existing?.label ?? '',
    layerId: live.layerId ?? existing?.layerId ?? payload.layerId,
    latitude: live.latitude ?? existing?.latitude ?? 0,
    longitude: live.longitude ?? existing?.longitude ?? 0,
    rangeMeters: live.rangeMeters ?? existing?.rangeMeters ?? 0,
    errorRadiusMeters: live.errorRadiusMeters ?? existing?.errorRadiusMeters ?? 0,
    updatedAt: live.updatedAt ?? existing?.updatedAt ?? new Date(0).toISOString(),
    trail: existing?.trail ?? [],
  };
  return [...tracks.filter((track) => trackKey(track.assetId, track.trackId) !== key), merged];
}

// --- Map track layer: trail + error-circle derivation (D6, D7) -------------------------------------

export interface LatLon {
  readonly latitude: number;
  readonly longitude: number;
}

/**
 * The polyline points to draw for a track's trail: its stored history plus its own current head
 * position appended — the live event's `latitude`/`longitude` can be one tick ahead of the last
 * stored (decimated) trail point — deduped when they already coincide exactly.
 */
export function trackTrailPoints(track: ProjectedTrackResponse): readonly LatLon[] {
  const points: LatLon[] = track.trail.map((point) => ({ latitude: point.latitude, longitude: point.longitude }));
  const last = points[points.length - 1];
  if (!last || last.latitude !== track.latitude || last.longitude !== track.longitude) {
    points.push({ latitude: track.latitude, longitude: track.longitude });
  }
  return points;
}

/** The error-radius circle's own radius, drawn **always** under the track dot (D6) — floored at 0 against a malformed/negative wire value rather than trusting it blindly. */
export function trackErrorRadiusMeters(track: Pick<ProjectedTrackResponse, 'errorRadiusMeters'>): number {
  return Math.max(track.errorRadiusMeters, 0);
}

/** The stable id chip's own label — "#17 car", not just the bare numeric id: an operator tracking several needs the object kind at a glance too. Falls back to the id alone when the server hasn't classified the object (`label` empty). */
export function trackChipLabel(track: Pick<ProjectedTrackResponse, 'trackId' | 'label'>): string {
  return track.label ? `#${track.trackId} ${track.label}` : `#${track.trackId}`;
}
