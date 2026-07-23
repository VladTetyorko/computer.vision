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

  it('fetches a usage timeline with no query params by default', async () => {
    const promise = api.usageTimeline('u-1');
    const request = http.expectOne((r) => r.url === '/api/usages/u-1/timeline');
    expect(request.request.params.keys()).toEqual([]);
    request.flush({ usage: { usageId: 'u-1' }, from: 'f', to: 't', telemetry: [], detections: [] });
    await expect(promise).resolves.toMatchObject({ from: 'f', to: 't' });
  });

  it('passes fromMs/toMs/maxPoints through when given', async () => {
    const promise = api.usageTimeline('u-1', { fromMs: 1_000, toMs: 2_000, maxPoints: 2_000 });
    const request = http.expectOne((r) => r.url === '/api/usages/u-1/timeline');
    expect(request.request.params.get('fromMs')).toBe('1000');
    expect(request.request.params.get('toMs')).toBe('2000');
    expect(request.request.params.get('maxPoints')).toBe('2000');
    request.flush({ usage: { usageId: 'u-1' }, from: 'f', to: 't', telemetry: [], detections: [] });
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

  // --- Detection events (docs/MVP2-PLAN.md §E, E-a/E-b) --------------------------------------

  it('lists recent events with a default limit and no sinceMs on the first poll', async () => {
    const promise = api.events();
    const request = http.expectOne((r) => r.url === '/api/events');
    expect(request.request.params.get('limit')).toBe('50');
    expect(request.request.params.has('sinceMs')).toBe(false);
    request.flush([]);
    await expect(promise).resolves.toEqual([]);
  });

  it('passes sinceMs through once a cursor exists', async () => {
    const promise = api.events(1_700_000_000_000, 25);
    const request = http.expectOne((r) => r.url === '/api/events');
    expect(request.request.params.get('sinceMs')).toBe('1700000000000');
    expect(request.request.params.get('limit')).toBe('25');
    request.flush([]);
    await promise;
  });

  it('lists one stream\'s events', async () => {
    const promise = api.streamEvents('s-1');
    const request = http.expectOne((r) => r.url === '/api/streams/s-1/events');
    expect(request.request.params.get('limit')).toBe('50');
    request.flush([]);
    await expect(promise).resolves.toEqual([]);
  });
});
