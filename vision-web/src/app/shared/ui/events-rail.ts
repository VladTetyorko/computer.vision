import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, output, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { describeEventSource, distinctLabels, filterEvents, relativeTimeLabel, resolveEventTarget } from '../../core/events/events-logic';
import { EventRow } from './event-row';
import type { DetectionEvent } from '../../core/api/models';

/** How many rows the rail shows at once — a "recent activity" feed, not the full retained history. */
const EVENTS_DISPLAY_LIMIT = 20;

/** How often the rail's relative "…s ago" timestamps re-render, independent of the events poll. */
const CLOCK_TICK_MS = 1_000;

/**
 * The detection-events rail (docs/plans/done/MVP2-PLAN.md §E, E-b bullet 1): label/asset filters over
 * `EventsStore`'s shared feed, a newest-first list capped to `EVENTS_DISPLAY_LIMIT`, each row
 * naming its source/confidence/relative time and, when resolvable, opening the owning asset's
 * cockpit or detail page on click.
 *
 * **Moved here from `pages/wall/wall.html`/`wall.ts`** (docs/plans/done/MVP3-PLAN.md §C-c) when the Command
 * dashboard needed the identical rail as its own "events feed" section — this codebase's
 * established "move to a shared home once a second page needs it" precedent (see
 * `core/fleet/device-logic.ts`'s doc comment, most recently repeated by `shared/map/fleet-map.ts`/
 * `shared/map/live-dock.ts`'s own moves for the same reason). `WallPage` now renders `<vision-events-rail>`
 * in place of its old inline markup; behavior — filters, cap, row content, click target — is
 * byte-for-byte unchanged, only the internal `.event-list`/`.event-row` styling and the
 * label/asset-filter/clock state moved with it. Positioning (width, sticky, responsive breakpoint)
 * stays each host page's own concern (`wall.css`'s `.events-rail`/`command.css`'s own class,
 * applied to this component's own host element, mirroring `shared/map/fleet-map.ts`'s `.map-panel`
 * precedent) — this component only owns what's *inside* its own card.
 *
 * **Does not manage `EventsStore.activate()`/`release()` itself** — like `shared/map/fleet-map.ts` injecting
 * `EventsStore` directly for its own event markers, that lifecycle stays the host page's job
 * (`WallPage`/`CommandPage` each call `activate()`/`release()` once in their own constructor,
 * exactly as before); a page that renders both this rail and the fleet map only needs to own the
 * refcount once, not per consumer.
 *
 * **Row markup extracted to `vision-event-row`** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4/Wave 3,
 * docs/extracts/design/08-alerts.md's own refactor list item 1) — this component still owns filters, the cap,
 * and every derivation (`sourceLabel`/`relativeTime`/`eventClickable`/`eventActionLabel` below), it
 * just no longer hand-rolls the row's own DOM; `/monitor/alerts` (which used to embed this whole
 * component to get the identical row look) now renders `vision-event-row` directly instead, in its
 * own `dense` variant — see that component's own doc comment for why the two hosts get different
 * density rather than one shape forced onto both.
 */
@Component({
  selector: 'vision-events-rail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EventRow],
  templateUrl: './events-rail.html',
  styleUrl: './events-rail.css',
})
export class EventsRail {
  private readonly fleet = inject(FleetStore);
  protected readonly events = inject(EventsStore);

  /** Emits the row's own event; the host page resolves navigation via `resolveEventTarget`. */
  readonly open = output<DetectionEvent>();

  private readonly nowSignal = signal(Date.now());

  protected readonly labelFilter = signal('');
  protected readonly assetFilter = signal('');

  protected readonly availableLabels = computed(() => distinctLabels(this.events.events()));

  /** One entry per distinct `assetId` seen in the feed, labeled with `describeEventSource`. */
  protected readonly availableAssets = computed(() => {
    const seen = new Map<string, string>();
    for (const event of this.events.events()) {
      if (event.assetId && !seen.has(event.assetId)) {
        seen.set(event.assetId, this.sourceLabel(event));
      }
    }
    return [...seen.entries()].map(([id, name]) => ({ id, name }));
  });

  private readonly filteredEvents = computed(() =>
    filterEvents(this.events.events(), {
      label: this.labelFilter() || undefined,
      assetId: this.assetFilter() || undefined,
    }),
  );

  protected readonly railEvents = computed(() => this.filteredEvents().slice(0, EVENTS_DISPLAY_LIMIT));

  constructor() {
    const stopClock = inject(PollScheduler).schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(stopClock);
  }

  protected setLabelFilter(value: string): void {
    this.labelFilter.set(value);
  }

  protected setAssetFilter(value: string): void {
    this.assetFilter.set(value);
  }

  protected sourceLabel(event: DetectionEvent): string {
    return describeEventSource(event, this.fleet.devices(), this.fleet.streams());
  }

  protected relativeTime(event: DetectionEvent): string {
    return relativeTimeLabel(event.lastSeen, this.nowSignal());
  }

  protected eventClickable(event: DetectionEvent): boolean {
    return resolveEventTarget(event, this.fleet.streams()) !== undefined;
  }

  /**
   * The row's own explicit affordance (docs/plans/done/UX-REWORK-PLAN.md U-a2 §2.6 — a hover-only style isn't
   * a label): which of the two-verb dictionary a click actually does, since `resolveEventTarget`
   * resolves either an asset (→ "Details") or a live stream (→ "Watch live"); `null` when neither
   * resolves, matching `eventClickable`'s own `false` — the row shows no promise it can't keep.
   */
  protected eventActionLabel(event: DetectionEvent): 'Watch live' | 'Details' | null {
    const target = resolveEventTarget(event, this.fleet.streams());
    if (!target) {
      return null;
    }
    return target.kind === 'asset' ? 'Details' : 'Watch live';
  }
}
