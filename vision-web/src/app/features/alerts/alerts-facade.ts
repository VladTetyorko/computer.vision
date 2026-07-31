import { DestroyRef, Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { resolveEventTarget, resolveReplayDeepLink } from '../../core/events/events-logic';
import type { DetectionEvent } from '../../core/api/models';

/**
 * `AlertsPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — `/monitor/alerts`, docs/UI-REDESIGN-PLAN.md
 * Wave 4's **SPLIT** "Alerts center": the live detection-events feed is functional (this class),
 * saved threshold rules + acknowledge are not built (the page's own honest inline note covers that,
 * no fake control here). `activate()`/`release()`/`openEvent` are byte-for-byte
 * `features/wall/wall-facade.ts`'s own events-rail wiring — this is now the third page-scoped
 * `EventsStore` consumer alongside `WallPage`/`AssetDetailPage` (the header bell is the fourth,
 * permanently-mounted one — see `EventsStore`'s own class doc comment for the full "O(visible)
 * discipline, mostly moot once the bell never releases" writeup).
 */
@Injectable()
export class AlertsFacade {
  private readonly router = inject(Router);
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  readonly events = inject(EventsStore);

  constructor() {
    this.events.activate();
    inject(DestroyRef).onDestroy(() => this.events.release());
  }

  /**
   * Navigates to the event's replay deep link (docs/OPS-CORE-PLAN.md §Q1) when a finished covering
   * usage resolves (a lazy, click-time-only lookup — mirrors `WallFacade.openEvent`/
   * `shared/ui/notification-bell.ts`'s identical idiom), else the event's asset detail page, or its
   * live view when no asset resolved yet.
   */
  async openEvent(event: DetectionEvent): Promise<void> {
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
