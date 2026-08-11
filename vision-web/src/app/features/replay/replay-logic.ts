import { deriveTrail } from '../../core/telemetry/telemetry-logic';
import type { DetectionResult, GeoPosition, TelemetrySample } from '../../core/api/models';

/**
 * Pure logic behind the flight-replay cockpit (docs/plans/done/MVP2-PLAN.md §R, R-b) — everything the scrub
 * bar/map/telemetry/detections panels compute from an already-fetched `UsageTimeline` and a scrub
 * position, with no HTTP, timers, or Leaflet involved. Split out so it is unit-testable in
 * isolation, mirroring `core/telemetry/telemetry-logic.ts`/`shared/player/player-recovery.ts`'s own split of pure
 * state/derivation from the component that drives it.
 *
 * `features/replay/replay.ts` fetches a usage's timeline exactly once (`maxPoints: 2000`, the
 * server's own clamp ceiling — see `VisionApi.usageTimeline`'s doc comment) and holds it in a
 * signal; every one of these functions then re-derives its answer from that same in-memory array
 * plus the current scrub time, so dragging the scrub bar is O(log n) per frame against data
 * already in hand — never a re-fetch.
 */

/**
 * Finds the index of the first item whose `timeOf(item)` is strictly after `atMs` — the standard
 * "upper bound" binary search, shared by `nearestSample`/`trailPrefix`/`nearestDetectionResult`
 * below. Assumes `items` is already ascending by `timeOf` (both `telemetry`/`detections` are, per
 * `UsageTimelineResponse`'s own contract).
 */
function upperBoundIndex<T>(items: readonly T[], atMs: number, timeOf: (item: T) => string): number {
  let lo = 0;
  let hi = items.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (Date.parse(timeOf(items[mid])) <= atMs) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  return lo;
}

/** The one item (by `timeOf`) closest to `atMs`, ties broken toward the earlier one — deterministic. */
function nearestByTime<T>(items: readonly T[], atMs: number, timeOf: (item: T) => string): T | undefined {
  if (items.length === 0) {
    return undefined;
  }
  const idx = upperBoundIndex(items, atMs, timeOf);
  const before = idx > 0 ? items[idx - 1] : undefined;
  const after = idx < items.length ? items[idx] : undefined;
  if (!before) {
    return after;
  }
  if (!after) {
    return before;
  }
  return atMs - Date.parse(timeOf(before)) <= Date.parse(timeOf(after)) - atMs ? before : after;
}

/**
 * The telemetry sample nearest `atMs` — the "value at the scrub time" reading every telemetry
 * panel and the map marker read from. No interpolation between samples (the task this cockpit
 * implements is literally named "nearest-sample"): a referee scrubbing to "minute 7" sees the
 * closest recorded reading, not a synthesized one.
 */
export function nearestSample(samples: readonly TelemetrySample[], atMs: number): TelemetrySample | undefined {
  return nearestByTime(samples, atMs, (sample) => sample.at);
}

/**
 * The flight trail drawn "up to" the scrub position (docs/plans/done/MVP2-PLAN.md §R, R-b: "map trail that
 * draws up to the scrub position") — every positioned sample with `at <= atMs`, in the same
 * chronological, gap-skipping shape `core/telemetry/telemetry-logic.ts#deriveTrail` already produces for the
 * live map. A sample exactly at `atMs` is included (inclusive prefix).
 */
export function trailPrefix(samples: readonly TelemetrySample[], atMs: number): readonly GeoPosition[] {
  return deriveTrail(samples.slice(0, upperBoundIndex(samples, atMs, (sample) => sample.at)));
}

/**
 * The detection result nearest `atMs` — mirrors `nearestSample`'s telemetry equivalent, but for
 * detections. Always resolves to *some* result when `detections` is non-empty, even one whose own
 * `detections` array is empty (an analyzed frame that saw nothing is still the honest "nearest
 * reading", not a gap) — `features/replay/replay.ts` additionally checks `isDetectionNear` before
 * trusting this as "what's showing right now" rather than a distant, unrelated frame.
 */
export function nearestDetectionResult(
  detections: readonly DetectionResult[],
  atMs: number,
): DetectionResult | undefined {
  return nearestByTime(detections, atMs, (result) => result.capturedAt);
}

/** How close the nearest detection result must be to the scrub time to count as "now", not a stale gap. */
export const DETECTION_MATCH_TOLERANCE_MS = 2_000;

/**
 * Whether `result` (typically `nearestDetectionResult`'s own answer) is close enough to `atMs` to
 * be shown as "detections right now" rather than a leftover from a distant part of the flight —
 * the difference between "nothing detected at this instant" (a nearby, empty result) and "no
 * detection data near this moment" (the nearest result is minutes away, e.g. CV ran only
 * intermittently). `features/replay/replay.ts`'s detections panel uses this to pick which of the two
 * honest empty states to show.
 */
export function isDetectionNear(
  result: DetectionResult | undefined,
  atMs: number,
  toleranceMs = DETECTION_MATCH_TOLERANCE_MS,
): boolean {
  return result !== undefined && Math.abs(Date.parse(result.capturedAt) - atMs) <= toleranceMs;
}

/** One slot in the scrub bar's density strip. */
export interface DetectionDensityBucket {
  /** Click-to-jump target — the earliest positive-detection result's `capturedAt` in this bucket. */
  readonly atMs: number;
  /** How many positive-detection frames landed in this bucket. */
  readonly count: number;
}

/** Default bucket count for `bucketDetections` — enough resolution for a wide scrub bar without one DOM node per detection. */
export const DEFAULT_DETECTION_BUCKETS = 120;

/**
 * Buckets a usage's *positive* detection frames (`detections.length > 0` — an empty inference is
 * not "where a detection occurred") into `bucketCount` equal-width slots across `[fromMs, toMs]`,
 * returning only the buckets that actually caught something, ascending by time.
 *
 * Backs the scrub bar's density strip (docs/plans/done/MVP2-PLAN.md §R, R-b: "density/markers along the
 * scrub bar where detections occurred… clicking a marker jumps the scrub there"). Rendering one
 * element per *bucket* rather than one per detection result keeps the strip cheap even for a
 * flight with hundreds of positive frames, and reads as density — a busy stretch of the flight
 * looks busier — instead of an unreadable smear of individual ticks. Each bucket's `atMs` is its
 * earliest result's `capturedAt`, so clicking it lands a referee at the first moment something was
 * seen in that stretch, not an arbitrary bucket midpoint.
 */
export function bucketDetections(
  detections: readonly DetectionResult[],
  fromMs: number,
  toMs: number,
  bucketCount = DEFAULT_DETECTION_BUCKETS,
): readonly DetectionDensityBucket[] {
  if (bucketCount <= 0 || toMs <= fromMs) {
    return [];
  }
  const span = toMs - fromMs;
  const buckets = new Map<number, { count: number; atMs: number }>();
  for (const result of detections) {
    if (result.detections.length === 0) {
      continue;
    }
    const capturedMs = Date.parse(result.capturedAt);
    const index = Math.min(bucketCount - 1, Math.max(0, Math.floor(((capturedMs - fromMs) / span) * bucketCount)));
    const existing = buckets.get(index);
    if (existing) {
      existing.count += 1;
      existing.atMs = Math.min(existing.atMs, capturedMs);
    } else {
      buckets.set(index, { count: 1, atMs: capturedMs });
    }
  }
  return [...buckets.values()].sort((a, b) => a.atMs - b.atMs);
}

/** `capDetectionBuckets`'s own default — docs/plans/done/OPS-CORE-PLAN.md §Q3a's pinned cap. */
export const DETECTION_STRIP_CAP = 200;

/** `capDetectionBuckets`'s result: the (possibly-trimmed) buckets to render, plus the pre-cap total. */
export interface CappedDetectionBuckets {
  readonly buckets: readonly DetectionDensityBucket[];
  readonly totalCount: number;
}

/**
 * Caps the scrub bar's density strip at the latest `cap` buckets (docs/plans/done/OPS-CORE-PLAN.md §Q3a) — a
 * pure slice, no virtualization library. `bucketDetections` already keeps the strip cheap by
 * summarizing into a fixed `bucketCount` of slots (well under this cap under today's own
 * `DEFAULT_DETECTION_BUCKETS`), so this rarely trims anything in practice; it exists as the strip's
 * own last-resort backstop should a caller ever ask for finer bucketing on an unusually long
 * flight, rather than trusting bucket-count tuning alone to keep the DOM cheap forever.
 *
 * `buckets` is assumed ascending by `atMs` (`bucketDetections`'s own contract) — keeping the tail
 * (`slice(totalCount - cap)`) is what makes "latest" literal and keeps the kept slice itself still
 * ascending, no re-sort needed. `totalCount` is the pre-cap length, so a caller can render "showing
 * latest N of totalCount" only when `totalCount > buckets.length` (i.e. something was actually cut).
 */
export function capDetectionBuckets(
  buckets: readonly DetectionDensityBucket[],
  cap: number = DETECTION_STRIP_CAP,
): CappedDetectionBuckets {
  const totalCount = buckets.length;
  return {
    buckets: totalCount > cap ? buckets.slice(totalCount - cap) : buckets,
    totalCount,
  };
}

// --- Playback clock (docs/plans/done/MVP2-PLAN.md §R, R-b: play/pause + 1x/4x/16x speed) -------------------

/** The three speeds the cockpit's transport control offers. */
export type PlaybackSpeed = 1 | 4 | 16;

/** One tick's result: the new scrub position, and whether playback should keep running. */
export interface PlaybackTick {
  readonly atMs: number;
  readonly playing: boolean;
}

/** Clamps a candidate scrub position into the usage's own time window — shared by dragging and playback. */
export function clampToRange(atMs: number, fromMs: number, toMs: number): number {
  if (toMs <= fromMs) {
    return fromMs;
  }
  return Math.min(toMs, Math.max(fromMs, atMs));
}

/**
 * Advances the scrub position by `deltaRealMs * speed` (wall-clock milliseconds elapsed since the
 * last tick, e.g. one `requestAnimationFrame` frame), clamped to `toMs`.
 *
 * Reaching `toMs` stops playback (`playing: false`) rather than looping — a referee reviewing
 * "where was it at minute 7" wants playback to land exactly on the end and stay there, not
 * silently restart mid-review. Speed changes need no special case here: `speed` is read fresh on
 * every call, so a caller that changes it between ticks (the transport control's 1×/4×/16×
 * buttons) simply gets a different multiplier on the very next tick — this function is stateless
 * per call, nothing to reconcile. A non-positive `deltaRealMs` (a clock hiccup, or the very first
 * tick with no prior frame to diff against) advances by zero rather than going backwards.
 */
export function advancePlaybackClock(
  atMs: number,
  deltaRealMs: number,
  speed: PlaybackSpeed,
  fromMs: number,
  toMs: number,
): PlaybackTick {
  const advanced = atMs + Math.max(0, deltaRealMs) * speed;
  const clamped = clampToRange(advanced, fromMs, toMs);
  return { atMs: clamped, playing: clamped < toMs };
}

// --- Recording video pane (docs/plans/done/OPS-CORE-PLAN.md §R, R-c) ----------------------------------------
// `GET /api/usages/{usageId}/recording` resolves a clip whose `start` is exactly the usage's own
// `startedAt` (`ReplayService#recordingFor`'s own contract — "start is the usage's own startedAt")
// — "clip t=0 aligns with usage start". Every function below anchors off that `recordingStartMs`
// directly, not `fromMs` (`UsageTimeline.from`, which defaults to the same instant but is a
// logically separate field), since the two coinciding is the recording endpoint's own promise, not
// this page's replay-window one.

/** `atMs` (the timeline's scrub position) → the video's own `currentTime`, in seconds, never negative. */
export function videoOffsetSeconds(atMs: number, recordingStartMs: number): number {
  return Math.max(0, (atMs - recordingStartMs) / 1000);
}

/** The video's own `currentTime` (seconds) → an absolute scrub `atMs`, clamped into the replay window. */
export function videoTimeToAtMs(currentSeconds: number, recordingStartMs: number, fromMs: number, toMs: number): number {
  return clampToRange(recordingStartMs + currentSeconds * 1000, fromMs, toMs);
}

/** Below this many seconds of drift, a programmatic reseek is skipped — see `ReplayPage`'s own guarded-effect doc comment for why. */
export const VIDEO_SEEK_THRESHOLD_SECONDS = 0.25;

/**
 * Whether the atMs→video sync effect should actually call `video.currentTime = targetSeconds` —
 * `false` once the video's own position is already within `thresholdSeconds` of the target, which
 * is what keeps ordinary 1× playback (where the video's own `timeupdate` and the timeline's own
 * scrub position drift apart by only a few tens of milliseconds per tick) from reseeking on every
 * single frame; a genuine scrub/jump (density-strip click, drag, or ≥4× playback) always exceeds
 * the threshold and reseeks immediately.
 */
export function shouldSeekVideo(
  currentSeconds: number,
  targetSeconds: number,
  thresholdSeconds: number = VIDEO_SEEK_THRESHOLD_SECONDS,
): boolean {
  return Math.abs(currentSeconds - targetSeconds) > thresholdSeconds;
}

// --- Clip export (docs/plans/done/OPS-CORE-PLAN.md §R, R-c: "Download clip") --------------------------------

export interface ClipWindow {
  /** Offset from the recording's own `start`, milliseconds — never negative. */
  readonly startOffsetMs: number;
  /** The clip's own length, milliseconds — always positive. */
  readonly durationMs: number;
}

/** The "whole flight" clip window — every second of the replay's own `[fromMs, toMs)` range, anchored off the recording's own start. */
export function wholeFlightClipWindow(recordingStartMs: number, fromMs: number, toMs: number): ClipWindow {
  return {
    startOffsetMs: Math.max(0, fromMs - recordingStartMs),
    durationMs: Math.max(1000, toMs - fromMs),
  };
}

/**
 * The current selection's clip window when both marks are set and well-ordered, else the whole
 * flight (docs/plans/done/OPS-CORE-PLAN.md §R, R-c: "current selected window (or whole flight if no selection)").
 */
export function selectedClipWindow(
  recordingStartMs: number,
  selectionStartMs: number | undefined,
  selectionEndMs: number | undefined,
  fromMs: number,
  toMs: number,
): ClipWindow {
  if (selectionStartMs !== undefined && selectionEndMs !== undefined && selectionEndMs > selectionStartMs) {
    return { startOffsetMs: Math.max(0, selectionStartMs - recordingStartMs), durationMs: selectionEndMs - selectionStartMs };
  }
  return wholeFlightClipWindow(recordingStartMs, fromMs, toMs);
}

/**
 * Builds the "Download clip" `<a download>` href: given the recording's own base `/get` URL (whose
 * `start`/`duration` query params already describe the *whole* recorded window) and a `window`
 * (offsets relative to that same `start`), returns a new URL with `start`/`duration` replaced to
 * describe just that sub-window — the `path` query param (and every other part of the URL) is left
 * untouched, so this works regardless of whether `recordingUrl` points at this app's own origin or
 * mediamtx's own playback server directly. `undefined` when `recordingUrl` doesn't parse as a URL
 * or carries no `start` param to anchor against (never a broken href).
 */
export function buildClipDownloadUrl(recordingUrl: string, window: ClipWindow): string | undefined {
  let parsed: URL;
  try {
    parsed = new URL(recordingUrl);
  } catch {
    return undefined;
  }
  const baseStart = parsed.searchParams.get('start');
  if (baseStart === null) {
    return undefined;
  }
  const baseStartMs = Date.parse(baseStart);
  if (Number.isNaN(baseStartMs)) {
    return undefined;
  }
  const durationSeconds = Math.max(1, Math.round(window.durationMs / 1000));
  parsed.searchParams.set('start', new Date(baseStartMs + window.startOffsetMs).toISOString());
  parsed.searchParams.set('duration', String(durationSeconds));
  return parsed.toString();
}

// --- Event → replay deep link (docs/plans/done/OPS-CORE-PLAN.md §Q1) ----------------------------------------

/** Parses the `?t=` deep-link query param (a plain offset-ms string) — `undefined` for anything absent/non-numeric, never `NaN`. */
export function parseDeepLinkOffsetMs(raw: string | undefined): number | undefined {
  if (raw === undefined) {
    return undefined;
  }
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : undefined;
}
