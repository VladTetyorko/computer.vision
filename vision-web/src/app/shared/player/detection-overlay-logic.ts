import type { Detection, DetectionResult } from '../../core/api/models';

/**
 * Pure logic behind the client-side vector detection overlay (docs/CYCLES-PLAN.md §11, CD-b item
 * 6): which completed `DetectionResult` batch best matches the frame currently on-screen, given
 * HLS's live-edge latency. Split out so the sync math is unit-testable without a `<canvas>`,
 * hls.js, or a poller — mirrors `shared/player/player-recovery.ts`.
 */

/** The per-tile toggle's three states — see `shouldDrawOverlay`'s doc comment for what each means. */
export type BoxesMode = 'overlay' | 'burned' | 'off';

/**
 * How much slack (in units of "one detection batch interval") to tolerate beyond the raw latency
 * estimate before discarding a result as "not on screen yet" — docs/CYCLES-PLAN.md §11 item 6's
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
  // estimate hasn't settled yet, right after attach) — the oldest available beats showing nothing.
  return results[results.length - 1];
}

/** The observed average gap between consecutive `capturedAt` values — one "batch" of `slackBatches`. */
function averageBatchIntervalMs(results: readonly DetectionResult[]): number {
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
 * Whether the canvas overlay should actually draw boxes right now.
 *
 * `'burned'` and `'off'` both suppress the client canvas — disabling the server's own burn-in is
 * out of scope for this cycle (docs/CYCLES-PLAN.md §11 item 6: "no server change"), so there is no
 * way to make the video itself show *zero* boxes; the two states differ only in what they claim
 * about intent (trusting the baked-in boxes vs. wanting no detection UI, including no hover/click)
 * and in whichever future cycle does add a server-side burn-in toggle, that is where `'off'` would
 * start doing more than `'burned'`.
 */
export function shouldDrawOverlay(mode: BoxesMode, hasResult: boolean): boolean {
  return mode === 'overlay' && hasResult;
}

// --- Composite-model box hues (docs/OPS-CORE-PLAN.md §Q3b) --------------------------------------

/**
 * The key `modelHue` colors a box by — cv-service's composite mode (docs/CV-MODELS-PLAN.md item
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
 * Stable per-model box color (docs/OPS-CORE-PLAN.md §Q3b): `DEFAULT_MODEL_KEY` always resolves to
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

// --- Track-aware rendering (docs/TRACKING-PLAN.md §4/§10, wave T7) ------------------------------
// Everything below gates on `detection.track` alone — one null check (per §4.G's own "the client
// gets a single null check gating all track rendering" design) — so a stream running an old/absent
// server, or a detection tracking hasn't (yet) assigned an id to, draws byte-identically to before
// this wave: `shared/player/player.ts#drawBox` only reaches these functions once `detection.track`
// is present.

/**
 * The box label `shared/player/player.ts#drawBox` draws — `"#7 car 82%"` once `detection.track` is
 * present (docs/TRACKING-PLAN.md §10 touchable outcome #1's "stable box numbers"), the exact,
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
 * Stable per-track box color (docs/TRACKING-PLAN.md §10 touchable outcome #1) — the same hash-to-hue
 * mechanism as {@link modelHue}, keyed on `trackId` instead of a model key, so one tracked object
 * keeps one color across every frame **even as its label flips** (composite-mode member handoff,
 * docs/TRACKING-PLAN.md §5.I risk R9) — a track's identity is its id, never its current label.
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

/** How far back a trail reaches (docs/TRACKING-PLAN.md §10 touchable outcome #3 — "a trail behind a
 *  tracked car", not a full trajectory history; S2's map trails are the durable, longer-lived kind). */
export const TRAIL_WINDOW_MS = 2_000;

/**
 * Per-track fading trails (docs/TRACKING-PLAN.md §10 touchable outcome #3), recomputed fresh on
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
