import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { LiveStore } from '../../core/live/live-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { ToastService } from '../../core/toast.service';
import { GlobalOverlayStore } from '../../core/ui/overlay-store';
import { readPersistedString, writePersistedString } from '../../core/panel-state';
import { eventNotificationText, relativeTimeLabel, resolveEventTarget, resolveReplayDeepLink } from '../../core/events/events-logic';
import { geofenceBreachToastMessage, parseGeofenceBreach } from '../../core/geofence/geofence-logic';
import { describeSystemEventSource, type SystemEventRow as SystemEventRowModel } from '../../core/system-events/system-events-logic';
import { SystemEventsStore } from '../../core/system-events/system-events-store';
import { EventsRail } from './events-rail';
import {
  BELL_READ_IDS_CAP,
  BELL_READ_IDS_KEY,
  newlyOpenedEvents,
  pruneReadIds,
  seedReadIds,
  shouldToast,
  unreadEvents,
} from './notification-logic';
import { SystemEventRow } from './system-event-row';
import type { DetectionEvent } from '../../core/api/models';

/**
 * The app-shell header bell (docs/plans/done/UX-REWORK-PLAN.md §U-c's user amendments: "Events become
 * notifications (header bell + transient toasts via the existing toast system), not a docked
 * module"). Replaces `CommandPage`'s old embedded `<vision-events-rail>` section — the rail itself
 * is unchanged and unmoved (`shared/ui/events-rail.ts`), it just gets a second host: this
 * component's own dropdown, reusing its row rendering wholesale rather than re-implementing it
 * ("reuse events-rail's row rendering … by moving it into the dropdown", read literally — the
 * whole component moves in, not just its template).
 *
 * **`EventsStore` stays the data source** (unchanged public API, per this task's own scope note) —
 * this is simply a *third* long-lived consumer of its `activate()`/`release()` refcount, alongside
 * `WallPage`/`AssetDetailPage`. The one real cost-model change: this component lives in `App`'s own
 * header, mounted for the entire session (never destroyed until the tab itself closes/reloads), so
 * `EventsStore`'s 5s poll is now **effectively always-on** — the exact same "starts at boot, never
 * stops" posture `FleetStore` already has, not the store's old "O(visible) discipline" (no poll
 * while on Devices/Settings/Debug/Live/Replay) that `EventsStore`'s own doc comment used to
 * describe as its defining trait. That trait is now stale by construction, not a bug — a header
 * bell showing unread events *only while the operator happens to be on Wall/Command/an asset page*
 * would defeat the entire point of a persistent notification affordance. `FleetMap`'s own event
 * markers (`shared/map/fleet-map.ts`, which injects `EventsStore` directly but never activates it
 * itself) keep working unchanged — they now simply read a feed this component keeps warm
 * everywhere, instead of one `CommandPage`/`MapPage` used to keep warm only on their own routes.
 *
 * **Two independent jobs, two independent id-tracking sets** (deliberately not one): `readIds`
 * (a signal — marks every currently-listed event "read" the moment the dropdown opens, driving the
 * unread badge) and `toastedIds` (a plain field, mirroring `EventsStore`'s own private `seenIds`
 * precedent — marks an id "already toasted" so a still-OPEN event's `lastSeen` advancing on a later
 * poll doesn't re-toast it). Opening the dropdown does **not** suppress future toasts for events
 * that arrive afterward, and a toast firing does **not** count as "read" — a manager who dismissed a
 * toast without clicking it should still see that event as unread in the dropdown.
 *
 * **Event → replay deep link (docs/plans/done/OPS-CORE-PLAN.md §Q1)**: clicking a row (or its own toast's
 * action) first tries `core/events/events-logic.ts#resolveReplayDeepLink` — a lazy, click-time-only
 * lookup (`VisionApi.getAsset(event.assetId)`, never done per-row on render) for a **finished**
 * usage covering the event's own `firstSeen` — and navigates to `/replay?asset=…&usage=…&t=…`,
 * scrubbed to that exact moment, when one resolves; otherwise falls back to the pre-existing
 * `resolveEventTarget` behavior (asset detail / live cockpit) unchanged.
 *
 * **Geofence breaches (docs/plans/done/OPS-CORE-PLAN.md §G-c)** ride a *different* feed —
 * `LiveStore.liveEvents()`, the generic `event` SSE topic, not this bell's own `DetectionEvent`
 * dropdown list (see `LiveEvent`'s own doc comment for why the two are genuinely different domain
 * concepts). This component is still where they toast from (the app's one "background thing just
 * happened" chrome), via a second, independent id-tracking set (`toastedBreachIds`, mirroring
 * `toastedIds` above, still needed so a breach already toasted once doesn't re-fire as
 * `liveEvents()` grows and this effect re-runs) — deliberately **not** folded into the unread-badge
 * count or the dropdown list itself, since both are typed to `DetectionEvent` and a breach isn't
 * one; a future cycle that wants breaches counted in the badge too would need to widen that typing,
 * out of this batch's own scope.
 *
 * **Toast eligibility is `notification-logic.ts#shouldToast` (docs/plans/active/OPERATOR-UX-5-PLAN.md
 * finding U4, §2 U4), not a "seed the first tick silently" idiom** — the breach effect used to
 * assume `liveEvents()` was already fully populated the instant it first ran, which is false
 * whenever the SSE connection's own backlog/snapshot arrives on a *later* tick (a historic breach —
 * an asset offline for days — then read as freshly "new" and toasted, U4's own reproduction: a
 * `KEEP-IN breach` toast on every page load). `shouldToast` fixes this by construction with two
 * timestamp/streaming checks instead of a fragile "first run = history" assumption — see that
 * function's own doc comment.
 *
 * **`readIds` now survives a reload (docs/plans/active/OPERATOR-UX-7-PLAN.md finding B1)** — it used
 * to be a plain in-memory signal, so *every* reload re-marked every historic event unread again
 * (reproduced live: `9+` on a station with nothing new in days). It now persists under
 * `notification-logic.ts#BELL_READ_IDS_KEY` (`vision.bell.readIds`, capped to the newest
 * `BELL_READ_IDS_CAP` ids) through `core/panel-state.ts`'s plain string read/write pair — that file
 * has no JSON-array-shaped helper of its own, so `loadPersistedReadIds`/`persistReadIds` below
 * JSON-encode/decode through it directly, each guarded with its own try/catch (a corrupt or
 * inaccessible value degrades to "cold start", never a thrown error). Seeding follows the exact same
 * "history is not news" idiom as `toastedIds`/`seededToasts` above: `notification-logic.ts#seedReadIds`
 * marks everything already present as read only on a genuine cold start (nothing ever persisted for
 * this browser profile) — once a persisted set exists, it is trusted as-is, so a genuinely new event
 * id correctly stays unread across reloads. This is a third, independent id-tracking concern from
 * `toastedIds`/`toastedBreachIds` above (persisted vs. in-memory-only, unread-badge vs. toast-dedupe)
 * — **the bell's existing toast behavior is untouched by this**, only what backs the unread badge.
 *
 * **Signal-backed open state, not `<details>`** (docs/plans/done/UI-STATE-PLAN.md §1 D4/D5, §2.3, §2.2): the
 * dropdown used to be a native `<details>`, whose `open` state lived in the DOM where nothing could
 * see or reset it — and since this component is mounted once in the always-on shell
 * (`app-sidebar.html`'s foot) and never destroyed on navigation, "the page component is destroyed on
 * route change" (this app's only other cleanup mechanism) never applied to it. Reproduced live: open
 * this bell, then the identity menu — both stayed open at once (D1); navigate to another page — both
 * stayed open there too (D2). The trigger now toggles `GlobalOverlayStore` (`'notification-bell'`),
 * which composes `core/ui/ui-store.ts#UiStore` for exclusivity with the identity menu and adds the
 * lifecycle rules the shell needs and no page does: closes on any navigation, on `Escape` (returning
 * focus to the trigger), and on a click outside — see that store's own class doc for the mechanism.
 * `toggleBell()` below is the one place opening still has a side effect beyond visibility (marking
 * events read), so it can't be a bare `overlays.toggle()` call in the template the way
 * `identity-chip.ts`'s trigger is.
 *
 * **System events (docs/plans/done/SYSTEM-STATUS-PLAN.md §3.2-§3.3)** get a *second*, independent card in
 * this same dropdown, `<vision-system-event-row>` per row — a **sibling** of `vision-event-row`
 * (`EventsRail`'s own row component), not a widened version of it, since `EventRow.event` is typed
 * to `DetectionEvent` and every one of its existing call sites stays untouched by this wave; see
 * `system-event-row.ts`'s own class doc for the full "why a sibling" writeup. `SystemEventsStore`
 * (`providedIn: 'root'`, backed by `LiveStore.liveEvents()`) is this section's one data source —
 * `DETECTION` is excluded there already (that store's own doc comment), so this section can never
 * duplicate a detection the card above it already shows, avoiding exactly the alert-noise failure
 * mode `SYSTEM-STATUS-PLAN.md §1` names. `GEOFENCE_BREACH` still toasts *in addition* (the breach
 * effect below, U4's own eligibility fix notwithstanding) — this section is the durable record of
 * the same event, not a replacement for its toast; a breach `shouldToast` suppresses still shows up
 * here exactly as before, since this card's own list is untouched by this wave (class doc, "Toast
 * eligibility" paragraph).
 */
@Component({
  selector: 'vision-notification-bell',
  imports: [EventsRail, SystemEventRow],
  templateUrl: './notification-bell.html',
  styleUrl: './notification-bell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationBell {
  private readonly router = inject(Router);
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  private readonly toasts = inject(ToastService);
  private readonly liveStore = inject(LiveStore);
  private readonly poll = inject(PollScheduler);
  protected readonly events = inject(EventsStore);
  protected readonly systemEvents = inject(SystemEventsStore);
  protected readonly overlays = inject(GlobalOverlayStore);
  private readonly host = inject(ElementRef<HTMLElement>);
  /** Optional, mirroring `identity-chip.ts`'s own `viewChild` — this trigger is in fact never behind
   *  an `@if` (`NotificationBell` itself only ever mounts once the shell already knows
   *  `auth.user()` is non-null), but kept the same shape as its sibling shell overlay rather than
   *  special-cased, so both register themselves identically regardless of a constructor-scheduled
   *  `effect()`'s exact first-run timing relative to view init. */
  private readonly triggerEl = viewChild<ElementRef<HTMLButtonElement>>('trigger');

  private readonly readIds = signal<ReadonlySet<string>>(new Set());
  private readonly mountedAtMs = Date.now();
  protected readonly unreadCount = computed(() => unreadEvents(this.events.events(), this.readIds(), this.mountedAtMs).length);

  /** Guards `readIds`'s own one-time seed effect below (finding B1) — mirrors `seededToasts`'s
   *  identical "only the very first tick decides cold-start-or-not" shape. */
  private readIdsSeeded = false;

  /** Toast-dedup only — never read by a `computed()`, so a plain mutable set is fine here (mirrors
   * `core/events/events-store.ts`'s own private `seenIds`). */
  private readonly toastedIds = new Set<string>();
  private seededToasts = false;

  /** The identical dedup idiom, for `GEOFENCE_BREACH` `LiveEvent`s — see class doc's own "Geofence breaches" paragraph. */
  private readonly toastedBreachIds = new Set<string>();

  /** The instant this bell actually mounted — `shouldToast`'s own "never toast something that
   *  predates the bell watching at all" gate (see class doc's "Toast eligibility" paragraph and
   *  `notification-logic.ts#shouldToast`'s own doc comment for the full U4 root-cause writeup).
   *  Captured once, here, rather than read fresh per effect run — the whole point is one fixed
   *  reference instant, not "whatever `Date.now()` happens to be on this particular tick". */

  /**
   * Drives the system-events card's own "…s ago" timestamps (mirrors `events-rail.ts`'s identical
   * `nowSignal`/`CLOCK_TICK_MS` pair for the detections card above it) — but only *ticks* while the
   * dropdown is actually open (see the constructor's own clock effect below), unlike `EventsRail`'s
   * version: that component is only ever mounted while its host `@if` is true, so its own
   * `PollScheduler` registration is naturally scoped already; this component is mounted for the
   * entire session (class doc), so an unconditional 1s registration here would tick for the whole
   * session for a row list nobody is looking at most of the time.
   */
  private readonly nowSignal = signal(Date.now());
  private stopClock: (() => void) | null = null;

  constructor() {
    this.events.activate();
    inject(DestroyRef).onDestroy(() => this.events.release());

    // Registers this component's own host (trigger + dropdown together) with the shell's overlay
    // coordinator — see `identity-chip.ts`'s identical constructor comment and
    // `GlobalOverlayStore.register`'s own doc comment for why `root` containing `trigger` is what
    // lets a click on the trigger itself never fight the outside-click listener.
    effect(() => {
      const trigger = this.triggerEl();
      if (trigger) {
        this.overlays.register('notification-bell', this.host.nativeElement, trigger.nativeElement);
      }
    });

    // Toast every genuinely new OPEN event — but never on the very first read (whatever's already
    // in the feed at bell-mount time is history, not news; toasting a burst of pre-existing open
    // events the instant the app loads would be noise, not a notification).
    effect(() => {
      const current = this.events.events();
      if (!this.seededToasts) {
        for (const event of current) {
          this.toastedIds.add(event.id);
        }
        this.seededToasts = true;
        return;
      }
      for (const event of newlyOpenedEvents(current, this.toastedIds)) {
        this.toastedIds.add(event.id);
        this.toastNewEvent(event);
      }
    });

    // `readIds`'s own one-time seed (docs/plans/active/OPERATOR-UX-7-PLAN.md finding B1, class doc's
    // own "readIds now survives a reload" paragraph) — cold start (nothing ever persisted) seeds
    // read with whatever this first tick already has (history, not news); a real persisted set is
    // trusted as-is instead. Persisted immediately either way, so a reload before the dropdown is
    // ever opened again still resumes from this seed rather than reverting to another cold start.
    effect(() => {
      const current = this.events.events();
      // Nothing to seed from yet: the store's first tick is empty and the backlog lands later.
      // Seeding (and persisting) an empty set here is exactly what made every historic event
      // "unread" after the first reload — wait for the first non-empty tick instead.
      if (!this.readIdsSeeded && current.length > 0) {
        const seeded = seedReadIds(current, this.loadPersistedReadIds());
        this.readIds.set(seeded);
        this.readIdsSeeded = true;
        this.persistReadIds(seeded);
      }
    });

    // Geofence breach toasts (docs/plans/done/OPS-CORE-PLAN.md §G-c) — a separate feed, a separate dedup
    // set. Eligibility is `shouldToast` (class doc's "Toast eligibility" paragraph) — no "seed the
    // first tick silently" step: `shouldToast`'s own mount-time gate makes one unnecessary, and it
    // was the very thing racing against a late SSE replay burst (U4's own root cause).
    effect(() => {
      const current = this.liveStore.liveEvents();
      // `liveEvents` is newest-first; iterate oldest-of-the-new-batch-first so a toast burst (rare,
      // but possible on reconnect) reads in the order the breaches actually happened.
      for (const event of [...current].reverse()) {
        if (this.toastedBreachIds.has(event.id)) {
          continue;
        }
        this.toastedBreachIds.add(event.id);
        const breach = parseGeofenceBreach(event);
        if (!breach || !shouldToast(event, this.mountedAtMs, this.isAssetStreaming(breach.assetId))) {
          continue;
        }
        const message = geofenceBreachToastMessage(event);
        if (message) {
          this.toasts.error(message);
        }
      }
    });

    // System-events clock (see `nowSignal`'s own doc comment) — starts the instant the dropdown
    // opens (with an immediate tick, so the first render is never a stale timestamp from whenever
    // the bell itself first mounted) and stops the instant it closes.
    effect(() => {
      if (this.overlays.isOpen('notification-bell')) {
        if (!this.stopClock) {
          this.nowSignal.set(Date.now());
          this.stopClock = this.poll.schedule(1_000, () => this.nowSignal.set(Date.now()));
        }
      } else if (this.stopClock) {
        this.stopClock();
        this.stopClock = null;
      }
    });
    inject(DestroyRef).onDestroy(() => this.stopClock?.());
  }

  /**
   * The trigger's own `(click)` (`notification-bell.html`) — opening marks everything currently
   * listed as read, same as the old `<details>` `toggle` event's `isOpen` branch. Computes "opening"
   * from the pre-toggle state rather than reading `overlays.isOpen(...)` back out afterward, since a
   * `GlobalOverlayStore.toggle` that *closed* the bell (or a click that opened a *different* overlay
   * and thus closed this one first) must never mark anything read.
   */
  protected toggleBell(): void {
    const opening = !this.overlays.isOpen('notification-bell');
    this.overlays.toggle('notification-bell');
    if (opening) {
      const ids = new Set(this.events.events().map((event) => event.id));
      this.readIds.set(ids);
      this.persistReadIds(ids);
    }
  }

  /** The dropdown's own `<vision-events-rail>` row click — resolves and navigates, then closes. */
  protected onRailOpen(event: DetectionEvent): void {
    this.overlays.close('notification-bell');
    void this.navigate(event);
  }

  /** The system-events card's own row source label — see `SystemEventRow`'s (component) own
   *  `sourceLabel` input doc comment for why this stays the host's job. */
  protected systemEventSource(row: SystemEventRowModel): string {
    return describeSystemEventSource(row, this.fleet.devices(), this.fleet.streams());
  }

  protected systemEventRelativeTime(row: SystemEventRowModel): string {
    return relativeTimeLabel(row.atIso, this.nowSignal());
  }

  /** `shouldToast`'s own "is this asset currently streaming" input — `LiveStore.fleet()` (the
   *  always-on `fleet` SSE topic's own `AssetSummary[]`, already flowing into this same store for
   *  `liveEvents()`; no new subscription) is `undefined` only before that topic's first snapshot
   *  ever arrives, which reads as "not streaming" — the honest default while nothing is confirmed
   *  yet, never a fabricated "yes". */
  private isAssetStreaming(assetId: string): boolean {
    return this.liveStore.fleet()?.some((asset) => asset.assetId === assetId && asset.status === 'STREAMING') ?? false;
  }

  /**
   * `readIds`'s own persisted load (finding B1) — `null` for "nothing ever persisted" (a genuine
   * cold start, `seedReadIds`'s own cue) covers both a first-ever visit *and* a value this browser
   * can no longer make sense of (corrupt JSON, a non-array shape from some future/older format) —
   * degrading to cold-start-reseed is always safe here, never worse than the pre-B1 behavior every
   * reload already had.
   */
  private loadPersistedReadIds(): readonly string[] | null {
    try {
      const raw = readPersistedString(BELL_READ_IDS_KEY, null);
      if (raw === null) {
        return null;
      }
      const parsed: unknown = JSON.parse(raw);
      return Array.isArray(parsed) && parsed.every((id) => typeof id === 'string') ? (parsed as string[]) : null;
    } catch {
      return null;
    }
  }

  /** The write-through half of `loadPersistedReadIds` — best-effort (a full/denied `localStorage`
   *  must never block the UI), pruned to `BELL_READ_IDS_CAP` before encoding (finding B1). */
  private persistReadIds(ids: ReadonlySet<string>): void {
    try {
      writePersistedString(BELL_READ_IDS_KEY, JSON.stringify(pruneReadIds([...ids], BELL_READ_IDS_CAP)));
    } catch {
      // Best-effort — quota exceeded, private browsing, etc. Never worse than the pre-B1 in-memory-only behavior.
    }
  }

  private toastNewEvent(event: DetectionEvent): void {
    const text = eventNotificationText(event);
    const target = resolveEventTarget(event, this.fleet.streams());
    const action = target
      ? { label: target.kind === 'asset' ? 'Details' : 'Watch live', onClick: () => void this.navigate(event) }
      : undefined;
    this.toasts.notify(`${text.title} — ${text.body}`, action);
  }

  /**
   * Navigates to the replay deep link (docs/plans/done/OPS-CORE-PLAN.md §Q1) when a finished covering usage
   * resolves — a lazy, click-time-only lookup (see class doc) — else falls back to
   * `resolveEventTarget`'s pre-existing asset/live-cockpit target, unchanged.
   */
  private async navigate(event: DetectionEvent): Promise<void> {
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
        // Falls through to the pre-existing target below — a failed lookup is never worse than
        // the behavior this app already had before Q1.
      }
    }
    const target = resolveEventTarget(event, this.fleet.streams());
    if (!target) {
      return;
    }
    void this.router.navigate(target.kind === 'asset' ? ['/assets', target.id] : ['/live', target.id]);
  }
}
