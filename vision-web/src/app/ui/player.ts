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
import type { Detection, DetectionResult } from '../core/api/models';
import {
  DEFAULT_SLACK_BATCHES,
  selectDetectionResult,
  shouldDrawOverlay,
  type BoxesMode,
} from './detection-overlay-logic';
import {
  COLD_START_RETRY_DELAY_MS,
  INITIAL_PACING_STATE,
  advancePacing,
  cyclePacingDelayMs,
  initialTransportState,
  isStalled,
  reduceTransportRecovery,
  shouldAttemptWhep,
  type PacingState,
  type PlayerPhase,
  type Transport,
  type TransportRecoveryState,
} from './player-recovery';
import {
  behindLiveChipLabel,
  shouldShowBehindLive,
  shouldSnapToLive,
  type SnapToLiveReason,
} from './live-edge-logic';

export type { PlayerPhase, Transport } from './player-recovery';
export type { BoxesMode } from './detection-overlay-logic';

const LATENCY_SAMPLE_MS = 1_000;
const OVERLAY_REDRAW_MS = 200;
const WATCHDOG_TICK_MS = 2_000;

/** How long a WHEP attach waits for `ontrack` after the answer is applied — see class doc. */
const WHEP_NO_TRACK_TIMEOUT_MS = 6_000;

/** Bounded wait for ICE gathering before POSTing the offer — see `waitForIceGatheringComplete`. */
const ICE_GATHERING_TIMEOUT_MS = 3_000;

/**
 * hls.js live-edge tuning (docs/MVP2-PLAN.md §V — V-a shrinks segment/part duration server-side,
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
 * (docs/MVP2-PLAN.md §L / §U3), with an honest status line (docs/CYCLES-PLAN.md §11, CD-b item 5)
 * — shared as-is by `wall-tile.ts`, `live.html`, `pages/asset-detail/asset-detail.html`, and
 * `pages/map/live-dock.ts`, so every one of those surfaces gets everything below for free.
 *
 * Three deliberate choices, all from docs/UX-DESIGN.md §2 T1 plus CD-b's own resilience ask:
 *
 *  - `hls.js` is imported dynamically, so its ~90 KB lands in its own chunk and never
 *    touches the initial bundle of a user who only visits Devices. WHEP needs no such import —
 *    `RTCPeerConnection`/`fetch` are browser built-ins — and this component itself only ever
 *    reaches a lazy route chunk (never `providedIn: 'root'`), so the WHEP code adds nothing eager.
 *  - The distance behind the live edge is measured and displayed continuously. HLS costs
 *    seconds of latency; a user told "≈6 s behind live" understands the trade, while a
 *    user shown a spinner concludes the app is broken. WHEP is effectively live — `behindLive` is
 *    pinned to `0` the moment a track arrives (a real, meaningful number, not `null`: the
 *    detection-overlay sync matcher (`detection-overlay-logic.ts#selectDetectionResult`) needs an
 *    actual latency estimate to pick the right batch, and `0` is what "near-zero" means here) —
 *    though the visible badge reads "live · WebRTC" rather than a suspiciously precise "0.0s".
 *  - The player never gives up. `ui/player-recovery.ts`'s pure state machine drives every
 *    transition; a fatal hls.js error or a silent stall (the watchdog: no fragment progress for
 *    `STALL_WATCHDOG_MS`) destroys and reattaches with capped exponential backoff, indefinitely,
 *    for as long as `src`/`whepUrl` stay set — including across a backend restart. `error` is
 *    reserved for a genuinely unrecoverable case (this browser can play neither transport at all).
 *
 * "Waiting for the first segment" is a first-class state rather than an error, because it
 * is the normal condition for the first few seconds of every new stream.
 *
 * **WHEP-first, HLS-fallback** (docs/MVP2-PLAN.md §L / §U3): when the optional `whepUrl` input is
 * set, the player POSTs an SDP offer straight to it (mediamtx's own absolute origin URL — never
 * proxied, never app-relative, see `core/api/models.ts#ActiveStream.whepUrl`'s doc comment),
 * applies the SDP answer, and waits for a track. Any failure before a track ever arrives (a POST
 * error, an ICE `failed`/`disconnected` state, or no track within `WHEP_NO_TRACK_TIMEOUT_MS`) falls
 * back to the exact same HLS attach path `src` has always used — this fallback is **load-bearing**,
 * not a rare edge case: docs/MVP2-PLAN.md §L's own documented caveat is that a dockerized mediamtx
 * advertises `127.0.0.1` ICE candidates, so *every* LAN viewer's WHEP attempt is expected to fail
 * and land here silently. Once a transport has ever reached `playing`, a later stall/failure
 * retries *that same transport* with the identical capped backoff instead of re-deciding transport
 * from scratch — see `player-recovery.ts#reduceTransportRecovery`'s own doc comment for the exact
 * rule. The visible state chip's transport suffix (`'live · WebRTC'` / `'…s behind · HLS'`) always
 * names whichever transport is actually attached, and `transportChanged` emits it too, for a host
 * that wants to echo it (e.g. `ui/stream-info-panel.ts`'s "Transport" fact).
 *
 * **Detection overlay** (docs/CYCLES-PLAN.md §11 item 6): an optional `detections`/`boxesMode`
 * input pair draws a canvas overlay of the freshest detection batch matched against this player's
 * own measured live-edge latency (`ui/detection-overlay-logic.ts#selectDetectionResult`) — crisp
 * at any video bitrate, and hoverable (label + confidence), unlike the server's burned-in boxes
 * (which stay; this is additive, see that module's doc comment on `'burned'`/`'off'`). Callers
 * that never pass `detections` simply never see the canvas draw anything.
 */
@Component({
  selector: 'vision-player',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="frame" [class.compact]="compact()">
      <video #video playsinline muted autoplay [controls]="!compact()"></video>

      <canvas
        #overlay
        class="overlay-canvas"
        [class.interactive]="overlayInteractive()"
        (mousemove)="onOverlayMouseMove($event)"
        (mouseleave)="onOverlayMouseLeave()"
      ></canvas>

      @if (hoveredDetection(); as hovered) {
        <div class="box-tooltip" [style.left.px]="tooltipX()" [style.top.px]="tooltipY()">
          {{ hovered.label }} · {{ (hovered.confidence * 100).toFixed(0) }}%
        </div>
      }

      @if (phase() !== 'playing') {
        <div class="overlay-status" [class.error]="phase() === 'error'">
          @switch (phase()) {
            @case ('idle') {
              <span class="muted">Not streaming</span>
            }
            @case ('connecting') {
              <span class="spinner" aria-hidden="true"></span>
              <span>Connecting…</span>
            }
            @case ('waiting') {
              <span class="spinner" aria-hidden="true"></span>
              <span>Waiting for the first segment…</span>
              <span class="hint">HLS buffers a few segments before playback can start.</span>
            }
            @case ('reconnecting') {
              <span class="spinner" aria-hidden="true"></span>
              <span>Reconnecting…</span>
              <span class="hint">{{ reconnectHint() }}</span>
              <button type="button" class="btn secondary small" (click)="retryNow()">Retry now</button>
            }
            @case ('error') {
              <span class="err-title">Playback failed</span>
              <span class="hint">{{ message() }}</span>
            }
            @case ('stopped') {
              <span class="muted">Stream stopped</span>
            }
          }
        </div>
      }

      @if (phase() === 'playing') {
        <div class="badge" [title]="latencyTitle()">
          <span class="dot live"></span>{{ latencyLabel() }}
        </div>
      }
    </div>
  `,
  styles: `
    .frame {
      position: relative;
      background: #000;
      border-radius: var(--radius-sm);
      overflow: hidden;
      aspect-ratio: 16 / 9;
    }

    video {
      width: 100%;
      height: 100%;
      object-fit: contain;
      display: block;
    }

    .overlay-canvas {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      pointer-events: none;
    }

    .overlay-canvas.interactive {
      pointer-events: auto;
    }

    .box-tooltip {
      position: absolute;
      transform: translate(-50%, -100%);
      background: rgb(0 0 0 / 80%);
      color: #fff;
      font-size: 0.7rem;
      font-family: var(--mono);
      padding: 0.15rem 0.4rem;
      border-radius: 4px;
      pointer-events: none;
      white-space: nowrap;
      z-index: 2;
    }

    .overlay-status {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      padding: 1rem;
      text-align: center;
      font-size: 0.85rem;
      color: var(--text-muted);
      background: linear-gradient(180deg, #0d1117 0%, #05070a 100%);
    }

    .overlay-status.error {
      color: #ffb3bd;
    }

    .err-title {
      color: var(--danger);
      font-weight: 600;
    }

    .hint {
      font-size: 0.78rem;
      color: var(--text-faint);
      max-width: 40ch;
    }

    .badge {
      position: absolute;
      top: 0.5rem;
      left: 0.5rem;
      display: flex;
      align-items: center;
      gap: 0.35rem;
      padding: 0.15rem 0.5rem;
      border-radius: var(--radius-pill);
      background: rgb(0 0 0 / 55%);
      backdrop-filter: blur(4px);
      font-size: 0.72rem;
      font-family: var(--mono);
      color: #dfe6f0;
    }

    .spinner {
      width: 18px;
      height: 18px;
      border-radius: 50%;
      border: 2px solid var(--border-strong);
      border-top-color: var(--accent);
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin {
      to {
        transform: rotate(360deg);
      }
    }
  `,
})
export class Player {
  /** HLS playlist URL, or `null` when nothing is streaming — also the WHEP fallback target. */
  readonly src = input<string | null>(null);

  /** Mediamtx's own absolute WHEP origin URL (docs/MVP2-PLAN.md §L), or `null`/absent for HLS-only. */
  readonly whepUrl = input<string | null>(null);

  /** Wall tiles set this when scrolled out of view so off-screen video stops decoding. */
  readonly suspended = input(false);

  /**
   * The host page knows this stream was deliberately stopped (docs/MVP2-PLAN.md §S, S-b) — its own
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

  /** `'overlay'` (draw boxes), `'burned'`/`'off'` (draw nothing — see `shouldDrawOverlay`'s doc). */
  readonly boxesMode = input<BoxesMode>('overlay');

  /** Emits the measured seconds-behind-live on every sample, `null` while unknown/not playing. */
  readonly latencyChanged = output<number | null>();

  /** Emits the live transport whenever it changes — see class doc's "WHEP-first, HLS-fallback". */
  readonly transportChanged = output<Transport>();

  private readonly video = viewChild.required<ElementRef<HTMLVideoElement>>('video');
  private readonly overlayCanvas = viewChild.required<ElementRef<HTMLCanvasElement>>('overlay');

  private readonly transportState = signal<TransportRecoveryState>(initialTransportState(false));
  protected readonly phase = computed<PlayerPhase>(() => this.transportState().recovery.phase);
  protected readonly transport = computed<Transport>(() => this.transportState().transport);
  protected readonly message = signal<string | null>(null);

  /**
   * Cross-cycle reconnect pacing (docs/MVP2-PLAN.md §S, S-c) — `player-recovery.ts#PacingState`'s
   * own doc comment has the full rules; this signal is the one piece of state that survives a
   * WHEP→HLS fallback and every subsequent cycle restart, driving the *actual* delay/WHEP-retry
   * decisions below. `transportState` above is untouched and still drives `phase`/the chip.
   */
  private readonly pacingState = signal<PacingState>(INITIAL_PACING_STATE);

  protected readonly reconnectHint = computed(() => {
    // Reads the saga-wide pacing counter, not `transportState().recovery.attempt` (which resets on
    // every WHEP→HLS fallback) — see `PacingState`'s doc comment for why the two differ on purpose.
    const attempt = this.pacingState().cycleAttempt;
    return attempt <= 1
      ? 'The stream will resume automatically.'
      : `Retrying automatically (attempt ${attempt}).`;
  });

  /** Seconds behind the live edge, or `null` while unknown. Pinned to `0` for a live WHEP track. */
  private readonly behindLive = signal<number | null>(null);

  /**
   * Whether the chip's quantified "…s behind" tail is currently showing (docs/MVP2-PLAN.md §V,
   * V-b) — `ui/live-edge-logic.ts#shouldShowBehindLive`'s own hysteresis state, updated once per
   * latency sample (`startLatencySampling`) rather than recomputed from nothing on every read, so
   * "currently showing" has a well-defined previous value for that hysteresis to compare against.
   */
  private readonly showBehindLive = signal(false);

  protected readonly latencyLabel = computed(() =>
    behindLiveChipLabel(this.transport(), this.behindLive(), this.showBehindLive()),
  );

  protected readonly latencyTitle = computed(() =>
    this.transport() === 'webrtc'
      ? 'WebRTC (WHEP) is sub-second, effectively live glass-to-glass.'
      : 'Distance behind the live edge, measured continuously. HLS inherently buffers ' +
        'several segments; WebRTC (WHEP), when reachable, cuts this to well under a second.',
  );

  protected readonly hoveredDetection = signal<Detection | null>(null);
  protected readonly tooltipX = signal(0);
  protected readonly tooltipY = signal(0);
  private drawnBoxes: readonly DrawnBox[] = [];

  protected readonly overlayInteractive = computed(
    () => shouldDrawOverlay(this.boxesMode(), this.detections().length > 0) && this.phase() === 'playing',
  );

  private hls: HlsType | null = null;
  private latencyTimer: ReturnType<typeof setInterval> | null = null;
  private coldStartTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private watchdogTimer: ReturnType<typeof setInterval> | null = null;
  private overlayTimer: ReturnType<typeof setInterval> | null = null;
  private mediaAbort: AbortController | null = null;
  private lastProgressAt = 0;
  private currentSrc: string | null = null;
  private generation = 0;
  /** `document.hidden` as of the latency timer's last tick — see `startLatencySampling`'s doc. */
  private wasDocumentHidden = false;

  // --- WHEP (docs/MVP2-PLAN.md §L / §U3) ---------------------------------------------------------
  private peerConnection: RTCPeerConnection | null = null;
  private whepAbort: AbortController | null = null;
  private whepWatchdogTimer: ReturnType<typeof setInterval> | null = null;
  private whepNoTrackTimer: ReturnType<typeof setTimeout> | null = null;
  private lastFrameAt = 0;
  private currentWhepUrl: string | null = null;

  constructor() {
    effect(() => {
      const src = this.src();
      const whepUrl = this.whepUrl();
      const suspended = this.suspended();
      const stopped = this.stopped();
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

    inject(DestroyRef).onDestroy(() => this.teardown());
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
   * place that's about to set a new one (docs/MVP2-PLAN.md §S, S-b bug fix). Before this, each of
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
      this.transportState.set(reduceTransportRecovery(this.transportState(), 'stopped'));
      return;
    }

    if ((!src && !whepUrl) || suspended) {
      this.transportState.set(reduceTransportRecovery(this.transportState(), 'reset'));
      return;
    }

    this.currentSrc = src;
    this.currentWhepUrl = whepUrl;
    // A genuine fresh attach (a new `src`/`whepUrl`) starts a brand new reconnect saga (docs/MVP2-
    // PLAN.md §S, S-c) — mirrors `transportState` being re-derived from scratch just below, rather
    // than carrying over whatever pacing a *previous* src/whepUrl's saga had accumulated.
    this.pacingState.set(INITIAL_PACING_STATE);
    const usesWhep = whepUrl !== null && shouldAttemptWhep(this.pacingState(), true, Date.now());
    this.transportState.set(reduceTransportRecovery(initialTransportState(usesWhep), 'attachStarted'));

    if (usesWhep && whepUrl) {
      this.pacingState.update((s) => advancePacing(s, 'whepAttempted', Date.now()));
      await this.beginWhepAttach(generation, whepUrl, src);
    } else if (src) {
      await this.beginAttach(generation, src);
    }
  }

  /**
   * Starts the next reconnect cycle (docs/MVP2-PLAN.md §S, S-c): tries WHEP again if this saga's
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
    const tryWhep = this.currentWhepUrl !== null && shouldAttemptWhep(this.pacingState(), true, now);
    this.transportState.set(reduceTransportRecovery(initialTransportState(tryWhep), 'attachStarted'));

    if (tryWhep && this.currentWhepUrl !== null) {
      this.pacingState.update((s) => advancePacing(s, 'whepAttempted', now));
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
      this.transportState.set(reduceTransportRecovery(this.transportState(), 'unsupported'));
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

    hls.on(Hls.Events.MANIFEST_PARSED, () => void video.play().catch(() => undefined));
    hls.on(Hls.Events.FRAG_BUFFERED, () => {
      this.lastProgressAt = Date.now();
      const wasReconnecting = this.transportState().recovery.phase === 'reconnecting';
      this.transportState.set(reduceTransportRecovery(this.transportState(), 'firstSegment'));
      this.pacingState.update((s) => advancePacing(s, 'playing', Date.now())); // docs/MVP2-PLAN.md §S, S-c
      if (wasReconnecting) {
        this.maybeSnapToLive('recovered'); // see docs/MVP2-PLAN.md §V, V-b — a fresh reattach earns a live-edge snap.
      }
    });
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (generation !== this.generation || !data.fatal) {
        return;
      }
      if (this.isColdStartMiss(data)) {
        this.transportState.set(reduceTransportRecovery(this.transportState(), 'playlistNotReady'));
        this.scheduleColdStartRetry(generation);
        return;
      }
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError(); // hls.js's own lighter-weight recovery, not a full reattach
        return;
      }
      this.transportState.set(reduceTransportRecovery(this.transportState(), 'fatalError'));
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
   * A never-yet-live stream's cold-start poll (docs/MVP2-PLAN.md §S, S-c rule 1): gentle and fixed
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
    const next = advancePacing(pacing, 'cycleFailed', Date.now());
    this.pacingState.set(next);
    this.coldStartTimer = setTimeout(() => this.beginNextCycle(generation), cyclePacingDelayMs(next));
  }

  /**
   * Schedules the next reconnect cycle after a genuine failure (not a cold-start miss) — the delay
   * and whether WHEP is retried both come from the shared, cross-cycle `pacingState` (docs/MVP2-
   * PLAN.md §S, S-c), not `transportState().recovery.attempt`'s own cycle-local counter.
   */
  private scheduleReconnect(generation: number): void {
    this.clearReconnectTimer(); // see its own doc comment — never leave a prior backoff orphaned
    const next = advancePacing(this.pacingState(), 'cycleFailed', Date.now());
    this.pacingState.set(next);
    const delay = cyclePacingDelayMs(next);
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
        this.transportState.set(reduceTransportRecovery(this.transportState(), 'stalled'));
        this.message.set('No new video for a while — reconnecting.');
        this.scheduleReconnect(generation);
      }
    }, WATCHDOG_TICK_MS);
  }

  /**
   * Dispatches a pacing `'healthTick'` (docs/MVP2-PLAN.md §S, S-c rule 3) whenever `phase ===
   * 'playing'` — a no-op otherwise. Called from both the HLS/native watchdog (`startWatchdog`) and
   * the WHEP one (`startWhepWatchdog`), which already tick every `WATCHDOG_TICK_MS` for their own
   * stall check regardless of transport, so this reuses that existing cadence rather than starting
   * a third timer.
   */
  private tickPacingHealth(): void {
    if (this.transportState().recovery.phase === 'playing') {
      this.pacingState.update((s) => advancePacing(s, 'healthTick', Date.now()));
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
        this.transportState.set(reduceTransportRecovery(this.transportState(), 'firstSegment'));
        this.pacingState.update((s) => advancePacing(s, 'playing', Date.now())); // docs/MVP2-PLAN.md §S, S-c
        if (wasReconnecting) {
          this.maybeSnapToLive('recovered'); // see docs/MVP2-PLAN.md §V, V-b — mirrors the hls.js FRAG_BUFFERED handler above.
        }
      },
      { signal: abortSignal },
    );
    video.addEventListener('progress', () => (this.lastProgressAt = Date.now()), { signal: abortSignal });
    video.addEventListener(
      'error',
      () => {
        if (generation !== this.generation) {
          return;
        }
        this.transportState.set(reduceTransportRecovery(this.transportState(), 'fatalError'));
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
   * Samples `behindLive` once a second and derives the two live-edge behaviors that ride on that
   * same cadence (docs/MVP2-PLAN.md §V, V-b) — no new timer for either:
   *
   *  - the chip's hysteresis (`showBehindLive`, `ui/live-edge-logic.ts#shouldShowBehindLive`);
   *  - a tab-visibility restore, detected by diffing `document.hidden` against what it was on the
   *    *previous* tick (there is no dedicated `visibilitychange` listener — this app already has
   *    the precedent of checking `document.hidden` from an existing per-second heartbeat rather
   *    than a separate event, see `core/poll-scheduler.ts`; unlike that poller, this timer
   *    deliberately keeps running while hidden, per this file's own pre-existing "a backgrounded
   *    tab's video should keep buffering" doc note above `startWatchdog`/`teardownMedia`).
   */
  private startLatencySampling(video: HTMLVideoElement): void {
    this.wasDocumentHidden = isDocumentHidden();
    this.latencyTimer = setInterval(() => {
      const hidden = isDocumentHidden();
      const becameVisible = this.wasDocumentHidden && !hidden;
      this.wasDocumentHidden = hidden;

      const measured = this.measureBehindLive(video);
      this.behindLive.set(measured);
      this.showBehindLive.set(shouldShowBehindLive(measured, this.showBehindLive()));

      if (becameVisible) {
        this.maybeSnapToLive('visibilityRestored', measured);
      }
    }, LATENCY_SAMPLE_MS);
  }

  /**
   * Forward-seeks to the live edge instead of leaving playback wherever its buffer sits — see
   * `ui/live-edge-logic.ts#SnapToLiveReason`'s own doc comment for what each reason covers and why.
   * A no-op for WHEP (no seekable buffer to fall behind — `behindLive` is pinned to `0`, so
   * `shouldSnapToLive`'s threshold branch would never fire anyway, but this also skips the always-
   * true `'recovered'` branch, which is never called from the WHEP attach path in the first place).
   */
  private maybeSnapToLive(reason: SnapToLiveReason, behindLiveSeconds: number | null = this.behindLive()): void {
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

  // --- WHEP attach (docs/MVP2-PLAN.md §L / §U3) --------------------------------------------------

  /**
   * POSTs an SDP offer straight to `whepUrl` (never proxied — see class doc), applies the answer,
   * and waits for a track. `hlsFallbackSrc` is threaded through purely so a failure can hand off to
   * `beginAttach` without the caller needing to remember it — this method never reads `this.src()`
   * itself, keeping the whole attach path driven by the values `reattach` captured at trigger time.
   */
  private async beginWhepAttach(generation: number, whepUrl: string, hlsFallbackSrc: string | null): Promise<void> {
    if (generation !== this.generation) {
      return;
    }
    this.teardownWhep();
    this.lastFrameAt = Date.now();
    this.startWhepWatchdog(generation, hlsFallbackSrc);

    if (typeof RTCPeerConnection === 'undefined') {
      this.handleWhepFailure(generation, hlsFallbackSrc, 'unsupported');
      return;
    }

    const controller = new AbortController();
    this.whepAbort = controller;

    try {
      const pc = new RTCPeerConnection();
      this.peerConnection = pc;
      pc.addTransceiver('video', { direction: 'recvonly' });

      pc.ontrack = (event) => {
        if (generation !== this.generation) {
          return;
        }
        const video = this.video().nativeElement;
        const stream = event.streams[0];
        if (stream && video.srcObject !== stream) {
          video.srcObject = stream;
          void video.play().catch(() => undefined);
        }
        this.lastFrameAt = Date.now();
        this.behindLive.set(0); // WHEP is effectively live — see class doc.
        this.clearWhepNoTrackTimer();
        this.transportState.set(reduceTransportRecovery(this.transportState(), 'firstSegment'));
        this.pacingState.update((s) => advancePacing(s, 'playing', Date.now())); // docs/MVP2-PLAN.md §S, S-c
        if (this.overlayTimer === null) {
          this.startOverlayLoop(); // ontrack can fire more than once on renegotiation
        }
      };

      pc.oniceconnectionstatechange = () => {
        if (generation !== this.generation) {
          return;
        }
        const state = pc.iceConnectionState;
        if (state === 'failed' || state === 'disconnected' || state === 'closed') {
          this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
        }
      };

      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      // Non-trickle: gather (bounded) before POSTing, so the offer's SDP carries usable candidates —
      // simpler than WHEP's optional trickle-ICE PATCH exchange, out of scope for this cycle.
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
      if (!response.ok) {
        throw new Error(`WHEP offer rejected: HTTP ${response.status}`);
      }
      const answerSdp = await response.text();
      if (generation !== this.generation) {
        return;
      }
      await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });

      this.scheduleWhepNoTrackTimeout(generation, hlsFallbackSrc);
    } catch {
      if (generation !== this.generation || controller.signal.aborted) {
        return;
      }
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

  private scheduleWhepNoTrackTimeout(generation: number, hlsFallbackSrc: string | null): void {
    this.whepNoTrackTimer = setTimeout(() => {
      if (generation === this.generation) {
        this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
      }
    }, WHEP_NO_TRACK_TIMEOUT_MS);
  }

  private clearWhepNoTrackTimer(): void {
    if (this.whepNoTrackTimer !== null) {
      clearTimeout(this.whepNoTrackTimer);
      this.whepNoTrackTimer = null;
    }
  }

  /** No frame progress for `STALL_WATCHDOG_MS` — the WHEP equivalent of `startWatchdog`'s HLS check. */
  private startWhepWatchdog(generation: number, hlsFallbackSrc: string | null): void {
    this.whepWatchdogTimer = setInterval(() => {
      if (generation !== this.generation || this.transportState().transport !== 'webrtc') {
        return;
      }
      this.tickPacingHealth();
      if (isStalled(this.lastFrameAt, Date.now())) {
        this.handleWhepFailure(generation, hlsFallbackSrc, 'stalled');
      }
    }, WATCHDOG_TICK_MS);
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
   * (docs/MVP2-PLAN.md §S, S-c).
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
    const next = reduceTransportRecovery(this.transportState(), event);
    this.transportState.set(next);

    if (next.transport === 'hls') {
      if (hlsFallbackSrc) {
        void this.beginAttach(generation, hlsFallbackSrc);
      } else {
        this.message.set('WebRTC playback failed and no HLS fallback is available for this stream.');
      }
      return;
    }

    const whepUrl = this.currentWhepUrl;
    const pacingNext = advancePacing(this.pacingState(), 'cycleFailed', Date.now());
    this.pacingState.set(pacingNext);
    const delay = cyclePacingDelayMs(pacingNext);
    this.clearReconnectTimer(); // see its own doc comment — never leave a prior backoff orphaned
    this.reconnectTimer = setTimeout(() => {
      if (generation === this.generation && whepUrl !== null) {
        this.pacingState.update((s) => advancePacing(s, 'whepAttempted', Date.now()));
        void this.beginWhepAttach(generation, whepUrl, hlsFallbackSrc);
      }
    }, delay);
  }

  private teardownWhep(): void {
    this.clearWhepNoTrackTimer();
    if (this.whepWatchdogTimer !== null) {
      clearInterval(this.whepWatchdogTimer);
      this.whepWatchdogTimer = null;
    }
    this.whepAbort?.abort();
    this.whepAbort = null;
    if (this.peerConnection) {
      this.peerConnection.ontrack = null;
      this.peerConnection.oniceconnectionstatechange = null;
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

  // --- Detection overlay (docs/CYCLES-PLAN.md §11 item 6) -------------------------------------

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
    const result = selectDetectionResult(results, Date.now(), this.behindLive(), DEFAULT_SLACK_BATCHES);
    if (!result || video.videoWidth === 0 || video.videoHeight === 0) {
      return;
    }

    const content = this.letterboxRect(width, height, video.videoWidth, video.videoHeight);
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

  private drawBox(ctx: CanvasRenderingContext2D, rect: DrawnBox, detection: Detection): void {
    const hovered = this.hoveredDetection() === detection;
    ctx.lineWidth = hovered ? 3 : 2;
    ctx.strokeStyle = hovered ? '#ffd479' : '#4f8cff';
    ctx.strokeRect(rect.x, rect.y, rect.width, rect.height);

    const label = `${detection.label} ${(detection.confidence * 100).toFixed(0)}%`;
    ctx.font = '11px ui-monospace, monospace';
    const metrics = ctx.measureText(label);
    const labelHeight = 14;
    ctx.fillStyle = hovered ? 'rgb(255 212 121 / 90%)' : 'rgb(79 140 255 / 85%)';
    ctx.fillRect(rect.x, Math.max(0, rect.y - labelHeight), metrics.width + 6, labelHeight);
    ctx.fillStyle = '#04101f';
    ctx.fillText(label, rect.x + 3, Math.max(labelHeight - 3, rect.y - 3));
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
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
    const video = this.video().nativeElement;
    video.removeAttribute('src');
    video.load();
    this.behindLive.set(null);
    this.showBehindLive.set(false); // hysteresis resets with every fresh attach, not carried over.
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
