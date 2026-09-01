import type { Transport } from './player-recovery';

/**
 * Pure logic behind `shared/player/player.ts`'s live-edge behavior (docs/plans/done/MVP2-PLAN.md §V, V-b; the
 * badge text itself extended by docs/plans/done/UX-QUICKWINS-PLAN.md QF-4): when a stale HLS buffer is worth
 * an active catch-up seek, and how the player chrome's latency badge should read given whichever
 * transport is live. Split out so both decisions are unit-testable without hls.js, timers, or a
 * `<video>` element — mirrors `shared/player/player-recovery.ts`/`shared/player/detection-overlay-logic.ts`.
 *
 * Both functions here are deliberately transport/reducer-*aware*, not transport/reducer
 * *replacements* — `shared/player/player.ts` still drives every phase transition through
 * `player-recovery.ts#reduceTransportRecovery`; this module only answers "given where that
 * machine already says we are, and what's been measured, what should the chrome show".
 */

// --- Snap-to-live --------------------------------------------------------------------------

/**
 * Why a snap-to-live check is firing:
 *
 *  - `'recovered'` — the recovery reducer just carried a transport from `reconnecting` back to
 *    `playing` (a stall or a fatal error triggered a reattach, and it succeeded). `shared/player/player.ts`
 *    always tears the old `hls.js`/native attachment down and rebuilds it from nothing before a
 *    reattach (`teardownMedia`), so there is no stale buffer here to *resume* — but there is also
 *    no cost to landing exactly on the edge instead of trusting wherever the fresh attach's own
 *    start-position logic happens to land it, so this reason always snaps.
 *  - `'visibilityRestored'` — the tab regained visibility. Nothing in the recovery reducer models
 *    this (correctly: the attachment itself never stalled or errored — a hidden tab's `<video>`
 *    decode/render is merely throttled by the browser, not torn down, so hls.js keeps buffering
 *    underneath), which is exactly why it needs its own explicit check here rather than falling
 *    out of `reduceTransportRecovery` for free.
 *  - `'firstAttach'` — the very first FRAG_BUFFERED (or native `'playing'`) event of a fresh attach
 *    (fix/stream-start-latency: the Stop→Start "picture is several seconds behind reality" symptom).
 *    `'recovered'` already snaps every *later* reattach within an attach's lifetime, but a brand new
 *    attach's own first playback start was never covered — hls.js's default start position on a cold
 *    `attachMedia`/`loadSource` can land behind the edge, and a stream that spent its first few
 *    seconds cycling through cold-start retries (`scheduleColdStartRetry`) or a WHEP→HLS fallback
 *    before ever reaching `playing` has every reason to have buffered stale segments in the
 *    meantime. Same reasoning as `'recovered'`'s own doc comment for why trusting a threshold here
 *    would be pointless — extended to the first time this attach ever reaches playing, not just a
 *    reattach after a failure.
 */
export type SnapToLiveReason = 'recovered' | 'visibilityRestored' | 'firstAttach';

/** How far behind (seconds) a tab-visibility restore has to measure before it's worth a hard seek. */
export const SNAP_TO_LIVE_THRESHOLD_SECONDS = 3;

/**
 * Whether `shared/player/player.ts` should forward-seek the `<video>` to the live edge rather than let
 * playback resume wherever its buffer happens to sit.
 *
 * `'recovered'` and `'firstAttach'` always snap, ignoring `behindLiveSeconds`/`thresholdSeconds`
 * entirely — see each reason's own doc comment for why trusting a threshold here would be pointless
 * (the measurement is almost always `null` at this exact moment: `behindLive` is reset to `null` on
 * every teardown and not re-sampled until the latency timer's next tick, which starts *after* this
 * fires). `'visibilityRestored'` only snaps once the *current* measurement clears `thresholdSeconds`
 * — a tab glanced away from for under a second shouldn't jump the video out from under the viewer
 * for a barely-measurable drift. `null` (unmeasured, or the WHEP `0` pin — though `shared/player/player.ts`
 * never calls this for a `webrtc` transport in the first place, since WHEP has no seekable buffer
 * to fall behind) never snaps on this branch.
 */
export function shouldSnapToLive(
  reason: SnapToLiveReason,
  behindLiveSeconds: number | null,
  thresholdSeconds = SNAP_TO_LIVE_THRESHOLD_SECONDS,
): boolean {
  if (reason === 'recovered' || reason === 'firstAttach') {
    return true;
  }
  return behindLiveSeconds !== null && behindLiveSeconds > thresholdSeconds;
}

// --- Latency badge (honest, always-on — docs/plans/done/UX-QUICKWINS-PLAN.md QF-4) --------------------
//
// Superseded the earlier hysteresis-gated "live · HLS[· Ns behind]" chip (docs/plans/done/MVP2-PLAN.md §V,
// V-b): the fleet/military honesty ethos this quick-win batch is built around (UX-DESIGN §T1) asks
// for the measured number *always* visible, updating as it changes, not hidden behind a dead-band
// until it crosses a few-second threshold. There is nothing left to debounce once the badge always
// shows a number — the old flicker concern only existed because the chip toggled between a
// qualitative "live" form and a quantified one at a single noisy cutoff; a badge that is *always*
// quantified has no such toggle to flicker.

/**
 * The player chrome's latency badge text, e.g. `'WebRTC 0.4s'`, `'HLS ~6s'`, or `'WebRTC —'`/
 * `'HLS —'` while unmeasured (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4) — `transport` always leads, exactly
 * as the plan's own example format shows. WebRTC shows one decimal (`estimateWhepLatencySeconds`,
 * `player-recovery.ts`, is a real getStats()-derived estimate, precise enough to be worth a
 * decimal); HLS rounds to a whole second with a leading `~` (`measureBehindLive`'s own live-edge
 * distance is a continuously-drifting measurement, not a fixed round-trip figure, so a decimal
 * would read as more precise than it is). Kept as its own pure function (rather than a template
 * ternary) purely so the exact wording has a unit test, same convention as every other formatter in
 * this app (`core/stream-info-logic.ts#formatLatency`/`formatDuration`, etc).
 */
export function transportLatencyLabel(
  transport: Transport,
  hlsBehindLiveSeconds: number | null,
  webrtcLatencySeconds: number | null,
): string {
  if (transport === 'webrtc') {
    return webrtcLatencySeconds === null
      ? 'WebRTC —'
      : `WebRTC ${webrtcLatencySeconds.toFixed(1)}s`;
  }
  return hlsBehindLiveSeconds === null
    ? 'HLS —'
    : `HLS ~${Math.max(0, Math.round(hlsBehindLiveSeconds))}s`;
}
