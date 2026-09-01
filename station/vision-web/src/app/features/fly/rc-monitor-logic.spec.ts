import { describe, expect, it } from 'vitest';
import {
  actionKeyRows,
  armedChip,
  armAlsoOnHint,
  engageBlock,
  engageDisabledReason,
  keyLegendLines,
  latencyLabel,
  modeAlsoOnHint,
  rcReadinessRows,
  resolveSessionAffordance,
  sampleIsStale,
} from './rc-monitor-logic';
import type { ActionBinding, ControlAction, ManualControlChannelBinding, ReadinessReport } from '../../core/api/models';

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
    expect(armedChip(undefined, undefined)).toEqual({ text: '—', tone: 'neutral' });
  });

  it('renders ARMED toned ok while the reading is live, mirroring the OSD chip bar\'s own convention', () => {
    expect(armedChip(true, 0)).toEqual({ text: 'ARMED', tone: 'ok' });
    expect(armedChip(true, undefined)).toEqual({ text: 'ARMED', tone: 'ok' });
  });

  it('renders DISARMED toned neutral while the reading is live — the grounded, routine state', () => {
    expect(armedChip(false, 0)).toEqual({ text: 'DISARMED', tone: 'neutral' });
  });

  it('stays ARMED/DISARMED while the reading is merely aging, not yet stale', () => {
    expect(armedChip(true, 10)).toEqual({ text: 'ARMED', tone: 'ok' });
    expect(armedChip(false, 10)).toEqual({ text: 'DISARMED', tone: 'neutral' });
  });

  it('drops the ok tone and reads as a past fact once the sample is stale (docs/plans/active/OPERATOR-UX-3-PLAN.md H1)', () => {
    expect(armedChip(true, 353099)).toEqual({ text: 'Armed 4d 2h ago', tone: 'neutral' });
  });

  it('a stale disarmed reading reads the same way, still neutral', () => {
    expect(armedChip(false, 353099)).toEqual({ text: 'Disarmed 4d 2h ago', tone: 'neutral' });
  });
});

describe('sampleIsStale', () => {
  it('is false with no age at all', () => {
    expect(sampleIsStale(undefined)).toBe(false);
  });

  it('is false while live/aging', () => {
    expect(sampleIsStale(0)).toBe(false);
    expect(sampleIsStale(10)).toBe(false);
  });

  it('is true past the same red threshold armedChip itself uses', () => {
    expect(sampleIsStale(10.01)).toBe(true);
    expect(sampleIsStale(353099)).toBe(true);
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

const NO_DANGER = new Set<ControlAction>();
const MODE_DANGEROUS = new Set<ControlAction>(['SET_MODE']);

describe('actionKeyRows (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3, wave W2)', () => {
  it('always lists Space and Shift+Enter, Space never dangerous, Shift+Enter always dangerous', () => {
    const rows = actionKeyRows([], NO_DANGER, false, new Set(), undefined);
    expect(rows[0]).toMatchObject({ id: 'EMERGENCY_STOP', keyLabel: 'Space', text: 'Emergency stop', dangerous: false });
    expect(rows[1]).toMatchObject({ id: 'TOGGLE_ARM', keyLabel: 'Shift+Enter', dangerous: true });
  });

  it('mode rows appear/disappear with the vehicle\'s own selectableModes — never a hardcoded list', () => {
    const none = actionKeyRows([], NO_DANGER, false, new Set(), undefined);
    expect(none.map((r) => r.id)).toEqual(['EMERGENCY_STOP', 'TOGGLE_ARM']);

    const two = actionKeyRows(['MANUAL', 'HOLD'], NO_DANGER, false, new Set(), undefined);
    expect(two.map((r) => r.id)).toEqual(['EMERGENCY_STOP', 'TOGGLE_ARM', 'MODE_1', 'MODE_2']);
    expect(two.find((r) => r.id === 'MODE_1')).toMatchObject({ keyLabel: '1', text: 'MANUAL' });
    expect(two.find((r) => r.id === 'MODE_2')).toMatchObject({ keyLabel: '2', text: 'HOLD' });
  });

  it('shows nothing for a digit past the reported mode list, never a placeholder row', () => {
    const rows = actionKeyRows(['MANUAL', 'HOLD'], NO_DANGER, false, new Set(), undefined);
    expect(rows.some((r) => r.id === 'MODE_3' || r.id === 'MODE_4')).toBe(false);
  });

  it("Arm/Disarm's row text follows the live armed flag, not a remembered toggle", () => {
    const disarmed = actionKeyRows([], NO_DANGER, false, new Set(), undefined);
    expect(disarmed.find((r) => r.id === 'TOGGLE_ARM')?.text).toBe('Arm');

    const armed = actionKeyRows([], NO_DANGER, true, new Set(), undefined);
    expect(armed.find((r) => r.id === 'TOGGLE_ARM')?.text).toBe('Disarm');

    const unknown = actionKeyRows([], NO_DANGER, undefined, new Set(), undefined);
    expect(unknown.find((r) => r.id === 'TOGGLE_ARM')?.text).toBe('Arm'); // unknown reads as the safer "arm"
  });

  it('a mode digit only holds when the catalogue itself marks SET_MODE dangerous — same rule the dispatcher uses', () => {
    const calm = actionKeyRows(['GUIDED'], NO_DANGER, false, new Set(), undefined);
    expect(calm.find((r) => r.id === 'MODE_1')?.dangerous).toBe(false);

    const cautious = actionKeyRows(['GUIDED'], MODE_DANGEROUS, false, new Set(), undefined);
    expect(cautious.find((r) => r.id === 'MODE_1')?.dangerous).toBe(true);
  });

  it('pressed reflects membership in the live held-key set', () => {
    const rows = actionKeyRows([], NO_DANGER, false, new Set(['EMERGENCY_STOP']), undefined);
    expect(rows.find((r) => r.id === 'EMERGENCY_STOP')?.pressed).toBe(true);
    expect(rows.find((r) => r.id === 'TOGGLE_ARM')?.pressed).toBe(false);
  });

  it("holding is true only for the row the dispatcher's own hold notice names", () => {
    const rows = actionKeyRows([], NO_DANGER, false, new Set(), 'Hold Shift+Enter to arm');
    expect(rows.find((r) => r.id === 'TOGGLE_ARM')?.holding).toBe(true);
    expect(rows.find((r) => r.id === 'EMERGENCY_STOP')?.holding).toBe(false);
  });

  it('holding is false for every row while nothing is mid-hold', () => {
    const rows = actionKeyRows(['MANUAL'], MODE_DANGEROUS, false, new Set(), undefined);
    expect(rows.every((r) => r.holding === false)).toBe(true);
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

describe('resolveSessionAffordance', () => {
  it('is none for an asset with no telemetry device — nothing this button could open', () => {
    expect(resolveSessionAffordance(false, false, false)).toBe('none');
    expect(resolveSessionAffordance(false, false, true)).toBe('none');
  });

  it('is none while a video stream is live, regardless of the operator-engaged fact — starting the stream already opened the usage', () => {
    expect(resolveSessionAffordance(true, true, false)).toBe('none');
    expect(resolveSessionAffordance(true, true, true)).toBe('none');
  });

  it('is engage for a telemetry-only asset with no open operator usage', () => {
    expect(resolveSessionAffordance(true, false, false)).toBe('engage');
  });

  it('is end once the operator has an open usage and no stream is live', () => {
    expect(resolveSessionAffordance(true, false, true)).toBe('end');
  });
});
