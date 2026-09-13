import { describe, expect, it } from 'vitest';
import {
  allTrackIds,
  assetIdForStream,
  clockTime,
  cvSubsystemRow,
  evidenceRowsFor,
  formatRecord,
  latestFrame,
  serializeTrace,
  traceFileName,
  worldObjectFor,
} from './cv-inspector-logic';
import type { AssetAttention, CvTrace, FleetSummary, FrameLedger, SystemStatus, WorldObject } from '../../core/api/models';

function frame(sequence: number, objects: FrameLedger['objects'] = {}): FrameLedger {
  return {
    streamId: 'stream-1',
    sequence,
    capturedAtMillis: 1_700_000_000_000 + sequence,
    levelServed: 2,
    detectorReason: 'FULL',
    eligible: ['detect', 'assoc', 'predict'],
    entries: [],
    objects,
    dropsSinceLast: 0,
    gateWaitMillis: 0,
    totalMillis: 0,
    halted: false,
  };
}

function worldObject(id: number): WorldObject {
  return {
    state: { id, lifecycle: 'CONFIRMED', streamId: 'stream-1' },
    operator: { followed: false, denied: false },
    event: {},
    render: { tier: 'T1' },
  };
}

describe('latestFrame', () => {
  it('is undefined for an empty ring', () => {
    expect(latestFrame([])).toBeUndefined();
  });

  it('is the last (newest) element, matching the ring\'s documented oldest-first order', () => {
    expect(latestFrame([frame(1), frame(2), frame(3)])?.sequence).toBe(3);
  });
});

describe('allTrackIds', () => {
  it('unions ids from every frame\'s objects with every world object\'s id, sorted ascending', () => {
    const frames = [frame(1, { '7': [] }), frame(2, { '3': [], '7': [] })];
    const world = [worldObject(9)];
    expect(allTrackIds(frames, world)).toEqual([3, 7, 9]);
  });

  it('is empty when there is nothing in either source', () => {
    expect(allTrackIds([], [])).toEqual([]);
  });

  it('de-duplicates an id seen in both a frame and world', () => {
    const frames = [frame(1, { '5': [] })];
    const world = [worldObject(5)];
    expect(allTrackIds(frames, world)).toEqual([5]);
  });
});

describe('evidenceRowsFor', () => {
  it('flattens evidence for one track across every frame in the ring, oldest first', () => {
    const frames = [
      frame(1, { '7': [{ contributorId: 'predict.cv', claim: { held: '0.1,0.2,0.3,0.4' } }] }),
      frame(2, { '7': [{ contributorId: 'predict.cv', claim: { held: '0.11,0.21,0.31,0.41' } }] }),
    ];
    const rows = evidenceRowsFor(frames, 7);
    expect(rows).toHaveLength(2);
    expect(rows[0].frameSequence).toBe(1);
    expect(rows[1].frameSequence).toBe(2);
    expect(rows[0].contributorId).toBe('predict.cv');
  });

  it('is empty for a track no frame in the ring mentions', () => {
    expect(evidenceRowsFor([frame(1, { '7': [] })], 99)).toEqual([]);
  });

  it('skips a frame that never touched the requested track without throwing', () => {
    const frames = [frame(1, { '7': [{ contributorId: 'predict.cv', claim: {} }] }), frame(2)];
    expect(evidenceRowsFor(frames, 7)).toHaveLength(1);
  });
});

describe('worldObjectFor', () => {
  it('finds the object matching the given track id', () => {
    const world = [worldObject(1), worldObject(2)];
    expect(worldObjectFor(world, 2)).toBe(world[1]);
  });

  it('is undefined when no world object has ever folded that id', () => {
    expect(worldObjectFor([worldObject(1)], 42)).toBeUndefined();
  });
});

describe('formatRecord', () => {
  it('renders an empty record as an em dash, never an empty string', () => {
    expect(formatRecord({})).toBe('—');
  });

  it('joins every key=value pair, comma-separated', () => {
    expect(formatRecord({ held: '0.1,0.2,0.3,0.4', velocity: '0.01,-0.02' })).toBe(
      'held=0.1,0.2,0.3,0.4, velocity=0.01,-0.02',
    );
  });
});

describe('clockTime', () => {
  it('renders a non-empty local time string for a given epoch millis', () => {
    expect(clockTime(1_700_000_000_000).length).toBeGreaterThan(0);
  });
});

describe('traceFileName', () => {
  it('embeds the stream id and a filesystem-safe timestamp, ending in .json', () => {
    const name = traceFileName('stream-1', 1_700_000_000_000);
    expect(name.startsWith('cv-trace-stream-1-')).toBe(true);
    expect(name.endsWith('.json')).toBe(true);
    expect(name).not.toContain(':');
  });

  it('two saves a second apart never collide', () => {
    const first = traceFileName('stream-1', 1_700_000_000_000);
    const second = traceFileName('stream-1', 1_700_000_001_000);
    expect(first).not.toBe(second);
  });
});

describe('serializeTrace', () => {
  it('round-trips every ledger back to an equal object', () => {
    const trace: CvTrace = {
      streamId: 'stream-1',
      gate: [],
      frame: [frame(1, { '7': [{ contributorId: 'predict.cv', claim: { held: '0.1,0.2,0.3,0.4' } }] })],
      world: [worldObject(7)],
    };
    expect(JSON.parse(serializeTrace(trace))).toEqual(trace);
  });

  it('is pretty-printed, not a single minified line', () => {
    const trace: CvTrace = { streamId: 'stream-1', gate: [], frame: [], world: [] };
    expect(serializeTrace(trace)).toContain('\n');
  });
});

function attention(overrides: Partial<AssetAttention> = {}): AssetAttention {
  return {
    assetId: 'asset-1',
    displayName: 'Rover 1',
    categoryId: 'rover',
    categoryName: 'Rover',
    lifecycle: 'ACTIVE',
    streaming: true,
    openEventCount: 0,
    ...overrides,
  };
}

describe('assetIdForStream', () => {
  it('finds the asset whose AssetAttention row names this stream', () => {
    const summary: FleetSummary = { categories: [], totalAssets: 1, assets: [attention({ streamId: 'stream-1' })] };
    expect(assetIdForStream(summary, 'stream-1')).toBe('asset-1');
  });

  it('is undefined before the fleet summary has ever loaded', () => {
    expect(assetIdForStream(undefined, 'stream-1')).toBeUndefined();
  });

  it('is undefined when no asset in the summary claims this stream', () => {
    const summary: FleetSummary = { categories: [], totalAssets: 1, assets: [attention({ streamId: 'stream-2' })] };
    expect(assetIdForStream(summary, 'stream-1')).toBeUndefined();
  });

  it('is undefined for an asset that is not currently streaming (no streamId at all)', () => {
    const summary: FleetSummary = {
      categories: [],
      totalAssets: 1,
      assets: [attention({ streaming: false, streamId: undefined })],
    };
    expect(assetIdForStream(summary, 'stream-1')).toBeUndefined();
  });
});

describe('cvSubsystemRow', () => {
  const status = (): SystemStatus => ({
    overall: 'OK',
    checkedAt: '2026-09-13T00:00:00Z',
    subsystems: [
      { id: 'mavlink-link', label: 'MAVLink', health: 'OK', detail: 'ok' },
      { id: 'cv-service', label: 'CV inference', health: 'OK', detail: 'cv-service channel is READY; 2 streams' },
    ],
  });

  it('finds the cv-service row among the other subsystems', () => {
    expect(cvSubsystemRow(status())?.id).toBe('cv-service');
  });

  it('is undefined before the first status fetch ever resolves', () => {
    expect(cvSubsystemRow(undefined)).toBeUndefined();
  });

  it('is undefined when this deployment has no cv-service provider wired', () => {
    expect(cvSubsystemRow({ overall: 'UNKNOWN', checkedAt: 'x', subsystems: [] })).toBeUndefined();
  });
});
