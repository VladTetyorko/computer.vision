import { describe, expect, it } from 'vitest';
import type {
  AssetSummary,
  CorrectionResponse,
  DetectionEvent,
  DetectionResult,
  DevicesSnapshot,
  DiscoveryCandidate,
  DiscoveryEventPayload,
  FrameLedger,
  GeofenceZone,
  GeofenceZoneEventPayload,
  LinkGroupResponse,
  LinkView,
  MapEventPayload,
  StreamTracksResponse,
  SystemStatus,
  TelemetrySample,
} from '../../api/models';
import { cvTraceTopic, detectionsTopic, geoTopic, linksTopic, telemetryTopic, tracksTopic } from '../live-fallback-logic';
import { LiveApiActions, LivePageActions, LiveSocketActions } from './live.actions';
import {
  MAX_LIVE_DETECTION_EVENTS,
  MAX_LIVE_DISCOVERY_EVENTS,
  MAX_LIVE_EVENTS,
  MAX_LIVE_MAP_EVENTS,
  MAX_LIVE_ZONE_EVENTS,
  initialLiveState,
} from './live.model';
import { liveFeature, topicFor } from './live.reducer';

const reduce = liveFeature.reducer;

// --- Minimal fixtures, one per LiveEnvelope payload shape — mirrors the codebase's standing
// per-spec-file fixture-factory convention (e.g. `geo-store.spec.ts#correction`, `links-store.spec.ts#group`). ---

function asset(assetId: string): AssetSummary {
  return {
    assetId,
    displayName: 'Drone One',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'STREAMING',
    lifecycle: 'ACTIVE',
    attributes: {},
  };
}

function sample(deviceId: string, at: string): TelemetrySample {
  return { deviceId, at, latitude: 50.45, longitude: 30.52 };
}

function detectionResult(streamId: string, frameSequence: number): DetectionResult {
  return { streamId, frameSequence, capturedAt: '2026-09-18T00:00:00Z', inferenceMillis: 10, detections: [] };
}

function liveEvent(id: string): { readonly id: string; readonly at: string; readonly type: string; readonly message: string; readonly attributes: Record<string, string> } {
  return { id, at: '2026-09-18T00:00:00Z', type: 'STREAM_STARTED', message: 'started', attributes: {} };
}

function devicesSnapshot(): DevicesSnapshot {
  return { devices: [], streams: [] };
}

function detectionEvent(id: string): DetectionEvent {
  return {
    id,
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.8,
    firstSeen: '2026-09-18T00:00:00Z',
    lastSeen: '2026-09-18T00:00:05Z',
    state: 'OPEN',
  };
}

function mapEvent(layerId: string): MapEventPayload {
  return { entity: 'mark', action: 'created', layerId };
}

function correction(assetId: string): CorrectionResponse {
  return {
    assetId,
    usageId: 'usage-1',
    frameAt: '2026-09-18T00:00:00Z',
    computedAt: '2026-09-18T00:00:01Z',
    status: 'CONFIRMED',
    source: 'VISUAL_HEAVY',
    divergent: false,
  };
}

function candidate(id: string): DiscoveryCandidate {
  return {
    id,
    method: 'onvif',
    name: 'Camera 1',
    address: '192.168.0.10',
    details: {},
    firstSeen: '2026-09-18T00:00:00Z',
    lastSeen: '2026-09-18T00:00:00Z',
    status: 'NEW',
  };
}

function discoveryEvent(id: string): DiscoveryEventPayload {
  return { action: 'REPORTED', candidate: candidate(id) };
}

function zone(id: string): GeofenceZone {
  return {
    id,
    name: 'North perimeter',
    kind: 'KEEP_OUT',
    polygon: [
      { latitude: 1, longitude: 1 },
      { latitude: 2, longitude: 2 },
      { latitude: 3, longitude: 1 },
    ],
    enabled: true,
  };
}

function zoneEvent(id: string): GeofenceZoneEventPayload {
  return { action: 'CREATED', zone: zone(id) };
}

function systemStatus(): SystemStatus {
  return { overall: 'OK', checkedAt: '2026-09-18T00:00:00Z', subsystems: [] };
}

function tracksResponse(streamId: string): StreamTracksResponse {
  return { streamId, lockedTrackId: -1, tracks: [], objects: [] };
}

function frameLedger(streamId: string, sequence: number): FrameLedger {
  return {
    streamId,
    sequence,
    capturedAtMillis: 1_700_000_000_000 + sequence,
    levelServed: 2,
    detectorReason: 'FULL',
    eligible: [],
    entries: [],
    objects: {},
    dropsSinceLast: 0,
    gateWaitMillis: 0,
    totalMillis: 0,
    halted: false,
    detections: [],
    frameWidth: 0,
    frameHeight: 0,
  };
}

function link(): LinkView {
  return { id: 'link-1', carrier: 'UDP', serialRole: 'NONE', label: 'Wi-Fi', active: true, receiving: true, heartbeatAgeSeconds: 2 };
}

function linkGroup(assetId: string): LinkGroupResponse {
  return { assetId, links: [link()], activeLinkId: 'link-1', pinned: false };
}

describe('live reducer', () => {
  it('starts connecting, with every topic/payload field empty', () => {
    expect(initialLiveState.connectionState).toBe('connecting');
    expect(initialLiveState.connectionId).toBeUndefined();
    expect(initialLiveState.topicRefs).toEqual({});
    expect(initialLiveState.fleet).toBeUndefined();
    expect(initialLiveState.liveEvents).toEqual([]);
  });

  describe('connection commands (docs/plans/done/NGRX-MIGRATION-PLAN.md §8 — synchronous, ahead of any gateway event)', () => {
    it('Reconnect Requested resets to connecting and drops the old connectionId', () => {
      const open = { ...initialLiveState, connectionState: 'open' as const, connectionId: 'conn-1' };
      const next = reduce(open, LivePageActions.reconnectRequested());
      expect(next.connectionState).toBe('connecting');
      expect(next.connectionId).toBeUndefined();
    });

    it('Stop Requested resets to closed and drops the connectionId — no Socket Closed needed', () => {
      const open = { ...initialLiveState, connectionState: 'open' as const, connectionId: 'conn-1' };
      const next = reduce(open, LivePageActions.stopRequested());
      expect(next.connectionState).toBe('closed');
      expect(next.connectionId).toBeUndefined();
    });
  });

  describe('ref-counted track/untrack — telemetry in full (the shared shape every other family reuses)', () => {
    it('the first tracker sets the topic ref count to 1', () => {
      const next = reduce(initialLiveState, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
      expect(next.topicRefs[telemetryTopic('a-1')]).toBe(1);
    });

    it('a second tracker of the same asset shares the one count, now 2', () => {
      const once = reduce(initialLiveState, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
      const twice = reduce(once, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
      expect(twice.topicRefs[telemetryTopic('a-1')]).toBe(2);
    });

    it('untrack decrements without clearing while another subscriber remains', () => {
      const twice = reduce(
        reduce(initialLiveState, LivePageActions.telemetryTracked({ assetId: 'a-1' })),
        LivePageActions.telemetryTracked({ assetId: 'a-1' }),
      );
      const withData = { ...twice, telemetryByAssetId: { 'a-1': [sample('dev-1', '2026-09-18T00:00:00Z')] } };
      const oneLeft = reduce(withData, LivePageActions.telemetryUntracked({ assetId: 'a-1' }));
      expect(oneLeft.topicRefs[telemetryTopic('a-1')]).toBe(1);
      expect(oneLeft.telemetryByAssetId['a-1']).toBeDefined(); // still a subscriber — data must survive
    });

    it('the last untrack removes the topic key entirely AND clears the per-asset entry', () => {
      const tracked = reduce(initialLiveState, LivePageActions.telemetryTracked({ assetId: 'a-1' }));
      const withData = { ...tracked, telemetryByAssetId: { 'a-1': [sample('dev-1', '2026-09-18T00:00:00Z')] } };
      const untracked = reduce(withData, LivePageActions.telemetryUntracked({ assetId: 'a-1' }));
      expect(Object.keys(untracked.topicRefs)).not.toContain(telemetryTopic('a-1'));
      expect(untracked.telemetryByAssetId['a-1']).toBeUndefined();
    });

    it('untracking an asset that was never tracked is a harmless floor at zero (no negative count, key absent)', () => {
      const next = reduce(initialLiveState, LivePageActions.telemetryUntracked({ assetId: 'ghost' }));
      expect(next.topicRefs[telemetryTopic('ghost')]).toBeUndefined();
    });
  });

  describe('the remaining five ref-counted families — each maps to its own topic and clears its own field', () => {
    it('detections', () => {
      const tracked = reduce(initialLiveState, LivePageActions.detectionsTracked({ assetId: 'a-1' }));
      expect(tracked.topicRefs[detectionsTopic('a-1')]).toBe(1);
      const withData = { ...tracked, detectionsByAssetId: { 'a-1': detectionResult('s-1', 1) } };
      const untracked = reduce(withData, LivePageActions.detectionsUntracked({ assetId: 'a-1' }));
      expect(untracked.topicRefs[detectionsTopic('a-1')]).toBeUndefined();
      expect(untracked.detectionsByAssetId['a-1']).toBeUndefined();
    });

    it('geo', () => {
      const tracked = reduce(initialLiveState, LivePageActions.geoTracked({ assetId: 'a-1' }));
      expect(tracked.topicRefs[geoTopic('a-1')]).toBe(1);
      const withData = { ...tracked, geoByAssetId: { 'a-1': correction('a-1') } };
      const untracked = reduce(withData, LivePageActions.geoUntracked({ assetId: 'a-1' }));
      expect(untracked.topicRefs[geoTopic('a-1')]).toBeUndefined();
      expect(untracked.geoByAssetId['a-1']).toBeUndefined();
    });

    it('worldObjects — ref-counts tracksTopic and clears tracksByAssetId (backs both tracksFor and worldObjectsFor)', () => {
      const tracked = reduce(initialLiveState, LivePageActions.worldObjectsTracked({ assetId: 'a-1' }));
      expect(tracked.topicRefs[tracksTopic('a-1')]).toBe(1);
      const withData = { ...tracked, tracksByAssetId: { 'a-1': tracksResponse('s-1') } };
      const untracked = reduce(withData, LivePageActions.worldObjectsUntracked({ assetId: 'a-1' }));
      expect(untracked.topicRefs[tracksTopic('a-1')]).toBeUndefined();
      expect(untracked.tracksByAssetId['a-1']).toBeUndefined();
    });

    it('cvTrace', () => {
      const tracked = reduce(initialLiveState, LivePageActions.cvTraceTracked({ assetId: 'a-1' }));
      expect(tracked.topicRefs[cvTraceTopic('a-1')]).toBe(1);
      const withData = { ...tracked, cvTraceByAssetId: { 'a-1': frameLedger('s-1', 1) } };
      const untracked = reduce(withData, LivePageActions.cvTraceUntracked({ assetId: 'a-1' }));
      expect(untracked.topicRefs[cvTraceTopic('a-1')]).toBeUndefined();
      expect(untracked.cvTraceByAssetId['a-1']).toBeUndefined();
    });

    it('links', () => {
      const tracked = reduce(initialLiveState, LivePageActions.linksTracked({ assetId: 'a-1' }));
      expect(tracked.topicRefs[linksTopic('a-1')]).toBe(1);
      const withData = { ...tracked, linksByAssetId: { 'a-1': linkGroup('a-1') } };
      const untracked = reduce(withData, LivePageActions.linksUntracked({ assetId: 'a-1' }));
      expect(untracked.topicRefs[linksTopic('a-1')]).toBeUndefined();
      expect(untracked.linksByAssetId['a-1']).toBeUndefined();
    });
  });

  describe('topicFor — the wire topic string per family, shared with live.effects.ts', () => {
    it('maps every family to its own builder from live-fallback-logic.ts', () => {
      expect(topicFor('telemetry', 'a-1')).toBe(telemetryTopic('a-1'));
      expect(topicFor('detections', 'a-1')).toBe(detectionsTopic('a-1'));
      expect(topicFor('geo', 'a-1')).toBe(geoTopic('a-1'));
      expect(topicFor('worldObjects', 'a-1')).toBe(tracksTopic('a-1'));
      expect(topicFor('cvTrace', 'a-1')).toBe(cvTraceTopic('a-1'));
      expect(topicFor('links', 'a-1')).toBe(linksTopic('a-1'));
    });
  });

  describe('socket lifecycle', () => {
    it('Opened moves connectionState to open', () => {
      const next = reduce(initialLiveState, LiveSocketActions.opened());
      expect(next.connectionState).toBe('open');
    });

    it('Handshake Received records the connectionId without touching connectionState', () => {
      const connecting = { ...initialLiveState, connectionState: 'connecting' as const };
      const next = reduce(connecting, LiveSocketActions.handshakeReceived({ connectionId: 'conn-9', topics: [] }));
      expect(next.connectionId).toBe('conn-9');
      expect(next.connectionState).toBe('connecting');
    });

    it('Retrying (a native browser auto-retry) reports connecting, same as the initial attempt', () => {
      const open = { ...initialLiveState, connectionState: 'open' as const, connectionId: 'conn-1' };
      const next = reduce(open, LiveSocketActions.retrying());
      expect(next.connectionState).toBe('connecting');
    });

    it('Closed (fatal, or an explicit stop already handled) clears connectionState and connectionId', () => {
      const open = { ...initialLiveState, connectionState: 'open' as const, connectionId: 'conn-1' };
      const next = reduce(open, LiveSocketActions.closed());
      expect(next.connectionState).toBe('closed');
      expect(next.connectionId).toBeUndefined();
    });
  });

  describe('Envelope Received — applyEnvelope, all fourteen LiveEnvelope branches', () => {
    it('fleet — latest snapshot replaces the previous one', () => {
      const next = reduce(initialLiveState, LiveSocketActions.envelopeReceived({ envelope: { seq: 1, type: 'fleet', payload: [asset('a-1')] } }));
      expect(next.fleet).toEqual([asset('a-1')]);
    });

    it('telemetry — merges into the tracked asset’s samples (mergeTelemetrySamples), keyed by assetId', () => {
      const seeded = { ...initialLiveState, telemetryByAssetId: { 'a-1': [sample('dev-1', '2026-09-18T00:00:00Z')] } };
      const next = reduce(
        seeded,
        LiveSocketActions.envelopeReceived({
          envelope: { seq: 2, assetId: 'a-1', type: 'telemetry', payload: [sample('dev-1', '2026-09-18T00:00:01Z')] },
        }),
      );
      expect(next.telemetryByAssetId['a-1']).toHaveLength(2);
    });

    it('detections — latest-wins per assetId', () => {
      const next = reduce(
        initialLiveState,
        LiveSocketActions.envelopeReceived({ envelope: { seq: 3, assetId: 'a-1', type: 'detections', payload: detectionResult('s-1', 5) } }),
      );
      expect(next.detectionsByAssetId['a-1']).toEqual(detectionResult('s-1', 5));
    });

    it('event — prepends (newest-first) and caps at MAX_LIVE_EVENTS', () => {
      const full = { ...initialLiveState, liveEvents: Array.from({ length: MAX_LIVE_EVENTS }, (_, i) => liveEvent(`old-${i}`)) };
      const next = reduce(full, LiveSocketActions.envelopeReceived({ envelope: { seq: 4, type: 'event', payload: liveEvent('newest') } }));
      expect(next.liveEvents).toHaveLength(MAX_LIVE_EVENTS);
      expect(next.liveEvents[0].id).toBe('newest');
    });

    it('devices — latest snapshot replaces the previous one', () => {
      const next = reduce(initialLiveState, LiveSocketActions.envelopeReceived({ envelope: { seq: 5, type: 'devices', payload: devicesSnapshot() } }));
      expect(next.devices).toEqual(devicesSnapshot());
    });

    it('detection-events — appends chronologically (oldest-first) and caps at MAX_LIVE_DETECTION_EVENTS', () => {
      const full = {
        ...initialLiveState,
        detectionEvents: Array.from({ length: MAX_LIVE_DETECTION_EVENTS }, (_, i) => detectionEvent(`old-${i}`)),
      };
      const next = reduce(full, LiveSocketActions.envelopeReceived({ envelope: { seq: 6, type: 'detection-events', payload: detectionEvent('newest') } }));
      expect(next.detectionEvents).toHaveLength(MAX_LIVE_DETECTION_EVENTS);
      expect(next.detectionEvents.at(-1)?.id).toBe('newest');
      expect(next.detectionEvents[0].id).not.toBe('old-0'); // the oldest was dropped
    });

    it('map — appends chronologically and caps at MAX_LIVE_MAP_EVENTS', () => {
      const full = { ...initialLiveState, mapEvents: Array.from({ length: MAX_LIVE_MAP_EVENTS }, () => mapEvent('layer-old')) };
      const next = reduce(full, LiveSocketActions.envelopeReceived({ envelope: { seq: 7, type: 'map', payload: mapEvent('layer-new') } }));
      expect(next.mapEvents).toHaveLength(MAX_LIVE_MAP_EVENTS);
      expect(next.mapEvents.at(-1)?.layerId).toBe('layer-new');
    });

    it('geo — latest-wins per assetId', () => {
      const next = reduce(
        initialLiveState,
        LiveSocketActions.envelopeReceived({ envelope: { seq: 8, assetId: 'a-1', type: 'geo', payload: correction('a-1') } }),
      );
      expect(next.geoByAssetId['a-1']).toEqual(correction('a-1'));
    });

    it('discovery — appends chronologically and caps at MAX_LIVE_DISCOVERY_EVENTS', () => {
      const full = {
        ...initialLiveState,
        discoveryEvents: Array.from({ length: MAX_LIVE_DISCOVERY_EVENTS }, (_, i) => discoveryEvent(`old-${i}`)),
      };
      const next = reduce(full, LiveSocketActions.envelopeReceived({ envelope: { seq: 9, type: 'discovery', payload: discoveryEvent('newest') } }));
      expect(next.discoveryEvents).toHaveLength(MAX_LIVE_DISCOVERY_EVENTS);
      expect(next.discoveryEvents.at(-1)?.candidate.id).toBe('newest');
    });

    it('zones — appends chronologically and caps at MAX_LIVE_ZONE_EVENTS', () => {
      const full = { ...initialLiveState, zoneEvents: Array.from({ length: MAX_LIVE_ZONE_EVENTS }, (_, i) => zoneEvent(`old-${i}`)) };
      const next = reduce(full, LiveSocketActions.envelopeReceived({ envelope: { seq: 10, type: 'zones', payload: zoneEvent('newest') } }));
      expect(next.zoneEvents).toHaveLength(MAX_LIVE_ZONE_EVENTS);
      expect(next.zoneEvents.at(-1)?.zone.id).toBe('newest');
    });

    it('system — latest sample replaces the previous one', () => {
      const next = reduce(initialLiveState, LiveSocketActions.envelopeReceived({ envelope: { seq: 11, type: 'system', payload: systemStatus() } }));
      expect(next.systemStatus).toEqual(systemStatus());
    });

    it('tracks — latest-wins snapshot per assetId, backs both tracksFor and worldObjectsFor', () => {
      const next = reduce(
        initialLiveState,
        LiveSocketActions.envelopeReceived({ envelope: { seq: 12, assetId: 'a-1', type: 'tracks', payload: tracksResponse('s-1') } }),
      );
      expect(next.tracksByAssetId['a-1']).toEqual(tracksResponse('s-1'));
    });

    it('cv-trace — latest-wins per assetId', () => {
      const next = reduce(
        initialLiveState,
        LiveSocketActions.envelopeReceived({ envelope: { seq: 13, assetId: 'a-1', type: 'cv-trace', payload: frameLedger('s-1', 1) } }),
      );
      expect(next.cvTraceByAssetId['a-1']).toEqual(frameLedger('s-1', 1));
    });

    it('links — latest-wins whole-list snapshot per assetId', () => {
      const next = reduce(
        initialLiveState,
        LiveSocketActions.envelopeReceived({ envelope: { seq: 14, assetId: 'a-1', type: 'links', payload: linkGroup('a-1') } }),
      );
      expect(next.linksByAssetId['a-1']).toEqual(linkGroup('a-1'));
    });
  });

  describe('Topics Patch Failed', () => {
    it('is a deliberate no-op — the same state reference comes back, per its own doc comment', () => {
      const state = { ...initialLiveState, connectionId: 'conn-1' };
      const next = reduce(state, LiveApiActions.topicsPatchFailed({ topics: [telemetryTopic('a-1')] }));
      expect(next).toBe(state);
    });
  });
});
