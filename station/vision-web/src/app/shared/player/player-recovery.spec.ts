import { describe, expect, it } from 'vitest';
import {
  ICE_RESTART_GRACE_MS,
  INITIAL_PACING_STATE,
  INITIAL_RECOVERY_STATE,
  INITIAL_WHEP_ICE_STATE,
  SUSTAINED_PLAYBACK_RESET_MS,
  WHEP_RETRY_COOLDOWN_MS,
  advancePacing,
  attachKey,
  cyclePacingDelayMs,
  didWhepStatsAdvance,
  estimateWhepLatencySeconds,
  extractWhepStatsSnapshot,
  initialTransportState,
  isStalled,
  isWhepPrePlayMiss,
  isWhepStillWaitingForTrack,
  reconnectDelayMs,
  reduceRecovery,
  reduceTransportRecovery,
  reduceWhepIce,
  shouldAttemptWhep,
  shouldAttemptWhepUpgrade,
  type PacingState,
  type RecoveryState,
  type TransportRecoveryState,
  type WhepCandidatePairStatLike,
  type WhepIceState,
  type WhepInboundRtpStatLike,
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
    expect(reduceRecovery(connecting, 'playlistNotReady')).toEqual({
      phase: 'waiting',
      attempt: 1,
    });

    const waiting: RecoveryState = { phase: 'waiting', attempt: 1 };
    expect(reduceRecovery(waiting, 'playlistNotReady')).toEqual({ phase: 'waiting', attempt: 2 });
  });

  it('playlistNotReady after ever having played is a real reconnect attempt (e.g. a backend restart)', () => {
    const playing: RecoveryState = { phase: 'playing', attempt: 0 };
    expect(reduceRecovery(playing, 'playlistNotReady')).toEqual({
      phase: 'reconnecting',
      attempt: 1,
    });
  });

  it('playlistNotReady while already reconnecting keeps reconnecting and bumps the attempt', () => {
    const reconnecting: RecoveryState = { phase: 'reconnecting', attempt: 2 };
    expect(reduceRecovery(reconnecting, 'playlistNotReady')).toEqual({
      phase: 'reconnecting',
      attempt: 3,
    });
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
    expect(reduceRecovery({ phase: 'reconnecting', attempt: 7 }, 'reset')).toEqual(
      INITIAL_RECOVERY_STATE,
    );
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

  // --- `stopped` (docs/plans/done/MVP2-PLAN.md §S, S-b) ----------------------------------------------------

  it('stopped always wins, moving to the stopped phase with a reset attempt count, from any phase', () => {
    expect(reduceRecovery(INITIAL_RECOVERY_STATE, 'stopped')).toEqual({
      phase: 'stopped',
      attempt: 0,
    });
    expect(reduceRecovery({ phase: 'connecting', attempt: 0 }, 'stopped')).toEqual({
      phase: 'stopped',
      attempt: 0,
    });
    expect(reduceRecovery({ phase: 'playing', attempt: 0 }, 'stopped')).toEqual({
      phase: 'stopped',
      attempt: 0,
    });
    expect(reduceRecovery({ phase: 'reconnecting', attempt: 7 }, 'stopped')).toEqual({
      phase: 'stopped',
      attempt: 0,
    });
    expect(reduceRecovery({ phase: 'error', attempt: 0 }, 'stopped')).toEqual({
      phase: 'stopped',
      attempt: 0,
    });
  });

  it('stopped is idempotent — stopping an already-stopped state changes nothing', () => {
    expect(reduceRecovery({ phase: 'stopped', attempt: 0 }, 'stopped')).toEqual({
      phase: 'stopped',
      attempt: 0,
    });
  });

  it('stopped absorbs every recovery signal except a fresh attach — no event leaves it on its own', () => {
    const stopped: RecoveryState = { phase: 'stopped', attempt: 0 };
    for (const event of ['stalled', 'fatalError', 'firstSegment', 'unsupported'] as const) {
      expect(reduceRecovery(stopped, event)).toBe(stopped); // same reference — a genuine no-op
    }
    // `playlistNotReady`'s own connecting/waiting branch can never apply from `stopped` (phase
    // isn't connecting/waiting), so it also lands on the absorbing no-op, not a fresh `reconnecting`.
    expect(reduceRecovery(stopped, 'playlistNotReady')).toBe(stopped);
  });

  it('attachStarted is the fresh-attach escape hatch out of stopped', () => {
    const stopped: RecoveryState = { phase: 'stopped', attempt: 0 };
    expect(reduceRecovery(stopped, 'attachStarted')).toEqual({ phase: 'connecting', attempt: 0 });
  });

  it('reset is the other escape hatch out of stopped, landing on plain idle', () => {
    const stopped: RecoveryState = { phase: 'stopped', attempt: 0 };
    expect(reduceRecovery(stopped, 'reset')).toEqual(INITIAL_RECOVERY_STATE);
  });

  it('regression: the diagnosed freeze mechanism — a stray recovery signal after stop never re-attaches', () => {
    // Reproduces, in pure state-machine terms, what the live network trace behind this cycle's bug
    // report showed: after Stop, the still-tearing-down attachment (or a backend that briefly
    // re-lists the "stopped" stream) kept firing fatalError/stalled/firstSegment signals in a tight
    // cycle. Before `stopped` was absorbing, `firstSegment` in particular reset `attempt` to 0 on
    // every fleeting reconnection, so backoff never grew — a fast, steady repeat forever. Once
    // `stopped` has been dispatched, none of that reaches the player any more: every stray signal
    // is swallowed, `phase` never leaves `stopped`, and nothing schedules a reattach.
    let state: RecoveryState = { phase: 'reconnecting', attempt: 4 };
    state = reduceRecovery(state, 'stopped');
    expect(state).toEqual({ phase: 'stopped', attempt: 0 });

    const flapEvents = [
      'fatalError',
      'firstSegment',
      'stalled',
      'playlistNotReady',
      'unsupported',
    ] as const;
    for (let i = 0; i < 50; i++) {
      state = reduceRecovery(state, flapEvents[i % flapEvents.length]);
      expect(state.phase).toBe('stopped');
      expect(state.attempt).toBe(0);
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
    expect(initialTransportState(true)).toEqual({
      transport: 'webrtc',
      recovery: INITIAL_RECOVERY_STATE,
    });
  });

  it('starts straight on hls when there is no WHEP URL to try', () => {
    expect(initialTransportState(false)).toEqual({
      transport: 'hls',
      recovery: INITIAL_RECOVERY_STATE,
    });
  });
});

describe('reduceTransportRecovery — WHEP-first, HLS-fallback (docs/plans/done/MVP2-PLAN.md §L / §U3)', () => {
  it('attachStarted on a fresh webrtc state moves to connecting, transport unchanged', () => {
    const state = initialTransportState(true);
    expect(reduceTransportRecovery(state, 'attachStarted')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'connecting', attempt: 0 },
    });
  });

  it('a webrtc fatalError before ever playing falls back to hls, restarting recovery at connecting', () => {
    const connecting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'connecting', attempt: 0 },
    };
    expect(reduceTransportRecovery(connecting, 'fatalError')).toEqual({
      transport: 'hls',
      recovery: { phase: 'connecting', attempt: 0 },
    });
  });

  it('a webrtc stall before ever playing (no track within a timeout) also falls back to hls', () => {
    const waiting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'waiting', attempt: 2 },
    };
    expect(reduceTransportRecovery(waiting, 'stalled')).toEqual({
      transport: 'hls',
      recovery: { phase: 'connecting', attempt: 0 },
    });
  });

  it('a webrtc-unsupported browser falls back to hls too, rather than reaching a dead error state', () => {
    const connecting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'connecting', attempt: 0 },
    };
    expect(reduceTransportRecovery(connecting, 'unsupported')).toEqual({
      transport: 'hls',
      recovery: { phase: 'connecting', attempt: 0 },
    });
  });

  it('fix/stream-start-latency: playlistNotReady on webrtc before ever playing stays webrtc — no permanent downgrade for a WHEP path-not-ready cold start', () => {
    const connecting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'connecting', attempt: 0 },
    };
    // `'playlistNotReady'` is deliberately absent from `isWhepFailure` — this is the whole fix:
    // `player.ts#beginWhepAttach` dispatches this event (not `'fatalError'`) for a pre-play WHEP POST
    // 404/non-2xx (`isWhepPrePlayMiss`), so the cold-start retry cadence applies instead of an
    // immediate, permanent fallback to HLS.
    expect(reduceTransportRecovery(connecting, 'playlistNotReady')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'waiting', attempt: 1 },
    });

    const waiting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'waiting', attempt: 1 },
    };
    expect(reduceTransportRecovery(waiting, 'playlistNotReady')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'waiting', attempt: 2 },
    });
  });

  it('firstSegment on webrtc moves to playing, transport stays webrtc', () => {
    const connecting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'connecting', attempt: 0 },
    };
    expect(reduceTransportRecovery(connecting, 'firstSegment')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'playing', attempt: 0 },
    });
  });

  it('a webrtc failure AFTER reaching playing retries webrtc itself — reconnect cycles per transport', () => {
    const playing: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'playing', attempt: 0 },
    };
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
    const hlsState: TransportRecoveryState = {
      transport: 'hls',
      recovery: { phase: 'connecting', attempt: 0 },
    };
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
    const hlsState: TransportRecoveryState = {
      transport: 'hls',
      recovery: { phase: 'connecting', attempt: 0 },
    };
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

  // --- `stopped` (docs/plans/done/MVP2-PLAN.md §S, S-b) ----------------------------------------------------

  it('stopped wins regardless of transport/neverPlayedYet, leaving transport untouched', () => {
    const webrtcConnecting: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'connecting', attempt: 0 },
    };
    expect(reduceTransportRecovery(webrtcConnecting, 'stopped')).toEqual({
      transport: 'webrtc',
      recovery: { phase: 'stopped', attempt: 0 },
    });

    const hlsReconnecting: TransportRecoveryState = {
      transport: 'hls',
      recovery: { phase: 'reconnecting', attempt: 5 },
    };
    expect(reduceTransportRecovery(hlsReconnecting, 'stopped')).toEqual({
      transport: 'hls',
      recovery: { phase: 'stopped', attempt: 0 },
    });
  });

  it('once stopped, further webrtc-failure-shaped events do not trigger the hls-fallback rule', () => {
    const stopped: TransportRecoveryState = {
      transport: 'webrtc',
      recovery: { phase: 'stopped', attempt: 0 },
    };
    // `fatalError`/`stalled`/`unsupported` normally fall back to hls when `neverPlayedYet` — but
    // `stopped` isn't `connecting`/`waiting`, so `neverPlayedYet` is false and the absorbing
    // no-op in `reduceRecovery` applies instead: transport and phase both stay put.
    expect(reduceTransportRecovery(stopped, 'fatalError')).toEqual(stopped);
  });
});

// --- PacingState — cross-cycle reconnect pacing (docs/plans/done/MVP2-PLAN.md §S, S-c) ----------------------
//
// `RecoveryState`/`reduceRecovery` and `TransportRecoveryState`/`reduceTransportRecovery` are not
// touched by any test below — every one of the 349 lines above this comment still exercises exactly
// the same, unmodified functions/behavior. `PacingState`/`advancePacing` is a separate, additional
// pure state machine `shared/player/player.ts` layers on top — see its own doc comment in `player-recovery.ts`.

describe('advancePacing', () => {
  it('starts at rest', () => {
    expect(INITIAL_PACING_STATE).toEqual({
      cycleAttempt: 0,
      playingSinceMs: null,
      lastWhepAttemptAtMs: null,
    });
  });

  it('whepAttempted records the attempt time only, leaving the rest untouched', () => {
    expect(advancePacing(INITIAL_PACING_STATE, 'whepAttempted', 1_000)).toEqual({
      cycleAttempt: 0,
      playingSinceMs: null,
      lastWhepAttemptAtMs: 1_000,
    });
  });

  it('cycleFailed bumps cycleAttempt and clears any in-progress streak', () => {
    const streaking: PacingState = {
      cycleAttempt: 2,
      playingSinceMs: 5_000,
      lastWhepAttemptAtMs: null,
    };
    expect(advancePacing(streaking, 'cycleFailed', 6_000)).toEqual({
      cycleAttempt: 3,
      playingSinceMs: null,
      lastWhepAttemptAtMs: null,
    });
  });

  it("cycleFailed keeps escalating across repeated failures — no cross-cycle reset (closes S-b's flagged gap)", () => {
    let state = INITIAL_PACING_STATE;
    for (let i = 1; i <= 6; i++) {
      state = advancePacing(state, 'cycleFailed', i * 1_000);
      expect(state.cycleAttempt).toBe(i);
    }
  });

  it('playing starts tracking the streak only from the first event — a later re-fire (e.g. WHEP renegotiation) does not push the start time later', () => {
    let state = advancePacing(INITIAL_PACING_STATE, 'playing', 10_000);
    expect(state.playingSinceMs).toBe(10_000);
    state = advancePacing(state, 'playing', 10_500);
    expect(state.playingSinceMs).toBe(10_000);
  });

  it('healthTick is a no-op before the sustained-playback threshold elapses', () => {
    const state: PacingState = { cycleAttempt: 4, playingSinceMs: 0, lastWhepAttemptAtMs: 500 };
    expect(advancePacing(state, 'healthTick', SUSTAINED_PLAYBACK_RESET_MS - 1)).toEqual(state);
  });

  it('healthTick resets cycleAttempt and re-arms WHEP once the streak has been sustained', () => {
    const state: PacingState = { cycleAttempt: 4, playingSinceMs: 0, lastWhepAttemptAtMs: 500 };
    expect(advancePacing(state, 'healthTick', SUSTAINED_PLAYBACK_RESET_MS)).toEqual({
      cycleAttempt: 0,
      playingSinceMs: 0,
      lastWhepAttemptAtMs: null,
    });
  });

  it('healthTick is a genuine no-op (same reference) once already at rest — nothing left to reset', () => {
    const resting: PacingState = { cycleAttempt: 0, playingSinceMs: 0, lastWhepAttemptAtMs: null };
    expect(advancePacing(resting, 'healthTick', 1_000_000)).toBe(resting);
  });

  it('healthTick does nothing while not currently playing', () => {
    const state: PacingState = { cycleAttempt: 3, playingSinceMs: null, lastWhepAttemptAtMs: null };
    expect(advancePacing(state, 'healthTick', 1_000_000)).toBe(state);
  });
});

describe('cyclePacingDelayMs', () => {
  it("mirrors reconnectDelayMs's own capped-exponential schedule, keyed off cycleAttempt", () => {
    const at = (cycleAttempt: number): PacingState => ({
      cycleAttempt,
      playingSinceMs: null,
      lastWhepAttemptAtMs: null,
    });
    expect(cyclePacingDelayMs(at(0))).toBe(1_000);
    expect(cyclePacingDelayMs(at(1))).toBe(1_000);
    expect(cyclePacingDelayMs(at(2))).toBe(2_000);
    expect(cyclePacingDelayMs(at(5))).toBe(16_000);
    expect(cyclePacingDelayMs(at(6))).toBe(30_000);
    expect(cyclePacingDelayMs(at(40))).toBe(30_000);
  });
});

describe('shouldAttemptWhep', () => {
  it('is false outright when there is no WHEP URL to try', () => {
    expect(shouldAttemptWhep(INITIAL_PACING_STATE, false, 0)).toBe(false);
  });

  it("is true for this saga's first attempt (no prior attempt recorded)", () => {
    expect(shouldAttemptWhep(INITIAL_PACING_STATE, true, 0)).toBe(true);
  });

  it('is false again immediately after an attempt, before the cooldown elapses', () => {
    const state = advancePacing(INITIAL_PACING_STATE, 'whepAttempted', 0);
    expect(shouldAttemptWhep(state, true, WHEP_RETRY_COOLDOWN_MS - 1)).toBe(false);
  });

  it('is true again once the cooldown has fully elapsed', () => {
    const state = advancePacing(INITIAL_PACING_STATE, 'whepAttempted', 0);
    expect(shouldAttemptWhep(state, true, WHEP_RETRY_COOLDOWN_MS)).toBe(true);
  });
});

describe('isWhepPrePlayMiss', () => {
  it('is true for a 404 before ever playing — the common mediamtx "no publisher yet" shape', () => {
    expect(isWhepPrePlayMiss(404, true)).toBe(true);
  });

  it('is true for any other non-2xx before ever playing, not just 404', () => {
    expect(isWhepPrePlayMiss(500, true)).toBe(true);
    expect(isWhepPrePlayMiss(503, true)).toBe(true);
    expect(isWhepPrePlayMiss(400, true)).toBe(true);
  });

  it('is false for a 2xx (the caller never calls this for a success response, but it should not lie)', () => {
    expect(isWhepPrePlayMiss(200, true)).toBe(false);
    expect(isWhepPrePlayMiss(204, true)).toBe(false);
  });

  it('is false once this attach has already played, regardless of status — a genuine reconnect concern instead', () => {
    expect(isWhepPrePlayMiss(404, false)).toBe(false);
    expect(isWhepPrePlayMiss(500, false)).toBe(false);
  });
});

describe('shouldAttemptWhepUpgrade', () => {
  it('is true only while parked on a calmly playing hls session with a whepUrl configured', () => {
    expect(shouldAttemptWhepUpgrade('hls', 'playing', true)).toBe(true);
  });

  it('is false with no whepUrl configured — nothing to upgrade to', () => {
    expect(shouldAttemptWhepUpgrade('hls', 'playing', false)).toBe(false);
  });

  it('is false while already on webrtc — nothing to upgrade', () => {
    expect(shouldAttemptWhepUpgrade('webrtc', 'playing', true)).toBe(false);
  });

  it('is false while hls itself is mid-recovery — that belongs to beginNextCycle, not a background probe', () => {
    expect(shouldAttemptWhepUpgrade('hls', 'connecting', true)).toBe(false);
    expect(shouldAttemptWhepUpgrade('hls', 'waiting', true)).toBe(false);
    expect(shouldAttemptWhepUpgrade('hls', 'reconnecting', true)).toBe(false);
    expect(shouldAttemptWhepUpgrade('hls', 'error', true)).toBe(false);
    expect(shouldAttemptWhepUpgrade('hls', 'stopped', true)).toBe(false);
  });
});

// --- Scenarios, replaying the trace shapes the task's own live evidence and test list describe ---

describe('zombie stream — WHEP fails pre-play, HLS playlist never appears, cycling indefinitely', () => {
  it('escalates the shared cross-cycle delay to the 30s cap and damps WHEP to a few attempts a minute', () => {
    // One simulated "cycle" below mirrors shared/player/player.ts's own beginNextCycle/scheduleReconnect shape
    // exactly: a WHEP attempt (only if `shouldAttemptWhep` allows it) immediately followed by an
    // HLS attempt, both failing before ever reaching playing (a zombie stream: WHEP POST dies,
    // HLS's playlist never appears) — the cycle as a whole is what `cycleFailed` reports once.
    let pacing = INITIAL_PACING_STATE;
    let nowMs = 0;
    const delays: number[] = [];
    const whepAttempts: number[] = [];

    for (let cycle = 0; cycle < 10; cycle++) {
      if (shouldAttemptWhep(pacing, true, nowMs)) {
        pacing = advancePacing(pacing, 'whepAttempted', nowMs);
        whepAttempts.push(nowMs);
      }
      // ...WHEP fails pre-play and falls straight to HLS within the same cycle (no delay, no pacing
      // change — see `handleWhepFailure`'s own doc comment in shared/player/player.ts); HLS's own fetch then
      // also fails, which is what ends the cycle:
      pacing = advancePacing(pacing, 'cycleFailed', nowMs);
      const delay = cyclePacingDelayMs(pacing);
      delays.push(delay);
      nowMs += delay;
    }

    // Escalates 1s, 2s, 4s, 8s, 16s, then caps at 30s — never resets back down on its own.
    expect(delays.slice(0, 6)).toEqual([1_000, 2_000, 4_000, 8_000, 16_000, 30_000]);
    expect(delays.slice(6).every((d) => d === 30_000)).toBe(true);

    // WHEP was tried on the very first cycle, then damped — nowhere near "every single cycle".
    expect(whepAttempts.length).toBeLessThan(delays.length);
    expect(whepAttempts[0]).toBe(0);
    // Every attempt after the first is at least a full cooldown apart from the previous one.
    for (let i = 1; i < whepAttempts.length; i++) {
      expect(whepAttempts[i] - whepAttempts[i - 1]).toBeGreaterThanOrEqual(WHEP_RETRY_COOLDOWN_MS);
    }
    // Over the ~3 minutes this 10-cycle run covers (dominated by the 30s-capped tail), WHEP was
    // attempted only a couple of times — "a few requests per minute, not per second".
    expect(whepAttempts.length).toBeLessThanOrEqual(3);
  });
});

describe('flapping stream — fails, briefly plays under the sustained window, fails again', () => {
  it('never resets cycleAttempt back down to a fresh 1s delay', () => {
    let pacing = INITIAL_PACING_STATE;
    pacing = advancePacing(pacing, 'cycleFailed', 0); // cycleAttempt 1, delay 1s
    pacing = advancePacing(pacing, 'cycleFailed', 1_000); // cycleAttempt 2, delay 2s
    expect(cyclePacingDelayMs(pacing)).toBe(2_000);

    // A reconnect succeeds briefly — firstSegment/ontrack fires...
    pacing = advancePacing(pacing, 'playing', 3_000);
    // ...but a health tick well under the sustained window finds nothing to reset yet...
    pacing = advancePacing(pacing, 'healthTick', 3_000 + SUSTAINED_PLAYBACK_RESET_MS - 1_000);
    expect(pacing.cycleAttempt).toBe(2); // untouched — this is the flap S-b flagged and left open
    // ...and the connection drops again before ever reaching the sustained threshold.
    pacing = advancePacing(pacing, 'cycleFailed', 3_000 + SUSTAINED_PLAYBACK_RESET_MS - 500);

    // Backoff resumes escalating from where it left off — attempt 3, not reset back to 1.
    expect(pacing.cycleAttempt).toBe(3);
    expect(cyclePacingDelayMs(pacing)).toBe(4_000);
  });
});

describe('genuine recovery — sustained playback resets the saga', () => {
  it('resets cycleAttempt (and re-arms WHEP) only once playback has held for the sustained window', () => {
    let pacing = INITIAL_PACING_STATE;
    for (let i = 1; i <= 5; i++) {
      pacing = advancePacing(pacing, 'cycleFailed', i * 1_000);
    }
    expect(pacing.cycleAttempt).toBe(5);
    pacing = advancePacing(pacing, 'whepAttempted', 5_000); // a WHEP attempt was in flight when it recovered

    const recoveredAt = 6_000;
    pacing = advancePacing(pacing, 'playing', recoveredAt);
    // A health tick before the window elapses changes nothing.
    pacing = advancePacing(pacing, 'healthTick', recoveredAt + SUSTAINED_PLAYBACK_RESET_MS - 1);
    expect(pacing.cycleAttempt).toBe(5);
    expect(pacing.lastWhepAttemptAtMs).toBe(5_000);

    // Once sustained, both the backoff level and the WHEP cooldown reset.
    pacing = advancePacing(pacing, 'healthTick', recoveredAt + SUSTAINED_PLAYBACK_RESET_MS);
    expect(pacing).toEqual({
      cycleAttempt: 0,
      playingSinceMs: recoveredAt,
      lastWhepAttemptAtMs: null,
    });
    expect(cyclePacingDelayMs(pacing)).toBe(1_000); // a later failure starts fresh, as expected

    // A later reconnect is immediately allowed to try WHEP again — the cooldown was cleared.
    expect(shouldAttemptWhep(pacing, true, recoveredAt + SUSTAINED_PLAYBACK_RESET_MS)).toBe(true);
  });
});

// --- WHEP real stall detection via getStats() (docs/plans/done/REALTIME-PLAN.md Phase R-a item 1) ----------

describe('extractWhepStatsSnapshot', () => {
  const inboundVideo: WhepInboundRtpStatLike = {
    type: 'inbound-rtp',
    kind: 'video',
    framesDecoded: 42,
    bytesReceived: 12_345,
  };

  it('finds the inbound video RTP entry among other, irrelevant report entries', () => {
    const report: WhepInboundRtpStatLike[] = [
      { type: 'candidate-pair', kind: undefined },
      { type: 'inbound-rtp', kind: 'audio', framesDecoded: 999 }, // audio, not the one we want
      inboundVideo,
    ];
    expect(extractWhepStatsSnapshot(report)).toEqual({ framesDecoded: 42, bytesReceived: 12_345 });
  });

  it('defaults missing counters to 0 rather than undefined', () => {
    expect(extractWhepStatsSnapshot([{ type: 'inbound-rtp', kind: 'video' }])).toEqual({
      framesDecoded: 0,
      bytesReceived: 0,
    });
  });

  it('is null when no inbound video RTP entry exists yet', () => {
    expect(extractWhepStatsSnapshot([{ type: 'candidate-pair' }])).toBeNull();
    expect(extractWhepStatsSnapshot([])).toBeNull();
  });

  // --- Latency badge extension (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4) — same report, same pass -------

  it('also reads jitter off the inbound video entry, when reported', () => {
    expect(
      extractWhepStatsSnapshot([{ ...inboundVideo, jitter: 0.012 }]),
    ).toEqual({ framesDecoded: 42, bytesReceived: 12_345, jitterSeconds: 0.012 });
  });

  it('also reads round-trip time off the active (succeeded, nominated) candidate-pair entry', () => {
    const pair: WhepCandidatePairStatLike = {
      type: 'candidate-pair',
      state: 'succeeded',
      nominated: true,
      currentRoundTripTime: 0.04,
    };
    expect(extractWhepStatsSnapshot([inboundVideo, pair])).toEqual({
      framesDecoded: 42,
      bytesReceived: 12_345,
      roundTripTimeSeconds: 0.04,
    });
  });

  it('ignores a candidate-pair entry that is not the active (succeeded + nominated) one', () => {
    const notNominated: WhepCandidatePairStatLike = {
      type: 'candidate-pair',
      state: 'succeeded',
      nominated: false,
      currentRoundTripTime: 0.9,
    };
    const notSucceeded: WhepCandidatePairStatLike = {
      type: 'candidate-pair',
      state: 'waiting',
      nominated: true,
      currentRoundTripTime: 0.9,
    };
    expect(extractWhepStatsSnapshot([inboundVideo, notNominated])?.roundTripTimeSeconds).toBeUndefined();
    expect(extractWhepStatsSnapshot([inboundVideo, notSucceeded])?.roundTripTimeSeconds).toBeUndefined();
  });

  it('order in the report does not matter — the candidate-pair can arrive before or after inbound-rtp', () => {
    const pair: WhepCandidatePairStatLike = {
      type: 'candidate-pair',
      state: 'succeeded',
      nominated: true,
      currentRoundTripTime: 0.06,
    };
    expect(extractWhepStatsSnapshot([pair, inboundVideo])).toEqual({
      framesDecoded: 42,
      bytesReceived: 12_345,
      roundTripTimeSeconds: 0.06,
    });
  });
});

describe('estimateWhepLatencySeconds', () => {
  it('is null with no snapshot at all', () => {
    expect(estimateWhepLatencySeconds(null)).toBeNull();
  });

  it('is null when the snapshot has no round-trip time yet, even with jitter known', () => {
    expect(
      estimateWhepLatencySeconds({ framesDecoded: 10, bytesReceived: 1_000, jitterSeconds: 0.02 }),
    ).toBeNull();
  });

  it('is half the round-trip time when jitter is unknown (treated as zero)', () => {
    expect(
      estimateWhepLatencySeconds({
        framesDecoded: 10,
        bytesReceived: 1_000,
        roundTripTimeSeconds: 0.08,
      }),
    ).toBeCloseTo(0.04);
  });

  it('adds jitter on top of half the round-trip time when both are known', () => {
    expect(
      estimateWhepLatencySeconds({
        framesDecoded: 10,
        bytesReceived: 1_000,
        roundTripTimeSeconds: 0.08,
        jitterSeconds: 0.01,
      }),
    ).toBeCloseTo(0.05);
  });

  it('a zero round-trip time (loopback) still counts as known, not unmeasured', () => {
    expect(
      estimateWhepLatencySeconds({ framesDecoded: 10, bytesReceived: 1_000, roundTripTimeSeconds: 0 }),
    ).toBe(0);
  });
});

describe('didWhepStatsAdvance', () => {
  it('is false with no current snapshot at all — nothing to point to', () => {
    expect(didWhepStatsAdvance(null, null)).toBe(false);
    expect(didWhepStatsAdvance({ framesDecoded: 5, bytesReceived: 500 }, null)).toBe(false);
  });

  it('the first snapshot ever (no previous) counts as progress only if it already shows life', () => {
    expect(didWhepStatsAdvance(null, { framesDecoded: 1, bytesReceived: 0 })).toBe(true);
    expect(didWhepStatsAdvance(null, { framesDecoded: 0, bytesReceived: 1 })).toBe(true);
    expect(didWhepStatsAdvance(null, { framesDecoded: 0, bytesReceived: 0 })).toBe(false);
  });

  it('is true once framesDecoded has moved since the previous tick', () => {
    const previous = { framesDecoded: 10, bytesReceived: 1_000 };
    const current = { framesDecoded: 11, bytesReceived: 1_000 };
    expect(didWhepStatsAdvance(previous, current)).toBe(true);
  });

  it('is true once bytesReceived has moved since the previous tick, even if frames have not', () => {
    const previous = { framesDecoded: 10, bytesReceived: 1_000 };
    const current = { framesDecoded: 10, bytesReceived: 1_500 };
    expect(didWhepStatsAdvance(previous, current)).toBe(true);
  });

  it('is false — a genuine stall — once neither counter has moved across a tick', () => {
    const snapshot = { framesDecoded: 10, bytesReceived: 1_000 };
    expect(didWhepStatsAdvance(snapshot, { ...snapshot })).toBe(false);
  });

  it('regression: a healthy stream never stalls — every 2s tick shows real progress', () => {
    // Reproduces this cycle's own exit criterion in pure terms: a stream that keeps decoding
    // frames every watchdog tick must never look stalled, no matter how many ticks accumulate.
    let previous: { framesDecoded: number; bytesReceived: number } | null = null;
    let lastFrameAt = 0;
    for (let tick = 1; tick <= 20; tick++) {
      const current = { framesDecoded: tick * 30, bytesReceived: tick * 4_000 };
      if (didWhepStatsAdvance(previous, current)) {
        lastFrameAt = tick * 2_000; // mirrors `shared/player/player.ts` refreshing `lastFrameAt` on advance
      }
      previous = current;
      expect(isStalled(lastFrameAt, tick * 2_000)).toBe(false);
    }
  });

  it('regression: a genuinely dead feed (counters frozen) still stalls after STALL_WATCHDOG_MS', () => {
    const frozen = { framesDecoded: 30, bytesReceived: 4_000 };
    let lastFrameAt = 0; // last real progress was at attach, t=0
    for (let tick = 1; tick <= 5; tick++) {
      const advanced = didWhepStatsAdvance(frozen, { ...frozen }); // counters never move again
      expect(advanced).toBe(false);
    }
    expect(isStalled(lastFrameAt, 8_000)).toBe(true);
  });
});

// --- Reattach identity key (docs/plans/done/REALTIME-PLAN.md Phase R-a item 4) ------------------------------

describe('attachKey', () => {
  it('is equal for the same primitive values, regardless of how many separate calls produce them', () => {
    // Simulates a secondary tile's `stream()`/`device()` computed re-deriving a brand-new object
    // every ~5s poll tick (or on array reorder) while the actual URLs/flags haven't moved.
    const first = attachKey('https://mediamtx/hls/a.m3u8', 'https://mediamtx/whep/a', false, false);
    const second = attachKey(
      'https://mediamtx/hls/a.m3u8',
      'https://mediamtx/whep/a',
      false,
      false,
    );
    expect(second).toBe(first);
  });

  it('changes when src changes', () => {
    expect(attachKey('a', 'w', false, false)).not.toBe(attachKey('b', 'w', false, false));
  });

  it('changes when whepUrl changes', () => {
    expect(attachKey('a', 'w1', false, false)).not.toBe(attachKey('a', 'w2', false, false));
  });

  it('changes when suspended changes', () => {
    expect(attachKey('a', 'w', false, false)).not.toBe(attachKey('a', 'w', true, false));
  });

  it('changes when stopped changes', () => {
    expect(attachKey('a', 'w', false, false)).not.toBe(attachKey('a', 'w', false, true));
  });

  it('is equal for two independent null-src/null-whepUrl calls (nothing to attach, both times)', () => {
    expect(attachKey(null, null, false, false)).toBe(attachKey(null, null, false, false));
  });
});

// --- WHEP ICE-restart-first recovery (docs/plans/done/REALTIME-PLAN.md Phase R-b item 1) -------------------

describe('reduceWhepIce', () => {
  it('starts stable', () => {
    expect(INITIAL_WHEP_ICE_STATE).toEqual({ phase: 'stable', restartAttempt: 0 });
  });

  it('disconnected from stable starts the grace phase, attempt count untouched', () => {
    expect(reduceWhepIce(INITIAL_WHEP_ICE_STATE, 'disconnected')).toEqual({
      phase: 'grace',
      restartAttempt: 0,
    });
  });

  it('disconnected is a no-op outside stable (grace/restarting already in progress)', () => {
    const grace: WhepIceState = { phase: 'grace', restartAttempt: 0 };
    expect(reduceWhepIce(grace, 'disconnected')).toBe(grace);
    const restarting: WhepIceState = { phase: 'restarting', restartAttempt: 1 };
    expect(reduceWhepIce(restarting, 'disconnected')).toBe(restarting);
  });

  it('reconnected from grace self-heals back to stable — no restart was ever attempted', () => {
    const grace: WhepIceState = { phase: 'grace', restartAttempt: 0 };
    expect(reduceWhepIce(grace, 'reconnected')).toEqual({ phase: 'stable', restartAttempt: 0 });
  });

  it('reconnected is a no-op outside grace (stable has nothing to heal from; restarting resolves via restartSucceeded instead)', () => {
    expect(reduceWhepIce(INITIAL_WHEP_ICE_STATE, 'reconnected')).toBe(INITIAL_WHEP_ICE_STATE);
    const restarting: WhepIceState = { phase: 'restarting', restartAttempt: 2 };
    expect(reduceWhepIce(restarting, 'reconnected')).toBe(restarting);
  });

  it('graceExpired moves grace to restarting and bumps the attempt count', () => {
    const grace: WhepIceState = { phase: 'grace', restartAttempt: 0 };
    expect(reduceWhepIce(grace, 'graceExpired')).toEqual({
      phase: 'restarting',
      restartAttempt: 1,
    });
  });

  it('graceExpired is a no-op outside grace', () => {
    expect(reduceWhepIce(INITIAL_WHEP_ICE_STATE, 'graceExpired')).toBe(INITIAL_WHEP_ICE_STATE);
  });

  it('failed restarts immediately from stable — no grace wait for an outright failure', () => {
    expect(reduceWhepIce(INITIAL_WHEP_ICE_STATE, 'failed')).toEqual({
      phase: 'restarting',
      restartAttempt: 1,
    });
  });

  it('failed restarts immediately from grace too, skipping the rest of the grace wait', () => {
    const grace: WhepIceState = { phase: 'grace', restartAttempt: 0 };
    expect(reduceWhepIce(grace, 'failed')).toEqual({ phase: 'restarting', restartAttempt: 1 });
  });

  it('failed while already restarting bumps the attempt again — a second, more urgent failure supersedes the in-flight one', () => {
    const restarting: WhepIceState = { phase: 'restarting', restartAttempt: 1 };
    expect(reduceWhepIce(restarting, 'failed')).toEqual({ phase: 'restarting', restartAttempt: 2 });
  });

  it('restartSucceeded moves restarting back to stable, keeping the attempt count as a historical tally', () => {
    const restarting: WhepIceState = { phase: 'restarting', restartAttempt: 3 };
    expect(reduceWhepIce(restarting, 'restartSucceeded')).toEqual({
      phase: 'stable',
      restartAttempt: 3,
    });
  });

  it('restartSucceeded is a no-op outside restarting', () => {
    expect(reduceWhepIce(INITIAL_WHEP_ICE_STATE, 'restartSucceeded')).toBe(INITIAL_WHEP_ICE_STATE);
    const grace: WhepIceState = { phase: 'grace', restartAttempt: 0 };
    expect(reduceWhepIce(grace, 'restartSucceeded')).toBe(grace);
  });

  it('reset always returns to the initial stable state, from any phase', () => {
    expect(reduceWhepIce({ phase: 'grace', restartAttempt: 4 }, 'reset')).toEqual(
      INITIAL_WHEP_ICE_STATE,
    );
    expect(reduceWhepIce({ phase: 'restarting', restartAttempt: 9 }, 'reset')).toEqual(
      INITIAL_WHEP_ICE_STATE,
    );
  });

  it('a full disconnected -> graceExpired -> restartSucceeded cycle', () => {
    let state = INITIAL_WHEP_ICE_STATE;
    state = reduceWhepIce(state, 'disconnected');
    expect(state.phase).toBe('grace');
    state = reduceWhepIce(state, 'graceExpired');
    expect(state).toEqual({ phase: 'restarting', restartAttempt: 1 });
    state = reduceWhepIce(state, 'restartSucceeded');
    expect(state).toEqual({ phase: 'stable', restartAttempt: 1 });
  });

  it('a disconnected -> reconnected cycle never reaches restarting at all — the self-heal case', () => {
    let state = INITIAL_WHEP_ICE_STATE;
    state = reduceWhepIce(state, 'disconnected');
    state = reduceWhepIce(state, 'reconnected');
    expect(state).toEqual({ phase: 'stable', restartAttempt: 0 });
  });

  it('repeated failures keep escalating the attempt tally, never resetting on their own', () => {
    let state = INITIAL_WHEP_ICE_STATE;
    for (let i = 1; i <= 5; i++) {
      state = reduceWhepIce(state, 'failed');
      expect(state).toEqual({ phase: 'restarting', restartAttempt: i });
      state = reduceWhepIce(state, 'restartSucceeded');
      expect(state).toEqual({ phase: 'stable', restartAttempt: i });
    }
  });
});

describe('ICE_RESTART_GRACE_MS', () => {
  it('is 5 seconds, per docs/plans/done/REALTIME-PLAN.md Phase R-b item 1', () => {
    expect(ICE_RESTART_GRACE_MS).toBe(5_000);
  });
});

// --- WHEP no-track timeout race (docs/plans/done/REALTIME-PLAN.md Phase R-b follow-up — a live soak test's
// own finding: 89 POSTs/88 DELETEs/0 PATCHes over a 10.5-minute *healthy* soak, teardown every
// ~7.15s, confirmed at ~90 reproductions with near-zero jitter) ---------------------------------

describe('isWhepStillWaitingForTrack', () => {
  it('is true before the track has arrived — a fresh attach should arm its no-track timeout', () => {
    expect(isWhepStillWaitingForTrack(false)).toBe(true);
  });

  it('is false once the track has arrived — nothing left to time out', () => {
    expect(isWhepStillWaitingForTrack(true)).toBe(false);
  });
});

describe('the ontrack-before-continuation race, simulated in pure terms', () => {
  /**
   * Models `shared/player/player.ts`'s own `whepTrackArrived` flag plus the two call sites that check it
   * (`scheduleWhepNoTrackTimeout`'s arm guard, and its fired timer's own re-check) without any
   * `RTCPeerConnection`/timer at all — just the boolean and the two decisions made against it, in
   * whichever order the surrounding events actually happen to arrive in.
   */
  function armDecision(trackArrived: boolean): 'armed' | 'not-armed' {
    return isWhepStillWaitingForTrack(trackArrived) ? 'armed' : 'not-armed';
  }
  function fireDecision(trackArrived: boolean): 'treated-as-failure' | 'moot' {
    return isWhepStillWaitingForTrack(trackArrived) ? 'treated-as-failure' : 'moot';
  }

  it('the exact bug reproduced live: ontrack fires before the scheduling call site ever runs — the timeout must never be armed', () => {
    // Confirmed live ordering: `pc.ontrack` (sets the flag) resolves before the `await
    // setRemoteDescription(...)` continuation that used to call `scheduleWhepNoTrackTimeout`
    // *afterward* even resumes. By the time that call site is reached, the track has already
    // arrived.
    let trackArrived = false;
    trackArrived = true; // ontrack fires first
    expect(armDecision(trackArrived)).toBe('not-armed'); // the scheduling call site, reached second, must skip arming
  });

  it('the normal ordering — scheduled while still waiting, track arrives after — still arms correctly and the later arrival is handled by clearWhepNoTrackTimer, not this predicate', () => {
    const trackArrived = false;
    expect(armDecision(trackArrived)).toBe('armed');
  });

  it('a timer that fires after the track arrived in the gap between arming and firing must be a no-op', () => {
    // e.g. armed while still waiting, then ontrack fires a moment later but *before* the 6s timer
    // itself elapses — `clearWhepNoTrackTimer()` normally cancels it outright, but this is the
    // defense-in-depth re-check for the fired callback regardless.
    const trackArrivedByFireTime = true;
    expect(fireDecision(trackArrivedByFireTime)).toBe('moot');
  });

  it('a timer firing with no track ever having arrived is a genuine, correctly-reported failure', () => {
    const trackArrivedByFireTime = false;
    expect(fireDecision(trackArrivedByFireTime)).toBe('treated-as-failure');
  });
});

describe('stopped stays unaffected by pacing history', () => {
  it('reduceTransportRecovery still absorbs into stopped exactly per S-b, regardless of how escalated pacing is — pacing and the recovery reducer are independent', () => {
    // `PacingState` has no `stopped`-shaped event at all — `shared/player/player.ts` resets it directly on a
    // fresh, wanted attach (mirroring `transportState`'s own `initialTransportState` reset), the
    // same way it always has. This is a defense-in-depth check that S-b's own absorbing-stopped
    // contract in the *recovery* reducer is untouched by anything added in this cycle.
    let state: TransportRecoveryState = {
      transport: 'hls',
      recovery: { phase: 'reconnecting', attempt: 9 },
    };
    state = reduceTransportRecovery(state, 'stopped');
    expect(state).toEqual({ transport: 'hls', recovery: { phase: 'stopped', attempt: 0 } });
  });
});
