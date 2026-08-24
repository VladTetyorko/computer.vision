import { describe, expect, it } from 'vitest';
import { channelBindingLabel, engageDisabledReason, latencyLabel } from './rc-monitor-logic';

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

describe('channelBindingLabel', () => {
  it('renders "<label> → CH<n>"', () => {
    expect(
      channelBindingLabel({
        source: 'AXIS',
        kind: 'AXIS',
        function: 'ROLL',
        travel: 'CENTERED',
        sourceIndex: 0,
        rcChannel: 1,
        minMicros: 1000,
        centerMicros: 1500,
        maxMicros: 2000,
        label: 'Roll',
      }),
    ).toBe('Roll → CH1');
    expect(
      channelBindingLabel({
        source: 'AXIS',
        kind: 'AXIS',
        function: 'THROTTLE',
        travel: 'UNIDIRECTIONAL',
        sourceIndex: 2,
        rcChannel: 3,
        minMicros: 1000,
        centerMicros: 1000,
        maxMicros: 2000,
        label: 'Throttle',
      }),
    ).toBe('Throttle → CH3');
  });
});
