import { describe, expect, it } from 'vitest';
import type { ManualControlChannelBinding } from '../api/models';
import { axisKeyGlyphs, hudBadgeFor, hudElementsFrom, hudTransitionToast, openTakeControlDisabledReason, sourceLocked } from './fly-hud-logic';

const axis = (
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

const STEERING = axis('STEERING', 'CENTERED', 0, 'Steering');
const THROTTLE_UNI = axis('THROTTLE', 'UNIDIRECTIONAL', 2, 'Throttle');
const YAW = axis('YAW', 'CENTERED', 3, 'Yaw');
const ROLL = axis('ROLL', 'CENTERED', 0, 'Roll');
const PITCH = axis('PITCH', 'CENTERED', 1, 'Pitch');
const THROTTLE_CENTERED = axis('THROTTLE', 'CENTERED', 2, 'Throttle');

describe('hudBadgeFor', () => {
  it('is Not commandable when the vehicle cannot be commanded, regardless of source', () => {
    expect(hudBadgeFor({ canCommand: false, sourceKind: 'virtual', gamepadConnected: false })).toEqual({
      text: 'Not commandable',
      icon: 'alert',
    });
  });

  it('is No link when the gamepad source is selected but nothing is connected', () => {
    expect(hudBadgeFor({ canCommand: true, sourceKind: 'gamepad', gamepadConnected: false })).toEqual({
      text: 'No link',
      icon: 'signal',
    });
  });

  it('is Ready with the gamepad selected and connected', () => {
    expect(hudBadgeFor({ canCommand: true, sourceKind: 'gamepad', gamepadConnected: true })).toEqual({
      text: 'Ready',
      icon: 'check',
    });
  });

  it('is Ready on the on-screen source regardless of gamepad connection — the surface is always available', () => {
    expect(hudBadgeFor({ canCommand: true, sourceKind: 'virtual', gamepadConnected: false })).toEqual({
      text: 'Ready',
      icon: 'check',
    });
  });

  it('is Ready on the keyboard source too', () => {
    expect(hudBadgeFor({ canCommand: true, sourceKind: 'keyboard', gamepadConnected: false })).toEqual({
      text: 'Ready',
      icon: 'check',
    });
  });

  it('not-commandable outranks a missing gamepad link', () => {
    expect(hudBadgeFor({ canCommand: false, sourceKind: 'gamepad', gamepadConnected: false })).toEqual({
      text: 'Not commandable',
      icon: 'alert',
    });
  });
});

describe('hudElementsFrom', () => {
  it('gives a rover one bar (steering) plus a separate throttle element, throttle last', () => {
    const elements = hudElementsFrom([STEERING, THROTTLE_UNI]);
    expect(elements.map((e) => e.kind)).toEqual(['bar', 'throttle']);
    expect(elements[0].label).toBe('Steering');
    expect(elements[1].label).toBe('Throttle');
  });

  it('gives a copter one glyph2d (roll/pitch) plus a yaw bar plus a separate throttle, glyph first, throttle last', () => {
    const elements = hudElementsFrom([YAW, THROTTLE_CENTERED, ROLL, PITCH]);
    expect(elements.map((e) => e.kind)).toEqual(['glyph2d', 'bar', 'throttle']);
    expect(elements[0].x).toBe(ROLL);
    expect(elements[0].y).toBe(PITCH);
    expect(elements[1].label).toBe('Yaw');
    expect(elements[2].label).toBe('Throttle');
  });

  it('never merges throttle into a 2D pad the way padsFrom does for the transmitter picture', () => {
    // A plane-like map that padsFrom would pair (YAW, THROTTLE) onto one pad — the HUD keeps them separate.
    const elements = hudElementsFrom([YAW, THROTTLE_CENTERED]);
    expect(elements).toEqual([
      { id: 'YAW', kind: 'bar', label: 'Yaw', x: YAW },
      { id: 'THROTTLE', kind: 'throttle', label: 'Throttle', y: THROTTLE_CENTERED },
    ]);
  });

  it('omits throttle entirely when the map binds none, rather than fabricating a rest element', () => {
    const elements = hudElementsFrom([ROLL, PITCH]);
    expect(elements.map((e) => e.kind)).toEqual(['glyph2d']);
  });

  it('gives an unpaired roll with no pitch its own bar rather than dropping it', () => {
    const elements = hudElementsFrom([ROLL]);
    expect(elements).toEqual([{ id: 'ROLL', kind: 'bar', label: 'Roll', x: ROLL }]);
  });

  it('skips buttons and any other non-axis binding', () => {
    const button: ManualControlChannelBinding = { ...STEERING, source: 'BUTTON', kind: 'BUTTON', function: 'AUX_1' };
    const elements = hudElementsFrom([STEERING, button]);
    expect(elements).toEqual([{ id: 'STEERING', kind: 'bar', label: 'Steering', x: STEERING }]);
  });

  it('returns nothing for an empty map', () => {
    expect(hudElementsFrom([])).toEqual([]);
  });
});

describe('sourceLocked', () => {
  it('is locked while engaging or engaged', () => {
    expect(sourceLocked('engaging')).toBe(true);
    expect(sourceLocked('engaged')).toBe(true);
  });

  it('is unlocked idle, denied, or released', () => {
    expect(sourceLocked('idle')).toBe(false);
    expect(sourceLocked('denied')).toBe(false);
    expect(sourceLocked('released')).toBe(false);
  });
});

describe('openTakeControlDisabledReason (docs/plans/active/FLY-FLOW-PLAN.md §4 W4 — the connect ritual\'s own "open" gate)', () => {
  it('is enabled once the vehicle is commandable and nothing is already in flight', () => {
    expect(openTakeControlDisabledReason({ canCommand: true, engageState: 'idle' })).toBeUndefined();
  });

  it('blocks opening while a handshake is already engaging', () => {
    expect(openTakeControlDisabledReason({ canCommand: true, engageState: 'engaging' })).toBe('Engaging…');
  });

  it('blocks opening when the vehicle is not commandable', () => {
    expect(openTakeControlDisabledReason({ canCommand: false, engageState: 'idle' })).toBe("This drone isn't commandable right now.");
  });

  it('never blocks on the selected source — that is exactly what the modal itself lets the operator fix', () => {
    // Unlike `engageDisabledReason`, there is no `sourceKind`/`gamepadConnected` in this gate's input
    // at all — a disconnected gamepad must never strand the operator outside the one door that lets
    // them switch away from it.
    expect(openTakeControlDisabledReason({ canCommand: true, engageState: 'denied' })).toBeUndefined();
    expect(openTakeControlDisabledReason({ canCommand: true, engageState: 'released' })).toBeUndefined();
  });
});

describe('hudTransitionToast (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3 — sentences become toasts)', () => {
  it('fires an error toast on the edge into denied, with the server reason', () => {
    expect(hudTransitionToast('engaging', 'denied', 'No live source address.', false)).toEqual({
      kind: 'error',
      text: 'Control denied — No live source address.',
    });
  });

  it('falls back to a generic denial sentence with no server reason', () => {
    expect(hudTransitionToast('engaging', 'denied', undefined, false)).toEqual({
      kind: 'error',
      text: 'Control denied — the vehicle refused control.',
    });
  });

  it('fires a warn toast on the edge into a watchdog-tripped release', () => {
    expect(hudTransitionToast('engaged', 'released', undefined, true)).toEqual({
      kind: 'warn',
      text: 'Control released — failsafe took over.',
    });
  });

  it('fires nothing for a plain operator-initiated release — clicking Release is not a surprise', () => {
    expect(hudTransitionToast('engaged', 'released', undefined, false)).toBeUndefined();
  });

  it('fires nothing when the state has not actually changed', () => {
    expect(hudTransitionToast('denied', 'denied', 'x', false)).toBeUndefined();
    expect(hudTransitionToast('released', 'released', undefined, true)).toBeUndefined();
  });

  it('fires nothing for every other transition (idle, engaging, engaged)', () => {
    expect(hudTransitionToast('idle', 'engaging', undefined, false)).toBeUndefined();
    expect(hudTransitionToast('engaging', 'engaged', undefined, false)).toBeUndefined();
    expect(hudTransitionToast('released', 'idle', undefined, false)).toBeUndefined();
  });
});

describe('axisKeyGlyphs (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3 — keyboard key-glyph ticker)', () => {
  it('lists only the primary W/A/S/D pad on a one-pad (rover) layout', () => {
    expect(axisKeyGlyphs(true)).toEqual([
      { code: 'KeyW', label: 'W' },
      { code: 'KeyA', label: 'A' },
      { code: 'KeyS', label: 'S' },
      { code: 'KeyD', label: 'D' },
    ]);
  });

  it('adds the arrow pad once a second pad exists (a two-pad aircraft layout)', () => {
    const glyphs = axisKeyGlyphs(false);
    expect(glyphs.map((g) => g.code)).toEqual(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']);
    expect(glyphs.map((g) => g.label)).toEqual(['W', 'A', 'S', 'D', '↑', '↓', '←', '→']);
  });
});
