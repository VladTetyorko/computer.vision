import { describe, expect, it } from 'vitest';
import type { DiscoveredDevice } from '../../core/api/models';
import {
  FIRMWARES,
  LINKS,
  buildDroneDeviceSpec,
  configSnippets,
  linkCompatibility,
} from './drone-config-logic';

function candidate(partial: Partial<DiscoveredDevice> = {}): DiscoveredDevice {
  return {
    method: 'mavlink',
    name: 'ArduPilot quadcopter (sysid 7)',
    address: '0.0.0.0:14550',
    details: {},
    suggestedOptions: {},
    ...partial,
  };
}

describe('linkCompatibility', () => {
  it('is full support for ArduPilot on every link', () => {
    for (const link of LINKS) {
      const result = linkCompatibility('ardupilot', link);
      expect(result.supported).toBe(true);
      expect(result.level).toBe('full');
      expect(result.note.length).toBeGreaterThan(0);
    }
  });

  it('is monitor-only for INAV on every link, with a transmit-only explanation', () => {
    for (const link of LINKS) {
      const result = linkCompatibility('inav', link);
      expect(result.supported).toBe(true);
      expect(result.level).toBe('monitor-only');
      expect(result.note).toContain('transmit-only');
    }
  });

  it('is a hard no-go for Betaflight on every link, below the 2025.12 floor', () => {
    for (const link of LINKS) {
      const result = linkCompatibility('betaflight', link);
      expect(result.supported).toBe(false);
      expect(result.level).toBe('no-go');
      expect(result.note).toContain('2025.12');
    }
  });

  it('surfaces the ELRS/backpack firmware-version floors for ArduPilot', () => {
    const result = linkCompatibility('ardupilot', 'elrs');
    expect(result.note).toContain('3.5.0');
    expect(result.note).toContain('1.5.0');
    expect(result.note).toContain('4.5');
  });

  it('surfaces the ELRS/backpack firmware-version floors for INAV too', () => {
    const result = linkCompatibility('inav', 'elrs');
    expect(result.note).toContain('3.5.0');
    expect(result.note).toContain('1.5.0');
    expect(result.note).toContain('INAV');
  });

  it('flags the companion path as the only one carrying video, for ArduPilot', () => {
    expect(linkCompatibility('ardupilot', 'companion').note.toLowerCase()).toContain('video');
  });

  it('covers every firmware x link cell without throwing', () => {
    for (const firmware of FIRMWARES) {
      for (const link of LINKS) {
        expect(() => linkCompatibility(firmware, link)).not.toThrow();
      }
    }
  });
});

describe('configSnippets', () => {
  it('interpolates address/port into the ELRS target block only, not the FC settings block', () => {
    const [fc, target] = configSnippets('ardupilot', 'elrs', '192.168.0.104', 14550);
    expect(fc.body).not.toContain('192.168.0.104');
    expect(target.body).toContain('192.168.0.104:14550');
  });

  it('interpolates address/port into the ESP32 target block as a udp:// URI', () => {
    const [fc, target] = configSnippets('ardupilot', 'esp32', '10.0.0.5', 14550);
    expect(fc.body).not.toContain('10.0.0.5');
    expect(target.body).toContain('udp://10.0.0.5:14550');
  });

  it('produces a companion main.conf block with a filename and the pre-filled UdpEndpoint', () => {
    const [, target] = configSnippets('ardupilot', 'companion', '192.168.0.104', 14550);
    expect(target.filename).toBe('main.conf');
    expect(target.body).toContain('Address = 192.168.0.104');
    expect(target.body).toContain('Port = 14550');
    expect(target.body).toContain('[UdpEndpoint platform]');
  });

  it('leaves the companion main.conf\'s Device/Baud untouched — those depend on wiring, not address/port', () => {
    const [, target] = configSnippets('ardupilot', 'companion', '192.168.0.104', 14550);
    expect(target.body).toContain('Device = /dev/ttyAMA0');
    expect(target.body).toContain('Baud = 57600');
  });

  it('never gives a filename to the elrs/esp32 target blocks — no download button for those', () => {
    expect(configSnippets('ardupilot', 'elrs', 'a', 1).find((b) => b.filename)).toBeUndefined();
    expect(configSnippets('ardupilot', 'esp32', 'a', 1).find((b) => b.filename)).toBeUndefined();
  });

  it("carries ArduPilot's distinctive SERIALx_PROTOCOL/BAUD setting, baud varying by link", () => {
    expect(configSnippets('ardupilot', 'elrs', 'a', 1)[0].body).toContain('SERIALx_PROTOCOL = 2');
    expect(configSnippets('ardupilot', 'elrs', 'a', 1)[0].body).toContain('460');
    expect(configSnippets('ardupilot', 'esp32', 'a', 1)[0].body).toContain('115');
    expect(configSnippets('ardupilot', 'companion', 'a', 1)[0].body).toContain('57');
  });

  it("carries INAV's distinctive serial-function-assignment setting", () => {
    const fc = configSnippets('inav', 'elrs', 'a', 1)[0];
    expect(fc.body).toContain('FUNCTION_TELEMETRY_MAVLINK');
    expect(fc.body).toContain('feature TELEMETRY');
  });

  it("carries Betaflight's distinctive Ports-tab Telemetry Output setting", () => {
    const fc = configSnippets('betaflight', 'elrs', 'a', 1)[0];
    expect(fc.body).toContain('Telemetry Output to MAVLink');
    expect(fc.body).toContain('feature TELEMETRY');
  });

  it('always returns exactly two blocks — FC settings, then the link target', () => {
    for (const firmware of FIRMWARES) {
      for (const link of LINKS) {
        expect(configSnippets(firmware, link, '10.0.0.1', 14550)).toHaveLength(2);
      }
    }
  });
});

describe('buildDroneDeviceSpec', () => {
  it('always uses protocol mavlink and udp://0.0.0.0:<mavlinkPort>, regardless of the candidate\'s own uri/address', () => {
    const spec = buildDroneDeviceSpec(
      candidate({ protocol: 'rtsp', uri: 'rtsp://not-this', address: '10.0.0.9:9999' }),
      14550,
    );
    expect(spec.protocol).toBe('mavlink');
    expect(spec.uri).toBe('udp://0.0.0.0:14550');
  });

  it('follows the requested mavlinkPort, not a hardcoded 14550', () => {
    const spec = buildDroneDeviceSpec(candidate(), 18550);
    expect(spec.uri).toBe('udp://0.0.0.0:18550');
  });

  it('pins the sysid option when the scanner reported one', () => {
    const spec = buildDroneDeviceSpec(candidate({ details: { sysid: '7' } }), 14550);
    expect(spec.options).toEqual({ sysid: '7' });
  });

  it('omits options entirely when no sysid was heard', () => {
    const spec = buildDroneDeviceSpec(candidate({ details: {} }), 14550);
    expect(spec).not.toHaveProperty('options');
  });
});
