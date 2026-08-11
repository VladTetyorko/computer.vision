import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import type HlsType from 'hls.js';
import { WebrtcCertificateService } from './webrtc-certificate';
import type { Detection, DetectionResult } from '../../core/api/models';
import {
  DEFAULT_SLACK_BATCHES,
  TRAIL_WINDOW_MS,
  detectionModelKey,
  distinctModelKeys,
  formatDetectionLabel,
  modelHue,
  selectDetectionResult,
  shouldDrawOverlay,
  trackHue,
  trackTrails,
  type BoxesMode,
} from './detection-overlay-logic';
import {
  COLD_START_RETRY_DELAY_MS,
  ICE_RESTART_GRACE_MS,
  INITIAL_PACING_STATE,
  INITIAL_WHEP_ICE_STATE,
  STALL_WATCHDOG_MS,
  advancePacing,
  attachKey,
  cyclePacingDelayMs,
  didWhepStatsAdvance,
  estimateWhepLatencySeconds,
  extractWhepStatsSnapshot,
  initialTransportState,
  isStalled,
  isWhepStillWaitingForTrack,
  reduceTransportRecovery,
  reduceWhepIce,
  shouldAttemptWhep,
  type PacingEvent,
  type PacingState,
  type PlayerPhase,
  type RecoveryEvent,
  type Transport,
  type TransportRecoveryState,
  type WhepCandidatePairStatLike,
  type WhepIceEvent,
  type WhepIceState,
  type WhepInboundRtpStatLike,
  type WhepStatsSnapshot,
} from './player-recovery';
import {
  applyIceRestartAnswer,
  buildIceRestartFragment,
  candidatesFromFragment,
  isPatchFallbackStatus,
} from './webrtc-ice-restart';
import {
  shouldSnapToLive,
  transportLatencyLabel,
  type SnapToLiveReason,
} from './live-edge-logic';

export type { PlayerPhase, Transport } from './player-recovery';
export type { BoxesMode } from './detection-overlay-logic';

/**
 * Console prefix for this component's diagnostic logging (docs/plans/done/MVP3-PLAN.md follow-up: the "Fly
 * shows an empty box, no error" investigation). This codebase has no logging service/convention
 * (grep-verified before adding this) — plain `console.*` with a stable prefix, mirroring the one
 * `[fly]` uses in `features/fly/fly.ts`. Kept deliberately terse: one line per state transition, not a
 * trace of every tick, so a real incident's console isn't itself a wall of noise.
 */
const LOG_PREFIX = '[player]';

const LATENCY_SAMPLE_MS = 1_000;
const OVERLAY_REDRAW_MS = 200;
const WATCHDOG_TICK_MS = 2_000;

/**
 * Cap on consecutive `hls.recoverMediaError()` calls within one attach lifetime, reset the moment
 * a fragment actually buffers (real progress). **Load-bearing, not a nicety**: hls.js's own error
 * model expects the caller to bound this — a persistently broken source (the underlying stream
 * never actually produces valid media, e.g. a dead upstream RTSP feed that never publishes a
 * frame) can make `recoverMediaError()` immediately re-trigger another fatal `MEDIA_ERROR`, and
 * calling it again unconditionally forever turns into a tight, unyielding recovery cycle — each
 * cycle cheap alone, but with no cap the browser tab pins a CPU core and its memory climbs without
 * bound, which reads as "the tab is frozen", not "the video won't play" (confirmed live: opening
 * `/fly` against an asset whose video source never produces frames hung the whole tab, not just
 * the player). Once the cap is hit, this degrades to the exact same timer-based, capped-backoff
 * `scheduleReconnect` path every other fatal error already takes — never a silent, ever-tighter
 * spin.
 */
const MEDIA_ERROR_RECOVERY_LIMIT = 3;

/** How long a WHEP attach waits for `ontrack` after the answer is applied — see class doc. */
const WHEP_NO_TRACK_TIMEOUT_MS = 6_000;

/** Bounded wait for ICE gathering before POSTing the offer — see `waitForIceGatheringComplete`. */
const ICE_GATHERING_TIMEOUT_MS = 3_000;

/**
 * hls.js live-edge tuning (docs/plans/done/MVP2-PLAN.md §V — V-a shrinks segment/part duration server-side,
 * V-b is this file's own half). Every value below is a *live* setting; VOD robustness (deep
 * back-buffers for scrubbing, generous stall tolerance) is deliberately out of scope — this player
 * only ever shows a live glass-to-glass feed, never something a viewer seeks around in.
 *
 *  - `HLS_LOW_LATENCY_MODE` (unchanged from before this cycle): turns on hls.js's own LL-HLS
 *    handling — when the playlist advertises `#EXT-X-PART-INF`/`PART-HOLD-BACK` (V-a's mediamtx
 *    `lowLatency` variant, once merged on a given deployment), hls.js starts/resyncs at the
 *    *partial*-segment hold-back position instead of the whole-segment one, which is most of the
 *    latency win. It also gates `HLS_MAX_LIVE_SYNC_PLAYBACK_RATE` below. Against a stock playlist
 *    with no LL tags, hls.js simply finds none and everything here still applies unchanged — this
 *    player works identically whether V-a has landed on a given deployment or not.
 *  - `HLS_LIVE_SYNC_DURATION_COUNT` / `HLS_LIVE_MAX_LATENCY_DURATION_COUNT`: **segment-count**,
 *    not duration-based (hls.js's `liveSyncDuration`/`liveMaxLatencyDuration` — mixing the two
 *    families is a config error hls.js throws on) — V-a shrinks segment duration (legacy ~6s →
 *    LL-HLS parts ~200ms) as part of this very latency push, and a fixed-seconds target would need
 *    re-tuning every time that number changes on the backend; a segment-count target scales with
 *    whatever the currently-active playlist actually serves, on either side of that landing.
 *    hls.js's own defaults are `3` / `Infinity` — the `Infinity` default means hls.js does **not**
 *    forcibly resync a playhead that has drifted behind, at all, unless this is set to a finite
 *    number. `2` synced segments back from the edge (down from `3`), forcibly reseeking to
 *    `hls.liveSyncPosition` the moment playback is `4` segments behind (hls.js's own
 *    `LatencyController#synchronizeToLiveEdge`, run on every playlist refresh) — tight enough to
 *    track live, loose enough that one slow segment fetch doesn't trigger a reseek storm. Must
 *    stay `HLS_LIVE_MAX_LATENCY_DURATION_COUNT > HLS_LIVE_SYNC_DURATION_COUNT`; hls.js throws a
 *    config error otherwise.
 *  - `HLS_MAX_LIVE_SYNC_PLAYBACK_RATE`: hls.js's own gentle *rate*-based catch-up — while behind
 *    but still within the live sync window, hls.js nudges `video.playbackRate` above `1` (capped
 *    at this value, itself capped at `2` internally) to close the gap without a visible seek jump.
 *    Only active while `HLS_LOW_LATENCY_MODE` is on (any live playlist, not only an LL one) and the
 *    default `1` disables it outright — this player set `lowLatencyMode` before this cycle but
 *    never raised this, so the catch-up mechanism has been silently inert until now. `1.5` is
 *    `Hls.js`'s own doc-recommended figure for "close enough to unnoticeable".
 *  - `HLS_MAX_BUFFER_LENGTH_SECONDS`: hls.js's forward-buffer target, bounded to `10`s (down from
 *    hls.js's own `30`s default, sized for VOD scrubbing headroom this player never needs) — a
 *    live tile has no reason to hold half a minute of look-ahead buffer it will never be seeked
 *    into, and a smaller target means less stale content to walk back through after a stall.
 *  - `HLS_BACK_BUFFER_LENGTH_SECONDS`: unchanged from before this cycle (`30`), just promoted to a
 *    named constant for consistency with the others — how much *already-played* buffer hls.js
 *    keeps around, unrelated to live-edge distance.
 */
const HLS_LOW_LATENCY_MODE = true;
const HLS_LIVE_SYNC_DURATION_COUNT = 2;
const HLS_LIVE_MAX_LATENCY_DURATION_COUNT = 4;
const HLS_MAX_LIVE_SYNC_PLAYBACK_RATE = 1.5;
const HLS_MAX_BUFFER_LENGTH_SECONDS = 2;
const HLS_BACK_BUFFER_LENGTH_SECONDS = 5;

/** `document.hidden`, guarded for an environment with no `document` (none this app ships to, today). */
function isDocumentHidden(): boolean {
  return typeof document !== 'undefined' && document.hidden;
}

interface DrawnBox {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
  readonly detection: Detection;
}

/**
 * Self-recovering video player, WebRTC(WHEP)-first with an automatic HLS fallback
 * (docs/plans/done/MVP2-PLAN.md §L / §U3), with an honest status line (docs/main/CYCLES-PLAN.md §11, CD-b item 5)
 * — shared as-is by `wall-tile.ts`, `live.html`, `features/asset-detail/asset-detail.html`, and
 * `shared/map/live-dock.ts`, so every one of those surfaces gets everything below for free.
 *
 * Three deliberate choices, all from docs/main/UX-DESIGN.md §2 T1 plus CD-b's own resilience ask:
 *
 *  - `hls.js` is imported dynamically, so its ~90 KB lands in its own chunk and never
 *    touches the initial bundle of a user who only visits Devices. WHEP needs no such import —
 *    `RTCPeerConnection`/`fetch` are browser built-ins — and this component itself only ever
 *    reaches a lazy route chunk (never `providedIn: 'root'`), so the WHEP code adds nothing eager.
 *  - The distance behind the live edge is measured and displayed continuously, in a small,
 *    always-on chrome badge (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4): `"HLS ~6s"` / `"WebRTC 0.4s"` / `"—"`
 *    while unmeasured — a user told "≈6 s behind live" understands the trade, while a user shown a
 *    spinner concludes the app is broken. WHEP's own badge figure is a `getStats()`-derived estimate
 *    (half the measured round-trip time plus jitter, `player-recovery.ts#estimateWhepLatencySeconds`
 *    — see `refreshWhepStatsProgress`), kept deliberately separate from `behindLive`, which stays
 *    pinned to `0` the moment a track arrives (a real, meaningful number, not `null`, for a
 *    different consumer: the detection-overlay sync matcher,
 *    `detection-overlay-logic.ts#selectDetectionResult`, needs an actual latency estimate to pick
 *    the right batch, and `0` is what "near-zero" means there).
 *  - The player never gives up. `shared/player/player-recovery.ts`'s pure state machine drives every
 *    transition; a fatal hls.js error or a silent stall (the watchdog: no fragment progress for
 *    `STALL_WATCHDOG_MS`) destroys and reattaches with capped exponential backoff, indefinitely,
 *    for as long as `src`/`whepUrl` stay set — including across a backend restart. `error` is
 *    reserved for a genuinely unrecoverable case (this browser can play neither transport at all).
 *
 * "Waiting for the first segment" is a first-class state rather than an error, because it
 * is the normal condition for the first few seconds of every new stream.
 *
 * **WHEP-first, HLS-fallback** (docs/plans/done/MVP2-PLAN.md §L / §U3): when the optional `whepUrl` input is
 * set, the player POSTs an SDP offer straight to it (mediamtx's own absolute origin URL — never
 * proxied, never app-relative, see `core/api/models.ts#ActiveStream.whepUrl`'s doc comment),
 * applies the SDP answer, and waits for a track. Any failure before a track ever arrives (a POST
 * error, or no track within `WHEP_NO_TRACK_TIMEOUT_MS`) falls back to the exact same HLS attach path
 * `src` has always used — this fallback is **load-bearing**, not a rare edge case: docs/plans/done/MVP2-PLAN.md
 * §L's own documented caveat is that a dockerized mediamtx advertises `127.0.0.1` ICE candidates, so
 * *every* LAN viewer's WHEP attempt is expected to fail and land here silently. Once a transport has
 * ever reached `playing`, a later failure no longer means "re-decide transport from scratch" the way
 * a pre-play one does — see below for what it means instead.
 *
 * **Connect once, ICE-restart first** (docs/plans/done/REALTIME-PLAN.md Phase R-b item 1) — once WHEP has ever
 * reached `playing`, `connectionState==='disconnected'` starts a 5s grace timer
 * (`player-recovery.ts#ICE_RESTART_GRACE_MS`) rather than tearing anything down; `'failed'` (or the
 * grace timer expiring without self-healing) triggers `restartIce()` + a fresh offer, sent as a WHEP
 * **PATCH** to the session URL captured from the original POST's `Location` header
 * (`application/trickle-ice-sdpfrag`, `If-Match: "*"` — verified against mediamtx `v1.19.2`'s own
 * source, `internal/servers/webrtc/session.go`/`http_server.go`; see `shared/player/webrtc-ice-restart.ts`'s
 * own module doc for the byte-for-byte mapping). The *same* `RTCPeerConnection` is reused throughout
 * — media keeps flowing, the visible chip never leaves `'playing'` for an in-place restart, unlike a
 * full teardown+reattach. The R-a stall watchdog (`getStats()`-confirmed no frame progress) triggers
 * the identical ICE-restart-first flow, not an immediate teardown, once a transport has played
 * before. Only when the PATCH itself is rejected (a non-2xx status, `404`/`412` explicitly, or the
 * restart mechanism being unavailable at all) does this fall through to the pre-existing full
 * teardown+re-POST/HLS-fallback path below — R-b's own "last resort", unchanged from before this
 * cycle. A pre-play failure (WHEP never having reached `playing` at all this attach) skips ICE
 * restart entirely and falls straight to HLS, exactly as it always has — restarting a connection
 * that was never actually live isn't meaningful.
 *
 * **Genuine teardown honors the session resource** (item 2): the WHEP POST's `Location` header
 * (`whepSessionUrl`) is `DELETE`d — fire-and-forget, `keepalive: true` — whenever this player
 * actually lets go of a session (a fresh `src`/`whepUrl`/`stopped` value, component destroy, or a
 * `pagehide`), never for an in-place ICE restart. `keepalive: true`, not `navigator.sendBeacon`,
 * since `sendBeacon` can only ever issue a `POST` and mediamtx's session resource only accepts a
 * real HTTP `DELETE` verb (verified in `http_server.go`).
 *
 * **Stable DTLS fingerprint** (item 3): every `RTCPeerConnection` this player creates is constructed
 * with the browser's one persisted ECDSA certificate (`core/webrtc-certificate.ts`, IndexedDB-backed,
 * shared across every `<vision-player>` tile) via the `certificates` option — a stable fingerprint
 * across every tile and every reconnect, for server-side viewer correlation, and one fewer ECDSA
 * keygen per attach.
 *
 * The visible latency badge (`'WebRTC 0.4s'` / `'HLS ~6s'` / `'—'` while unmeasured — see
 * `live-edge-logic.ts#transportLatencyLabel`) always names whichever transport is actually
 * attached, and `transportChanged` emits it too, for a host that wants to echo it (e.g.
 * `shared/player/stream-info-panel.ts`'s "Transport" fact).
 *
 * **Detection overlay** (docs/main/CYCLES-PLAN.md §11 item 6): an optional `detections`/`boxesMode`
 * input pair draws a canvas overlay of the freshest detection batch matched against this player's
 * own measured live-edge latency (`shared/player/detection-overlay-logic.ts#selectDetectionResult`) — crisp
 * at any video bitrate, and hoverable (label + confidence), unlike the server's burned-in boxes
 * (which stay; this is additive, see that module's doc comment on `'burned'`/`'off'`). Callers
 * that never pass `detections` simply never see the canvas draw anything.
 *
 * **Always a dark video surface, wherever it's mounted** (docs/plans/done/VISUAL-REFRESH-PLAN.md F3/W4): the
 * `.frame` host carries `.surface-dark` itself rather than depending on an ambient enclave, because
 * this component is reused far outside the three pages that own their own enclave root (`Fly`
 * cockpit, `Wall`) — `features/live`, `features/command/asset-panel`, and any future host all mount
 * `<vision-player>` directly on a themed (light-by-default) page. `.frame`'s own idle/error
 * placeholder (`.overlay-status`) reads `--bg`/`--text-muted` — safe only when those resolve to
 * their dark values, which was true unconditionally back when the app had one, always-dark theme,
 * and is true again now only because `.frame` forces it. Every other bit of chrome in this
 * component (`.badge`, `.box-tooltip`, `.model-legend`) already used the theme-invariant
 * `--scrim`/`--hud-*` compositing tokens, so only the placeholder needed this; self-scoping it here
 * closes that gap once for every host instead of requiring each one to remember to wrap its own
 * `<vision-player>`. Redundant-but-harmless inside `Fly`/`Wall`, which already provide it.
 */
@Component({
  selector: 'vision-player',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './player.html',
  styleUrl: './player.css',
})
export class Player {
  /** HLS playlist URL, or `null` when nothing is streaming — also the WHEP fallback target. */
  readonly src = input<string | null>(null);

  /** Mediamtx's own absolute WHEP origin URL (docs/plans/done/MVP2-PLAN.md §L), or `null`/absent for HLS-only. */
  readonly whepUrl = input<string | null>(null);

  /** Wall tiles set this when scrolled out of view so off-screen video stops decoding. */
  readonly suspended = input(false);

  /**
   * The host page knows this stream was deliberately stopped (docs/plans/done/MVP2-PLAN.md §S, S-b) — its own
   * Stop action, or (a host reading it off `FleetStore`/an equivalent store) the streams list no
   * longer naming this device. Renders a calm "Stream stopped" state and — the actual fix for the
   * diagnosed freeze — makes the reattach effect below a no-op regardless of `src`/`whepUrl`, so a
   * stray/late signal (the backend still tearing down, or briefly re-listing the stream) can never
   * restart a reconnect attempt against a stream the user just asked to end. Absorbing per
   * `player-recovery.ts#reduceRecovery`'s own doc comment: only a fresh `src`/`whepUrl` while this
   * is `false` re-attaches.
   */
  readonly stopped = input(false);

  /** Compact tiles hide native controls and the caption. */
  readonly compact = input(false);

  /** Recent detection batches (newest first) to draw as a vector overlay — see class doc. */
  readonly detections = input<readonly DetectionResult[]>([]);

  /** `'overlay'` (draw boxes), `'burned'`/`'off'` (draw nothing — see `shouldDrawOverlay`'s doc).
   * Defaults to `'burned'` (per direct user request) for a caller that never binds this input at
   * all — every page that offers a boxes-mode control of its own (Fly/Live/Wall) seeds its own
   * signal to `'burned'` too, so this default only matters for the callers that don't (asset-detail,
   * Command's asset panel, replay). */
  readonly boxesMode = input<BoxesMode>('burned');

  /** Emits the measured seconds-behind-live on every sample, `null` while unknown/not playing. */
  readonly latencyChanged = output<number | null>();

  /** Emits the live transport whenever it changes — see class doc's "WHEP-first, HLS-fallback". */
  readonly transportChanged = output<Transport>();

  /**
   * Click-to-follow (docs/plans/done/TRACKING-PLAN.md §4.D, wave T7) — emits a track id when the operator
   * clicks a **tracked** box in the overlay (an untracked box has no id to lock onto, and is
   * deliberately a no-op click — the point/box lock forms the wire contract also allows are out of
   * this wave's scope). The host (`CockpitFacade#followTrack`) is the one that actually PATCHes
   * `{tracking:{mode:'FOLLOW', lock:{trackId}}}` — this component never calls `VisionApi`/`FleetStore`
   * itself, same "dumb component, host owns the write" rule as `latencyChanged`/`transportChanged`
   * above. No optimistic UI here either: nothing in this component claims the lock took until a host
   * that reads it back (`cv-control-panel.ts`'s own tracks poll) says so — see that class's own doc
   * comment for docs/extracts/TRACKING-ORCHESTRATION.md §3.3's honesty rule.
   */
  readonly trackFollowed = output<number>();

  private readonly video = viewChild.required<ElementRef<HTMLVideoElement>>('video');
  private readonly overlayCanvas = viewChild.required<ElementRef<HTMLCanvasElement>>('overlay');

  private readonly transportState = signal<TransportRecoveryState>(initialTransportState(false));
  protected readonly phase = computed<PlayerPhase>(() => this.transportState().recovery.phase);
  protected readonly transport = computed<Transport>(() => this.transportState().transport);
  protected readonly message = signal<string | null>(null);

  /**
   * Cross-cycle reconnect pacing (docs/plans/done/MVP2-PLAN.md §S, S-c) — `player-recovery.ts#PacingState`'s
   * own doc comment has the full rules; this signal is the one piece of state that survives a
   * WHEP→HLS fallback and every subsequent cycle restart, driving the *actual* delay/WHEP-retry
   * decisions below. `transportState` above is untouched and still drives `phase`/the chip.
   */
  private readonly pacingState = signal<PacingState>(INITIAL_PACING_STATE);

  /**
   * Dispatches a `RecoveryEvent` through `reduceTransportRecovery` and logs the resulting
   * transition — the NgRx/Redux idiom this file's two reducers (`reduceRecovery`/
   * `reduceTransportRecovery` in `player-recovery.ts`, already pure `(state, action) => state`
   * functions) were missing: every other piece of this class already *computes* the next state
   * correctly, but nothing surfaced *which action fired and what actually changed* — exactly the
   * transparency gap that made the hard-freeze bug (see the reattach effect's own doc comment
   * above) invisible until this incident's CDP profiling. Every call site in this file that used
   * to write `this.transportState.set(reduceTransportRecovery(this.transportState(), event))`
   * directly now goes through here instead, so the console's action log is complete, not
   * best-effort at a few hand-picked spots. A no-op transition (`next === prev`, e.g. `'stopped'`
   * dispatched twice in a row) logs nothing — only genuine phase/transport changes are noise
   * worth seeing.
   */
  private dispatchRecovery(event: RecoveryEvent): TransportRecoveryState {
    const prev = this.transportState();
    const next = reduceTransportRecovery(prev, event);
    if (next !== prev) {
      console.info(
        `${LOG_PREFIX} [action] ${event} → phase ${prev.recovery.phase}→${next.recovery.phase}` +
          (next.transport !== prev.transport
            ? `, transport ${prev.transport}→${next.transport}`
            : ''),
      );
    }
    this.transportState.set(next);
    return next;
  }

  /** The `PacingState` analog of `dispatchRecovery` above — same reasoning, same log shape. */
  private dispatchPacing(event: PacingEvent, nowMs: number): PacingState {
    const prev = this.pacingState();
    const next = advancePacing(prev, event, nowMs);
    if (next !== prev) {
      console.info(
        `${LOG_PREFIX} [action] pacing/${event} → cycleAttempt ${prev.cycleAttempt}→${next.cycleAttempt}`,
      );
    }
    this.pacingState.set(next);
    return next;
  }

  /** The `WhepIceState` analog of `dispatchRecovery`/`dispatchPacing` above (docs/plans/done/REALTIME-PLAN.md Phase R-b item 1) — same reasoning, same log shape. Not a signal: nothing in the template reads it, it's pure internal watchdog/connectionstatechange bookkeeping, same as `lastFrameAt`/`mediaErrorRecoveryCount`. */
  private dispatchWhepIce(event: WhepIceEvent): WhepIceState {
    const prev = this.whepIceState;
    const next = reduceWhepIce(prev, event);
    if (next !== prev) {
      console.info(`${LOG_PREFIX} [action] whepIce/${event} → phase ${prev.phase}→${next.phase}`);
    }
    this.whepIceState = next;
    return next;
  }

  /**
   * The "reconnecting" state line's cause + action (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4 item 2 —
   * "Feed unreachable — retrying in Ns (attempt k)") — projects the existing recovery/pacing state
   * into words rather than inventing a new one: `cyclePacingDelayMs` (`player-recovery.ts`) is the
   * *exact* delay `scheduleReconnect`/`scheduleColdStartRetry`/`handleWhepFailure` already computed
   * and used to arm the pending retry timer (each dispatches `'cycleFailed'` — updating
   * `pacingState` — *before* scheduling it), so this reads the real countdown, not a guess. Reads
   * the saga-wide pacing counter, not `transportState().recovery.attempt` (which resets on every
   * WHEP→HLS fallback) — see `PacingState`'s doc comment for why the two differ on purpose.
   */
  protected readonly reconnectHint = computed(() => {
    const pacing = this.pacingState();
    const delaySeconds = Math.max(1, Math.round(cyclePacingDelayMs(pacing) / 1000));
    return `Retrying in ${delaySeconds}s (attempt ${pacing.cycleAttempt}).`;
  });

  /**
   * Seconds behind the live edge, or `null` while unknown. Pinned to `0` for a live WHEP track —
   * this is the number the detection-overlay sync matcher needs (see class doc), *not* the latency
   * badge's own WebRTC figure below; kept separate on purpose so that invariant never has to share a
   * signal with a display-only estimate.
   */
  private readonly behindLive = signal<number | null>(null);

  /**
   * The latency badge's WebRTC figure (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4) — `null` until the first
   * `getStats()` tick that reports a round-trip time (`estimateWhepLatencySeconds`,
   * `player-recovery.ts`), refreshed on the same `WATCHDOG_TICK_MS` cadence as the stall watchdog's
   * own poll (`refreshWhepStatsProgress`). Purely a display concern — never read by the overlay sync
   * matcher or the snap-to-live logic, both of which keep using `behindLive` above exactly as before.
   */
  private readonly whepLatencySeconds = signal<number | null>(null);

  /** The player chrome's latency badge text — see `live-edge-logic.ts#transportLatencyLabel`'s own doc comment for the exact wording rules per transport. */
  protected readonly latencyLabel = computed(() =>
    transportLatencyLabel(this.transport(), this.behindLive(), this.whepLatencySeconds()),
  );

  protected readonly latencyTitle = computed(() =>
    this.transport() === 'webrtc'
      ? "Estimated one-way delay from the WebRTC (WHEP) connection's own measured round-trip " +
        'time and jitter buffer (getStats()) — not a fixed number.'
      : 'Distance behind the live edge, measured continuously from the HLS buffer position.',
  );

  protected readonly hoveredDetection = signal<Detection | null>(null);
  /** The hover tooltip's own text — `formatDetectionLabel` so the tooltip and the canvas-drawn box
   * label always agree on whether a box is carrying a track id (docs/plans/done/TRACKING-PLAN.md §10). */
  protected readonly hoveredLabel = computed(() => {
    const hovered = this.hoveredDetection();
    return hovered ? formatDetectionLabel(hovered) : '';
  });
  protected readonly tooltipX = signal(0);
  protected readonly tooltipY = signal(0);
  private drawnBoxes: readonly DrawnBox[] = [];

  protected readonly overlayInteractive = computed(
    () =>
      shouldDrawOverlay(this.boxesMode(), this.detections().length > 0) &&
      this.phase() === 'playing',
  );

  /**
   * The batch the canvas overlay is currently drawing — the same `selectDetectionResult` pick
   * `redrawOverlay` uses, mirrored here as a `computed` purely so the legend chip below has a
   * reactive read of "what's on screen right now" without duplicating the sync logic. Recomputes
   * whenever `detections()`/`boxesMode()`/`phase()`/`behindLive()` change; a legend a redraw-cycle
   * stale by a frame or two (this doesn't tick on the overlay's own `OVERLAY_REDRAW_MS` timer) is a
   * non-issue for a chip that only ever says "which models are present", not exact box positions.
   */
  private readonly overlayResult = computed<DetectionResult | undefined>(() =>
    shouldDrawOverlay(this.boxesMode(), this.detections().length > 0) && this.phase() === 'playing'
      ? selectDetectionResult(this.detections(), Date.now(), this.behindLive(), DEFAULT_SLACK_BATCHES)
      : undefined,
  );

  /** docs/plans/done/OPS-CORE-PLAN.md §Q3b: every distinct model key in the batch currently on screen. */
  protected readonly overlayModelKeys = computed(() => distinctModelKeys(this.overlayResult()?.detections ?? []));
  /** The legend chip only earns its place once ≥2 models actually mix in the same frame. */
  protected readonly showModelLegend = computed(() => this.overlayModelKeys().length >= 2);

  /** The legend swatch's own color — same `modelHue` the canvas boxes are stroked with. */
  protected modelSwatch(modelKey: string): string {
    return modelHue(modelKey);
  }

  private hls: HlsType | null = null;
  private latencyTimer: ReturnType<typeof setInterval> | null = null;
  private coldStartTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private watchdogTimer: ReturnType<typeof setInterval> | null = null;
  private overlayTimer: ReturnType<typeof setInterval> | null = null;
  private mediaAbort: AbortController | null = null;
  /** Consecutive `recoverMediaError()` calls since the last real progress — see `MEDIA_ERROR_RECOVERY_LIMIT`. */
  private mediaErrorRecoveryCount = 0;
  private lastProgressAt = 0;
  private currentSrc: string | null = null;
  private generation = 0;
  /**
   * The `src`/`whepUrl`/`suspended`/`stopped` tuple (`player-recovery.ts#attachKey`) the reattach
   * effect last actually acted on — see its own doc comment. Also what keeps a secondary tile's
   * `<vision-player>` from tearing down on every poll re-render (docs/plans/done/REALTIME-PLAN.md Phase R-a
   * item 4) — see `attachKey`'s own doc comment for why this holds regardless of tile
   * reorder/resize.
   */
  private lastAttachKey: string | null = null;
  /** `document.hidden` as of the latency timer's last tick — see `startLatencySampling`'s doc. */
  private wasDocumentHidden = false;

  // --- WHEP (docs/plans/done/MVP2-PLAN.md §L / §U3) ---------------------------------------------------------
  private peerConnection: RTCPeerConnection | null = null;
  private whepAbort: AbortController | null = null;
  private whepWatchdogTimer: ReturnType<typeof setInterval> | null = null;
  private whepNoTrackTimer: ReturnType<typeof setTimeout> | null = null;
  /**
   * Whether `ontrack` has already fired for the current attach — reset `false` at the top of every
   * `beginWhepAttach`. **The actual fix for a live-soak-confirmed race** (docs/plans/done/REALTIME-PLAN.md
   * Phase R-b follow-up): on a fast/loopback connection, `pc.ontrack` can fire *before*
   * `scheduleWhepNoTrackTimeout`'s own call site runs (it fires as part of applying the remote
   * description, ahead of the `await`-continuation that used to call that method afterward) — a
   * timer armed once the track has already arrived has nothing left to ever clear it, so it fired
   * unconditionally `WHEP_NO_TRACK_TIMEOUT_MS` later against a perfectly healthy stream, every
   * single attach, confirmed at ~90 reproductions with near-zero jitter. `player-recovery.ts#
   * isWhepStillWaitingForTrack` is the pure predicate both `scheduleWhepNoTrackTimeout` (don't even
   * arm) and the timer's own fired callback (don't act on a stale fire) check against this flag —
   * reordering the call site earlier (see `beginWhepAttach`) only narrows the race window, it
   * doesn't close it on its own, since nothing stops `ontrack` firing even earlier still.
   */
  private whepTrackArrived = false;
  private lastFrameAt = 0;
  private currentWhepUrl: string | null = null;
  /** Previous tick's `getStats()` snapshot (docs/plans/done/REALTIME-PLAN.md Phase R-a item 1) — `null` until the first tick that finds one. */
  private whepStatsSnapshot: WhepStatsSnapshot | null = null;
  /** Last logged advance/stall state, so the watchdog logs only on a *transition* — see `refreshWhepStatsProgress`. */
  private whepStatsAdvancing: boolean | null = null;

  // --- WHEP session lifecycle + ICE-restart-first recovery (docs/plans/done/REALTIME-PLAN.md Phase R-b) -----
  private readonly certificateService = inject(WebrtcCertificateService);
  /** The WHEP POST's `Location` header, resolved to an absolute URL (item 2) — `null` until captured, and again once genuinely torn down. The PATCH/DELETE target for everything below. */
  private whepSessionUrl: string | null = null;
  /** Grace-timer/restart bookkeeping (`player-recovery.ts#WhepIceState`, item 1) — see `dispatchWhepIce`. */
  private whepIceState: WhepIceState = INITIAL_WHEP_ICE_STATE;
  private iceRestartGraceTimer: ReturnType<typeof setTimeout> | null = null;
  /** Re-entrancy guard: the `connectionState` handler and the stall watchdog can both notice trouble on the same tick — only one `attemptIceRestart` runs at a time. */
  private restartInFlight = false;

  constructor() {
    effect(() => {
      const src = this.src();
      const whepUrl = this.whepUrl();
      const suspended = this.suspended();
      const stopped = this.stopped();
      const key = attachKey(src, whepUrl, suspended, stopped);
      if (key === this.lastAttachKey) {
        // **The hard-freeze fix (distinct from the `stopped`-state freeze fix below — see that
        // one's own doc comment for the *other* incident it closed).** Confirmed live
        // (CDP-profiled against `/live/:deviceId`, both a genuinely healthy stream and a dead
        // one): this effect can be re-run by Angular's own change-detection/effect-flush
        // machinery with every tracked input signal reading back byte-for-byte identical to last
        // time — no `src`, `whepUrl`, `suspended`, or `stopped` actually changed (verified with
        // instance/run counters logged during the investigation: the same `Player` instance, same
        // values, run after run, hundreds of times a second, indefinitely). `reattach()` itself
        // has no reason to distrust that and redoes the *entire* attach from scratch every time
        // (`teardown()` — destroying any in-flight `RTCPeerConnection`/`Hls` — then a brand new
        // WHEP POST or HLS load): each cycle alone is cheap, but at hundreds of cycles a second
        // the tab's main thread never gets a spare tick for anything else, including its own
        // DevTools/CDP protocol handling — which is the literal mechanism behind "the whole tab
        // hard-freezes" (both `/fly` and `/live/:deviceId` exhibit it; not a WHEP/HLS/mediamtx
        // problem, and not specific to a broken source). This equality check makes the effect
        // idempotent against a spurious re-notification with no value change — `reattach()`'s own
        // `generation` counter already guards *stale* async work landing late, but nothing
        // previously guarded against *starting* redundant work in the first place.
        console.info(`${LOG_PREFIX} reattach effect re-notified with unchanged inputs — skipping`);
        return;
      }
      this.lastAttachKey = key;
      // Read inputs before the async teardown so the effect tracks them.
      void this.reattach(src, whepUrl, suspended, stopped);
    });

    effect(() => {
      this.latencyChanged.emit(this.behindLive());
    });

    effect(() => {
      this.transportChanged.emit(this.transport());
    });

    // Redraws follow phase/mode/detections changes; a low-frequency timer (started/stopped
    // alongside playback, see `beginAttach`/`teardownMedia`) covers the continuous drift of
    // "on-screen instant" between polls even when nothing else changed.
    effect(() => {
      this.boxesMode();
      this.detections();
      this.phase();
      this.redrawOverlay();
    });

    // `pagehide` — not `beforeunload`/`unload` — is the reliable signal a real tab close/navigation
    // is happening (docs/plans/done/REALTIME-PLAN.md Phase R-b item 2): `DestroyRef.onDestroy` below still
    // covers a normal Angular route-leave/component-removal teardown, but a genuine browser tab
    // close doesn't reliably run Angular's own destroy hooks in time. `sendWhepSessionDelete` itself
    // nulls out `whepSessionUrl` once fired, so if `teardown()` *also* runs (ordinary route leave)
    // it finds nothing left to delete a second time.
    const onPageHide = () => {
      if (this.whepSessionUrl !== null) {
        this.sendWhepSessionDelete(this.whepSessionUrl);
        this.whepSessionUrl = null;
      }
    };
    window.addEventListener('pagehide', onPageHide);

    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('pagehide', onPageHide);
      this.teardown();
    });
  }

  /** Cancels any pending backoff wait and retries immediately — visible only while reconnecting. */
  protected retryNow(): void {
    this.clearReconnectTimer();
    const generation = this.generation;
    if (this.transport() === 'webrtc' && this.currentWhepUrl !== null) {
      void this.beginWhepAttach(generation, this.currentWhepUrl, this.currentSrc);
    } else if (this.currentSrc !== null) {
      void this.beginAttach(generation, this.currentSrc);
    }
  }

  /**
   * Cancels whatever reconnect backoff is currently pending, if any — called at the top of every
   * place that's about to set a new one (docs/plans/done/MVP2-PLAN.md §S, S-b bug fix). Before this, each of
   * `scheduleReconnect`/`handleWhepFailure`'s retry branch assigned `this.reconnectTimer` directly,
   * silently **orphaning** whatever the field previously held: a second failure signal arriving
   * before the first backoff wait elapsed (hls.js can emit more than one fatal `ERROR` for the same
   * dead playlist as its own internal retry budget exhausts; the stall watchdog re-detects the same
   * ongoing stall every `WATCHDOG_TICK_MS` once a long backoff wait is in progress; WHEP's ICE state
   * can flip through `disconnected` then `failed` in quick succession) would leave the orphaned
   * timer alive, still armed to fire `beginAttach`/`beginWhepAttach` **on its own, now-stale,
   * shorter schedule** — a premature, redundant reattach that opens a fresh `Hls`/
   * `RTCPeerConnection` and issues a fresh network request completely out of step with the capped
   * backoff the state chip claims to be honoring. Under a persistently failing/flapping stream this
   * compounds — each premature reattach can itself fail immediately, scheduling yet another
   * un-cleared timer — which is the mechanism observed live behind the stop-freeze bug report (a
   * tight, repeating WHEP-POST/GET-devices/GET-streams cycle in the network panel).
   */
  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private async reattach(
    src: string | null,
    whepUrl: string | null,
    suspended: boolean,
    stopped: boolean,
  ): Promise<void> {
    const generation = ++this.generation;
    this.teardown();
    this.currentSrc = null;
    this.currentWhepUrl = null;

    // Checked first, ahead of `src`/`whepUrl`: a deliberately stopped stream must never attach,
    // even if the host is still feeding a (possibly stale/flapping) `src`/`whepUrl` — see the
    // `stopped` input's own doc comment for why this is the actual freeze fix.
    if (stopped) {
      console.info(`${LOG_PREFIX} stopped — not attaching`);
      this.dispatchRecovery('stopped');
      return;
    }

    if ((!src && !whepUrl) || suspended) {
      console.info(`${LOG_PREFIX} nothing to attach`, {
        hasSrc: !!src,
        hasWhepUrl: !!whepUrl,
        suspended,
      });
      this.dispatchRecovery('reset');
      return;
    }

    this.currentSrc = src;
    this.currentWhepUrl = whepUrl;
    // A genuine fresh attach (a new `src`/`whepUrl`) starts a brand new reconnect saga (docs/MVP2-
    // PLAN.md §S, S-c) — mirrors `transportState` being re-derived from scratch just below, rather
    // than carrying over whatever pacing a *previous* src/whepUrl's saga had accumulated.
    this.pacingState.set(INITIAL_PACING_STATE);
    const usesWhep = whepUrl !== null && shouldAttemptWhep(this.pacingState(), true, Date.now());
    this.transportState.set(
      reduceTransportRecovery(initialTransportState(usesWhep), 'attachStarted'),
    );
    console.info(`${LOG_PREFIX} source selected: ${usesWhep ? 'WHEP' : 'HLS'}`, {
      chosenUrl: usesWhep ? whepUrl : src,
      whepUrl,
      hlsUrl: src,
    });

    if (usesWhep && whepUrl) {
      this.dispatchPacing('whepAttempted', Date.now());
      await this.beginWhepAttach(generation, whepUrl, src);
    } else if (src) {
      await this.beginAttach(generation, src);
    }
  }

  /**
   * Starts the next reconnect cycle (docs/plans/done/MVP2-PLAN.md §S, S-c): tries WHEP again if this saga's
   * damping cooldown allows it (`shouldAttemptWhep`), otherwise goes straight to HLS — the fresh-
   * `RecoveryState`/chip-phase reset here exactly mirrors `reattach()`'s own "fresh attach" branch
   * above, just without resetting `pacingState` (a new *cycle*, not a new *saga*). Every scheduled
   * retry (`scheduleColdStartRetry`'s escalating branch, `scheduleReconnect`) calls this instead of
   * reattaching a fixed transport directly, so a saga that has fallen back to HLS gets a fair chance
   * to re-try WHEP on a later cycle, not just once per component lifetime.
   */
  private beginNextCycle(generation: number): void {
    if (generation !== this.generation || this.currentSrc === null) {
      return;
    }
    const now = Date.now();
    const tryWhep =
      this.currentWhepUrl !== null && shouldAttemptWhep(this.pacingState(), true, now);
    this.transportState.set(
      reduceTransportRecovery(initialTransportState(tryWhep), 'attachStarted'),
    );
    console.info(
      `${LOG_PREFIX} [action] attachStarted (new cycle) → transport ${tryWhep ? 'webrtc' : 'hls'}`,
    );

    if (tryWhep && this.currentWhepUrl !== null) {
      this.dispatchPacing('whepAttempted', now);
      void this.beginWhepAttach(generation, this.currentWhepUrl, this.currentSrc);
    } else {
      void this.beginAttach(generation, this.currentSrc);
    }
  }

  private async beginAttach(generation: number, src: string): Promise<void> {
    if (generation !== this.generation) {
      return;
    }
    this.teardownMedia();
    this.lastProgressAt = Date.now();
    this.startWatchdog(generation);

    const video = this.video().nativeElement;

    // Safari and iOS play HLS natively; loading hls.js there would be pure overhead.
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src;
      this.watchNativePlayback(generation, video);
      return;
    }

    const { default: Hls } = await import('hls.js');
    if (generation !== this.generation) {
      return; // src changed / component destroyed while the chunk was loading
    }
    if (!Hls.isSupported()) {
      console.error(`${LOG_PREFIX} this browser cannot play HLS (hls.js reports unsupported)`);
      this.dispatchRecovery('unsupported');
      this.message.set('This browser cannot play HLS.');
      return;
    }

    const hls = new Hls({
      lowLatencyMode: HLS_LOW_LATENCY_MODE,
      liveSyncDurationCount: HLS_LIVE_SYNC_DURATION_COUNT,
      liveMaxLatencyDurationCount: HLS_LIVE_MAX_LATENCY_DURATION_COUNT,
      maxLiveSyncPlaybackRate: HLS_MAX_LIVE_SYNC_PLAYBACK_RATE,
      maxBufferLength: HLS_MAX_BUFFER_LENGTH_SECONDS,
      backBufferLength: HLS_BACK_BUFFER_LENGTH_SECONDS,
    });
    this.hls = hls;
    hls.attachMedia(video);
    hls.loadSource(src);
    console.info(`${LOG_PREFIX} HLS attach starting`, { src });

    hls.on(Hls.Events.MANIFEST_PARSED, () => void video.play().catch(() => undefined));
    hls.on(Hls.Events.FRAG_BUFFERED, () => {
      this.lastProgressAt = Date.now();
      this.mediaErrorRecoveryCount = 0; // real progress — the recovery cap starts fresh
      const wasReconnecting = this.transportState().recovery.phase === 'reconnecting';
      this.dispatchRecovery('firstSegment');
      this.dispatchPacing('playing', Date.now()); // docs/plans/done/MVP2-PLAN.md §S, S-c
      if (wasReconnecting) {
        console.info(`${LOG_PREFIX} HLS recovered — fragment buffered again`);
        this.maybeSnapToLive('recovered'); // see docs/plans/done/MVP2-PLAN.md §V, V-b — a fresh reattach earns a live-edge snap.
      }
    });
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (generation !== this.generation || !data.fatal) {
        return;
      }
      if (this.isColdStartMiss(data)) {
        console.info(`${LOG_PREFIX} HLS playlist not ready yet (cold start) — retrying`, {
          details: data.details,
          responseCode: data.response?.code,
        });
        this.dispatchRecovery('playlistNotReady');
        this.scheduleColdStartRetry(generation);
        return;
      }
      if (data.details === Hls.ErrorDetails.MANIFEST_PARSING_ERROR) {
        // The exact shape a dev-server missing the `/hls` proxy entry produces: the request
        // succeeds (200) but the body is the SPA's own `index.html`, which hls.js correctly
        // refuses to parse as a playlist — see vision-web/proxy.conf.json and its own comment.
        console.error(
          `${LOG_PREFIX} got HTML instead of HLS playlist — is the /hls proxy configured? ` +
            '(vision-web/proxy.conf.json needs a "/hls" entry pointing at the backend; ng serve must be restarted after editing it)',
          { src },
        );
      }
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        this.mediaErrorRecoveryCount++;
        if (this.mediaErrorRecoveryCount <= MEDIA_ERROR_RECOVERY_LIMIT) {
          console.warn(
            `${LOG_PREFIX} HLS media error (recovery attempt ${this.mediaErrorRecoveryCount}/${MEDIA_ERROR_RECOVERY_LIMIT}) — calling hls.recoverMediaError()`,
            { details: data.details },
          );
          hls.recoverMediaError(); // hls.js's own lighter-weight recovery, not a full reattach
          return;
        }
        console.error(
          `${LOG_PREFIX} HLS media error recovery exhausted after ${MEDIA_ERROR_RECOVERY_LIMIT} attempts — the underlying ` +
            'stream is likely never producing valid media (e.g. a dead upstream source); falling back to a full, ' +
            'timer-based reconnect instead of retrying recoverMediaError() again',
          { details: data.details },
        );
        // Falls through to the generic fatal-error path just below, same as any other
        // unrecoverable error — `teardownMedia()`'s own reset (see above) zeroes the count again
        // once the scheduled reconnect actually rebuilds `hls` from scratch.
      }
      console.warn(`${LOG_PREFIX} HLS fatal error — reconnecting`, {
        type: data.type,
        details: data.details,
      });
      this.dispatchRecovery('fatalError');
      this.message.set(data.details ?? 'The stream could not be loaded.');
      this.scheduleReconnect(generation);
    });

    this.startLatencySampling(video);
    this.startOverlayLoop();
  }

  /** A 404/network miss on the manifest while we've never yet played is the normal cold-start shape. */
  private isColdStartMiss(data: { response?: { code?: number } }): boolean {
    const phase = this.transportState().recovery.phase;
    if (phase !== 'connecting' && phase !== 'waiting') {
      return false;
    }
    const code = data.response?.code;
    return code === 404 || code === 0;
  }

  /**
   * A never-yet-live stream's cold-start poll (docs/plans/done/MVP2-PLAN.md §S, S-c rule 1): gentle and fixed
   * while this saga has never failed anything (`cycleAttempt === 0` — "a never-yet-live stream
   * politely waiting is allowed its gentle poll"), but the moment this saga has already failed a
   * transport (a WHEP fallback, or an earlier HLS attempt this same saga), a further playlist miss
   * is itself a cycle failure and folds under the same escalating pacing as any other reconnect
   * ("a stream that HAS failed transports escalates") — closing the half of S-b's flagged gap where
   * a stuck-`waiting` phase (which `isColdStartMiss` can keep true indefinitely, since the phase
   * itself never leaves `connecting`/`waiting`) never got throttled at all.
   */
  private scheduleColdStartRetry(generation: number): void {
    const pacing = this.pacingState();
    if (pacing.cycleAttempt === 0) {
      this.coldStartTimer = setTimeout(() => {
        if (generation === this.generation && this.currentSrc !== null) {
          void this.beginAttach(generation, this.currentSrc);
        }
      }, COLD_START_RETRY_DELAY_MS);
      return;
    }
    const next = this.dispatchPacing('cycleFailed', Date.now());
    this.coldStartTimer = setTimeout(
      () => this.beginNextCycle(generation),
      cyclePacingDelayMs(next),
    );
  }

  /**
   * Schedules the next reconnect cycle after a genuine failure (not a cold-start miss) — the delay
   * and whether WHEP is retried both come from the shared, cross-cycle `pacingState` (docs/MVP2-
   * PLAN.md §S, S-c), not `transportState().recovery.attempt`'s own cycle-local counter.
   */
  private scheduleReconnect(generation: number): void {
    this.clearReconnectTimer(); // see its own doc comment — never leave a prior backoff orphaned
    const next = this.dispatchPacing('cycleFailed', Date.now());
    const delay = cyclePacingDelayMs(next);
    console.warn(
      `${LOG_PREFIX} scheduling reconnect in ${delay}ms (cycle attempt ${next.cycleAttempt})`,
    );
    this.reconnectTimer = setTimeout(() => this.beginNextCycle(generation), delay);
  }

  /** No fragment/segment progress for `STALL_WATCHDOG_MS` is treated as a silent stall — reconnect. */
  private startWatchdog(generation: number): void {
    this.watchdogTimer = setInterval(() => {
      if (generation !== this.generation) {
        return;
      }
      const phase = this.transportState().recovery.phase;
      if (phase === 'idle' || phase === 'error') {
        return;
      }
      this.tickPacingHealth();
      if (isStalled(this.lastProgressAt, Date.now())) {
        console.warn(
          `${LOG_PREFIX} no HLS fragment progress for ${STALL_WATCHDOG_MS}ms — reconnecting`,
        );
        this.dispatchRecovery('stalled');
        this.message.set('No new video for a while — reconnecting.');
        this.scheduleReconnect(generation);
      }
    }, WATCHDOG_TICK_MS);
  }

  /**
   * Dispatches a pacing `'healthTick'` (docs/plans/done/MVP2-PLAN.md §S, S-c rule 3) whenever `phase ===
   * 'playing'` — a no-op otherwise. Called from both the HLS/native watchdog (`startWatchdog`) and
   * the WHEP one (`startWhepWatchdog`), which already tick every `WATCHDOG_TICK_MS` for their own
   * stall check regardless of transport, so this reuses that existing cadence rather than starting
   * a third timer.
   */
  private tickPacingHealth(): void {
    if (this.transportState().recovery.phase === 'playing') {
      this.dispatchPacing('healthTick', Date.now());
    }
  }

  private watchNativePlayback(generation: number, video: HTMLVideoElement): void {
    const controller = new AbortController();
    this.mediaAbort = controller;
    const abortSignal = controller.signal;

    video.addEventListener(
      'playing',
      () => {
        this.lastProgressAt = Date.now();
        const wasReconnecting = this.transportState().recovery.phase === 'reconnecting';
        this.dispatchRecovery('firstSegment');
        this.dispatchPacing('playing', Date.now()); // docs/plans/done/MVP2-PLAN.md §S, S-c
        if (wasReconnecting) {
          this.maybeSnapToLive('recovered'); // see docs/plans/done/MVP2-PLAN.md §V, V-b — mirrors the hls.js FRAG_BUFFERED handler above.
        }
      },
      { signal: abortSignal },
    );
    video.addEventListener('progress', () => (this.lastProgressAt = Date.now()), {
      signal: abortSignal,
    });
    video.addEventListener(
      'error',
      () => {
        if (generation !== this.generation) {
          return;
        }
        console.warn(`${LOG_PREFIX} native HLS playback error — reconnecting`, {
          error: video.error?.message,
        });
        this.dispatchRecovery('fatalError');
        this.message.set('The browser could not load this stream.');
        this.scheduleReconnect(generation);
      },
      { signal: abortSignal },
    );

    void video.play().catch(() => undefined);
    this.startLatencySampling(video);
    this.startOverlayLoop();
  }

  /**
   * Samples `behindLive` once a second and derives the live-edge behavior that rides on that same
   * cadence (docs/plans/done/MVP2-PLAN.md §V, V-b) — a tab-visibility restore, detected by diffing
   * `document.hidden` against what it was on the *previous* tick (there is no dedicated
   * `visibilitychange` listener — this app already has the precedent of checking `document.hidden`
   * from an existing per-second heartbeat rather than a separate event, see `core/poll-scheduler.ts`;
   * unlike that poller, this timer deliberately keeps running while hidden, per this file's own
   * pre-existing "a backgrounded tab's video should keep buffering" doc note above
   * `startWatchdog`/`teardownMedia`).
   */
  private startLatencySampling(video: HTMLVideoElement): void {
    this.wasDocumentHidden = isDocumentHidden();
    this.latencyTimer = setInterval(() => {
      const hidden = isDocumentHidden();
      const becameVisible = this.wasDocumentHidden && !hidden;
      this.wasDocumentHidden = hidden;

      const measured = this.measureBehindLive(video);
      this.behindLive.set(measured);

      if (becameVisible) {
        this.maybeSnapToLive('visibilityRestored', measured);
      }
    }, LATENCY_SAMPLE_MS);
  }

  /**
   * Forward-seeks to the live edge instead of leaving playback wherever its buffer sits — see
   * `shared/player/live-edge-logic.ts#SnapToLiveReason`'s own doc comment for what each reason covers and why.
   * A no-op for WHEP (no seekable buffer to fall behind — `behindLive` is pinned to `0`, so
   * `shouldSnapToLive`'s threshold branch would never fire anyway, but this also skips the always-
   * true `'recovered'` branch, which is never called from the WHEP attach path in the first place).
   */
  private maybeSnapToLive(
    reason: SnapToLiveReason,
    behindLiveSeconds: number | null = this.behindLive(),
  ): void {
    if (this.transport() !== 'hls') {
      return;
    }
    if (!shouldSnapToLive(reason, behindLiveSeconds)) {
      return;
    }
    this.snapToLiveEdge();
  }

  /**
   * `hls.liveSyncPosition` — the exact point `HLS_LIVE_SYNC_DURATION_COUNT` targets — when hls.js
   * is the active engine; falls back to the end of the native `seekable` range for Safari's native
   * HLS path (mirrors `measureBehindLive`'s own fallback, since `this.hls` is `null` there). A
   * no-op if neither is available yet (nothing buffered yet to seek into).
   */
  private snapToLiveEdge(): void {
    const videoRef = this.video();
    if (!videoRef) {
      return;
    }
    const video = videoRef.nativeElement;
    const target = this.hls?.liveSyncPosition ?? this.liveEdgeFromSeekable(video);
    if (target !== null && Number.isFinite(target) && target > 0) {
      video.currentTime = target;
    }
  }

  private liveEdgeFromSeekable(video: HTMLVideoElement): number | null {
    const seekable = video.seekable;
    return seekable.length === 0 ? null : seekable.end(seekable.length - 1);
  }

  private startOverlayLoop(): void {
    this.overlayTimer = setInterval(() => this.redrawOverlay(), OVERLAY_REDRAW_MS);
  }

  // --- WHEP attach (docs/plans/done/MVP2-PLAN.md §L / §U3) --------------------------------------------------

  /**
   * POSTs an SDP offer straight to `whepUrl` (never proxied — see class doc), applies the answer,
   * and waits for a track. `hlsFallbackSrc` is threaded through purely so a failure can hand off to
   * `beginAttach` without the caller needing to remember it — this method never reads `this.src()`
   * itself, keeping the whole attach path driven by the values `reattach` captured at trigger time.
   *
   * Every field this method resets at the top (`whepSessionUrl`/`whepIceState`/`restartInFlight`
   * alongside the pre-existing `lastFrameAt`/`whepStatsSnapshot`/`whepStatsAdvancing`) is a
   * per-*session* value — a brand new POST always starts a brand new session, so nothing from a
   * previous attach's bookkeeping should leak into this one, regardless of whether this is the very
   * first attach or the "last resort" fallback after a rejected ICE-restart PATCH.
   */
  private async beginWhepAttach(
    generation: number,
    whepUrl: string,
    hlsFallbackSrc: string | null,
  ): Promise<void> {
    if (generation !== this.generation) {
      return;
    }
    this.teardownWhep();
    this.lastFrameAt = Date.now();
    this.whepStatsSnapshot = null;
    this.whepStatsAdvancing = null;
    this.whepSessionUrl = null;
    this.whepIceState = INITIAL_WHEP_ICE_STATE;
    this.restartInFlight = false;
    this.whepTrackArrived = false;
    this.startWhepWatchdog(generation, hlsFallbackSrc);
    console.info(`${LOG_PREFIX} WHEP attach starting`, { whepUrl });

    if (typeof RTCPeerConnection === 'undefined') {
      console.error(`${LOG_PREFIX} WHEP unsupported — this browser has no RTCPeerConnection`);
      this.handleWhepFailure(generation, hlsFallbackSrc, 'unsupported');
      return;
    }

    const controller = new AbortController();
    this.whepAbort = controller;

    try {
      // Stable DTLS fingerprint (docs/plans/done/REALTIME-PLAN.md Phase R-b item 3) — every PC this player
      // creates shares the one browser-persisted certificate; see `WebrtcCertificateService`'s own
      // class doc for why (server-side viewer correlation across tiles/reconnects) and its graceful
      // degrade (an empty array here just means "let the browser generate its own ephemeral one",
      // exactly what every attach did before this cycle).
      const certificates = await this.certificateService.certificates();
      if (generation !== this.generation) {
        return;
      }
      const pc = new RTCPeerConnection(
        certificates.length > 0 ? { certificates: [...certificates] } : undefined,
      );
      this.peerConnection = pc;
      pc.addTransceiver('video', { direction: 'recvonly' });

      pc.ontrack = (event) => {
        if (generation !== this.generation) {
          return;
        }
        console.info(`${LOG_PREFIX} WHEP ontrack fired`, {
          kind: event.track.kind,
          streams: event.streams.length,
        });
        const video = this.video().nativeElement;
        const stream = event.streams[0];
        if (stream && video.srcObject !== stream) {
          video.srcObject = stream;
          void video.play().catch(() => undefined);
        }
        this.whepTrackArrived = true; // see this field's own doc comment for the race this closes
        this.lastFrameAt = Date.now();
        this.behindLive.set(0); // WHEP is effectively live — see class doc.
        this.clearWhepNoTrackTimer();
        this.dispatchRecovery('firstSegment');
        this.dispatchPacing('playing', Date.now()); // docs/plans/done/MVP2-PLAN.md §S, S-c
        if (this.overlayTimer === null) {
          this.startOverlayLoop(); // ontrack can fire more than once on renegotiation
        }
      };

      // `connectionState` (not `iceConnectionState`) drives ICE-restart-first recovery
      // (docs/plans/done/REALTIME-PLAN.md Phase R-b item 1) — the WebRTC-recommended aggregated signal for
      // "is media actually still flowing", replacing this handler's pre-R-b immediate-teardown
      // behavior entirely (an ICE restart needs the grace/restart decision made *before* anything
      // tears down, which the old handler never allowed for).
      pc.onconnectionstatechange = () =>
        this.onWhepConnectionStateChange(generation, pc, hlsFallbackSrc);

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      // Non-trickle: gather (bounded) before POSTing, so the offer's SDP carries usable candidates.
      // An ICE *restart* (see `attemptIceRestart`) reuses this exact same gather-then-send shape —
      // "one negotiate step, reused for both the first offer and every later restart" is this file's
      // own reading of "adopt the perfect-negotiation pattern" (docs/plans/done/REALTIME-PLAN.md Phase R-b item
      // 1): WHEP's browser-always-offers shape has no offer/answer glare to resolve (mediamtx never
      // sends an unsolicited offer of its own), so the one thing worth sharing is this negotiation
      // shape itself, not the full polite/impolite conflict-resolution protocol.
      await this.waitForIceGatheringComplete(pc);
      if (generation !== this.generation) {
        return;
      }

      const response = await fetch(whepUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp' },
        body: pc.localDescription?.sdp ?? offer.sdp ?? '',
        signal: controller.signal,
      });
      if (generation !== this.generation) {
        return;
      }
      console.info(`${LOG_PREFIX} WHEP POST ${whepUrl} → HTTP ${response.status}`);
      if (!response.ok) {
        throw new Error(`WHEP offer rejected: HTTP ${response.status}`);
      }

      // Honor the session resource (docs/plans/done/REALTIME-PLAN.md Phase R-b item 2): capture `Location` so
      // a later ICE restart (PATCH) or genuine teardown (DELETE) has a target. Absent entirely
      // degrades gracefully to "no restart/DELETE available for this session" rather than throwing —
      // a still-playable WHEP attach with no `Location` header is a mediamtx-compatibility gap worth
      // logging, not a reason to refuse otherwise-working video.
      const locationHeader = response.headers.get('Location') ?? response.headers.get('location');
      this.whepSessionUrl = locationHeader ? new URL(locationHeader, whepUrl).toString() : null;
      if (!this.whepSessionUrl) {
        console.warn(
          `${LOG_PREFIX} WHEP POST response carried no Location header — ICE restart/DELETE unavailable for this session`,
        );
      }

      const answerSdp = await response.text();
      if (generation !== this.generation) {
        return;
      }
      // Armed *before* `setRemoteDescription` is awaited, not after (docs/plans/done/REALTIME-PLAN.md Phase
      // R-b follow-up — a live soak test's own finding): `ontrack` can fire as part of applying the
      // remote description, ahead of this method's own `await` continuation, so arming any later
      // than this narrows — but does not by itself close — the race `whepTrackArrived`/
      // `isWhepStillWaitingForTrack` actually fixes (see `scheduleWhepNoTrackTimeout`'s own doc
      // comment for why the flag check, not the ordering, is the real fix).
      this.scheduleWhepNoTrackTimeout(generation, hlsFallbackSrc);
      await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });
    } catch (error) {
      if (generation !== this.generation || controller.signal.aborted) {
        return;
      }
      console.warn(`${LOG_PREFIX} WHEP negotiation failed`, { error });
      this.handleWhepFailure(generation, hlsFallbackSrc, 'fatalError');
    }
  }

  /** Bounded wait for ICE gathering to finish — a hung gatherer still lets the offer go out. */
  private waitForIceGatheringComplete(pc: RTCPeerConnection): Promise<void> {
    if (pc.iceGatheringState === 'complete') {
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, ICE_GATHERING_TIMEOUT_MS);
      const onChange = () => {
        if (pc.iceGatheringState === 'complete') {
          clearTimeout(timer);
          pc.removeEventListener('icegatheringstatechange', onChange);
          resolve();
        }
      };
      pc.addEventListener('icegatheringstatechange', onChange);
    });
  }

  /**
   * Arms the "no track arrived in time" timeout — but only when one is actually still meaningful
   * (`isWhepStillWaitingForTrack`, `player-recovery.ts`'s own doc comment has the full race this
   * guards against, confirmed by a live soak test). Called *before* `setRemoteDescription` is
   * awaited (see its own call site's comment) specifically because `ontrack` can fire ahead of that
   * `await`'s continuation — if it already has by the time this runs, arming a timer at all would
   * leave nothing able to clear it later.
   */
  private scheduleWhepNoTrackTimeout(generation: number, hlsFallbackSrc: string | null): void {
    if (!isWhepStillWaitingForTrack(this.whepTrackArrived)) {
      console.info(`${LOG_PREFIX} WHEP track already arrived — no-track timeout not armed`);
      return;
    }
    this.whepNoTrackTimer = setTimeout(() => {
      this.whepNoTrackTimer = null;
      if (generation !== this.generation || !isWhepStillWaitingForTrack(this.whepTrackArrived)) {
        return; // superseded, or the track arrived in the window between arming and firing
      }
      console.warn(
        `${LOG_PREFIX} WHEP connected but no track arrived within ${WHEP_NO_TRACK_TIMEOUT_MS}ms — treating as a WHEP failure`,
      );
      this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
    }, WHEP_NO_TRACK_TIMEOUT_MS);
  }

  private clearWhepNoTrackTimer(): void {
    if (this.whepNoTrackTimer !== null) {
      clearTimeout(this.whepNoTrackTimer);
      this.whepNoTrackTimer = null;
    }
  }

  // --- ICE-restart-first recovery (docs/plans/done/REALTIME-PLAN.md Phase R-b item 1) -----------------------

  /**
   * `connectionState` handler: `'connected'` clears any grace timer and reports a self-heal;
   * `'disconnected'`/`'failed'` route into `WhepIceState` (`dispatchWhepIce`) exactly per that
   * reducer's own event docs. **A restart is only attempted once this transport has actually
   * reached `playing`** — the same `neverPlayedYet` check `player-recovery.ts#reduceTransportRecovery`
   * already uses for the WHEP→HLS fallback decision: restarting ICE on a connection that was never
   * live in the first place isn't meaningful, so a pre-play failure here falls straight through to
   * the existing `handleWhepFailure` unchanged, same as before this cycle.
   */
  private onWhepConnectionStateChange(
    generation: number,
    pc: RTCPeerConnection,
    hlsFallbackSrc: string | null,
  ): void {
    if (generation !== this.generation) {
      return;
    }
    const state = pc.connectionState;
    console.info(`${LOG_PREFIX} WHEP connection state: ${state}`);

    if (state === 'connected') {
      this.clearIceRestartGraceTimer();
      this.dispatchWhepIce('reconnected');
      return;
    }

    const neverPlayedYet =
      this.transportState().recovery.phase === 'connecting' ||
      this.transportState().recovery.phase === 'waiting';
    if (neverPlayedYet) {
      if (state === 'failed' || state === 'disconnected') {
        console.warn(
          `${LOG_PREFIX} WHEP connection ${state} before ever playing — treating as a WHEP failure`,
        );
        this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
      }
      return;
    }

    if (state === 'disconnected') {
      const next = this.dispatchWhepIce('disconnected');
      if (next.phase === 'grace') {
        console.info(
          `${LOG_PREFIX} WHEP connection disconnected — ${ICE_RESTART_GRACE_MS}ms grace before attempting an ICE restart`,
        );
        this.scheduleIceRestartGrace(generation, hlsFallbackSrc);
      }
    } else if (state === 'failed') {
      this.clearIceRestartGraceTimer();
      this.dispatchWhepIce('failed');
      void this.attemptIceRestart(generation, hlsFallbackSrc);
    }
  }

  private scheduleIceRestartGrace(generation: number, hlsFallbackSrc: string | null): void {
    this.clearIceRestartGraceTimer();
    this.iceRestartGraceTimer = setTimeout(() => {
      this.iceRestartGraceTimer = null;
      if (generation !== this.generation) {
        return;
      }
      const next = this.dispatchWhepIce('graceExpired');
      if (next.phase === 'restarting') {
        void this.attemptIceRestart(generation, hlsFallbackSrc);
      }
    }, ICE_RESTART_GRACE_MS);
  }

  private clearIceRestartGraceTimer(): void {
    if (this.iceRestartGraceTimer !== null) {
      clearTimeout(this.iceRestartGraceTimer);
      this.iceRestartGraceTimer = null;
    }
  }

  /**
   * `restartIce()` + a fresh offer, sent as a WHEP PATCH to the session URL — see this class's own
   * doc comment and `shared/player/webrtc-ice-restart.ts`'s module doc for the full wire-format rationale.
   * Shares `waitForIceGatheringComplete` with the initial attach (the "one negotiate step, reused"
   * reading of "perfect negotiation" this class doc describes). Guarded by `restartInFlight` so the
   * `connectionState` handler and the stall watchdog noticing trouble on the same tick never start
   * two overlapping restarts against the same `RTCPeerConnection`.
   *
   * Falls through to the pre-existing `handleWhepFailure` (full teardown + re-POST/HLS-fallback,
   * completely unchanged from before this cycle) whenever: there's no active session to restart
   * (`peerConnection`/`whepSessionUrl` missing — e.g. no `Location` header was ever captured),
   * `restartIce()` itself doesn't exist (very old browsers), the PATCH is rejected
   * (`isPatchFallbackStatus` — 404 session gone, 412 precondition failed, or any other non-2xx), or
   * any step throws (a network error, a malformed answer fragment). `whepIceState` is not explicitly
   * reset on that path — the next fresh `beginWhepAttach`, whichever route reaches it, resets it
   * directly, the same way `lastFrameAt`/`whepStatsSnapshot` already are.
   */
  private async attemptIceRestart(
    generation: number,
    hlsFallbackSrc: string | null,
  ): Promise<void> {
    if (this.restartInFlight) {
      console.info(
        `${LOG_PREFIX} an ICE restart is already in flight — skipping a redundant trigger`,
      );
      return;
    }
    const pc = this.peerConnection;
    const sessionUrl = this.whepSessionUrl;
    if (!pc || !sessionUrl) {
      console.warn(`${LOG_PREFIX} ICE restart requested with no active session — falling back`);
      this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
      return;
    }
    if (typeof pc.restartIce !== 'function') {
      console.warn(`${LOG_PREFIX} this browser has no RTCPeerConnection#restartIce — falling back`);
      this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
      return;
    }

    this.restartInFlight = true;
    const attempt = this.whepIceState.restartAttempt;
    console.info(`${LOG_PREFIX} attempting a WHEP ICE restart (attempt ${attempt})`);
    try {
      pc.restartIce();
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      await this.waitForIceGatheringComplete(pc);
      if (generation !== this.generation || this.whepIceState.restartAttempt !== attempt) {
        return; // superseded — a fresh attach, or a newer failure, landed first
      }

      const localSdp = pc.localDescription?.sdp ?? offer.sdp ?? '';
      const response = await fetch(sessionUrl, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/trickle-ice-sdpfrag', 'If-Match': '"*"' },
        body: buildIceRestartFragment(localSdp),
      });
      if (generation !== this.generation || this.whepIceState.restartAttempt !== attempt) {
        return;
      }
      console.info(`${LOG_PREFIX} WHEP ICE restart PATCH ${sessionUrl} → HTTP ${response.status}`);

      if (isPatchFallbackStatus(response.status)) {
        throw new Error(`ICE restart PATCH rejected: HTTP ${response.status}`);
      }

      if (response.status === 200) {
        const answerFragment = await response.text();
        const remoteSdp = pc.remoteDescription?.sdp;
        if (!remoteSdp) {
          throw new Error('no remote description to patch the ICE restart answer onto');
        }
        await pc.setRemoteDescription({
          type: 'answer',
          sdp: applyIceRestartAnswer(remoteSdp, answerFragment),
        });
        for (const candidate of candidatesFromFragment(answerFragment)) {
          await pc.addIceCandidate(candidate).catch(() => undefined); // best-effort — a stale/duplicate candidate is not fatal
        }
      }
      // else 204: acknowledged, nothing further to apply — see `isPatchFallbackStatus`'s own doc
      // comment for why this module never actually sends a plain (non-restart) PATCH in practice.

      if (generation !== this.generation) {
        return;
      }
      console.info(`${LOG_PREFIX} WHEP ICE restart succeeded — media kept flowing throughout`);
      this.dispatchWhepIce('restartSucceeded');
      // A fresh stall-detection window: the connection just proved itself, but frames may take a
      // brief moment to resume — don't let the watchdog reconsider before giving it a fair chance.
      this.lastFrameAt = Date.now();
      this.whepStatsSnapshot = null;
      this.whepStatsAdvancing = null;
    } catch (error) {
      if (generation !== this.generation) {
        return;
      }
      console.warn(`${LOG_PREFIX} WHEP ICE restart failed — falling back to full teardown`, {
        error,
      });
      this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
    } finally {
      this.restartInFlight = false;
    }
  }

  /**
   * Fire-and-forget `DELETE` to the WHEP session resource (docs/plans/done/REALTIME-PLAN.md Phase R-b item 2)
   * — genuine teardown only; never called from the ICE-restart path above, which keeps the same
   * session alive on purpose. `keepalive: true` lets this outlive a `pagehide` navigation (browsers
   * permit a bounded amount of in-flight keepalive request data across a page unload) — chosen over
   * `navigator.sendBeacon` because `sendBeacon` can only ever issue a `POST`, never a `DELETE`, and
   * mediamtx's WHEP session resource only accepts the real HTTP `DELETE` verb (verified in
   * `internal/servers/webrtc/http_server.go`: `case http.MethodDelete: s.onWHIPDelete(...)` — no
   * fallback for a POST-as-delete convention). Errors are swallowed — the worst case is mediamtx
   * reaping this session on its own idle-session GC, exactly the pre-R-b leak this closes, not a new
   * regression if the DELETE occasionally doesn't land.
   */
  private sendWhepSessionDelete(sessionUrl: string): void {
    void fetch(sessionUrl, { method: 'DELETE', keepalive: true }).catch(() => undefined);
  }

  /**
   * No frame progress for `STALL_WATCHDOG_MS` — the WHEP equivalent of `startWatchdog`'s HLS check.
   * Real progress is measured via `getStats()` each tick (`refreshWhepStatsProgress`,
   * docs/plans/done/REALTIME-PLAN.md Phase R-a item 1) rather than assumed from the one-shot `ontrack` handler
   * — see that method's own doc comment for why the old assumption tripped this watchdog on
   * perfectly healthy streams.
   */
  private startWhepWatchdog(generation: number, hlsFallbackSrc: string | null): void {
    this.whepWatchdogTimer = setInterval(() => {
      void this.tickWhepWatchdog(generation, hlsFallbackSrc);
    }, WATCHDOG_TICK_MS);
  }

  private async tickWhepWatchdog(generation: number, hlsFallbackSrc: string | null): Promise<void> {
    if (generation !== this.generation || this.transportState().transport !== 'webrtc') {
      return;
    }
    this.tickPacingHealth();
    await this.refreshWhepStatsProgress(generation);
    if (generation !== this.generation || this.transportState().transport !== 'webrtc') {
      return; // superseded (a fresh attach / transport switch) while `getStats()` was in flight
    }
    if (this.restartInFlight) {
      return; // an ICE restart is already addressing whatever this tick would otherwise report
    }
    if (isStalled(this.lastFrameAt, Date.now())) {
      // Keep the real-stall watchdog from R-a as the trigger for recovery, but recovery now means
      // ICE-restart-first (docs/plans/done/REALTIME-PLAN.md Phase R-b item 1) — the same rule
      // `onWhepConnectionStateChange` uses: a transport that has already played retries via
      // in-place ICE restart before ever tearing anything down; a pre-play stall (rare — the
      // no-track timeout usually catches that first) still falls straight to `handleWhepFailure`.
      console.warn(`${LOG_PREFIX} no WHEP frame progress for ${STALL_WATCHDOG_MS}ms`);
      const neverPlayedYet =
        this.transportState().recovery.phase === 'connecting' ||
        this.transportState().recovery.phase === 'waiting';
      if (neverPlayedYet) {
        this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
      } else {
        this.dispatchWhepIce('failed');
        void this.attemptIceRestart(generation, hlsFallbackSrc);
      }
    }
  }

  /**
   * Refreshes `lastFrameAt` from real decode/receive progress (docs/plans/done/REALTIME-PLAN.md §0 + Phase
   * R-a item 1): before this, `lastFrameAt` was only ever set at attach and in the one-shot
   * `ontrack` handler, so a perfectly healthy stream tripped `STALL_WATCHDOG_MS` ~8-10s after
   * connecting regardless — `ontrack` fires once per renegotiation, never once per frame.
   * `getStats()` is the ground truth instead: if the inbound video track's
   * `framesDecoded`/`bytesReceived` moved since the previous tick (`didWhepStatsAdvance`),
   * `lastFrameAt` is refreshed exactly as a fresh `ontrack` would; a genuinely dead feed's counters
   * stop moving, so this watchdog still fires for it exactly as before this cycle.
   *
   * Silently gives up on a `getStats()` rejection (a closing/closed `RTCPeerConnection` mid-
   * teardown) — the stall check in `tickWhepWatchdog` just runs against whatever `lastFrameAt`
   * already holds. Logs only on an advancing/stalled *transition* (`whepStatsAdvancing`), never
   * every tick — this runs on the same `WATCHDOG_TICK_MS` (2s) cadence as every healthy stream, so
   * per-tick logging here would be exactly the console spam this file's own `LOG_PREFIX` doc
   * comment says to avoid.
   *
   * **Also refreshes `whepLatencySeconds`** (docs/plans/done/UX-QUICKWINS-PLAN.md QF-4's latency badge) from
   * the exact same report — `extractWhepStatsSnapshot` now also reads jitter/round-trip time
   * alongside the decode counters, so this is one `getStats()` call serving both concerns, not a
   * second poller.
   */
  private async refreshWhepStatsProgress(generation: number): Promise<void> {
    const pc = this.peerConnection;
    if (!pc) {
      return;
    }
    let report: RTCStatsReport;
    try {
      report = await pc.getStats();
    } catch {
      return;
    }
    if (generation !== this.generation) {
      return;
    }
    const stats: (WhepInboundRtpStatLike | WhepCandidatePairStatLike)[] = [];
    report.forEach((value) => stats.push(value as WhepInboundRtpStatLike | WhepCandidatePairStatLike));
    const current = extractWhepStatsSnapshot(stats);
    const advanced = didWhepStatsAdvance(this.whepStatsSnapshot, current);
    if (advanced !== this.whepStatsAdvancing) {
      this.whepStatsAdvancing = advanced;
      console.debug(
        `${LOG_PREFIX} WHEP stats ${advanced ? 'advancing' : 'not advancing'}`,
        current,
      );
    }
    if (current) {
      this.whepStatsSnapshot = current;
    }
    this.whepLatencySeconds.set(estimateWhepLatencySeconds(current));
    if (advanced) {
      this.lastFrameAt = Date.now();
    }
  }

  /**
   * Routes a WHEP failure through `reduceTransportRecovery`: falls back to HLS (via the existing
   * `beginAttach` path, immediately, no extra delay — U3's permanent-per-cycle fallback, unchanged)
   * the first time this transport hasn't played yet, or retries WHEP itself once it has — see that
   * function's own doc comment for the rule. The pre-play fallback branch doesn't touch `pacingState`
   * itself (the WHEP attempt that just failed was already recorded when it started — see
   * `reattach`/`beginNextCycle`'s own `'whepAttempted'` dispatch — and falling through to try HLS is
   * *within* the current cycle, not a new one); the played-before retry branch below does, since a
   * previously-proven WHEP connection dying and needing reconnection is genuinely a new cycle
   * (docs/plans/done/MVP2-PLAN.md §S, S-c).
   */
  private handleWhepFailure(
    generation: number,
    hlsFallbackSrc: string | null,
    event: 'fatalError' | 'stalled' | 'unsupported',
  ): void {
    if (generation !== this.generation) {
      return;
    }
    this.teardownWhep();
    const next = this.dispatchRecovery(event);

    if (next.transport === 'hls') {
      if (hlsFallbackSrc) {
        console.warn(
          `${LOG_PREFIX} WHEP failed (${event}) before ever playing — falling back to HLS`,
          {
            hlsFallbackSrc,
          },
        );
        void this.beginAttach(generation, hlsFallbackSrc);
      } else {
        console.error(
          `${LOG_PREFIX} WHEP failed (${event}) and no HLS fallback URL is available for this stream`,
        );
        this.message.set(
          'WebRTC playback failed and no HLS fallback is available for this stream.',
        );
      }
      return;
    }

    const whepUrl = this.currentWhepUrl;
    const pacingNext = this.dispatchPacing('cycleFailed', Date.now());
    const delay = cyclePacingDelayMs(pacingNext);
    console.warn(
      `${LOG_PREFIX} WHEP failed (${event}) after having played before — retrying WHEP in ${delay}ms`,
    );
    this.clearReconnectTimer(); // see its own doc comment — never leave a prior backoff orphaned
    this.reconnectTimer = setTimeout(() => {
      if (generation === this.generation && whepUrl !== null) {
        this.dispatchPacing('whepAttempted', Date.now());

        // --- THE FIX GOES HERE ---
        this.transportState.set(
          reduceTransportRecovery(this.transportState(), 'attachStarted')
        );
        // -------------------------

        void this.beginWhepAttach(generation, whepUrl, hlsFallbackSrc);
      }
    }, delay);
  }

  /**
   * Tears down the current WHEP attempt/session — called at the top of every fresh
   * `beginWhepAttach` (a brand new session is about to replace whatever this one was) and from full
   * `teardown()`. **Always `DELETE`s the session resource if one exists** (docs/plans/done/REALTIME-PLAN.md
   * Phase R-b item 2): every call site of this method represents genuinely letting go of
   * `whepSessionUrl` — the *only* code path that keeps a session alive across a connectivity blip is
   * `attemptIceRestart`, which deliberately never calls this method at all. That includes this
   * method's own call from `handleWhepFailure`'s "last resort" branch, which is exactly right: a
   * rejected ICE-restart PATCH means the old session is being abandoned in favor of a fresh POST,
   * precisely the leak item 2 exists to close.
   */
  private teardownWhep(): void {
    this.clearWhepNoTrackTimer();
    this.clearIceRestartGraceTimer();
    this.restartInFlight = false;
    if (this.whepWatchdogTimer !== null) {
      clearInterval(this.whepWatchdogTimer);
      this.whepWatchdogTimer = null;
    }
    this.whepAbort?.abort();
    this.whepAbort = null;
    if (this.whepSessionUrl !== null) {
      this.sendWhepSessionDelete(this.whepSessionUrl);
      this.whepSessionUrl = null;
    }
    if (this.peerConnection) {
      this.peerConnection.ontrack = null;
      this.peerConnection.onconnectionstatechange = null;
      this.peerConnection.close();
      this.peerConnection = null;
    }
    const videoRef = this.video();
    if (videoRef?.nativeElement.srcObject) {
      videoRef.nativeElement.srcObject = null;
    }
  }

  /**
   * Distance from the live edge in seconds.
   *
   * hls.js reports this directly in low-latency mode; otherwise the end of the seekable
   * range is the best available estimate of the edge. This is not glass-to-glass latency —
   * the label says "behind" for exactly that reason.
   */
  private measureBehindLive(video: HTMLVideoElement): number | null {
    const reported = this.hls?.latency;
    if (typeof reported === 'number' && reported > 0) {
      return reported;
    }
    const seekable = video.seekable;
    if (seekable.length === 0 || video.currentTime === 0) {
      return null;
    }
    return Math.max(0, seekable.end(seekable.length - 1) - video.currentTime);
  }

  // --- Detection overlay (docs/main/CYCLES-PLAN.md §11 item 6) -------------------------------------

  private redrawOverlay(): void {
    const canvasRef = this.overlayCanvas();
    const videoRef = this.video();
    if (!canvasRef || !videoRef) {
      return;
    }
    const canvas = canvasRef.nativeElement;
    const video = videoRef.nativeElement;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return;
    }

    const width = video.clientWidth;
    const height = video.clientHeight;
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    ctx.clearRect(0, 0, width, height);
    this.drawnBoxes = [];

    const results = this.detections();
    if (!shouldDrawOverlay(this.boxesMode(), results.length > 0) || this.phase() !== 'playing') {
      return;
    }
    const result = selectDetectionResult(
      results,
      Date.now(),
      this.behindLive(),
      DEFAULT_SLACK_BATCHES,
    );
    if (!result || video.videoWidth === 0 || video.videoHeight === 0) {
      return;
    }

    const content = this.letterboxRect(width, height, video.videoWidth, video.videoHeight);
    // Trails first, so every box (drawn next) sits visually on top of its own tail rather than
    // under it — docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #3.
    this.drawTrails(ctx, content, results);
    const drawn: DrawnBox[] = [];
    for (const detection of result.detections) {
      const box = detection.box;
      const rect = {
        x: content.x + box.x * content.width,
        y: content.y + box.y * content.height,
        width: box.width * content.width,
        height: box.height * content.height,
        detection,
      };
      drawn.push(rect);
      this.drawBox(ctx, rect, detection);
    }
    this.drawnBoxes = drawn;
  }

  /** The video's actual rendered rectangle within `element`, accounting for `object-fit: contain` letterboxing. */
  private letterboxRect(
    elementWidth: number,
    elementHeight: number,
    videoWidth: number,
    videoHeight: number,
  ): { x: number; y: number; width: number; height: number } {
    const elementRatio = elementWidth / elementHeight;
    const videoRatio = videoWidth / videoHeight;
    if (videoRatio > elementRatio) {
      const width = elementWidth;
      const height = width / videoRatio;
      return { x: 0, y: (elementHeight - height) / 2, width, height };
    }
    const height = elementHeight;
    const width = height * videoRatio;
    return { x: (elementWidth - width) / 2, y: 0, width, height };
  }

  /**
   * Box color is per-**track** once a detection carries one (docs/plans/done/TRACKING-PLAN.md §10 — `trackHue`,
   * `detection-overlay-logic.ts`), so one object keeps one color across every frame even as its
   * label flips (a composite-mode member handoff mid-track); **per-model** otherwise (docs/OPS-CORE-
   * PLAN.md §Q3b, `modelHue`/`detectionModelKey`) — a single-model, untracked stream's boxes stay
   * the exact `#4f8cff` this always drew. Hover still overrides to the same amber it always has,
   * for every box alike — hover means "this box", not "this model" or "this track". A `COASTING`
   * track (tracker-predicted, not detector-reconfirmed on the most recent pass) draws **dashed** —
   * the honest-UI doctrine made pixel-level: the system is visibly saying "I am extrapolating, not
   * seeing" (docs/extracts/TRACKING-ORCHESTRATION.md §3.3's same doctrine, applied to a box instead of a lock).
   */
  private drawBox(ctx: CanvasRenderingContext2D, rect: DrawnBox, detection: Detection): void {
    const hovered = this.hoveredDetection() === detection;
    const track = detection.track;
    const modelKey = detectionModelKey(detection);
    const strokeColor = track ? trackHue(track.id) : modelHue(modelKey);
    const fillColor = track ? trackHue(track.id, 85) : modelHue(modelKey, 85);

    ctx.lineWidth = hovered ? 3 : 2;
    ctx.strokeStyle = hovered ? '#ffd479' : strokeColor;
    ctx.setLineDash(track?.state === 'COASTING' ? [6, 4] : []);
    ctx.strokeRect(rect.x, rect.y, rect.width, rect.height);
    ctx.setLineDash([]); // never leak dashing into the label fill below, or a later stroke (a trail, another box)

    const label = formatDetectionLabel(detection);
    ctx.font = '11px ui-monospace, monospace';
    const metrics = ctx.measureText(label);
    const labelHeight = 14;
    ctx.fillStyle = hovered ? 'rgb(255 212 121 / 90%)' : fillColor;
    ctx.fillRect(rect.x, Math.max(0, rect.y - labelHeight), metrics.width + 6, labelHeight);
    ctx.fillStyle = '#04101f';
    ctx.fillText(label, rect.x + 3, Math.max(labelHeight - 3, rect.y - 3));
  }

  /**
   * Fading per-track trails (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #3) — `trackTrails` is a
   * pure recomputation from `results` on every redraw tick (`detection-overlay-logic.ts`'s own doc
   * comment on why that's enough to "clear on stream change" with no extra bookkeeping here). A
   * track with fewer than two points in the window has nothing to connect yet and draws nothing —
   * a lone dot would just be noise next to the box itself. Segments fade from `0.15` (oldest) to
   * `0.80` (newest) alpha, the "history, not a snapshot" effect the plan's own touchable outcome
   * asks for; `content` is the same letterboxed video rect `redrawOverlay` already computed for boxes,
   * so a trail point and its own box always land on the identical pixel.
   */
  private drawTrails(
    ctx: CanvasRenderingContext2D,
    content: { x: number; y: number; width: number; height: number },
    results: readonly DetectionResult[],
  ): void {
    const trails = trackTrails(results, Date.now(), TRAIL_WINDOW_MS);
    if (trails.size === 0) {
      return;
    }
    ctx.lineWidth = 2;
    ctx.setLineDash([]);
    for (const [trackId, points] of trails) {
      if (points.length < 2) {
        continue;
      }
      ctx.strokeStyle = trackHue(trackId);
      for (let i = 1; i < points.length; i++) {
        const from = points[i - 1];
        const to = points[i];
        ctx.globalAlpha = 0.15 + 0.65 * (i / (points.length - 1));
        ctx.beginPath();
        ctx.moveTo(content.x + from.x * content.width, content.y + from.y * content.height);
        ctx.lineTo(content.x + to.x * content.width, content.y + to.y * content.height);
        ctx.stroke();
      }
    }
    ctx.globalAlpha = 1;
  }

  protected onOverlayMouseMove(event: MouseEvent): void {
    const canvas = this.overlayCanvas().nativeElement;
    const bounds = canvas.getBoundingClientRect();
    const x = event.clientX - bounds.left;
    const y = event.clientY - bounds.top;
    const hit = this.drawnBoxes.find(
      (box) => x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height,
    );
    this.hoveredDetection.set(hit?.detection ?? null);
    if (hit) {
      this.tooltipX.set(x);
      this.tooltipY.set(Math.max(0, y - 4));
      this.redrawOverlay();
    } else if (this.hoveredDetection() !== null) {
      this.redrawOverlay();
    }
  }

  protected onOverlayMouseLeave(): void {
    if (this.hoveredDetection() !== null) {
      this.hoveredDetection.set(null);
      this.redrawOverlay();
    }
  }

  /**
   * Click-to-follow's hit-test (docs/plans/done/TRACKING-PLAN.md §4.D) — reuses the exact same
   * {@link drawnBoxes} hit-test {@link onOverlayMouseMove} already does; a click that lands on a
   * **tracked** box emits its id via {@link trackFollowed}. A click on an untracked box, or on empty
   * canvas, is a no-op — there is no id to lock onto (the wire's point/box lock forms are out of
   * this wave's scope, see {@link trackFollowed}'s own doc comment).
   */
  protected onOverlayClick(event: MouseEvent): void {
    const canvas = this.overlayCanvas().nativeElement;
    const bounds = canvas.getBoundingClientRect();
    const x = event.clientX - bounds.left;
    const y = event.clientY - bounds.top;
    const hit = this.drawnBoxes.find(
      (box) => x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height,
    );
    const trackId = hit?.detection.track?.id;
    if (trackId !== undefined) {
      this.trackFollowed.emit(trackId);
    }
  }

  // --- Teardown ---------------------------------------------------------------------------------

  /** Clears whatever the *current* attach attempt owns — safe to call at the top of every attach. */
  private teardownMedia(): void {
    if (this.latencyTimer !== null) {
      clearInterval(this.latencyTimer);
      this.latencyTimer = null;
    }
    if (this.overlayTimer !== null) {
      clearInterval(this.overlayTimer);
      this.overlayTimer = null;
    }
    if (this.watchdogTimer !== null) {
      clearInterval(this.watchdogTimer);
      this.watchdogTimer = null;
    }
    if (this.coldStartTimer !== null) {
      clearTimeout(this.coldStartTimer);
      this.coldStartTimer = null;
    }
    this.clearReconnectTimer();
    this.mediaAbort?.abort();
    this.mediaAbort = null;
    this.mediaErrorRecoveryCount = 0; // a fresh `hls` instance below starts the recovery cap over
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
    const video = this.video().nativeElement;
    video.removeAttribute('src');
    video.load();
    this.behindLive.set(null);
    this.whepLatencySeconds.set(null); // the badge resets with every fresh attach, not carried over.
  }

  /** Full stop — component destroy, or the `src`/`whepUrl`/`suspended` inputs actually changed. */
  private teardown(): void {
    this.teardownMedia();
    this.teardownWhep();
    this.message.set(null);
    this.hoveredDetection.set(null);
    this.drawnBoxes = [];
  }
}
