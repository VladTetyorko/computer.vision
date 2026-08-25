import { describe, expect, it } from 'vitest';
import { channelOutputs, microsFor, microsToPercent, restMicros } from './channel-output-logic';
import type { ControlDraft, ProfileDraft } from './controller-setup-logic';
import type { ControlCatalog } from '../api/models';

const CATALOG = {
  vehicleKinds: [],
  inputKinds: [],
  positions: [],
  functions: [
    { name: 'STEERING', label: 'Steering' },
    { name: 'THROTTLE', label: 'Throttle' },
  ],
  actions: [],
  auxFunctions: [],
  maxRcChannel: 4,
} as unknown as ControlCatalog;

function axis(over: Partial<ControlDraft> = {}): ControlDraft {
  return {
    source: 'AXIS',
    sourceIndex: 0,
    kind: 'AXIS',
    role: 'CHANNEL',
    function: 'THROTTLE',
    rcChannel: 3,
    travel: 'CENTERED',
    reversed: false,
    positions: [],
    ...over,
  };
}

function draft(controls: readonly ControlDraft[]): ProfileDraft {
  return { id: 'p1', kind: 'ROVER', name: 'Bench rover', controls, stickMode: 2, forwardIsUp: true };
}

describe('restMicros', () => {
  it('rests at the minimum when the control travels one way', () => {
    expect(restMicros(axis({ travel: 'UNIDIRECTIONAL' }))).toBe(1000);
  });

  it('rests at the centre otherwise', () => {
    expect(restMicros(axis())).toBe(1500);
  });
});

describe('microsFor', () => {
  it('maps a centred axis piecewise around its rest point', () => {
    expect(microsFor(axis(), 0)).toBe(1500);
    expect(microsFor(axis(), 1)).toBe(2000);
    expect(microsFor(axis(), -1)).toBe(1000);
    expect(microsFor(axis(), 0.5)).toBe(1750);
  });

  it('pins a one-way axis at idle for anything below rest, rather than reversing', () => {
    const throttle = axis({ travel: 'UNIDIRECTIONAL' });

    expect(microsFor(throttle, 0)).toBe(1000);
    expect(microsFor(throttle, -1)).toBe(1000);
    expect(microsFor(throttle, 1)).toBe(2000);
  });

  it('reverses the input, not the output range', () => {
    expect(microsFor(axis({ reversed: true }), 1)).toBe(1000);
    expect(microsFor(axis({ reversed: true }), -1)).toBe(2000);
  });

  it('clamps a reading past the ends of its natural range', () => {
    expect(microsFor(axis(), 4)).toBe(2000);
    expect(microsFor(axis(), -4)).toBe(1000);
  });

  it('snaps a switch to a detent instead of relaying where it happens to sit', () => {
    const sw = axis({ kind: 'SWITCH_3' });

    expect(microsFor(sw, -1)).toBe(1000);
    expect(microsFor(sw, 0.1)).toBe(1500);
    expect(microsFor(sw, 1)).toBe(2000);
  });

  it('reverses a switch by its position, so the middle stays the middle', () => {
    const sw = axis({ kind: 'SWITCH_3', reversed: true });

    expect(microsFor(sw, -1)).toBe(2000);
    expect(microsFor(sw, 0)).toBe(1500);
    expect(microsFor(sw, 1)).toBe(1000);
  });

  it('maps a button straight across, with no rest point in the middle', () => {
    const button = axis({ source: 'BUTTON', kind: 'BUTTON', sourceIndex: 2, travel: 'UNIDIRECTIONAL' });

    expect(microsFor(button, 0)).toBe(1000);
    expect(microsFor(button, 1)).toBe(2000);
    expect(microsFor(button, 0.5)).toBe(1500);
  });
});

describe('microsToPercent', () => {
  it('places the envelope across the full width', () => {
    expect(microsToPercent(1000)).toBe(0);
    expect(microsToPercent(1500)).toBe(50);
    expect(microsToPercent(2000)).toBe(100);
  });
});

describe('channelOutputs', () => {
  it('lists every relayed channel, driven or not', () => {
    const outputs = channelOutputs(draft([axis()]), [0], [], CATALOG);

    expect(outputs.map((o) => o.rcChannel)).toEqual([1, 2, 3, 4]);
  });

  it('says a channel nothing drives is not sent, rather than showing a resting 1500', () => {
    const [ch1] = channelOutputs(draft([axis()]), [0], [], CATALOG);

    expect(ch1.micros).toBeUndefined();
    expect(ch1.controlLabel).toBeUndefined();
  });

  it('names the control and the function behind a driven channel', () => {
    const outputs = channelOutputs(draft([axis({ sourceIndex: 2 })]), [0, 0, 0.5], [], CATALOG);
    const ch3 = outputs[2];

    expect(ch3.micros).toBe(1750);
    expect(ch3.controlLabel).toBe('Axis 3');
    expect(ch3.functionLabel).toBe('Throttle');
    expect(ch3.controlKey).toBe('AXIS:2');
  });

  it('reads a missing reading as rest instead of failing', () => {
    const outputs = channelOutputs(draft([axis({ sourceIndex: 9 })]), [0], [], CATALOG);

    expect(outputs[2].micros).toBe(1500);
  });

  it('ignores a control that fires commands — it drives no channel at all', () => {
    const outputs = channelOutputs(draft([axis({ role: 'ACTIONS' })]), [0], [], CATALOG);

    expect(outputs.every((o) => o.micros === undefined)).toBe(true);
  });

  it('marks where a one-way channel rests, so its bar can show idle', () => {
    const outputs = channelOutputs(draft([axis({ travel: 'UNIDIRECTIONAL' })]), [0], [], CATALOG);

    expect(outputs[2].restPercent).toBe(0);
  });
});
