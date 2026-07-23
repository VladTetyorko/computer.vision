import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { VisionApi } from './vision-api';

describe('VisionApi', () => {
  let api: VisionApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(VisionApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists devices', async () => {
    const promise = api.listDevices();
    http.expectOne({ method: 'GET', url: '/api/devices' }).flush([]);
    await expect(promise).resolves.toEqual([]);
  });

  it('starts a stream on the device-scoped path', async () => {
    const promise = api.startStream('cam-1', { inferenceFps: 3 });
    const request = http.expectOne({ method: 'POST', url: '/api/devices/cam-1/stream' });
    expect(request.request.body).toEqual({ inferenceFps: 3 });
    request.flush({ streamId: 's-1', viewUrl: 'http://localhost:8888/s-1/index.m3u8' });
    await expect(promise).resolves.toMatchObject({ streamId: 's-1' });
  });

  it('escapes ids that would otherwise break the path', async () => {
    const promise = api.stopStream('a/b c');
    http.expectOne({ method: 'DELETE', url: '/api/streams/a%2Fb%20c' }).flush(null);
    await promise;
  });

  it('posts an empty scan body when no overrides are given', async () => {
    const promise = api.scan();
    const request = http.expectOne({ method: 'POST', url: '/api/discovery/scan' });
    expect(request.request.body).toEqual({});
    request.flush({ devices: [], failedMethods: [] });
    await expect(promise).resolves.toEqual({ devices: [], failedMethods: [] });
  });

  it('lists assets', async () => {
    const promise = api.listAssets();
    http.expectOne({ method: 'GET', url: '/api/assets' }).flush([]);
    await expect(promise).resolves.toEqual([]);
  });

  it('fetches one asset by id', async () => {
    const promise = api.getAsset('a-1');
    http.expectOne({ method: 'GET', url: '/api/assets/a-1' }).flush({ assetId: 'a-1' });
    await expect(promise).resolves.toMatchObject({ assetId: 'a-1' });
  });

  it('defaults a usage telemetry request to limit=200', async () => {
    const promise = api.usageTelemetry('u-1');
    const request = http.expectOne((r) => r.url === '/api/usages/u-1/telemetry');
    expect(request.request.params.get('limit')).toBe('200');
    request.flush([]);
    await expect(promise).resolves.toEqual([]);
  });

  it('passes an explicit telemetry limit through', async () => {
    const promise = api.usageTelemetry('u-1', 50);
    const request = http.expectOne((r) => r.url === '/api/usages/u-1/telemetry');
    expect(request.request.params.get('limit')).toBe('50');
    request.flush([]);
    await promise;
  });

  it('starts a simulation', async () => {
    const promise = api.startSimulation({ videoPath: '/videos/flight.mp4', transport: 'rtsp' });
    const request = http.expectOne({ method: 'POST', url: '/api/simulations' });
    expect(request.request.body).toEqual({ videoPath: '/videos/flight.mp4', transport: 'rtsp' });
    request.flush({ assetId: 'a-1', streamId: 's-1' });
    await expect(promise).resolves.toEqual({ assetId: 'a-1', streamId: 's-1' });
  });

  it('escapes the asset id when stopping a simulation', async () => {
    const promise = api.stopSimulation('a/b c');
    http.expectOne({ method: 'DELETE', url: '/api/simulations/a%2Fb%20c' }).flush(null);
    await promise;
  });

  // --- Warehouse lifecycle (docs/CYCLES-PLAN.md §8's pinned contract) ------------------------

  it('lists devices without includeDeleted by default', async () => {
    const promise = api.listDevices();
    const request = http.expectOne((r) => r.url === '/api/devices');
    expect(request.request.params.has('includeDeleted')).toBe(false);
    request.flush([]);
    await promise;
  });

  it('lists devices with includeDeleted=true when asked', async () => {
    const promise = api.listDevices(true);
    const request = http.expectOne((r) => r.url === '/api/devices');
    expect(request.request.params.get('includeDeleted')).toBe('true');
    request.flush([]);
    await promise;
  });

  it('patches a device edit', async () => {
    const promise = api.updateDevice('dev-1', { name: 'renamed' });
    const request = http.expectOne({ method: 'PATCH', url: '/api/devices/dev-1' });
    expect(request.request.body).toEqual({ name: 'renamed' });
    request.flush({ id: 'dev-1' });
    await expect(promise).resolves.toMatchObject({ id: 'dev-1' });
  });

  it('posts a device state change', async () => {
    const promise = api.setDeviceState('dev-1', 'DEACTIVATED');
    const request = http.expectOne({ method: 'POST', url: '/api/devices/dev-1/state' });
    expect(request.request.body).toEqual({ state: 'DEACTIVATED' });
    request.flush({ id: 'dev-1', state: 'DEACTIVATED' });
    await expect(promise).resolves.toMatchObject({ state: 'DEACTIVATED' });
  });

  it('deletes (archives) a device', async () => {
    const promise = api.deleteDevice('dev-1');
    http.expectOne({ method: 'DELETE', url: '/api/devices/dev-1' }).flush({ id: 'dev-1', state: 'DELETED' });
    await expect(promise).resolves.toMatchObject({ state: 'DELETED' });
  });

  it('lists assets without includeDeleted by default', async () => {
    const promise = api.listAssets();
    const request = http.expectOne((r) => r.url === '/api/assets');
    expect(request.request.params.has('includeDeleted')).toBe(false);
    request.flush([]);
    await promise;
  });

  it('lists assets with includeDeleted=true when asked', async () => {
    const promise = api.listAssets(true);
    const request = http.expectOne((r) => r.url === '/api/assets');
    expect(request.request.params.get('includeDeleted')).toBe('true');
    request.flush([]);
    await promise;
  });

  it('patches an asset edit', async () => {
    const promise = api.updateAsset('a-1', { displayName: 'Renamed' });
    const request = http.expectOne({ method: 'PATCH', url: '/api/assets/a-1' });
    expect(request.request.body).toEqual({ displayName: 'Renamed' });
    request.flush({ assetId: 'a-1' });
    await expect(promise).resolves.toMatchObject({ assetId: 'a-1' });
  });

  it('posts an asset state change', async () => {
    const promise = api.setAssetState('a-1', 'ACTIVE');
    const request = http.expectOne({ method: 'POST', url: '/api/assets/a-1/state' });
    expect(request.request.body).toEqual({ state: 'ACTIVE' });
    request.flush({ assetId: 'a-1' });
    await promise;
  });

  it('deletes (archives) an asset and resolves the deletion report', async () => {
    const promise = api.deleteAsset('a-1');
    http
      .expectOne({ method: 'DELETE', url: '/api/assets/a-1' })
      .flush({ assetId: 'a-1', displayName: 'My Drone', devicesDeleted: 2, usagesRetained: 3, streamsStopped: 1 });
    await expect(promise).resolves.toEqual({
      assetId: 'a-1',
      displayName: 'My Drone',
      devicesDeleted: 2,
      usagesRetained: 3,
      streamsStopped: 1,
    });
  });

  it('assigns a device to an asset', async () => {
    const promise = api.assignDevice('a-1', 'dev-1');
    const request = http.expectOne({ method: 'POST', url: '/api/assets/a-1/devices' });
    expect(request.request.body).toEqual({ deviceId: 'dev-1' });
    request.flush({ assetId: 'a-1' });
    await promise;
  });

  it('unassigns a device from an asset, escaping both ids', async () => {
    const promise = api.unassignDevice('a/1', 'dev/1');
    http
      .expectOne({ method: 'DELETE', url: '/api/assets/a%2F1/devices/dev%2F1' })
      .flush({ assetId: 'a/1' });
    await promise;
  });
});
