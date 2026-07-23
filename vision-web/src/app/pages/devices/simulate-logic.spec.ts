import { describe, expect, it } from 'vitest';
import type { AssetDetails, Device } from '../../core/api/models';
import {
  buildSimulationRequest,
  buildSyntheticRegisterRequest,
  buildTestDroneRequest,
  isSimulatedAsset,
  mapSimulatedDevices,
  type FileSimulateForm,
  type TestDroneForm,
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
    lifecycle: 'ACTIVE',
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

  it('omits telemetry when the form carries no flight plan', () => {
    expect(buildSimulationRequest(form())).not.toHaveProperty('telemetry');
  });

  it('includes telemetry verbatim when the form carries a flight plan (docs/CYCLES-PLAN.md §7, CT-b)', () => {
    const telemetry: FileSimulateForm['telemetry'] = {
      speedMps: 12,
      routeMode: 'loop',
      route: [
        { latitude: 1, longitude: 2, altitudeMeters: 60 },
        { latitude: 3, longitude: 4 },
      ],
    };
    const request = buildSimulationRequest(form({ telemetry }));
    expect(request.telemetry).toEqual(telemetry);
    // latitude/longitude are still sent alongside a route — the backend ignores them in that case.
    expect(buildSimulationRequest(form({ telemetry, latitude: 10, longitude: 20 })).latitude).toBe(10);
  });
});

function testDroneForm(partial: Partial<TestDroneForm> = {}): TestDroneForm {
  return { name: '', latitude: null, longitude: null, autoStart: true, ...partial };
}

describe('buildTestDroneRequest', () => {
  it('omits videoPath entirely — that is what makes it a fully synthetic simulation', () => {
    const request = buildTestDroneRequest(testDroneForm());
    expect(request).not.toHaveProperty('videoPath');
  });

  it('omits a blank name rather than sending an empty displayName', () => {
    expect(buildTestDroneRequest(testDroneForm({ name: '   ' }))).not.toHaveProperty('displayName');
  });

  it('trims and includes a non-blank name', () => {
    expect(buildTestDroneRequest(testDroneForm({ name: '  Chase drone  ' })).displayName).toBe('Chase drone');
  });

  it('omits latitude/longitude when absent rather than sending null', () => {
    const request = buildTestDroneRequest(testDroneForm());
    expect(request).not.toHaveProperty('latitude');
    expect(request).not.toHaveProperty('longitude');
  });

  it('includes latitude/longitude when given, including falsy-but-valid 0', () => {
    const request = buildTestDroneRequest(testDroneForm({ latitude: 0, longitude: -122.4 }));
    expect(request.latitude).toBe(0);
    expect(request.longitude).toBe(-122.4);
  });

  it('carries autoStart through as given', () => {
    expect(buildTestDroneRequest(testDroneForm({ autoStart: false })).autoStart).toBe(false);
  });

  it('never sends a transport — the backend defaults a videoPath-less request to direct', () => {
    expect(buildTestDroneRequest(testDroneForm())).not.toHaveProperty('transport');
  });

  it('omits telemetry when the form carries no flight plan', () => {
    expect(buildTestDroneRequest(testDroneForm())).not.toHaveProperty('telemetry');
  });

  it('includes telemetry verbatim when the form carries a flight plan (docs/CYCLES-PLAN.md §7, CT-b)', () => {
    const telemetry: TestDroneForm['telemetry'] = {
      routeMode: 'once',
      route: [
        { latitude: 5, longitude: 6 },
        { latitude: 7, longitude: 8 },
      ],
    };
    expect(buildTestDroneRequest(testDroneForm({ telemetry })).telemetry).toEqual(telemetry);
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
