import { describe, expect, it } from 'vitest';
import {
  DEFAULT_BOX_COLOR,
  DEFAULT_DECLUTTER_LEVEL,
  DEFAULT_MODEL_KEY,
  DETECTION_STALE_CUTOFF_SECONDS,
  EXTRAPOLATION_MATCH_GATE,
  EXTRAPOLATION_MAX_MS,
  LOCK_DIM_ALPHA_PERCENT,
  MAX_PAINTED_LABELS,
  MOVING_DISPLACEMENT_THRESHOLD,
  NOTABLE_TOP_K,
  STALE_FADE_ALPHA_PERCENT,
  STALE_FADE_BATCH_MULTIPLIER,
  STICKY_LABEL_SWITCH_MARGIN,
  STICKY_LABEL_SWITCH_STREAK,
  STICKY_LABEL_VOTE_WINDOW,
  SUB_SCALE_PX,
  T2_ALPHA_PERCENT,
  TRAIL_WINDOW_MS,
  applyStickyLabels,
  averageBatchIntervalMs,
  canvasBackingSize,
  classBucket,
  classBucketHue,
  cycleBoxesMode,
  declutterLevelLabel,
  detectionAlphaPercent,
  detectionModelKey,
  detectionTiers,
  detectionsPausedNotice,
  distinctModelKeys,
  electStickyLabels,
  estimatedOnScreenAtMs,
  extrapolateDetections,
  findPredecessorResult,
  formatDetectionLabel,
  formatTierLabel,
  isDetectionStale,
  modelHue,
  overlaySyncLatencySeconds,
  placeLabels,
  selectDetectionResult,
  shouldDrawOverlay,
  tierAlphaPercent,
  tierBoxColor,
  tiersForDeclutterLevel,
  trackTrails,
  type DetectionTierContext,
  type LabelCandidate,
} from './detection-overlay-logic';
import type { Detection, DetectionResult } from '../../core/api/models';

function result(partial: Partial<DetectionResult> = {}): DetectionResult {
  return {
    streamId: 's-0',
    frameSequence: 0,
    capturedAt: '2026-07-22T00:00:00.000Z',
    inferenceMillis: 10,
    detections: [],
    ...partial,
  };
}

describe('selectDetectionResult', () => {
  it('returns undefined for no results', () => {
    expect(selectDetectionResult([], Date.now(), 0)).toBeUndefined();
  });

  it('picks the newest result whose capturedAt is not ahead of the on-screen instant', () => {
    // 200ms batch cadence; results newest-first, as VisionApi returns them.
    const newest = result({ capturedAt: '2026-07-22T00:00:01.000Z' });
    const middle = result({ capturedAt: '2026-07-22T00:00:00.800Z' });
    const oldest = result({ capturedAt: '2026-07-22T00:00:00.600Z' });
    const results = [newest, middle, oldest];

    // now = 1.000s, 500ms of latency -> the on-screen frame is from ~0.500s; with no slack
    // (batch interval 200ms, 1 batch of slack = 200ms) the cutoff is 0.700s, so `oldest` (0.600s) wins.
    const nowMs = Date.parse('2026-07-22T00:00:01.000Z');
    const picked = selectDetectionResult(results, nowMs, 0.5, 0);
    expect(picked).toBe(oldest);
  });

  it('applies batch slack so a marginally-early result is not discarded', () => {
    const newer = result({ capturedAt: '2026-07-22T00:00:00.900Z' });
    const older = result({ capturedAt: '2026-07-22T00:00:00.700Z' });
    const results = [newer, older];

    // batch interval = 200ms; on-screen instant = now(1.000s) - latency(0.150s) = 0.850s.
    // Without slack, 0.900s is "ahead" and would be skipped; with 1 batch (200ms) of slack the
    // cutoff becomes 1.050s, so the newer result is accepted instead of falling back to `older`.
    const nowMs = Date.parse('2026-07-22T00:00:01.000Z');
    expect(selectDetectionResult(results, nowMs, 0.15, 1)).toBe(newer);
    expect(selectDetectionResult(results, nowMs, 0.15, 0)).toBe(older);
  });

  it('treats a null latency as zero (no distance behind live yet known)', () => {
    const only = result({ capturedAt: '2026-07-22T00:00:00.000Z' });
    const nowMs = Date.parse('2026-07-22T00:00:00.000Z') + 10;
    expect(selectDetectionResult([only], nowMs, null, 0)).toBe(only);
  });

  it('falls back to the oldest result when every result appears to be in the future', () => {
    const results = [
      result({ capturedAt: '2026-07-22T00:00:10.000Z' }),
      result({ capturedAt: '2026-07-22T00:00:09.000Z' }),
    ];
    const nowMs = Date.parse('2026-07-22T00:00:00.000Z'); // long before any result
    expect(selectDetectionResult(results, nowMs, 0, 0)).toBe(results[results.length - 1]);
  });

  it('handles a single result with no batch interval to derive slack from', () => {
    const only = result({ capturedAt: '2026-07-22T00:00:00.000Z' });
    const nowMs = Date.parse('2026-07-22T00:00:00.500Z');
    expect(selectDetectionResult([only], nowMs, 0, 1)).toBe(only);
  });

  it('a fallback-eligible oldest result that is itself stale in absolute terms draws nothing (§3.4 bound)', () => {
    // A huge latency estimate pushes the on-screen instant far into the past, so `staleOldest`
    // still looks "in the future" relative to it — the fallback branch fires, but the batch it
    // would return is actually DETECTION_STALE_CUTOFF_SECONDS+ old in real wall-clock terms.
    const nowMs = Date.parse('2026-07-22T00:00:10.000Z');
    const staleOldest = result({ capturedAt: '2026-07-22T00:00:03.000Z' }); // 7s old, absolute
    expect(selectDetectionResult([staleOldest], nowMs, 1000, 0)).toBeUndefined();
  });

  it('a fallback-eligible oldest result within the cutoff still wins, unchanged', () => {
    const nowMs = Date.parse('2026-07-22T00:00:10.000Z');
    const freshOldest = result({ capturedAt: '2026-07-22T00:00:08.000Z' }); // 2s old, absolute
    expect(selectDetectionResult([freshOldest], nowMs, 1000, 0)).toBe(freshOldest);
  });
});

describe('estimatedOnScreenAtMs', () => {
  it('subtracts the latency (in seconds, converted to ms) from now', () => {
    expect(estimatedOnScreenAtMs(10_000, 2.5)).toBe(7_500);
  });

  it('degrades a null latency (not yet measured) to 0', () => {
    expect(estimatedOnScreenAtMs(10_000, null)).toBe(10_000);
  });

  it('degrades a negative latency to 0 rather than adding time', () => {
    expect(estimatedOnScreenAtMs(10_000, -1)).toBe(10_000);
  });
});

describe('shouldDrawOverlay', () => {
  it('draws in every non-off declutter level with a result available', () => {
    expect(shouldDrawOverlay('all', true)).toBe(true);
    expect(shouldDrawOverlay('priority', true)).toBe(true);
    expect(shouldDrawOverlay('locked', true)).toBe(true);
  });

  it('never draws without a result, even at the "all" level', () => {
    expect(shouldDrawOverlay('all', false)).toBe(false);
  });

  it('never draws in off mode', () => {
    expect(shouldDrawOverlay('off', true)).toBe(false);
  });
});

// --- Declutter levels (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.6, wave W4) ------------------------
// 'overlay'/'off' (W3) is now a four-state density cycle: 'all' -> 'priority' -> 'locked' -> 'off'.

describe('cycleBoxesMode', () => {
  it('cycles all -> priority -> locked -> off -> all', () => {
    expect(cycleBoxesMode('all')).toBe('priority');
    expect(cycleBoxesMode('priority')).toBe('locked');
    expect(cycleBoxesMode('locked')).toBe('off');
    expect(cycleBoxesMode('off')).toBe('all');
  });

  it('a stale/unrecognized mode restarts from the front of the cycle', () => {
    expect(cycleBoxesMode('overlay' as never)).toBe('all');
    expect(cycleBoxesMode('burned' as never)).toBe('all');
  });
});

describe('declutterLevelLabel', () => {
  it('names every level', () => {
    expect(declutterLevelLabel('all')).toBe('All');
    expect(declutterLevelLabel('priority')).toBe('Priority');
    expect(declutterLevelLabel('locked')).toBe('Locked only');
    expect(declutterLevelLabel('off')).toBe('Off');
  });
});

describe('DEFAULT_DECLUTTER_LEVEL', () => {
  it('is priority, not the undifferentiated "all" level', () => {
    expect(DEFAULT_DECLUTTER_LEVEL).toBe('priority');
  });
});

describe('tiersForDeclutterLevel', () => {
  it('"all" draws every tier', () => {
    expect(tiersForDeclutterLevel('all')).toEqual(new Set(['T0', 'T1', 'T2', 'T3']));
  });

  it('"priority" draws T0+T1+T3 but hides ambient T2', () => {
    expect(tiersForDeclutterLevel('priority')).toEqual(new Set(['T0', 'T1', 'T3']));
  });

  it('"locked" draws only T0', () => {
    expect(tiersForDeclutterLevel('locked')).toEqual(new Set(['T0']));
  });

  it('"off" draws nothing', () => {
    expect(tiersForDeclutterLevel('off').size).toBe(0);
  });
});

describe('averageBatchIntervalMs', () => {
  it('is zero for fewer than two results — nothing to derive a cadence from', () => {
    expect(averageBatchIntervalMs([])).toBe(0);
    expect(averageBatchIntervalMs([result()])).toBe(0);
  });

  it('averages the gaps between consecutive newest-first capturedAt values', () => {
    const results = [
      result({ capturedAt: '2026-07-22T00:00:01.000Z' }),
      result({ capturedAt: '2026-07-22T00:00:00.800Z' }),
      result({ capturedAt: '2026-07-22T00:00:00.600Z' }),
    ];
    expect(averageBatchIntervalMs(results)).toBe(200);
  });
});

// --- Staleness honesty (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.4, D7) ---------------------------

describe('detectionAlphaPercent', () => {
  it('is full alpha while within the fade threshold', () => {
    expect(detectionAlphaPercent(0, 500)).toBe(100);
    expect(detectionAlphaPercent(1_000, 500)).toBe(100); // exactly 2 batch-intervals — not yet past
  });

  it('fades once older than STALE_FADE_BATCH_MULTIPLIER batch-intervals', () => {
    expect(detectionAlphaPercent(1_001, 500)).toBe(STALE_FADE_ALPHA_PERCENT);
  });

  it('never fades with no observed cadence to compare against (a single-batch history)', () => {
    expect(detectionAlphaPercent(10_000, 0)).toBe(100);
  });
});

describe('isDetectionStale', () => {
  it('is not stale within the cutoff, including exactly at it', () => {
    expect(isDetectionStale(0)).toBe(false);
    expect(isDetectionStale(DETECTION_STALE_CUTOFF_SECONDS * 1000)).toBe(false);
  });

  it('is stale once older than the cutoff', () => {
    expect(isDetectionStale(DETECTION_STALE_CUTOFF_SECONDS * 1000 + 1)).toBe(true);
  });
});

describe('detectionsPausedNotice', () => {
  const now = Date.parse('2026-07-22T00:00:20.000Z');

  it('says nothing when nothing has ever arrived', () => {
    expect(detectionsPausedNotice(undefined, now)).toBeNull();
  });

  it('says nothing while the last-seen batch is still within the freshness window', () => {
    const capturedAt = new Date(now - DETECTION_STALE_CUTOFF_SECONDS * 1000).toISOString();
    expect(detectionsPausedNotice(capturedAt, now)).toBeNull();
  });

  it('reports how long ago detections were last seen, rounded to the second, once stale', () => {
    const capturedAt = new Date(now - 12_000).toISOString();
    expect(detectionsPausedNotice(capturedAt, now)).toBe('Detections paused — last seen 12s ago');
  });

  it('clamps a future-dated capturedAt (clock skew) to age zero rather than reporting a negative age', () => {
    const capturedAt = new Date(now + 1_000).toISOString();
    expect(detectionsPausedNotice(capturedAt, now)).toBeNull();
  });
});

// --- WHEP-aware overlay sync latency (docs/plans/done/MEDIA-SOT-PLAN.md §6/§8 wave M8) ---------------------

describe('overlaySyncLatencySeconds', () => {
  it('HLS keeps behindLive unchanged, including null', () => {
    expect(overlaySyncLatencySeconds('hls', 1.5, null)).toBe(1.5);
    expect(overlaySyncLatencySeconds('hls', null, 0.3)).toBeNull();
  });

  it('WHEP uses the measured whepLatencySeconds instead of the old hard-pinned 0', () => {
    expect(overlaySyncLatencySeconds('webrtc', 0, 0.35)).toBe(0.35);
  });

  it('WHEP degrades an unmeasured latency (null) to 0, not to behindLive', () => {
    expect(overlaySyncLatencySeconds('webrtc', 0, null)).toBe(0);
  });
});

// --- HiDPI canvas backing store (docs/plans/done/MEDIA-SOT-PLAN.md §8 wave M8) -------------------------------

describe('canvasBackingSize', () => {
  it('is a 1:1 pass-through at devicePixelRatio 1 — no behavior change on a standard display', () => {
    expect(canvasBackingSize(640, 360, 1)).toEqual({ width: 640, height: 360 });
  });

  it('scales the backing store by devicePixelRatio on a HiDPI display', () => {
    expect(canvasBackingSize(640, 360, 2)).toEqual({ width: 1280, height: 720 });
  });

  it('rounds a fractional ratio (e.g. Windows 125% scaling) to the nearest device pixel', () => {
    expect(canvasBackingSize(640, 360, 1.25)).toEqual({ width: 800, height: 450 });
  });

  it('treats a zero or negative ratio as 1 rather than collapsing the backing store to nothing', () => {
    expect(canvasBackingSize(640, 360, 0)).toEqual({ width: 640, height: 360 });
  });
});

function detection(label: string): Pick<Detection, 'label'> {
  return { label };
}

describe('detectionModelKey', () => {
  it('resolves an unprefixed label (any single-model stream) to DEFAULT_MODEL_KEY', () => {
    expect(detectionModelKey(detection('tank'))).toBe(DEFAULT_MODEL_KEY);
    expect(detectionModelKey(detection('person'))).toBe(DEFAULT_MODEL_KEY);
  });

  it('extracts the short-name prefix from a composite-mode label', () => {
    expect(detectionModelKey(detection('orion12l:tank'))).toBe('orion12l');
    expect(detectionModelKey(detection('yolo11n:person'))).toBe('yolo11n');
  });

  it('a label that merely contains a colon at position 0 is not treated as a prefix', () => {
    // separatorIndex must be > 0 — an empty prefix would be a nonsensical model key.
    expect(detectionModelKey(detection(':oops'))).toBe(DEFAULT_MODEL_KEY);
  });
});

describe('modelHue', () => {
  it('DEFAULT_MODEL_KEY always resolves to the exact original box color — zero change for single-model streams', () => {
    expect(modelHue(DEFAULT_MODEL_KEY)).toBe(DEFAULT_BOX_COLOR);
    expect(modelHue(DEFAULT_MODEL_KEY)).toBe('#4f8cff');
  });

  it('DEFAULT_MODEL_KEY at partial alpha matches the exact original label-background literal', () => {
    expect(modelHue(DEFAULT_MODEL_KEY, 85)).toBe('rgb(79 140 255 / 85%)');
  });

  it('is stable — the same key always hashes to the same color', () => {
    expect(modelHue('orion12l')).toBe(modelHue('orion12l'));
    expect(modelHue('yolo11n')).toBe(modelHue('yolo11n'));
  });

  it('is distinct across a sample of different, non-default keys', () => {
    const keys = ['orion12l', 'yolo11n', 'future-model', 'another-one', 'zzz'];
    const colors = keys.map((key) => modelHue(key));
    expect(new Set(colors).size).toBe(colors.length);
  });

  it('never collides with the default color for a non-default key', () => {
    expect(modelHue('orion12l')).not.toBe(DEFAULT_BOX_COLOR);
    expect(modelHue('yolo11n')).not.toBe(DEFAULT_BOX_COLOR);
  });

  it('a non-default key at partial alpha embeds the alpha in the hsl() string', () => {
    const opaque = modelHue('orion12l');
    const translucent = modelHue('orion12l', 85);
    expect(translucent).toBe(`${opaque.slice(0, -1)} / 85%)`);
  });
});

describe('distinctModelKeys', () => {
  it('is empty for no detections', () => {
    expect(distinctModelKeys([])).toEqual([]);
  });

  it('collapses every unprefixed detection into one DEFAULT_MODEL_KEY entry', () => {
    expect(distinctModelKeys([detection('person'), detection('car'), detection('dog')])).toEqual([DEFAULT_MODEL_KEY]);
  });

  it('returns one entry per distinct prefix, first-seen order, regardless of repeats', () => {
    const detections = [
      detection('yolo11n:person'),
      detection('orion12l:tank'),
      detection('yolo11n:car'),
      detection('orion12l:truck'),
    ];
    expect(distinctModelKeys(detections)).toEqual(['yolo11n', 'orion12l']);
  });

  it('a single member producing detections this frame reads as single-model, even mid-composite-mode', () => {
    expect(distinctModelKeys([detection('orion12l:tank'), detection('orion12l:truck')])).toEqual(['orion12l']);
  });
});

// --- Track-aware rendering (docs/plans/done/TRACKING-PLAN.md §4/§10, wave T7) ------------------------------

function fullDetection(partial: Partial<Detection> = {}): Detection {
  return {
    label: 'car',
    confidence: 0.82,
    box: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 },
    modelId: 'yolo26n.pt',
    modelVersion: 'latest',
    ...partial,
  };
}

describe('formatDetectionLabel', () => {
  it('is unchanged for an untracked detection — byte-identical to before this wave', () => {
    expect(formatDetectionLabel(fullDetection())).toBe('car 82%');
  });

  it('prefixes the track id once a detection carries one', () => {
    const tracked = fullDetection({
      track: { id: 7, state: 'CONFIRMED', source: 'TRACKER', velocityX: 0, velocityY: 0, reupdated: false },
    });
    expect(formatDetectionLabel(tracked)).toBe('#7 car 82%');
  });
});

describe('trackTrails', () => {
  function trackedResult(capturedAt: string, points: readonly { trackId: number; cx: number; cy: number }[]): DetectionResult {
    return result({
      capturedAt,
      detections: points.map(({ trackId, cx, cy }) =>
        fullDetection({
          box: { x: cx - 0.05, y: cy - 0.05, width: 0.1, height: 0.1 },
          track: { id: trackId, state: 'CONFIRMED', source: 'TRACKER', velocityX: 0, velocityY: 0, reupdated: false },
        }),
      ),
    });
  }

  it('is empty for no results', () => {
    expect(trackTrails([], Date.now()).size).toBe(0);
  });

  it('ignores untracked detections entirely', () => {
    const untracked = result({ capturedAt: '2026-07-22T00:00:00.000Z', detections: [fullDetection()] });
    const nowMs = Date.parse('2026-07-22T00:00:00.000Z');
    expect(trackTrails([untracked], nowMs).size).toBe(0);
  });

  it('accumulates one track\'s box centers, oldest-first, from newest-first results', () => {
    const newest = trackedResult('2026-07-22T00:00:01.000Z', [{ trackId: 7, cx: 0.5, cy: 0.5 }]);
    const oldest = trackedResult('2026-07-22T00:00:00.500Z', [{ trackId: 7, cx: 0.2, cy: 0.2 }]);
    const nowMs = Date.parse('2026-07-22T00:00:01.000Z');

    const trails = trackTrails([newest, oldest], nowMs);
    expect(trails.get(7)).toEqual([
      { x: 0.2, y: 0.2 },
      { x: 0.5, y: 0.5 },
    ]);
  });

  it('keys separate tracks under separate ids in the same result', () => {
    const batch = trackedResult('2026-07-22T00:00:00.000Z', [
      { trackId: 1, cx: 0.1, cy: 0.1 },
      { trackId: 2, cx: 0.9, cy: 0.9 },
    ]);
    const nowMs = Date.parse('2026-07-22T00:00:00.000Z');

    const trails = trackTrails([batch], nowMs);
    expect(trails.get(1)).toEqual([{ x: 0.1, y: 0.1 }]);
    expect(trails.get(2)).toEqual([{ x: 0.9, y: 0.9 }]);
  });

  it('drops points older than the trail window', () => {
    const inWindow = trackedResult('2026-07-22T00:00:02.900Z', [{ trackId: 7, cx: 0.5, cy: 0.5 }]);
    const tooOld = trackedResult('2026-07-22T00:00:00.000Z', [{ trackId: 7, cx: 0.1, cy: 0.1 }]);
    const nowMs = Date.parse('2026-07-22T00:00:03.000Z'); // 100ms / 3000ms old respectively

    const trails = trackTrails([inWindow, tooOld], nowMs, TRAIL_WINDOW_MS);
    expect(trails.get(7)).toEqual([{ x: 0.5, y: 0.5 }]);
  });

  it('clears for free on a stream switch — an empty results array yields an empty map', () => {
    // DetectionsStore.results() resets to [] the instant track() targets a new stream/asset
    // (DetectionsStore#track's own doc comment) — this function has no state of its own to reset.
    expect(trackTrails([], Date.now()).size).toBe(0);
  });
});

// --- Priority tiers (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.2, wave W4) ---------------------------

function trackedDetection(partial: Partial<Detection> = {}, trackId = 1): Detection {
  return fullDetection({
    track: { id: trackId, state: 'CONFIRMED', source: 'TRACKER', velocityX: 0, velocityY: 0, reupdated: false },
    ...partial,
  });
}

/** At `tierContext()`'s default 200x200 content size, a fraction safely under/over SUB_SCALE_PX
 *  on a given axis — named rather than restating the arithmetic in every test that needs a
 *  deliberately tiny or deliberately large box. */
const UNDER_SUB_SCALE_FRACTION = (SUB_SCALE_PX - 2) / 200;
const OVER_SUB_SCALE_FRACTION = (SUB_SCALE_PX + 8) / 200;

function tierContext(partial: Partial<DetectionTierContext> = {}): DetectionTierContext {
  return {
    lockedTrackId: 0,
    hoveredDetection: null,
    trails: new Map(),
    contentWidthPx: 200,
    contentHeightPx: 200,
    hoveredClass: null,
    ...partial,
  };
}

// --- Sticky labels per track (docs/plans/done/TRACK-IDENTITY-PLAN.md §L3 item 1) ---------------------------

/** Builds a `results` batch list (newest-first) from oldest-first `(label, confidence)` observations for
 *  one track — {@link electStickyLabels}/{@link electFromObservations}'s own replay direction, reversed
 *  here once so every test can state its scenario in the natural "what happened, in order" reading. */
function observationBatches(trackId: number, observationsOldestFirst: readonly [string, number][]): DetectionResult[] {
  return [...observationsOldestFirst]
    .reverse()
    .map(([label, confidence], index) =>
      result({
        frameSequence: index,
        detections: [trackedDetection({ label, confidence }, trackId)],
      }),
    );
}

describe('electStickyLabels', () => {
  it('a track with a single observation elects its own raw label', () => {
    const elected = electStickyLabels(observationBatches(7, [['plant', 0.7]]));
    expect(elected.get(7)).toBe('plant');
  });

  it('incumbent holds under alternating noise — no single challenger ever leads by the margin', () => {
    // Mirrors L1's own "incumbent holds under alternating noise" case (docs/plans/active/
    // TRACK-IDENTITY-PLAN.md §L1 item 5): a die-roll label alternates every pass, but the two
    // candidates' tallies stay within STICKY_LABEL_SWITCH_MARGIN of each other throughout, so the
    // very first observation's label — "plant" — never actually gets out-voted.
    const observations: [string, number][] = [
      ['plant', 0.5],
      ['helicopter', 0.5],
      ['plant', 0.5],
      ['helicopter', 0.5],
      ['plant', 0.5],
      ['helicopter', 0.5],
    ];
    const elected = electStickyLabels(observationBatches(7, observations));
    expect(elected.get(7)).toBe('plant');
  });

  it('switches after a genuine streak — a challenger leading by the margin for STICKY_LABEL_SWITCH_STREAK passes in a row', () => {
    expect(STICKY_LABEL_SWITCH_STREAK).toBe(3);
    expect(STICKY_LABEL_SWITCH_MARGIN).toBe(1.5);
    const observations: [string, number][] = [
      ['plant', 0.3], // incumbent, low weight
      ['helicopter', 0.9], // streak 1 (0.9 > 0.3 * 1.5)
      ['helicopter', 0.9], // streak 2
      ['helicopter', 0.9], // streak 3 — switches
    ];
    const elected = electStickyLabels(observationBatches(7, observations));
    expect(elected.get(7)).toBe('helicopter');
  });

  it('does not switch one pass short of the required streak', () => {
    const observations: [string, number][] = [
      ['plant', 0.3],
      ['helicopter', 0.9],
      ['helicopter', 0.9], // only streak 2 — one short of STICKY_LABEL_SWITCH_STREAK (3)
    ];
    const elected = electStickyLabels(observationBatches(7, observations));
    expect(elected.get(7)).toBe('plant');
  });

  it('only the most recent STICKY_LABEL_VOTE_WINDOW observations count', () => {
    expect(STICKY_LABEL_VOTE_WINDOW).toBe(10);
    // The true oldest observation ("phantom", high weight) falls outside the 10-observation window;
    // if it were wrongly included, its weight would never be out-voted by the low-confidence "steady"
    // run that follows (0.1 * 10 = 1.0 never exceeds 1.0 * 1.5), so the test only passes once windowing
    // is applied correctly.
    const observations: [string, number][] = [
      ['phantom', 1.0],
      ...Array.from({ length: 10 }, (): [string, number] => ['steady', 0.1]),
    ];
    const elected = electStickyLabels(observationBatches(7, observations));
    expect(elected.get(7)).toBe('steady');
  });

  it('elects independently per track — one track cannot influence another', () => {
    const trackSeven = observationBatches(7, [['plant', 0.5]]);
    const trackNine = observationBatches(9, [['car', 0.5]]);
    const merged: DetectionResult[] = trackSeven.map((batch, index) => ({
      ...batch,
      detections: [...batch.detections, ...trackNine[index].detections],
    }));
    const elected = electStickyLabels(merged);
    expect(elected.get(7)).toBe('plant');
    expect(elected.get(9)).toBe('car');
  });

  it('returns an empty map for no results', () => {
    expect(electStickyLabels([]).size).toBe(0);
  });

  it('ignores untracked detections entirely — nothing to elect over', () => {
    const untracked = result({ detections: [fullDetection({ label: 'car' })] });
    expect(electStickyLabels([untracked]).size).toBe(0);
  });
});

describe('applyStickyLabels', () => {
  it('leaves an untracked detection completely unchanged — same object reference', () => {
    const untracked = fullDetection({ label: 'car' });
    const [result0] = applyStickyLabels([untracked], new Map([[7, 'truck']]));
    expect(result0).toBe(untracked);
  });

  it('swaps a tracked detection\'s label for its election entry, keeping every other field', () => {
    const tracked = trackedDetection({ label: 'plant', confidence: 0.6 }, 7);
    const [displayed] = applyStickyLabels([tracked], new Map([[7, 'helicopter']]));
    expect(displayed.label).toBe('helicopter');
    expect(displayed.confidence).toBe(0.6);
    expect(displayed.box).toBe(tracked.box);
    expect(displayed.track).toBe(tracked.track);
  });

  it('keeps the same object reference once the sticky label already equals the raw one — the L1-deployed no-op case', () => {
    const tracked = trackedDetection({ label: 'car' }, 7);
    const [displayed] = applyStickyLabels([tracked], new Map([[7, 'car']]));
    expect(displayed).toBe(tracked);
  });

  it('keeps the same object reference when the track has no election entry yet', () => {
    const tracked = trackedDetection({ label: 'car' }, 7);
    const [displayed] = applyStickyLabels([tracked], new Map());
    expect(displayed).toBe(tracked);
  });
});

describe('detectionTiers', () => {
  it('the hovered box promotes to T0 unconditionally — even a sub-scale box that would otherwise be T3', () => {
    const hovered = fullDetection({
      label: 'tiny',
      box: { x: 0, y: 0, width: UNDER_SUB_SCALE_FRACTION, height: UNDER_SUB_SCALE_FRACTION },
    });
    const tiers = detectionTiers([hovered], tierContext({ hoveredDetection: hovered }));
    expect(tiers.get(hovered)).toBe('T0');
  });

  it('the FOLLOW-locked track promotes to T0 unconditionally', () => {
    const locked = trackedDetection({ label: 'locked-target' }, 7);
    const tiers = detectionTiers([locked], tierContext({ lockedTrackId: 7 }));
    expect(tiers.get(locked)).toBe('T0');
  });

  it('lockedTrackId 0 (the wire "no lock" sentinel) never promotes a track to T0 by itself', () => {
    const tracked = trackedDetection({}, 7);
    const tiers = detectionTiers([tracked], tierContext({ lockedTrackId: 0 }));
    expect(tiers.get(tracked)).not.toBe('T0');
  });

  it('a box under SUB_SCALE_PX on both axes draws as T3, checked before the top-K budget is spent', () => {
    // confidence .99 would otherwise trivially win a top-K slot — sub-scale wins regardless.
    const tiny = fullDetection({
      label: 'tiny',
      confidence: 0.99,
      box: { x: 0, y: 0, width: UNDER_SUB_SCALE_FRACTION, height: UNDER_SUB_SCALE_FRACTION },
    });
    const tiers = detectionTiers([tiny], tierContext());
    expect(tiers.get(tiny)).toBe('T3');
  });

  it('sub-scale requires BOTH axes under the threshold — a thin-but-tall box stays size-eligible', () => {
    const thin = fullDetection({
      label: 'thin',
      box: { x: 0, y: 0, width: UNDER_SUB_SCALE_FRACTION, height: OVER_SUB_SCALE_FRACTION },
    });
    const tiers = detectionTiers([thin], tierContext());
    expect(tiers.get(thin)).not.toBe('T3');
  });

  it('a tracked, moving detection promotes to T1 regardless of its own confidence', () => {
    const mover = trackedDetection({ label: 'mover', confidence: 0.01 }, 9);
    const trails = new Map([[9, [{ x: 0, y: 0 }, { x: 0.5, y: 0.5 }]]]);
    const tiers = detectionTiers([mover], tierContext({ trails }));
    expect(tiers.get(mover)).toBe('T1');
  });

  it('a tracked detection under MOVING_DISPLACEMENT_THRESHOLD is not treated as moving — it competes on ranking alone', () => {
    const stillish = trackedDetection({ label: 'jitter', confidence: 0.01 }, 9);
    const trails = new Map([[9, [{ x: 0.5, y: 0.5 }, { x: 0.5 + MOVING_DISPLACEMENT_THRESHOLD / 2, y: 0.5 }]]]);
    const stronger = Array.from({ length: NOTABLE_TOP_K }, (_, i) =>
      fullDetection({ label: `s${i}`, confidence: 0.9 - i * 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } }),
    );
    const tiers = detectionTiers([stillish, ...stronger], tierContext({ trails }));
    // if displacement-under-threshold were wrongly treated as "moving" it would bypass ranking and
    // still land T1 regardless of its rock-bottom confidence; instead it competes on (area x
    // confidence) alone against `stronger`'s own NOTABLE_TOP_K detections and loses every slot.
    expect(tiers.get(stillish)).toBe('T2');
  });

  it('promotes the top NOTABLE_TOP_K non-moving detections by (area × confidence), demoting the rest to T2', () => {
    const detections = Array.from({ length: NOTABLE_TOP_K + 2 }, (_, i) =>
      fullDetection({
        label: `d${i}`,
        confidence: 0.9 - i * 0.05, // strictly decreasing -> unambiguous rank order
        box: { x: 0, y: 0, width: 0.2, height: 0.2 },
      }),
    );
    const tiers = detectionTiers(detections, tierContext());
    expect(detections.slice(0, NOTABLE_TOP_K).every((d) => tiers.get(d) === 'T1')).toBe(true);
    expect(detections.slice(NOTABLE_TOP_K).every((d) => tiers.get(d) === 'T2')).toBe(true);
  });

  it('a moving track claims a slot from the shared NOTABLE_TOP_K budget, not a separate one', () => {
    const mover = trackedDetection({ label: 'mover', confidence: 0.01 }, 9);
    const stationary = Array.from({ length: NOTABLE_TOP_K }, (_, i) =>
      fullDetection({ label: `s${i}`, confidence: 0.9 - i * 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } }),
    );
    const trails = new Map([[9, [{ x: 0, y: 0 }, { x: 0.5, y: 0.5 }]]]);
    const tiers = detectionTiers([mover, ...stationary], tierContext({ trails }));
    expect(tiers.get(mover)).toBe('T1');
    // the mover already spent one of the NOTABLE_TOP_K slots, so only the strongest
    // (NOTABLE_TOP_K - 1) stationary detections also make T1 — the weakest is demoted to T2.
    expect(stationary.slice(0, NOTABLE_TOP_K - 1).every((d) => tiers.get(d) === 'T1')).toBe(true);
    expect(tiers.get(stationary[NOTABLE_TOP_K - 1])).toBe('T2');
  });

  it('movement itself is never capped — a batch of movers alone can exceed NOTABLE_TOP_K', () => {
    const movers = Array.from({ length: NOTABLE_TOP_K + 2 }, (_, i) =>
      trackedDetection({ label: `m${i}`, box: { x: 0, y: 0, width: 0.2, height: 0.2 } }, i + 1),
    );
    const trails = new Map(movers.map((d) => [d.track!.id, [{ x: 0, y: 0 }, { x: 0.5, y: 0.5 }]]));
    const tiers = detectionTiers(movers, tierContext({ trails }));
    expect(movers.every((d) => tiers.get(d) === 'T1')).toBe(true);
  });

  it('everything left after T0/T3/T1 is T2', () => {
    const strong = Array.from({ length: NOTABLE_TOP_K }, (_, i) =>
      fullDetection({ label: `strong${i}`, confidence: 0.9 - i * 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } }),
    );
    const ambient = fullDetection({ label: 'ambient', confidence: 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } });
    const tiers = detectionTiers([...strong, ambient], tierContext());
    expect(strong.every((d) => tiers.get(d) === 'T1')).toBe(true);
    expect(tiers.get(ambient)).toBe('T2');
  });

  // --- Class hover promotion (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3, wave W5) -----------------

  it('a size-eligible detection whose label matches hoveredClass promotes to T1 regardless of confidence', () => {
    const matched = fullDetection({ label: 'person', confidence: 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } });
    const stronger = Array.from({ length: NOTABLE_TOP_K }, (_, i) =>
      fullDetection({ label: `s${i}`, confidence: 0.9 - i * 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } }),
    );
    const tiers = detectionTiers([matched, ...stronger], tierContext({ hoveredClass: 'person' }));
    expect(tiers.get(matched)).toBe('T1');
  });

  it('hoveredClass shares the NOTABLE_TOP_K budget with the ranked remainder, not a separate one', () => {
    const matched = fullDetection({ label: 'person', confidence: 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } });
    const stronger = Array.from({ length: NOTABLE_TOP_K }, (_, i) =>
      fullDetection({ label: `s${i}`, confidence: 0.9 - i * 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } }),
    );
    const tiers = detectionTiers([matched, ...stronger], tierContext({ hoveredClass: 'person' }));
    expect(stronger.slice(0, NOTABLE_TOP_K - 1).every((d) => tiers.get(d) === 'T1')).toBe(true);
    expect(tiers.get(stronger[NOTABLE_TOP_K - 1])).toBe('T2');
  });

  it('hoveredClass never promotes a sub-scale detection past T3 — a hover cannot make an object bigger', () => {
    const tiny = fullDetection({
      label: 'person',
      box: { x: 0, y: 0, width: UNDER_SUB_SCALE_FRACTION, height: UNDER_SUB_SCALE_FRACTION },
    });
    const tiers = detectionTiers([tiny], tierContext({ hoveredClass: 'person' }));
    expect(tiers.get(tiny)).toBe('T3');
  });

  it('hoveredClass never overrides an existing T0 (the FOLLOW lock/hovered box still win)', () => {
    const locked = trackedDetection({ label: 'person' }, 7);
    const tiers = detectionTiers([locked], tierContext({ lockedTrackId: 7, hoveredClass: 'person' }));
    expect(tiers.get(locked)).toBe('T0');
  });

  it('null hoveredClass (nothing hovered) never matches — a detection with no label collision stays ranked normally', () => {
    // Mirrors "everything left after T0/T3/T1 is T2" above — `strong` fills the NOTABLE_TOP_K
    // ranking budget so `ambient` falls out to T2 on its own (low area × confidence) merit, not
    // because a solo detection would otherwise win the budget by default.
    const strong = Array.from({ length: NOTABLE_TOP_K }, (_, i) =>
      fullDetection({ label: `strong${i}`, confidence: 0.9 - i * 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } }),
    );
    const ambient = fullDetection({ label: 'ambient', confidence: 0.01, box: { x: 0, y: 0, width: 0.2, height: 0.2 } });
    const tiers = detectionTiers([...strong, ambient], tierContext({ hoveredClass: null }));
    expect(tiers.get(ambient)).toBe('T2');
  });
});

// --- Forward-projection (docs/plans/done/CV-CLEAN-FEED-PLAN.md §7, wave W7) ------------------------------

describe('extrapolateDetections', () => {
  it('matches by track id and projects the center along the implied velocity', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [trackedDetection({ box: { x: 0.15, y: 0.15, width: 0.1, height: 0.1 } }, 5)],
    });
    const selected = result({
      capturedAt: '2026-07-22T00:00:01.000Z', // 1000ms later, center +0.1 in x -> 0.1/s, y unchanged
      detections: [trackedDetection({ box: { x: 0.25, y: 0.15, width: 0.1, height: 0.1 } }, 5)],
    });
    const targetMs = Date.parse(selected.capturedAt) + 500; // 500ms further

    const [projected] = extrapolateDetections(selected, previous, targetMs);
    expect(projected.box.x).toBeCloseTo(0.3, 10); // center 0.35 minus half-width
    expect(projected.box.y).toBeCloseTo(0.15, 10); // no y velocity -> unchanged
    expect(projected.box.width).toBe(0.1);
    expect(projected.box.height).toBe(0.1);
    expect(projected.track?.id).toBe(5); // identity survives projection
  });

  it('matches by track id even when centers are far apart — pass 1 is exact, not gated by distance', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [trackedDetection({ box: { x: 0, y: 0, width: 0.05, height: 0.05 } }, 3)],
    });
    const selected = result({
      capturedAt: '2026-07-22T00:00:01.000Z',
      detections: [trackedDetection({ box: { x: 0.9, y: 0.9, width: 0.05, height: 0.05 } }, 3)],
    });
    // Center jump of ~1.27 normalized units, far beyond EXTRAPOLATION_MATCH_GATE (0.15) — pass 1
    // (track id) still fires regardless, since the tracker itself already vouches for the identity.
    const [projected] = extrapolateDetections(selected, previous, Date.parse(selected.capturedAt) + 1);
    expect(projected.box.x).toBeCloseTo(0.9009, 6);
    expect(projected.box.y).toBeCloseTo(0.9009, 6);
  });

  it('same-label gate match works when one side is untracked', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [fullDetection({ box: { x: 0.15, y: 0.15, width: 0.1, height: 0.1 } })], // untracked
    });
    const selected = result({
      capturedAt: '2026-07-22T00:00:01.000Z',
      // Tracking just picked this object up this frame — selected carries a track, previous doesn't;
      // center distance 0.05 is inside EXTRAPOLATION_MATCH_GATE (0.15), same label ('car' default).
      // Center moves 0.2 -> 0.25 over 1s -> 0.05/s velocity.
      detections: [trackedDetection({ box: { x: 0.2, y: 0.15, width: 0.1, height: 0.1 } }, 9)],
    });
    const targetMs = Date.parse(selected.capturedAt) + 500;

    const [projected] = extrapolateDetections(selected, previous, targetMs);
    expect(projected.box.x).toBeCloseTo(0.225, 10); // center 0.275 (0.25 + 0.05/s * 0.5s) minus half-width
    expect(projected.track?.id).toBe(9);
  });

  it('outside the gate, a same-label pair does not match — the detection draws raw', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [fullDetection({ box: { x: 0, y: 0, width: 0.1, height: 0.1 } })],
    });
    // Center distance ~0.71, far beyond the 0.15 default gate.
    const selectedDetection = fullDetection({ box: { x: 0.5, y: 0.5, width: 0.1, height: 0.1 } });
    const selected = result({ capturedAt: '2026-07-22T00:00:01.000Z', detections: [selectedDetection] });

    const projected = extrapolateDetections(selected, previous, Date.parse(selected.capturedAt) + 500);
    expect(projected[0]).toBe(selectedDetection); // same reference — untouched
  });

  it('never gate-matches two detections that both carry a track id — the tracker already said "different objects"', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [trackedDetection({ box: { x: 0.15, y: 0.15, width: 0.1, height: 0.1 } }, 100)],
    });
    // Different track id, well within the gate (distance 0.05) — must still not match.
    const selectedDetection = trackedDetection({ box: { x: 0.2, y: 0.15, width: 0.1, height: 0.1 } }, 200);
    const selected = result({ capturedAt: '2026-07-22T00:00:01.000Z', detections: [selectedDetection] });

    const projected = extrapolateDetections(selected, previous, Date.parse(selected.capturedAt) + 500);
    expect(projected[0]).toBe(selectedDetection);
  });

  it('caps the projection at maxExtrapolationMs past capturedAt and freezes there, not beyond', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [trackedDetection({ box: { x: 0.15, y: 0.15, width: 0.1, height: 0.1 } }, 1)],
    });
    const selected = result({
      capturedAt: '2026-07-22T00:00:01.000Z', // 1000ms delta, velocity 0.0001/ms in x
      detections: [trackedDetection({ box: { x: 0.25, y: 0.15, width: 0.1, height: 0.1 } }, 1)],
    });
    const selectedCapturedAtMs = Date.parse(selected.capturedAt);

    const atCap = extrapolateDetections(selected, previous, selectedCapturedAtMs + EXTRAPOLATION_MAX_MS);
    const wayBeyondCap = extrapolateDetections(selected, previous, selectedCapturedAtMs + 10 * EXTRAPOLATION_MAX_MS);

    expect(atCap[0].box.x).toBeCloseTo(0.33, 10); // (0.3 center + 0.0001/ms * 800ms) - 0.05 half-width
    expect(wayBeyondCap[0].box.x).toBeCloseTo(atCap[0].box.x, 10); // frozen at exactly the cap, no further
  });

  it('no predecessor draws raw, unchanged', () => {
    const selected = result({ detections: [fullDetection()] });
    expect(extrapolateDetections(selected, undefined, Date.parse(selected.capturedAt) + 500)).toBe(
      selected.detections,
    );
  });

  it('a degenerate/duplicate dt (previous not strictly before selected) draws raw', () => {
    const selected = result({ capturedAt: '2026-07-22T00:00:01.000Z', detections: [fullDetection()] });
    const samInstant = result({ capturedAt: '2026-07-22T00:00:01.000Z' });
    expect(extrapolateDetections(selected, samInstant, Date.parse(selected.capturedAt) + 500)).toBe(
      selected.detections,
    );

    const previousAfterSelected = result({ capturedAt: '2026-07-22T00:00:02.000Z' });
    expect(extrapolateDetections(selected, previousAfterSelected, Date.parse(selected.capturedAt) + 500)).toBe(
      selected.detections,
    );
  });

  it('a target at or before the capture instant draws raw — nothing to project forward to yet', () => {
    const previous = result({ capturedAt: '2026-07-22T00:00:00.000Z', detections: [trackedDetection({}, 1)] });
    const selected = result({ capturedAt: '2026-07-22T00:00:01.000Z', detections: [trackedDetection({}, 1)] });
    expect(extrapolateDetections(selected, previous, Date.parse(selected.capturedAt))).toBe(selected.detections);
    expect(extrapolateDetections(selected, previous, Date.parse(selected.capturedAt) - 10)).toBe(selected.detections);
  });

  it('clamps a projected center back into [0,1]', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [trackedDetection({ box: { x: 0.8, y: 0.4, width: 0.1, height: 0.1 } }, 1)],
    });
    const selected = result({
      capturedAt: '2026-07-22T00:00:01.000Z',
      // Center moves 0.85 -> 0.98 over 1s (0.13/s); projecting the full 800ms horizon pushes the
      // resulting box origin past 1.
      detections: [trackedDetection({ box: { x: 0.93, y: 0.4, width: 0.1, height: 0.1 } }, 1)],
    });
    const [projected] = extrapolateDetections(
      selected,
      previous,
      Date.parse(selected.capturedAt) + EXTRAPOLATION_MAX_MS,
    );
    expect(projected.box.x).toBe(1);
  });

  it('an unmatched detection newly present in `selected` passes through raw alongside a matched one', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [trackedDetection({ box: { x: 0.15, y: 0.15, width: 0.1, height: 0.1 } }, 1)],
    });
    const matched = trackedDetection({ box: { x: 0.25, y: 0.15, width: 0.1, height: 0.1 } }, 1);
    const newlyAppeared = fullDetection({ label: 'bicycle', box: { x: 0.6, y: 0.6, width: 0.1, height: 0.1 } });
    const selected = result({ capturedAt: '2026-07-22T00:00:01.000Z', detections: [matched, newlyAppeared] });

    const projected = extrapolateDetections(selected, previous, Date.parse(selected.capturedAt) + 500);
    expect(projected[1]).toBe(newlyAppeared); // raw, same reference — nothing in `previous` matches it
    expect(projected[0]).not.toBe(matched); // the matched detection was projected into a new object
  });

  it('honors a custom matchGate/maxExtrapolationMs instead of the exported defaults', () => {
    const previous = result({
      capturedAt: '2026-07-22T00:00:00.000Z',
      detections: [fullDetection({ box: { x: 0.15, y: 0.15, width: 0.1, height: 0.1 } })],
    });
    // Center distance 0.05 — inside the default gate, outside a tightened one.
    const selectedDetection = fullDetection({ box: { x: 0.2, y: 0.15, width: 0.1, height: 0.1 } });
    const selected = result({ capturedAt: '2026-07-22T00:00:01.000Z', detections: [selectedDetection] });
    const targetMs = Date.parse(selected.capturedAt) + 500;

    expect(extrapolateDetections(selected, previous, targetMs, EXTRAPOLATION_MAX_MS, 0.01)[0]).toBe(
      selectedDetection,
    );

    // A 1ms max horizon still projects (default gate matches), but freezes almost immediately past capturedAt.
    const [tinyHorizon] = extrapolateDetections(selected, previous, targetMs, 1, EXTRAPOLATION_MATCH_GATE);
    expect(tinyHorizon.box.x).toBeCloseTo(0.20005, 8); // rawX (0.2) + velocity(0.00005/ms) * 1ms
    expect(tinyHorizon.box.x).not.toBe(selectedDetection.box.x);
  });
});

describe('findPredecessorResult', () => {
  it('returns the newest batch strictly older than the selected one', () => {
    const newest = result({ capturedAt: '2026-07-22T00:00:02.000Z' });
    const selected = result({ capturedAt: '2026-07-22T00:00:01.000Z' });
    const oldest = result({ capturedAt: '2026-07-22T00:00:00.000Z' });
    expect(findPredecessorResult([newest, selected, oldest], selected)).toBe(oldest);
  });

  it('returns undefined when selected is already the oldest entry', () => {
    const selected = result({ capturedAt: '2026-07-22T00:00:00.000Z' });
    expect(findPredecessorResult([selected], selected)).toBeUndefined();
  });

  it('returns undefined when selected is not present in results at all', () => {
    const elsewhere = result({ capturedAt: '2026-07-22T00:00:05.000Z' });
    const results = [result({ capturedAt: '2026-07-22T00:00:00.000Z' })];
    expect(findPredecessorResult(results, elsewhere)).toBeUndefined();
  });

  it('skips a duplicate-timestamp neighbor (not strictly older) and finds the next one back', () => {
    const selected = result({ capturedAt: '2026-07-22T00:00:01.000Z' });
    const duplicate = result({ capturedAt: '2026-07-22T00:00:01.000Z' }); // same instant, not "older"
    const actualPredecessor = result({ capturedAt: '2026-07-22T00:00:00.000Z' });
    expect(findPredecessorResult([selected, duplicate, actualPredecessor], selected)).toBe(actualPredecessor);
  });
});

describe('tierAlphaPercent', () => {
  it('T0 always draws at full alpha, locked or not', () => {
    expect(tierAlphaPercent('T0', false)).toBe(100);
    expect(tierAlphaPercent('T0', true)).toBe(100);
  });

  it('T1 is full alpha unlocked, dimmed to LOCK_DIM_ALPHA_PERCENT once a lock is active', () => {
    expect(tierAlphaPercent('T1', false)).toBe(100);
    expect(tierAlphaPercent('T1', true)).toBe(LOCK_DIM_ALPHA_PERCENT);
  });

  it('T2 carries its own thinner base alpha, further dimmed once locked', () => {
    expect(tierAlphaPercent('T2', false)).toBe(T2_ALPHA_PERCENT);
    expect(tierAlphaPercent('T2', true)).toBe(Math.round((T2_ALPHA_PERCENT * LOCK_DIM_ALPHA_PERCENT) / 100));
  });

  it('T3 mirrors T1 — full base alpha, dimmed once locked', () => {
    expect(tierAlphaPercent('T3', false)).toBe(100);
    expect(tierAlphaPercent('T3', true)).toBe(LOCK_DIM_ALPHA_PERCENT);
  });
});

// --- Class-bucket box colors (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.2/D5, wave W4) ---------------

describe('classBucket', () => {
  it('recognizes person-like labels', () => {
    expect(classBucket('person')).toBe('person');
    expect(classBucket('pedestrian')).toBe('person');
  });

  it('recognizes vehicle-like labels', () => {
    expect(classBucket('car')).toBe('vehicle');
    expect(classBucket('bicycle')).toBe('vehicle');
  });

  it('defaults everything else to "other" rather than fabricating a bucket', () => {
    // 'tank' is deliberately a vehicle-bucket keyword in this app's own list (a drone/military
    // context), so this test picks labels genuinely absent from either keyword list instead.
    expect(classBucket('building')).toBe('other');
    expect(classBucket('backpack')).toBe('other');
  });

  it('matches case-insensitively', () => {
    expect(classBucket('PERSON')).toBe('person');
  });

  it('strips a composite-mode "model:label" prefix before matching', () => {
    expect(classBucket('orion12l:person')).toBe('person');
    expect(classBucket('yolo11n:car')).toBe('vehicle');
  });
});

describe('classBucketHue', () => {
  it('is a fixed hue per bucket — stable across calls, unlike the old per-track hash', () => {
    expect(classBucketHue('person')).toBe(classBucketHue('person'));
    expect(classBucketHue('vehicle')).toBe(classBucketHue('vehicle'));
  });

  it('all three buckets get distinct hues', () => {
    const hues = (['person', 'vehicle', 'other'] as const).map((bucket) => classBucketHue(bucket));
    expect(new Set(hues).size).toBe(3);
  });

  it('never collides with the T0/default accent color', () => {
    const hues = (['person', 'vehicle', 'other'] as const).map((bucket) => classBucketHue(bucket));
    expect(hues).not.toContain(DEFAULT_BOX_COLOR);
  });

  it('embeds the alpha in the hsl() string at partial alpha, mirroring modelHue', () => {
    const opaque = classBucketHue('person');
    const translucent = classBucketHue('person', 85);
    expect(translucent).toBe(`${opaque.slice(0, -1)} / 85%)`);
  });
});

describe('tierBoxColor', () => {
  it('uses the class-bucket hue in single-model mode', () => {
    const detection = fullDetection({ label: 'person' });
    expect(tierBoxColor(detection, false)).toBe(classBucketHue('person'));
  });

  it('uses the per-model hue in composite mode, ignoring class entirely', () => {
    const detection = fullDetection({ label: 'orion12l:person' });
    expect(tierBoxColor(detection, true)).toBe(modelHue('orion12l'));
  });
});

describe('formatTierLabel', () => {
  it('T0 keeps the full label — track id, class, and confidence — byte-identical to formatDetectionLabel', () => {
    const tracked = trackedDetection({}, 7);
    expect(formatTierLabel(tracked, 'T0')).toBe(formatDetectionLabel(tracked));
    expect(formatTierLabel(tracked, 'T0')).toBe('#7 car 82%');
  });

  it('T1 drops the confidence percent (research §3.2/D4 — confidence stays on T0 and the hover tooltip only)', () => {
    const tracked = trackedDetection({}, 7);
    expect(formatTierLabel(tracked, 'T1')).toBe('#7 car');
  });

  it('T1 for an untracked detection is just the bare class name', () => {
    expect(formatTierLabel(fullDetection(), 'T1')).toBe('car');
  });
});

// --- Label collision-yield (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.3, wave W4) --------------------

describe('placeLabels', () => {
  function candidate(key: string, box: { x: number; y: number; width: number; height: number }): LabelCandidate {
    return { key, box, labelWidth: 40, labelHeight: 14 };
  }

  it('places a single candidate above its box by default', () => {
    const placed = placeLabels([candidate('a', { x: 0, y: 100, width: 20, height: 20 })], new Map());
    expect(placed).toEqual([{ key: 'a', rect: { x: 0, y: 86, width: 40, height: 14 }, slot: 'above' }]);
  });

  it('falls through above -> below -> inside-top as each slot collides', () => {
    const overlappingBox = { x: 0, y: 100, width: 20, height: 20 };
    const candidates = [candidate('a', overlappingBox), candidate('b', overlappingBox), candidate('c', overlappingBox)];
    const placed = placeLabels(candidates, new Map());
    expect(placed.map((p) => p.slot)).toEqual(['above', 'below', 'inside-top']);
  });

  it('a candidate that collides in all three slots paints no label at all — the box itself still draws separately', () => {
    const overlappingBox = { x: 0, y: 100, width: 20, height: 20 };
    const candidates = Array.from({ length: 4 }, (_, i) => candidate(`k${i}`, overlappingBox));
    const placed = placeLabels(candidates, new Map());
    expect(placed.length).toBe(3); // above/below/inside-top all consumed; the 4th has nowhere left
  });

  it('prefers a candidate\'s own previous-frame slot (hysteresis) when it is still collision-free', () => {
    const c = candidate('a', { x: 0, y: 100, width: 20, height: 20 });
    const placed = placeLabels([c], new Map([['a', 'below']]));
    expect(placed[0].slot).toBe('below');
  });

  it('falls back off a stale hysteresis slot when it now collides', () => {
    const overlappingBox = { x: 0, y: 100, width: 20, height: 20 };
    const first = candidate('a', overlappingBox);
    const second = candidate('b', overlappingBox);
    // 'b' preferred 'above' last frame, but 'a' (placed first this frame) already holds it.
    const placed = placeLabels([first, second], new Map([['b', 'above']]));
    expect(placed[0]).toMatchObject({ key: 'a', slot: 'above' });
    expect(placed[1]).toMatchObject({ key: 'b', slot: 'below' });
  });

  it('caps total painted labels at MAX_PAINTED_LABELS, even with room for more', () => {
    const candidates = Array.from({ length: MAX_PAINTED_LABELS + 5 }, (_, i) =>
      candidate(`k${i}`, { x: i * 100, y: 100, width: 20, height: 20 }), // spaced far apart, never collide
    );
    const placed = placeLabels(candidates, new Map());
    expect(placed.length).toBe(MAX_PAINTED_LABELS);
  });
});
