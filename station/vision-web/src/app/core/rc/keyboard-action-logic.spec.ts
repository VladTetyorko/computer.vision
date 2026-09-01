import { describe, expect, it } from 'vitest';
import {
  actionKeyIdFor,
  holdContinues,
  isTypingTarget,
  newActionKeyPresses,
  resolveActionKey,
  toggleArmVerb,
  type ActionKeyId,
} from './keyboard-action-logic';
import type { ControlAction } from '../api/models';

describe('actionKeyIdFor', () => {
  it('reads Space as EMERGENCY_STOP regardless of any modifier', () => {
    expect(actionKeyIdFor('Space', false)).toBe('EMERGENCY_STOP');
    expect(actionKeyIdFor('Space', true)).toBe('EMERGENCY_STOP');
  });

  it('reads Enter as TOGGLE_ARM only with Shift held', () => {
    expect(actionKeyIdFor('Enter', true)).toBe('TOGGLE_ARM');
  });

  it('leaves a bare Enter (no Shift) unclaimed — an ordinary form-submit key', () => {
    expect(actionKeyIdFor('Enter', false)).toBeUndefined();
  });

  it('reads Digit1-4 as the first four mode keys, in order', () => {
    expect(actionKeyIdFor('Digit1', false)).toBe('MODE_1');
    expect(actionKeyIdFor('Digit2', false)).toBe('MODE_2');
    expect(actionKeyIdFor('Digit3', false)).toBe('MODE_3');
    expect(actionKeyIdFor('Digit4', false)).toBe('MODE_4');
  });

  it('does not claim Digit5 or an axis key', () => {
    expect(actionKeyIdFor('Digit5', false)).toBeUndefined();
    expect(actionKeyIdFor('KeyW', false)).toBeUndefined();
    expect(actionKeyIdFor('ArrowUp', false)).toBeUndefined();
  });
});

describe('resolveActionKey', () => {
  const noneDangerous: ReadonlySet<ControlAction> = new Set();
  const setModeDangerous: ReadonlySet<ControlAction> = new Set(['SET_MODE']);

  it('resolves EMERGENCY_STOP as always immediate, even when the catalogue marks it dangerous', () => {
    const dangerousCatalog: ReadonlySet<ControlAction> = new Set(['EMERGENCY_STOP']);
    const resolved = resolveActionKey('EMERGENCY_STOP', [], dangerousCatalog);
    expect(resolved).toEqual({ action: 'EMERGENCY_STOP', label: 'Space', dangerous: false });
  });

  it('resolves TOGGLE_ARM as always the dangerous hold, even when the catalogue does not mark it', () => {
    const resolved = resolveActionKey('TOGGLE_ARM', [], noneDangerous);
    expect(resolved).toEqual({ action: 'TOGGLE_ARM', label: 'Shift+Enter', dangerous: true });
  });

  it('resolves a mode digit to the vehicle’s own selectableModes, never a hardcoded name', () => {
    const modes = ['MANUAL', 'HOLD', 'STEERING'];
    expect(resolveActionKey('MODE_1', modes, noneDangerous)).toEqual({
      action: 'SET_MODE',
      parameter: 'MANUAL',
      label: '1',
      dangerous: false,
    });
    expect(resolveActionKey('MODE_3', modes, noneDangerous)).toEqual({
      action: 'SET_MODE',
      parameter: 'STEERING',
      label: '3',
      dangerous: false,
    });
  });

  it('resolves to undefined for a digit past what the vehicle actually reports, rather than guessing', () => {
    expect(resolveActionKey('MODE_3', ['MANUAL', 'HOLD'], noneDangerous)).toBeUndefined();
    expect(resolveActionKey('MODE_1', [], noneDangerous)).toBeUndefined();
  });

  it('inherits the hold policy from the catalogue’s own SET_MODE danger flag', () => {
    const modes = ['MANUAL', 'HOLD'];
    expect(resolveActionKey('MODE_1', modes, noneDangerous)?.dangerous).toBe(false);
    expect(resolveActionKey('MODE_1', modes, setModeDangerous)?.dangerous).toBe(true);
  });
});

describe('toggleArmVerb', () => {
  it('reads "disarm" only when telemetry actually reports armed', () => {
    expect(toggleArmVerb(true)).toBe('disarm');
  });

  it('reads "arm" when not armed, and when the armed state is not yet known', () => {
    expect(toggleArmVerb(false)).toBe('arm');
    expect(toggleArmVerb(undefined)).toBe('arm');
  });
});

describe('newActionKeyPresses', () => {
  it('fires the very first press from an empty held-set — unlike a switch, there is no settled first frame', () => {
    const pressed = newActionKeyPresses(new Set(), new Set<ActionKeyId>(['EMERGENCY_STOP']));
    expect(pressed).toEqual(['EMERGENCY_STOP']);
  });

  it('does not repeat while a key stays held across frames', () => {
    const held = new Set<ActionKeyId>(['EMERGENCY_STOP']);
    expect(newActionKeyPresses(held, held)).toEqual([]);
    expect(newActionKeyPresses(held, new Set(held))).toEqual([]);
  });

  it('produces no press on release', () => {
    const pressed = newActionKeyPresses(new Set<ActionKeyId>(['MODE_1']), new Set());
    expect(pressed).toEqual([]);
  });

  it('reports every key that newly landed this frame, when more than one does', () => {
    const pressed = newActionKeyPresses(new Set(), new Set<ActionKeyId>(['MODE_1', 'TOGGLE_ARM']));
    expect([...pressed].sort()).toEqual(['MODE_1', 'TOGGLE_ARM']);
  });
});

describe('holdContinues', () => {
  it('is false when nothing is being held at all', () => {
    expect(holdContinues(undefined, new Set(['TOGGLE_ARM']))).toBe(false);
  });

  it('is true while the held id is still down', () => {
    expect(holdContinues('TOGGLE_ARM', new Set<ActionKeyId>(['TOGGLE_ARM']))).toBe(true);
  });

  it('is false the instant the currently-down set no longer contains it — a blur emptying it included', () => {
    expect(holdContinues('TOGGLE_ARM', new Set())).toBe(false);
    expect(holdContinues('TOGGLE_ARM', new Set<ActionKeyId>(['MODE_1']))).toBe(false);
  });
});

describe('isTypingTarget', () => {
  it('claims input, select, textarea and contenteditable elements', () => {
    expect(isTypingTarget(document.createElement('input'))).toBe(true);
    expect(isTypingTarget(document.createElement('select'))).toBe(true);
    expect(isTypingTarget(document.createElement('textarea'))).toBe(true);

    const editable = document.createElement('div');
    Object.defineProperty(editable, 'isContentEditable', { value: true });
    expect(isTypingTarget(editable)).toBe(true);
  });

  it('does not claim an ordinary element, or a null/non-element target', () => {
    expect(isTypingTarget(document.createElement('div'))).toBe(false);
    expect(isTypingTarget(null)).toBe(false);
  });
});
