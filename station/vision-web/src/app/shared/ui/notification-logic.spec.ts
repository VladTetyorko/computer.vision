import { describe, expect, it } from 'vitest';
import { newlyOpenedEvents, unreadEvents } from './notification-logic';
import type { DetectionEvent } from '../../core/api/models';

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
