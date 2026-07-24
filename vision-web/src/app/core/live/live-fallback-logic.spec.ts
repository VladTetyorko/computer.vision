import { describe, expect, it } from 'vitest';
import {
  buildTopicsParam,
  decrementTopicRef,
  detectionsTopic,
  incrementTopicRef,
  isLiveAvailable,
  mergeTelemetrySamples,
  resolveAssetScopedTransport,
  telemetryTopic,
  trackSessionKey,
} from './live-fallback-logic';
import type { TelemetrySample } from '../api/models';

function sample(deviceId: string, at: string, latitude: number): TelemetrySample {
  return { deviceId, at, latitude, longitude: latitude };
}

describe('isLiveAvailable', () => {
  it('is true only for "open"', () => {
    expect(isLiveAvailable('open')).toBe(true);
    expect(isLiveAvailable('connecting')).toBe(false);
    expect(isLiveAvailable('closed')).toBe(false);
  });
});

describe('resolveAssetScopedTransport', () => {
  it('uses live only when open AND an assetId is known', () => {
    expect(resolveAssetScopedTransport('open', 'a-1')).toBe('live');
  });

  it('falls back to poll when not open, even with an assetId', () => {
    expect(resolveAssetScopedTransport('connecting', 'a-1')).toBe('poll');
    expect(resolveAssetScopedTransport('closed', 'a-1')).toBe('poll');
  });

  it('falls back to poll when there is no assetId, even while open', () => {
    expect(resolveAssetScopedTransport('open', undefined)).toBe('poll');
  });
});

describe('trackSessionKey (docs/REALTIME-PLAN.md §4 Phase R-c follow-up)', () => {
  it('is stable for the same (primaryId, assetId) pair', () => {
    expect(trackSessionKey('dev-1', 'a-1')).toBe(trackSessionKey('dev-1', 'a-1'));
  });

  it('differs when the primary id differs', () => {
    expect(trackSessionKey('dev-1', 'a-1')).not.toBe(trackSessionKey('dev-2', 'a-1'));
  });

  it('differs when the assetId differs', () => {
    expect(trackSessionKey('dev-1', 'a-1')).not.toBe(trackSessionKey('dev-1', 'a-2'));
  });

  it('differs between an omitted assetId and one that looks like the separator-joined empty string', () => {
    expect(trackSessionKey('dev-1', undefined)).not.toBe(trackSessionKey('dev-1 ', undefined));
  });
});

describe('telemetryTopic / detectionsTopic', () => {
  it('builds the wire topic strings', () => {
    expect(telemetryTopic('a-1')).toBe('telemetry:a-1');
    expect(detectionsTopic('a-1')).toBe('detections:a-1');
  });
});

describe('buildTopicsParam', () => {
  it('comma-joins topics', () => {
    expect(buildTopicsParam(['telemetry:a-1', 'detections:a-2'])).toBe('telemetry:a-1,detections:a-2');
  });

  it('is empty for no topics', () => {
    expect(buildTopicsParam([])).toBe('');
  });
});

describe('incrementTopicRef', () => {
  it('reports firstSubscriber going from absent to 1', () => {
    const result = incrementTopicRef(new Map(), 'telemetry:a-1');
    expect(result).toEqual({ count: 1, firstSubscriber: true });
  });

  it('reports a second subscriber as not first', () => {
    const counts = new Map([['telemetry:a-1', 1]]);
    expect(incrementTopicRef(counts, 'telemetry:a-1')).toEqual({ count: 2, firstSubscriber: false });
  });

  it('does not mutate the input map', () => {
    const counts = new Map<string, number>();
    incrementTopicRef(counts, 'telemetry:a-1');
    expect(counts.size).toBe(0);
  });
});

describe('decrementTopicRef', () => {
  it('reports lastSubscriber going from 1 to 0', () => {
    const counts = new Map([['telemetry:a-1', 1]]);
    expect(decrementTopicRef(counts, 'telemetry:a-1')).toEqual({ count: 0, lastSubscriber: true });
  });

  it('reports an intermediate decrement as not last', () => {
    const counts = new Map([['telemetry:a-1', 2]]);
    expect(decrementTopicRef(counts, 'telemetry:a-1')).toEqual({ count: 1, lastSubscriber: false });
  });

  it('floors at 0 for an already-untracked topic, never going negative', () => {
    expect(decrementTopicRef(new Map(), 'telemetry:a-1')).toEqual({ count: 0, lastSubscriber: false });
  });
});

describe('mergeTelemetrySamples', () => {
  it('appends incoming samples in chronological order', () => {
    const existing = [sample('dev-1', '2026-07-24T00:00:00Z', 1)];
    const incoming = [sample('dev-1', '2026-07-24T00:00:01Z', 2)];
    expect(mergeTelemetrySamples(existing, incoming)).toEqual([
      sample('dev-1', '2026-07-24T00:00:00Z', 1),
      sample('dev-1', '2026-07-24T00:00:01Z', 2),
    ]);
  });

  it('dedupes by (deviceId, at) — a resumed/re-subscribed replay never duplicates a point', () => {
    const existing = [sample('dev-1', '2026-07-24T00:00:00Z', 1)];
    const incoming = [sample('dev-1', '2026-07-24T00:00:00Z', 1), sample('dev-1', '2026-07-24T00:00:01Z', 2)];
    expect(mergeTelemetrySamples(existing, incoming)).toHaveLength(2);
  });

  it('re-sorts rather than trusting append order, since a snapshot burst is not guaranteed ordered', () => {
    const existing = [sample('dev-1', '2026-07-24T00:00:05Z', 5)];
    const incoming = [sample('dev-1', '2026-07-24T00:00:01Z', 1)];
    expect(mergeTelemetrySamples(existing, incoming).map((s) => s.at)).toEqual([
      '2026-07-24T00:00:01Z',
      '2026-07-24T00:00:05Z',
    ]);
  });

  it('returns the existing array unchanged (by value) when incoming is empty', () => {
    const existing = [sample('dev-1', '2026-07-24T00:00:00Z', 1)];
    expect(mergeTelemetrySamples(existing, [])).toEqual(existing);
  });

  it('trims to maxRetained, dropping the oldest first', () => {
    const existing = [sample('dev-1', '2026-07-24T00:00:00Z', 1), sample('dev-1', '2026-07-24T00:00:01Z', 2)];
    const incoming = [sample('dev-1', '2026-07-24T00:00:02Z', 3)];
    expect(mergeTelemetrySamples(existing, incoming, 2).map((s) => s.at)).toEqual([
      '2026-07-24T00:00:01Z',
      '2026-07-24T00:00:02Z',
    ]);
  });

  it('treats different devices with the same timestamp as distinct samples', () => {
    const incoming = [sample('dev-1', '2026-07-24T00:00:00Z', 1), sample('dev-2', '2026-07-24T00:00:00Z', 9)];
    expect(mergeTelemetrySamples([], incoming)).toHaveLength(2);
  });
});
