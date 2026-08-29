import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { LiveStore } from '../../core/live/live-store';
import { PollScheduler } from '../../core/poll-scheduler';
import { relativeTimeLabel } from '../../core/events/events-logic';
import { describeSystemEventSource, type SystemEventRow } from '../../core/system-events/system-events-logic';
import { SystemEventsStore } from '../../core/system-events/system-events-store';
import {
  connectionSeverity,
  reachableSeverity,
  verdictFor,
  type ShellSeverity,
  type SystemVerdict,
} from '../../core/system-status/system-status-logic';
import { SystemStatusStore } from '../../core/system-status/system-status-store';

/** How often the page's own "…s ago" labels (`checkedAt`, each subsystem's `since`, every event row)
 *  re-render — the same 1s cadence `notification-bell.ts#nowSignal`/`events-rail.ts` already use for
 *  an identical purpose, registered on the shared `PollScheduler` rather than a private timer. */
const CLOCK_TICK_MS = 1_000;

/**
 * `/manage/system`'s facade (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1, wave S3), the one seam this
 * routed page injects (`core/ui/architecture.spec.ts`'s layering guard). Reads three existing
 * `providedIn: 'root'` singletons rather than owning any polling/SSE of its own:
 *
 * - {@link SystemStatusStore} — `GET /api/system/status`, already polling app-wide for the shell
 *   rollup dot (§5.2); this page is simply a second reader, exactly like `ReportsFacade` reading
 *   `LiveStore` without re-subscribing to anything.
 * - {@link SystemEventsStore} — S1's system-event log, reused wholesale (this wave's own explicit
 *   instruction) rather than a second `computed()` over `LiveStore.liveEvents()`.
 * - {@link FleetStore}/{@link LiveStore} — `devices`/`streams` for
 *   `describeSystemEventSource`, and `connectionState()` for the page's own Live transport card.
 *
 * Degrades honestly: `subsystems`/`overall*` read `undefined` (never a fabricated "OK") until the
 * store's own first fetch succeeds, and stay at their last-known value (with {@link statusError} set)
 * on a later poll failure — see `SystemStatusStore`'s own class doc for why that choice lives there,
 * not here.
 */
@Injectable()
export class SystemStatusFacade {
  private readonly statusStore = inject(SystemStatusStore);
  private readonly eventsStore = inject(SystemEventsStore);
  private readonly fleet = inject(FleetStore);
  private readonly live = inject(LiveStore);
  private readonly scheduler = inject(PollScheduler);

  private readonly nowSignal = signal(Date.now());

  readonly status = this.statusStore.status;
  readonly statusLoading = this.statusStore.loading;
  /** Present while the most recent background poll failed — `status` (if any) is still shown, stale. */
  readonly statusError = this.statusStore.error;

  readonly subsystems = computed(() => this.status()?.subsystems ?? []);

  /**
   * The banner's own verdict (OPERATOR-UX-5-PLAN.md finding U3, §2 U3) — `undefined` only before
   * the very first `GET /api/system/status` fetch has ever settled (`this.status()` itself
   * `undefined`), which `overallMessage` below reads as its own honest "Checking…" sentence rather
   * than forcing that state through `verdictFor`'s own punctuation. Once `status` has loaded even
   * once, `verdictFor` is fed this page's own already-fetched `subsystems` plus the two transport
   * signals it was already reading for `backendSeverity`/`connectionSeverity` below — no new fetch,
   * no re-derivation of facts this facade didn't already have.
   */
  readonly verdict = computed<SystemVerdict | undefined>(() => {
    const status = this.status();
    if (status === undefined) {
      return undefined;
    }
    return verdictFor(status.subsystems, { backendReachable: this.fleet.reachable(), liveConnection: this.live.connectionState() });
  });
  /** `'neutral'` (never a fabricated `'ok'`) before the first successful fetch — see class doc. */
  readonly overallSeverity = computed<ShellSeverity>(() => this.verdict()?.severity ?? 'neutral');
  /** The banner's own sentence — see {@link verdict}'s doc comment for the pre-first-fetch case. */
  readonly overallMessage = computed(() => this.verdict()?.message ?? 'Checking system status…');
  readonly checkedAtRelative = computed(() => {
    const status = this.status();
    return status ? relativeTimeLabel(status.checkedAt, this.nowSignal()) : undefined;
  });

  /** Backend REST reachability (`FleetStore`'s own always-on device/stream poll) — the rollup's first axis. */
  readonly backendSeverity = computed<ShellSeverity>(() => reachableSeverity(this.fleet.reachable()));

  /** The page's own explicit Live-transport card (§5.1) — same signal the sidebar's quiet dot reads,
   *  spelled out here with the transport's name rather than left as an unexplained dot. */
  readonly connectionState = this.live.connectionState;
  readonly connectionSeverity = computed<ShellSeverity>(() => connectionSeverity(this.connectionState()));
  readonly connectionLabel = computed(() => {
    switch (this.connectionState()) {
      case 'open':
        return 'Live (SSE)';
      case 'connecting':
        return 'Connecting…';
      case 'closed':
        return 'Polling fallback';
    }
  });

  readonly events = this.eventsStore.rows;

  constructor() {
    void this.statusStore.refresh(); // no-op if already in flight; picks up any change since page mount.
    this.nowSignal.set(Date.now());
    const stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(stopClock);
  }

  eventSource(row: SystemEventRow): string {
    return describeSystemEventSource(row, this.fleet.devices(), this.fleet.streams());
  }

  eventRelativeTime(row: SystemEventRow): string {
    return relativeTimeLabel(row.atIso, this.nowSignal());
  }

  /** `since` is an ISO instant when present (see `models.ts#SubsystemStatus`'s own doc comment) —
   *  rendered the same "…s ago" way as `checkedAtRelative`/event rows, for one consistent time
   *  vocabulary across the page. */
  sinceRelative(since: string): string {
    return relativeTimeLabel(since, this.nowSignal());
  }

  async refresh(): Promise<void> {
    await this.statusStore.refresh();
  }
}
