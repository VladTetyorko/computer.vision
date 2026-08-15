import { describe, expect, it } from 'vitest';
import {
  REGISTRATION_NUMBER_ATTRIBUTE_KEY,
  registrationNumberOf,
  withRegistrationNumber,
  withoutRegistrationNumber,
} from './asset-attributes';

describe('registrationNumberOf', () => {
  it('reads the value under the conventional key', () => {
    expect(registrationNumberOf({ [REGISTRATION_NUMBER_ATTRIBUTE_KEY]: 'N12345' })).toBe('N12345');
  });

  it('returns undefined when the key is absent', () => {
    expect(registrationNumberOf({})).toBeUndefined();
  });

  it('returns undefined for a blank value', () => {
    expect(registrationNumberOf({ [REGISTRATION_NUMBER_ATTRIBUTE_KEY]: '   ' })).toBeUndefined();
  });

  it('ignores unrelated attributes', () => {
    expect(registrationNumberOf({ color: 'red' })).toBeUndefined();
  });
});

describe('withRegistrationNumber', () => {
  it('adds the trimmed value under the conventional key', () => {
    expect(withRegistrationNumber({}, '  N12345  ')).toEqual({ registrationNumber: 'N12345' });
  });

  it('preserves existing attributes', () => {
    expect(withRegistrationNumber({ color: 'red' }, 'N12345')).toEqual({
      color: 'red',
      registrationNumber: 'N12345',
    });
  });

  it('leaves attributes untouched for a blank value', () => {
    expect(withRegistrationNumber({ color: 'red' }, '   ')).toEqual({ color: 'red' });
  });

  it('leaves attributes untouched for an absent value', () => {
    expect(withRegistrationNumber({ color: 'red' }, undefined)).toEqual({ color: 'red' });
  });

  it('overwrites a previous registration number rather than duplicating the key', () => {
    expect(
      withRegistrationNumber({ [REGISTRATION_NUMBER_ATTRIBUTE_KEY]: 'OLD' }, 'NEW'),
    ).toEqual({ registrationNumber: 'NEW' });
  });
});

describe('withoutRegistrationNumber', () => {
  it('removes the key entirely', () => {
    expect(withoutRegistrationNumber({ [REGISTRATION_NUMBER_ATTRIBUTE_KEY]: 'N12345' })).toEqual({});
  });

  it('preserves other attributes', () => {
    expect(withoutRegistrationNumber({ color: 'red', [REGISTRATION_NUMBER_ATTRIBUTE_KEY]: 'N12345' })).toEqual({
      color: 'red',
    });
  });

  it('is a no-op when the key is absent', () => {
    expect(withoutRegistrationNumber({ color: 'red' })).toEqual({ color: 'red' });
  });

  it('never mutates the input map', () => {
    const original = { [REGISTRATION_NUMBER_ATTRIBUTE_KEY]: 'N12345' };
    withoutRegistrationNumber(original);
    expect(original).toEqual({ [REGISTRATION_NUMBER_ATTRIBUTE_KEY]: 'N12345' });
  });
});
