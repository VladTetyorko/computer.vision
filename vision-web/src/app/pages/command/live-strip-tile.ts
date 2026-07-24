import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { PollScheduler } from '../../core/poll-scheduler';
import { attentionAgeLabel, batteryAttentionSeverity, shouldPollSnapshot } from './command-logic';
import type { AssetAttention } from '../../core/api/models';

/** How often a *visible* tile re-fetches its snapshot (docs/MVP3-PLAN.md §C-a's "~1/5s per visible tile"). */
const SNAPSHOT_POLL_INTERVAL_MS = 5_000;

/** Same pre-roll margin `pages/wall/wall-tile.ts` uses before deciding a tile is worth polling. */
const PREROLL_MARGIN = '250px';

/**
 * One live-strip tile (docs/MVP3-PLAN.md §C-c bullet 2): a snapshot thumbnail (`GET
 * /api/streams/{id}/snapshot` into an `<img>`, cache-busted on every poll so the browser actually
 * re-requests it — server responses are `Cache-Control: no-store` already, but an unchanged `src`
 * string is a no-op for an `<img>` regardless of response headers), name, battery/age chips, and a
 * click-to-watch action.
 *
 * **Visibility-gated, mirroring `pages/wall/wall-tile.ts`'s own `IntersectionObserver`** — the
 * identical "off-screen things stop polling" idiom that page already established for its own
 * telemetry/detections pollers, applied here to snapshot polling instead. `CommandPage` renders
 * this via CDK virtual scroll (only a viewport-sized window of tiles ever mounts at all, see
 * `command.html`), so this observer is a second, more precise layer on top of that coarser
 * mount/unmount bound — a tile just inside the virtual-scroll buffer but not actually on screen
 * still doesn't poll. `shouldPollSnapshot` (`command-logic.ts`) is the pure rule this drives.
 *
 * **404s degrade to a placeholder, not an error** — a fresh stream that hasn't published a frame
 * yet (docs/MVP3-PLAN.md C-a's own documented 404 case) is exactly as likely as a genuine problem,
 * so `onError` never toasts, it just shows "No preview" until a later poll succeeds.
 */
@Component({
  selector: 'vision-live-strip-tile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="tile" (click)="watch.emit(asset().assetId)" [title]="'Watch ' + asset().displayName">
      <div class="thumb" [class.placeholder]="status() !== 'ok'">
        @if (snapshotSrc(); as src) {
          <img [src]="src" alt="" [class.hidden]="status() !== 'ok'" (load)="onLoad()" (error)="onError()" />
        }
        @if (status() !== 'ok') {
          <span class="thumb-fallback muted">{{ status() === 'error' ? 'No preview' : 'Loading…' }}</span>
        }
      </div>
      <div class="tile-meta">
        <span class="name truncate">{{ asset().displayName }}</span>
        <div class="row chips">
          @if (batteryLabel(); as battery) {
            <span class="chip" [class.warn]="batterySeverity() === 'warning'" [class.danger]="batterySeverity() === 'critical'">
              {{ battery }}
            </span>
          }
          <span class="chip faint">{{ ageLabel() }}</span>
        </div>
      </div>
    </button>
  `,
  styles: `
    :host {
      display: block;
    }

    .tile {
      display: flex;
      flex-direction: column;
      width: 168px;
      cursor: pointer;
      text-align: left;
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      overflow: hidden;
      transition: border-color 0.15s ease;
    }

    .tile:hover {
      border-color: var(--border-strong);
    }

    .thumb {
      position: relative;
      aspect-ratio: 16 / 9;
      background: var(--panel-raised);
    }

    .thumb img {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .thumb img.hidden {
      display: none;
    }

    .thumb-fallback {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 0.72rem;
    }

    .tile-meta {
      display: flex;
      flex-direction: column;
      gap: 0.3rem;
      padding: 0.5rem 0.6rem;
    }

    .name {
      color: var(--text);
      font-size: 0.82rem;
      font-weight: 500;
    }

    .chips {
      gap: 0.3rem;
      flex-wrap: wrap;
    }
  `,
})
export class LiveStripTile {
  readonly asset = input.required<AssetAttention>();

  /** The clicked asset's id — `CommandPage` navigates to `/fly?asset=<id>&watch=1`. */
  readonly watch = output<string>();

  private readonly api = inject(VisionApi);

  protected readonly visible = signal(true);
  private readonly cacheBust = signal(0);
  protected readonly status = signal<'loading' | 'ok' | 'error'>('loading');

  /**
   * `null` while off-screen too, not just while polling is paused — mirrors `WallTile` passing
   * `[suspended]` straight through to its player: an off-screen tile issues *no* request at all,
   * not even a first one, until it actually scrolls into view.
   */
  protected readonly snapshotSrc = computed(() => {
    const streamId = this.asset().streamId;
    if (streamId === undefined || !this.visible()) {
      return null;
    }
    return `${this.api.snapshotUrl(streamId)}?t=${this.cacheBust()}`;
  });

  protected readonly batterySeverity = computed(() => batteryAttentionSeverity(this.asset().batteryPercent));

  protected readonly batteryLabel = computed(() => {
    const percent = this.asset().batteryPercent;
    return percent === undefined ? null : `${Math.round(percent)}%`;
  });

  protected readonly ageLabel = computed(() => attentionAgeLabel(this.asset()));

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement;
    const observer = new IntersectionObserver(
      (entries) => this.visible.set(entries.some((entry) => entry.isIntersecting)),
      { rootMargin: PREROLL_MARGIN },
    );
    observer.observe(host);

    const scheduler = inject(PollScheduler);
    const stopPoll = scheduler.schedule(SNAPSHOT_POLL_INTERVAL_MS, () => {
      if (shouldPollSnapshot(this.visible(), this.asset().streamId !== undefined)) {
        this.cacheBust.update((n) => n + 1);
      }
    });

    inject(DestroyRef).onDestroy(() => {
      observer.disconnect();
      stopPoll();
    });
  }

  protected onLoad(): void {
    this.status.set('ok');
  }

  protected onError(): void {
    this.status.set('error');
  }
}
