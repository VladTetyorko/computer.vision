import { beforeEach, describe, expect, it } from 'vitest';
import { PanelState, readPersistedFlag, readPersistedString, writePersistedFlag, writePersistedString } from './panel-state';

describe('panel-state', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns the fallback when nothing was ever persisted', () => {
    expect(readPersistedFlag('vision.test.flag', true)).toBe(true);
    expect(readPersistedFlag('vision.test.flag', false)).toBe(false);
  });

  it('round-trips a written value regardless of the fallback', () => {
    writePersistedFlag('vision.test.flag', false);
    expect(readPersistedFlag('vision.test.flag', true)).toBe(false);

    writePersistedFlag('vision.test.flag', true);
    expect(readPersistedFlag('vision.test.flag', false)).toBe(true);
  });

  it('keys are independent of one another', () => {
    writePersistedFlag('vision.test.a', true);
    writePersistedFlag('vision.test.b', false);

    expect(readPersistedFlag('vision.test.a', false)).toBe(true);
    expect(readPersistedFlag('vision.test.b', true)).toBe(false);
  });
});

describe('readPersistedString / writePersistedString', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns the fallback (including null) when nothing was ever persisted', () => {
    expect(readPersistedString('vision.test.str', 'fallback')).toBe('fallback');
    expect(readPersistedString('vision.test.str', null)).toBeNull();
  });

  it('round-trips a written string regardless of the fallback', () => {
    writePersistedString('vision.test.str', 'cv');
    expect(readPersistedString('vision.test.str', null)).toBe('cv');
  });

  it('writing null removes the key outright, not the literal text "null"', () => {
    writePersistedString('vision.test.str', 'cv');
    writePersistedString('vision.test.str', null);

    expect(localStorage.getItem('vision.test.str')).toBeNull();
    expect(readPersistedString('vision.test.str', 'fallback')).toBe('fallback');
  });
});

describe('PanelState', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('starts with nothing active when constructed with no storage key', () => {
    const panels = new PanelState();
    expect(panels.active()).toBeNull();
    expect(panels.isOpen('flight')).toBe(false);
  });

  it('open() activates the given id and isOpen() reflects it', () => {
    const panels = new PanelState();
    panels.open('flight');

    expect(panels.active()).toBe('flight');
    expect(panels.isOpen('flight')).toBe(true);
    expect(panels.isOpen('cv')).toBe(false);
  });

  it('opening a second panel closes the first — one open at a time', () => {
    const panels = new PanelState();
    panels.open('flight');
    panels.open('cv');

    expect(panels.active()).toBe('cv');
    expect(panels.isOpen('flight')).toBe(false);
    expect(panels.isOpen('cv')).toBe(true);
  });

  it('close() clears the active panel', () => {
    const panels = new PanelState();
    panels.open('flight');
    panels.close();

    expect(panels.active()).toBeNull();
  });

  it('toggle() opens a closed panel and closes an already-open one', () => {
    const panels = new PanelState();
    panels.toggle('help');
    expect(panels.active()).toBe('help');

    panels.toggle('help');
    expect(panels.active()).toBeNull();
  });

  it('toggle() on a different id switches the active panel rather than closing it', () => {
    const panels = new PanelState();
    panels.open('flight');
    panels.toggle('cv');

    expect(panels.active()).toBe('cv');
  });

  it('with no storageKey, nothing is written to localStorage', () => {
    const panels = new PanelState();
    panels.open('flight');

    expect(localStorage.length).toBe(0);
  });

  it('with a storageKey, the active id round-trips through localStorage', () => {
    const panels = new PanelState('vision.test.panel');
    panels.open('flight');

    expect(readPersistedString('vision.test.panel', null)).toBe('flight');

    const reopened = new PanelState('vision.test.panel');
    expect(reopened.active()).toBe('flight');
  });

  it('with a storageKey, close() persists as closed (not a stale leftover id)', () => {
    const panels = new PanelState('vision.test.panel');
    panels.open('flight');
    panels.close();

    expect(readPersistedString('vision.test.panel', 'fallback')).toBe('fallback');

    const reopened = new PanelState('vision.test.panel');
    expect(reopened.active()).toBeNull();
  });
});
