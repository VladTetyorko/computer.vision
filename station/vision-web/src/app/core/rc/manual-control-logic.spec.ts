import { describe, expect, it } from 'vitest';
import {
  buildChannelsFrame,
  buildEngageFrame,
  buildReleaseFrame,
  computeLatencyMs,
  parseManualControlServerMessage,
  pushLatencySample,
  rollingAverageMs,
  MIN_SEND_GAP_MS,
  channelsEqual,
  keepaliveIntervalMs,
  shouldSendChannels,
} from './manual-control-logic';

describe('manual-control-logic', () => {
  describe('frame builders', () => {
    it('buildEngageFrame', () => {
      expect(buildEngageFrame('asset-1')).toEqual({ type: 'engage', assetId: 'asset-1' });
    });

    it('buildChannelsFrame copies axes/buttons rather than aliasing the input arrays', () => {
      const axes = [0.1, -0.5];
      const buttons = [1, 0];
      const frame = buildChannelsFrame(axes, buttons, 7, 1_000);
      expect(frame).toEqual({ type: 'channels', axes: [0.1, -0.5], buttons: [1, 0], seq: 7, tSent: 1_000 });
      expect(frame.axes).not.toBe(axes);
      expect(frame.buttons).not.toBe(buttons);
    });

    it('buildReleaseFrame', () => {
      expect(buildReleaseFrame()).toEqual({ type: 'release' });
    });
  });

  describe('parseManualControlServerMessage', () => {
    it('parses a valid engaged frame', () => {
      const engaged = {
        type: 'engaged',
        assetId: 'asset-1',
        rateHz: 33,
        vehicleKind: 'ROVER',
        profileCode: 'S-T-',
        profileName: 'Ground vehicle',
        channelMap: [
          {
            source: 'AXIS',
            function: 'STEERING',
            travel: 'CENTERED',
            sourceIndex: 0,
            rcChannel: 1,
            minMicros: 1000,
            centerMicros: 1500,
            maxMicros: 2000,
            label: 'Steering',
          },
        ],
      };
      expect(parseManualControlServerMessage(JSON.stringify(engaged))).toEqual(engaged);
    });

    it('rejects an engaged frame with a malformed channelMap entry — the whole message, not a partial accept', () => {
      const raw = JSON.stringify({
        type: 'engaged',
        assetId: 'asset-1',
        rateHz: 33,
        vehicleKind: 'ROVER',
        profileCode: 'S-T-',
        profileName: 'Ground vehicle',
        channelMap: [{ source: 'AXIS', sourceIndex: 0 /* missing everything else */ }],
      });
      expect(parseManualControlServerMessage(raw)).toBeUndefined();
    });

    it('rejects an engaged frame that omits the profile the surface is shaped from', () => {
      const raw = JSON.stringify({
        type: 'engaged',
        assetId: 'asset-1',
        rateHz: 33,
        channelMap: [],
      });
      expect(parseManualControlServerMessage(raw)).toBeUndefined();
    });

    it('parses a valid denied frame', () => {
      const raw = JSON.stringify({ type: 'denied', code: 'NOT_COMMANDABLE', reason: 'No live source address.' });
      expect(parseManualControlServerMessage(raw)).toEqual({
        type: 'denied',
        code: 'NOT_COMMANDABLE',
        reason: 'No live source address.',
      });
    });

    it('accepts a denied frame with a handler-defensive code outside the four engage-refusal codes — the backend (ManualControlWebSocketHandler) also sends MALFORMED/UNKNOWN_TYPE/BAD_REQUEST', () => {
      const raw = JSON.stringify({ type: 'denied', code: 'MALFORMED', reason: 'could not parse frame as JSON' });
      expect(parseManualControlServerMessage(raw)).toEqual({
        type: 'denied',
        code: 'MALFORMED',
        reason: 'could not parse frame as JSON',
      });
    });

    it('rejects a denied frame with no reason string', () => {
      const raw = JSON.stringify({ type: 'denied', code: 'OUT_OF_SCOPE' });
      expect(parseManualControlServerMessage(raw)).toBeUndefined();
    });

    it('parses a valid ack frame', () => {
      const raw = JSON.stringify({ type: 'ack', seq: 5, tSent: 1_000, tServer: 1_010 });
      expect(parseManualControlServerMessage(raw)).toEqual({ type: 'ack', seq: 5, tSent: 1_000, tServer: 1_010 });
    });

    it('parses a valid released frame', () => {
      const raw = JSON.stringify({ type: 'released', reason: 'EXPLICIT' });
      expect(parseManualControlServerMessage(raw)).toEqual({ type: 'released', reason: 'EXPLICIT' });
    });

    it('rejects a released frame with an unknown reason', () => {
      const raw = JSON.stringify({ type: 'released', reason: 'WHATEVER' });
      expect(parseManualControlServerMessage(raw)).toBeUndefined();
    });

    it('parses a valid watchdog frame', () => {
      const raw = JSON.stringify({ type: 'watchdog', timeoutMs: 300 });
      expect(parseManualControlServerMessage(raw)).toEqual({ type: 'watchdog', timeoutMs: 300 });
    });

    it('returns undefined for unparsable JSON', () => {
      expect(parseManualControlServerMessage('not json')).toBeUndefined();
    });

    it('returns undefined for a JSON value that is not an object', () => {
      expect(parseManualControlServerMessage('42')).toBeUndefined();
      expect(parseManualControlServerMessage('null')).toBeUndefined();
    });

    it('returns undefined for an unknown type', () => {
      expect(parseManualControlServerMessage(JSON.stringify({ type: 'mystery' }))).toBeUndefined();
    });
  });

  describe('computeLatencyMs', () => {
    it('is the client-receipt-time minus tSent — a round trip, not tServer - tSent', () => {
      expect(computeLatencyMs(1_000, 1_042)).toBe(42);
    });

    it('clamps to 0 rather than going negative on clock skew', () => {
      expect(computeLatencyMs(1_000, 990)).toBe(0);
    });
  });

  describe('pushLatencySample', () => {
    it('appends without mutating the input array', () => {
      const window = [10, 20];
      const next = pushLatencySample(window, 30, 5);
      expect(next).toEqual([10, 20, 30]);
      expect(window).toEqual([10, 20]);
    });

    it('trims to the most recent `max` samples', () => {
      const next = pushLatencySample([1, 2, 3], 4, 3);
      expect(next).toEqual([2, 3, 4]);
    });
  });

  describe('rollingAverageMs', () => {
    it('is undefined for an empty window — no ack yet, never a fabricated 0', () => {
      expect(rollingAverageMs([])).toBeUndefined();
    });

    it('averages the window', () => {
      expect(rollingAverageMs([10, 20, 30])).toBe(20);
    });
  });

  describe('keepaliveIntervalMs', () => {
    it('the plan default (33Hz) rounds to ~30ms', () => {
      expect(keepaliveIntervalMs(33)).toBe(Math.round(1000 / 33));
    });

    it('clamps below 10Hz to the 10Hz floor', () => {
      expect(keepaliveIntervalMs(1)).toBe(100);
    });

    it('clamps above 50Hz to the 50Hz ceiling', () => {
      expect(keepaliveIntervalMs(1000)).toBe(20);
    });

    it('stays far under the 300ms input-loss watchdog even at the slowest clamp', () => {
      expect(keepaliveIntervalMs(1)).toBeLessThan(300 / 2);
    });
  });

  describe('channelsEqual', () => {
    it('is true for the same values', () => {
      expect(channelsEqual([0.5, -0.5], [0.5, -0.5])).toBe(true);
    });

    it('is false for a different value or a different length', () => {
      expect(channelsEqual([0.5, -0.5], [0.5, -0.4])).toBe(false);
      expect(channelsEqual([0.5], [0.5, 0])).toBe(false);
    });
  });

  describe('shouldSendChannels', () => {
    const KEEPALIVE = 30;

    it('sends an unchanged frame once the keepalive floor is due', () => {
      expect(shouldSendChannels(false, KEEPALIVE, KEEPALIVE)).toBe(true);
    });

    it('holds an unchanged frame before the floor is due', () => {
      expect(shouldSendChannels(false, KEEPALIVE - 1, KEEPALIVE)).toBe(false);
    });

    it('sends a change as soon as the wire ceiling allows, without waiting for the floor', () => {
      expect(shouldSendChannels(true, MIN_SEND_GAP_MS, KEEPALIVE)).toBe(true);
    });

    it('holds a change that arrives inside the ceiling — the next sample re-offers it', () => {
      expect(shouldSendChannels(true, MIN_SEND_GAP_MS - 1, KEEPALIVE)).toBe(false);
    });
  });
});
