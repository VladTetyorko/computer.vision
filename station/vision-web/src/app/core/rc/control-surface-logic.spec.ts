import { describe, expect, it } from 'vitest';
import type { ManualControlChannelBinding } from '../api/models';
import {
  KEY_STEP,
  REST_VALUE,
  axesFrom,
  displayPercent,
  displayPercentFor,
  fractionAlong,
  knobLeftPercent,
  knobTopPercent,
  nudge,
  padsFrom,
  profileCaveat,
  springsBack,
  valueFromFraction,
} from './control-surface-logic';

const binding = (
  fn: ManualControlChannelBinding['function'],
  travel: ManualControlChannelBinding['travel'],
  sourceIndex: number,
  rcChannel: number,
  label: string,
): ManualControlChannelBinding => ({
  source: 'AXIS',
  kind: 'AXIS',
  function: fn,
  travel,
  sourceIndex,
  rcChannel,
  minMicros: 1000,
  centerMicros: travel === 'CENTERED' ? 1500 : 1000,
  maxMicros: 2000,
  label,
});

const ROLL = binding('ROLL', 'CENTERED', 0, 1, 'Roll');
const PITCH = binding('PITCH', 'CENTERED', 1, 2, 'Pitch');
const YAW = binding('YAW', 'CENTERED', 3, 4, 'Yaw');
const COPTER_THROTTLE = binding('THROTTLE', 'UNIDIRECTIONAL', 2, 3, 'Throttle');
const STEERING = binding('STEERING', 'CENTERED', 0, 1, 'Steering');
const ROVER_THROTTLE = binding('THROTTLE', 'CENTERED', 2, 3, 'Throttle');

const COPTER_MAP = [ROLL, PITCH, COPTER_THROTTLE, YAW];
const ROVER_MAP = [STEERING, ROVER_THROTTLE];

describe('padsFrom', () => {
  it('gives an aircraft two pads, mode-2 style: yaw/throttle and roll/pitch', () => {
    const pads = padsFrom(COPTER_MAP);
    expect(pads.map((p) => p.id)).toEqual(['YAW-THROTTLE', 'ROLL-PITCH']);
    expect(pads[0].label).toBe('Throttle / Yaw');
    expect(pads[1].label).toBe('Pitch / Roll');
  });

  it('gives a ground vehicle exactly one pad — steer across, drive up and down', () => {
    const pads = padsFrom(ROVER_MAP);
    expect(pads.length).toBe(1);
    expect(pads[0].x).toBe(STEERING);
    expect(pads[0].y).toBe(ROVER_THROTTLE);
  });

  it('renders an unclaimed control on a pad of its own rather than dropping it', () => {
    const pads = padsFrom([ROLL]);
    expect(pads.length).toBe(1);
    expect(pads[0].x).toBe(ROLL);
    expect(pads[0].y).toBeUndefined();
  });

  it('skips buttons — no profile binds one', () => {
    const button: ManualControlChannelBinding = { ...ROLL, source: 'BUTTON' };
    expect(padsFrom([button])).toEqual([]);
  });
});

describe('springsBack', () => {
  it('springs a centred control back, so a released steering stick straightens and a rover stops', () => {
    expect(springsBack(STEERING)).toBe(true);
    expect(springsBack(ROVER_THROTTLE)).toBe(true);
  });

  it('holds a unidirectional throttle where it was left — springing it to idle would drop the aircraft', () => {
    expect(springsBack(COPTER_THROTTLE)).toBe(false);
  });
});

describe('displayPercent — the operator reads 0-100', () => {
  it("reads a drone's throttle straight through: rest is 0, full is 100", () => {
    expect(displayPercent(COPTER_THROTTLE, REST_VALUE)).toBe(0);
    expect(displayPercent(COPTER_THROTTLE, 0.5)).toBe(50);
    expect(displayPercent(COPTER_THROTTLE, 1)).toBe(100);
  });

  it("reads a car's throttle as 50 at stop, below 50 reverse, above 50 forward", () => {
    expect(displayPercent(ROVER_THROTTLE, REST_VALUE)).toBe(50);
    expect(displayPercent(ROVER_THROTTLE, -1)).toBe(0);
    expect(displayPercent(ROVER_THROTTLE, 1)).toBe(100);
  });

  it('clamps rather than reporting an out-of-range value it could never have produced', () => {
    expect(displayPercent(COPTER_THROTTLE, -0.5)).toBe(0);
    expect(displayPercent(ROVER_THROTTLE, 3)).toBe(100);
  });
});

describe('displayPercentFor — the same math, before a binding exists', () => {
  it('agrees with displayPercent for the same travel', () => {
    expect(displayPercentFor('UNIDIRECTIONAL', REST_VALUE)).toBe(displayPercent(COPTER_THROTTLE, REST_VALUE));
    expect(displayPercentFor('UNIDIRECTIONAL', 1)).toBe(displayPercent(COPTER_THROTTLE, 1));
    expect(displayPercentFor('CENTERED', REST_VALUE)).toBe(displayPercent(ROVER_THROTTLE, REST_VALUE));
    expect(displayPercentFor('CENTERED', -1)).toBe(displayPercent(ROVER_THROTTLE, -1));
  });
});

describe('knob position', () => {
  it('is the same quantity as the readout, so the two cannot drift apart', () => {
    expect(knobLeftPercent(STEERING, 0)).toBe(displayPercent(STEERING, 0));
  });

  it('puts a full control at the top — screen Y grows downward', () => {
    expect(knobTopPercent(COPTER_THROTTLE, 1)).toBe(0);
    expect(knobTopPercent(COPTER_THROTTLE, REST_VALUE)).toBe(100);
  });

  it('centres an unbound axis, so a single-control pad still renders', () => {
    expect(knobLeftPercent(undefined, 0)).toBe(50);
    expect(knobTopPercent(undefined, 0)).toBe(50);
  });
});

describe('valueFromFraction', () => {
  it('maps a pointer across the full travel of each kind of control', () => {
    expect(valueFromFraction(COPTER_THROTTLE, 0)).toBe(0);
    expect(valueFromFraction(COPTER_THROTTLE, 1)).toBe(1);
    expect(valueFromFraction(ROVER_THROTTLE, 0)).toBe(-1);
    expect(valueFromFraction(ROVER_THROTTLE, 0.5)).toBe(0);
    expect(valueFromFraction(ROVER_THROTTLE, 1)).toBe(1);
  });

  it('clamps a pointer dragged past the pad', () => {
    expect(valueFromFraction(ROVER_THROTTLE, 2)).toBe(1);
    expect(valueFromFraction(COPTER_THROTTLE, -1)).toBe(0);
  });
});

describe('fractionAlong', () => {
  it('reads a pointer position as a fraction of the pad', () => {
    expect(fractionAlong(150, 100, 200)).toBe(0.25);
    expect(fractionAlong(50, 100, 200)).toBe(0);
    expect(fractionAlong(500, 100, 200)).toBe(1);
  });

  it('centres on a zero-sized pad rather than dividing by zero', () => {
    expect(fractionAlong(10, 0, 0)).toBe(0.5);
  });
});

describe('nudge', () => {
  it('steps a fraction of full travel, which is twice as much on a centred control', () => {
    expect(nudge(COPTER_THROTTLE, 0.5, KEY_STEP)).toBeCloseTo(0.5 + KEY_STEP);
    expect(nudge(ROVER_THROTTLE, 0, KEY_STEP)).toBeCloseTo(KEY_STEP * 2);
  });

  it('cannot be nudged below idle, or past a centred control’s stops', () => {
    expect(nudge(COPTER_THROTTLE, 0, -KEY_STEP)).toBe(0);
    expect(nudge(ROVER_THROTTLE, -1, -KEY_STEP)).toBe(-1);
    expect(nudge(ROVER_THROTTLE, 1, KEY_STEP)).toBe(1);
  });
});

describe('axesFrom', () => {
  it('places each value at the index the server said it reads from', () => {
    const values = new Map([
      [0, -0.5],
      [2, 0.75],
    ]);
    expect(axesFrom(values, ROVER_MAP)).toEqual([-0.5, REST_VALUE, 0.75]);
  });

  it('fills an unbound slot with rest, never leaving a hole to reach the backend as a real reading', () => {
    expect(axesFrom(new Map(), COPTER_MAP)).toEqual([0, 0, 0, 0]);
  });

  it('is empty when nothing is bound', () => {
    expect(axesFrom(new Map(), [])).toEqual([]);
  });
});

describe('profileCaveat', () => {
  it('warns only for an unrecognised vehicle, which binds nothing and is refused control', () => {
    expect(profileCaveat('UNKNOWN')).toContain('refused');
    expect(profileCaveat('COPTER')).toBeUndefined();
    expect(profileCaveat('ROVER')).toBeUndefined();
    expect(profileCaveat('PLANE')).toBeUndefined();
  });
});
