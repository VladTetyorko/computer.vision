import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { EventsStore } from '../../core/events-store';
import { resolveEventTarget } from '../../core/events-logic';
import type { DetectionEvent } from '../../core/api/models';
import { WallTile } from './wall-tile';
import { EventsRail } from '../../ui/events-rail';

@Component({
  selector: 'vision-wall',
  imports: [WallTile, RouterLink, EventsRail],
  templateUrl: './wall.html',
  styleUrl: './wall.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WallPage {
  private readonly router = inject(Router);
  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly events = inject(EventsStore);

  protected readonly densities = [2, 3, 4, 5, 6] as const;

  protected readonly tiles = computed(() =>
    this.fleet.streams().map((stream) => ({
      stream,
      device: this.fleet.device(stream.deviceId),
    })),
  );

  /** Streams with no publisher URL cannot be watched; say so instead of showing black boxes. */
  protected readonly unwatchable = computed(
    () => this.fleet.streams().filter((stream) => !stream.viewUrl).length,
  );

  // --- Events rail (docs/MVP2-PLAN.md §E, E-b bullet 1; docs/MVP3-PLAN.md §C-c moved the rail's
  // own markup/filters/clock into `ui/events-rail.ts` — this page still owns the shared feed's
  // activate/release lifecycle and its own click-to-navigate target resolution, see that
  // component's own doc comment for why the split lands there) ------------------------------

  constructor() {
    // "O(visible) discipline" (docs/MVP2-PLAN.md §E, E-b bullet 5) — see `EventsStore`'s own doc
    // comment: this is one of exactly three pages that keeps the shared events poll alive.
    this.events.activate();
    inject(DestroyRef).onDestroy(() => this.events.release());
  }

  protected setDensity(value: string): void {
    this.settings.wallDensity.set(Number(value));
  }

  /** Navigates to the event's asset detail page, or its live view when no asset resolved yet. */
  protected openEvent(event: DetectionEvent): void {
    const target = resolveEventTarget(event, this.fleet.streams());
    if (!target) {
      return;
    }
    void this.router.navigate(target.kind === 'asset' ? ['/assets', target.id] : ['/live', target.id]);
  }
}
