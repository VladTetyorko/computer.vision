import { describe, expect, it } from 'vitest';
import {
  CERTIFICATE_EXPIRY_SAFETY_MARGIN_MS,
  formatFingerprints,
  isCertificateUsable,
} from './webrtc-certificate-logic';

describe('isCertificateUsable', () => {
  it('is false for null/undefined — nothing stored yet', () => {
    expect(isCertificateUsable(null, 0)).toBe(false);
    expect(isCertificateUsable(undefined, 0)).toBe(false);
  });

  it('is true well before expiry', () => {
    expect(isCertificateUsable({ expires: 1_000_000 }, 0)).toBe(true);
  });

  it('is false once inside the safety margin of the real expiry', () => {
    const nowMs = 0;
    const expires = nowMs + CERTIFICATE_EXPIRY_SAFETY_MARGIN_MS - 1;
    expect(isCertificateUsable({ expires }, nowMs)).toBe(false);
  });

  it('is true exactly outside the safety margin (boundary)', () => {
    const nowMs = 0;
    const expires = nowMs + CERTIFICATE_EXPIRY_SAFETY_MARGIN_MS + 1;
    expect(isCertificateUsable({ expires }, nowMs)).toBe(true);
  });

  it('is false for an already-expired certificate', () => {
    expect(isCertificateUsable({ expires: 500 }, 1_000)).toBe(false);
  });

  it('honors a custom safety margin', () => {
    expect(isCertificateUsable({ expires: 10_000 }, 0, 20_000)).toBe(false);
    expect(isCertificateUsable({ expires: 10_000 }, 0, 5_000)).toBe(true);
  });
});

describe('formatFingerprints', () => {
  it('joins algorithm+value pairs, comma-separated', () => {
    expect(formatFingerprints([{ algorithm: 'sha-256', value: 'AA:BB' }])).toBe('sha-256 AA:BB');
    expect(
      formatFingerprints([
        { algorithm: 'sha-256', value: 'AA:BB' },
        { algorithm: 'sha-1', value: 'CC:DD' },
      ]),
    ).toBe('sha-256 AA:BB, sha-1 CC:DD');
  });

  it('renders "(none)" for an empty list', () => {
    expect(formatFingerprints([])).toBe('(none)');
  });

  it('degrades missing algorithm/value to "?" rather than throwing', () => {
    expect(formatFingerprints([{}])).toBe('? ?');
  });
});
