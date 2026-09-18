import { describe, expect, it } from 'vitest';
import type { Device, DiscoveryCandidate, ParameterReading, VehicleProfile } from '../../core/api/models';
import { candidateSysidCollision, detectSysidCollision, sysidParameterName } from './sysid-collision-logic';

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

describe('candidateSysidCollision', () => {
  it('prefers the server-reported sysidPushRequired/assignedSysid pair when present', () => {
    const found = candidate({ sysidPushRequired: true, assignedSysid: 4, details: { sysid: '1' } });
    expect(candidateSysidCollision(found, [])).toBe(4);
  });

  it('ignores assignedSysid when sysidPushRequired is not true', () => {
    const found = candidate({ sysidPushRequired: false, assignedSysid: 4, details: { sysid: '1' } });
    expect(candidateSysidCollision(found, [device({ options: { sysid: '1' } })])).toBe(1);
  });

  it('returns null when there is no details sysid and no server hint', () => {
    expect(candidateSysidCollision(candidate(), [device({ options: { sysid: '1' } })])).toBeNull();
  });

  it('returns null when the candidate sysid is not numeric', () => {
    const found = candidate({ details: { sysid: 'not-a-number' } });
    expect(candidateSysidCollision(found, [device({ options: { sysid: '1' } })])).toBeNull();
  });

  it('returns null when the details sysid collides with nothing in the fleet', () => {
    const found = candidate({ details: { sysid: '1' } });
    expect(candidateSysidCollision(found, [device({ options: { sysid: '3' } })])).toBeNull();
  });

  it('falls back to the details sysid heuristic and finds a collision', () => {
    const found = candidate({ details: { sysid: '3' } });
    expect(candidateSysidCollision(found, [device({ options: { sysid: '3' } })])).toBe(3);
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
