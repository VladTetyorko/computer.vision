import { describe, expect, it } from 'vitest';
import { armedChip, armAlsoOnHint, engageDisabledReason, latencyLabel, modeAlsoOnHint } from './rc-monitor-logic';
import type { ActionBinding } from '../../core/api/models';

const BASE = {
  hasAsset: true,
  canCommand: true,
  sourceKind: 'gamepad' as const,
  gamepadConnected: true,
  engageState: 'idle' as const,
};

describe('engageDisabledReason', () => {
  it('is undefined (enabled) when every condition is met', () => {
    expect(engageDisabledReason(BASE)).toBeUndefined();
  });

  it('is enabled while released or denied, same as idle', () => {
    expect(engageDisabledReason({ ...BASE, engageState: 'released' })).toBeUndefined();
    expect(engageDisabledReason({ ...BASE, engageState: 'denied' })).toBeUndefined();
  });

  it('reports "Engaging…" first, regardless of any other condition', () => {
    expect(engageDisabledReason({ ...BASE, engageState: 'engaging', hasAsset: false })).toBe('Engaging…');
  });

  it('reports no asset before the commandability/gamepad checks', () => {
    expect(engageDisabledReason({ ...BASE, hasAsset: false, canCommand: false })).toBe('Pick a drone first.');
  });

  it('reports not commandable before the input-source check', () => {
    expect(engageDisabledReason({ ...BASE, canCommand: false, gamepadConnected: false })).toBe(
      "This drone isn't commandable right now.",
    );
  });

  it('reports the transmitter not being plugged in as the last-mile reason', () => {
    expect(engageDisabledReason({ ...BASE, gamepadConnected: false })).toBe(
      'Plug your transmitter in, or switch to the on-screen controls.',
    );
  });

  it('does not require a gamepad at all when the on-screen source is selected', () => {
    expect(engageDisabledReason({ ...BASE, sourceKind: 'virtual', gamepadConnected: false })).toBeUndefined();
  });

  it('is engaged is also enabled (a caller should not render the button in that state, but the gate itself does not special-case it)', () => {
    expect(engageDisabledReason({ ...BASE, engageState: 'engaged' })).toBeUndefined();
  });
});

describe('latencyLabel', () => {
  it('renders an em-dash while undefined — never a fabricated 0', () => {
    expect(latencyLabel(undefined)).toBe('—');
  });

  it('renders a rounded ms readout', () => {
    expect(latencyLabel(41.6)).toBe('42 ms');
    expect(latencyLabel(0)).toBe('0 ms');
  });
});

describe('armedChip', () => {
  it('renders a faint dash, toned neutral, while armed is unknown', () => {
    expect(armedChip(undefined)).toEqual({ text: '—', tone: 'neutral' });
  });

  it('renders ARMED toned ok, mirroring the OSD chip bar\'s own convention', () => {
    expect(armedChip(true)).toEqual({ text: 'ARMED', tone: 'ok' });
  });

  it('renders DISARMED toned neutral — the grounded, routine state', () => {
    expect(armedChip(false)).toEqual({ text: 'DISARMED', tone: 'neutral' });
  });
});

function button(sourceIndex: number, action: ActionBinding['positions'][number]['action'], position: 'LOW' | 'MIDDLE' | 'HIGH' = 'HIGH'): ActionBinding {
  return { source: 'BUTTON', kind: 'BUTTON', sourceIndex, positions: [{ position, action, parameter: null }] };
}

function axisSwitch(sourceIndex: number, action: ActionBinding['positions'][number]['action'], position: 'LOW' | 'MIDDLE' | 'HIGH'): ActionBinding {
  return { source: 'AXIS', kind: 'SWITCH_2', sourceIndex, positions: [{ position, action, parameter: 'HOLD' }] };
}

describe('modeAlsoOnHint', () => {
  it('is undefined when no bound switch fires SET_MODE', () => {
    expect(modeAlsoOnHint([button(1, 'ARM')])).toBeUndefined();
    expect(modeAlsoOnHint([])).toBeUndefined();
  });

  it('names the switch that fires SET_MODE, never arrow-suffixed', () => {
    expect(modeAlsoOnHint([axisSwitch(4, 'SET_MODE', 'HIGH')])).toBe('Axis 5');
    expect(modeAlsoOnHint([axisSwitch(4, 'SET_MODE', 'LOW')])).toBe('Axis 5');
  });

  it('takes the first matching binding when more than one profile control could fire it', () => {
    expect(modeAlsoOnHint([axisSwitch(4, 'SET_MODE', 'HIGH'), axisSwitch(5, 'SET_MODE', 'HIGH')])).toBe('Axis 5');
  });
});

describe('armAlsoOnHint', () => {
  it('is undefined when no bound switch fires ARM or TOGGLE_ARM', () => {
    expect(armAlsoOnHint([axisSwitch(4, 'SET_MODE', 'HIGH')])).toBeUndefined();
  });

  it('names the switch that fires ARM, arrow-suffixed only on the HIGH position', () => {
    expect(armAlsoOnHint([button(1, 'ARM', 'HIGH')])).toBe('Sw 2 ↑');
    expect(armAlsoOnHint([button(1, 'ARM', 'LOW')])).toBe('Sw 2');
  });

  it('also matches TOGGLE_ARM — the same physical arm sequence', () => {
    expect(armAlsoOnHint([axisSwitch(3, 'TOGGLE_ARM', 'HIGH')])).toBe('Axis 4 ↑');
  });
});
