import { beforeEach, describe, expect, it } from 'vitest';
import { readPersistedFlag, readPersistedString, writePersistedFlag, writePersistedString } from './panel-state';

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
