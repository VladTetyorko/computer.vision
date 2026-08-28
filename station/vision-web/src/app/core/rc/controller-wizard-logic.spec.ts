import { describe, expect, it } from 'vitest';
import {
  actionStepDefaults,
  actionStepKindOf,
  actionsForStep,
  applyActionStep,
  applyChannelStep,
  channelStepDefaults,
  detectedControl,
  observeInputs,
  questionsFor,
  reviewChecks,
  stepStatus,
  wizardSteps,
  type DetectSample,
  type WizardStep,
} from './controller-wizard-logic';
import type { ControlCatalog, ControlProfile } from '../api/models';
import { blankControlDraft, type ControlDraft, type ProfileDraft } from './controller-setup-logic';

const CATALOG: ControlCatalog = {
  vehicleKinds: [
    { name: 'ROVER', label: 'Rover' },
    { name: 'COPTER', label: 'Multirotor' },
  ],
  inputKinds: [
    { name: 'AXIS', label: 'Axis', sources: ['AXIS'], positions: [] },
    { name: 'BUTTON', label: 'Button', sources: ['BUTTON'], positions: ['LOW', 'HIGH'] },
    { name: 'SWITCH_2', label: '2-position switch', sources: ['AXIS', 'BUTTON'], positions: ['LOW', 'HIGH'] },
    { name: 'SWITCH_3', label: '3-position switch', sources: ['AXIS'], positions: ['LOW', 'MIDDLE', 'HIGH'] },
  ],
  positions: [
    { name: 'LOW', label: 'Low', level: 0 },
    { name: 'MIDDLE', label: 'Middle', level: 1 },
    { name: 'HIGH', label: 'High', level: 2 },
  ],
  functions: [
    { name: 'THROTTLE', label: 'Throttle' },
    { name: 'YAW', label: 'Yaw' },
    { name: 'STEERING', label: 'Steering' },
    { name: 'PITCH', label: 'Pitch' },
    { name: 'ROLL', label: 'Roll' },
  ],
  actions: [
    { name: 'ARM', label: 'Arm', parameter: 'NONE', dangerous: true },
    { name: 'DISARM', label: 'Disarm', parameter: 'NONE', dangerous: false },
    { name: 'TOGGLE_ARM', label: 'Toggle arm', parameter: 'NONE', dangerous: true },
    { name: 'EMERGENCY_STOP', label: 'Emergency stop', parameter: 'NONE', dangerous: true },
    { name: 'RETURN_TO_HOME', label: 'Return home', parameter: 'NONE', dangerous: false },
    { name: 'SET_MODE', label: 'Set mode', parameter: 'MODE_NAME', dangerous: false },
    { name: 'AUX_FUNCTION', label: 'Aux function', parameter: 'AUX_FUNCTION', dangerous: false },
  ],
  auxFunctions: [{ number: 19, label: 'Gripper' }],
};

const ROVER_BUILTIN: ControlProfile = {
  id: 'builtin-rover',
  source: 'BUILT_IN',
  kind: 'ROVER',
  code: 'S-T-',
  name: 'Ground vehicle',
  active: true,
  channelMap: [
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'STEERING',
      sourceIndex: 0,
      rcChannel: 1,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    },
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'THROTTLE',
      sourceIndex: 2,
      rcChannel: 3,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    },
  ],
  actionMap: [],
};

const COPTER_BUILTIN: ControlProfile = {
  id: 'builtin-copter',
  source: 'BUILT_IN',
  kind: 'COPTER',
  code: 'AETR',
  name: 'Multirotor',
  active: true,
  channelMap: [
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'ROLL',
      sourceIndex: 0,
      rcChannel: 1,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    },
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'PITCH',
      sourceIndex: 1,
      rcChannel: 2,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    },
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'THROTTLE',
      sourceIndex: 2,
      rcChannel: 3,
      minMicros: 1000,
      centerMicros: 1000,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    },
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'YAW',
      sourceIndex: 3,
      rcChannel: 4,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    },
  ],
  actionMap: [],
};

const UNKNOWN_BUILTIN: ControlProfile = {
  ...COPTER_BUILTIN,
  id: 'builtin-unknown',
  kind: 'UNKNOWN',
  code: 'AETR?',
  name: 'Unrecognised vehicle',
  channelMap: COPTER_BUILTIN.channelMap.map((b) =>
    b.function === 'THROTTLE' ? { ...b, centerMicros: 1500 } : b,
  ),
};

function draft(controls: readonly ControlDraft[], kind: ProfileDraft['kind'] = 'ROVER'): ProfileDraft {
  return { id: 'p1', kind, name: 'Bench layout', controls };
}

describe('wizardSteps', () => {
  it('lists a rover as throttle, steering, then the fixed tail', () => {
    const steps = wizardSteps('ROVER', ROVER_BUILTIN, CATALOG);

    expect(steps.map((s) => s.title)).toEqual(['Throttle', 'Steering', 'Arm', 'Mode', 'Extras', 'Review']);
    expect(steps.map((s) => s.kind)).toEqual(['CHANNEL', 'CHANNEL', 'ARM', 'MODE', 'EXTRAS', 'REVIEW']);
  });

  it('lists a copter as throttle, yaw, pitch, roll, then the fixed tail', () => {
    const steps = wizardSteps('COPTER', COPTER_BUILTIN, CATALOG);

    expect(steps.map((s) => s.title)).toEqual([
      'Throttle',
      'Yaw',
      'Pitch',
      'Roll',
      'Arm',
      'Mode',
      'Extras',
      'Review',
    ]);
  });

  it('carries the built-in channel and travel forward as each step default', () => {
    const throttle = wizardSteps('ROVER', ROVER_BUILTIN, CATALOG).find((s) => s.function === 'THROTTLE');

    expect(throttle?.defaultChannel).toBe(3);
    expect(throttle?.defaultTravel).toBe('CENTERED');
  });

  it('has no channel steps but still the fixed tail when the built-in has not loaded', () => {
    const steps = wizardSteps('ROVER', undefined, CATALOG);

    expect(steps.map((s) => s.kind)).toEqual(['ARM', 'MODE', 'EXTRAS', 'REVIEW']);
  });

  it('caveats every channel step for an unrecognised vehicle, and only those steps', () => {
    const steps = wizardSteps('UNKNOWN', UNKNOWN_BUILTIN, CATALOG);
    const channelSteps = steps.filter((s) => s.kind === 'CHANNEL');

    expect(channelSteps.length).toBeGreaterThan(0);
    channelSteps.forEach((s) => expect(s.caveat).toMatch(/has not reported what kind/));
    expect(steps.find((s) => s.kind === 'MODE')?.caveat).toBeUndefined();
  });

  it('never caveats a recognised kind\'s channel steps', () => {
    const steps = wizardSteps('ROVER', ROVER_BUILTIN, CATALOG).filter((s) => s.kind === 'CHANNEL');

    steps.forEach((s) => expect(s.caveat).toBeUndefined());
  });

  it('always warns on the arm step that a switch needs a hold', () => {
    const arm = wizardSteps('ROVER', ROVER_BUILTIN, CATALOG).find((s) => s.kind === 'ARM');

    expect(arm?.caveat).toMatch(/hold/);
    expect(arm?.instruction).toBe('Flip the switch you want to arm with.');
  });
});

describe('stepStatus', () => {
  const throttleStep: WizardStep = {
    id: 'channel-throttle',
    kind: 'CHANNEL',
    title: 'Throttle',
    instruction: '',
    function: 'THROTTLE',
    defaultChannel: 3,
    defaultTravel: 'UNIDIRECTIONAL',
  };
  const armStep: WizardStep = { id: 'arm', kind: 'ARM', title: 'Arm', instruction: '' };
  const modeStep: WizardStep = { id: 'mode', kind: 'MODE', title: 'Mode', instruction: '' };
  const extrasStep: WizardStep = { id: 'extras', kind: 'EXTRAS', title: 'Extras', instruction: '' };
  const reviewStep: WizardStep = { id: 'review', kind: 'REVIEW', title: 'Review', instruction: '' };

  it('is open for a channel step nothing drives that function yet', () => {
    expect(stepStatus(throttleStep, draft([]))).toBe('open');
  });

  it('is done once some control drives the step\'s function', () => {
    const control: ControlDraft = { ...blankControlDraft('AXIS', 2, draft([])), role: 'CHANNEL', function: 'THROTTLE' };

    expect(stepStatus(throttleStep, draft([control]))).toBe('done');
  });

  it('is done for arm once any position fires ARM or TOGGLE_ARM', () => {
    const control: ControlDraft = {
      ...blankControlDraft('BUTTON', 1, draft([])),
      positions: [{ position: 'HIGH', action: 'TOGGLE_ARM', parameter: null }],
    };

    expect(stepStatus(armStep, draft([control]))).toBe('done');
    expect(stepStatus(armStep, draft([]))).toBe('open');
  });

  it('is done for mode once any position fires SET_MODE', () => {
    const control: ControlDraft = {
      ...blankControlDraft('BUTTON', 1, draft([])),
      positions: [{ position: 'HIGH', action: 'SET_MODE', parameter: 'HOLD' }],
    };

    expect(stepStatus(modeStep, draft([control]))).toBe('done');
  });

  it('is done for extras on emergency stop, return home, or aux function', () => {
    const control: ControlDraft = {
      ...blankControlDraft('BUTTON', 1, draft([])),
      positions: [{ position: 'HIGH', action: 'RETURN_TO_HOME', parameter: null }],
    };

    expect(stepStatus(extrasStep, draft([control]))).toBe('done');
  });

  it('review is never done', () => {
    const control: ControlDraft = {
      ...blankControlDraft('BUTTON', 1, draft([])),
      positions: [{ position: 'HIGH', action: 'ARM', parameter: null }],
    };

    expect(stepStatus(reviewStep, draft([control]))).toBe('open');
  });
});

describe('observeInputs + detectedControl', () => {
  it('picks the control that travelled the furthest and ignores idle jitter', () => {
    let samples: ReadonlyMap<string, DetectSample> = new Map();
    samples = observeInputs(samples, [0, 0], [0]);
    samples = observeInputs(samples, [0.03, 0.9], [0]);
    samples = observeInputs(samples, [-0.02, -0.8], [0]);

    const winner = detectedControl(samples, 'CHANNEL');

    expect(winner?.source).toBe('AXIS');
    expect(winner?.sourceIndex).toBe(1);
    expect(winner?.travelled).toBeCloseTo(1.7, 5);
  });

  it('reports undefined when nothing has moved past the noise floor', () => {
    let samples: ReadonlyMap<string, DetectSample> = new Map();
    samples = observeInputs(samples, [0, 0.01], [0]);
    samples = observeInputs(samples, [-0.02, 0.03], [0.01]);

    expect(detectedControl(samples, 'CHANNEL')).toBeUndefined();
  });

  it('for a CHANNEL purpose, reports the winner\'s own source as its kind', () => {
    let samples: ReadonlyMap<string, DetectSample> = new Map();
    samples = observeInputs(samples, [], [0]);
    samples = observeInputs(samples, [], [1]);

    const winner = detectedControl(samples, 'CHANNEL');

    expect(winner?.source).toBe('BUTTON');
    expect(winner?.inputKind).toBe('BUTTON');
  });

  it('for an ACTION purpose, guesses SWITCH_3 for an axis that visits both ends', () => {
    let samples: ReadonlyMap<string, DetectSample> = new Map();
    samples = observeInputs(samples, [0], []);
    samples = observeInputs(samples, [-1], []);
    samples = observeInputs(samples, [1], []);

    expect(detectedControl(samples, 'ACTION')?.inputKind).toBe('SWITCH_3');
  });

  it('for an ACTION purpose, guesses SWITCH_2 for an axis that only reaches one end', () => {
    let samples: ReadonlyMap<string, DetectSample> = new Map();
    samples = observeInputs(samples, [0], []);
    samples = observeInputs(samples, [1], []);

    expect(detectedControl(samples, 'ACTION')?.inputKind).toBe('SWITCH_2');
  });

  it('for an ACTION purpose, a button is always BUTTON, never a switch guess', () => {
    let samples: ReadonlyMap<string, DetectSample> = new Map();
    samples = observeInputs(samples, [], [0]);
    samples = observeInputs(samples, [], [1]);

    expect(detectedControl(samples, 'ACTION')?.inputKind).toBe('BUTTON');
  });
});

describe('channelStepDefaults', () => {
  const step: WizardStep = {
    id: 'channel-throttle',
    kind: 'CHANNEL',
    title: 'Throttle',
    instruction: '',
    function: 'THROTTLE',
    defaultChannel: 3,
    defaultTravel: 'UNIDIRECTIONAL',
  };

  it('reverses when the detected control\'s first excursion was negative', () => {
    const samples = new Map<string, DetectSample>([
      ['AXIS:5', { source: 'AXIS', sourceIndex: 5, min: -1, max: 0.1, firstSign: -1 }],
    ]);

    const defaults = channelStepDefaults(step, { source: 'AXIS', sourceIndex: 5, inputKind: 'AXIS', travelled: 1.1 }, samples);

    expect(defaults).toEqual({ reversed: true, travel: 'UNIDIRECTIONAL', rcChannel: 3 });
  });

  it('does not reverse when the first excursion was positive', () => {
    const samples = new Map<string, DetectSample>([
      ['AXIS:5', { source: 'AXIS', sourceIndex: 5, min: -0.1, max: 1, firstSign: 1 }],
    ]);

    expect(
      channelStepDefaults(step, { source: 'AXIS', sourceIndex: 5, inputKind: 'AXIS', travelled: 1.1 }, samples).reversed,
    ).toBe(false);
  });

  it('falls back to the built-in\'s own channel and travel, un-reversed, when nothing is detected yet', () => {
    expect(channelStepDefaults(step, undefined, new Map())).toEqual({
      reversed: false,
      travel: 'UNIDIRECTIONAL',
      rcChannel: 3,
    });
  });
});

describe('actionStepDefaults', () => {
  const armStep: WizardStep = { id: 'arm', kind: 'ARM', title: 'Arm', instruction: '' };
  const modeStep: WizardStep = { id: 'mode', kind: 'MODE', title: 'Mode', instruction: '' };

  it('defaults a 3-position switch to LOW disarm, HIGH arm, and leaves MIDDLE unbound', () => {
    expect(actionStepDefaults(armStep, 'SWITCH_3', CATALOG)).toEqual([
      { position: 'LOW', action: 'DISARM', parameter: null },
      { position: 'HIGH', action: 'ARM', parameter: null },
    ]);
  });

  it('defaults a 2-position switch to LOW disarm, HIGH arm', () => {
    expect(actionStepDefaults(armStep, 'SWITCH_2', CATALOG)).toEqual([
      { position: 'LOW', action: 'DISARM', parameter: null },
      { position: 'HIGH', action: 'ARM', parameter: null },
    ]);
  });

  it('defaults a button the same way — LOW disarm (released), HIGH arm (pressed)', () => {
    expect(actionStepDefaults(armStep, 'BUTTON', CATALOG)).toEqual([
      { position: 'LOW', action: 'DISARM', parameter: null },
      { position: 'HIGH', action: 'ARM', parameter: null },
    ]);
  });

  it('has no default for mode — there is no safe flight mode to guess', () => {
    expect(actionStepDefaults(modeStep, 'SWITCH_3', CATALOG)).toEqual([]);
  });
});

describe('questionsFor', () => {
  it('always offers the direction tiles', () => {
    expect(questionsFor('YAW').direction.map((t) => t.label)).toEqual(['Up is more', 'Up is less']);
    expect(questionsFor('THROTTLE').direction.map((t) => t.label)).toEqual(['Up is more', 'Up is less']);
  });

  it('offers rests tiles only for throttle', () => {
    expect(questionsFor('THROTTLE').rests.map((t) => t.label)).toEqual([
      'At the bottom — idle is 0 %',
      'In the centre — centre is stop',
    ]);
    expect(questionsFor('YAW').rests).toEqual([]);
    expect(questionsFor('STEERING').rests).toEqual([]);
  });
});

describe('applyChannelStep', () => {
  const throttleStep: WizardStep = {
    id: 'channel-throttle',
    kind: 'CHANNEL',
    title: 'Throttle',
    instruction: '',
    function: 'THROTTLE',
    defaultChannel: 3,
    defaultTravel: 'UNIDIRECTIONAL',
  };

  it('replaces the built-in\'s throttle binding when a different control is detected', () => {
    const builtInThrottle: ControlDraft = {
      ...blankControlDraft('AXIS', 2, draft([])),
      role: 'CHANNEL',
      function: 'THROTTLE',
      rcChannel: 3,
      travel: 'UNIDIRECTIONAL',
      reversed: false,
    };
    const before = draft([builtInThrottle], 'COPTER');

    const after = applyChannelStep(
      before,
      throttleStep,
      { source: 'AXIS', sourceIndex: 5, inputKind: 'AXIS', travelled: 1.4 },
      { reversed: false, travel: 'UNIDIRECTIONAL', rcChannel: 3 },
    );

    expect(after.controls).toHaveLength(1);
    expect(after.controls[0]).toMatchObject({ source: 'AXIS', sourceIndex: 5, role: 'CHANNEL', function: 'THROTTLE' });
  });

  it('re-roles an existing row for the detected control instead of duplicating it', () => {
    const existingAction: ControlDraft = {
      ...blankControlDraft('AXIS', 5, draft([])),
      role: 'ACTIONS',
      positions: [{ position: 'HIGH', action: 'ARM', parameter: null }],
    };
    const before = draft([existingAction]);

    const after = applyChannelStep(
      before,
      throttleStep,
      { source: 'AXIS', sourceIndex: 5, inputKind: 'AXIS', travelled: 1.4 },
      { reversed: true, travel: 'UNIDIRECTIONAL', rcChannel: 3 },
    );

    expect(after.controls).toHaveLength(1);
    expect(after.controls[0]).toMatchObject({ role: 'CHANNEL', function: 'THROTTLE', reversed: true, positions: [] });
  });

  it('is a no-op when nothing has been detected yet', () => {
    const before = draft([]);

    expect(applyChannelStep(before, throttleStep, undefined, { reversed: false, travel: 'CENTERED', rcChannel: 1 })).toBe(
      before,
    );
  });
});

describe('applyActionStep', () => {
  it('adds a fresh row when the detected control has no row yet', () => {
    const positions = [{ position: 'HIGH' as const, action: 'ARM' as const, parameter: null }];

    const after = applyActionStep(draft([]), { source: 'BUTTON', sourceIndex: 2 }, 'BUTTON', positions);

    expect(after.controls).toHaveLength(1);
    expect(after.controls[0]).toMatchObject({ source: 'BUTTON', sourceIndex: 2, role: 'ACTIONS', kind: 'BUTTON', positions });
  });

  it('re-roles an existing channel row rather than duplicating it', () => {
    const existingChannel: ControlDraft = {
      ...blankControlDraft('AXIS', 4, draft([])),
      role: 'CHANNEL',
      function: 'AUX_1',
      rcChannel: 6,
    };
    const positions = [{ position: 'MIDDLE' as const, action: 'SET_MODE' as const, parameter: 'HOLD' }];

    const after = applyActionStep(draft([existingChannel]), { source: 'AXIS', sourceIndex: 4 }, 'SWITCH_3', positions);

    expect(after.controls).toHaveLength(1);
    expect(after.controls[0]).toMatchObject({ role: 'ACTIONS', kind: 'SWITCH_3', positions });
  });
});

describe('reviewChecks', () => {
  const movedSamples = (keys: readonly string[]): ReadonlyMap<string, DetectSample> =>
    new Map(keys.map((key) => [key, { source: 'AXIS', sourceIndex: 0, min: -1, max: 1, firstSign: 1 }]));

  it('fails throttle-rests-at-idle for a copter whose throttle is centred', () => {
    const throttle: ControlDraft = {
      ...blankControlDraft('AXIS', 2, draft([])),
      role: 'CHANNEL',
      function: 'THROTTLE',
      travel: 'CENTERED',
    };

    const checks = reviewChecks(draft([throttle], 'COPTER'), 'COPTER', new Map());

    expect(checks.find((c) => c.label === 'Throttle rests at idle')?.ok).toBe(false);
  });

  it('passes throttle-rests-at-idle for a copter whose throttle is unidirectional', () => {
    const throttle: ControlDraft = {
      ...blankControlDraft('AXIS', 2, draft([])),
      role: 'CHANNEL',
      function: 'THROTTLE',
      travel: 'UNIDIRECTIONAL',
    };

    const checks = reviewChecks(draft([throttle], 'COPTER'), 'COPTER', new Map());

    expect(checks.find((c) => c.label === 'Throttle rests at idle')?.ok).toBe(true);
  });

  it('does not apply throttle-rests-at-idle to a rover, whose centred throttle is correct', () => {
    const checks = reviewChecks(draft([], 'ROVER'), 'ROVER', new Map());

    expect(checks.find((c) => c.label === 'Throttle rests at idle')?.ok).toBeUndefined();
  });

  it('every-stick-moved is undefined with no channel controls, true once every one moved', () => {
    const empty = reviewChecks(draft([]), 'ROVER', new Map());
    expect(empty.find((c) => c.label === 'Every stick moved at least once')?.ok).toBeUndefined();

    const steering: ControlDraft = { ...blankControlDraft('AXIS', 0, draft([])), role: 'CHANNEL', function: 'STEERING' };
    const withMovement = reviewChecks(draft([steering]), 'ROVER', movedSamples(['AXIS:0']));
    expect(withMovement.find((c) => c.label === 'Every stick moved at least once')?.ok).toBe(true);

    const withoutMovement = reviewChecks(draft([steering]), 'ROVER', new Map());
    expect(withoutMovement.find((c) => c.label === 'Every stick moved at least once')?.ok).toBe(false);
  });

  it('arm and mode checks mirror whether any control fires those actions', () => {
    const armed: ControlDraft = {
      ...blankControlDraft('BUTTON', 3, draft([])),
      positions: [{ position: 'HIGH', action: 'ARM', parameter: null }],
    };

    const checks = reviewChecks(draft([armed]), 'ROVER', new Map());

    expect(checks.find((c) => c.label === 'Arm is on a switch')?.ok).toBe(true);
    expect(checks.find((c) => c.label === 'Mode is on a switch')?.ok).toBe(false);
  });
});

describe('actionStepKindOf', () => {
  it('is undefined for a CHANNEL control', () => {
    const control: ControlDraft = { ...blankControlDraft('AXIS', 0, draft([])), role: 'CHANNEL' };
    expect(actionStepKindOf(control)).toBeUndefined();
  });

  it('is undefined for an ACTIONS control with nothing chosen yet', () => {
    expect(actionStepKindOf(blankControlDraft('BUTTON', 0, draft([])))).toBeUndefined();
  });

  it('reads ARM off an ARM/DISARM/TOGGLE_ARM position', () => {
    const control: ControlDraft = {
      ...blankControlDraft('BUTTON', 0, draft([])),
      positions: [{ position: 'HIGH', action: 'TOGGLE_ARM', parameter: null }],
    };
    expect(actionStepKindOf(control)).toBe('ARM');
  });

  it('reads MODE off a SET_MODE position', () => {
    const control: ControlDraft = {
      ...blankControlDraft('BUTTON', 0, draft([])),
      positions: [{ position: 'HIGH', action: 'SET_MODE', parameter: 'HOLD' }],
    };
    expect(actionStepKindOf(control)).toBe('MODE');
  });

  it('reads EXTRAS off emergency stop, return home, or aux function', () => {
    const control: ControlDraft = {
      ...blankControlDraft('BUTTON', 0, draft([])),
      positions: [{ position: 'HIGH', action: 'AUX_FUNCTION', parameter: '19' }],
    };
    expect(actionStepKindOf(control)).toBe('EXTRAS');
  });
});

describe('actionsForStep', () => {
  const armStep: WizardStep = { id: 'arm', kind: 'ARM', title: 'Arm', instruction: '' };
  const modeStep: WizardStep = { id: 'mode', kind: 'MODE', title: 'Mode', instruction: '' };
  const extrasStep: WizardStep = { id: 'extras', kind: 'EXTRAS', title: 'Extras', instruction: '' };
  const reviewStep: WizardStep = { id: 'review', kind: 'REVIEW', title: 'Review', instruction: '' };

  it('narrows the ARM step to arm/disarm/toggle', () => {
    expect(actionsForStep(armStep, CATALOG).map((a) => a.name).sort()).toEqual(['ARM', 'DISARM', 'TOGGLE_ARM'].sort());
  });

  it('narrows the MODE step to SET_MODE only', () => {
    expect(actionsForStep(modeStep, CATALOG).map((a) => a.name)).toEqual(['SET_MODE']);
  });

  it('narrows the EXTRAS step to the extras trio', () => {
    expect(actionsForStep(extrasStep, CATALOG).map((a) => a.name).sort()).toEqual(
      ['EMERGENCY_STOP', 'RETURN_TO_HOME', 'AUX_FUNCTION'].sort(),
    );
  });

  it('does not narrow a step with no action family (e.g. REVIEW), or an absent catalogue', () => {
    expect(actionsForStep(reviewStep, CATALOG)).toEqual(CATALOG.actions);
    expect(actionsForStep(armStep, undefined)).toEqual([]);
  });
});
