import { describe, expect, it } from 'vitest';
import { cameraHeldByOther, cameraSeatChipLabel, crewDock, crewStage } from './crew-logic';
import type { SeatHolderResponse } from '../../core/api/models';

const FREE: SeatHolderResponse = {
  holderUserId: null,
  holderDisplayName: null,
  acquiredAt: null,
  expiresAt: null,
  mine: false,
};

function held(overrides: Partial<SeatHolderResponse> = {}): SeatHolderResponse {
  return {
    holderUserId: 'u-2',
    holderDisplayName: 'Anna Kovalenko',
    acquiredAt: '2026-09-04T10:12:03Z',
    expiresAt: '2026-09-04T10:12:18Z',
    mine: false,
    ...overrides,
  };
}

describe('cameraHeldByOther', () => {
  it('is false for a genuinely free seat, even though `mine` is also false', () => {
    expect(cameraHeldByOther(FREE)).toBe(false);
  });

  it('is false for a seat this caller holds', () => {
    expect(cameraHeldByOther(held({ mine: true }))).toBe(false);
  });

  it('is true for a seat somebody else holds', () => {
    expect(cameraHeldByOther(held())).toBe(true);
  });
});

describe('crewStage', () => {
  it('C0 — not live, not busy', () => {
    expect(crewStage({ live: false, busy: false, cameraHeldByOther: false })).toBe('C0');
  });

  it('C1 — busy takes priority while not live', () => {
    expect(crewStage({ live: false, busy: true, cameraHeldByOther: false })).toBe('C1');
  });

  it('C2 — live, camera held by someone else', () => {
    expect(crewStage({ live: true, busy: false, cameraHeldByOther: true })).toBe('C2');
  });

  it('C3 — live, camera mine', () => {
    expect(crewStage({ live: true, busy: false, cameraHeldByOther: false })).toBe('C3');
  });

  it('C3 — live, camera free (single-operator / feature-off fallback)', () => {
    expect(crewStage({ live: true, busy: false, cameraHeldByOther: false })).toBe('C3');
  });

  it('live always wins over busy — a stream going live mid-start-click is not stuck in C1', () => {
    expect(crewStage({ live: true, busy: true, cameraHeldByOther: false })).toBe('C3');
  });
});

describe('crewDock', () => {
  it('C0 with a free camera seat offers Start video, no reason', () => {
    const dock = crewDock('C0', FREE);
    expect(dock.text).toBe('Not streaming — the pilot has not started video');
    expect(dock.actionLabel).toBe('Start video');
    expect(dock.reason).toBeNull();
  });

  it('C0 with the camera seat mine also offers Start video', () => {
    const dock = crewDock('C0', held({ mine: true }));
    expect(dock.actionLabel).toBe('Start video');
    expect(dock.reason).toBeNull();
  });

  it('C0 with the camera held by someone else names them in the reason instead of an action', () => {
    const dock = crewDock('C0', held({ holderDisplayName: 'Anna Kovalenko' }));
    expect(dock.actionLabel).toBeNull();
    expect(dock.reason).toBe('Camera held by Anna Kovalenko');
  });

  it('C0 degrades the reason honestly if a held seat ever violated its own display-name contract', () => {
    const dock = crewDock('C0', held({ holderDisplayName: null }));
    expect(dock.reason).toBe('Camera held by another operator');
  });

  it('C1 has no action', () => {
    const dock = crewDock('C1', FREE);
    expect(dock.text).toBe('Waiting for the first frame');
    expect(dock.actionLabel).toBeNull();
    expect(dock.reason).toBeNull();
  });

  it('C2 is the fixed phrase, never interpolated with a name', () => {
    const dock = crewDock('C2', held({ holderDisplayName: 'Anna Kovalenko' }));
    expect(dock.text).toBe('Pilot has the camera');
    expect(dock.actionLabel).toBeNull();
    expect(dock.reason).toBeNull();
  });

  it('C3 is the fixed "you have it" phrase', () => {
    const dock = crewDock('C3', held({ mine: true }));
    expect(dock.text).toBe('You have the camera');
    expect(dock.actionLabel).toBeNull();
    expect(dock.reason).toBeNull();
  });
});

describe('cameraSeatChipLabel', () => {
  it('reads "You" when this caller holds it', () => {
    expect(cameraSeatChipLabel(held({ mine: true }))).toBe('You');
  });

  it('reads "Free" for a genuinely unheld seat', () => {
    expect(cameraSeatChipLabel(FREE)).toBe('Free');
  });

  it("reads the holder's name when someone else holds it", () => {
    expect(cameraSeatChipLabel(held({ holderDisplayName: 'Anna Kovalenko' }))).toBe('Anna Kovalenko');
  });
});
