import { describe, expect, it } from 'vitest';
import { MAVLINK_METHOD, MEDIAMTX_METHOD, freshestNewCandidate, intakeState } from './intake-logic';
import { emptyFitOutRow, type FitOutRowDraft } from './fit-out-logic';
import type { DiscoveryCandidate, DiscoveryStatusResponse } from '../api/models';

const NOW = Date.parse('2026-09-05T12:00:00Z');

function status(overrides: Partial<DiscoveryStatusResponse> = {}): DiscoveryStatusResponse {
  return {
    sweepSeconds: 30,
    telemetryIntake: {
      bound: true,
      bindAddress: '0.0.0.0:14550',
      lobbyHeld: true,
      datagramsReceived: 0,
      bytesReceived: 0,
      framesDecoded: 0,
      unclaimedSysids: [],
      claimedSysids: [],
    },
    sources: [],
    ...overrides,
  };
}

function candidate(overrides: Partial<DiscoveryCandidate> = {}): DiscoveryCandidate {
  return {
    id: 'c1',
    method: 'mavlink',
    name: 'ArduPilot rover',
    address: '192.168.0.50',
    details: {},
    firstSeen: '2026-09-05T11:59:00Z',
    lastSeen: '2026-09-05T11:59:58Z',
    status: 'NEW',
    ...overrides,
  };
}

function findRow(role: FitOutRowDraft['role']): FitOutRowDraft {
  return { ...emptyFitOutRow(role), value: 'find' };
}

describe('intakeState', () => {
  it('is idle for a none/simulate row regardless of status', () => {
    expect(intakeState(emptyFitOutRow('sense'), status(), [], NOW)).toEqual({ kind: 'idle' });
    expect(intakeState({ ...emptyFitOutRow('sight'), value: 'simulate' }, status(), [], NOW)).toEqual({
      kind: 'idle',
    });
  });

  it('reports listening with the bind address when nothing has arrived on Sense', () => {
    expect(intakeState(findRow('sense'), status(), [], NOW)).toEqual({
      kind: 'listening',
      where: 'Listening on 0.0.0.0:14550',
      seenNothing: true,
    });
  });

  it('never claims failed for silence — S1/S3 honesty', () => {
    const result = intakeState(findRow('sense'), status(), [], NOW);
    expect(result.kind).not.toBe('failed');
  });

  it('surfaces the datagrams-but-no-frames diagnosis (P2)', () => {
    const result = intakeState(
      findRow('sense'),
      status({
        telemetryIntake: {
          bound: true,
          bindAddress: '0.0.0.0:14550',
          lobbyHeld: true,
          datagramsReceived: 412,
          bytesReceived: 51236,
          framesDecoded: 0,
          unclaimedSysids: [],
          claimedSysids: [],
        },
      }),
      [],
      NOW,
    );
    expect(result).toEqual({
      kind: 'listening',
      where: 'Listening on 0.0.0.0:14550',
      seenNothing: false,
      hint: '412 datagrams arrived on 0.0.0.0:14550 but none decoded as MAVLink 2 — check the protocol version and the port.',
    });
  });

  it('names every unclaimed sysid instead of pairing one with the freshest age', () => {
    // Sysid 7 may be long dead while 42 is the one transmitting -- the wire doesn't say which
    // sysid the last datagram came from, so the honest headline lists them all.
    const result = intakeState(
      findRow('sense'),
      status({
        telemetryIntake: {
          bound: true,
          bindAddress: '0.0.0.0:14550',
          lobbyHeld: true,
          datagramsReceived: 20,
          bytesReceived: 900,
          framesDecoded: 20,
          unclaimedSysids: [7, 42],
          claimedSysids: [],
          lastDatagramAt: new Date(NOW - 1_000).toISOString(),
        },
      }),
      [],
      NOW,
    );
    expect(result).toEqual({ kind: 'heard', what: 'Heartbeats from sysids 7, 42', ageMs: 1_000 });
  });

  it('prefers a named NEW candidate over the raw unclaimed-sysid counter', () => {
    const result = intakeState(
      findRow('sense'),
      status({
        telemetryIntake: {
          bound: true,
          bindAddress: '0.0.0.0:14550',
          lobbyHeld: true,
          datagramsReceived: 10,
          bytesReceived: 800,
          framesDecoded: 10,
          unclaimedSysids: [7],
          claimedSysids: [],
        },
      }),
      [candidate({ lastSeen: new Date(NOW - 2_000).toISOString() })],
      NOW,
    );
    expect(result).toEqual({ kind: 'heard', what: 'ArduPilot rover', ageMs: 2_000 });
  });

  it('falls back to the raw sysid when the sweep has not caught up yet', () => {
    const result = intakeState(
      findRow('sense'),
      status({
        telemetryIntake: {
          bound: true,
          bindAddress: '0.0.0.0:14550',
          lobbyHeld: true,
          datagramsReceived: 4,
          bytesReceived: 200,
          framesDecoded: 4,
          lastDatagramAt: new Date(NOW - 1_000).toISOString(),
          unclaimedSysids: [9],
          claimedSysids: [],
        },
      }),
      [],
      NOW,
    );
    expect(result).toEqual({ kind: 'heard', what: 'Heartbeat from sysid 9', ageMs: 1_000 });
  });

  it('reports failed only for the standing lobby genuinely not bound', () => {
    const result = intakeState(
      findRow('sense'),
      status({
        telemetryIntake: {
          bound: false,
          bindAddress: '0.0.0.0:14550',
          lobbyHeld: false,
          datagramsReceived: 0,
          bytesReceived: 0,
          framesDecoded: 0,
          unclaimedSysids: [],
          claimedSysids: [],
        },
      }),
      [],
      NOW,
    );
    expect(result.kind).toBe('failed');
  });

  it('reports failed for Sight when no video push is configured', () => {
    const result = intakeState(findRow('sight'), status({ videoIntake: undefined }), [], NOW);
    expect(result).toEqual({
      kind: 'failed',
      reason: 'No video push address is configured on this station.',
    });
  });

  it('reports listening for Sight when push is configured but nothing has arrived', () => {
    const result = intakeState(
      findRow('sight'),
      status({ videoIntake: { pushPort: 8554, pathPrefix: 'ingest/', readyPaths: [] } }),
      [],
      NOW,
    );
    expect(result).toEqual({ kind: 'listening', where: 'Watching ingest/ for a pushed stream', seenNothing: true });
  });

  it('reports heard for Sight from a ready path with no matching candidate yet', () => {
    const result = intakeState(
      findRow('sight'),
      status({ videoIntake: { pushPort: 8554, pathPrefix: 'ingest/', readyPaths: ['ingest/smoke-cam'] } }),
      [],
      NOW,
    );
    expect(result).toEqual({ kind: 'heard', what: 'A stream is publishing on ingest/smoke-cam', ageMs: 0 });
  });

  it('reports heard for Sight from a named mediamtx candidate', () => {
    const result = intakeState(
      findRow('sight'),
      status({ videoIntake: { pushPort: 8554, pathPrefix: 'ingest/', readyPaths: ['ingest/smoke-cam'] } }),
      [candidate({ method: 'mediamtx', name: 'smoke-cam', lastSeen: new Date(NOW - 500).toISOString() })],
      NOW,
    );
    expect(result).toEqual({ kind: 'heard', what: 'smoke-cam', ageMs: 500 });
  });
});

describe('freshestNewCandidate', () => {
  it('picks the freshest NEW candidate for the given method, ignoring other methods/statuses', () => {
    const rows = [
      candidate({ id: 'old', method: MAVLINK_METHOD, lastSeen: '2026-09-05T11:00:00Z' }),
      candidate({ id: 'fresh', method: MAVLINK_METHOD, lastSeen: '2026-09-05T11:59:00Z' }),
      candidate({ id: 'other-method', method: MEDIAMTX_METHOD, lastSeen: '2026-09-05T11:59:59Z' }),
      candidate({ id: 'registered', method: MAVLINK_METHOD, status: 'REGISTERED', lastSeen: '2026-09-05T11:59:59Z' }),
    ];
    expect(freshestNewCandidate(rows, MAVLINK_METHOD)?.id).toBe('fresh');
  });

  it('is undefined when nothing matches', () => {
    expect(freshestNewCandidate([], MAVLINK_METHOD)).toBeUndefined();
  });
});
