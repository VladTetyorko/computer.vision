import { describe, expect, it } from 'vitest';
import type { Device } from './api/models';
import { findVideoDevice } from './device-logic';

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
