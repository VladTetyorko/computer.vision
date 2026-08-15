import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { ToastService } from '../../core/toast.service';
import { describeHttpError } from '../../core/api-error';
import {
  capitalizeLabel,
  describeEventSource,
  distinctLabels,
  filterEvents,
  relativeTimeLabel,
  resolveEventTarget,
  resolveReplayDeepLink,
} from '../../core/events/events-logic';
import type { DetectionEvent } from '../../core/api/models';

/** How often the list's own relative "…s ago" timestamps re-render, independent of the events poll. */
const CLOCK_TICK_MS = 1_000;

/**
 * `AlertsPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/monitor/alerts`, Wave 3 of
 * docs/plans/done/NAV-IA-REDESIGN-PLAN.md (§2.4, docs/extracts/design/08-alerts.md): a two-pane triage view over the
 * shared `EventsStore` feed. Saved threshold rules + acknowledge are still not built (the page's own
 * honest `<vision-notice>` covers that, unchanged from before this task).
 *
 * **No longer embeds `<vision-events-rail>`** (the pre-Wave-3 shape, see this file's own git
 * history) — that component's own `EVENTS_DISPLAY_LIMIT`=20 cap and 300px-rail layout are right for
 * a "recent activity" sidebar, wrong for this page's job as the *full* triage list
 * (docs/extracts/design/08-alerts.md's own acceptance: "≥20 events visible… without scrolling", which implies
 * more than 20 may exist beyond that). This facade re-derives the identical label/asset filtering
 * `EventsRail` used to own internally (`distinctLabels`/`filterEvents`, the same pure functions,
 * unchanged) now that this page renders its own list of `vision-event-row[dense]` instead of
 * embedding the rail wholesale.
 */
@Injectable()
export class AlertsFacade {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  private readonly toasts = inject(ToastService);
  readonly events = inject(EventsStore);

  private readonly nowSignal = signal(Date.now());

  readonly labelFilter = signal('');
  readonly assetFilter = signal('');

  readonly availableLabels = computed(() => distinctLabels(this.events.events()));

  /** One entry per distinct `assetId` seen in the feed, labeled with `describeEventSource`. */
  readonly availableAssets = computed(() => {
    const seen = new Map<string, string>();
    for (const event of this.events.events()) {
      if (event.assetId && !seen.has(event.assetId)) {
        seen.set(event.assetId, this.sourceLabel(event));
      }
    }
    return [...seen.entries()].map(([id, name]) => ({ id, name }));
  });

  readonly filteredEvents = computed(() =>
    filterEvents(this.events.events(), {
      label: this.labelFilter() || undefined,
      assetId: this.assetFilter() || undefined,
    }),
  );

  /**
   * `?sel=<eventId>` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4) — read straight off the route, not via a
   * component input, since this facade already owns navigation (`select`/`clearSelection` below)
   * and every other piece of page state; keeping the two together avoids a third layer relaying the
   * param between the route and this class.
   */
  private readonly queryParamMap = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });
  readonly selectedId = computed(() => this.queryParamMap().get('sel') ?? undefined);

  /**
   * Resolved against the **full** retained feed, not `filteredEvents` — a selection survives the
   * user tightening a filter afterward (the row just leaves the visible list; the detail pane keeps
   * describing it, since the point of `?sel=` surviving refresh/Back is that it survives, not that
   * it's contingent on today's filter state too). Degrades to `undefined` — never a crash — the
   * moment the id doesn't match anything currently retained: a bogus id typed into the URL, or one
   * evicted from `EventsStore`'s own `MAX_RETAINED_EVENTS` cap (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4's
   * own "must degrade to no selection" rule).
   */
  readonly selectedEvent = computed(() => this.events.events().find((event) => event.id === this.selectedId()));

  readonly selectedEventLabel = computed(() => {
    const event = this.selectedEvent();
    return event ? `${capitalizeLabel(event.label)} · ${this.sourceLabel(event)}` : 'Event';
  });

  constructor() {
    this.events.activate();
    const stopClock = inject(PollScheduler).schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(() => {
      this.events.release();
      stopClock();
    });
  }

  sourceLabel(event: DetectionEvent): string {
    return describeEventSource(event, this.fleet.devices(), this.fleet.streams());
  }

  relativeTime(event: DetectionEvent): string {
    return relativeTimeLabel(event.lastSeen, this.nowSignal());
  }

  /** The row's own click target — selects, never navigates (§2.4: "selecting a row does not navigate"). */
  select(id: string): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { sel: id },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** `vision-two-pane`'s own `detailClose` (Esc, scrim click, the pane's own close button). */
  clearSelection(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { sel: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** Whether the detail pane's "Open cockpit" button has anywhere to send the operator. */
  canOpenCockpit(event: DetectionEvent): boolean {
    return resolveEventTarget(event, this.fleet.streams()) !== undefined;
  }

  /**
   * `docs/extracts/design/08-alerts.md`'s two actions, split out of the old single "smart" row-click
   * (`resolveEventTarget` then `resolveReplayDeepLink`) this page used to do on every click before
   * selecting became the row's job instead — an asset-tied event opens `/fly?asset=…` (the same
   * `?asset=` deep-link `FlyPage`/`CommandPage` already read, docs/plans/done/MVP3-PLAN.md §C-b), a
   * device-only one with a live stream opens `/live/:deviceId` (the single-device cockpit) — both
   * genuinely "a cockpit", just two different pages depending on whether an asset resolved.
   */
  openCockpit(event: DetectionEvent): void {
    const target = resolveEventTarget(event, this.fleet.streams());
    if (!target) {
      return;
    }
    if (target.kind === 'asset') {
      void this.router.navigate(['/fly'], { queryParams: { asset: target.id } });
    } else {
      void this.router.navigate(['/live', target.id]);
    }
  }

  /** Whether the detail pane's "Jump to replay" button is worth offering at all (see its own doc comment). */
  canJumpToReplay(event: DetectionEvent): boolean {
    return event.assetId !== undefined;
  }

  /**
   * A lazy, click-time-only lookup (`VisionApi.getAsset`, never done per-row on render — mirrors
   * `shared/ui/notification-bell.ts#navigate`'s identical idiom) for a **finished** usage covering
   * `event.firstSeen`. `canJumpToReplay` only guarantees an `assetId` exists, not that a finished
   * flight actually covers this moment — that can only be known after the fetch, so a miss here
   * degrades to an honest toast rather than a silently dead button.
   */
  async jumpToReplay(event: DetectionEvent): Promise<void> {
    if (!event.assetId) {
      return;
    }
    try {
      const asset = await this.api.getAsset(event.assetId);
      const deepLink = resolveReplayDeepLink(event, asset.recentUsages);
      if (!deepLink) {
        this.toasts.info('No finished flight covers this event yet.');
        return;
      }
      void this.router.navigate(['/replay'], {
        queryParams: { asset: event.assetId, usage: deepLink.usageId, t: deepLink.offsetMs },
      });
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    }
  }
}
