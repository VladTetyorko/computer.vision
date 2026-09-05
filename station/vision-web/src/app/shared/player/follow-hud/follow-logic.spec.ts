import { describe, expect, it } from 'vitest';
import { followPresentation } from './follow-logic';
import type { FollowStatus } from '../../../core/api/models';

const NOW = Date.parse('2026-09-02T12:00:00.000Z');

function follow(overrides: Partial<FollowStatus>): FollowStatus {
  return {
    state: 'HOLDING',
    trackId: 7,
    label: 'person',
    since: '2026-09-02T11:59:00.000Z',
    lastSeenAt: '2026-09-02T11:59:59.000Z',
    lastSeenAgeMillis: 1_000,
    lastBox: null,
    reacquirable: false,
    recoveredAfterMillis: null,
    recoveryConfidence: null,
    ...overrides,
  };
}

describe('followPresentation', () => {
  it('renders REQUESTING as acquiring, warn tone, no reacquire', () => {
    const p = followPresentation(follow({ state: 'REQUESTING' }), NOW);
    expect(p.title).toBe('person #7');
    expect(p.detail).toBe('Acquiring…');
    expect(p.tone).toBe('warn');
    expect(p.showReacquire).toBe(false);
  });

  it('renders HOLDING as following, live tone, no reacquire', () => {
    const p = followPresentation(follow({ state: 'HOLDING' }), NOW);
    expect(p.detail).toBe('Following');
    expect(p.tone).toBe('live');
    expect(p.showReacquire).toBe(false);
  });

  it('renders COASTING as coasting, warn tone, no reacquire', () => {
    const p = followPresentation(follow({ state: 'COASTING' }), NOW);
    expect(p.detail).toBe('Coasting');
    expect(p.tone).toBe('warn');
    expect(p.showReacquire).toBe(false);
  });

  it('renders RELEASED as released, warn tone, no reacquire', () => {
    const p = followPresentation(follow({ state: 'RELEASED' }), NOW);
    expect(p.detail).toBe('Released');
    expect(p.tone).toBe('warn');
    expect(p.showReacquire).toBe(false);
  });

  describe('LOST', () => {
    it('renders danger tone and a humanAge-formatted last-seen phrase', () => {
      const p = followPresentation(
        follow({ state: 'LOST', lastSeenAt: new Date(NOW - 4_000).toISOString() }),
        NOW,
      );
      expect(p.tone).toBe('danger');
      expect(p.detail).toBe('Lost — last seen 4s ago');
    });

    it('falls back to a bare "Lost" when lastSeenAt is null', () => {
      const p = followPresentation(follow({ state: 'LOST', lastSeenAt: null }), NOW);
      expect(p.detail).toBe('Lost');
    });

    it('gates showReacquire on reacquirable', () => {
      expect(followPresentation(follow({ state: 'LOST', reacquirable: true }), NOW).showReacquire).toBe(true);
      expect(followPresentation(follow({ state: 'LOST', reacquirable: false }), NOW).showReacquire).toBe(false);
    });

    it('never offers reacquire outside LOST even when reacquirable is somehow true', () => {
      expect(followPresentation(follow({ state: 'HOLDING', reacquirable: true }), NOW).showReacquire).toBe(false);
      expect(followPresentation(follow({ state: 'COASTING', reacquirable: true }), NOW).showReacquire).toBe(false);
      expect(followPresentation(follow({ state: 'REQUESTING', reacquirable: true }), NOW).showReacquire).toBe(false);
      expect(followPresentation(follow({ state: 'RELEASED', reacquirable: true }), NOW).showReacquire).toBe(false);
    });
  });

  describe('title', () => {
    it('falls back to "#<trackId>" when label is ""', () => {
      const p = followPresentation(follow({ label: '', trackId: 7 }), NOW);
      expect(p.title).toBe('#7');
    });

    it('renders "<label> #<trackId>" when label is present', () => {
      const p = followPresentation(follow({ label: 'car', trackId: 12 }), NOW);
      expect(p.title).toBe('car #12');
    });
  });
});
