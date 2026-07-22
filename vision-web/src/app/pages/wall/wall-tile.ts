import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Player } from '../../ui/player';
import type { ActiveStream, Device } from '../../core/api/models';

/** Start decoding slightly before a tile scrolls into view, so it is ready on arrival. */
const PREROLL_MARGIN = '250px';

/**
 * One live tile.
 *
 * Suspends its player while off-screen: a 30-camera wall that decodes every tile at once
 * saturates the CPU and drops frames on the tiles the user is actually looking at
 * (docs/WEB-PLAN.md, W6).
 */
@Component({
  selector: 'vision-wall-tile',
  imports: [Player, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="tile">
      <vision-player [src]="stream().viewUrl ?? null" [suspended]="!visible()" [compact]="true" />
      <footer>
        <a class="name truncate" [routerLink]="['/live', stream().deviceId]">
          {{ device()?.name ?? stream().deviceId }}
        </a>
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
  `,
})
export class WallTile {
  readonly stream = input.required<ActiveStream>();
  readonly device = input<Device | undefined>();

  protected readonly visible = signal(true);

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement;
    const observer = new IntersectionObserver(
      (entries) => this.visible.set(entries.some((entry) => entry.isIntersecting)),
      { rootMargin: PREROLL_MARGIN },
    );
    observer.observe(host);
    inject(DestroyRef).onDestroy(() => observer.disconnect());
  }
}
