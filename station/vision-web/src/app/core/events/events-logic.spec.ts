import { describe, expect, it } from 'vitest';
import type { ActiveStream, AssetUsage, DetectionEvent, Device } from '../api/models';
import {
  MAX_EVENT_MARKERS,
  advanceCursor,
  capitalizeLabel,
  describeEventSource,
  distinctLabels,
  eventNotificationText,
  filterEvents,
  findCoveringUsage,
  formatConfidence,
  mergeEvents,
  relativeTimeLabel,
  resolveEventTarget,
  resolveReplayDeepLink,
  selectEventMarkers,
  shouldNotify,
} from './events-logic';

/** A `lastSeen` a few seconds ago — inside every default/custom `maxAgeMinutes` window this file tests. */
function recentIso(secondsAgo = 5): string {
  return new Date(Date.now() - secondsAgo * 1000).toISOString();
}

function usage(partial: Partial<AssetUsage> = {}): AssetUsage {
  return {
    usageId: 'u-1',
    startedAt: '2026-07-23T10:00:00.000Z',
    endedAt: '2026-07-23T10:10:00.000Z',
    sampleCount: 100,
    ...partial,
  };
}

function event(partial: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.8,
    firstSeen: '2026-07-23T10:00:00.000Z',
    lastSeen: '2026-07-23T10:00:05.000Z',
    state: 'OPEN',
    ...partial,
  };
}

function device(partial: Partial<Device> = {}): Device {
  return {
    id: 'dev-1',
    name: 'Front camera',
    capabilities: ['VIDEO'],
    protocol: 'sim',
    uri: 'sim://demo',
    options: {},
    state: 'ACTIVE',
    ...partial,
  };
}

function stream(partial: Partial<ActiveStream> = {}): ActiveStream {
  return { streamId: 's-1', deviceId: 'dev-1', startedAt: '2026-07-23T09:00:00Z', ...partial };
}

describe('mergeEvents', () => {
  it('adds a genuinely new event', () => {
    const merged = mergeEvents([], [event({ id: 'e-1' })]);
    expect(merged.map((e) => e.id)).toEqual(['e-1']);
  });

  it('upserts an existing id — incoming always wins, never duplicated', () => {
    const stale = event({ id: 'e-1', peakConfidence: 0.5, lastSeen: '2026-07-23T10:00:05.000Z' });
    const fresh = event({ id: 'e-1', peakConfidence: 0.9, lastSeen: '2026-07-23T10:00:10.000Z' });
    const merged = mergeEvents([stale], [fresh]);
    expect(merged).toHaveLength(1);
    expect(merged[0]).toEqual(fresh);
  });

  it('re-sorts the merged set newest-first by lastSeen regardless of input order', () => {
    const older = event({ id: 'older', lastSeen: '2026-07-23T10:00:00.000Z' });
    const newer = event({ id: 'newer', lastSeen: '2026-07-23T10:05:00.000Z' });
    const merged = mergeEvents([older], [newer]);
    expect(merged.map((e) => e.id)).toEqual(['newer', 'older']);
  });

  it('trims to maxRetained, dropping the oldest first', () => {
    const events = [
      event({ id: 'a', lastSeen: '2026-07-23T10:03:00.000Z' }),
      event({ id: 'b', lastSeen: '2026-07-23T10:02:00.000Z' }),
      event({ id: 'c', lastSeen: '2026-07-23T10:01:00.000Z' }),
    ];
    expect(mergeEvents([], events, 2).map((e) => e.id)).toEqual(['a', 'b']);
  });

  it('a reopened/updated OPEN event keeps its single slot, not a second row', () => {
    const first = event({ id: 'e-1', state: 'OPEN', lastSeen: '2026-07-23T10:00:00.000Z' });
    const closed = event({ id: 'e-1', state: 'CLOSED', lastSeen: '2026-07-23T10:00:30.000Z' });
    const merged = mergeEvents(mergeEvents([], [first]), [closed]);
    expect(merged).toHaveLength(1);
    expect(merged[0].state).toBe('CLOSED');
  });
});

describe('advanceCursor', () => {
  it('stays undefined when nothing has ever arrived', () => {
    expect(advanceCursor(undefined, [])).toBeUndefined();
  });

  it('adopts the newest batch\'s lastSeen on the first poll (incoming is newest-first)', () => {
    const incoming = [
      event({ lastSeen: '2026-07-23T10:05:00.000Z' }),
      event({ lastSeen: '2026-07-23T10:00:00.000Z' }),
    ];
    expect(advanceCursor(undefined, incoming)).toBe(Date.parse('2026-07-23T10:05:00.000Z'));
  });

  it('never regresses when an empty poll comes back', () => {
    expect(advanceCursor(1_000, [])).toBe(1_000);
  });

  it('never regresses even if a later poll\'s newest is somehow not fresher', () => {
    const stale = [event({ lastSeen: '2026-07-23T09:00:00.000Z' })];
    expect(advanceCursor(Date.parse('2026-07-23T10:00:00.000Z'), stale)).toBe(
      Date.parse('2026-07-23T10:00:00.000Z'),
    );
  });

  it('advances forward when a fresher batch arrives', () => {
    const fresh = [event({ lastSeen: '2026-07-23T11:00:00.000Z' })];
    expect(advanceCursor(Date.parse('2026-07-23T10:00:00.000Z'), fresh)).toBe(
      Date.parse('2026-07-23T11:00:00.000Z'),
    );
  });
});

describe('filterEvents', () => {
  const events = [
    event({ id: 'e-1', label: 'person', assetId: 'a-1' }),
    event({ id: 'e-2', label: 'car', assetId: 'a-1' }),
    event({ id: 'e-3', label: 'person', assetId: 'a-2' }),
    event({ id: 'e-4', label: 'person' }), // no assetId
  ];

  it('matches everything when no filter fields are given', () => {
    expect(filterEvents(events, {})).toHaveLength(4);
  });

  it('filters by label alone', () => {
    expect(filterEvents(events, { label: 'car' }).map((e) => e.id)).toEqual(['e-2']);
  });

  it('filters by asset alone', () => {
    expect(filterEvents(events, { assetId: 'a-1' }).map((e) => e.id)).toEqual(['e-1', 'e-2']);
  });

  it('combines both filters', () => {
    expect(filterEvents(events, { label: 'person', assetId: 'a-2' }).map((e) => e.id)).toEqual(['e-3']);
  });

  it('an assetId filter never matches an event with no assetId', () => {
    expect(filterEvents(events, { assetId: 'a-1' })).not.toContainEqual(events[3]);
  });
});

describe('distinctLabels', () => {
  it('deduplicates and sorts alphabetically', () => {
    const events = [event({ label: 'person' }), event({ label: 'car' }), event({ label: 'person' })];
    expect(distinctLabels(events)).toEqual(['car', 'person']);
  });

  it('is empty for no events', () => {
    expect(distinctLabels([])).toEqual([]);
  });
});

describe('capitalizeLabel', () => {
  it('capitalizes the first letter only', () => {
    expect(capitalizeLabel('person')).toBe('Person');
  });

  it('handles an empty string without throwing', () => {
    expect(capitalizeLabel('')).toBe('');
  });
});

describe('formatConfidence', () => {
  it('renders a whole-percent string', () => {
    expect(formatConfidence(0.873)).toBe('87%');
  });

  it('rounds rather than truncates', () => {
    expect(formatConfidence(0.876)).toBe('88%');
  });
});

describe('relativeTimeLabel', () => {
  it('renders seconds elapsed', () => {
    const now = Date.parse('2026-07-23T10:00:12.000Z');
    expect(relativeTimeLabel('2026-07-23T10:00:00.000Z', now)).toBe('12s ago');
  });

  it('never goes negative for a clock-skewed future timestamp', () => {
    const now = Date.parse('2026-07-23T10:00:00.000Z');
    expect(relativeTimeLabel('2026-07-23T10:00:05.000Z', now)).toBe('0s ago');
  });

  // docs/plans/active/OPERATOR-UX-4-PLAN.md finding N4 — one age vocabulary (humanAge), not
  // formatDuration's zero-padded, hour-capped rendering.
  it('renders minutes via humanAge, dropping a zero seconds remainder', () => {
    const now = Date.parse('2026-07-23T10:10:00.000Z');
    expect(relativeTimeLabel('2026-07-23T10:00:00.000Z', now)).toBe('10m ago');
  });

  it('renders hours via humanAge, with a non-zero minutes remainder', () => {
    const now = Date.parse('2026-07-23T14:20:00.000Z');
    expect(relativeTimeLabel('2026-07-23T10:00:00.000Z', now)).toBe('4h 20m ago');
  });

  it('renders days for an age humanAge could never reach as a duration (N4\'s own 323353s finding)', () => {
    const now = Date.parse('2026-07-23T10:00:00.000Z');
    expect(relativeTimeLabel('2026-07-19T14:04:07.000Z', now)).toBe('3d 19h ago');
  });
});

describe('describeEventSource', () => {
  it('resolves the streaming device\'s own name', () => {
    const e = event({ streamId: 's-1' });
    expect(describeEventSource(e, [device({ id: 'dev-1', name: 'Front camera' })], [stream()])).toBe(
      'Front camera',
    );
  });

  // docs/plans/active/OPERATOR-UX-4-PLAN.md finding N5 — a bare hash reads as a name; this says the
  // device is gone.
  it('falls back to "Removed device · <8-char id>" from assetId when the device cannot be resolved', () => {
    const e = event({ streamId: 'unknown-stream', assetId: 'asset-1234-5678' });
    expect(describeEventSource(e, [], [])).toBe('Removed device · asset-12');
  });

  it('falls back to "Removed device · <8-char id>" from streamId when neither device nor asset resolve', () => {
    const e = event({ streamId: 'stream-1234-5678', assetId: undefined });
    expect(describeEventSource(e, [], [])).toBe('Removed device · stream-1');
  });

  it('degrades to "—" for a device-level event with neither a streamId nor an assetId', () => {
    const e = event({ streamId: undefined, assetId: undefined });
    expect(describeEventSource(e, [], [])).toBe('—');
  });
});

describe('resolveEventTarget', () => {
  it('prefers the asset detail target when assetId resolved', () => {
    const e = event({ assetId: 'a-1', streamId: 's-1' });
    expect(resolveEventTarget(e, [stream()])).toEqual({ kind: 'asset', id: 'a-1' });
  });

  it('falls back to the live cockpit when no assetId but the stream is still active', () => {
    const e = event({ assetId: undefined, streamId: 's-1' });
    expect(resolveEventTarget(e, [stream({ streamId: 's-1', deviceId: 'dev-9' })])).toEqual({
      kind: 'live',
      id: 'dev-9',
    });
  });

  it('resolves to undefined when neither an asset nor a live stream can be found', () => {
    const e = event({ assetId: undefined, streamId: 'gone' });
    expect(resolveEventTarget(e, [])).toBeUndefined();
  });
});

describe('selectEventMarkers', () => {
  it('keeps only events carrying a position', () => {
    const withPos = event({ id: 'has-pos', position: { latitude: 1, longitude: 2 }, lastSeen: recentIso() });
    const withoutPos = event({ id: 'no-pos', lastSeen: recentIso() });
    expect(selectEventMarkers([withPos, withoutPos]).map((e) => e.id)).toEqual(['has-pos']);
  });

  it('caps to the max most recent (assumes newest-first input)', () => {
    const events = Array.from({ length: MAX_EVENT_MARKERS + 5 }, (_, i) =>
      event({ id: `e-${i}`, position: { latitude: i, longitude: i }, lastSeen: recentIso() }),
    );
    expect(selectEventMarkers(events)).toHaveLength(MAX_EVENT_MARKERS);
    expect(selectEventMarkers(events)[0].id).toBe('e-0');
  });

  it('respects a custom max', () => {
    const events = [
      event({ id: 'a', position: { latitude: 1, longitude: 1 }, lastSeen: recentIso() }),
      event({ id: 'b', position: { latitude: 2, longitude: 2 }, lastSeen: recentIso() }),
    ];
    expect(selectEventMarkers(events, { max: 1 }).map((e) => e.id)).toEqual(['a']);
  });

  it('is empty when nothing carries a position', () => {
    expect(selectEventMarkers([event({ lastSeen: recentIso() })])).toEqual([]);
  });

  /** docs/plans/active/COMMAND-MAP-FLOW-PLAN.md §3.5 D5 — "filter, do not hide": open+recent is the
   *  frozen default, not an opt-in, so the bare call every existing caller already makes picks up
   *  the judged filter for free. */
  describe('the frozen defaults (openOnly: true, maxAgeMinutes: 60) — §3.5', () => {
    it('drops a CLOSED event even when it is recent', () => {
      const closed = event({ id: 'closed', position: { latitude: 1, longitude: 1 }, state: 'CLOSED', lastSeen: recentIso() });
      expect(selectEventMarkers([closed])).toEqual([]);
    });

    it('keeps an OPEN event that is recent', () => {
      const open = event({ id: 'open', position: { latitude: 1, longitude: 1 }, state: 'OPEN', lastSeen: recentIso() });
      expect(selectEventMarkers([open]).map((e) => e.id)).toEqual(['open']);
    });

    it('drops an OPEN event whose lastSeen is older than an hour — the exact live-station repro (33 CLOSED events on a removed device used to render as a 30-dot swarm)', () => {
      const stale = event({ id: 'stale', position: { latitude: 1, longitude: 1 }, state: 'OPEN', lastSeen: recentIso(61 * 60) });
      expect(selectEventMarkers([stale])).toEqual([]);
    });
  });

  describe('options — every field independently overridable', () => {
    it('openOnly: false lets a recent CLOSED event through', () => {
      const closed = event({ id: 'closed', position: { latitude: 1, longitude: 1 }, state: 'CLOSED', lastSeen: recentIso() });
      expect(selectEventMarkers([closed], { openOnly: false }).map((e) => e.id)).toEqual(['closed']);
    });

    it('a wider maxAgeMinutes keeps an event the frozen default would have dropped', () => {
      const hourOld = event({ id: 'hour-old', position: { latitude: 1, longitude: 1 }, state: 'OPEN', lastSeen: recentIso(90 * 60) });
      expect(selectEventMarkers([hourOld])).toEqual([]);
      expect(selectEventMarkers([hourOld], { maxAgeMinutes: 120 }).map((e) => e.id)).toEqual(['hour-old']);
    });

    it('a narrower maxAgeMinutes drops an event the frozen default would have kept', () => {
      const fiveMinOld = event({ id: 'five-min', position: { latitude: 1, longitude: 1 }, state: 'OPEN', lastSeen: recentIso(5 * 60) });
      expect(selectEventMarkers([fiveMinOld])).toHaveLength(1);
      expect(selectEventMarkers([fiveMinOld], { maxAgeMinutes: 1 })).toEqual([]);
    });
  });
});

describe('shouldNotify', () => {
  const base = {
    event: event({ state: 'OPEN' }),
    alreadySeen: false,
    notificationsEnabled: true,
    permission: 'granted' as NotificationPermission,
    documentHidden: true,
  };

  it('fires for a genuinely new, OPEN event with everything else granted', () => {
    expect(shouldNotify(base)).toBe(true);
  });

  it('refuses an already-seen event — dedupe by id', () => {
    expect(shouldNotify({ ...base, alreadySeen: true })).toBe(false);
  });

  it('refuses a CLOSED event even if brand new', () => {
    expect(shouldNotify({ ...base, event: event({ state: 'CLOSED' }) })).toBe(false);
  });

  it('refuses when the user has not opted in', () => {
    expect(shouldNotify({ ...base, notificationsEnabled: false })).toBe(false);
  });

  it('refuses when the browser permission is not granted', () => {
    expect(shouldNotify({ ...base, permission: 'default' })).toBe(false);
    expect(shouldNotify({ ...base, permission: 'denied' })).toBe(false);
  });

  it('refuses while the document is visible — the rail already shows it', () => {
    expect(shouldNotify({ ...base, documentHidden: false })).toBe(false);
  });
});

describe('eventNotificationText', () => {
  it('renders a capitalized label title and a confidence body', () => {
    const text = eventNotificationText(event({ label: 'person', peakConfidence: 0.87 }));
    expect(text).toEqual({ title: 'Person detected', body: '87% confidence' });
  });
});

describe('findCoveringUsage (docs/plans/done/OPS-CORE-PLAN.md §Q1)', () => {
  it('finds the finished usage whose window covers the given instant', () => {
    const usages = [usage({ usageId: 'u-2', startedAt: '2026-07-23T11:00:00.000Z', endedAt: '2026-07-23T11:10:00.000Z' }), usage()];
    expect(findCoveringUsage(usages, '2026-07-23T10:05:00.000Z')?.usageId).toBe('u-1');
  });

  it('treats an open usage\'s window as covering up to "now"', () => {
    const openUsage = usage({ usageId: 'u-open', endedAt: undefined });
    expect(findCoveringUsage([openUsage], new Date().toISOString())?.usageId).toBe('u-open');
  });

  it('is undefined when nothing covers the instant (a gap between usages)', () => {
    expect(findCoveringUsage([usage()], '2026-07-23T09:00:00.000Z')).toBeUndefined();
  });

  it('is undefined for an empty usage list', () => {
    expect(findCoveringUsage([], '2026-07-23T10:05:00.000Z')).toBeUndefined();
  });
});

describe('resolveReplayDeepLink (docs/plans/done/OPS-CORE-PLAN.md §Q1)', () => {
  it('resolves the covering finished usage and the offset from its own start', () => {
    const e = event({ firstSeen: '2026-07-23T10:02:30.000Z' });
    const deepLink = resolveReplayDeepLink(e, [usage()]);
    expect(deepLink).toEqual({ usageId: 'u-1', offsetMs: 150_000 });
  });

  it('is undefined when the covering usage is still open — replay would just redirect to live', () => {
    const openUsage = usage({ endedAt: undefined });
    const e = event({ firstSeen: '2026-07-23T10:02:30.000Z' });
    expect(resolveReplayDeepLink(e, [openUsage])).toBeUndefined();
  });

  it('is undefined when no usage covers the event at all', () => {
    const e = event({ firstSeen: '2026-07-23T09:00:00.000Z' });
    expect(resolveReplayDeepLink(e, [usage()])).toBeUndefined();
  });

  it('never returns a negative offset', () => {
    // firstSeen exactly at the usage's own start.
    const e = event({ firstSeen: '2026-07-23T10:00:00.000Z' });
    expect(resolveReplayDeepLink(e, [usage()])?.offsetMs).toBe(0);
  });
});
