/**
 * Pure state machine behind `shared/player/player.ts`'s self-recovery (docs/main/CYCLES-PLAN.md §11, CD-b item 5).
 *
 * Split out of the component so the recovery logic — which phase follows which event, and how
 * long to wait before the next reconnect attempt — is unit-testable without hls.js, timers, or a
 * `<video>` element, mirroring `core/telemetry/telemetry-logic.ts`'s split of pure derivation from the
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
 *
 * **`stopped`** (docs/plans/done/MVP2-PLAN.md §S, S-b) — a *deliberately* stopped stream, distinct from both
 * `idle` (nothing has ever been asked to play) and `reconnecting`/`error` (a still-wanted stream
 * that is struggling or unplayable). Reached only via the new `'stopped'` event, which a host page
 * dispatches once it knows — from its own explicit Stop action, or from the streams list no longer
 * naming this device (see `shared/player/player.ts`'s `stopped` input doc) — that nothing should be attached
 * any more. **Absorbing**: see `reduceRecovery`'s own doc comment for exactly which events can (and
 * cannot) move a state back out of it.
 */
export type PlayerPhase =
  'idle' | 'connecting' | 'waiting' | 'playing' | 'reconnecting' | 'error' | 'stopped';

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
  | 'unsupported'
  /**
   * The stream was deliberately stopped (docs/plans/done/MVP2-PLAN.md §S, S-b) — either this page's own Stop
   * action, or the streams list no longer naming this device. Always wins, from any phase.
   */
  | 'stopped';

/**
 * Advances the recovery state machine by one event.
 *
 * The one branch worth reading closely is `playlistNotReady`: before the stream has ever played
 * (`connecting`/`waiting`), a missing playlist is the ordinary, expected shape of a fresh stream —
 * it stays a gentle `waiting` (docs/main/CYCLES-PLAN.md §11 item 5: "no error flash on fresh streams").
 * Once the stream has played before, or is already mid-recovery, the *same* event means the
 * backend actually went away (e.g. a restart) — it becomes a real `reconnecting` attempt instead.
 *
 * **`stopped` is absorbing** (docs/plans/done/MVP2-PLAN.md §S, S-b): once `phase === 'stopped'`, every event
 * except `'attachStarted'`/`'reset'` (a *fresh* attach — a new `src`/`whepUrl` the caller actually
 * wants attached again) is a no-op, returning the exact same `state`. This is the fix for the
 * diagnosed stop-freeze mechanism: without it, a stray recovery signal arriving after a deliberate
 * stop — a late hls.js fatal error from the just-torn-down attachment, a WHEP ICE state flip, or
 * (observed live) the backend briefly re-listing the "stopped" stream while its own teardown is
 * still in flight — would otherwise re-enter `reconnecting`/`playing` and the player would attach
 * *again*, against a stream the user explicitly asked to end; repeated, this is exactly the tight
 * reconnect cycle (fresh WHEP POST after fresh WHEP POST) that read as the whole app freezing.
 * `'stopped'` itself always wins, overriding any in-progress state including a prior `'stopped'`.
 */
export function reduceRecovery(state: RecoveryState, event: RecoveryEvent): RecoveryState {
  if (event === 'stopped') {
    return { phase: 'stopped', attempt: 0 };
  }
  if (state.phase === 'stopped' && event !== 'reset' && event !== 'attachStarted') {
    return state; // absorbing — see this function's own doc comment above
  }
  switch (event) {
    case 'reset':
      return INITIAL_RECOVERY_STATE;
    case 'unsupported':
      return { phase: 'error', attempt: state.attempt };
    case 'attachStarted':
      return state.phase === 'idle' || state.phase === 'stopped'
        ? { phase: 'connecting', attempt: 0 }
        : state;
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
 * still set (docs/main/CYCLES-PLAN.md §11 item 5: "indefinitely while the page is open"), including
 * across a backend restart that takes longer than any single attempt window.
 */
export function reconnectDelayMs(attempt: number): number {
  const exponent = Math.max(0, attempt - 1);
  return Math.min(RECONNECT_BASE_DELAY_MS * 2 ** exponent, RECONNECT_MAX_DELAY_MS);
}

/** Whether enough time has passed since the last fragment/segment progress to call it a stall. */
export function isStalled(
  lastProgressAtMs: number,
  nowMs: number,
  thresholdMs = STALL_WATCHDOG_MS,
): boolean {
  return nowMs - lastProgressAtMs >= thresholdMs;
}

// --- Transport (docs/plans/done/MVP2-PLAN.md §L / §U3: WHEP-first playback, HLS fallback) -----------------
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
 * clean `connecting` state (docs/plans/done/MVP2-PLAN.md §L's documented caveat: a dockerized mediamtx
 * advertises `127.0.0.1` ICE candidates, so a LAN viewer's WHEP attempt is *expected* to fail every
 * time — retrying it would just add latency before the same, inevitable fallback). A `webrtc`
 * transport that fails **after** having reached `playing` at least once instead retries `webrtc`
 * itself with `reduceRecovery`'s own capped backoff — "reconnect cycles per transport": a
 * connection that has already proven reachable earns a retry on the same transport, not an
 * immediate demotion, exactly mirroring how `reduceRecovery`'s own `playlistNotReady` branch tells
 * "never played yet" apart from "played before, now reconnecting" (see its doc comment). `hls`,
 * whether active from the start or reached via fallback, behaves exactly as `reduceRecovery` always
 * has — this function changes nothing about HLS's own recovery once it's the active transport.
 *
 * `'stopped'` (docs/plans/done/MVP2-PLAN.md §S, S-b) is not a "failure" this function's fallback rule cares
 * about — it always routes straight to `reduceRecovery`, landing on the absorbing `stopped` phase
 * regardless of `transport`/`neverPlayedYet`; `transport` itself is left as whatever it was (moot —
 * the next real attach picks a fresh one via `initialTransportState`, it never reads this field).
 */
export function reduceTransportRecovery(
  state: TransportRecoveryState,
  event: RecoveryEvent,
): TransportRecoveryState {
  const neverPlayedYet =
    state.recovery.phase === 'connecting' || state.recovery.phase === 'waiting';
  const isWhepFailure = event === 'fatalError' || event === 'stalled' || event === 'unsupported';

  if (state.transport === 'webrtc' && neverPlayedYet && isWhepFailure) {
    return { transport: 'hls', recovery: reduceRecovery(INITIAL_RECOVERY_STATE, 'attachStarted') };
  }
  return { transport: state.transport, recovery: reduceRecovery(state.recovery, event) };
}

// --- Cross-cycle reconnect pacing (docs/plans/done/MVP2-PLAN.md §S, S-c) ------------------------------------
//
// `RecoveryState`/`reduceRecovery` and `TransportRecoveryState`/`reduceTransportRecovery` above are
// UNCHANGED by this cycle — every existing caller/spec (including their own `attempt` field, reset
// immediately by `firstSegment`) keeps working exactly as before; it still drives the visible
// "Retrying automatically (attempt N)" hint, which is a *local*, per-phase-machine bookkeeping
// detail, not a cross-cycle one.
//
// What follows is a further, independent pure state machine `shared/player/player.ts` layers on top, for the
// one question `RecoveryState.attempt` was never designed to answer honestly: across a reconnect
// saga that can span many WHEP→HLS *cycles* (a cycle: try WHEP if due, else go straight to HLS; a
// WHEP pre-play failure still falls straight over to HLS within the *same* cycle, no extra delay —
// U3's permanent-per-cycle fallback, untouched), how long has this saga actually been failing, and
// has the stream proven itself recovered — or just blipped? S-b explicitly flagged this as an open
// gap: "a rapid flapping connection defeats backoff via the legitimate firstSegment-resets-attempt
// rule", and a zombie stream's WHEP-fail-then-HLS-fail cycling can pin every retry at the shortest
// 1s delay forever, since each cycle's own `RecoveryState` legitimately looks freshly-connecting on
// its own local terms — `reduceTransportRecovery`'s webrtc→hls fallback branch, in particular,
// starts a brand new `RecoveryState` from scratch every time it fires.
//
// Three rules, each independently unit-tested below:
//
//  1. **Escalating pacing survives a cycle boundary.** `PacingState.cycleAttempt` is the single
//     counter `shared/player/player.ts` uses to compute the delay before the *next* cycle
//     (`cyclePacingDelayMs`, reusing `reconnectDelayMs`'s exact 1s→…→30s-capped schedule) — bumped
//     once per *cycle* that ends without reaching a sustained recovery (`'cycleFailed'`), never
//     reset by merely reaching `playing` (contrast `RecoveryState.attempt`, reset immediately). A
//     cold-start playlist miss (404, never-yet-played) folds under this same pacing the moment this
//     saga has already failed at least once (`cycleAttempt > 0`) — only a saga that has *never*
//     failed anything yet keeps the fixed, gentle `COLD_START_RETRY_DELAY_MS` poll ("a never-yet-
//     live stream politely waiting is allowed its gentle poll; a stream that HAS failed transports
//     escalates").
//  2. **WHEP is damped, not abandoned.** `shouldAttemptWhep` gates each cycle's choice to try WHEP
//     at all behind `WHEP_RETRY_COOLDOWN_MS` (60s) since this saga's last attempt — see its own doc
//     comment for the concrete request-rate math against a zombie endpoint. A sustained recovery
//     (rule 3) re-arms it immediately (`lastWhepAttemptAtMs` cleared), so a connection that has
//     genuinely proven itself healthy again doesn't have to wait out a stale cooldown before its
//     next reconnect is allowed to try the better transport once more.
//  3. **Only a sustained streak counts as recovered.** `'healthTick'` (dispatched roughly once a
//     second by `shared/player/player.ts` while `phase === 'playing'`) only resets `cycleAttempt` once the
//     *unbroken* streak since the last `'playing'` event has lasted `SUSTAINED_PLAYBACK_RESET_MS`
//     (5s) — a connection that flaps (plays briefly, fails again before the window elapses) reports
//     `'cycleFailed'` first, clearing the in-progress streak, so it never reaches the reset at all;
//     the very next failure resumes escalating from wherever `cycleAttempt` already was.

/**
 * Cross-cycle reconnect pacing (docs/plans/done/MVP2-PLAN.md §S, S-c) — see the block comment above for the
 * three rules this backs.
 */
export interface PacingState {
  /**
   * Backoff level for the whole reconnect saga — survives every cycle boundary, including a
   * WHEP→HLS fallback; only a sustained `'healthTick'` (rule 3) ever zeroes it.
   */
  readonly cycleAttempt: number;
  /** `Date.now()` when the current unbroken `'playing'` streak began, `null` while not playing. */
  readonly playingSinceMs: number | null;
  /** `Date.now()` of the most recent WHEP attempt, `null` before this saga's first one. */
  readonly lastWhepAttemptAtMs: number | null;
}

export const INITIAL_PACING_STATE: PacingState = {
  cycleAttempt: 0,
  playingSinceMs: null,
  lastWhepAttemptAtMs: null,
};

/** How long an unbroken `playing` streak must last to count as a genuine recovery, not a blip. */
export const SUSTAINED_PLAYBACK_RESET_MS = 5_000;

/**
 * Minimum time between WHEP attempts once this saga has made one — see `shouldAttemptWhep`'s own
 * doc comment for the concrete request-rate this produces against a dead/zombie endpoint.
 */
export const WHEP_RETRY_COOLDOWN_MS = 60_000;

export type PacingEvent =
  /** A WHEP POST is actually going out this cycle — starts/refreshes the damping cooldown. */
  | 'whepAttempted'
  /** The current cycle ended without reaching a sustained recovery — bumps `cycleAttempt`. */
  | 'cycleFailed'
  /** This cycle (or a later moment) reached `playing` — starts tracking the streak, if not already. */
  | 'playing'
  /** A ~1s clock tick while `playing` — the only event that can reset `cycleAttempt`. */
  | 'healthTick';

/** Advances the pacing state by one event — see the block comment above this section for the rules. */
export function advancePacing(state: PacingState, event: PacingEvent, nowMs: number): PacingState {
  switch (event) {
    case 'whepAttempted':
      return { ...state, lastWhepAttemptAtMs: nowMs };
    case 'cycleFailed':
      return { ...state, cycleAttempt: state.cycleAttempt + 1, playingSinceMs: null };
    case 'playing':
      return state.playingSinceMs === null ? { ...state, playingSinceMs: nowMs } : state;
    case 'healthTick': {
      if (
        state.playingSinceMs === null ||
        nowMs - state.playingSinceMs < SUSTAINED_PLAYBACK_RESET_MS
      ) {
        return state;
      }
      return state.cycleAttempt === 0 && state.lastWhepAttemptAtMs === null
        ? state // already at rest — a genuine no-op, same reference (mirrors `reduceRecovery`'s own idiom)
        : { cycleAttempt: 0, playingSinceMs: state.playingSinceMs, lastWhepAttemptAtMs: null };
    }
  }
}

/**
 * The shared cross-cycle backoff delay for the *next* cycle (rule 1) — reuses `reconnectDelayMs`'s
 * exact 1s→2s→…→30s-capped schedule, keyed off the saga-wide `cycleAttempt` rather than
 * `RecoveryState.attempt`'s own cycle-local one.
 */
export function cyclePacingDelayMs(state: PacingState): number {
  return reconnectDelayMs(state.cycleAttempt);
}

/**
 * Whether the next cycle should even attempt WHEP (rule 2), given `hasWhepUrl` and the damping
 * cooldown. Always true for this saga's first attempt (`lastWhepAttemptAtMs` starts `null`) — a
 * fresh attach always tries the better transport first; damping only applies to *subsequent* cycles
 * within the same still-failing saga.
 *
 * **The 60s figure, concretely, chosen over a fixed cycle-count**: a wall-clock cooldown bounds the
 * *absolute* WHEP request rate directly ("a few requests per minute, not per second" — the task's
 * own success criterion) regardless of how fast `cyclePacingDelayMs` happens to be cycling, which a
 * cycle-count-based rule (e.g. "every 4th cycle") would not: cycle counting ties the WHEP-retry rate
 * to the backoff schedule's own shape, so a future change to that schedule would silently change the
 * WHEP-damping rate too. Concretely, against a zombie stream (WHEP fails pre-play every time, HLS's
 * playlist never appears), `cycleAttempt`'s capped-exponential schedule reaches the 30s floor within
 * about a minute of cumulative delay (1+2+4+8+16 = 31s by the 6th cycle) — so a 60s cooldown lands on
 * roughly one WHEP retry every one-to-two reconnect cycles once backoff is fully escalated, i.e. on
 * the order of one WHEP POST a minute, not one a second.
 */
export function shouldAttemptWhep(state: PacingState, hasWhepUrl: boolean, nowMs: number): boolean {
  if (!hasWhepUrl) {
    return false;
  }
  return (
    state.lastWhepAttemptAtMs === null ||
    nowMs - state.lastWhepAttemptAtMs >= WHEP_RETRY_COOLDOWN_MS
  );
}

// --- WHEP real stall detection via getStats() (docs/plans/done/REALTIME-PLAN.md §0 + Phase R-a item 1) -----
//
// Before this, `shared/player/player.ts`'s `lastFrameAt` was only ever set once at attach and once more in the
// one-shot `ontrack` handler — nothing observed *ongoing* frame delivery, so `STALL_WATCHDOG_MS`
// (8s) declared a perfectly healthy WHEP stream stalled ~8-10s after connecting, every time,
// forever (measured: WHEP churn cycling at ~10-11s on a stream with no actual problem). The fix:
// the existing 2s watchdog tick (`startWhepWatchdog`) also polls `RTCPeerConnection.getStats()`
// and refreshes `lastFrameAt` whenever the inbound video track's own decode/receive counters moved
// since the previous tick — real evidence of ongoing decode, not just "a track was ever attached
// once". A genuinely dead feed's counters stop moving, so `isStalled` still fires for it exactly as
// before this cycle.
//
// Split out here (rather than left inline in `shared/player/player.ts`) so the comparison logic is testable
// without a real `RTCPeerConnection`/`getStats()` — mirrors this file's own existing split of pure
// state-machine logic from the component that drives it.

/**
 * The two monotonic WHEP inbound-video counters this cycle's stall check watches, plus the two
 * connection-quality figures the latency badge (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4) reads from the
 * *same* `getStats()` pass — extending this extraction rather than adding a second poller/call.
 */
export interface WhepStatsSnapshot {
  readonly framesDecoded: number;
  readonly bytesReceived: number;
  /** `RTCInboundRtpStreamStats.jitter` (seconds), from the same inbound video entry, when reported. */
  readonly jitterSeconds?: number;
  /** `RTCIceCandidatePairStats.currentRoundTripTime` (seconds) of the active pair, when reported. */
  readonly roundTripTimeSeconds?: number;
}

/**
 * The subset of a `getStats()` report entry this module reads — a small structural type (not the
 * full DOM `RTCStats` union) so `extractWhepStatsSnapshot` is callable with plain object literals
 * in tests, no `RTCStatsReport`/jsdom WebRTC shim required.
 */
export interface WhepInboundRtpStatLike {
  readonly type?: string;
  readonly kind?: string;
  readonly framesDecoded?: number;
  readonly bytesReceived?: number;
  readonly jitter?: number;
}

/**
 * The active ICE candidate-pair entry in the same `getStats()` report — the source of round-trip
 * time for the latency badge (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4): STUN connectivity checks measure RTT
 * regardless of media direction, so this is available even for this player's `recvonly`-only
 * transceiver, unlike a sender-side `remote-inbound-rtp` report (mediamtx's WHEP output has no
 * reason to produce one of those for a receive-only viewer).
 */
export interface WhepCandidatePairStatLike {
  readonly type?: string;
  readonly state?: string;
  readonly nominated?: boolean;
  readonly currentRoundTripTime?: number;
}

/**
 * Finds the inbound video RTP stat among a `getStats()` report's entries and reads its decode
 * counters plus jitter, and separately reads round-trip time off whichever candidate-pair entry is
 * actually carrying the connection (`state === 'succeeded'`, `nominated`) — one pass over the same
 * report the R-a stall watchdog already fetches every `WATCHDOG_TICK_MS`, not a second call.
 *
 * `null` when no inbound video RTP entry exists yet (e.g. the very first tick right after
 * `setRemoteDescription`, before the receiver has produced its first stats sample) — the stall
 * watchdog treats that the same as "no progress observed this tick"; `estimateWhepLatencySeconds`
 * below treats a missing `roundTripTimeSeconds` the same way, as "not yet known", never a guess.
 */
export function extractWhepStatsSnapshot(
  stats: Iterable<WhepInboundRtpStatLike | WhepCandidatePairStatLike>,
): WhepStatsSnapshot | null {
  let inbound: { framesDecoded: number; bytesReceived: number; jitterSeconds?: number } | null =
    null;
  let roundTripTimeSeconds: number | undefined;
  for (const stat of stats) {
    if (stat.type === 'inbound-rtp' && (stat as WhepInboundRtpStatLike).kind === 'video') {
      const s = stat as WhepInboundRtpStatLike;
      inbound = {
        framesDecoded: s.framesDecoded ?? 0,
        bytesReceived: s.bytesReceived ?? 0,
        jitterSeconds: s.jitter,
      };
    } else if (stat.type === 'candidate-pair') {
      const pair = stat as WhepCandidatePairStatLike;
      if (pair.state === 'succeeded' && pair.nominated) {
        roundTripTimeSeconds = pair.currentRoundTripTime;
      }
    }
  }
  return inbound ? { ...inbound, roundTripTimeSeconds } : null;
}

/**
 * A player-chrome latency estimate for a WHEP transport (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4's latency
 * badge) — WHEP has no live-edge distance to measure the way HLS does (`shared/player/player.ts`'s
 * own `behindLive` stays pinned to `0` for the overlay-sync matcher; see that class's own doc
 * comment), so this is the honest number to show instead: half the measured round-trip time (the
 * one-way network transit this side of the connection experiences) plus the jitter-buffer delay the
 * browser is absorbing to smooth packet arrival — both already sitting in the same `getStats()`
 * snapshot the stall watchdog polls every `WATCHDOG_TICK_MS`, via `extractWhepStatsSnapshot` above.
 *
 * `null` — rendered as `'—'` by the badge, never a guessed number — until this attach's first
 * candidate-pair report actually carries a round-trip time; jitter alone, without an RTT, isn't a
 * latency estimate worth showing.
 */
export function estimateWhepLatencySeconds(snapshot: WhepStatsSnapshot | null): number | null {
  if (!snapshot || snapshot.roundTripTimeSeconds === undefined) {
    return null;
  }
  return snapshot.roundTripTimeSeconds / 2 + (snapshot.jitterSeconds ?? 0);
}

/**
 * Whether the stream made real decode/receive progress since the previous tick's snapshot.
 *
 * No `current` snapshot at all is never progress (nothing to compare). No `previous` snapshot (the
 * first tick that finds one at all) counts as progress only if the counters already show *some*
 * life (`> 0`) — `lastFrameAt` is already fresh from attach/`ontrack` at that point, so this only
 * has to answer "has anything actually been decoded/received, ever or since the last tick".
 */
export function didWhepStatsAdvance(
  previous: WhepStatsSnapshot | null,
  current: WhepStatsSnapshot | null,
): boolean {
  if (!current) {
    return false;
  }
  if (!previous) {
    return current.framesDecoded > 0 || current.bytesReceived > 0;
  }
  return (
    current.framesDecoded > previous.framesDecoded || current.bytesReceived > previous.bytesReceived
  );
}

// --- Reattach identity key (docs/plans/done/REALTIME-PLAN.md Phase R-a item 4) -----------------------------
//
// `shared/player/player.ts`'s constructor effect only actually reattaches when this key changes from the
// previous run — the e7cdc46 fix for the hard-freeze bug (see that commit / this file's own
// `RecoveryState` doc comment history). Extracted to a pure function so the guarantee this cycle
// leans on for secondary tiles is directly testable: **the key depends only on the four primitive
// values, never on the identity of whatever object they were read off of.** A wall/fly secondary
// tile's `stream()`/`device()` computed re-derives a brand-new object every ~5s poll tick even when
// nothing about that one device actually changed — `@for`'s own `track device.id` already keeps the
// same `<vision-player>` component instance alive across that (Angular reuses/moves the DOM node,
// never destroys it, as long as the tracked key is stable); this key is what then keeps *that*
// instance from tearing down and rebuilding its `RTCPeerConnection`/`Hls` on every one of those
// re-renders too, reattaching only when `src`/`whepUrl`/`suspended`/`stopped` actually changed value.
export function attachKey(
  src: string | null,
  whepUrl: string | null,
  suspended: boolean,
  stopped: boolean,
): string {
  return `${src} ${whepUrl} ${suspended} ${stopped}`;
}

// --- WHEP ICE-restart-first recovery (docs/plans/done/REALTIME-PLAN.md Phase R-b item 1) -------------------
//
// A further, independent pure state machine, sibling to `PacingState` above — same reasoning: kept
// separate rather than bolted onto `RecoveryState`/`TransportRecoveryState` as ad-hoc flags. The
// crucial difference from every failure this file's other reducers model: an in-place ICE restart
// is designed to be **invisible** — media keeps flowing on the same `RTCPeerConnection` throughout,
// so the visible chip (`RecoveryState.phase`) must **not** flip to `'reconnecting'` while one is in
// progress, the way it always has for a full teardown+reattach. `shared/player/player.ts` only ever reaches
// into the *existing* `RecoveryState`/`TransportRecoveryState`/`PacingState` machinery as this
// item's own explicitly-named "last resort" (a PATCH rejected/404/412, or the restart mechanism
// itself unavailable) — that fallback path is completely unchanged from before this cycle.
//
// `RecoveryState`/`reduceRecovery`/`TransportRecoveryState`/`reduceTransportRecovery`/`PacingState`/
// `advancePacing` above are all **unchanged** by this — every existing caller/spec keeps working
// exactly as before.

/**
 * `'stable'` — connected, nothing to do. `'grace'` — `connectionState` just went `'disconnected'`;
 * a 5s grace timer (`ICE_RESTART_GRACE_MS`) is running, giving a brief network blip a chance to
 * self-heal before anything is attempted. `'restarting'` — an ICE-restart PATCH is (or is about to
 * be) in flight, triggered either by the grace timer expiring or by `connectionState` going
 * straight to `'failed'` (no grace wait for an outright failure — see `reduceWhepIce`'s own event
 * docs). There is deliberately no `'failed'`/dead-end phase here: a rejected restart routes straight
 * through the pre-existing `RecoveryState`/`TransportRecoveryState` fallback instead of being
 * represented in this machine at all — the next fresh `beginWhepAttach` resets this one back to
 * `'stable'` directly (a plain field assignment, not a dispatched event — mirrors how `lastFrameAt`/
 * `whepStatsSnapshot` are already reset the same way on every fresh attach).
 */
export type WhepIcePhase = 'stable' | 'grace' | 'restarting';

export interface WhepIceState {
  readonly phase: WhepIcePhase;
  /** Bumped every time a restart attempt actually begins — lets an in-flight PATCH notice a newer failure/attach superseded it, without a second generation counter. */
  readonly restartAttempt: number;
}

export const INITIAL_WHEP_ICE_STATE: WhepIceState = { phase: 'stable', restartAttempt: 0 };

export type WhepIceEvent =
  /** `connectionState` → `'disconnected'` — starts the grace timer. A no-op unless currently `'stable'`. */
  | 'disconnected'
  /** `connectionState` recovered back to `'connected'` before the grace timer fired — ICE self-healed, no restart needed. A no-op unless currently `'grace'`. */
  | 'reconnected'
  /** The grace timer elapsed without self-healing. A no-op unless currently `'grace'`. */
  | 'graceExpired'
  /** `connectionState` → `'failed'` — restart immediately, no grace wait, from *any* phase (including mid-restart: a second, more urgent failure signal still bumps `restartAttempt`, superseding whatever attempt was already in flight). */
  | 'failed'
  /** The PATCH-based restart's `setRemoteDescription` resolved — media/ICE recovered in place. A no-op unless currently `'restarting'`. */
  | 'restartSucceeded'
  /** A fresh attach — back to a clean slate. */
  | 'reset';

/**
 * Advances the ICE-health state by one event — see the block comment above this section for why
 * this is a separate machine from `RecoveryState`. `'failed'` always restarts immediately
 * regardless of current phase; every other event only means something from the one specific phase
 * named in its own doc comment above and is a genuine no-op (`state` returned unchanged) otherwise
 * — `connectionState` can fire events this machine isn't watching for outside those phases (e.g. a
 * stray `'connected'` while already `'stable'`), and those must never be mistaken for progress.
 */
export function reduceWhepIce(state: WhepIceState, event: WhepIceEvent): WhepIceState {
  switch (event) {
    case 'disconnected':
      return state.phase === 'stable' ? { ...state, phase: 'grace' } : state;
    case 'reconnected':
      return state.phase === 'grace' ? { ...state, phase: 'stable' } : state;
    case 'graceExpired':
      return state.phase === 'grace'
        ? { phase: 'restarting', restartAttempt: state.restartAttempt + 1 }
        : state;
    case 'failed':
      return { phase: 'restarting', restartAttempt: state.restartAttempt + 1 };
    case 'restartSucceeded':
      return state.phase === 'restarting'
        ? { phase: 'stable', restartAttempt: state.restartAttempt }
        : state;
    case 'reset':
      return INITIAL_WHEP_ICE_STATE;
  }
}

/**
 * Grace period between `connectionState==='disconnected'` and attempting an ICE restart
 * (docs/plans/done/REALTIME-PLAN.md Phase R-b item 1) — long enough to absorb a brief network blip (a Wi-Fi
 * roam, a momentary NAT rebind) without ever tearing anything down for it, short enough that a
 * genuine loss still recovers well within the operator's own attention span.
 */
export const ICE_RESTART_GRACE_MS = 5_000;

// --- WHEP no-track timeout race (docs/plans/done/REALTIME-PLAN.md Phase R-b follow-up) ----------------------
//
// A live soak test caught a 100%-reproducible false-teardown bug: `shared/player/player.ts`'s
// `scheduleWhepNoTrackTimeout` used to run only after `await pc.setRemoteDescription(...)`
// resolved, but on a fast/loopback connection `pc.ontrack` (which clears that timer) fires as part
// of applying the remote description itself — *before* that `await`'s own continuation resumes.
// The timer ended up armed *after* the track had already arrived, with nothing left to ever clear
// it, so it fired unconditionally `WHEP_NO_TRACK_TIMEOUT_MS` later against a perfectly healthy
// stream — confirmed at ~90 reproductions, near-zero jitter, every single attach: WHEP tore down
// and re-POSTed every ~7.15s, worse than the pre-R-a baseline this whole plan set out to fix, and
// so much faster than `STALL_WATCHDOG_MS`/`ICE_RESTART_GRACE_MS` that neither R-a's stats-based
// stall watchdog nor R-b's ICE-restart machinery ever got a fair, quiet window to run at all.
//
// This one predicate is the actual fix, used at both the "should I even arm the timer" call site
// and the timer's own "did the track show up while I was waiting" fired callback — reordering
// `scheduleWhepNoTrackTimeout`'s call site earlier in `beginWhepAttach` (right after receiving the
// answer, before `await`ing `setRemoteDescription`) only narrows the race window; this flag check is
// what closes it regardless of exact event-loop ordering.

/**
 * Whether a WHEP attach is still legitimately waiting for `ontrack` to fire — `false` once the
 * track has already arrived, at which point neither arming a fresh no-track timeout nor treating an
 * already-scheduled one's later firing as a genuine failure is ever correct.
 */
export function isWhepStillWaitingForTrack(trackArrived: boolean): boolean {
  return !trackArrived;
}
