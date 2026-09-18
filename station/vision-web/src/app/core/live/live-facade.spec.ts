import { TestBed } from '@angular/core/testing';
import { Store } from '@ngrx/store';
import { describe, expect, it, vi } from 'vitest';
import type { DetectionResult, StreamTracksResponse } from '../api/models';
import { VisionApi } from '../api/vision-api';
import { provideAppState } from '../state/app-state';
import { LiveFacade } from './live-facade';
import { LiveSocketActions } from './state/live.actions';
import { liveFeature } from './state/live.reducer';

/** Lets the connection$ effect's synchronous-but-async-dispatched degrade-to-closed settle. */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

function setup() {
  TestBed.configureTestingModule({
    providers: [provideAppState(), LiveFacade, { provide: VisionApi, useValue: { updateLiveTopics: vi.fn().mockResolvedValue({}) } }],
  });
  return { facade: TestBed.inject(LiveFacade), store: TestBed.inject(Store) };
}

function tracksResponse(streamId: string, partial: Partial<StreamTracksResponse> = {}): StreamTracksResponse {
  return { streamId, lockedTrackId: -1, tracks: [], objects: [], ...partial };
}

function detectionResult(streamId: string): DetectionResult {
  return { streamId, frameSequence: 1, capturedAt: '2026-09-18T00:00:00Z', inferenceMillis: 10, detections: [] };
}

/**
 * The `live` slice end to end through `LiveFacade` — real `Store`/effects/reducer, a real
 * `LiveGateway` (which degrades to unavailable on its own under jsdom, exactly like the deleted
 * `LiveStore` did — see that class's doc comment, preserved on `LiveGateway`), so this spec never
 * fakes the gateway itself. `live.reducer.spec.ts`/`live.effects.spec.ts` already cover every
 * branch and timing edge directly; this file's job is only the facade's own wiring: signal
 * projection, per-asset `Signal` identity caching, and that every dispatch method reaches the
 * right action (docs/plans/active/NGRX-MIGRATION-PLAN.md §8).
 */
describe('LiveFacade', () => {
  it('degrades to closed shortly after construction — no EventSource under jsdom', async () => {
    const { facade } = setup();
    await flush();
    expect(facade.connectionState()).toBe('closed');
  });

  it('telemetryFor/detectionsFor/geoFor/cvTraceFor/linksFor/tracksFor read undefined/empty before any arrival', () => {
    const { facade } = setup();
    expect(facade.telemetryFor('a-1')()).toEqual([]);
    expect(facade.detectionsFor('a-1')()).toBeUndefined();
    expect(facade.geoFor('a-1')()).toBeUndefined();
    expect(facade.cvTraceFor('a-1')()).toBeUndefined();
    expect(facade.linksFor('a-1')()).toBeUndefined();
    expect(facade.tracksFor('a-1')()).toBeNull();
    expect(facade.worldObjectsFor('a-1')()).toEqual([]);
  });

  it('telemetryFor(assetId) returns the same Signal instance on repeated calls (identity-cached)', () => {
    const { facade } = setup();
    expect(facade.telemetryFor('a-1')).toBe(facade.telemetryFor('a-1'));
  });

  it('untrackTelemetry evicts the cache — a later telemetryFor(assetId) is a fresh Signal', () => {
    const { facade } = setup();
    const first = facade.telemetryFor('a-1');
    facade.trackTelemetry('a-1');
    facade.untrackTelemetry('a-1');
    expect(facade.telemetryFor('a-1')).not.toBe(first);
  });

  it('trackTelemetry/untrackTelemetry ref-count the telemetry:<assetId> topic through the real reducer', () => {
    const { facade, store } = setup();
    const topicRefs = store.selectSignal(liveFeature.selectTopicRefs);
    facade.trackTelemetry('a-1');
    expect(topicRefs()['telemetry:a-1']).toBe(1);
    facade.untrackTelemetry('a-1');
    expect(topicRefs()['telemetry:a-1']).toBeUndefined();
  });

  it('detectionsFor reflects a detections envelope dispatched straight to the store', () => {
    const { facade, store } = setup();
    const result = detectionResult('s-1');
    store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 1, assetId: 'a-1', type: 'detections', payload: result } }));
    expect(facade.detectionsFor('a-1')()).toEqual(result);
  });

  it('tracksFor and worldObjectsFor both project the same tracks:<assetId> arrival', () => {
    const { facade, store } = setup();
    const response = tracksResponse('s-1', { lockedTrackId: 7 });
    store.dispatch(LiveSocketActions.envelopeReceived({ envelope: { seq: 2, assetId: 'a-1', type: 'tracks', payload: response } }));

    expect(facade.tracksFor('a-1')()).toEqual(response);
    expect(facade.worldObjectsFor('a-1')()).toEqual(response.objects);
  });

  it('untrackWorldObjects evicts both the tracks and the derived worldObjects cache entries', () => {
    const { facade } = setup();
    const tracksBefore = facade.tracksFor('a-1');
    const worldBefore = facade.worldObjectsFor('a-1');
    facade.trackWorldObjects('a-1');
    facade.untrackWorldObjects('a-1');
    expect(facade.tracksFor('a-1')).not.toBe(tracksBefore);
    expect(facade.worldObjectsFor('a-1')).not.toBe(worldBefore);
  });

  it('reconnect() and stop() dispatch their own page actions without throwing', async () => {
    const { facade } = setup();
    await flush();
    expect(() => facade.stop()).not.toThrow();
    expect(() => facade.reconnect()).not.toThrow();
    await flush();
    expect(facade.connectionState()).toBe('closed'); // still no EventSource under jsdom
  });

  it('always-on signals (fleet, liveEvents, devices, systemStatus) start empty/undefined honestly', () => {
    const { facade } = setup();
    expect(facade.fleet()).toBeUndefined();
    expect(facade.liveEvents()).toEqual([]);
    expect(facade.devices()).toBeUndefined();
    expect(facade.systemStatus()).toBeUndefined();
  });
});
