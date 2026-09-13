import { describe, expect, it } from 'vitest';
import fixture from './__fixtures__/stream-tracks.wire.json';
import { DetectionRate, FollowStatus, PipelineLatency, StreamTrack, StreamTracksResponse, TrackStats } from './models';

// This spec is the TypeScript half of CV-ORCHESTRATION wave W9's "tracks: carries the whole
// StreamTracksResponse snapshot" acceptance test (docs/plans/active/CV-ORCHESTRATION-PLAN.md
// §4.6/§4.9, decision E25). The Java half (station/vision-api's StreamTracksResponseWireContractTest)
// builds a StreamTracksResponse via StreamTracksResponse.from(TracksSnapshot, Instant) -- the same
// assembly `GET /api/streams/{id}/tracks` and the `tracks:<assetId>` SSE topic both now build from
// ("one assembly, two transports") -- with every optional field populated (a booked track, stats,
// latency, rate, detectionState, an active follow lock, one world object), serializes it with the
// app's real Jackson config, and commits the result as `./__fixtures__/stream-tracks.wire.json`. This
// file loads that exact fixture and proves it satisfies the `StreamTracksResponse` TypeScript type
// below -- key for key, no extra keys either way -- the same `Record<keyof T, true>` technique
// `cv-trace.wire.contract.spec.ts`/`world-object.wire.contract.spec.ts` use. `full.objects[0]`'s own
// shape is already pinned by `world-object.wire.contract.spec.ts`, so this file only checks that
// `objects` carries at least one entry, not its internals again.

const streamTracksResponseKeys: Record<keyof StreamTracksResponse, true> = {
  streamId: true,
  lockedTrackId: true,
  tracks: true,
  stats: true,
  latency: true,
  rate: true,
  detectionState: true,
  follow: true,
  objects: true,
};

const streamTrackKeys: Record<keyof StreamTrack, true> = {
  trackId: true,
  label: true,
  confidence: true,
  box: true,
  state: true,
  source: true,
  velocityX: true,
  velocityY: true,
  ageFrames: true,
  reupdated: true,
  firstSeen: true,
  lastSeen: true,
};

const trackStatsKeys: Record<keyof TrackStats, true> = {
  mode: true,
  engineId: true,
  windowSeconds: true,
  detectorPasses: true,
  trackerFrames: true,
  dutyRatio: true,
  trackerMillisP50: true,
  trackerMillisP95: true,
  lastDetectorReason: true,
  byState: true,
};

const pipelineLatencyKeys: Record<keyof PipelineLatency, true> = {
  windowSeconds: true,
  samples: true,
  roundTripMillisP50: true,
  roundTripMillisP95: true,
  roundTripMillisMax: true,
  updateIntervalMillisP50: true,
  effectiveFps: true,
  worstBoxAgeMillis: true,
};

const detectionRateKeys: Record<keyof DetectionRate, true> = {
  windowSeconds: true,
  sourceFps: true,
  targetFps: true,
  demandFps: true,
  submittedFps: true,
  submitted: true,
  droppedInFlight: true,
  droppedOutage: true,
  missedDeadlines: true,
  dropRatio: true,
  transport: true,
  decodeMillisP50: true,
};

const followStatusKeys: Record<keyof FollowStatus, true> = {
  state: true,
  trackId: true,
  label: true,
  since: true,
  lastSeenAt: true,
  lastSeenAgeMillis: true,
  lastBox: true,
  reacquirable: true,
  recoveredAfterMillis: true,
  recoveryConfidence: true,
};

/** Sorted key list of a compiler-verified `Record<keyof T, true>` map -- the expected side of every
 *  "does the fixture's actual key set match T exactly" assertion below. */
function keysOf(map: Record<string, true>): string[] {
  return Object.keys(map).sort();
}

function actualKeysOf(value: object): string[] {
  return Object.keys(value).sort();
}

describe('StreamTracksResponse wire contract (fixture: __fixtures__/stream-tracks.wire.json)', () => {
  const full = fixture.full;
  const minimal = fixture.minimal;

  it('full: top-level keys match StreamTracksResponse exactly, every optional field present', () => {
    expect(actualKeysOf(full)).toEqual(keysOf(streamTracksResponseKeys));
    expect(full.lockedTrackId).toBe(7);
  });

  it('full: tracks carries one booked TrackedObject, keys match StreamTrack exactly', () => {
    expect(full.tracks).toHaveLength(1);
    expect(actualKeysOf(full.tracks[0])).toEqual(keysOf(streamTrackKeys));
  });

  it('full: stats keys match TrackStats exactly', () => {
    expect(actualKeysOf(full.stats)).toEqual(keysOf(trackStatsKeys));
  });

  it('full: latency keys match PipelineLatency exactly', () => {
    expect(actualKeysOf(full.latency)).toEqual(keysOf(pipelineLatencyKeys));
  });

  it('full: rate keys match DetectionRate exactly', () => {
    expect(actualKeysOf(full.rate)).toEqual(keysOf(detectionRateKeys));
  });

  it('full: detectionState is a real DetectionState member', () => {
    expect(full.detectionState).toBe('RUNNING');
  });

  it('full: follow keys match FollowStatus exactly, holding the same track the lock is on', () => {
    expect(actualKeysOf(full.follow)).toEqual(keysOf(followStatusKeys));
    expect(full.follow.state).toBe('HOLDING');
    expect(full.follow.trackId).toBe(full.lockedTrackId);
  });

  it('full: objects carries at least one WorldObject fold entry -- its own shape is world-object.wire.contract.spec.ts\'s job', () => {
    expect(full.objects.length).toBeGreaterThan(0);
  });

  it('minimal: never-booked shape -- tracks/objects empty, every optional field absent, lockedTrackId 0', () => {
    expect(actualKeysOf(minimal)).toEqual(['streamId', 'lockedTrackId', 'tracks', 'objects'].sort());
    expect(minimal.tracks).toEqual([]);
    expect(minimal.objects).toEqual([]);
    expect(minimal.lockedTrackId).toBe(0);
  });
});
