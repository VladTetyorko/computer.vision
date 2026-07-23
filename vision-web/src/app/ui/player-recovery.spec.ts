import { describe, expect, it } from 'vitest';
import {
  INITIAL_PACING_STATE,
  INITIAL_RECOVERY_STATE,
  SUSTAINED_PLAYBACK_RESET_MS,
  WHEP_RETRY_COOLDOWN_MS,
  advancePacing,
  cyclePacingDelayMs,
  initialTransportState,
  isStalled,
  reconnectDelayMs,
  reduceRecovery,
  reduceTransportRecovery,
  shouldAttemptWhep,
  type PacingState,
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

  // --- `stopped` (docs/MVP2-PLAN.md §S, S-b) ----------------------------------------------------

  it('stopped always wins, moving to the stopped phase with a reset attempt count, from any phase', () => {
    expect(reduceRecovery(INITIAL_RECOVERY_STATE, 'stopped')).toEqual({ phase: 'stopped', attempt: 0 });
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
    expect(reduceRecovery({ phase: 'error', attempt: 0 }, 'stopped')).toEqual({ phase: 'stopped', attempt: 0 });
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

    const flapEvents = ['fatalError', 'firstSegment', 'stalled', 'playlistNotReady', 'unsupported'] as const;
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

  // --- `stopped` (docs/MVP2-PLAN.md §S, S-b) ----------------------------------------------------

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
    const stopped: TransportRecoveryState = { transport: 'webrtc', recovery: { phase: 'stopped', attempt: 0 } };
    // `fatalError`/`stalled`/`unsupported` normally fall back to hls when `neverPlayedYet` — but
    // `stopped` isn't `connecting`/`waiting`, so `neverPlayedYet` is false and the absorbing
    // no-op in `reduceRecovery` applies instead: transport and phase both stay put.
    expect(reduceTransportRecovery(stopped, 'fatalError')).toEqual(stopped);
  });
});

// --- PacingState — cross-cycle reconnect pacing (docs/MVP2-PLAN.md §S, S-c) ----------------------
//
// `RecoveryState`/`reduceRecovery` and `TransportRecoveryState`/`reduceTransportRecovery` are not
// touched by any test below — every one of the 349 lines above this comment still exercises exactly
// the same, unmodified functions/behavior. `PacingState`/`advancePacing` is a separate, additional
// pure state machine `ui/player.ts` layers on top — see its own doc comment in `player-recovery.ts`.

describe('advancePacing', () => {
  it('starts at rest', () => {
    expect(INITIAL_PACING_STATE).toEqual({ cycleAttempt: 0, playingSinceMs: null, lastWhepAttemptAtMs: null });
  });

  it('whepAttempted records the attempt time only, leaving the rest untouched', () => {
    expect(advancePacing(INITIAL_PACING_STATE, 'whepAttempted', 1_000)).toEqual({
      cycleAttempt: 0,
      playingSinceMs: null,
      lastWhepAttemptAtMs: 1_000,
    });
  });

  it('cycleFailed bumps cycleAttempt and clears any in-progress streak', () => {
    const streaking: PacingState = { cycleAttempt: 2, playingSinceMs: 5_000, lastWhepAttemptAtMs: null };
    expect(advancePacing(streaking, 'cycleFailed', 6_000)).toEqual({
      cycleAttempt: 3,
      playingSinceMs: null,
      lastWhepAttemptAtMs: null,
    });
  });

  it('cycleFailed keeps escalating across repeated failures — no cross-cycle reset (closes S-b\'s flagged gap)', () => {
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
    const at = (cycleAttempt: number): PacingState => ({ cycleAttempt, playingSinceMs: null, lastWhepAttemptAtMs: null });
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

// --- Scenarios, replaying the trace shapes the task's own live evidence and test list describe ---

describe('zombie stream — WHEP fails pre-play, HLS playlist never appears, cycling indefinitely', () => {
  it('escalates the shared cross-cycle delay to the 30s cap and damps WHEP to a few attempts a minute', () => {
    // One simulated "cycle" below mirrors ui/player.ts's own beginNextCycle/scheduleReconnect shape
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
      // change — see `handleWhepFailure`'s own doc comment in ui/player.ts); HLS's own fetch then
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
    expect(pacing).toEqual({ cycleAttempt: 0, playingSinceMs: recoveredAt, lastWhepAttemptAtMs: null });
    expect(cyclePacingDelayMs(pacing)).toBe(1_000); // a later failure starts fresh, as expected

    // A later reconnect is immediately allowed to try WHEP again — the cooldown was cleared.
    expect(shouldAttemptWhep(pacing, true, recoveredAt + SUSTAINED_PLAYBACK_RESET_MS)).toBe(true);
  });
});

describe('stopped stays unaffected by pacing history', () => {
  it('reduceTransportRecovery still absorbs into stopped exactly per S-b, regardless of how escalated pacing is — pacing and the recovery reducer are independent', () => {
    // `PacingState` has no `stopped`-shaped event at all — `ui/player.ts` resets it directly on a
    // fresh, wanted attach (mirroring `transportState`'s own `initialTransportState` reset), the
    // same way it always has. This is a defense-in-depth check that S-b's own absorbing-stopped
    // contract in the *recovery* reducer is untouched by anything added in this cycle.
    let state: TransportRecoveryState = { transport: 'hls', recovery: { phase: 'reconnecting', attempt: 9 } };
    state = reduceTransportRecovery(state, 'stopped');
    expect(state).toEqual({ transport: 'hls', recovery: { phase: 'stopped', attempt: 0 } });
  });
});
