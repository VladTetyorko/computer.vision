import type { Detection, DetectionResult } from '../../core/api/models';
import type { Transport } from './player-recovery';
import { CV_STATUS_FRESH_SECONDS } from '../../core/detections/detections-logic';

/**
 * Pure logic behind the client-side vector detection overlay (docs/main/CYCLES-PLAN.md §11, CD-b item
 * 6): which completed `DetectionResult` batch best matches the frame currently on-screen, given
 * HLS's live-edge latency. Split out so the sync math is unit-testable without a `<canvas>`,
 * hls.js, or a poller — mirrors `shared/player/player-recovery.ts`.
 */

/** The per-tile toggle's two states — see `shouldDrawOverlay`'s doc comment for what each means.
 *  `'burned'` is gone (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-1, wave W3): server-side burn-in no
 *  longer exists, so there is nothing left for it to name. */
export type BoxesMode = 'overlay' | 'off';

const BOXES_CYCLE: readonly BoxesMode[] = ['overlay', 'off'];

/** `B` (Fly) / the wall tile's own toggle button — cycles `'overlay'` <-> `'off'`. A `current` no
 *  longer present in the cycle (a stale value from before this wave) restarts from the cycle's
 *  first entry rather than throwing or standing still. */
export function cycleBoxesMode(current: BoxesMode): BoxesMode {
  const index = BOXES_CYCLE.indexOf(current);
  return index === -1 ? BOXES_CYCLE[0] : BOXES_CYCLE[(index + 1) % BOXES_CYCLE.length];
}

/**
 * How much slack (in units of "one detection batch interval") to tolerate beyond the raw latency
 * estimate before discarding a result as "not on screen yet" — docs/main/CYCLES-PLAN.md §11 item 6's
 * "±1 batch of slack", covering ordinary jitter between the CV pipeline's sampling cadence and the
 * poll cycle that fetched `results`.
 */
export const DEFAULT_SLACK_BATCHES = 1;

/**
 * Picks which completed `DetectionResult` best represents what is on-screen *right now*.
 *
 * HLS runs the `<video>` element several seconds behind the true live edge
 * (`shared/player/player.ts`'s own `behindLive`/hls.js `latency`). A detection batch's `capturedAt` is a
 * *source* timestamp — the instant the frame was sampled off the pipeline, not when the browser
 * displays it. The frame currently visible was captured at approximately
 * `nowMs - latencySeconds*1000`; the right batch to draw is therefore the newest one whose
 * `capturedAt` does not run *ahead* of that estimated on-screen instant — drawing a box for a
 * not-yet-displayed frame would make it appear early, jittering ahead of the video.
 *
 * `results` is expected newest-first, `VisionApi.streamDetections`'s own contract. `slackBatches`
 * additionally tolerates a batch or so of scheduling jitter, sized from the results' own observed
 * cadence (`averageBatchIntervalMs`) rather than a fixed guess, since `inferenceFps` is
 * user-configurable per stream.
 */
export function selectDetectionResult(
  results: readonly DetectionResult[],
  nowMs: number,
  latencySeconds: number | null,
  slackBatches: number = DEFAULT_SLACK_BATCHES,
): DetectionResult | undefined {
  if (results.length === 0) {
    return undefined;
  }
  const latencyMs = latencySeconds !== null && latencySeconds >= 0 ? latencySeconds * 1000 : 0;
  const onScreenAtMs = nowMs - latencyMs;
  const slackMs = averageBatchIntervalMs(results) * Math.max(0, slackBatches);

  for (const result of results) {
    if (Date.parse(result.capturedAt) <= onScreenAtMs + slackMs) {
      return result;
    }
  }
  // Every result appears "in the future" relative to the on-screen frame (e.g. the latency
  // estimate hasn't settled yet, right after attach) — the oldest available beats showing nothing,
  // UNLESS that oldest result is itself stale in absolute wall-clock terms (research §3.4's staleness
  // bound, `isDetectionStale`): a fallback exists for attach jitter, not as an unbounded policy that
  // would draw a genuinely old batch as if it were fresh forever.
  const oldest = results[results.length - 1];
  return isDetectionStale(nowMs - Date.parse(oldest.capturedAt)) ? undefined : oldest;
}

/** The observed average gap between consecutive `capturedAt` values — one "batch" of `slackBatches`,
 *  and the unit {@link detectionAlphaPercent} scales its own fade threshold off. */
export function averageBatchIntervalMs(results: readonly DetectionResult[]): number {
  if (results.length < 2) {
    return 0;
  }
  let sum = 0;
  let count = 0;
  for (let i = 0; i < results.length - 1; i++) {
    const delta = Date.parse(results[i].capturedAt) - Date.parse(results[i + 1].capturedAt);
    if (delta > 0) {
      sum += delta;
      count++;
    }
  }
  return count > 0 ? sum / count : 0;
}

/**
 * The latency figure {@link selectDetectionResult} should sync boxes against, given which transport
 * is actually attached (docs/plans/active/MEDIA-SOT-PLAN.md §6/§8 wave M8).
 *
 * HLS keeps its existing behaviour exactly: `behindLiveSeconds` (`shared/player/player.ts`'s own
 * measured live-edge distance) passes through unchanged.
 *
 * WHEP used to hard-pin `behindLiveSeconds` to `0` for this purpose — a real number, not `null`, but
 * one that ignores the glass-to-glass delay a live WebRTC track genuinely has (~0.2–0.5s, MEDIA-SOT-
 * PLAN.md §6's own measurement), which made boxes **lead** the picture by that much. This function
 * is the fix: for `'webrtc'`, the already-measured `getStats()`-derived figure
 * (`player-recovery.ts#estimateWhepLatencySeconds` — half the round-trip time plus the jitter term,
 * i.e. the same number the latency badge already shows) is used instead. `null` (not yet measured —
 * e.g. the first tick or two right after attach, before a candidate-pair report has carried a round-
 * trip time) degrades to `0` rather than blocking the overlay outright: `0` is what
 * `selectDetectionResult` has always treated as "near-zero latency", the same fallback a `null`
 * `behindLiveSeconds` already gets there.
 */
export function overlaySyncLatencySeconds(
  transport: Transport,
  behindLiveSeconds: number | null,
  whepLatencySeconds: number | null,
): number | null {
  return transport === 'webrtc' ? (whepLatencySeconds ?? 0) : behindLiveSeconds;
}

/**
 * Whether the canvas overlay should actually draw boxes right now — `'off'` suppresses it, `'overlay'`
 * draws once a result is available. The video itself is always clean pixels now (docs/plans/active/
 * CV-CLEAN-FEED-PLAN.md D-1: server-side burn-in is deleted, not defaulted off), so `'off'` means
 * genuinely no boxes anywhere, not merely "hide the client canvas over the server's own baked-in ones"
 * the way it used to.
 */
export function shouldDrawOverlay(mode: BoxesMode, hasResult: boolean): boolean {
  return mode === 'overlay' && hasResult;
}

// --- Staleness honesty (docs/plans/active/CV-FLY-INTERACTION-RESEARCH.md §3.4, D7) -----------------------
// The renderer already knows a batch's age (`capturedAt` vs `now`) — this turns that age into an
// honest signal instead of drawing a stale batch at full confidence forever. Two thresholds, both
// scaled off the stream's own observed cadence rather than one flat guess:
//  - past `STALE_FADE_BATCH_MULTIPLIER` observed batch-intervals, a batch still draws, but dimmed —
//    "probably fine, trust it a little less";
//  - past `DETECTION_STALE_CUTOFF_SECONDS`, a batch doesn't draw at all and the stage says so instead
//    (`detectionsPausedNotice`) — the same freshness window `DetectionsStore`'s own status dot already
//    uses (`core/detections/detections-logic.ts#CV_STATUS_FRESH_SECONDS`, imported rather than
//    redeclared) so the canvas, the status dot, and this notice can never quietly disagree about what
//    "too old to call live" means.

/** A batch older than this many observed batch-intervals still draws, but at
 *  {@link STALE_FADE_ALPHA_PERCENT} instead of full alpha. */
export const STALE_FADE_BATCH_MULTIPLIER = 2;

/** Alpha percent applied once a batch crosses the fade threshold — dimmed, not gone. */
export const STALE_FADE_ALPHA_PERCENT = 40;

/** Detections older than this hard cutoff stop drawing entirely — the exact window `DetectionsStore`
 *  already prunes its own results to (`CV_STATUS_FRESH_SECONDS`), imported so the two can never drift
 *  apart and disagree. */
export const DETECTION_STALE_CUTOFF_SECONDS = CV_STATUS_FRESH_SECONDS;

/**
 * The alpha percent a batch this old should draw at, scaled off the stream's own observed batch
 * cadence ({@link averageBatchIntervalMs}) — 100% while fresh, {@link STALE_FADE_ALPHA_PERCENT} once
 * older than {@link STALE_FADE_BATCH_MULTIPLIER} batch-intervals. A `batchIntervalMs` of `0` (a
 * single-batch history — nothing to measure a cadence from yet) never fades; there's no baseline to
 * call this batch old *relative to*. Not called once a batch has crossed
 * {@link DETECTION_STALE_CUTOFF_SECONDS} — {@link isDetectionStale} gates that first, and the caller
 * stops drawing entirely rather than fading to invisible.
 */
export function detectionAlphaPercent(ageMs: number, batchIntervalMs: number): number {
  if (batchIntervalMs <= 0) {
    return 100;
  }
  return ageMs > batchIntervalMs * STALE_FADE_BATCH_MULTIPLIER ? STALE_FADE_ALPHA_PERCENT : 100;
}

/** Whether a batch this old (milliseconds) has crossed the hard cutoff and should stop drawing
 *  entirely, handing off to {@link detectionsPausedNotice}. */
export function isDetectionStale(ageMs: number): boolean {
  return ageMs > DETECTION_STALE_CUTOFF_SECONDS * 1000;
}

/**
 * "Detections paused — last seen Ns ago" (research §3.4) once the feed has gone stale — mirrors
 * `stream-state-logic.ts#videoNotice`'s own shape/honesty rule for the sibling *video* axis, applied
 * here to the *detection* feed instead. `null` means "say nothing", covering both "detections are
 * fresh" and "nothing has ever arrived to report a pause from" (no honest age to show).
 *
 * `latestCapturedAt` must be the **unfiltered** last-seen timestamp (e.g. `DetectionsStore`'s own raw
 * latest result, not the freshness-pruned `results()` list) — once a batch ages past
 * {@link DETECTION_STALE_CUTOFF_SECONDS} it is no longer present in a freshness-filtered list at all,
 * which is exactly the moment this notice needs to start reporting how long ago it was.
 */
export function detectionsPausedNotice(latestCapturedAt: string | undefined, nowMs: number): string | null {
  if (latestCapturedAt === undefined) {
    return null;
  }
  const ageMs = Math.max(0, nowMs - Date.parse(latestCapturedAt));
  if (!isDetectionStale(ageMs)) {
    return null;
  }
  return `Detections paused — last seen ${Math.round(ageMs / 1000)}s ago`;
}

// --- Composite-model box hues (docs/plans/done/OPS-CORE-PLAN.md §Q3b) --------------------------------------

/**
 * The key `modelHue` colors a box by — cv-service's composite mode (docs/plans/done/CV-MODELS-PLAN.md item
 * 4, `registry.py#detect_composite`) prefixes every `label` `"{short_name}:{label}"` (e.g.
 * `"orion12l:tank"`) only when more than one member model actually ran, so this is the *one*
 * signal that distinguishes which member produced a given box.
 *
 * Deliberately **not** `Detection.modelId`: in composite mode every detection in the same batch
 * carries the identical `modelId` (the request's own comma-joined composite id, e.g.
 * `"yolo11n.pt,orion12l.pt"`) — proto's `Detection` message has no per-detection model-tag field,
 * so `modelId` can't distinguish members within one batch even though it exists on the wire. A
 * plain single-model stream never prefixes its labels at all (`tag_labels` is only true for >1
 * resolved member — see that function's own doc comment), so `DEFAULT_MODEL_KEY` is what every
 * detection from *any* single model — not just cv-service's own default — resolves to.
 */
export const DEFAULT_MODEL_KEY = 'default';

export function detectionModelKey(detection: Pick<Detection, 'label'>): string {
  const separatorIndex = detection.label.indexOf(':');
  return separatorIndex > 0 ? detection.label.slice(0, separatorIndex) : DEFAULT_MODEL_KEY;
}

/** Fixed to sit in the same vivid/legible band as `--accent` (`#4f8cff` ≈ `hsl(219 100% 65%)`) so a
 *  hashed model color never reads as washed-out or too dark against the video underneath. */
const MODEL_HUE_SATURATION = 90;
const MODEL_HUE_LIGHTNESS = 65;

/** The exact color every box has always been drawn in — see `modelHue`'s own doc comment. */
export const DEFAULT_BOX_COLOR = '#4f8cff';
/** `DEFAULT_BOX_COLOR` at 85% alpha, byte-identical to the label background this file always drew. */
const DEFAULT_BOX_FILL = 'rgb(79 140 255 / 85%)';

/** Simple deterministic string hash (djb2-ish) → a hue in `[0, 360)`, no external dependency. */
function hashHue(key: string): number {
  let hash = 0;
  for (let i = 0; i < key.length; i++) {
    hash = (hash * 31 + key.charCodeAt(i)) >>> 0;
  }
  return hash % 360;
}

/**
 * Stable per-model box color (docs/plans/done/OPS-CORE-PLAN.md §Q3b): `DEFAULT_MODEL_KEY` always resolves to
 * the exact color every box has always been drawn in — a single-model stream (the overwhelming
 * common case, whichever model it happens to be) sees zero visual change. Any other key hashes
 * deterministically into a hue at a fixed saturation/lightness, so the same model always draws the
 * same color across frames, reconnects, and page loads with no stateful color registry to keep in
 * sync. `alphaPercent` (default fully opaque, for the box stroke) also covers the semi-transparent
 * label background `shared/player/player.ts#drawBox` paints behind each box's text.
 */
export function modelHue(modelKey: string, alphaPercent = 100): string {
  if (modelKey === DEFAULT_MODEL_KEY) {
    return alphaPercent >= 100 ? DEFAULT_BOX_COLOR : DEFAULT_BOX_FILL;
  }
  const hue = hashHue(modelKey);
  return alphaPercent >= 100
    ? `hsl(${hue} ${MODEL_HUE_SATURATION}% ${MODEL_HUE_LIGHTNESS}%)`
    : `hsl(${hue} ${MODEL_HUE_SATURATION}% ${MODEL_HUE_LIGHTNESS}% / ${alphaPercent}%)`;
}

/**
 * Every distinct model key present in `detections`, in first-seen order — `shared/player/player.ts`'s
 * legend gate shows a chip only when this has ≥2 entries: "the frame actually on screen mixes
 * models right now", not merely "composite mode is configured" (a frame where only one member
 * model detected anything still reads, correctly, as single-model).
 */
export function distinctModelKeys(detections: readonly Pick<Detection, 'label'>[]): readonly string[] {
  const seen = new Set<string>();
  for (const detection of detections) {
    seen.add(detectionModelKey(detection));
  }
  return [...seen];
}

// --- Track-aware rendering (docs/plans/done/TRACKING-PLAN.md §4/§10, wave T7) ------------------------------
// Everything below gates on `detection.track` alone — one null check (per §4.G's own "the client
// gets a single null check gating all track rendering" design) — so a stream running an old/absent
// server, or a detection tracking hasn't (yet) assigned an id to, draws byte-identically to before
// this wave: `shared/player/player.ts#drawBox` only reaches these functions once `detection.track`
// is present.

/**
 * The box label `shared/player/player.ts#drawBox` draws — `"#7 car 82%"` once `detection.track` is
 * present (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #1's "stable box numbers"), the exact,
 * unchanged `"car 82%"` otherwise. Also used for the hover tooltip's own text, so the two always
 * agree on whether a box is carrying an id.
 */
export function formatDetectionLabel(detection: Detection): string {
  const confidence = `${(detection.confidence * 100).toFixed(0)}%`;
  return detection.track
    ? `#${detection.track.id} ${detection.label} ${confidence}`
    : `${detection.label} ${confidence}`;
}

/**
 * Stable per-track box color (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #1) — the same hash-to-hue
 * mechanism as {@link modelHue}, keyed on `trackId` instead of a model key, so one tracked object
 * keeps one color across every frame **even as its label flips** (composite-mode member handoff,
 * docs/plans/done/TRACKING-PLAN.md §5.I risk R9) — a track's identity is its id, never its current label.
 * `drawBox` calls this instead of `modelHue` whenever `detection.track` is present; the two never
 * mix on the same box. A distinct hash seed (`"track-"` prefix) from `modelHue`'s own model-key hash
 * is deliberate, not load-bearing — the two are never compared or blended, only each internally
 * stable.
 */
export function trackHue(trackId: number, alphaPercent = 100): string {
  const hue = hashHue(`track-${trackId}`);
  return alphaPercent >= 100
    ? `hsl(${hue} ${MODEL_HUE_SATURATION}% ${MODEL_HUE_LIGHTNESS}%)`
    : `hsl(${hue} ${MODEL_HUE_SATURATION}% ${MODEL_HUE_LIGHTNESS}% / ${alphaPercent}%)`;
}

/** One point of a per-track trail — a tracked box's normalized center, `[0,1]` against frame dimensions. */
export interface TrailPoint {
  readonly x: number;
  readonly y: number;
}

/** How far back a trail reaches (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #3 — "a trail behind a
 *  tracked car", not a full trajectory history; S2's map trails are the durable, longer-lived kind). */
export const TRAIL_WINDOW_MS = 2_000;

/**
 * Per-track fading trails (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #3), recomputed fresh on
 * every call from `results` — `DetectionsStore.results()`, which already retains a short
 * newest-first history (up to 50 batches, `DetectionsStore#DETECTIONS_LIMIT`). **Not a mutable
 * accumulator**: there is nothing stateful in this module for `results` to be cleared *from* — a
 * stream switch already empties `DetectionsStore.results()` to `[]` (`DetectionsStore#track`'s own
 * doc comment), so the very next call here naturally returns an empty map, which is what "cleared on
 * stream change" means in a pure-function world.
 *
 * Points for one track come out **oldest-first** (draw order for a fading polyline); a track with
 * only one point in the window (a track that just appeared) still gets an entry — the caller decides
 * whether a single-point trail is worth stroking.
 */
export function trackTrails(
  results: readonly DetectionResult[],
  nowMs: number,
  windowMs: number = TRAIL_WINDOW_MS,
): ReadonlyMap<number, readonly TrailPoint[]> {
  const cutoffMs = nowMs - windowMs;
  const byTrack = new Map<number, TrailPoint[]>();
  // `results` is newest-first (VisionApi.streamDetections's own contract); walk it back-to-front so
  // each track's own point list comes out oldest-first without a second reverse pass.
  for (let i = results.length - 1; i >= 0; i--) {
    const result = results[i];
    const capturedAtMs = Date.parse(result.capturedAt);
    if (!Number.isFinite(capturedAtMs) || capturedAtMs < cutoffMs) {
      continue;
    }
    for (const detection of result.detections) {
      const trackId = detection.track?.id;
      if (trackId === undefined) {
        continue;
      }
      const point: TrailPoint = {
        x: detection.box.x + detection.box.width / 2,
        y: detection.box.y + detection.box.height / 2,
      };
      const points = byTrack.get(trackId);
      if (points) {
        points.push(point);
      } else {
        byTrack.set(trackId, [point]);
      }
    }
  }
  return byTrack;
}

// --- HiDPI canvas backing store (docs/plans/active/MEDIA-SOT-PLAN.md §8 wave M8) -----------------------------
// The overlay canvas used to size its backing store 1:1 with its CSS box (`canvas.width =
// video.clientWidth`), so every box/trail/label drew at 1 device pixel per CSS pixel — soft/blurry on
// any HiDPI display, quietly undercutting the reason the client overlay exists at all ("crisp boxes
// at any bitrate", `shared/player/player.ts`'s own class doc). The fix is backing-store-only: the CSS
// box size (and therefore `letterboxRect`'s own math, and hit-testing off
// `canvas.getBoundingClientRect()`) never changes, only how many physical pixels each CSS pixel maps
// to — `shared/player/player.ts#redrawOverlay` applies this via `ctx.setTransform(devicePixelRatio,
// ...)` right after resizing, so every existing draw call keeps working in CSS-pixel coordinates
// unmodified.

/** A canvas's backing-store size (device pixels) for a given CSS box size and `devicePixelRatio` —
 *  `Math.round` because `canvas.width`/`height` are integers and a fractional DPR (e.g. Windows'
 *  125% → `1.25`) would otherwise silently truncate instead of rounding to the nearest pixel. */
export function canvasBackingSize(
  cssWidth: number,
  cssHeight: number,
  devicePixelRatio: number,
): { readonly width: number; readonly height: number } {
  const ratio = devicePixelRatio > 0 ? devicePixelRatio : 1;
  return {
    width: Math.round(cssWidth * ratio),
    height: Math.round(cssHeight * ratio),
  };
}
