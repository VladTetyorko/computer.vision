import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { EventsStore } from '../../core/events-store';
import { PollScheduler } from '../../core/poll-scheduler';
import {
  describeEventSource,
  distinctLabels,
  filterEvents,
  relativeTimeLabel,
  resolveEventTarget,
} from '../../core/events-logic';
import type { DetectionEvent } from '../../core/api/models';
import { WallTile } from './wall-tile';

/** How many rows the rail shows at once — a "recent activity" feed, not the full retained history. */
const WALL_EVENTS_DISPLAY_LIMIT = 20;

/** How often the rail's relative "…s ago" timestamps re-render, independent of the events poll. */
const CLOCK_TICK_MS = 1_000;

@Component({
  selector: 'vision-wall',
  imports: [WallTile, RouterLink],
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

  // --- Events rail (docs/MVP2-PLAN.md §E, E-b bullet 1) ---------------------------------------

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

  protected readonly railEvents = computed(() => this.filteredEvents().slice(0, WALL_EVENTS_DISPLAY_LIMIT));

  constructor() {
    // "O(visible) discipline" (docs/MVP2-PLAN.md §E, E-b bullet 5) — see `EventsStore`'s own doc
    // comment: this is one of exactly three pages that keeps the shared events poll alive.
    this.events.activate();
    const stopClock = inject(PollScheduler).schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(() => {
      this.events.release();
      stopClock();
    });
  }

  protected setDensity(value: string): void {
    this.settings.wallDensity.set(Number(value));
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

  /** Navigates to the event's asset detail page, or its live view when no asset resolved yet. */
  protected openEvent(event: DetectionEvent): void {
    const target = resolveEventTarget(event, this.fleet.streams());
    if (!target) {
      return;
    }
    void this.router.navigate(target.kind === 'asset' ? ['/assets', target.id] : ['/live', target.id]);
  }
}
