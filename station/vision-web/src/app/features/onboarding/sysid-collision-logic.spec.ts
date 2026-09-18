import { describe, expect, it } from 'vitest';
import type { Device, DiscoveryCandidate, ParameterReading, VehicleProfile } from '../../core/api/models';
import {
  SYSID_RANGE_MAX,
  SYSID_RANGE_MIN,
  describeSysidStep,
  detectSysidCollision,
  heardSysidFor,
  sysidParameterName,
} from './sysid-collision-logic';

function profile(partial: Partial<VehicleProfile> = {}): VehicleProfile {
  return {
    linkKey: 'udp:0.0.0.0:14550',
    observedAt: '2026-08-27T00:00:00Z',
    sysid: null,
    firmware: null,
    firmwareVersion: null,
    vehicleKind: null,
    capabilityBitmask: null,
    capabilityFlags: [],
    messages: [],
    parameters: [],
    linkBytesPerSecond: null,
    complete: true,
    incompleteReason: null,
    ...partial,
  };
}

function device(partial: Partial<Device> = {}): Device {
  return {
    id: 'device-1',
    name: 'Rover 1',
    capabilities: [],
    protocol: 'mavlink',
    uri: 'udp://0.0.0.0:14550',
    options: {},
    state: 'ACTIVE',
    ...partial,
  };
}

function reading(name: string, value = 1): ParameterReading {
  return { name, value, type: 'INT32' };
}

describe('detectSysidCollision', () => {
  it('returns null when there is no profile', () => {
    expect(detectSysidCollision(null, [device({ options: { sysid: '1' } })])).toBeNull();
  });

  it('returns null when the profile carries no observed sysid', () => {
    expect(detectSysidCollision(profile({ sysid: null }), [device({ options: { sysid: '1' } })])).toBeNull();
  });

  it('returns null when the sysid is observed but no existing device claims it', () => {
    expect(detectSysidCollision(profile({ sysid: 1 }), [device({ options: { sysid: '3' } })])).toBeNull();
  });

  it('returns null against an empty fleet', () => {
    expect(detectSysidCollision(profile({ sysid: 1 }), [])).toBeNull();
  });

  it('returns the colliding sysid when an existing device claims the same one', () => {
    const devices = [device({ id: 'device-2', options: { sysid: '3' } })];
    expect(detectSysidCollision(profile({ sysid: 3 }), devices)).toBe(3);
  });

  it('compares numerically against the device option string', () => {
    const devices = [device({ options: { sysid: '01' } })];
    expect(detectSysidCollision(profile({ sysid: 1 }), devices)).toBe(1);
  });

  it('ignores devices with no sysid option at all', () => {
    const devices = [device({ options: {} }), device({ id: 'device-3', options: { sysid: '1' } })];
    expect(detectSysidCollision(profile({ sysid: 1 }), devices)).toBe(1);
  });
});

function candidate(partial: Partial<DiscoveryCandidate> = {}): DiscoveryCandidate {
  return {
    id: 'c-1',
    method: 'mavlink',
    name: 'Vehicle 7',
    address: 'udp:14550',
    details: {},
    firstSeen: '2026-08-31T00:00:00Z',
    lastSeen: '2026-08-31T00:00:00Z',
    status: 'NEW',
    ...partial,
  };
}

describe('heardSysidFor', () => {
  it('returns null for no candidate', () => {
    expect(heardSysidFor(null)).toBeNull();
  });

  it('prefers suggestedStreamOptions.sysid over details.sysid', () => {
    const found = candidate({ suggestedStreamOptions: { sysid: '1' }, details: { sysid: '9' } });
    expect(heardSysidFor(found)).toBe(1);
  });

  it('falls back to details.sysid when suggestedStreamOptions is absent', () => {
    const found = candidate({ details: { sysid: '7' } });
    expect(heardSysidFor(found)).toBe(7);
  });

  it('returns null when neither field carries a sysid', () => {
    expect(heardSysidFor(candidate())).toBeNull();
  });

  it('returns null for a non-numeric sysid', () => {
    const found = candidate({ details: { sysid: 'not-a-number' } });
    expect(heardSysidFor(found)).toBeNull();
  });
});

describe('describeSysidStep', () => {
  const range = { sysidRangeMin: SYSID_RANGE_MIN, sysidRangeMax: SYSID_RANGE_MAX };

  it('classifies the factory-default sysid (1) even though the station already assigned a real one', () => {
    const result = describeSysidStep({ heardSysid: 1, assignedSysid: 12, ...range });
    expect(result.kind).toBe('factory-default');
    expect(result.title).toBe('Give this vehicle its fleet number');
    expect(result.message).not.toMatch(/collision/i);
    expect(result.message).toContain('1');
    expect(result.message).toContain('12');
  });

  it('classifies a heard sysid inside the assignable range as a genuine collision, naming the assigned fix', () => {
    const result = describeSysidStep({ heardSysid: 15, assignedSysid: 20, ...range });
    expect(result.kind).toBe('collision');
    expect(result.title).toBe('Fix the sysid collision');
    expect(result.message).toContain('15');
    expect(result.message).toContain('20');
  });

  it('classifies a heard sysid above the range (GCS/broadcast band) as out-of-range, not a collision', () => {
    const result = describeSysidStep({ heardSysid: 253, assignedSysid: 11, ...range });
    expect(result.kind).toBe('out-of-range');
    expect(result.message).not.toMatch(/collision/i);
    expect(result.message).toContain('253');
    expect(result.message).toContain('11');
  });

  it('classifies a heard sysid below the range as out-of-range', () => {
    const result = describeSysidStep({ heardSysid: 0, assignedSysid: 13, ...range });
    expect(result.kind).toBe('out-of-range');
  });

  it('degrades to the collision wording without a heard number when nothing was observed client-side', () => {
    const result = describeSysidStep({ heardSysid: null, assignedSysid: 14, ...range });
    expect(result.kind).toBe('collision');
    expect(result.message).toContain('14');
  });

  it('never fabricates an assigned number when none is known (legacy connection-form path)', () => {
    const result = describeSysidStep({ heardSysid: 15, assignedSysid: null, ...range });
    expect(result.message).not.toMatch(/\bnull\b/);
    expect(result.message).toContain('a new fleet number');
  });
});

describe('sysidParameterName', () => {
  // The fallback is reachable: detectSysidCollision works off profile.sysid (the heartbeat's own
  // source system id) and never needs this parameter, so a short probe can detect a collision
  // having read neither spelling. It resolves to the MODERN name — an observed SYSID_THISMAV is
  // positive evidence of pre-4.7 firmware, but observing neither is no evidence, and the firmware
  // generation this platform targets answers MAV_SYSID (FLEET-RADIO R0/R5).
  it('falls back to the modern spelling when neither was ever read back', () => {
    expect(sysidParameterName(profile({ parameters: [] }))).toBe('MAV_SYSID');
  });

  it('falls back to the modern spelling when an unrelated parameter was read but not either spelling', () => {
    expect(sysidParameterName(profile({ parameters: [reading('SYSID_MYGCS')] }))).toBe('MAV_SYSID');
  });

  it('returns MAV_SYSID when only the modern spelling was observed', () => {
    expect(sysidParameterName(profile({ parameters: [reading('MAV_SYSID')] }))).toBe('MAV_SYSID');
  });

  it('returns SYSID_THISMAV when only the legacy spelling was observed', () => {
    expect(sysidParameterName(profile({ parameters: [reading('SYSID_THISMAV')] }))).toBe('SYSID_THISMAV');
  });

  it('prefers the modern spelling when both were somehow observed', () => {
    const parameters = [reading('SYSID_THISMAV'), reading('MAV_SYSID')];
    expect(sysidParameterName(profile({ parameters }))).toBe('MAV_SYSID');
  });
});
