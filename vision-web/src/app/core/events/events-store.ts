import { Injectable, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { SettingsStore } from '../settings/settings-store';
import type { DetectionEvent } from '../api/models';
import { MAX_RETAINED_EVENTS, advanceCursor, eventNotificationText, mergeEvents, shouldNotify } from './events-logic';

/** How often the global events feed is re-read while ≥1 consumer is active. */
const POLL_INTERVAL_MS = 5_000;

/** Matches `EventController.DEFAULT_LIMIT` — plenty for a "recent activity" rail/marker set. */
const EVENTS_LIMIT = 50;

/**
 * The single shared feed behind docs/MVP2-PLAN.md §E, E-b's three consumers — the Wall page's
 * events rail, the fleet map's event markers, and the asset detail page's "recent events by
 * matching assetId" fallback — polling `GET /api/events` once for all three rather than three times
 * over, and (the real reason it needs to be one long-lived singleton rather than three page-scoped
 * instances like `TelemetryStore`/`DetectionsStore`) keeping one continuous `sinceMs` cursor and one
 * continuous "already seen this event id" dedupe set across page navigation — restarting either per
 * page would mean re-fetching the full recent-events window on every tab switch, and would let a
 * genuinely-already-seen event re-fire a notification just because the user happened to change tabs
 * in between.
 *
 * **Cost, documented per the plan's own ask**: this is a `providedIn: 'root'` singleton, but it is
 * only ever *injected* from lazy route components (`WallPage`/`MapPage`/`AssetDetailPage`), never
 * from `app.ts` or any other eager path — so, like `FleetMapStore`'s own `leaflet-src` chunk
 * reasoning, it lands wherever the bundler puts code shared by those three lazy chunks, not the
 * initial bundle. It differs from `FleetStore` in one more way: `FleetStore` starts polling the
 * instant the app boots (constructed eagerly from `app.ts`) and never stops; this store polls
 * **only while `activeConsumers > 0`** (see `activate`/`release`) — "O(visible) discipline"
 * (docs/MVP2-PLAN.md §E, E-b bullet 5): no events poll runs at all while the user is on
 * Devices/Settings/Debug/Live/Replay, however long that lasts.
 *
 * **`ignoreHidden: true`, the one deliberate exception in this app** (see
 * `PollScheduler.ScheduleOptions#ignoreHidden`'s own doc comment): every other poller in this app
 * pauses while `document.hidden` to save battery/network on a backgrounded tab, which is the right
 * default when the only consequence of pausing is "the display is a few seconds stale until the tab
 * comes back". Here it is not: the entire point of the opt-in browser-notification feature (bullet 4)
 * is to alert the user about something *while they are not looking* — a poll that paused the instant
 * the tab backgrounds could, by construction, only ever discover new events while already visible,
 * making `shouldNotify`'s own `documentHidden` gate permanently unsatisfiable. So this store's poll
 * keeps running (at the same 5s cadence) even while hidden, for as long as ≥1 of the three pages
 * above is still mounted underneath — the SPA route doesn't unmount just because the OS-level
 * window/tab loses focus.
 */
@Injectable({ providedIn: 'root' })
export class EventsStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);
  private readonly settings = inject(SettingsStore);

  private readonly eventsSignal = signal<readonly DetectionEvent[]>([]);
  readonly events = this.eventsSignal.asReadonly();

  private sinceMs: number | undefined;
  private readonly seenIds = new Set<string>();
  private stopPollingFn: (() => void) | null = null;
  private activeConsumers = 0;

  /**
   * Registers interest — call once from a page's constructor (`WallPage`/`MapPage`/
   * `AssetDetailPage`). The first `activate()` since the last full `release()` triggers an
   * immediate poll and starts the shared 5s cadence; a second/third concurrent consumer (never
   * actually simultaneous in this SPA today, but harmless if it ever were) just bumps the refcount.
   */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers === 1) {
      void this.pollOnce();
      // Returns the poll's own promise so `PollScheduler`'s in-flight guard applies — see
      // `FleetStore`'s identical comment (docs/MVP2-PLAN.md §S, S-b).
      this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(), {
        ignoreHidden: true,
      });
    }
  }

  /** The matching teardown — call from `DestroyRef.onDestroy`. Stops polling once nothing is left. */
  release(): void {
    if (this.activeConsumers === 0) {
      return; // defensive — a mismatched release should never go negative
    }
    this.activeConsumers--;
    if (this.activeConsumers === 0) {
      this.stopPollingFn?.();
      this.stopPollingFn = null;
    }
  }

  private async pollOnce(): Promise<void> {
    try {
      const incoming = await this.api.events(this.sinceMs, EVENTS_LIMIT);
      this.eventsSignal.set(mergeEvents(this.eventsSignal(), incoming, MAX_RETAINED_EVENTS));
      this.sinceMs = advanceCursor(this.sinceMs, incoming);
      this.maybeNotify(incoming);
    } catch {
      // Silent-degrade: background enrichment, not a user-initiated action — matches every other
      // poller in this app (`TelemetryStore`/`DetectionsStore`/`FleetMapStore`).
    }
  }

  /** Dedupes by id (`seenIds`) and gates each genuinely-new arrival through `shouldNotify`. */
  private maybeNotify(incoming: readonly DetectionEvent[]): void {
    const permission: NotificationPermission =
      typeof Notification === 'undefined' ? 'denied' : Notification.permission;
    for (const event of incoming) {
      const alreadySeen = this.seenIds.has(event.id);
      this.seenIds.add(event.id);
      const notify = shouldNotify({
        event,
        alreadySeen,
        notificationsEnabled: this.settings.eventNotifications(),
        permission,
        documentHidden: document.hidden,
      });
      if (notify) {
        this.fireNotification(event);
      }
    }
  }

  private fireNotification(event: DetectionEvent): void {
    const text = eventNotificationText(event);
    try {
      // `tag: event.id` also protects against a genuine double-fire (e.g. two overlapping polls)
      // collapsing into one OS-level notification rather than two, belt-and-braces alongside the
      // `seenIds` dedupe above.
      new Notification(text.title, { body: text.body, tag: event.id });
    } catch {
      // Best-effort — some environments accept the permission grant but still throw on construction
      // (e.g. a service-worker-only context in certain browsers); never let this break polling.
    }
  }
}
