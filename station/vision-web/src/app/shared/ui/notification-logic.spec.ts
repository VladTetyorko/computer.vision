import { describe, expect, it } from 'vitest';
import { newlyOpenedEvents, shouldToast, unreadEvents } from './notification-logic';
import type { DetectionEvent, LiveEvent } from '../../core/api/models';

function event(partial: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-0',
    streamId: 's-0',
    label: 'person',
    peakConfidence: 0.9,
    firstSeen: '2026-01-01T00:00:00Z',
    lastSeen: '2026-01-01T00:00:05Z',
    state: 'OPEN',
    ...partial,
  };
}

describe('unreadEvents', () => {
  it('keeps every event whose id is not in readIds', () => {
    const a = event({ id: 'a' });
    const b = event({ id: 'b' });
    expect(unreadEvents([a, b], new Set(['a']))).toEqual([b]);
  });

  it('is empty once every event has been read', () => {
    const a = event({ id: 'a' });
    expect(unreadEvents([a], new Set(['a']))).toEqual([]);
  });

  it('is the full list when nothing has ever been read', () => {
    const a = event({ id: 'a' });
    const b = event({ id: 'b' });
    expect(unreadEvents([a, b], new Set())).toEqual([a, b]);
  });
});

describe('newlyOpenedEvents', () => {
  it('excludes an id already in knownIds', () => {
    const a = event({ id: 'a' });
    expect(newlyOpenedEvents([a], new Set(['a']))).toEqual([]);
  });

  it('excludes a genuinely new event that is already CLOSED', () => {
    const closed = event({ id: 'c', state: 'CLOSED' });
    expect(newlyOpenedEvents([closed], new Set())).toEqual([]);
  });

  it('includes a genuinely new, still-OPEN event', () => {
    const open = event({ id: 'o', state: 'OPEN' });
    expect(newlyOpenedEvents([open], new Set())).toEqual([open]);
  });

  it('filters a mixed batch down to only the new-and-open ones', () => {
    const known = event({ id: 'known', state: 'OPEN' });
    const newClosed = event({ id: 'new-closed', state: 'CLOSED' });
    const newOpen = event({ id: 'new-open', state: 'OPEN' });
    const result = newlyOpenedEvents([known, newClosed, newOpen], new Set(['known']));
    expect(result.map((e) => e.id)).toEqual(['new-open']);
  });
});

function liveEvent(partial: Partial<LiveEvent> = {}): LiveEvent {
  return {
    id: 'le-0',
    at: '2026-08-29T12:00:00.000Z',
    type: 'GEOFENCE_BREACH',
    message: 'KEEP-IN breach — Demo operating area',
    attributes: { assetId: 'a-0', zoneId: 'z-0', zoneName: 'Demo operating area', kind: 'KEEP_IN', direction: 'enter' },
    ...partial,
  };
}

describe('shouldToast (OPERATOR-UX-5-PLAN.md finding U4)', () => {
  const mountedAtMs = Date.parse('2026-08-29T12:00:00.000Z');

  it('toasts an event at or after the bell mounted, for a streaming asset', () => {
    expect(shouldToast(liveEvent({ at: '2026-08-29T12:00:00.000Z' }), mountedAtMs, true)).toBe(true);
    expect(shouldToast(liveEvent({ at: '2026-08-29T12:00:05.000Z' }), mountedAtMs, true)).toBe(true);
  });

  it("never toasts an event that predates the bell's own mount — the U4 root cause: an SSE replay landing after the seed must not read as news", () => {
    // The exact U4 reproduction: a breach from an asset offline for days, replayed over SSE on a
    // later tick than the bell's first render.
    const daysOld = liveEvent({ at: '2026-08-25T09:00:00.000Z' });
    expect(shouldToast(daysOld, mountedAtMs, true)).toBe(false);
    expect(shouldToast(daysOld, mountedAtMs, false)).toBe(false);
  });

  it('never toasts for an asset that is not currently streaming, even with a perfectly fresh timestamp', () => {
    expect(shouldToast(liveEvent({ at: '2026-08-29T12:00:01.000Z' }), mountedAtMs, false)).toBe(false);
  });

  it('both gates are independently required — fresh timestamp and streaming both true is the only true case', () => {
    const fresh = liveEvent({ at: '2026-08-29T12:00:01.000Z' });
    expect(shouldToast(fresh, mountedAtMs, true)).toBe(true);
    expect(shouldToast(fresh, mountedAtMs, false)).toBe(false);
  });
});
