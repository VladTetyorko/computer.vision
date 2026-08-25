import { describe, expect, it } from 'vitest';
import {
  GUIDED_OFF,
  SETTLE_TICKS,
  advanceGuided,
  beginGuided,
  currentStep,
  guidedSteps,
  isGuidedDone,
  skipGuided,
  type GuidedState,
  type GuidedStep,
} from './guided-setup';
import type { ControlFunction, ControlProfile } from '../api/models';

const LABELS = new Map<ControlFunction, string>([
  ['STEERING', 'Steering'],
  ['THROTTLE', 'Throttle'],
]);

function binding(fn: ControlFunction, sourceIndex: number, rcChannel: number, centerMicros: number) {
  return {
    source: 'AXIS' as const,
    kind: 'AXIS' as const,
    function: fn,
    sourceIndex,
    rcChannel,
    minMicros: 1000,
    centerMicros,
    maxMicros: 2000,
    deadband: 0,
    reversed: false,
  };
}

const ROVER = {
  id: 'built-in-rover',
  source: 'BUILT_IN',
  kind: 'ROVER',
  code: 'S-T-',
  name: 'Ground vehicle',
  active: true,
  channelMap: [binding('THROTTLE', 2, 3, 1500), binding('STEERING', 0, 1, 1500)],
  actionMap: [],
} as unknown as ControlProfile;

const STEPS = guidedSteps(ROVER, LABELS);

function frames(state: GuidedState, readings: { axes: number[]; buttons: number[] }, count: number): GuidedState {
  let next = state;
  for (let i = 0; i < count; i += 1) {
    next = advanceGuided(next, readings).state;
  }
  return next;
}

describe('guidedSteps', () => {
  it('asks in channel order, not in whichever order the bindings arrived', () => {
    expect(STEPS.map((s) => s.function)).toEqual(['STEERING', 'THROTTLE']);
  });

  it('carries the channel and travel the built-in already uses, so nothing is invented here', () => {
    expect(STEPS[1]).toEqual({ function: 'THROTTLE', label: 'Throttle', rcChannel: 3, travel: 'CENTERED' });
  });

  it('reads travel off the microseconds, exactly as the domain derives it', () => {
    const copter = { ...ROVER, channelMap: [binding('THROTTLE', 2, 3, 1000)] } as ControlProfile;

    expect(guidedSteps(copter, LABELS)[0].travel).toBe('UNIDIRECTIONAL');
  });

  it('falls back to the function name when the catalogue has no label for it', () => {
    expect(guidedSteps(ROVER, new Map())[0].label).toBe('STEERING');
  });

  it('has nothing to ask about a profile with no channel bindings', () => {
    expect(guidedSteps(undefined, LABELS)).toEqual([]);
  });
});

describe('beginGuided', () => {
  it('refuses to start a run with no questions in it', () => {
    expect(beginGuided([], { axes: [0], buttons: [] })).toBe(GUIDED_OFF);
  });

  it('opens on the first step, watching', () => {
    const state = beginGuided(STEPS, { axes: [0, 0, 0], buttons: [] });

    expect(state.phase).toBe('watch');
    expect(currentStep(state)?.function).toBe('STEERING');
  });
});

describe('advanceGuided', () => {
  it('binds whichever control moved to the step on screen', () => {
    const state = beginGuided(STEPS, { axes: [0, 0, 0], buttons: [] });

    const { learned } = advanceGuided(state, { axes: [0, 0, 0.9], buttons: [] });

    expect(learned).toEqual({ source: 'AXIS', sourceIndex: 2, step: STEPS[0] });
  });

  it('ignores jitter that is not a deliberate movement', () => {
    const state = beginGuided(STEPS, { axes: [0, 0, 0], buttons: [] });

    expect(advanceGuided(state, { axes: [0.02, -0.01, 0], buttons: [] }).learned).toBeUndefined();
  });

  it('will not read a stick springing back as the answer to the next question', () => {
    const start = beginGuided(STEPS, { axes: [0, 0, 0], buttons: [] });
    const answered = advanceGuided(start, { axes: [0, 0, 0.9], buttons: [] }).state;

    // The operator lets go: the stick returns to centre, which is a 0.9 swing.
    const springBack = advanceGuided(answered, { axes: [0, 0, 0], buttons: [] });

    expect(springBack.learned).toBeUndefined();
    expect(springBack.state.phase).toBe('settle');
  });

  it('watches again once the transmitter has been still long enough', () => {
    const start = beginGuided(STEPS, { axes: [0, 0, 0], buttons: [] });
    const answered = advanceGuided(start, { axes: [0, 0, 0.9], buttons: [] }).state;
    const settled = frames(answered, { axes: [0, 0, 0], buttons: [] }, SETTLE_TICKS + 1);

    expect(settled.phase).toBe('watch');
    expect(currentStep(settled)?.function).toBe('THROTTLE');
  });

  it('answers the second step from the settled baseline, not from the first step’s', () => {
    const start = beginGuided(STEPS, { axes: [0, 0, 0], buttons: [] });
    const answered = advanceGuided(start, { axes: [0, 0, 0.9], buttons: [] }).state;
    const settled = frames(answered, { axes: [0, 0, 0], buttons: [] }, SETTLE_TICKS + 1);

    const second = advanceGuided(settled, { axes: [-0.8, 0, 0], buttons: [] });

    expect(second.learned).toEqual({ source: 'AXIS', sourceIndex: 0, step: STEPS[1] });
  });

  it('is done after the last step, and asks nothing more', () => {
    let state = beginGuided(STEPS, { axes: [0, 0, 0], buttons: [] });
    state = advanceGuided(state, { axes: [0, 0, 0.9], buttons: [] }).state;
    state = frames(state, { axes: [0, 0, 0], buttons: [] }, SETTLE_TICKS + 1);
    state = advanceGuided(state, { axes: [-0.8, 0, 0], buttons: [] }).state;

    expect(isGuidedDone(state)).toBe(true);
    expect(currentStep(state)).toBeUndefined();
    expect(advanceGuided(state, { axes: [0, 0, 0.9], buttons: [] }).learned).toBeUndefined();
  });

  it('does nothing at all when the run is off', () => {
    expect(advanceGuided(GUIDED_OFF, { axes: [1], buttons: [] })).toEqual({ state: GUIDED_OFF });
  });
});

describe('skipGuided', () => {
  it('moves on without binding anything', () => {
    const state = beginGuided(STEPS, { axes: [0, 0, 0], buttons: [] });

    const skipped = skipGuided(state, { axes: [0, 0, 0], buttons: [] });

    expect(skipped.index).toBe(1);
    expect(skipped.phase).toBe('settle');
  });

  it('cannot skip past the end', () => {
    const done = { ...beginGuided(STEPS, { axes: [0], buttons: [] }), index: STEPS.length } as GuidedState;

    expect(skipGuided(done, { axes: [0], buttons: [] })).toBe(done);
  });
});

describe('a step', () => {
  it('carries everything a binding needs, so the page writes no defaults of its own', () => {
    const step: GuidedStep = STEPS[0];

    expect(Object.keys(step).sort()).toEqual(['function', 'label', 'rcChannel', 'travel']);
  });
});
