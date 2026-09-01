import { describe, expect, it } from 'vitest';
import type { ActionBinding, ControlAction, ControlProfile, SwitchPosition } from '../api/models';
import {
  actionLabel,
  activeProfileFor,
  boundControlKeys,
  controlKey,
  pendingActions,
  positionOf,
  positionsFrom,
} from './control-action-logic';

const DANGEROUS = new Set<ControlAction>(['ARM', 'DISARM', 'TOGGLE_ARM', 'EMERGENCY_STOP']);

function switch3(index: number, positions: ActionBinding['positions']): ActionBinding {
  return { source: 'AXIS', kind: 'SWITCH_3', sourceIndex: index, positions };
}

function profile(over: Partial<ControlProfile>): ControlProfile {
  return {
    id: 'p',
    source: 'SAVED',
    kind: 'ROVER',
    code: 'S-T-',
    name: 'Bench rover',
    active: false,
    stickMode: 2,
    forwardIsUp: true,
    channelMap: [],
    actionMap: [],
    ...over,
  };
}

describe('positionOf', () => {
  it('reads a button as pressed or not, never as a middle position', () => {
    expect(positionOf('BUTTON', 'BUTTON', 1)).toBe('HIGH');
    expect(positionOf('BUTTON', 'BUTTON', 0)).toBe('LOW');
    expect(positionOf('BUTTON', 'BUTTON', 0.5)).toBe('HIGH');
    expect(positionOf('BUTTON', 'BUTTON', 0.49)).toBe('LOW');
  });

  // The firmware's own detents -- a station that quantized anywhere else would show "middle" while
  // the vehicle acted on "high" (CONTROLLER-SETUP-CONTEXT.md C4).
  it('splits a three-position switch at the same detents the firmware uses', () => {
    expect(positionOf('AXIS', 'SWITCH_3', -1)).toBe('LOW');
    expect(positionOf('AXIS', 'SWITCH_3', -0.5)).toBe('LOW');
    expect(positionOf('AXIS', 'SWITCH_3', -0.49)).toBe('MIDDLE');
    expect(positionOf('AXIS', 'SWITCH_3', 0)).toBe('MIDDLE');
    expect(positionOf('AXIS', 'SWITCH_3', 0.49)).toBe('MIDDLE');
    expect(positionOf('AXIS', 'SWITCH_3', 0.5)).toBe('HIGH');
    expect(positionOf('AXIS', 'SWITCH_3', 1)).toBe('HIGH');
  });

  it('splits a two-position switch on an axis at centre', () => {
    expect(positionOf('AXIS', 'SWITCH_2', -0.01)).toBe('LOW');
    expect(positionOf('AXIS', 'SWITCH_2', 0)).toBe('HIGH');
  });
});

describe('positionsFrom', () => {
  it('reads a control the gamepad does not report as resting rather than dropping it', () => {
    const positions = positionsFrom([switch3(7, [{ position: 'HIGH', action: 'ARM' }])], [0.9], []);

    expect(positions.get(controlKey('AXIS', 7))).toBe('MIDDLE');
  });
});

describe('pendingActions', () => {
  const arming = switch3(4, [
    { position: 'LOW', action: 'DISARM' },
    { position: 'HIGH', action: 'ARM' },
  ]);

  function positions(entries: Record<string, SwitchPosition>): ReadonlyMap<string, SwitchPosition> {
    return new Map(Object.entries(entries));
  }

  it('fires once when a switch arrives at a bound position', () => {
    const fired = pendingActions(
      [arming],
      positions({ 'AXIS:4': 'MIDDLE' }),
      positions({ 'AXIS:4': 'HIGH' }),
      DANGEROUS,
    );

    expect(fired).toEqual([
      { key: 'AXIS:4', label: 'Axis 5', position: 'HIGH', action: 'ARM', parameter: undefined, dangerous: true },
    ]);
  });

  it('does not fire again while the switch is held there', () => {
    expect(
      pendingActions([arming], positions({ 'AXIS:4': 'HIGH' }), positions({ 'AXIS:4': 'HIGH' }), DANGEROUS),
    ).toEqual([]);
  });

  /** The rule that stops a panel opening from arming anything. */
  it('treats the first frame as already-settled, never as an edge', () => {
    expect(pendingActions([arming], new Map(), positions({ 'AXIS:4': 'HIGH' }), DANGEROUS)).toEqual([]);
  });

  it('ignores a position the operator bound nothing to', () => {
    const half = switch3(4, [{ position: 'HIGH', action: 'ARM' }]);

    expect(
      pendingActions([half], positions({ 'AXIS:4': 'HIGH' }), positions({ 'AXIS:4': 'MIDDLE' }), DANGEROUS),
    ).toEqual([]);
  });

  it('carries the parameter and the danger flag through', () => {
    const aux = switch3(2, [{ position: 'HIGH', action: 'AUX_FUNCTION', parameter: '46' }]);

    const fired = pendingActions([aux], positions({ 'AXIS:2': 'LOW' }), positions({ 'AXIS:2': 'HIGH' }), DANGEROUS);

    expect(fired[0]).toMatchObject({ action: 'AUX_FUNCTION', parameter: '46', dangerous: false });
  });
});

describe('activeProfileFor', () => {
  const builtIn = profile({ id: 'b', source: 'BUILT_IN', active: true });
  const saved = profile({ id: 's', source: 'SAVED', active: true });

  it('prefers the operators active saved layout over the built-in for that kind', () => {
    expect(activeProfileFor([builtIn, saved], 'ROVER')?.id).toBe('s');
  });

  it('falls back to the built-in when nothing of theirs is active', () => {
    expect(activeProfileFor([builtIn, profile({ id: 's', active: false })], 'ROVER')?.id).toBe('b');
  });

  it('never crosses vehicle kinds', () => {
    expect(activeProfileFor([builtIn, saved], 'COPTER')).toBeUndefined();
  });

  it('answers nothing while the vehicle kind is still unknown', () => {
    expect(activeProfileFor([builtIn], undefined)).toBeUndefined();
  });
});

describe('boundControlKeys', () => {
  it('counts both maps, so a setup page can say an input is already taken', () => {
    const keys = boundControlKeys(
      profile({
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
        ],
        actionMap: [switch3(4, [{ position: 'HIGH', action: 'ARM' }])],
      }),
    );

    expect([...keys].sort()).toEqual(['AXIS:0', 'AXIS:4']);
  });
});

describe('actionLabel', () => {
  it('names what happened, including the parameter where there is one', () => {
    expect(actionLabel('SET_MODE', 'Loiter')).toBe('Mode Loiter');
    expect(actionLabel('AUX_FUNCTION', '46')).toBe('Aux function 46');
    expect(actionLabel('EMERGENCY_STOP')).toBe('Emergency stop');
  });

  // FLEET-RADIO R4b: the backend's emergency stop is not one command with one meaning any more --
  // a rover holds and brakes, everything else still force-disarms. The label must say so.
  describe('EMERGENCY_STOP per vehicle kind', () => {
    it('reads as a brake/hold on a rover, not a forced disarm', () => {
      expect(actionLabel('EMERGENCY_STOP', undefined, 'ROVER')).toBe('Emergency stop (Hold)');
    });

    it('still reads as the historical forced disarm on a copter', () => {
      expect(actionLabel('EMERGENCY_STOP', undefined, 'COPTER')).toBe('Emergency stop');
    });

    it('still reads as the historical forced disarm on a plane', () => {
      expect(actionLabel('EMERGENCY_STOP', undefined, 'PLANE')).toBe('Emergency stop');
    });

    it('falls back to the historical forced-disarm wording for an unrecognized vehicle', () => {
      expect(actionLabel('EMERGENCY_STOP', undefined, 'UNKNOWN')).toBe('Emergency stop');
    });

    it('falls back to the historical wording when the kind is not known yet', () => {
      expect(actionLabel('EMERGENCY_STOP')).toBe('Emergency stop');
      expect(actionLabel('EMERGENCY_STOP', undefined, undefined)).toBe('Emergency stop');
    });
  });
});
