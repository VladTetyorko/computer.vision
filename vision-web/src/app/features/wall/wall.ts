import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { EventsStore } from '../../core/events/events-store';
import { resolveEventTarget, resolveReplayDeepLink } from '../../core/events/events-logic';
import type { DetectionEvent } from '../../core/api/models';
import { WallTile } from './wall-tile';
import { EventsRail } from '../../shared/ui/events-rail';

@Component({
  selector: 'vision-wall',
  imports: [WallTile, RouterLink, EventsRail],
  templateUrl: './wall.html',
  styleUrl: './wall.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WallPage {
  private readonly router = inject(Router);
  private readonly api = inject(VisionApi);
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
  // own markup/filters/clock into `shared/ui/events-rail.ts` — this page still owns the shared feed's
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

  /**
   * Navigates to the event's replay deep link (docs/OPS-CORE-PLAN.md §Q1) when a finished covering
   * usage resolves (a lazy, click-time-only lookup — see `shared/ui/notification-bell.ts`'s own
   * doc comment for the identical idiom), else the event's asset detail page, or its live view when
   * no asset resolved yet.
   */
  protected async openEvent(event: DetectionEvent): Promise<void> {
    if (event.assetId) {
      try {
        const asset = await this.api.getAsset(event.assetId);
        const deepLink = resolveReplayDeepLink(event, asset.recentUsages);
        if (deepLink) {
          void this.router.navigate(['/replay'], {
            queryParams: { asset: event.assetId, usage: deepLink.usageId, t: deepLink.offsetMs },
          });
          return;
        }
      } catch {
        // Falls through to the pre-existing target below.
      }
    }
    const target = resolveEventTarget(event, this.fleet.streams());
    if (!target) {
      return;
    }
    void this.router.navigate(target.kind === 'asset' ? ['/assets', target.id] : ['/live', target.id]);
  }
}
