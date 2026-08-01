import { describe, expect, it } from 'vitest';
import type { DetectionResult, TelemetrySample } from '../../core/api/models';
import {
  DETECTION_MATCH_TOLERANCE_MS,
  advancePlaybackClock,
  buildClipDownloadUrl,
  bucketDetections,
  capDetectionBuckets,
  clampToRange,
  isDetectionNear,
  nearestDetectionResult,
  nearestSample,
  parseDeepLinkOffsetMs,
  selectedClipWindow,
  shouldSeekVideo,
  trailPrefix,
  videoOffsetSeconds,
  videoTimeToAtMs,
  wholeFlightClipWindow,
  type DetectionDensityBucket,
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

describe('capDetectionBuckets', () => {
  function bucketList(count: number): DetectionDensityBucket[] {
    return Array.from({ length: count }, (_, i) => ({ atMs: sec(i), count: 1 }));
  }

  it('passes an under-cap list through unchanged, totalCount matching its own length', () => {
    const buckets = bucketList(5);
    expect(capDetectionBuckets(buckets, 200)).toEqual({ buckets, totalCount: 5 });
  });

  it('is a no-op at exactly the cap', () => {
    const buckets = bucketList(200);
    const capped = capDetectionBuckets(buckets, 200);
    expect(capped.buckets).toEqual(buckets);
    expect(capped.totalCount).toBe(200);
  });

  it('keeps only the latest `cap` buckets (the tail) when over cap, and reports the pre-cap total', () => {
    const buckets = bucketList(250);
    const capped = capDetectionBuckets(buckets, 200);
    expect(capped.totalCount).toBe(250);
    expect(capped.buckets).toHaveLength(200);
    expect(capped.buckets[0]).toBe(buckets[50]); // the tail starts at index 250-200=50
    expect(capped.buckets[capped.buckets.length - 1]).toBe(buckets[249]);
  });

  it('defaults to DETECTION_STRIP_CAP (200) when no cap is given', () => {
    const buckets = bucketList(201);
    const capped = capDetectionBuckets(buckets);
    expect(capped.buckets).toHaveLength(200);
    expect(capped.totalCount).toBe(201);
  });

  it('keeps the kept slice ascending (it is the tail of an already-ascending list)', () => {
    const buckets = bucketList(210);
    const capped = capDetectionBuckets(buckets, 200);
    const atMsValues = capped.buckets.map((b) => b.atMs);
    expect(atMsValues).toEqual([...atMsValues].sort((a, b) => a - b));
  });

  it('handles an empty list', () => {
    expect(capDetectionBuckets([], 200)).toEqual({ buckets: [], totalCount: 0 });
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

describe('videoOffsetSeconds (docs/OPS-CORE-PLAN.md §R, R-c — the `<video>` DOM sync; also the exact `atSeconds` conversion `ReplayFacade.addToDataset()` reuses, docs/CV-TRAINING-V2-PLAN.md §8)', () => {
  it('converts a scrub position into the video\'s own currentTime, seconds', () => {
    expect(videoOffsetSeconds(sec(30), T0)).toBe(30);
  });

  it('never goes negative — a scrub position before the recording\'s own start clamps to 0', () => {
    expect(videoOffsetSeconds(T0 - 5000, T0)).toBe(0);
  });

  it('carries sub-second precision — the new POST /api/usages/{usageId}/samples endpoint accepts a fractional atSeconds (docs/CV-TRAINING-V2-PLAN.md §5)', () => {
    expect(videoOffsetSeconds(T0 + 412_500, T0)).toBe(412.5);
  });
});

describe('videoTimeToAtMs (docs/OPS-CORE-PLAN.md §R, R-c)', () => {
  it('converts the video\'s own currentTime back into an absolute atMs', () => {
    expect(videoTimeToAtMs(30, T0, T0, sec(100))).toBe(sec(30));
  });

  it('clamps into the replay window', () => {
    expect(videoTimeToAtMs(-10, T0, T0, sec(100))).toBe(T0);
    expect(videoTimeToAtMs(200, T0, T0, sec(100))).toBe(sec(100));
  });
});

describe('shouldSeekVideo (docs/OPS-CORE-PLAN.md §R, R-c — the guarded-effect threshold)', () => {
  it('is false for drift within the threshold (ordinary 1x playback)', () => {
    expect(shouldSeekVideo(30, 30.1)).toBe(false);
  });

  it('is true once drift exceeds the threshold (a scrub/jump)', () => {
    expect(shouldSeekVideo(30, 45)).toBe(true);
  });

  it('respects a custom threshold', () => {
    expect(shouldSeekVideo(30, 30.4, 0.5)).toBe(false);
    expect(shouldSeekVideo(30, 30.6, 0.5)).toBe(true);
  });
});

describe('wholeFlightClipWindow (docs/OPS-CORE-PLAN.md §R, R-c)', () => {
  it('spans the whole replay window, anchored off the recording\'s own start', () => {
    expect(wholeFlightClipWindow(T0, T0, sec(120))).toEqual({ startOffsetMs: 0, durationMs: 120_000 });
  });

  it('offsets when the recording started before the replay window\'s own from', () => {
    expect(wholeFlightClipWindow(T0 - 5000, T0, sec(120))).toEqual({ startOffsetMs: 5000, durationMs: 120_000 });
  });
});

describe('selectedClipWindow (docs/OPS-CORE-PLAN.md §R, R-c)', () => {
  it('uses the current selection when both marks are set and well-ordered', () => {
    expect(selectedClipWindow(T0, sec(10), sec(40), T0, sec(120))).toEqual({ startOffsetMs: 10_000, durationMs: 30_000 });
  });

  it('falls back to the whole flight with no selection', () => {
    expect(selectedClipWindow(T0, undefined, undefined, T0, sec(120))).toEqual({ startOffsetMs: 0, durationMs: 120_000 });
  });

  it('falls back to the whole flight when only one mark is set', () => {
    expect(selectedClipWindow(T0, sec(10), undefined, T0, sec(120))).toEqual({ startOffsetMs: 0, durationMs: 120_000 });
  });

  it('falls back to the whole flight when the marks are inverted', () => {
    expect(selectedClipWindow(T0, sec(40), sec(10), T0, sec(120))).toEqual({ startOffsetMs: 0, durationMs: 120_000 });
  });
});

describe('buildClipDownloadUrl (docs/OPS-CORE-PLAN.md §R, R-c)', () => {
  const baseUrl = 'http://mediamtx.local:19996/get?path=stream-1&start=2026-07-23T10%3A00%3A00Z&duration=600';

  it('replaces start/duration for a sub-window, keeping path and every other part of the URL', () => {
    const url = buildClipDownloadUrl(baseUrl, { startOffsetMs: 30_000, durationMs: 60_000 });
    expect(url).toBeDefined();
    const parsed = new URL(url!);
    expect(parsed.searchParams.get('path')).toBe('stream-1');
    expect(parsed.searchParams.get('start')).toBe('2026-07-23T10:00:30.000Z');
    expect(parsed.searchParams.get('duration')).toBe('60');
    expect(parsed.origin).toBe('http://mediamtx.local:19996');
  });

  it('rounds duration to the nearest whole second', () => {
    const url = buildClipDownloadUrl(baseUrl, { startOffsetMs: 0, durationMs: 1_499 });
    expect(new URL(url!).searchParams.get('duration')).toBe('1');
  });

  it('never emits a zero-or-negative duration', () => {
    const url = buildClipDownloadUrl(baseUrl, { startOffsetMs: 0, durationMs: 10 });
    expect(new URL(url!).searchParams.get('duration')).toBe('1');
  });

  it('is undefined for an unparseable URL', () => {
    expect(buildClipDownloadUrl('not a url', { startOffsetMs: 0, durationMs: 1000 })).toBeUndefined();
  });

  it('is undefined when the URL carries no start param to anchor against', () => {
    expect(buildClipDownloadUrl('http://mediamtx.local:19996/get?path=stream-1', { startOffsetMs: 0, durationMs: 1000 })).toBeUndefined();
  });
});

describe('parseDeepLinkOffsetMs (docs/OPS-CORE-PLAN.md §Q1)', () => {
  it('parses a numeric string', () => {
    expect(parseDeepLinkOffsetMs('1500')).toBe(1500);
  });

  it('is undefined for an absent value', () => {
    expect(parseDeepLinkOffsetMs(undefined)).toBeUndefined();
  });

  it('is undefined for a non-numeric value, never NaN', () => {
    expect(parseDeepLinkOffsetMs('soon')).toBeUndefined();
  });
});
