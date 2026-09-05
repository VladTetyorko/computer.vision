import { describe, expect, it } from 'vitest';
import { targetRows } from './target-list-logic';
import type { StreamTrack } from '../../core/api/models';

const NOW = Date.parse('2026-09-05T12:00:00.000Z');

function track(overrides: Partial<StreamTrack>): StreamTrack {
  return {
    trackId: 1,
    label: 'person',
    confidence: 0.9,
    box: { x: 0.1, y: 0.1, width: 0.1, height: 0.1 },
    state: 'CONFIRMED',
    source: 'DETECTOR',
    velocityX: 0,
    velocityY: 0,
    ageFrames: 10,
    reupdated: false,
    firstSeen: '2026-09-05T11:59:00.000Z',
    lastSeen: '2026-09-05T11:59:59.000Z',
    ...overrides,
  };
}

describe('targetRows', () => {
  it('returns an empty list for an empty tracks array (the empty-state trigger)', () => {
    expect(targetRows([], 0, NOW)).toEqual([]);
  });

  it('orders the followed track first regardless of recency', () => {
    const stale = track({ trackId: 7, lastSeen: new Date(NOW - 30_000).toISOString() });
    const fresh = track({ trackId: 9, lastSeen: new Date(NOW - 1_000).toISOString() });
    const rows = targetRows([stale, fresh], 7, NOW);
    expect(rows.map((r) => r.trackId)).toEqual([7, 9]);
    expect(rows[0].followed).toBe(true);
    expect(rows[1].followed).toBe(false);
  });

  it('orders non-followed tracks most-recently-seen first', () => {
    const oldest = track({ trackId: 1, lastSeen: new Date(NOW - 20_000).toISOString() });
    const newest = track({ trackId: 2, lastSeen: new Date(NOW - 1_000).toISOString() });
    const middle = track({ trackId: 3, lastSeen: new Date(NOW - 10_000).toISOString() });
    const rows = targetRows([oldest, newest, middle], 0, NOW);
    expect(rows.map((r) => r.trackId)).toEqual([2, 3, 1]);
  });

  it('never marks any row followed when followedTrackId is 0 (the wire\'s own "no lock" sentinel)', () => {
    const rows = targetRows([track({ trackId: 0 })], 0, NOW);
    expect(rows[0].followed).toBe(false);
  });

  it('maps followedTrackId to exactly the matching row, never a mismatch', () => {
    const a = track({ trackId: 5 });
    const b = track({ trackId: 6 });
    const rows = targetRows([a, b], 6, NOW);
    const byId = Object.fromEntries(rows.map((r) => [r.trackId, r.followed]));
    expect(byId[5]).toBe(false);
    expect(byId[6]).toBe(true);
  });

  it('renders a humanAge-formatted age from lastSeen', () => {
    const rows = targetRows([track({ trackId: 1, lastSeen: new Date(NOW - 4_000).toISOString() })], 0, NOW);
    expect(rows[0].age).toBe('4s');
  });

  describe('state presentation', () => {
    it('maps CONFIRMED to live tone', () => {
      const rows = targetRows([track({ state: 'CONFIRMED' })], 0, NOW);
      expect(rows[0].stateLabel).toBe('Confirmed');
      expect(rows[0].stateTone).toBe('live');
    });

    it('maps TENTATIVE and COASTING to warn tone', () => {
      expect(targetRows([track({ state: 'TENTATIVE' })], 0, NOW)[0].stateTone).toBe('warn');
      expect(targetRows([track({ state: 'COASTING' })], 0, NOW)[0].stateTone).toBe('warn');
    });

    it('maps LOST to danger tone', () => {
      const rows = targetRows([track({ state: 'LOST' })], 0, NOW);
      expect(rows[0].stateLabel).toBe('Lost');
      expect(rows[0].stateTone).toBe('danger');
    });
  });
});
