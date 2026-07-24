import { describe, expect, it } from 'vitest';
import type { Device } from './api/models';
import { findVideoDevice, videoDevices } from './device-logic';

function device(partial: Partial<Device>): Device {
  return {
    id: 'dev-0',
    name: 'device',
    capabilities: ['VIDEO'],
    protocol: 'sim',
    uri: 'sim://demo',
    options: {},
    state: 'ACTIVE',
    ...partial,
  };
}

describe('findVideoDevice', () => {
  it('finds the first VIDEO-capable device', () => {
    const telemetry = device({ id: 'dev-t', capabilities: ['TELEMETRY'] });
    const video = device({ id: 'dev-v', capabilities: ['VIDEO'] });
    expect(findVideoDevice([telemetry, video])).toBe(video);
  });

  it('returns undefined when no device is VIDEO-capable', () => {
    expect(findVideoDevice([device({ capabilities: ['TELEMETRY'] })])).toBeUndefined();
  });

  it('returns undefined for an empty device list', () => {
    expect(findVideoDevice([])).toBeUndefined();
  });
});

describe('videoDevices', () => {
  it('keeps only VIDEO-capable devices, in order', () => {
    const gps = device({ id: 'd1', capabilities: ['TELEMETRY'] });
    const cam1 = device({ id: 'd2', capabilities: ['VIDEO'] });
    const cam2 = device({ id: 'd3', capabilities: ['VIDEO', 'TELEMETRY'] });
    expect(videoDevices([gps, cam1, cam2]).map((d) => d.id)).toEqual(['d2', 'd3']);
  });

  it('returns an empty array when no device carries a camera', () => {
    expect(videoDevices([device({ capabilities: ['TELEMETRY'] })])).toEqual([]);
  });
});
