import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { LiveStore } from '../../core/live/live-store';
import { ToastService } from '../../core/toast.service';
import { GlobalOverlayStore } from '../../core/ui/overlay-store';
import { eventNotificationText, resolveEventTarget, resolveReplayDeepLink } from '../../core/events/events-logic';
import { geofenceBreachToastMessage } from '../../core/geofence/geofence-logic';
import { EventsRail } from './events-rail';
import { newlyOpenedEvents, unreadEvents } from './notification-logic';
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
 * happened" chrome), via a second, independent id-tracking set (`toastedBreachIds`/
 * `seededBreachToasts`, mirroring `toastedIds`/`seededToasts` exactly) — deliberately **not**
 * folded into the unread-badge count or the dropdown list itself, since both are typed to
 * `DetectionEvent` and a breach isn't one; a future cycle that wants breaches counted in the badge
 * too would need to widen that typing, out of this batch's own scope.
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
 */
@Component({
  selector: 'vision-notification-bell',
  imports: [EventsRail],
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
  protected readonly events = inject(EventsStore);
  protected readonly overlays = inject(GlobalOverlayStore);
  private readonly host = inject(ElementRef<HTMLElement>);
  /** Optional, mirroring `identity-chip.ts`'s own `viewChild` — this trigger is in fact never behind
   *  an `@if` (`NotificationBell` itself only ever mounts once the shell already knows
   *  `auth.user()` is non-null), but kept the same shape as its sibling shell overlay rather than
   *  special-cased, so both register themselves identically regardless of a constructor-scheduled
   *  `effect()`'s exact first-run timing relative to view init. */
  private readonly triggerEl = viewChild<ElementRef<HTMLButtonElement>>('trigger');

  private readonly readIds = signal<ReadonlySet<string>>(new Set());
  protected readonly unreadCount = computed(() => unreadEvents(this.events.events(), this.readIds()).length);

  /** Toast-dedup only — never read by a `computed()`, so a plain mutable set is fine here (mirrors
   * `core/events/events-store.ts`'s own private `seenIds`). */
  private readonly toastedIds = new Set<string>();
  private seededToasts = false;

  /** The identical dedup idiom, for `GEOFENCE_BREACH` `LiveEvent`s — see class doc's own "Geofence breaches" paragraph. */
  private readonly toastedBreachIds = new Set<string>();
  private seededBreachToasts = false;

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

    // Geofence breach toasts (docs/plans/done/OPS-CORE-PLAN.md §G-c) — a separate feed, a separate dedup set,
    // identical "seed silently, toast only what arrives after" rule as the effect above.
    effect(() => {
      const current = this.liveStore.liveEvents();
      if (!this.seededBreachToasts) {
        for (const event of current) {
          this.toastedBreachIds.add(event.id);
        }
        this.seededBreachToasts = true;
        return;
      }
      // `liveEvents` is newest-first; iterate oldest-of-the-new-batch-first so a toast burst (rare,
      // but possible on reconnect) reads in the order the breaches actually happened.
      for (const event of [...current].reverse()) {
        if (this.toastedBreachIds.has(event.id)) {
          continue;
        }
        this.toastedBreachIds.add(event.id);
        const message = geofenceBreachToastMessage(event);
        if (message) {
          this.toasts.error(message);
        }
      }
    });
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
      this.readIds.set(new Set(this.events.events().map((event) => event.id)));
    }
  }

  /** The dropdown's own `<vision-events-rail>` row click — resolves and navigates, then closes. */
  protected onRailOpen(event: DetectionEvent): void {
    this.overlays.close('notification-bell');
    void this.navigate(event);
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
