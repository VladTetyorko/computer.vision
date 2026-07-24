import { Injectable, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { SettingsStore } from '../settings/settings-store';
import { LiveStore } from '../live/live-store';
import { isLiveAvailable } from '../live/live-fallback-logic';
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
 *
 * **Projection of `LiveStore`'s `detection-events` topic** (docs/REALTIME-PLAN.md §4's backend
 * follow-up batch — the same poll-vs-live pattern `TelemetryStore`/`DetectionsStore` established in
 * R-c, and `FleetStore` now shares too): while `LiveStore` is `'open'`, the 5s poll below is paused
 * entirely and this store instead reacts to `LiveStore.detectionEvents()` — every arrival is folded
 * into `eventsSignal` through the exact same `mergeEvents`/`advanceCursor`/`maybeNotify` pipeline a
 * poll batch already used, so notification semantics (dedupe by id via `seenIds`, `OPEN`-only,
 * permission/hidden-gated) are identical regardless of which transport actually delivered the data.
 * While not `'open'`, the 5s `GET /api/events` poll is the fallback, exactly as it always was — no
 * behavior change for a pre-live backend or one with `vision.live.enabled=false`.
 *
 * **One retained signal, not two, unlike `TelemetryStore`/`DetectionsStore`.** Those stores keep a
 * separate poll-fed and live-fed signal and `computed()`-select between them, because a poll
 * response and a live batch are structurally different views of the same data (a full recent window
 * vs. a coalesced delta/latest-frame). Here both transports feed the *same* upsert-by-id merge
 * (`mergeEvents`'s own "incoming always wins" contract already handles overlap/replay from either
 * source correctly), so there is nothing to select between — `eventsSignal` is simply the
 * accumulated truth, fed by whichever transport is currently delivering.
 *
 * **No ref-counted subscribe/unsubscribe** (unlike `trackTelemetry`/`trackDetections`) —
 * `detection-events` is always-on, arriving on every connection regardless of the `topics` query
 * parameter and regardless of this store's own `activate()`/`release()` refcount (which only ever
 * gates the *poll*, never whether `LiveStore` itself is connected).
 */
@Injectable({ providedIn: 'root' })
export class EventsStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);
  private readonly settings = inject(SettingsStore);
  private readonly live = inject(LiveStore);

  private readonly eventsSignal = signal<readonly DetectionEvent[]>([]);
  readonly events = this.eventsSignal.asReadonly();

  private sinceMs: number | undefined;
  private readonly seenIds = new Set<string>();
  private stopPollingFn: (() => void) | null = null;
  private activeConsumers = 0;

  constructor() {
    // Re-evaluates poll-vs-live whenever `LiveStore` (re)connects or drops — mirrors
    // `TelemetryStore`/`DetectionsStore`'s identical reconnect-driven effect, simplified like
    // `FleetStore`'s own: no per-session `tracking` guard, since there is no track()/reset()
    // session here either, just "poll while ≥1 consumer is active, unless live is open".
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });

    // Folds every `detection-events` arrival into `eventsSignal` — see class doc's "Projection of
    // LiveStore's detection-events topic". Runs regardless of whether the poll is currently paused,
    // so a snapshot/arrival that lands before the connectionState effect above has paused polling
    // (or one delivered while genuinely live) is never dropped. `live.detectionEvents()` is already
    // oldest-first (see that signal's own doc comment) — `applyIncoming`'s expected order.
    effect(() => {
      const incoming = this.live.detectionEvents();
      if (incoming.length > 0) {
        this.applyIncoming(incoming);
      }
    });
  }

  /**
   * Registers interest — call once from a page's constructor (`WallPage`/`MapPage`/
   * `AssetDetailPage`). The first `activate()` since the last full `release()` triggers an
   * immediate poll and starts the shared 5s cadence, **unless `LiveStore` is already `'open'`**, in
   * which case there is nothing to poll for yet (live data is already flowing for free) — a
   * second/third concurrent consumer (never actually simultaneous in this SPA today, but harmless if
   * it ever were) just bumps the refcount.
   */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers === 1) {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    }
  }

  /** The matching teardown — call from `DestroyRef.onDestroy`. Stops polling once nothing is left. */
  release(): void {
    if (this.activeConsumers === 0) {
      return; // defensive — a mismatched release should never go negative
    }
    this.activeConsumers--;
    if (this.activeConsumers === 0) {
      this.stopPolling();
    }
  }

  /**
   * Switches whether the local 5s poll is running — **not** whether `LiveStore` itself has a
   * connection (there is nothing to subscribe/unsubscribe here, `detection-events` being
   * always-on). `liveAvailable` pauses the poll; its absence resumes it — but only while
   * `activeConsumers > 0` (mirrors `activate()`'s own original gate: no poll at all with nothing
   * mounted to show it) — refetching immediately first (mirrors `TelemetryStore.applyTransport`'s
   * reconnect-driven branch) since `eventsSignal` may be stale from however long live was up. A
   * no-op when the poll is already in the requested state (`stopPollingFn`'s own nullness tracks
   * that).
   */
  private applyTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      this.stopPolling();
      return;
    }
    if (this.activeConsumers === 0 || this.stopPollingFn !== null) {
      return; // nothing mounted to poll for, or already polling
    }
    void this.pollOnce();
    // Returns the poll's own promise so `PollScheduler`'s in-flight guard applies — see
    // `FleetStore`'s identical comment (docs/MVP2-PLAN.md §S, S-b).
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(), {
      ignoreHidden: true,
    });
  }

  private stopPolling(): void {
    this.stopPollingFn?.();
    this.stopPollingFn = null;
  }

  private async pollOnce(): Promise<void> {
    try {
      // `EventController`'s own contract is newest-first; `applyIncoming` expects oldest-first
      // (see its own doc comment for why) — reversed here, once, at the one call site that isn't
      // already in that order.
      const incoming = await this.api.events(this.sinceMs, EVENTS_LIMIT);
      this.applyIncoming([...incoming].reverse());
    } catch {
      // Silent-degrade: background enrichment, not a user-initiated action — matches every other
      // poller in this app (`TelemetryStore`/`DetectionsStore`/`FleetMapStore`).
    }
  }

  /**
   * The one place either transport's batch is folded in — merges/dedupes (`mergeEvents`), advances
   * the shared cursor (`advanceCursor`), and runs the notification gate (`maybeNotify`).
   *
   * **`incoming` must be oldest-first** — the one contract both call sites normalize to before
   * calling this, and the reason: unlike a poll batch (each id appears at most once —
   * `EventController` is a plain repository read), a live batch (`LiveStore.detectionEvents()`) can
   * carry the *same* id more than once as its `OPEN`→`CLOSED` lifecycle advances, and both
   * `mergeEvents` (upsert-by-id — the *later* array entry wins) and `maybeNotify`/`seenIds` (must
   * observe a genuine `OPEN` before a later `CLOSED` marks the id "seen", or the `OPEN` notification
   * is silently lost) need to process same-id repeats in true chronological order to stay correct.
   * `advanceCursor` itself wants newest-first (its own documented contract), so this method reverses
   * once, internally, rather than pushing that detail onto every caller.
   *
   * **`eventsSignal.update(...)`, not `.set(fn(this.eventsSignal()))`** — the latter would call the
   * tracked `eventsSignal()` getter from *inside* whichever effect is currently running this method
   * (the live-arrival effect above), registering `eventsSignal` as one of *its own* dependencies;
   * the `.set()` write immediately after would then re-notify that same effect, which calls this
   * method again, reads-and-writes again, forever — a real, reproduced self-triggering loop (`live`
   * never stops "arriving" from this store's own point of view once it starts). `.update()`'s
   * callback receives the current value directly, with no tracked read, exactly like
   * `DetectionsStore`'s own identical-shaped accumulation effect already relies on.
   */
  private applyIncoming(incoming: readonly DetectionEvent[]): void {
    this.eventsSignal.update((existing) => mergeEvents(existing, incoming, MAX_RETAINED_EVENTS));
    this.sinceMs = advanceCursor(this.sinceMs, [...incoming].reverse());
    this.maybeNotify(incoming);
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
