import type { Transport } from './player-recovery';

/**
 * Pure logic behind `shared/player/player.ts`'s live-edge behavior (docs/MVP2-PLAN.md §V, V-b): when a
 * stale HLS buffer is worth an active catch-up seek, and when the state chip's "how far behind"
 * readout is worth showing at all. Split out so both decisions are unit-testable without hls.js,
 * timers, or a `<video>` element — mirrors `shared/player/player-recovery.ts`/`shared/player/detection-overlay-logic.ts`.
 *
 * Both functions here are deliberately transport/reducer-*aware*, not transport/reducer
 * *replacements* — `shared/player/player.ts` still drives every phase transition through
 * `player-recovery.ts#reduceTransportRecovery`; this module only answers "given where that
 * machine already says we are, should we also do X" for the two live-edge-specific actions V-b
 * adds on top of it.
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
 */
export type SnapToLiveReason = 'recovered' | 'visibilityRestored';

/** How far behind (seconds) a tab-visibility restore has to measure before it's worth a hard seek. */
export const SNAP_TO_LIVE_THRESHOLD_SECONDS = 3;

/**
 * Whether `shared/player/player.ts` should forward-seek the `<video>` to the live edge rather than let
 * playback resume wherever its buffer happens to sit.
 *
 * `'recovered'` always snaps, ignoring `behindLiveSeconds`/`thresholdSeconds` entirely — see the
 * reason's own doc comment for why trusting a threshold here would be pointless (the measurement
 * is almost always `null` at this exact moment: `behindLive` is reset to `null` on every teardown
 * and not re-sampled until the latency timer's next tick, which starts *after* this fires).
 * `'visibilityRestored'` only snaps once the *current* measurement clears `thresholdSeconds` —
 * a tab glanced away from for under a second shouldn't jump the video out from under the viewer
 * for a barely-measurable drift. `null` (unmeasured, or the WHEP `0` pin — though `shared/player/player.ts`
 * never calls this for a `webrtc` transport in the first place, since WHEP has no seekable buffer
 * to fall behind) never snaps on this branch.
 */
export function shouldSnapToLive(
  reason: SnapToLiveReason,
  behindLiveSeconds: number | null,
  thresholdSeconds = SNAP_TO_LIVE_THRESHOLD_SECONDS,
): boolean {
  if (reason === 'recovered') {
    return true;
  }
  return behindLiveSeconds !== null && behindLiveSeconds > thresholdSeconds;
}

// --- Behind-live chip (honest, not decorative — with hysteresis) ---------------------------

/** Above this many seconds behind, the chip starts quantifying it — see `shouldShowBehindLive`. */
export const BEHIND_LIVE_SHOW_THRESHOLD_SECONDS = 4;

/**
 * Once shown, the readout stays up until behind-ness drops below this *lower* threshold — the
 * hysteresis band between this and `BEHIND_LIVE_SHOW_THRESHOLD_SECONDS` is what stops the chip
 * flickering "live · HLS" / "live · HLS · 4.0s behind" back and forth while the real latency
 * hovers within a second of a single cutoff (ordinary jitter in `measureBehindLive`'s own
 * per-second sample, not a real change in how far behind the stream is).
 */
export const BEHIND_LIVE_HIDE_THRESHOLD_SECONDS = 2;

/**
 * Whether the chip should currently be showing the quantified "…s behind" readout.
 *
 * A plain `> threshold` check with only one threshold would flicker every time `behindLiveSeconds`
 * ticks across that single line; this takes the *current* display state as an input and applies
 * whichever of the two thresholds is relevant to the direction being asked about (rising past the
 * show line, or falling past the lower hide line) — the standard two-threshold hysteresis shape,
 * same idea as a thermostat's deadband. `null` (not yet measured) never shows — there is nothing
 * honest to quantify yet, so the chip falls back to the plain "live" form.
 */
export function shouldShowBehindLive(
  behindLiveSeconds: number | null,
  currentlyShowing: boolean,
  showThresholdSeconds = BEHIND_LIVE_SHOW_THRESHOLD_SECONDS,
  hideThresholdSeconds = BEHIND_LIVE_HIDE_THRESHOLD_SECONDS,
): boolean {
  if (behindLiveSeconds === null) {
    return false;
  }
  return currentlyShowing
    ? behindLiveSeconds > hideThresholdSeconds
    : behindLiveSeconds > showThresholdSeconds;
}

/**
 * The state chip's label, e.g. `'live · WebRTC'`, `'live · HLS'`, or `'live · HLS · 4.2s behind'`
 * — `transport` always leads (docs/MVP2-PLAN.md §V, V-b's own example format), the quantified tail
 * only appears while `shouldShowBehindLive` says it should. Kept as its own pure function (rather
 * than a template ternary) purely so the exact wording has a unit test, same convention as every
 * other formatter in this app (`core/stream-info-logic.ts#formatLatency`/`formatDuration`, etc).
 */
export function behindLiveChipLabel(
  transport: Transport,
  behindLiveSeconds: number | null,
  showBehindLive: boolean,
): string {
  if (transport === 'webrtc') {
    return 'live · WebRTC';
  }
  return showBehindLive && behindLiveSeconds !== null
    ? `live · HLS · ${behindLiveSeconds.toFixed(1)}s behind`
    : 'live · HLS';
}
