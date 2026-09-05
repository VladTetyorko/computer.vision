import { describe, expect, it } from 'vitest';
import { renewalIntervalMs, seatFor, singleOperatorSeats } from './seat-logic';
import type { SeatsResponse } from '../api/models';

function seats(partial: Partial<SeatsResponse> = {}): SeatsResponse {
  return {
    ...singleOperatorSeats('a-1'),
    ...partial,
  };
}

describe('singleOperatorSeats', () => {
  it('reports both seats free, four explicit nulls each — never omitted holder fields', () => {
    const result = singleOperatorSeats('a-1');

    expect(result.flight).toEqual({
      holderUserId: null,
      holderDisplayName: null,
      acquiredAt: null,
      expiresAt: null,
      mine: false,
    });
    expect(result.camera).toEqual(result.flight);
  });

  it('grants every authority boolean except forcing a seat', () => {
    const result = singleOperatorSeats('a-1');

    expect(result.mayTakeFlight).toBe(true);
    expect(result.mayTakeCamera).toBe(true);
    expect(result.mayForceSeat).toBe(false);
  });

  it('stamps the given assetId, not a fabricated one', () => {
    expect(singleOperatorSeats('a-42').assetId).toBe('a-42');
  });
});

describe('seatFor', () => {
  it('reads the FLIGHT seat off the lowercase `flight` field', () => {
    const response = seats({ flight: { ...singleOperatorSeats('x').flight, mine: true } });
    expect(seatFor(response, 'FLIGHT').mine).toBe(true);
  });

  it('reads the CAMERA seat off the lowercase `camera` field', () => {
    const response = seats({ camera: { ...singleOperatorSeats('x').camera, mine: true } });
    expect(seatFor(response, 'CAMERA').mine).toBe(true);
  });
});

describe('renewalIntervalMs', () => {
  it('derives the default 15s TTL down to a 5s heartbeat', () => {
    expect(renewalIntervalMs(15_000)).toBe(5_000);
  });

  it('rounds a non-multiple-of-3000 TTL to the nearest whole second — PollScheduler requires it', () => {
    expect(renewalIntervalMs(10_000)).toBe(3_000); // 3333.33.. rounds to 3000
  });

  it('floors at 1s so a very short TTL never produces a zero or negative period', () => {
    expect(renewalIntervalMs(1_000)).toBe(1_000); // 333.33.. would round to 0 unfloored
    expect(renewalIntervalMs(0)).toBe(1_000);
  });

  it('never hard-codes a cadence — it is purely a function of the served ttlMs', () => {
    expect(renewalIntervalMs(30_000)).toBe(10_000);
    expect(renewalIntervalMs(6_000)).toBe(2_000);
  });
});
