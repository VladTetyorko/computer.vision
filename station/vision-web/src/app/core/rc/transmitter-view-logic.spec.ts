import { describe, expect, it } from 'vitest';
import type { ActionBinding, ControlBinding, ControlCatalog, ManualControlChannelBinding } from '../api/models';
import {
  actionSwitchRows,
  displayPads,
  holdingMatchesRow,
  normalizeChannelMap,
  padRestX,
  padRestY,
  positionsForKind,
  toChannelBinding,
  unmappedControls,
} from './transmitter-view-logic';

const CATALOG: ControlCatalog = {
  vehicleKinds: [
    { name: 'ROVER', label: 'Rover' },
    { name: 'COPTER', label: 'Multirotor' },
  ],
  inputKinds: [
    { name: 'AXIS', label: 'Axis', sources: ['AXIS'], positions: [] },
    { name: 'BUTTON', label: 'Button', sources: ['BUTTON'], positions: ['HIGH'] },
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
    { name: 'YAW', label: 'Yaw' },
    { name: 'ROLL', label: 'Roll' },
    { name: 'PITCH', label: 'Pitch' },
  ],
  actions: [
    { name: 'ARM', label: 'Arm', parameter: 'NONE', dangerous: true },
    { name: 'DISARM', label: 'Disarm', parameter: 'NONE', dangerous: true },
    { name: 'SET_MODE', label: 'Set mode', parameter: 'MODE_NAME', dangerous: false },
  ],
  auxFunctions: [],
};

const axisBinding = (
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
  centerMicros: travel === 'CENTERED' ? 1500 : 1000,
  maxMicros: 2000,
  label,
});

const STEERING = axisBinding('STEERING', 'CENTERED', 0, 'Steering');
const ROVER_THROTTLE = axisBinding('THROTTLE', 'CENTERED', 1, 'Throttle');
const YAW = axisBinding('YAW', 'CENTERED', 3, 'Yaw');
const COPTER_THROTTLE = axisBinding('THROTTLE', 'UNIDIRECTIONAL', 2, 'Throttle');
const ROLL = axisBinding('ROLL', 'CENTERED', 0, 'Roll');
const PITCH = axisBinding('PITCH', 'CENTERED', 1, 'Pitch');

describe('normalizeChannelMap / toChannelBinding', () => {
  it('passes an already-normalized ManualControlChannelBinding[] through unchanged', () => {
    expect(normalizeChannelMap([STEERING, ROVER_THROTTLE], CATALOG)).toEqual([STEERING, ROVER_THROTTLE]);
  });

  it('adapts a profile ControlBinding, filling travel/label from the catalogue when absent', () => {
    const raw: ControlBinding = {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'STEERING',
      sourceIndex: 0,
      rcChannel: 1,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0.05,
      reversed: false,
      // travel/label deliberately omitted, as a write payload would.
    };
    expect(toChannelBinding(raw, CATALOG)).toEqual({
      source: 'AXIS',
      kind: 'AXIS',
      function: 'STEERING',
      travel: 'CENTERED',
      sourceIndex: 0,
      rcChannel: 1,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      label: 'Steering',
    });
  });

  it('keeps a ControlBinding read that already carries travel/label as the server sent them', () => {
    const raw: ControlBinding = {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'THROTTLE',
      travel: 'UNIDIRECTIONAL',
      sourceIndex: 2,
      rcChannel: 3,
      minMicros: 1000,
      centerMicros: 1000,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
      label: 'Throttle',
    };
    expect(toChannelBinding(raw, CATALOG).travel).toBe('UNIDIRECTIONAL');
    expect(toChannelBinding(raw, CATALOG).label).toBe('Throttle');
  });

  it('falls back to the function name itself when there is no catalogue to resolve a label from', () => {
    const raw: ControlBinding = {
      source: 'AXIS',
      kind: 'AXIS',
      function: 'AUX_1',
      sourceIndex: 5,
      rcChannel: 6,
      minMicros: 1000,
      centerMicros: 1500,
      maxMicros: 2000,
      deadband: 0,
      reversed: false,
    };
    expect(toChannelBinding(raw, undefined).label).toBe('AUX_1');
  });
});

describe('displayPads', () => {
  it('gives a rover exactly one pad — steer across, drive up and down', () => {
    const pads = displayPads([STEERING, ROVER_THROTTLE], CATALOG);
    expect(pads.length).toBe(1);
    expect(pads[0].x?.function).toBe('STEERING');
    expect(pads[0].y?.function).toBe('THROTTLE');
  });

  it('gives an aircraft two pads: yaw/throttle and roll/pitch', () => {
    const pads = displayPads([YAW, COPTER_THROTTLE, ROLL, PITCH], CATALOG);
    expect(pads.map((p) => p.id)).toEqual(['YAW-THROTTLE', 'ROLL-PITCH']);
  });

  it('normalizes a profile ControlBinding[] the same way before padding it up', () => {
    const rawSteering: ControlBinding = { ...STEERING, deadband: 0, reversed: false };
    const rawThrottle: ControlBinding = { ...ROVER_THROTTLE, deadband: 0, reversed: false };
    const pads = displayPads([rawSteering, rawThrottle], CATALOG);
    expect(pads.length).toBe(1);
    expect(pads[0].x?.function).toBe('STEERING');
    expect(pads[0].y?.function).toBe('THROTTLE');
  });
});

describe('padRestX / padRestY', () => {
  it('marks a centred axis at 50%, not heavy', () => {
    const [pad] = displayPads([STEERING, ROVER_THROTTLE], CATALOG);
    expect(padRestX(pad)).toEqual({ percent: 50, heavy: false });
    expect(padRestY(pad)).toEqual({ percent: 50, heavy: false });
  });

  it('marks a unidirectional throttle heavy, at the pad edge (idle), not the centre', () => {
    const [yawThrottlePad] = displayPads([YAW, COPTER_THROTTLE], CATALOG);
    expect(padRestX(yawThrottlePad)).toEqual({ percent: 50, heavy: false }); // yaw: centred
    expect(padRestY(yawThrottlePad)).toEqual({ percent: 100, heavy: true }); // throttle: idle at the bottom
  });

  it('is undefined for the axis a single-control pad does not bind', () => {
    const [pad] = displayPads([ROLL], CATALOG);
    expect(pad.y).toBeUndefined();
    expect(padRestY(pad)).toBeUndefined();
  });
});

describe('positionsForKind', () => {
  it('reads BUTTON/SWITCH_2/SWITCH_3 cell counts off the catalogue', () => {
    expect(positionsForKind('BUTTON', CATALOG)).toEqual(['HIGH']);
    expect(positionsForKind('SWITCH_2', CATALOG)).toEqual(['LOW', 'HIGH']);
    expect(positionsForKind('SWITCH_3', CATALOG)).toEqual(['LOW', 'MIDDLE', 'HIGH']);
  });

  it('falls back to the same fixed table when no catalogue has loaded yet', () => {
    expect(positionsForKind('BUTTON', undefined)).toEqual(['HIGH']);
    expect(positionsForKind('SWITCH_2', undefined)).toEqual(['LOW', 'HIGH']);
    expect(positionsForKind('SWITCH_3', undefined)).toEqual(['LOW', 'MIDDLE', 'HIGH']);
    expect(positionsForKind('AXIS', undefined)).toEqual([]);
  });
});

describe('actionSwitchRows', () => {
  const armSwitch: ActionBinding = {
    source: 'AXIS',
    kind: 'SWITCH_3',
    sourceIndex: 4,
    positions: [
      { position: 'LOW', action: 'DISARM' },
      { position: 'HIGH', action: 'ARM' },
    ],
  };
  const modeButton: ActionBinding = {
    source: 'BUTTON',
    kind: 'BUTTON',
    sourceIndex: 3,
    positions: [{ position: 'HIGH', action: 'SET_MODE', parameter: 'LOITER' }],
  };

  it('computes the live lit position for a 3-position switch read from an axis', () => {
    const rows = actionSwitchRows([armSwitch], [0, 0, 0, 0, 1], [], CATALOG, undefined);
    expect(rows[0].position).toBe('HIGH');
    expect(rows[0].cells.map((c) => c.lit)).toEqual([false, false, true]);
  });

  it('computes the live lit position for a button', () => {
    const rows = actionSwitchRows([modeButton], [], [0, 0, 0, 1], CATALOG, undefined);
    expect(rows[0].position).toBe('HIGH');
    expect(rows[0].cells).toEqual([{ position: 'HIGH', lit: true, text: 'Mode LOITER', dangerous: false }]);
  });

  it('renders an unconfigured position as "—" and marks a configured dangerous action', () => {
    const rows = actionSwitchRows([armSwitch], [0, 0, 0, 0, 0], [], CATALOG, undefined);
    expect(rows[0].cells.map((c) => c.text)).toEqual(['Disarm', '—', 'Arm']);
    expect(rows[0].cells.map((c) => c.dangerous)).toEqual([true, false, true]);
  });

  it('labels a row by its device index, 1-based, matching controlLabel — an AXIS-sourced switch reads "Axis N", a BUTTON one "Sw N"', () => {
    const rows = actionSwitchRows([armSwitch, modeButton], [0, 0, 0, 0, 0], [0, 0, 0, 0], CATALOG, undefined);
    expect(rows[0].idLabel).toBe('Axis 5');
    expect(rows[1].idLabel).toBe('Sw 4');
  });

  it('flags the row the dispatcher\'s hold notice names, and only that row', () => {
    const rows = actionSwitchRows([armSwitch, modeButton], [0, 0, 0, 0, 1], [0, 0, 0, 1], CATALOG, 'Hold Axis 5 to arm');
    expect(rows[0].holding).toBe(true);
    expect(rows[1].holding).toBe(false);
  });
});

describe('holdingMatchesRow', () => {
  it('matches the dispatcher\'s exact "Hold <label> to <verb>" prefix', () => {
    expect(holdingMatchesRow('Hold Sw 5 to arm', 'Sw 5')).toBe(true);
  });

  it('does not match a different row, a prefix collision, or an undefined notice', () => {
    expect(holdingMatchesRow('Hold Sw 15 to arm', 'Sw 1')).toBe(false);
    expect(holdingMatchesRow('Hold Sw 4 to disarm', 'Sw 5')).toBe(false);
    expect(holdingMatchesRow(undefined, 'Sw 5')).toBe(false);
  });
});

describe('unmappedControls', () => {
  it('lists device inputs bound to neither map, 1-based labels', () => {
    const channelMap = [{ source: 'AXIS' as const, sourceIndex: 0 }];
    const actionMap: readonly ActionBinding[] = [{ source: 'BUTTON', kind: 'BUTTON', sourceIndex: 0, positions: [] }];
    const unmapped = unmappedControls(channelMap, actionMap, 3, 2);
    expect(unmapped).toEqual([
      { source: 'AXIS', sourceIndex: 1, label: 'Axis 2' },
      { source: 'AXIS', sourceIndex: 2, label: 'Axis 3' },
      { source: 'BUTTON', sourceIndex: 1, label: 'Sw 2' },
    ]);
  });

  it('is empty once every input is claimed by one map or the other', () => {
    const channelMap = [{ source: 'AXIS' as const, sourceIndex: 0 }, { source: 'AXIS' as const, sourceIndex: 1 }];
    const actionMap: readonly ActionBinding[] = [{ source: 'BUTTON', kind: 'BUTTON', sourceIndex: 0, positions: [] }];
    expect(unmappedControls(channelMap, actionMap, 2, 1)).toEqual([]);
  });

  it('is empty when there is no device at all — no axes, no buttons', () => {
    expect(unmappedControls([], [], 0, 0)).toEqual([]);
  });
});
