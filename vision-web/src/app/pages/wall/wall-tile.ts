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
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Player, type BoxesMode } from '../../ui/player';
import { TelemetryStore } from '../../core/telemetry-store';
import { DetectionsStore } from '../../core/detections-store';
import type { ActiveStream, Device } from '../../core/api/models';

/** Start decoding slightly before a tile scrolls into view, so it is ready on arrival. */
const PREROLL_MARGIN = '250px';

/** `boxesMode` cycles through these three in order — see `cycleBoxesMode`. */
const BOXES_MODE_CYCLE: readonly BoxesMode[] = ['overlay', 'burned', 'off'];

/**
 * One live tile.
 *
 * Suspends its player while off-screen: a 30-camera wall that decodes every tile at once
 * saturates the CPU and drops frames on the tiles the user is actually looking at
 * (docs/WEB-PLAN.md, W6).
 *
 * Also carries its own `TelemetryStore`/`DetectionsStore` (docs/CYCLES-PLAN.md §2,
 * docs/CYCLES-PLAN.md §11 item 6): a tile only exists for a device that is currently streaming
 * (`WallPage` builds `tiles()` from `fleet.streams()`), so "only show telemetry/detections while
 * the tile's stream is live" is automatic — what this component adds is gating both polls on
 * on-screen visibility too, the same idea as suspending the player, so a 30-tile wall doesn't run
 * 30 telemetry/detections pollers for tiles nobody is looking at (the O(visible) posture item 4
 * asks for). The per-tile "boxes: overlay/burned/off" toggle (item 6) is a tiny cycling button
 * rather than three buttons — there is no room for a labeled toggle group at wall-tile scale.
 */
@Component({
  selector: 'vision-wall-tile',
  imports: [Player, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TelemetryStore, DetectionsStore],
  template: `
    <article class="tile">
      <vision-player
        [src]="stream().viewUrl ?? null"
        [whepUrl]="stream().whepUrl ?? null"
        [suspended]="!visible()"
        [compact]="true"
        [detections]="detections.results()"
        [boxesMode]="boxesMode()"
      />
      <footer>
        <a class="name truncate" [routerLink]="['/live', stream().deviceId]">
          {{ device()?.name ?? stream().deviceId }}
        </a>
        <div class="row chips">
          @if (telemetry.hasTelemetry()) {
            <span class="chip telemetry-chip" [class.stale]="telemetry.stale()">
              @if (batteryLabel(); as battery) {
                <span>⬢{{ battery }}</span>
              }
              @if (altitudeLabel(); as altitude) {
                <span>▲{{ altitude }}</span>
              }
            </span>
          }
          <button
            type="button"
            class="boxes-btn"
            (click)="cycleBoxesMode()"
            [title]="'Detection boxes: ' + boxesMode() + ' (click to cycle)'"
          >
            ▢{{ boxesMode() === 'overlay' ? '' : boxesMode() === 'burned' ? '·' : '×' }}
          </button>
        </div>
      </footer>
    </article>
  `,
  styles: `
    .tile {
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      overflow: hidden;
      transition: border-color 0.15s ease;
    }

    .tile:hover {
      border-color: var(--border-strong);
    }

    footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      padding: 0.45rem 0.6rem;
    }

    .chips {
      gap: 0.3rem;
    }

    .name {
      color: var(--text);
      font-size: 0.85rem;
      font-weight: 500;
      min-width: 0;
    }

    .name:hover {
      color: var(--accent);
    }

    .telemetry-chip {
      font-size: 0.72rem;
      white-space: nowrap;
    }

    .telemetry-chip.stale {
      color: var(--danger);
      border-color: var(--danger);
    }

    .boxes-btn {
      cursor: pointer;
      background: transparent;
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      color: var(--text-faint);
      font-size: 0.7rem;
      line-height: 1;
      padding: 0.15rem 0.35rem;
    }

    .boxes-btn:hover {
      color: var(--text);
      border-color: var(--border-strong);
    }
  `,
})
export class WallTile {
  readonly stream = input.required<ActiveStream>();
  readonly device = input<Device | undefined>();

  protected readonly visible = signal(true);
  protected readonly telemetry = inject(TelemetryStore);
  protected readonly detections = inject(DetectionsStore);
  protected readonly boxesMode = signal<BoxesMode>('overlay');

  private readonly hasTelemetryCapability = computed(() =>
    (this.device()?.capabilities ?? []).includes('TELEMETRY'),
  );

  protected cycleBoxesMode(): void {
    const currentIndex = BOXES_MODE_CYCLE.indexOf(this.boxesMode());
    this.boxesMode.set(BOXES_MODE_CYCLE[(currentIndex + 1) % BOXES_MODE_CYCLE.length]);
  }

  protected readonly batteryLabel = computed(() => {
    const percent = this.telemetry.latest()?.batteryPercent;
    return percent === undefined ? null : `${percent.toFixed(0)}%`;
  });

  protected readonly altitudeLabel = computed(() => {
    const meters = this.telemetry.latest()?.altitudeMeters;
    return meters === undefined ? null : `${meters.toFixed(0)}m`;
  });

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement;
    const observer = new IntersectionObserver(
      (entries) => this.visible.set(entries.some((entry) => entry.isIntersecting)),
      { rootMargin: PREROLL_MARGIN },
    );
    observer.observe(host);
    inject(DestroyRef).onDestroy(() => observer.disconnect());

    // Detections poll the same way — on-screen only (docs/CYCLES-PLAN.md §11 item 4/6), no
    // capability gate (any streaming device can have CV running on its stream).
    effect(() => {
      if (this.visible()) {
        this.detections.track(this.stream().streamId);
      } else {
        this.detections.reset();
      }
    });

    // Poll only for a telemetry-capable device that is actually on-screen — off-screen tiles
    // already stop decoding video (above), so they stop polling telemetry too.
    effect(() => {
      const deviceId = this.stream().deviceId;
      if (this.hasTelemetryCapability() && this.visible()) {
        this.telemetry.track(deviceId);
      } else {
        this.telemetry.reset();
      }
    });
  }
}
