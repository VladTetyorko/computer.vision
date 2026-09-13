import { describe, expect, it } from 'vitest';
import { appendFrameLedger, DEFAULT_CV_TRACE_LAST } from './cv-trace-logic';
import type { FrameLedger } from '../api/models';

function ledger(sequence: number): FrameLedger {
  return {
    streamId: 'stream-1',
    sequence,
    capturedAtMillis: 1_700_000_000_000 + sequence,
    levelServed: 2,
    detectorReason: 'FULL',
    eligible: [],
    entries: [],
    objects: {},
    dropsSinceLast: 0,
    gateWaitMillis: 0,
    totalMillis: 0,
    halted: false,
    detections: [],
    frameWidth: 0,
    frameHeight: 0,
  };
}

describe('DEFAULT_CV_TRACE_LAST', () => {
  it('matches the server default (StreamController DEFAULT_TRACE_LAST)', () => {
    expect(DEFAULT_CV_TRACE_LAST).toBe(50);
  });
});

describe('appendFrameLedger', () => {
  it('appends a new frame to an empty ring', () => {
    const result = appendFrameLedger([], ledger(1), 50);
    expect(result).toEqual([ledger(1)]);
  });

  it('appends in ascending sequence order regardless of arrival order', () => {
    const afterFirst = appendFrameLedger([], ledger(5), 50);
    const afterSecond = appendFrameLedger(afterFirst, ledger(3), 50);
    expect(afterSecond.map((f) => f.sequence)).toEqual([3, 5]);
  });

  it('replaces a re-delivered frame (same sequence) instead of duplicating it', () => {
    const first = appendFrameLedger([], ledger(1), 50);
    const updated: FrameLedger = { ...ledger(1), totalMillis: 42 };
    const result = appendFrameLedger(first, updated, 50);
    expect(result).toHaveLength(1);
    expect(result[0].totalMillis).toBe(42);
  });

  it('caps the ring, dropping the oldest entries first', () => {
    let ring: readonly FrameLedger[] = [];
    for (let i = 0; i < 5; i++) {
      ring = appendFrameLedger(ring, ledger(i), 3);
    }
    expect(ring.map((f) => f.sequence)).toEqual([2, 3, 4]);
  });

  it('never drops the newest entry even at cap 1', () => {
    let ring: readonly FrameLedger[] = [];
    ring = appendFrameLedger(ring, ledger(1), 1);
    ring = appendFrameLedger(ring, ledger(2), 1);
    expect(ring.map((f) => f.sequence)).toEqual([2]);
  });
});
