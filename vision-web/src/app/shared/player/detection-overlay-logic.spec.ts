import { describe, expect, it } from 'vitest';
import {
  DEFAULT_BOX_COLOR,
  DEFAULT_MODEL_KEY,
  TRAIL_WINDOW_MS,
  boxesModeCycle,
  canvasBackingSize,
  cycleBoxesMode,
  defaultBoxesMode,
  detectionModelKey,
  distinctModelKeys,
  formatDetectionLabel,
  modelHue,
  overlaySyncLatencySeconds,
  resolveBurnedIn,
  selectDetectionResult,
  shouldDrawOverlay,
  trackHue,
  trackTrails,
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
});

describe('shouldDrawOverlay', () => {
  it('draws only in overlay mode with a result available', () => {
    expect(shouldDrawOverlay('overlay', true)).toBe(true);
  });

  it('never draws without a result, even in overlay mode', () => {
    expect(shouldDrawOverlay('overlay', false)).toBe(false);
  });

  it('never draws in burned or off mode', () => {
    expect(shouldDrawOverlay('burned', true)).toBe(false);
    expect(shouldDrawOverlay('off', true)).toBe(false);
  });
});

// --- Burn-in awareness (docs/plans/active/MEDIA-SOT-PLAN.md §5.4/§8 wave M8) ---------------------------------
// D1's "defaults reproduce today's behaviour exactly": every deployment this app talks to today
// never sends `burnedIn` at all (a pre-M5 backend), so `undefined` is by far the most load-bearing
// case in this whole group — it must behave byte-identically to the pre-wave M8 code.

describe('resolveBurnedIn', () => {
  it('treats an absent field as burned-in (dev-parity default, D1)', () => {
    expect(resolveBurnedIn(undefined)).toBe(true);
  });

  it('trusts an explicit true', () => {
    expect(resolveBurnedIn(true)).toBe(true);
  });

  it('only an explicit false means the picture is clean', () => {
    expect(resolveBurnedIn(false)).toBe(false);
  });
});

describe('boxesModeCycle', () => {
  it('offers the full three-mode cycle when burned-in (including undefined, the pre-M5 default)', () => {
    expect(boxesModeCycle(undefined)).toEqual(['overlay', 'burned', 'off']);
    expect(boxesModeCycle(true)).toEqual(['overlay', 'burned', 'off']);
  });

  it('drops burned from the cycle once a stream is confirmed burn-in-free', () => {
    expect(boxesModeCycle(false)).toEqual(['overlay', 'off']);
  });
});

describe('defaultBoxesMode', () => {
  it('defaults to burned when burned-in (including undefined — dev parity, D1)', () => {
    expect(defaultBoxesMode(undefined)).toBe('burned');
    expect(defaultBoxesMode(true)).toBe('burned');
  });

  it('defaults to overlay once a stream is confirmed burn-in-free — burned would show nothing', () => {
    expect(defaultBoxesMode(false)).toBe('overlay');
  });
});

describe('cycleBoxesMode', () => {
  it('cycles overlay -> burned -> off -> overlay when burnedIn is omitted (byte-identical to before wave M8)', () => {
    expect(cycleBoxesMode('overlay')).toBe('burned');
    expect(cycleBoxesMode('burned')).toBe('off');
    expect(cycleBoxesMode('off')).toBe('overlay');
  });

  it('cycles the same three-step loop for an explicitly burned-in stream', () => {
    expect(cycleBoxesMode('overlay', true)).toBe('burned');
    expect(cycleBoxesMode('burned', true)).toBe('off');
    expect(cycleBoxesMode('off', true)).toBe('overlay');
  });

  it('skips burned for a confirmed burn-in-free stream — overlay <-> off only', () => {
    expect(cycleBoxesMode('overlay', false)).toBe('off');
    expect(cycleBoxesMode('off', false)).toBe('overlay');
  });

  it('a stale burned reading (burnedIn just resolved false out from under it) restarts from the front of the cycle', () => {
    expect(cycleBoxesMode('burned', false)).toBe('overlay');
  });
});

// --- WHEP-aware overlay sync latency (docs/plans/active/MEDIA-SOT-PLAN.md §6/§8 wave M8) ---------------------

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

// --- HiDPI canvas backing store (docs/plans/active/MEDIA-SOT-PLAN.md §8 wave M8) -------------------------------

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
      track: { id: 7, state: 'CONFIRMED', source: 'TRACKER', velocityX: 0, velocityY: 0 },
    });
    expect(formatDetectionLabel(tracked)).toBe('#7 car 82%');
  });
});

describe('trackHue', () => {
  it('is stable — the same track id always hashes to the same color', () => {
    expect(trackHue(7)).toBe(trackHue(7));
  });

  it('is distinct across a sample of different track ids', () => {
    const ids = [1, 2, 3, 7, 42];
    const colors = ids.map((id) => trackHue(id));
    expect(new Set(colors).size).toBe(colors.length);
  });

  it('a track id never collides with modelHue\'s own default box color', () => {
    expect(trackHue(1)).not.toBe(DEFAULT_BOX_COLOR);
  });

  it('embeds the alpha in the hsl() string at partial alpha, mirroring modelHue', () => {
    const opaque = trackHue(7);
    const translucent = trackHue(7, 85);
    expect(translucent).toBe(`${opaque.slice(0, -1)} / 85%)`);
  });
});

describe('trackTrails', () => {
  function trackedResult(capturedAt: string, points: readonly { trackId: number; cx: number; cy: number }[]): DetectionResult {
    return result({
      capturedAt,
      detections: points.map(({ trackId, cx, cy }) =>
        fullDetection({
          box: { x: cx - 0.05, y: cy - 0.05, width: 0.1, height: 0.1 },
          track: { id: trackId, state: 'CONFIRMED', source: 'TRACKER', velocityX: 0, velocityY: 0 },
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
