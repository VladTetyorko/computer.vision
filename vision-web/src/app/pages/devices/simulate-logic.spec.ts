import { describe, expect, it } from 'vitest';
import type { AssetDetails, Device } from '../../core/api/models';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  isSimulatedAsset,
  mapSimulatedDevices,
  type FileSimulateForm,
} from './simulate-logic';

function form(partial: Partial<FileSimulateForm> = {}): FileSimulateForm {
  return {
    name: '',
    videoPath: '/videos/flight.mp4',
    mode: 'direct',
    latitude: null,
    longitude: null,
    autoStart: true,
    ...partial,
  };
}

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

function asset(partial: Partial<AssetDetails>): AssetDetails {
  return {
    assetId: 'a-0',
    displayName: 'asset',
    category: 'simulated',
    categoryName: 'Simulated',
    owner: 'owner-0',
    status: 'OFFLINE',
    state: 'ACTIVE',
    attributes: {},
    devices: [],
    recentUsages: [],
    ...partial,
  };
}

describe('buildSimulationRequest', () => {
  it('trims the video path and maps mode straight to transport', () => {
    expect(buildSimulationRequest(form({ videoPath: '  /videos/flight.mp4  ', mode: 'rtsp' }))).toEqual({
      videoPath: '/videos/flight.mp4',
      transport: 'rtsp',
      autoStart: true,
    });
  });

  it('omits a blank name rather than sending an empty displayName', () => {
    const request = buildSimulationRequest(form({ name: '   ' }));
    expect(request).not.toHaveProperty('displayName');
  });

  it('trims and includes a non-blank name', () => {
    const request = buildSimulationRequest(form({ name: '  My Drone  ' }));
    expect(request.displayName).toBe('My Drone');
  });

  it('omits latitude/longitude when absent rather than sending null', () => {
    const request = buildSimulationRequest(form({ latitude: null, longitude: null }));
    expect(request).not.toHaveProperty('latitude');
    expect(request).not.toHaveProperty('longitude');
  });

  it('includes latitude/longitude when given, including falsy-but-valid 0', () => {
    const request = buildSimulationRequest(form({ latitude: 0, longitude: -122.4 }));
    expect(request.latitude).toBe(0);
    expect(request.longitude).toBe(-122.4);
  });

  it('carries autoStart through as given', () => {
    expect(buildSimulationRequest(form({ autoStart: false })).autoStart).toBe(false);
  });
});

describe('buildSyntheticRegisterRequest', () => {
  it('uses the trimmed name when one is given', () => {
    expect(buildSyntheticRegisterRequest('  my-pattern  ')).toEqual({
      name: 'my-pattern',
      protocol: 'sim',
      uri: 'sim://demo',
    });
  });

  it('falls back to the quick-add default name when blank', () => {
    expect(buildSyntheticRegisterRequest('   ')).toEqual({
      name: 'sim-demo',
      protocol: 'sim',
      uri: 'sim://demo',
    });
  });

  it('falls back to the default name for an empty string', () => {
    expect(buildSyntheticRegisterRequest('').name).toBe('sim-demo');
  });
});

describe('isSimulatedAsset', () => {
  it('is true for the simulated category', () => {
    expect(isSimulatedAsset({ category: 'simulated' })).toBe(true);
  });

  it('is false for any other category', () => {
    expect(isSimulatedAsset({ category: 'drone' })).toBe(false);
  });
});

describe('mapSimulatedDevices', () => {
  it('maps each device id to its owning asset', () => {
    const a = asset({ assetId: 'a-1', displayName: 'Sim 1', devices: [device({ id: 'dev-1' }), device({ id: 'dev-2' })] });
    const b = asset({ assetId: 'a-2', displayName: 'Sim 2', devices: [device({ id: 'dev-3' })] });

    const map = mapSimulatedDevices([a, b]);

    expect(map.get('dev-1')).toEqual({ assetId: 'a-1', displayName: 'Sim 1' });
    expect(map.get('dev-2')).toEqual({ assetId: 'a-1', displayName: 'Sim 1' });
    expect(map.get('dev-3')).toEqual({ assetId: 'a-2', displayName: 'Sim 2' });
  });

  it('returns an empty map for no simulated assets', () => {
    expect(mapSimulatedDevices([]).size).toBe(0);
  });

  it('returns an empty map when simulated assets have no devices', () => {
    expect(mapSimulatedDevices([asset({ devices: [] })]).size).toBe(0);
  });
});
