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

  // --- Fleet summary + stream snapshots (docs/MVP3-PLAN.md C-a/C-c) --------------------------

  it('fetches the fleet summary without includeArchived by default', async () => {
    const promise = api.fleetSummary();
    const request = http.expectOne((r) => r.url === '/api/fleet/summary');
    expect(request.request.params.has('includeArchived')).toBe(false);
    request.flush({ categories: [], assets: [], totalAssets: 0 });
    await expect(promise).resolves.toEqual({ categories: [], assets: [], totalAssets: 0 });
  });

  it('fetches the fleet summary with includeArchived=true when asked', async () => {
    const promise = api.fleetSummary(true);
    const request = http.expectOne((r) => r.url === '/api/fleet/summary');
    expect(request.request.params.get('includeArchived')).toBe('true');
    request.flush({ categories: [], assets: [], totalAssets: 0 });
    await promise;
  });

  it('builds a stream snapshot URL without issuing any request', () => {
    expect(api.snapshotUrl('s-1')).toBe('/api/streams/s-1/snapshot');
    http.verify(); // nothing was ever requested
  });

  it('escapes a stream id in the snapshot URL', () => {
    expect(api.snapshotUrl('s/1 x')).toBe('/api/streams/s%2F1%20x/snapshot');
  });

  // --- Device probe (docs/UX-REWORK-PLAN.md §U-d) --------------------------------------------

  it('probes a candidate connection', async () => {
    const promise = api.probeDevice({ protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' });
    const request = http.expectOne({ method: 'POST', url: '/api/devices/probe' });
    expect(request.request.body).toEqual({ protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' });
    request.flush({
      ok: true,
      widthPx: 1920,
      heightPx: 1080,
      telemetryDetected: false,
      frameJpegBase64: 'ZmFrZQ==',
      warnings: [],
    });
    await expect(promise).resolves.toMatchObject({ widthPx: 1920, heightPx: 1080 });
  });

  it('resolves a probe response with warnings entirely absent — the shape observed live against the running backend, not just the pinned contract', async () => {
    const promise = api.probeDevice({ protocol: 'sim', uri: 'sim://demo' });
    const request = http.expectOne({ method: 'POST', url: '/api/devices/probe' });
    request.flush({
      ok: true,
      widthPx: 640,
      heightPx: 480,
      codec: 'mjpeg',
      telemetryDetected: true,
      frameJpegBase64: 'ZmFrZQ==',
      // No `warnings` key at all — see models.ts#ProbeDeviceResult's own doc comment.
    });
    await expect(promise).resolves.not.toHaveProperty('warnings');
    await expect(promise).resolves.toMatchObject({ ok: true, codec: 'mjpeg' });
  });

  // --- Asset image (docs/UX-REWORK-PLAN.md §U-d) --------------------------------------------

  it('builds an asset image URL without issuing any request', () => {
    expect(api.assetImageUrl('a-1')).toBe('/api/assets/a-1/image');
    http.verify();
  });

  it('escapes the asset id in the image URL', () => {
    expect(api.assetImageUrl('a/1 x')).toBe('/api/assets/a%2F1%20x/image');
  });

  it('uploads an asset image as a raw body', async () => {
    const blob = new Blob(['fake-jpeg-bytes'], { type: 'image/jpeg' });
    const promise = api.uploadAssetImage('a-1', blob);
    const request = http.expectOne({ method: 'PUT', url: '/api/assets/a-1/image' });
    expect(request.request.body).toBe(blob);
    request.flush(null);
    await promise;
  });

  it('deletes an asset image', async () => {
    const promise = api.deleteAssetImage('a-1');
    http.expectOne({ method: 'DELETE', url: '/api/assets/a-1/image' }).flush(null);
    await promise;
  });

  // --- Geofencing (docs/OPS-CORE-PLAN.md §G's frozen wire contract) --------------------------

  it('lists geofence zones', async () => {
    const promise = api.listGeofences();
    http.expectOne({ method: 'GET', url: '/api/geofences' }).flush([]);
    await expect(promise).resolves.toEqual([]);
  });

  it('creates a geofence zone', async () => {
    const request = {
      name: 'North perimeter',
      kind: 'KEEP_OUT' as const,
      polygon: [
        { latitude: 1, longitude: 1 },
        { latitude: 2, longitude: 2 },
        { latitude: 3, longitude: 1 },
      ],
      enabled: true,
    };
    const promise = api.createGeofence(request);
    const captured = http.expectOne({ method: 'POST', url: '/api/geofences' });
    expect(captured.request.body).toEqual(request);
    captured.flush({ id: 'z-1', ...request });
    await expect(promise).resolves.toMatchObject({ id: 'z-1' });
  });

  it('replaces a geofence zone wholesale on update', async () => {
    const request = {
      name: 'Renamed',
      kind: 'KEEP_IN' as const,
      polygon: [
        { latitude: 1, longitude: 1 },
        { latitude: 2, longitude: 2 },
        { latitude: 3, longitude: 1 },
      ],
      enabled: false,
    };
    const promise = api.updateGeofence('z-1', request);
    const captured = http.expectOne({ method: 'PUT', url: '/api/geofences/z-1' });
    expect(captured.request.body).toEqual(request);
    captured.flush({ id: 'z-1', ...request });
    await expect(promise).resolves.toMatchObject({ enabled: false });
  });

  it('deletes a geofence zone', async () => {
    const promise = api.deleteGeofence('z-1');
    http.expectOne({ method: 'DELETE', url: '/api/geofences/z-1' }).flush(null);
    await promise;
  });

  // --- Recording + clip export (docs/OPS-CORE-PLAN.md §R's frozen wire contract) -------------

  it('fetches a usage recording', async () => {
    const promise = api.usageRecording('u-1');
    http.expectOne({ method: 'GET', url: '/api/usages/u-1/recording' }).flush({ available: false });
    await expect(promise).resolves.toEqual({ available: false });
  });
});
