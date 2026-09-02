import { describe, expect, it } from 'vitest';
import { neutralGateReason, neutralOffenderReason, worstNeutralOffender } from './neutral-gate-logic';
import type { ManualControlChannelBinding } from '../api/models';

const axis = (
  fn: ManualControlChannelBinding['function'],
  travel: ManualControlChannelBinding['travel'],
  sourceIndex: number,
  label: string,
): ManualControlChannelBinding => ({
  source: 'AXIS',
  kind: 'AXIS',
  function: fn,
  travel,
  sourceIndex,
  rcChannel: sourceIndex + 1,
  minMicros: 1000,
  centerMicros: 1500,
  maxMicros: 2000,
  label,
});

const STEERING = axis('STEERING', 'CENTERED', 0, 'Steering');
const ROVER_THROTTLE = axis('THROTTLE', 'CENTERED', 1, 'Throttle');
const COPTER_THROTTLE = axis('THROTTLE', 'UNIDIRECTIONAL', 1, 'Throttle');
const ARM_SWITCH: ManualControlChannelBinding = {
  ...axis('AUX_1', 'CENTERED', 2, 'Sw 1'),
  source: 'BUTTON',
  kind: 'BUTTON',
};

describe('worstNeutralOffender', () => {
  it('is undefined with every bound axis at rest', () => {
    const map = [STEERING, ROVER_THROTTLE];
    expect(worstNeutralOffender(map, [0, 0], 5)).toBeUndefined();
  });

  it('is undefined with no axis bindings at all — nothing to be neutral about', () => {
    expect(worstNeutralOffender([], [], 5)).toBeUndefined();
  });

  it('ignores a non-AXIS binding (a switch has no neutral)', () => {
    const map = [STEERING, ARM_SWITCH];
    expect(worstNeutralOffender(map, [0, 1], 5)).toBeUndefined();
  });

  it('ignores an unbound (missing) axis index rather than treating it as off', () => {
    // axes[1] is absent entirely — the function must not read `undefined` as a deflection.
    expect(worstNeutralOffender([STEERING], [0], 5)).toBeUndefined();
  });

  it('flags a CENTERED axis outside tolerance, exactly at the boundary still reads neutral (inclusive)', () => {
    // 5% tolerance on a CENTERED axis: displayPercent 55 is exactly |55-50|=5, still inclusive-neutral.
    expect(worstNeutralOffender([STEERING], [0.1], 5)).toBeUndefined(); // (0.1+1)*50 = 55
    // Nudge one hair past it.
    const offender = worstNeutralOffender([STEERING], [0.11], 5);
    expect(offender?.binding.label).toBe('Steering');
  });

  it('flags a UNIDIRECTIONAL throttle above tolerance, exactly at the boundary still reads neutral', () => {
    // COPTER_THROTTLE reads `sourceIndex` 1 (mirroring a real rover/copter map where index 0 is
    // steering/roll) — the `axes` array must be shaped to match, index 0 is an unclaimed filler.
    expect(worstNeutralOffender([COPTER_THROTTLE], [0, 0.05], 5)).toBeUndefined(); // displayPercent 5
    const offender = worstNeutralOffender([COPTER_THROTTLE], [0, 0.06], 5);
    expect(offender?.binding.label).toBe('Throttle');
  });

  it('picks the worst offender by proportional deviation, not raw display-percent distance', () => {
    // Steering at displayPercent 80 (deviation 30 of a 50 half-range -> 60% off).
    // Throttle (unidirectional) at displayPercent 40 (deviation 40 of a 100 full-range -> 40% off).
    // Raw deviation would wrongly favor throttle (40 > 30); proportional favors steering (60% > 40%).
    const map = [STEERING, COPTER_THROTTLE];
    const offender = worstNeutralOffender(map, [0.6, 0.4], 5); // steering (0.6+1)*50=80
    expect(offender?.binding.label).toBe('Steering');
  });
});

describe('neutralOffenderReason', () => {
  it('names the CENTERED axis with its live value and the generic "center sticks" instruction', () => {
    const reason = neutralOffenderReason({ binding: STEERING, value: 0.24 }); // (0.24+1)*50 = 62
    expect(reason).toBe('Steering 62% — center sticks to arm');
  });

  it('names a UNIDIRECTIONAL throttle with the literal "throttle to zero" instruction', () => {
    const reason = neutralOffenderReason({ binding: COPTER_THROTTLE, value: 0.34 }); // 34%
    expect(reason).toBe('Throttle 34% — throttle to zero to arm');
  });
});

describe('neutralGateReason', () => {
  // ROVER_THROTTLE reads `sourceIndex` 1 (mirroring a real rover map where index 0 is steering) —
  // every `axes` array below is shaped to match, index 0 an unclaimed filler.
  const map = [ROVER_THROTTLE];

  it('is undefined with no live session at all — idle', () => {
    expect(neutralGateReason('idle', map, [0, 0.5], 5)).toBeUndefined();
  });

  it('is undefined once denied/released — the same "no session, no gate" rule', () => {
    expect(neutralGateReason('denied', map, [0, 0.5], 5)).toBeUndefined();
    expect(neutralGateReason('released', map, [0, 0.5], 5)).toBeUndefined();
  });

  it('gates while engaging, not only once fully engaged', () => {
    expect(neutralGateReason('engaging', map, [0, 0.5], 5)).toBe('Throttle 75% — center sticks to arm');
  });

  it('gates while engaged with an off-neutral axis', () => {
    expect(neutralGateReason('engaged', map, [0, 0.5], 5)).toBe('Throttle 75% — center sticks to arm');
  });

  it('is undefined while engaged with every axis neutral', () => {
    expect(neutralGateReason('engaged', map, [0, 0], 5)).toBeUndefined();
  });
});
