import { describe, expect, it } from 'vitest';
import type { DiscoveredDevice } from '../../core/api/models';
import {
  MAVLINK_DISCOVERY_METHOD,
  buildMavlinkScanRequest,
  detailsSummary,
  isClaimedVehicle,
  prefillFromVehicle,
  vehicleDetailChips,
  vehicleSysid,
} from './drone-scan-logic';

function candidate(partial: Partial<DiscoveredDevice> = {}): DiscoveredDevice {
  return {
    method: 'mavlink',
    name: 'ArduPilot quadcopter (sysid 7)',
    address: '0.0.0.0:14550',
    details: {},
    ...partial,
  };
}

describe('buildMavlinkScanRequest', () => {
  it('restricts the scan to the mavlink method alone', () => {
    expect(buildMavlinkScanRequest()).toEqual({ methods: ['mavlink'] });
  });

  it('omits timeoutMs when not given — the scanner self-time-boxes', () => {
    expect(buildMavlinkScanRequest()).not.toHaveProperty('timeoutMs');
  });

  it('includes timeoutMs when given', () => {
    expect(buildMavlinkScanRequest(8_000)).toEqual({ methods: ['mavlink'], timeoutMs: 8_000 });
  });

  it('uses the same method key MAVLINK_DISCOVERY_METHOD exports', () => {
    expect(buildMavlinkScanRequest().methods).toEqual([MAVLINK_DISCOVERY_METHOD]);
  });
});

describe('isClaimedVehicle', () => {
  it('is false when details carries no claimed key', () => {
    expect(isClaimedVehicle(candidate())).toBe(false);
  });

  it('is false for any value other than the exact string "true"', () => {
    expect(isClaimedVehicle(candidate({ details: { claimed: 'false' } }))).toBe(false);
    expect(isClaimedVehicle(candidate({ details: { claimed: 'True' } }))).toBe(false);
  });

  it('is true only for the exact string "true"', () => {
    expect(isClaimedVehicle(candidate({ details: { claimed: 'true' } }))).toBe(true);
  });
});

describe('vehicleSysid', () => {
  it('is undefined when absent', () => {
    expect(vehicleSysid(candidate())).toBeUndefined();
  });

  it('reads details["sysid"] verbatim', () => {
    expect(vehicleSysid(candidate({ details: { sysid: '7' } }))).toBe('7');
  });
});

describe('vehicleDetailChips', () => {
  it('is empty when neither firmware nor sysid is present', () => {
    expect(vehicleDetailChips(candidate())).toEqual([]);
  });

  it('includes only the chips whose key is present, firmware before sysid', () => {
    const chips = vehicleDetailChips(candidate({ details: { firmware: 'ardupilot', sysid: '7' } }));
    expect(chips).toEqual([
      { key: 'firmware', label: 'Firmware', value: 'ardupilot' },
      { key: 'sysid', label: 'Sysid', value: '7' },
    ]);
  });

  it('omits the firmware chip alone when only sysid is present', () => {
    expect(vehicleDetailChips(candidate({ details: { sysid: '3' } }))).toEqual([
      { key: 'sysid', label: 'Sysid', value: '3' },
    ]);
  });

  it('never surfaces mode/armed/mavType or any other detail key as a chip', () => {
    const chips = vehicleDetailChips(candidate({ details: { mode: 'Loiter', armed: 'true', mavType: 'quad' } }));
    expect(chips).toEqual([]);
  });
});

describe('detailsSummary', () => {
  it('returns an empty string for an empty details map', () => {
    expect(detailsSummary({})).toBe('');
  });

  it('joins one entry as "key: value" with no trailing separator', () => {
    expect(detailsSummary({ manufacturer: 'Hikvision' })).toBe('manufacturer: Hikvision');
  });

  it('comma-joins every entry, in insertion order', () => {
    expect(detailsSummary({ manufacturer: 'Hikvision', model: 'DS-2CD', firmware: '5.7.0' })).toBe(
      'manufacturer: Hikvision, model: DS-2CD, firmware: 5.7.0',
    );
  });
});

describe('prefillFromVehicle', () => {
  it('uses the candidate protocol/uri, and the sysid detail as a stream option', () => {
    const prefill = prefillFromVehicle(
      candidate({
        protocol: 'mavlink',
        uri: 'udp://0.0.0.0:14550',
        suggestedCategory: 'drone',
        details: { sysid: '7', firmware: 'ardupilot' },
      }),
    );
    expect(prefill).toEqual({
      protocol: 'mavlink',
      uri: 'udp://0.0.0.0:14550',
      options: { sysid: '7' },
      suggestedCategory: 'drone',
    });
  });

  it('falls back to "mavlink" when the candidate carries no protocol of its own', () => {
    const prefill = prefillFromVehicle(candidate({ protocol: undefined, uri: 'udp://0.0.0.0:14550' }));
    expect(prefill.protocol).toBe('mavlink');
  });

  it('falls back to address when the candidate carries no uri', () => {
    const prefill = prefillFromVehicle(candidate({ uri: undefined, address: 'udp://0.0.0.0:14550' }));
    expect(prefill.uri).toBe('udp://0.0.0.0:14550');
  });

  it('omits options entirely when no sysid was reported', () => {
    const prefill = prefillFromVehicle(candidate({ protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' }));
    expect(prefill).not.toHaveProperty('options');
  });

  it('omits suggestedCategory entirely when the candidate carries none', () => {
    const prefill = prefillFromVehicle(candidate({ protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' }));
    expect(prefill).not.toHaveProperty('suggestedCategory');
  });
});
