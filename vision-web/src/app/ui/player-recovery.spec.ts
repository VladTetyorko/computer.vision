import { describe, expect, it } from 'vitest';
import {
  INITIAL_RECOVERY_STATE,
  initialTransportState,
  isStalled,
  reconnectDelayMs,
  reduceRecovery,
  reduceTransportRecovery,
  type RecoveryState,
  type TransportRecoveryState,
} from './player-recovery';

describe('reduceRecovery', () => {
  it('starts idle', () => {
    expect(INITIAL_RECOVERY_STATE).toEqual({ phase: 'idle', attempt: 0 });
  });

  it('attachStarted moves idle to connecting with a reset attempt count', () => {
    expect(reduceRecovery(INITIAL_RECOVERY_STATE, 'attachStarted')).toEqual({
      phase: 'connecting',
      attempt: 0,
    });
  });

  it('attachStarted is a no-op once already past idle (a retry firing mid-flow does not reset the chip)', () => {
    const reconnecting: RecoveryState = { phase: 'reconnecting', attempt: 3 };
    expect(reduceRecovery(reconnecting, 'attachStarted')).toEqual(reconnecting);
  });

  it('playlistNotReady from connecting/waiting stays a gentle waiting — no error flash on a fresh stream', () => {
    const connecting: RecoveryState = { phase: 'connecting', attempt: 0 };
    expect(reduceRecovery(connecting, 'playlistNotReady')).toEqual({ phase: 'waiting', attempt: 1 });

    const waiting: RecoveryState = { phase: 'waiting', attempt: 1 };
    expect(reduceRecovery(waiting, 'playlistNotReady')).toEqual({ phase: 'waiting', attempt: 2 });
  });

  it('playlistNotReady after ever having played is a real reconnect attempt (e.g. a backend restart)', () => {
    const playing: RecoveryState = { phase: 'playing', attempt: 0 };
    expect(reduceRecovery(playing, 'playlistNotReady')).toEqual({ phase: 'reconnecting', attempt: 1 });
  });

  it('playlistNotReady while already reconnecting keeps reconnecting and bumps the attempt', () => {
    const reconnecting: RecoveryState = { phase: 'reconnecting', attempt: 2 };
    expect(reduceRecovery(reconnecting, 'playlistNotReady')).toEqual({ phase: 'reconnecting', attempt: 3 });
  });

  it('firstSegment moves to playing and resets the attempt count from any phase', () => {
    expect(reduceRecovery({ phase: 'waiting', attempt: 5 }, 'firstSegment')).toEqual({
      phase: 'playing',
      attempt: 0,
    });
    expect(reduceRecovery({ phase: 'reconnecting', attempt: 9 }, 'firstSegment')).toEqual({
      phase: 'playing',
      attempt: 0,
    });
  });

  it('stalled moves to reconnecting and bumps the attempt, even from playing', () => {
    expect(reduceRecovery({ phase: 'playing', attempt: 0 }, 'stalled')).toEqual({
      phase: 'reconnecting',
      attempt: 1,
    });
  });

  it('fatalError moves to reconnecting and bumps the attempt', () => {
    expect(reduceRecovery({ phase: 'connecting', attempt: 0 }, 'fatalError')).toEqual({
      phase: 'reconnecting',
      attempt: 1,
    });
  });

  it('unsupported is terminal — the one phase reset() cannot walk back from except a fresh src', () => {
    expect(reduceRecovery({ phase: 'connecting', attempt: 0 }, 'unsupported')).toEqual({
      phase: 'error',
      attempt: 0,
    });
  });

  it('reset always returns to the initial idle state, from any phase', () => {
    expect(reduceRecovery({ phase: 'reconnecting', attempt: 7 }, 'reset')).toEqual(INITIAL_RECOVERY_STATE);
    expect(reduceRecovery({ phase: 'error', attempt: 0 }, 'reset')).toEqual(INITIAL_RECOVERY_STATE);
  });

  it('never reaches error from a fatal error or a stall — reconnecting is never a dead end', () => {
    let state = INITIAL_RECOVERY_STATE;
    state = reduceRecovery(state, 'attachStarted');
    state = reduceRecovery(state, 'firstSegment');
    for (let i = 0; i < 50; i++) {
      state = reduceRecovery(state, i % 2 === 0 ? 'fatalError' : 'stalled');
      expect(state.phase).toBe('reconnecting');
    }
  });
});

describe('reconnectDelayMs', () => {
  it('is capped exponential starting at 1s', () => {
    expect(reconnectDelayMs(1)).toBe(1_000);
    expect(reconnectDelayMs(2)).toBe(2_000);
    expect(reconnectDelayMs(3)).toBe(4_000);
    expect(reconnectDelayMs(4)).toBe(8_000);
    expect(reconnectDelayMs(5)).toBe(16_000);
  });

  it('caps at 30s and stays there indefinitely', () => {
    expect(reconnectDelayMs(6)).toBe(30_000);
    expect(reconnectDelayMs(20)).toBe(30_000);
    expect(reconnectDelayMs(1_000)).toBe(30_000);
  });

  it('treats a non-positive attempt as the first attempt', () => {
    expect(reconnectDelayMs(0)).toBe(1_000);
    expect(reconnectDelayMs(-1)).toBe(1_000);
  });
});

describe('isStalled', () => {
  it('is false before the threshold has elapsed', () => {
    expect(isStalled(1_000, 1_000 + 7_999, 8_000)).toBe(false);
  });

  it('is true once the threshold has elapsed', () => {
    expect(isStalled(1_000, 1_000 + 8_000, 8_000)).toBe(true);
    expect(isStalled(1_000, 1_000 + 20_000, 8_000)).toBe(true);
  });

  it('defaults its threshold to STALL_WATCHDOG_MS (8s)', () => {
    expect(isStalled(0, 7_999)).toBe(false);
    expect(isStalled(0, 8_000)).toBe(true);
  });
});

describe('initialTransportState', () => {
  it('starts on webrtc when a WHEP URL exists', () => {
    expect(initialTransportState(true)).toEqual({ transport: 'webrtc', recovery: INITIAL_RECOVERY_STATE });
  });

  it('starts straight on hls when there is no WHEP URL to try', () => {
    expect(initialTransportState(false)).toEqual({ transport: 'hls', recovery: INITIAL_RECOVERY_STATE });
  });
});

describe('reduceTransportRecovery — WHEP-first, HLS-fallback (docs/MVP2-PLAN.md §L / §U3)', () => {
  it('attachStarted on a fresh webrtc state moves to connecting, transport unchanged', () => {
    const state = initialTransportState(true);
    expect(reduceTransportRecovery(state, 'attachStarted')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'connecting', attempt: 0 },
    });
  });

  it('a webrtc fatalError before ever playing falls back to hls, restarting recovery at connecting', () => {
    const connecting: TransportRecoveryState = { transport: 'webrtc', recovery: { phase: 'connecting', attempt: 0 } };
    expect(reduceTransportRecovery(connecting, 'fatalError')).toEqual({
      transport: 'hls',
      recovery: { phase: 'connecting', attempt: 0 },
    });
  });

  it('a webrtc stall before ever playing (no track within a timeout) also falls back to hls', () => {
    const waiting: TransportRecoveryState = { transport: 'webrtc', recovery: { phase: 'waiting', attempt: 2 } };
    expect(reduceTransportRecovery(waiting, 'stalled')).toEqual({
      transport: 'hls',
      recovery: { phase: 'connecting', attempt: 0 },
    });
  });

  it('a webrtc-unsupported browser falls back to hls too, rather than reaching a dead error state', () => {
    const connecting: TransportRecoveryState = { transport: 'webrtc', recovery: { phase: 'connecting', attempt: 0 } };
    expect(reduceTransportRecovery(connecting, 'unsupported')).toEqual({
      transport: 'hls',
      recovery: { phase: 'connecting', attempt: 0 },
    });
  });

  it('firstSegment on webrtc moves to playing, transport stays webrtc', () => {
    const connecting: TransportRecoveryState = { transport: 'webrtc', recovery: { phase: 'connecting', attempt: 0 } };
    expect(reduceTransportRecovery(connecting, 'firstSegment')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'playing', attempt: 0 },
    });
  });

  it('a webrtc failure AFTER reaching playing retries webrtc itself — reconnect cycles per transport', () => {
    const playing: TransportRecoveryState = { transport: 'webrtc', recovery: { phase: 'playing', attempt: 0 } };
    expect(reduceTransportRecovery(playing, 'stalled')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'reconnecting', attempt: 1 },
    });
  });

  it('a further webrtc failure while already reconnecting (played before) keeps retrying webrtc, not a late fallback', () => {
    const reconnecting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'reconnecting', attempt: 3 },
    };
    expect(reduceTransportRecovery(reconnecting, 'fatalError')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'reconnecting', attempt: 4 },
    });
  });

  it('once on hls (from the start, or via fallback), behaves exactly like plain reduceRecovery', () => {
    const hlsState: TransportRecoveryState = { transport: 'hls', recovery: { phase: 'connecting', attempt: 0 } };
    expect(reduceTransportRecovery(hlsState, 'playlistNotReady')).toEqual({
      transport: 'hls',
      recovery: reduceRecovery(hlsState.recovery, 'playlistNotReady'),
    });
    const hlsReconnecting: TransportRecoveryState = {
      transport: 'hls',
      recovery: { phase: 'playing', attempt: 0 },
    };
    expect(reduceTransportRecovery(hlsReconnecting, 'fatalError')).toEqual({
      transport: 'hls',
      recovery: { phase: 'reconnecting', attempt: 1 },
    });
  });

  it('an hls-unsupported browser (no fallback left) reaches the terminal error phase, transport unchanged', () => {
    const hlsState: TransportRecoveryState = { transport: 'hls', recovery: { phase: 'connecting', attempt: 0 } };
    expect(reduceTransportRecovery(hlsState, 'unsupported')).toEqual({
      transport: 'hls',
      recovery: { phase: 'error', attempt: 0 },
    });
  });

  it('reset keeps the current transport (the next fresh attach re-derives it via initialTransportState)', () => {
    const reconnecting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'reconnecting', attempt: 5 },
    };
    expect(reduceTransportRecovery(reconnecting, 'reset')).toEqual({
      transport: 'webrtc',
      recovery: INITIAL_RECOVERY_STATE,
    });
  });

  it('a full WHEP-attempt → fallback → HLS-plays → HLS-reconnects cycle', () => {
    let state = initialTransportState(true);
    state = reduceTransportRecovery(state, 'attachStarted'); // webrtc/connecting
    expect(state.transport).toBe('webrtc');

    state = reduceTransportRecovery(state, 'fatalError'); // WHEP POST/ICE failure, never played
    expect(state).toEqual({ transport: 'hls', recovery: { phase: 'connecting', attempt: 0 } });

    state = reduceTransportRecovery(state, 'firstSegment'); // HLS now playing
    expect(state).toEqual({ transport: 'hls', recovery: { phase: 'playing', attempt: 0 } });

    state = reduceTransportRecovery(state, 'stalled'); // a later HLS stall reconnects HLS itself
    expect(state).toEqual({ transport: 'hls', recovery: { phase: 'reconnecting', attempt: 1 } });
  });
});
