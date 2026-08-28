import { describe, expect, it } from 'vitest';
import {
  armedChip,
  armAlsoOnHint,
  engageBlock,
  engageDisabledReason,
  keyLegendLines,
  latencyLabel,
  modeAlsoOnHint,
  rcReadinessRows,
} from './rc-monitor-logic';
import type { ActionBinding, ManualControlChannelBinding, ReadinessReport } from '../../core/api/models';

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

  it('does not require a gamepad at all when the keyboard source is selected — the same rule the on-screen surface gets', () => {
    expect(engageDisabledReason({ ...BASE, sourceKind: 'keyboard', gamepadConnected: false })).toBeUndefined();
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

const axis = (
  fn: ManualControlChannelBinding['function'],
  sourceIndex: number,
  label: string,
): ManualControlChannelBinding => ({
  source: 'AXIS',
  kind: 'AXIS',
  function: fn,
  travel: 'CENTERED',
  sourceIndex,
  rcChannel: sourceIndex + 1,
  minMicros: 1000,
  centerMicros: 1500,
  maxMicros: 2000,
  label,
});

describe('keyLegendLines', () => {
  it('is empty for a layout with nothing this drawer maps a key to', () => {
    expect(keyLegendLines([])).toEqual([]);
  });

  it('one line for a rover — steering shares the throttle pad, so no arrow-key line', () => {
    const lines = keyLegendLines([axis('STEERING', 0, 'Steering'), axis('THROTTLE', 2, 'Throttle')]);
    expect(lines).toEqual(['W/S throttle · A/D steering']);
  });

  it('two lines for an aircraft — yaw/throttle share a pad, roll/pitch get the freed-up arrow keys', () => {
    const lines = keyLegendLines([
      axis('YAW', 3, 'Yaw'),
      axis('THROTTLE', 2, 'Throttle'),
      axis('ROLL', 0, 'Roll'),
      axis('PITCH', 1, 'Pitch'),
    ]);
    expect(lines).toEqual(['W/S throttle · A/D yaw', '↑↓ pitch · ←→ roll']);
  });

  it('omits a side of a line the layout does not bind, rather than naming a key with nothing behind it', () => {
    expect(keyLegendLines([axis('THROTTLE', 2, 'Throttle')])).toEqual(['W/S throttle']);
  });

  it('reads the bound control\'s own label, never a hardcoded function name', () => {
    expect(keyLegendLines([axis('THROTTLE', 2, 'Power')])).toEqual(['W/S power']);
  });
});

const READY_REPORT: ReadinessReport = {
  assetId: 'asset-1',
  verdict: 'GO',
  evaluatedAt: '2026-08-28T00:00:00Z',
  profileObservedAt: '2026-08-28T00:00:00Z',
  features: [
    { feature: 'rc-relay', label: 'RC relay', status: 'READY', detail: '', remedy: null },
    { feature: 'battery', label: 'Battery', status: 'MISSING', detail: 'No battery telemetry.', remedy: null },
  ],
  blockers: [],
};

const NOT_READY_REPORT: ReadinessReport = {
  ...READY_REPORT,
  verdict: 'NO_GO',
  features: [
    { feature: 'rc-relay', label: 'RC relay', status: 'MISSING', detail: 'GCS sysid not set.', remedy: 'PARAM_WRITE' },
  ],
  blockers: ['rc-relay'],
};

describe('rcReadinessRows', () => {
  it('is empty with no report at all — still loading, or the read failed; never a fabricated warning', () => {
    expect(rcReadinessRows(undefined)).toEqual([]);
  });

  it('is empty when the rc-relay row is READY, even if other unrelated features are not', () => {
    expect(rcReadinessRows(READY_REPORT)).toEqual([]);
  });

  it('is empty when the report never evaluated rc-relay at all', () => {
    expect(rcReadinessRows({ ...READY_REPORT, features: [] })).toEqual([]);
  });

  it('surfaces the rc-relay row, toned and labeled, when it is not READY', () => {
    expect(rcReadinessRows(NOT_READY_REPORT)).toEqual([
      { label: 'RC relay', tone: 'danger', detail: 'GCS sysid not set.' },
    ]);
  });

  it('omits detail rather than rendering an empty string', () => {
    const report: ReadinessReport = {
      ...NOT_READY_REPORT,
      features: [{ feature: 'rc-relay', label: 'RC relay', status: 'DEGRADED', detail: '', remedy: null }],
    };
    expect(rcReadinessRows(report)).toEqual([{ label: 'RC relay', tone: 'warn', detail: undefined }]);
  });
});

describe('engageBlock', () => {
  it('is a plain reason when a more fundamental gate blocks — readiness never even gets consulted', () => {
    expect(engageBlock({ ...BASE, hasAsset: false }, NOT_READY_REPORT)).toEqual({
      reason: 'Pick a drone first.',
    });
  });

  it('is the readiness rows once every other gate is clear and the vehicle is not RC-ready', () => {
    expect(engageBlock(BASE, NOT_READY_REPORT)).toEqual({
      rows: [{ label: 'RC relay', tone: 'danger', detail: 'GCS sysid not set.' }],
    });
  });

  it('renders nothing when every gate is clear and the vehicle is RC-ready', () => {
    expect(engageBlock(BASE, READY_REPORT)).toEqual({});
  });

  it('renders nothing (never a fabricated warning) when no report has loaded yet, even with other gates clear', () => {
    expect(engageBlock(BASE, undefined)).toEqual({});
  });
});
