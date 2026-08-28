import { describe, expect, it } from 'vitest';
import {
  actionAt,
  blankControlDraft,
  draftFrom,
  draftIssues,
  draftKey,
  draftLabel,
  kindsFor,
  microsFor,
  movedControl,
  nextFreeChannel,
  parameterKindOf,
  positionsOf,
  toUpdateRequest,
  withKind,
  withPositionAction,
  type ControlDraft,
  type ProfileDraft,
} from './controller-setup-logic';
import type { ControlCatalog, ControlProfile } from '../api/models';

const CATALOG = {
  vehicleKinds: [{ name: 'ROVER', label: 'Rover' }],
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
    { name: 'STEERING', label: 'Steering' },
    { name: 'THROTTLE', label: 'Throttle' },
  ],
  actions: [
    { name: 'ARM', label: 'Arm', parameter: 'NONE', dangerous: true },
    { name: 'SET_MODE', label: 'Set mode', parameter: 'MODE_NAME', dangerous: false },
    { name: 'AUX_FUNCTION', label: 'Aux function', parameter: 'AUX_FUNCTION', dangerous: false },
  ],
  auxFunctions: [{ number: 19, label: 'Gripper' }],
} as ControlCatalog;

const ROVER: ControlProfile = {
  id: 'p1',
  source: 'SAVED',
  kind: 'ROVER',
  code: 'CUSTOM',
  name: 'Bench rover',
  active: false,
  channelMap: [
    {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'THROTTLE',
      sourceIndex: 3,
      rcChannel: 3,
      minMicros: 1000,
      centerMicros: 1000,
      maxMicros: 2000,
      deadband: 0,
      reversed: true,
    },
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
  ],
  actionMap: [
    {
      source: 'BUTTON',
      kind: 'BUTTON',
      sourceIndex: 2,
      positions: [{ position: 'HIGH', action: 'ARM', parameter: null }],
    },
  ],
};

function draft(controls: readonly ControlDraft[], name = 'Bench rover'): ProfileDraft {
  return { id: 'p1', kind: 'ROVER', name, controls };
}

describe('draftFrom', () => {
  it('makes one row per control, axes before buttons, by index', () => {
    const rows = draftFrom(ROVER).controls;

    expect(rows.map(draftLabel)).toEqual(['Axis 1', 'Axis 4', 'Sw 3']);
    expect(rows.map((r) => r.role)).toEqual(['CHANNEL', 'CHANNEL', 'ACTIONS']);
  });

  it('reads travel back out of the microseconds rather than storing a second copy of it', () => {
    const rows = draftFrom(ROVER).controls;

    expect(rows[0].travel).toBe('CENTERED');
    expect(rows[1].travel).toBe('UNIDIRECTIONAL');
    expect(rows[1].reversed).toBe(true);
  });

  it('round-trips a layout unchanged — opening and saving without editing sends what was there', () => {
    const request = toUpdateRequest(draftFrom(ROVER));

    expect(request.name).toBe('Bench rover');
    expect(request.channelMap).toHaveLength(2);
    expect(request.actionMap).toEqual(ROVER.actionMap);
    expect(request.channelMap.find((b) => b.function === 'THROTTLE')).toMatchObject({
      centerMicros: 1000,
      minMicros: 1000,
      maxMicros: 2000,
      reversed: true,
    });
  });
});

describe('blankControlDraft', () => {
  it('starts a new control doing nothing at all, not driving a channel', () => {
    const control = blankControlDraft('BUTTON', 4, draft([]));

    expect(control.role).toBe('ACTIONS');
    expect(control.positions).toEqual([]);
    expect(draftKey(control)).toBe('BUTTON:4');
  });

  it('reserves a channel nothing else drives, in case it becomes a channel row', () => {
    const existing = draftFrom(ROVER);

    expect(blankControlDraft('AXIS', 5, existing).rcChannel).toBe(2);
  });
});

describe('nextFreeChannel', () => {
  it('skips every channel already driven', () => {
    expect(nextFreeChannel(draftFrom(ROVER))).toBe(2);
  });

  it('is 1 for an empty layout', () => {
    expect(nextFreeChannel(draft([]))).toBe(1);
  });

  // FLEET-RADIO R3/F17: ArduPilot reads only channels 1-16 from an RC_CHANNELS_OVERRIDE, so
  // `ControlBinding` throws on 17/18. This page must never hand the operator a channel the server
  // will reject -- when every usable channel is taken it clamps to 16 rather than inventing a 17th.
  it('never offers a channel past the 16 the vehicle can actually read', () => {
    const full = draft(
      Array.from({ length: 16 }, (_unused, index) => ({
        ...blankControlDraft('AXIS', index, draft([])),
        role: 'CHANNEL' as const,
        rcChannel: index + 1,
      })),
    );

    expect(nextFreeChannel(full)).toBe(16);
  });
});

describe('withKind', () => {
  it('drops positions the new kind cannot reach', () => {
    const three = withPositionAction(
      withKind(blankControlDraft('AXIS', 4, draft([])), 'SWITCH_3', CATALOG),
      'MIDDLE',
      'ARM',
      null,
      ['LOW', 'MIDDLE', 'HIGH'],
    );
    expect(actionAt(three, 'MIDDLE')).toBeDefined();

    const two = withKind(three, 'SWITCH_2', CATALOG);
    expect(actionAt(two, 'MIDDLE')).toBeUndefined();
  });

  it('pins a button to one-way travel — a button has no centre to rest at', () => {
    const control = withKind(blankControlDraft('BUTTON', 1, draft([])), 'BUTTON', CATALOG);

    expect(control.travel).toBe('UNIDIRECTIONAL');
  });
});

describe('withPositionAction', () => {
  const base = withKind(blankControlDraft('AXIS', 4, draft([])), 'SWITCH_3', CATALOG);
  const order = ['LOW', 'MIDDLE', 'HIGH'] as const;

  it('keeps positions in the catalogue order, whatever order they were filled in', () => {
    const filled = withPositionAction(
      withPositionAction(base, 'HIGH', 'ARM', null, order),
      'LOW',
      'SET_MODE',
      'HOLD',
      order,
    );

    expect(filled.positions.map((p) => p.position)).toEqual(['LOW', 'HIGH']);
  });

  it('clears a position back to nothing', () => {
    const filled = withPositionAction(base, 'HIGH', 'ARM', null, order);

    expect(withPositionAction(filled, 'HIGH', undefined, null, order).positions).toEqual([]);
  });
});

describe('toUpdateRequest', () => {
  it('drops an action row nobody finished, rather than sending an empty binding the server refuses', () => {
    const request = toUpdateRequest(draft([blankControlDraft('BUTTON', 1, draft([]))]));

    expect(request.actionMap).toEqual([]);
  });

  it('derives the microseconds from the travel the operator picked', () => {
    const control: ControlDraft = {
      ...blankControlDraft('AXIS', 1, draft([])),
      role: 'CHANNEL',
      function: 'THROTTLE',
      rcChannel: 3,
      travel: 'UNIDIRECTIONAL',
    };

    expect(toUpdateRequest(draft([control])).channelMap[0]).toMatchObject({
      minMicros: 1000,
      centerMicros: 1000,
      maxMicros: 2000,
    });
  });

  it('trims the name, so a trailing space is not what a layout is called', () => {
    expect(toUpdateRequest(draft([], '  Rover  ')).name).toBe('Rover');
  });
});

describe('microsFor', () => {
  it('rests a unidirectional control at its minimum', () => {
    expect(microsFor('UNIDIRECTIONAL')).toEqual({ min: 1000, center: 1000, max: 2000 });
  });

  it('rests a centered control at the midpoint', () => {
    expect(microsFor('CENTERED')).toEqual({ min: 1000, center: 1500, max: 2000 });
  });

  it('agrees with what toUpdateRequest actually sends', () => {
    const control: ControlDraft = {
      ...blankControlDraft('AXIS', 1, draft([])),
      role: 'CHANNEL',
      function: 'THROTTLE',
      rcChannel: 3,
      travel: 'CENTERED',
    };
    const sent = toUpdateRequest(draft([control])).channelMap[0];
    const derived = microsFor('CENTERED');
    expect({ minMicros: sent.minMicros, centerMicros: sent.centerMicros, maxMicros: sent.maxMicros }).toEqual({
      minMicros: derived.min,
      centerMicros: derived.center,
      maxMicros: derived.max,
    });
  });
});

describe('draftIssues', () => {
  it('is silent about a layout that is fine', () => {
    expect(draftIssues(draftFrom(ROVER), CATALOG)).toEqual([]);
  });

  it('names the control when two rows drive the same channel', () => {
    const a: ControlDraft = { ...blankControlDraft('AXIS', 0, draft([])), role: 'CHANNEL', rcChannel: 1 };
    const b: ControlDraft = { ...blankControlDraft('AXIS', 1, draft([])), role: 'CHANNEL', rcChannel: 1 };

    expect(draftIssues(draft([a, b]), CATALOG)).toEqual(['CH1 is driven by more than one control.']);
  });

  it('catches an action whose parameter was never chosen', () => {
    const control = withPositionAction(
      withKind(blankControlDraft('AXIS', 4, draft([])), 'SWITCH_2', CATALOG),
      'HIGH',
      'SET_MODE',
      null,
      ['LOW', 'HIGH'],
    );

    expect(draftIssues(draft([control]), CATALOG)).toEqual(['Axis 5 high needs a flight mode.']);
  });

  it('refuses a nameless layout', () => {
    expect(draftIssues(draft([], '   '), CATALOG)).toContain('Give this layout a name.');
  });
});

describe('catalogue lookups', () => {
  it('offers a 3-position switch only where it can actually be read from', () => {
    expect(kindsFor(CATALOG, 'AXIS')).toContain('SWITCH_3');
    expect(kindsFor(CATALOG, 'BUTTON')).not.toContain('SWITCH_3');
  });

  it('says a continuous axis has no positions', () => {
    expect(positionsOf(CATALOG, 'AXIS')).toEqual([]);
    expect(positionsOf(CATALOG, 'SWITCH_3')).toEqual(['LOW', 'MIDDLE', 'HIGH']);
  });

  it('answers NONE for an action nobody has chosen yet, so the UI asks for nothing', () => {
    expect(parameterKindOf(CATALOG, undefined)).toBe('NONE');
    expect(parameterKindOf(CATALOG, 'AUX_FUNCTION')).toBe('AUX_FUNCTION');
    expect(parameterKindOf(undefined, 'SET_MODE')).toBe('NONE');
  });
});

describe('movedControl', () => {
  const baseline = { axes: [0, 0, 0, 0], buttons: [0, 0] };

  it('names the control that genuinely moved', () => {
    expect(movedControl([0, 0, 0.9, 0], [0, 0], baseline)).toEqual({ source: 'AXIS', sourceIndex: 2 });
    expect(movedControl([0, 0, 0, 0], [0, 1], baseline)).toEqual({ source: 'BUTTON', sourceIndex: 1 });
  });

  it('ignores a resting stick own jitter', () => {
    expect(movedControl([0.02, -0.01, 0, 0], [0, 0], baseline)).toBeUndefined();
  });

  it('reads a control the baseline never saw as resting at zero', () => {
    expect(movedControl([0, 0, 0, 0, 1], [], { axes: [], buttons: [] })).toEqual({ source: 'AXIS', sourceIndex: 4 });
  });
});
