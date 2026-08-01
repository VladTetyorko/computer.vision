import { describe, expect, it } from 'vitest';
import { channelBindingLabel, engageDisabledReason, latencyLabel } from './rc-monitor-logic';

const BASE = { hasAsset: true, canCommand: true, gamepadSupported: true, gamepadConnected: true, engageState: 'idle' as const };

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

  it('reports not commandable before the gamepad checks', () => {
    expect(engageDisabledReason({ ...BASE, canCommand: false, gamepadConnected: false })).toBe(
      "This drone isn't commandable right now.",
    );
  });

  it('reports unsupported gamepad API before "not connected"', () => {
    expect(engageDisabledReason({ ...BASE, gamepadSupported: false, gamepadConnected: false })).toBe(
      "This browser doesn't expose gamepad input.",
    );
  });

  it('reports the transmitter not being plugged in as the last-mile reason', () => {
    expect(engageDisabledReason({ ...BASE, gamepadConnected: false })).toBe('Plug your transmitter in first.');
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
    expect(channelBindingLabel({ source: 'AXIS', sourceIndex: 0, rcChannel: 1, label: 'Roll' })).toBe('Roll → CH1');
    expect(channelBindingLabel({ source: 'BUTTON', sourceIndex: 0, rcChannel: 5, label: 'Aux 1' })).toBe(
      'Aux 1 → CH5',
    );
  });
});
