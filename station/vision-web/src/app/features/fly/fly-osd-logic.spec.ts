import { describe, expect, it } from 'vitest';
import { armedOsdText, isStaleReading, osdGroupLabel } from './fly-osd-logic';

describe('isStaleReading', () => {
  it('is false with no sample at all', () => {
    expect(isStaleReading(undefined)).toBe(false);
  });

  it('is false for a live or aging sample', () => {
    expect(isStaleReading(0)).toBe(false);
    expect(isStaleReading(5)).toBe(false);
    expect(isStaleReading(10)).toBe(false);
  });

  it('is true past the same red threshold the age chip already uses', () => {
    expect(isStaleReading(10.01)).toBe(true);
    expect(isStaleReading(353099)).toBe(true);
  });
});

describe('osdGroupLabel', () => {
  it('is the plain group name while the sample is live/aging/absent', () => {
    expect(osdGroupLabel('Power', undefined)).toBe('Power');
    expect(osdGroupLabel('Power', 5)).toBe('Power');
    expect(osdGroupLabel('Nav', 10)).toBe('Nav');
  });

  it('becomes "LAST KNOWN · <humanAge>" once the sample is stale, regardless of the group name', () => {
    expect(osdGroupLabel('Power', 353099)).toBe('LAST KNOWN · 4d 2h');
    expect(osdGroupLabel('Nav', 353099)).toBe('LAST KNOWN · 4d 2h');
  });
});

describe('armedOsdText', () => {
  it('is DISARMED regardless of age', () => {
    expect(armedOsdText(false, undefined)).toBe('DISARMED');
    expect(armedOsdText(false, 353099)).toBe('DISARMED');
  });

  it('is the confident ARMED while the reading is live/aging', () => {
    expect(armedOsdText(true, undefined)).toBe('ARMED');
    expect(armedOsdText(true, 5)).toBe('ARMED');
    expect(armedOsdText(true, 10)).toBe('ARMED');
  });

  it('is the hedged ARMED? once the reading is stale — H1\'s own finding', () => {
    expect(armedOsdText(true, 10.01)).toBe('ARMED?');
    expect(armedOsdText(true, 353099)).toBe('ARMED?');
  });
});
