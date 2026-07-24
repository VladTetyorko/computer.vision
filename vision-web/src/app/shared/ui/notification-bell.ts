import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FleetStore } from '../../core/fleet/fleet-store';
import { EventsStore } from '../../core/events/events-store';
import { ToastService } from '../../core/toast.service';
import { eventNotificationText, resolveEventTarget } from '../../core/events/events-logic';
import { EventsRail } from './events-rail';
import { newlyOpenedEvents, unreadEvents } from './notification-logic';
import type { DetectionEvent } from '../../core/api/models';

/**
 * The app-shell header bell (docs/UX-REWORK-PLAN.md §U-c's user amendments: "Events become
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
  private readonly fleet = inject(FleetStore);
  private readonly toasts = inject(ToastService);
  protected readonly events = inject(EventsStore);

  private readonly readIds = signal<ReadonlySet<string>>(new Set());
  protected readonly unreadCount = computed(() => unreadEvents(this.events.events(), this.readIds()).length);

  /** Toast-dedup only — never read by a `computed()`, so a plain mutable set is fine here (mirrors
   * `core/events/events-store.ts`'s own private `seenIds`). */
  private readonly toastedIds = new Set<string>();
  private seededToasts = false;

  constructor() {
    this.events.activate();
    inject(DestroyRef).onDestroy(() => this.events.release());

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
  }

  /** The native `<details>` `toggle` event (`notification-bell.html`) — opening marks everything read. */
  protected onToggle(isOpen: boolean): void {
    if (isOpen) {
      this.readIds.set(new Set(this.events.events().map((event) => event.id)));
    }
  }

  /** The dropdown's own `<vision-events-rail>` row click — resolves and navigates, then closes. */
  protected onRailOpen(event: DetectionEvent, details: HTMLDetailsElement): void {
    details.open = false;
    this.navigate(event);
  }

  private toastNewEvent(event: DetectionEvent): void {
    const text = eventNotificationText(event);
    const target = resolveEventTarget(event, this.fleet.streams());
    const action = target
      ? { label: target.kind === 'asset' ? 'Details' : 'Watch live', onClick: () => this.navigate(event) }
      : undefined;
    this.toasts.notify(`${text.title} — ${text.body}`, action);
  }

  private navigate(event: DetectionEvent): void {
    const target = resolveEventTarget(event, this.fleet.streams());
    if (!target) {
      return;
    }
    void this.router.navigate(target.kind === 'asset' ? ['/assets', target.id] : ['/live', target.id]);
  }
}
