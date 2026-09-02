import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { KeyboardRcInputService, RAMP_MS, TICK_MS } from './keyboard-rc-input.service';
import type { ManualControlChannelBinding } from '../api/models';

const axis = (
  fn: ManualControlChannelBinding['function'],
  travel: ManualControlChannelBinding['travel'],
  sourceIndex: number,
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
  label: fn,
});

const THROTTLE = axis('THROTTLE', 'UNIDIRECTIONAL', 2);
const STEERING = axis('STEERING', 'CENTERED', 0);
const ROVER_MAP = [STEERING, THROTTLE];

const YAW = axis('YAW', 'CENTERED', 3);
const ROLL = axis('ROLL', 'CENTERED', 0);
const PITCH = axis('PITCH', 'CENTERED', 1);
const COPTER_MAP = [YAW, THROTTLE, ROLL, PITCH];

function create(): KeyboardRcInputService {
  TestBed.configureTestingModule({ providers: [KeyboardRcInputService] });
  return TestBed.inject(KeyboardRcInputService);
}

function press(code: string): void {
  window.dispatchEvent(new KeyboardEvent('keydown', { code }));
}

function release(code: string): void {
  window.dispatchEvent(new KeyboardEvent('keyup', { code }));
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('KeyboardRcInputService', () => {
  it('produces nothing until bound, and ignores keys until enabled', () => {
    const service = create();
    expect(service.axes()).toEqual([]);

    service.bind(ROVER_MAP);
    press('KeyW'); // not enabled yet — RcSource hasn't selected 'keyboard'
    vi.advanceTimersByTime(RAMP_MS);

    expect(service.axes()[THROTTLE.sourceIndex]).toBe(0);
  });

  it('ramps a bound throttle over time rather than snapping to full', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyW');
    vi.advanceTimersByTime(80); // well short of RAMP_MS

    const partial = service.axes()[THROTTLE.sourceIndex];
    expect(partial).toBeGreaterThan(0);
    expect(partial).toBeLessThan(1);
  });

  it('reaches full deflection once held for the full ramp, and clamps rather than overshooting', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyW');
    vi.advanceTimersByTime(RAMP_MS * 4);

    expect(service.axes()[THROTTLE.sourceIndex]).toBe(1);
  });

  it("springs a centred control back to rest on key-up — a released steering stick straightens", () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyD');
    vi.advanceTimersByTime(80);
    expect(service.axes()[STEERING.sourceIndex]).toBeGreaterThan(0);

    release('KeyD');
    expect(service.axes()[STEERING.sourceIndex]).toBe(0);
  });

  it('holds a unidirectional throttle on release — springing it to idle would drop the aircraft', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyW');
    vi.advanceTimersByTime(80);
    const held = service.axes()[THROTTLE.sourceIndex];

    release('KeyW');
    expect(service.axes()[THROTTLE.sourceIndex]).toBe(held);
  });

  it('opposite keys held together cancel to a standstill — the value stops moving rather than fighting toward either extreme', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyD');
    vi.advanceTimersByTime(40);
    const midpoint = service.axes()[STEERING.sourceIndex];
    expect(midpoint).toBeGreaterThan(0);

    press('KeyA');
    vi.advanceTimersByTime(200);

    expect(service.axes()[STEERING.sourceIndex]).toBe(midpoint);
  });

  it('arrow-up also drives throttle on a one-pad layout, and does not double the ramp rate with W held too', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('ArrowUp');
    vi.advanceTimersByTime(80);
    const arrowOnly = service.axes()[THROTTLE.sourceIndex];

    release('ArrowUp');
    service.bind(ROVER_MAP); // clears the ramped value for a clean second measurement
    press('KeyW');
    press('ArrowUp');
    vi.advanceTimersByTime(80);
    const both = service.axes()[THROTTLE.sourceIndex];

    expect(arrowOnly).toBeGreaterThan(0);
    expect(both).toBeCloseTo(arrowOnly, 5);
  });

  it('arrow keys drive pitch/roll instead of throttle once a second pad exists (two-pad layout)', () => {
    const service = create();
    service.bind(COPTER_MAP);
    service.setEnabled(true);

    press('ArrowUp');
    vi.advanceTimersByTime(80);

    expect(service.axes()[PITCH.sourceIndex]).toBeGreaterThan(0);
    expect(service.axes()[THROTTLE.sourceIndex]).toBe(0);
  });

  it('W/S still drive throttle on a two-pad layout, alongside the arrow keys driving pitch', () => {
    const service = create();
    service.bind(COPTER_MAP);
    service.setEnabled(true);

    press('KeyW');
    vi.advanceTimersByTime(80);

    expect(service.axes()[THROTTLE.sourceIndex]).toBeGreaterThan(0);
  });

  it('a key for a function the layout does not bind does nothing (no roll on a rover)', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('ArrowLeft');
    vi.advanceTimersByTime(RAMP_MS);

    expect(service.axes()).toEqual([0, 0, 0]);
  });

  it('OS key-repeat (repeated keydown for a held key) does not restart the ramp', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyW');
    vi.advanceTimersByTime(80);
    const before = service.axes()[THROTTLE.sourceIndex];
    press('KeyW'); // the browser's own auto-repeat, key never actually released
    vi.advanceTimersByTime(80);
    const after = service.axes()[THROTTLE.sourceIndex];

    expect(after).toBeGreaterThan(before);
  });

  it('a window blur releases every held key immediately — the same deadman a gamepad unplug gives', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyD');
    vi.advanceTimersByTime(80);
    expect(service.axes()[STEERING.sourceIndex]).toBeGreaterThan(0);

    window.dispatchEvent(new Event('blur'));
    expect(service.axes()[STEERING.sourceIndex]).toBe(0);

    // The ramp actually stopped, not just reset once — further time must not resume it.
    vi.advanceTimersByTime(200);
    expect(service.axes()[STEERING.sourceIndex]).toBe(0);
  });

  it('the tab hiding releases every held key, same as a blur', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyW');
    vi.advanceTimersByTime(80);
    expect(service.axes()[THROTTLE.sourceIndex]).toBeGreaterThan(0);

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));

    // Throttle is unidirectional (holds on release) but the key is still gone — no further ramp.
    const atHide = service.axes()[THROTTLE.sourceIndex];
    vi.advanceTimersByTime(200);
    expect(service.axes()[THROTTLE.sourceIndex]).toBe(atHide);

    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });

  it('disabling releases every held key and stops listening entirely', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    press('KeyD');
    vi.advanceTimersByTime(80);
    expect(service.axes()[STEERING.sourceIndex]).toBeGreaterThan(0);

    service.setEnabled(false);
    expect(service.axes()[STEERING.sourceIndex]).toBe(0);

    press('KeyD'); // no longer listening — RcSource picked a different source
    vi.advanceTimersByTime(RAMP_MS);
    expect(service.axes()[STEERING.sourceIndex]).toBe(0);
  });

  it('ignores keys typed into a form field, even while enabled', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);

    const input = document.createElement('input');
    document.body.appendChild(input);
    input.dispatchEvent(new KeyboardEvent('keydown', { code: 'KeyW', bubbles: true }));
    vi.advanceTimersByTime(RAMP_MS);

    expect(service.axes()[THROTTLE.sourceIndex]).toBe(0);
    document.body.removeChild(input);
  });

  describe('action keys (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D3)', () => {
    it('reports Space as EMERGENCY_STOP the instant it is pressed', () => {
      const service = create();
      service.setEnabled(true);

      press('Space');

      expect(service.actionKeysDown()).toEqual(new Set(['EMERGENCY_STOP']));
    });

    it('reports Shift+Enter as TOGGLE_ARM, and a bare Enter as nothing', () => {
      const service = create();
      service.setEnabled(true);

      window.dispatchEvent(new KeyboardEvent('keydown', { code: 'Enter' }));
      expect(service.actionKeysDown().size).toBe(0);

      window.dispatchEvent(new KeyboardEvent('keydown', { code: 'Enter', shiftKey: true }));
      expect(service.actionKeysDown()).toEqual(new Set(['TOGGLE_ARM']));
    });

    it('reports digits 1-4 as their own mode keys', () => {
      const service = create();
      service.setEnabled(true);

      press('Digit3');

      expect(service.actionKeysDown()).toEqual(new Set(['MODE_3']));
    });

    it('does not register auto-repeat as a fresh press — the held set stays a one-element set', () => {
      const service = create();
      service.setEnabled(true);

      press('Space');
      press('Space'); // the browser's own auto-repeat, key never actually released

      expect(service.actionKeysDown()).toEqual(new Set(['EMERGENCY_STOP']));
    });

    it('clears on release', () => {
      const service = create();
      service.setEnabled(true);

      press('Space');
      release('Space');

      expect(service.actionKeysDown().size).toBe(0);
    });

    it('republishes with a fresh identity every tick while held, so a reactive reader keeps re-evaluating', () => {
      const service = create();
      service.setEnabled(true);

      press('Space');
      const first = service.actionKeysDown();
      vi.advanceTimersByTime(TICK_MS);
      const second = service.actionKeysDown();

      expect(second).not.toBe(first);
      expect(second).toEqual(first);
    });

    it('a window blur clears any held action key immediately, same as it clears axis keys', () => {
      const service = create();
      service.setEnabled(true);

      press('Space');
      expect(service.actionKeysDown().size).toBe(1);

      window.dispatchEvent(new Event('blur'));

      expect(service.actionKeysDown().size).toBe(0);
    });

    it('the tab hiding clears any held action key, same as a blur', () => {
      const service = create();
      service.setEnabled(true);

      press('Digit1');
      expect(service.actionKeysDown().size).toBe(1);

      Object.defineProperty(document, 'hidden', { value: true, configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));

      expect(service.actionKeysDown().size).toBe(0);
      Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    });

    it('disabling clears any held action key and stops listening entirely', () => {
      const service = create();
      service.setEnabled(true);

      press('Space');
      service.setEnabled(false);
      expect(service.actionKeysDown().size).toBe(0);

      press('Space'); // no longer listening
      expect(service.actionKeysDown().size).toBe(0);
    });

    it('ignores an action key typed into a form field, exactly like an axis key', () => {
      const service = create();
      service.setEnabled(true);

      const input = document.createElement('input');
      document.body.appendChild(input);
      input.dispatchEvent(new KeyboardEvent('keydown', { code: 'Digit1', bubbles: true }));

      expect(service.actionKeysDown().size).toBe(0);
      document.body.removeChild(input);
    });

    it('an axis key and an action key can be held at the same time, independently', () => {
      const service = create();
      service.bind(ROVER_MAP);
      service.setEnabled(true);

      press('KeyW');
      press('Space');
      vi.advanceTimersByTime(80);

      expect(service.axes()[THROTTLE.sourceIndex]).toBeGreaterThan(0);
      expect(service.actionKeysDown()).toEqual(new Set(['EMERGENCY_STOP']));
    });
  });

  describe('axisKeysDown (docs/plans/active/FLY-CONTROL-UX-PLAN.md §3 — HUD key-glyph ticker)', () => {
    it('starts empty', () => {
      const service = create();
      expect(service.axisKeysDown().size).toBe(0);
    });

    it('reports a bound key the instant it is pressed', () => {
      const service = create();
      service.bind(ROVER_MAP);
      service.setEnabled(true);

      press('KeyW');

      expect(service.axisKeysDown()).toEqual(new Set(['KeyW']));
    });

    it('reports two keys held at once, e.g. opposite steering keys', () => {
      const service = create();
      service.bind(ROVER_MAP);
      service.setEnabled(true);

      press('KeyD');
      press('KeyA');

      expect(service.axisKeysDown()).toEqual(new Set(['KeyD', 'KeyA']));
    });

    it('drops a key from the set on release, keeping the rest', () => {
      const service = create();
      service.bind(ROVER_MAP);
      service.setEnabled(true);

      press('KeyW');
      press('KeyD');
      release('KeyW');

      expect(service.axisKeysDown()).toEqual(new Set(['KeyD']));
    });

    it('never reports a key for a function the layout does not bind (no roll on a rover)', () => {
      const service = create();
      service.bind(ROVER_MAP);
      service.setEnabled(true);

      press('ArrowLeft');

      expect(service.axisKeysDown().size).toBe(0);
    });

    it('is unaffected by action keys held alongside — the two sets are independent', () => {
      const service = create();
      service.bind(ROVER_MAP);
      service.setEnabled(true);

      press('KeyW');
      press('Space');

      expect(service.axisKeysDown()).toEqual(new Set(['KeyW']));
      expect(service.actionKeysDown()).toEqual(new Set(['EMERGENCY_STOP']));
    });

    it('a window blur clears it immediately, same as it clears the ramped values', () => {
      const service = create();
      service.bind(ROVER_MAP);
      service.setEnabled(true);

      press('KeyW');
      window.dispatchEvent(new Event('blur'));

      expect(service.axisKeysDown().size).toBe(0);
    });

    it('disabling clears it and stops listening entirely', () => {
      const service = create();
      service.bind(ROVER_MAP);
      service.setEnabled(true);

      press('KeyW');
      service.setEnabled(false);
      expect(service.axisKeysDown().size).toBe(0);

      press('KeyW'); // no longer listening
      expect(service.axisKeysDown().size).toBe(0);
    });
  });

  it('re-binding to a fresh map clears any ramped value', () => {
    const service = create();
    service.bind(ROVER_MAP);
    service.setEnabled(true);
    press('KeyW');
    vi.advanceTimersByTime(80);
    expect(service.axes()[THROTTLE.sourceIndex]).toBeGreaterThan(0);

    service.bind(ROVER_MAP);
    expect(service.axes()[THROTTLE.sourceIndex]).toBe(0);
  });
});
