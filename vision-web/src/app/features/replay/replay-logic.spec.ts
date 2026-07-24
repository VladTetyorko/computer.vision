import { describe, expect, it } from 'vitest';
import type { DetectionResult, TelemetrySample } from '../../core/api/models';
import {
  DETECTION_MATCH_TOLERANCE_MS,
  advancePlaybackClock,
  bucketDetections,
  clampToRange,
  isDetectionNear,
  nearestDetectionResult,
  nearestSample,
  trailPrefix,
} from './replay-logic';

function sample(partial: Partial<TelemetrySample> = {}): TelemetrySample {
  return { deviceId: 'dev-0', at: '2026-07-22T00:00:00Z', ...partial };
}

function result(partial: Partial<DetectionResult> = {}): DetectionResult {
  return { streamId: 's-1', frameSequence: 0, capturedAt: '2026-07-22T00:00:00Z', inferenceMillis: 5, detections: [], ...partial };
}

const T0 = Date.parse('2026-07-22T00:00:00Z');
const sec = (n: number) => T0 + n * 1000;
const iso = (n: number) => new Date(sec(n)).toISOString();

describe('nearestSample', () => {
  it('returns undefined for an empty list', () => {
    expect(nearestSample([], T0)).toBeUndefined();
  });

  it('returns the only sample regardless of time', () => {
    const only = sample({ at: iso(5) });
    expect(nearestSample([only], sec(999))).toBe(only);
  });

  it('picks an exact match', () => {
    const samples = [sample({ at: iso(0) }), sample({ at: iso(5) }), sample({ at: iso(10) })];
    expect(nearestSample(samples, sec(5))).toBe(samples[1]);
  });

  it('picks the closer neighbor between two samples', () => {
    const samples = [sample({ at: iso(0) }), sample({ at: iso(10) })];
    expect(nearestSample(samples, sec(3))).toBe(samples[0]); // 3s away vs 7s away
    expect(nearestSample(samples, sec(8))).toBe(samples[1]); // 8s away vs 2s away
  });

  it('breaks an exact tie toward the earlier sample', () => {
    const samples = [sample({ at: iso(0) }), sample({ at: iso(10) })];
    expect(nearestSample(samples, sec(5))).toBe(samples[0]);
  });

  it('clamps to the first sample before the range', () => {
    const samples = [sample({ at: iso(5) }), sample({ at: iso(10) })];
    expect(nearestSample(samples, sec(0))).toBe(samples[0]);
  });

  it('clamps to the last sample after the range', () => {
    const samples = [sample({ at: iso(5) }), sample({ at: iso(10) })];
    expect(nearestSample(samples, sec(999))).toBe(samples[1]);
  });
});

describe('trailPrefix', () => {
  it('keeps only positioned samples at or before the scrub time', () => {
    const samples = [
      sample({ at: iso(0), latitude: 1, longitude: 1 }),
      sample({ at: iso(5), latitude: 2, longitude: 2 }),
      sample({ at: iso(10), latitude: 3, longitude: 3 }),
    ];
    expect(trailPrefix(samples, sec(5))).toEqual([
      { latitude: 1, longitude: 1, altitudeMeters: undefined },
      { latitude: 2, longitude: 2, altitudeMeters: undefined },
    ]);
  });

  it('is empty before the first sample', () => {
    const samples = [sample({ at: iso(5), latitude: 1, longitude: 1 })];
    expect(trailPrefix(samples, sec(0))).toEqual([]);
  });

  it('includes every positioned sample once the scrub time is past the end', () => {
    const samples = [
      sample({ at: iso(0), latitude: 1, longitude: 1 }),
      sample({ at: iso(5), latitude: 2, longitude: 2 }),
    ];
    expect(trailPrefix(samples, sec(999))).toHaveLength(2);
  });

  it('skips samples without a position, matching deriveTrail', () => {
    const samples = [
      sample({ at: iso(0), latitude: 1, longitude: 1 }),
      sample({ at: iso(2), batteryPercent: 80 }), // no fix this tick
      sample({ at: iso(4), latitude: 2, longitude: 2 }),
    ];
    expect(trailPrefix(samples, sec(4))).toEqual([
      { latitude: 1, longitude: 1, altitudeMeters: undefined },
      { latitude: 2, longitude: 2, altitudeMeters: undefined },
    ]);
  });
});

describe('nearestDetectionResult', () => {
  it('returns undefined for no detections at all', () => {
    expect(nearestDetectionResult([], T0)).toBeUndefined();
  });

  it('picks the nearest result, including one with zero detections', () => {
    const empty = result({ capturedAt: iso(0), detections: [] });
    const positive = result({ capturedAt: iso(10), detections: [{ label: 'person', confidence: 0.9, box: { x: 0, y: 0, width: 1, height: 1 }, modelId: 'm', modelVersion: '1' }] });
    expect(nearestDetectionResult([empty, positive], sec(1))).toBe(empty);
    expect(nearestDetectionResult([empty, positive], sec(9))).toBe(positive);
  });
});

describe('isDetectionNear', () => {
  it('is false with no result', () => {
    expect(isDetectionNear(undefined, T0)).toBe(false);
  });

  it('is true within the tolerance window', () => {
    const r = result({ capturedAt: iso(10) });
    expect(isDetectionNear(r, sec(10) + DETECTION_MATCH_TOLERANCE_MS)).toBe(true);
  });

  it('is false just outside the tolerance window', () => {
    const r = result({ capturedAt: iso(10) });
    expect(isDetectionNear(r, sec(10) + DETECTION_MATCH_TOLERANCE_MS + 1)).toBe(false);
  });
});

describe('bucketDetections', () => {
  it('is empty when the range is degenerate', () => {
    expect(bucketDetections([result()], T0, T0)).toEqual([]);
    expect(bucketDetections([result()], T0, T0 + 1000, 0)).toEqual([]);
  });

  it('ignores results with no detections', () => {
    const empty = result({ capturedAt: iso(1), detections: [] });
    expect(bucketDetections([empty], T0, sec(10))).toEqual([]);
  });

  const box = { x: 0, y: 0, width: 1, height: 1 };
  const detected = (capturedAt: string) => result({
    capturedAt,
    detections: [{ label: 'person', confidence: 0.8, box, modelId: 'm', modelVersion: '1' }],
  });

  it('produces one bucket per positive result when buckets are wide relative to spacing', () => {
    const results = [detected(iso(0)), detected(iso(50)), detected(iso(90))];
    const buckets = bucketDetections(results, T0, sec(100), 10);
    expect(buckets.map((b) => b.count)).toEqual([1, 1, 1]);
  });

  it('merges close-together results into one bucket, keeping the earliest atMs and summing count', () => {
    const results = [detected(iso(0)), detected(iso(1))];
    const buckets = bucketDetections(results, T0, sec(100), 10); // each bucket spans 10s here
    expect(buckets).toEqual([{ atMs: sec(0), count: 2 }]);
  });

  it('sorts buckets ascending by time regardless of input order', () => {
    const results = [detected(iso(90)), detected(iso(0))];
    const buckets = bucketDetections(results, T0, sec(100), 10);
    expect(buckets.map((b) => b.atMs)).toEqual([sec(0), sec(90)]);
  });

  it('clamps an out-of-range capturedAt into the first/last bucket rather than dropping it', () => {
    const results = [detected(iso(-50)), detected(iso(500))];
    const buckets = bucketDetections(results, T0, sec(100), 10);
    expect(buckets).toHaveLength(2);
    expect(buckets[0].count).toBe(1);
    expect(buckets[1].count).toBe(1);
  });
});

describe('clampToRange', () => {
  it('clamps into [fromMs, toMs]', () => {
    expect(clampToRange(sec(-5), T0, sec(10))).toBe(T0);
    expect(clampToRange(sec(15), T0, sec(10))).toBe(sec(10));
    expect(clampToRange(sec(5), T0, sec(10))).toBe(sec(5));
  });

  it('collapses to fromMs for a degenerate (zero-length) range', () => {
    expect(clampToRange(sec(5), T0, T0)).toBe(T0);
  });
});

describe('advancePlaybackClock', () => {
  it('advances by deltaRealMs * speed', () => {
    const tick = advancePlaybackClock(T0, 1000, 4, T0, sec(100));
    expect(tick).toEqual({ atMs: sec(4), playing: true });
  });

  it('reflects a speed change between ticks — the same deltaRealMs advances further at a higher speed', () => {
    const at1x = advancePlaybackClock(T0, 1000, 1, T0, sec(100));
    const at16x = advancePlaybackClock(T0, 1000, 16, T0, sec(100));
    expect(at16x.atMs - T0).toBe((at1x.atMs - T0) * 16);
  });

  it('clamps to toMs and stops playback at the end of range', () => {
    const tick = advancePlaybackClock(sec(98), 5000, 1, T0, sec(100));
    expect(tick).toEqual({ atMs: sec(100), playing: false });
  });

  it('keeps playing while strictly before toMs', () => {
    const tick = advancePlaybackClock(sec(98), 1000, 1, T0, sec(100));
    expect(tick).toEqual({ atMs: sec(99), playing: true });
  });

  it('treats a non-positive deltaRealMs as a no-op advance (e.g. the first frame with no prior timestamp)', () => {
    const tick = advancePlaybackClock(sec(5), 0, 4, T0, sec(100));
    expect(tick).toEqual({ atMs: sec(5), playing: true });
    const negative = advancePlaybackClock(sec(5), -50, 4, T0, sec(100));
    expect(negative.atMs).toBe(sec(5));
  });

  it('is already stopped when starting exactly at toMs', () => {
    const tick = advancePlaybackClock(sec(100), 1000, 1, T0, sec(100));
    expect(tick).toEqual({ atMs: sec(100), playing: false });
  });
});
