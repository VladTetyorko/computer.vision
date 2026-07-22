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
import { Player } from '../../ui/player';
import { TelemetryStore } from '../../core/telemetry-store';
import type { ActiveStream, Device } from '../../core/api/models';

/** Start decoding slightly before a tile scrolls into view, so it is ready on arrival. */
const PREROLL_MARGIN = '250px';

/**
 * One live tile.
 *
 * Suspends its player while off-screen: a 30-camera wall that decodes every tile at once
 * saturates the CPU and drops frames on the tiles the user is actually looking at
 * (docs/WEB-PLAN.md, W6).
 *
 * Also carries its own `TelemetryStore` (docs/CYCLES-PLAN.md §2): a tile only exists for a
 * device that is currently streaming (`WallPage` builds `tiles()` from `fleet.streams()`), so
 * "only show the chip while the tile's stream is live" is automatic — what this component adds
 * is gating the poll on on-screen visibility too, the same idea as suspending the player, so a
 * 30-tile wall doesn't run 30 telemetry pollers for tiles nobody is looking at.
 */
@Component({
  selector: 'vision-wall-tile',
  imports: [Player, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [TelemetryStore],
  template: `
    <article class="tile">
      <vision-player [src]="stream().viewUrl ?? null" [suspended]="!visible()" [compact]="true" />
      <footer>
        <a class="name truncate" [routerLink]="['/live', stream().deviceId]">
          {{ device()?.name ?? stream().deviceId }}
        </a>
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
  `,
})
export class WallTile {
  readonly stream = input.required<ActiveStream>();
  readonly device = input<Device | undefined>();

  protected readonly visible = signal(true);
  protected readonly telemetry = inject(TelemetryStore);

  private readonly hasTelemetryCapability = computed(() =>
    (this.device()?.capabilities ?? []).includes('TELEMETRY'),
  );

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
