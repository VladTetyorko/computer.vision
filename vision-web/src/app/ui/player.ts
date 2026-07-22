import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import type HlsType from 'hls.js';

export type PlayerState = 'idle' | 'connecting' | 'waiting' | 'playing' | 'error';

/** mediamtx serves 404 for a playlist until the first segments are muxed — that is normal. */
const FIRST_SEGMENT_RETRY_LIMIT = 40;
const RETRY_DELAY_MS = 1_500;
const LATENCY_SAMPLE_MS = 1_000;

/**
 * HLS player with an honest status line.
 *
 * Two deliberate choices, both from docs/UX-DESIGN.md §2 T1:
 *
 *  - `hls.js` is imported dynamically, so its ~90 KB lands in its own chunk and never
 *    touches the initial bundle of a user who only visits Devices.
 *  - The distance behind the live edge is measured and displayed continuously. HLS costs
 *    seconds of latency; a user told "≈6 s behind live" understands the trade, while a
 *    user shown a spinner concludes the app is broken.
 *
 * "Waiting for the first segment" is a first-class state rather than an error, because it
 * is the normal condition for the first few seconds of every new stream.
 */
@Component({
  selector: 'vision-player',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="frame" [class.compact]="compact()">
      <video #video playsinline muted autoplay [controls]="!compact()"></video>

      @if (state() !== 'playing') {
        <div class="overlay" [class.error]="state() === 'error'">
          @switch (state()) {
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
            @case ('error') {
              <span class="err-title">Playback failed</span>
              <span class="hint">{{ message() }}</span>
              <button type="button" class="btn secondary small" (click)="retry()">Retry</button>
            }
          }
        </div>
      }

      @if (state() === 'playing') {
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

    .overlay {
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

    .overlay.error {
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
  /** Playlist URL, or `null` when nothing is streaming. */
  readonly src = input<string | null>(null);

  /** Wall tiles set this when scrolled out of view so off-screen video stops decoding. */
  readonly suspended = input(false);

  /** Compact tiles hide native controls and the caption. */
  readonly compact = input(false);

  private readonly video = viewChild.required<ElementRef<HTMLVideoElement>>('video');

  protected readonly state = signal<PlayerState>('idle');
  protected readonly message = signal<string | null>(null);

  /** Seconds behind the live edge, or `null` while unknown. */
  private readonly behindLive = signal<number | null>(null);

  protected readonly latencyLabel = computed(() => {
    const behind = this.behindLive();
    return behind === null ? 'live · HLS' : `${behind.toFixed(1)}s behind · HLS`;
  });

  protected readonly latencyTitle = computed(
    () =>
      'Distance behind the live edge, measured continuously. HLS inherently buffers ' +
      'several segments; WebRTC egress (Phase 7) will cut this to well under a second.',
  );

  private hls: HlsType | null = null;
  private latencyTimer: ReturnType<typeof setInterval> | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private retries = 0;
  private generation = 0;

  constructor() {
    effect(() => {
      const src = this.src();
      const suspended = this.suspended();
      // Read inputs before the async teardown so the effect tracks them.
      void this.reattach(src, suspended);
    });
    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  protected retry(): void {
    this.retries = 0;
    void this.reattach(this.src(), this.suspended());
  }

  private async reattach(src: string | null, suspended: boolean): Promise<void> {
    const generation = ++this.generation;
    this.teardown();

    if (!src || suspended) {
      this.state.set('idle');
      return;
    }

    this.state.set('connecting');
    const video = this.video().nativeElement;

    // Safari and iOS play HLS natively; loading hls.js there would be pure overhead.
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src;
      this.watchNativePlayback(video);
      return;
    }

    const { default: Hls } = await import('hls.js');
    if (generation !== this.generation) {
      return; // src changed while the chunk was loading
    }
    if (!Hls.isSupported()) {
      this.fail('This browser cannot play HLS.');
      return;
    }

    const hls = new Hls({ lowLatencyMode: true, backBufferLength: 30 });
    this.hls = hls;
    hls.attachMedia(video);
    hls.loadSource(src);

    hls.on(Hls.Events.MANIFEST_PARSED, () => void video.play().catch(() => undefined));
    hls.on(Hls.Events.FRAG_BUFFERED, () => {
      this.retries = 0;
      this.state.set('playing');
    });
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) {
        return;
      }
      if (this.isAwaitingFirstSegment(data)) {
        this.state.set('waiting');
        this.scheduleReload(() => hls.startLoad());
        return;
      }
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError();
        return;
      }
      this.fail(data.details ?? 'The stream could not be loaded.');
    });

    this.startLatencySampling(video);
  }

  /** A 404 on the playlist means the muxer has not produced segments yet, not a failure. */
  private isAwaitingFirstSegment(data: { response?: { code?: number } }): boolean {
    const code = data.response?.code;
    return (code === 404 || code === 0) && this.retries < FIRST_SEGMENT_RETRY_LIMIT;
  }

  private scheduleReload(reload: () => void): void {
    this.retries++;
    this.retryTimer = setTimeout(reload, RETRY_DELAY_MS);
  }

  private watchNativePlayback(video: HTMLVideoElement): void {
    video.addEventListener('playing', () => this.state.set('playing'), { once: true });
    video.addEventListener(
      'error',
      () => this.fail('The browser could not load this stream.'),
      { once: true },
    );
    void video.play().catch(() => undefined);
    this.startLatencySampling(video);
  }

  private startLatencySampling(video: HTMLVideoElement): void {
    this.latencyTimer = setInterval(() => {
      this.behindLive.set(this.measureBehindLive(video));
    }, LATENCY_SAMPLE_MS);
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

  private fail(reason: string): void {
    this.message.set(reason);
    this.state.set('error');
  }

  private teardown(): void {
    if (this.latencyTimer !== null) {
      clearInterval(this.latencyTimer);
      this.latencyTimer = null;
    }
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
    this.behindLive.set(null);
    this.message.set(null);
  }
}
