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
});
