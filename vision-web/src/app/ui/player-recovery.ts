/**
 * Pure state machine behind `ui/player.ts`'s self-recovery (docs/CYCLES-PLAN.md §11, CD-b item 5).
 *
 * Split out of the component so the recovery logic — which phase follows which event, and how
 * long to wait before the next reconnect attempt — is unit-testable without hls.js, timers, or a
 * `<video>` element, mirroring `core/telemetry-logic.ts`'s split of pure derivation from the
 * component/service that drives it.
 */

/**
 * The player's visible lifecycle. `idle`/`connecting`/`waiting`/`playing`/`error` existed before
 * this cycle; `reconnecting` is new — the one state that must never be a dead end: once a stream
 * has been reached at all, a fatal error or a silent stall moves here, never to `error`, and stays
 * here (retrying with backoff) for as long as the page has a `src` to reattach.
 *
 * `error` is now reserved for a genuinely unrecoverable condition — this browser cannot play HLS
 * at all — not for a transient network hiccup.
 */
export type PlayerPhase = 'idle' | 'connecting' | 'waiting' | 'playing' | 'reconnecting' | 'error';

export interface RecoveryState {
  readonly phase: PlayerPhase;
  /** Reconnect attempts since the last time `phase` was `playing` (or since the start). */
  readonly attempt: number;
}

export const INITIAL_RECOVERY_STATE: RecoveryState = { phase: 'idle', attempt: 0 };

export type RecoveryEvent =
  /** `src` became null, or the tile was suspended (scrolled off-screen) — back to a clean slate. */
  | 'reset'
  /** Starting a fresh attach cycle: the very first attempt, or a scheduled retry firing. */
  | 'attachStarted'
  /** The playlist isn't muxed yet (a fresh stream's normal cold-start 404) or vanished again. */
  | 'playlistNotReady'
  /** Genuine playback progress — a fragment buffered, or the native path fired `playing`. */
  | 'firstSegment'
  /** The stall watchdog found no fragment progress for the threshold window. */
  | 'stalled'
  /** An hls.js/native fatal error that isn't the cold-start-404 pattern above. */
  | 'fatalError'
  /** This browser can play neither native HLS nor hls.js — nothing to retry. */
  | 'unsupported';

/**
 * Advances the recovery state machine by one event.
 *
 * The one branch worth reading closely is `playlistNotReady`: before the stream has ever played
 * (`connecting`/`waiting`), a missing playlist is the ordinary, expected shape of a fresh stream —
 * it stays a gentle `waiting` (docs/CYCLES-PLAN.md §11 item 5: "no error flash on fresh streams").
 * Once the stream has played before, or is already mid-recovery, the *same* event means the
 * backend actually went away (e.g. a restart) — it becomes a real `reconnecting` attempt instead.
 */
export function reduceRecovery(state: RecoveryState, event: RecoveryEvent): RecoveryState {
  switch (event) {
    case 'reset':
      return INITIAL_RECOVERY_STATE;
    case 'unsupported':
      return { phase: 'error', attempt: state.attempt };
    case 'attachStarted':
      return state.phase === 'idle' ? { phase: 'connecting', attempt: 0 } : state;
    case 'firstSegment':
      return { phase: 'playing', attempt: 0 };
    case 'playlistNotReady':
      return state.phase === 'connecting' || state.phase === 'waiting'
        ? { phase: 'waiting', attempt: state.attempt + 1 }
        : { phase: 'reconnecting', attempt: state.attempt + 1 };
    case 'stalled':
    case 'fatalError':
      return { phase: 'reconnecting', attempt: state.attempt + 1 };
  }
}

/** Fixed cadence for the cold-start "waiting for the first segment" retry — fast, since this is the expected path. */
export const COLD_START_RETRY_DELAY_MS = 1_500;

/** No fragment/segment progress for this long counts as a stall worth reconnecting over. */
export const STALL_WATCHDOG_MS = 8_000;

const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 30_000;

/**
 * Capped exponential backoff for the `reconnecting` phase: attempt 1 → 1s, 2 → 2s, 3 → 4s, …,
 * capped at 30s and held there indefinitely — the player never gives up on its own while `src` is
 * still set (docs/CYCLES-PLAN.md §11 item 5: "indefinitely while the page is open"), including
 * across a backend restart that takes longer than any single attempt window.
 */
export function reconnectDelayMs(attempt: number): number {
  const exponent = Math.max(0, attempt - 1);
  return Math.min(RECONNECT_BASE_DELAY_MS * 2 ** exponent, RECONNECT_MAX_DELAY_MS);
}

/** Whether enough time has passed since the last fragment/segment progress to call it a stall. */
export function isStalled(lastProgressAtMs: number, nowMs: number, thresholdMs = STALL_WATCHDOG_MS): boolean {
  return nowMs - lastProgressAtMs >= thresholdMs;
}

// --- Transport (docs/MVP2-PLAN.md §L / §U3: WHEP-first playback, HLS fallback) -----------------
//
// `RecoveryState`/`reduceRecovery` above are untouched — every existing caller/spec keeps working
// exactly as before. What follows *wraps* them with one additional rule specific to a
// WebRTC(WHEP)-first player: which transport is currently live, and when a WHEP attempt gives up
// and falls back to HLS. This is deliberately a thin wrapper, not a parallel state machine —
// `reduceTransportRecovery` still funnels every event through `reduceRecovery` itself, so a
// transport's own phase/attempt bookkeeping (including the capped exponential reconnect backoff)
// is the exact same code path HLS-only players have always used.

/** Which live media transport is currently attached — surfaced by the player's state chip. */
export type Transport = 'webrtc' | 'hls';

export interface TransportRecoveryState {
  readonly transport: Transport;
  readonly recovery: RecoveryState;
}

/** A fresh attach starts on `webrtc` when a WHEP URL exists, `hls` otherwise (no WHEP to try). */
export function initialTransportState(hasWhepUrl: boolean): TransportRecoveryState {
  return { transport: hasWhepUrl ? 'webrtc' : 'hls', recovery: INITIAL_RECOVERY_STATE };
}

/**
 * Advances the transport-aware recovery state by one event.
 *
 * The one rule this adds on top of `reduceRecovery`: a `webrtc` transport that fails (`fatalError`
 * — POST/ICE error; `stalled` — no track within a timeout, or a later stall watchdog miss;
 * `unsupported` — this browser has no `RTCPeerConnection`) **before it has ever reached `playing`**
 * falls over to `hls` permanently for this attach lifetime, restarting `hls`'s own recovery from a
 * clean `connecting` state (docs/MVP2-PLAN.md §L's documented caveat: a dockerized mediamtx
 * advertises `127.0.0.1` ICE candidates, so a LAN viewer's WHEP attempt is *expected* to fail every
 * time — retrying it would just add latency before the same, inevitable fallback). A `webrtc`
 * transport that fails **after** having reached `playing` at least once instead retries `webrtc`
 * itself with `reduceRecovery`'s own capped backoff — "reconnect cycles per transport": a
 * connection that has already proven reachable earns a retry on the same transport, not an
 * immediate demotion, exactly mirroring how `reduceRecovery`'s own `playlistNotReady` branch tells
 * "never played yet" apart from "played before, now reconnecting" (see its doc comment). `hls`,
 * whether active from the start or reached via fallback, behaves exactly as `reduceRecovery` always
 * has — this function changes nothing about HLS's own recovery once it's the active transport.
 */
export function reduceTransportRecovery(
  state: TransportRecoveryState,
  event: RecoveryEvent,
): TransportRecoveryState {
  const neverPlayedYet = state.recovery.phase === 'connecting' || state.recovery.phase === 'waiting';
  const isWhepFailure = event === 'fatalError' || event === 'stalled' || event === 'unsupported';

  if (state.transport === 'webrtc' && neverPlayedYet && isWhepFailure) {
    return { transport: 'hls', recovery: reduceRecovery(INITIAL_RECOVERY_STATE, 'attachStarted') };
  }
  return { transport: state.transport, recovery: reduceRecovery(state.recovery, event) };
}
