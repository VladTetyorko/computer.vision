import { describe, expect, it } from 'vitest';
import {
  DEFAULT_BOX_COLOR,
  DEFAULT_MODEL_KEY,
  detectionModelKey,
  distinctModelKeys,
  modelHue,
  selectDetectionResult,
  shouldDrawOverlay,
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
