import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import { isLiveAvailable } from '../live/live-fallback-logic';
import type { OverallHealth, SystemStatus } from '../api/models';

/**
 * How often `GET /api/system/status` is re-read — slower than `FleetStore`'s 5s device/stream poll
 * (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1's page is a diagnostics surface an operator opens
 * deliberately, not a live cockpit readout), and a multiple of `PollScheduler`'s 1s heartbeat as
 * every registered cadence must be.
 *
 * **Gated on live, live axis only** (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1/§4.2, wave
 * L5) — this poll now runs **only** while `LiveFacade` is not `'open'`. While live is open, the
 * always-on `system` topic (wave L4) already delivers every health-change sample for free, so
 * scheduling this poll on top would just be a redundant `GET` every 15s. There is no demand axis to
 * compose this with, unlike every other store this plan gates: see the class doc for why.
 */
const POLL_INTERVAL_MS = 15_000;

const LOG_PREFIX = '[system-status]';

/**
 * Single source of truth for the platform's own self-reported health
 * (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.3's frozen `GET /api/system/status` contract, wave S3).
 *
 * `providedIn: 'root'` and polling unconditionally from construction — **not** gated behind the
 * `/manage/system` route being active — because `shared/ui/app-sidebar/app-sidebar.ts`'s shell rollup
 * dot (§5.2) needs `overall` on every page, not just while the diagnostics page itself is open; the
 * routed page's own facade (`features/system-status/system-status-facade.ts`) reads the same
 * singleton rather than re-fetching. This mirrors `FleetStore`'s own "one poller, every page reads
 * the same signal" precedent (see that store's class doc).
 *
 * **Degrades to stale-but-present data, not a wiped page**, on a background poll failure — a
 * transient blip while the diagnostics page happens to be open must not blank out the last-known
 * subsystem table (the "failed enrichment read shows '—' / stays as it was, never a blocked page"
 * rule). `status` is `undefined` only before the very first request has ever settled; once any
 * request has succeeded, a later failure leaves `status` exactly as it was and only updates
 * {@link error} so a caller can show a small "couldn't refresh — showing last-known status" note.
 *
 * <h2>Gated on live, live axis only (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1/§4.2, wave
 * L5) — deliberately no `activeConsumers` ref-counting here</h2>
 * Every other store this plan gates composes two axes, demand (`activate()`/`release()`) and
 * transport (`isLiveAvailable(...)`). This store has **only ever had one consumer posture**: it
 * polls unconditionally from construction because the shell rollup dot needs `overall` on *every*
 * page, not just while `/manage/system` happens to be open (see the paragraph above) — there is no
 * "nobody needs this right now" state to ref-count in the first place, so adding `activate()`/
 * `release()` here would just be ceremony around a refcount that never reaches zero. **Do not add
 * it** — this asymmetry with `GeofenceStore`/`MarksStore`/etc. is intentional, not an oversight.
 * `applyTransport(liveAvailable)` therefore only ever asks "is live available", identical in shape
 * to the other stores' method of the same name minus the `activeConsumers` branch.
 *
 * Once live is open, `system.systemStatus()` (the always-on topic, wave L4) is projected straight
 * onto `statusSignal` as it arrives — a fresh live sample always clears {@link error} too: a value
 * that just arrived over an open connection is by definition current-and-good, exactly like a
 * successful `refresh()` already clears it. This is what "error" now actually means once live is
 * up: not "no live data has ever arrived" (the `system` topic effectively arrives on connect — see
 * `LiveFacade.systemStatus`'s own doc comment — so that case is vanishingly narrow) but "the last
 * REST attempt this store made — the initial fetch, or the one-time reconcile on a poll→live
 * transition — failed", which a subsequent live arrival supersedes exactly as a subsequent
 * successful poll would have.
 */
@Injectable({ providedIn: 'root' })
export class SystemStatusStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveFacade);

  private readonly statusSignal = signal<SystemStatus | undefined>(undefined);
  private readonly loadingSignal = signal(false);
  /** Set on every failed fetch, cleared on every successful one (poll *or* live) — independent of
   *  {@link statusSignal}, which a failure never clears (see class doc). */
  private readonly errorSignal = signal<string | undefined>(undefined);

  /** `undefined` until the first fetch ever succeeds, or if it never has — see class doc. */
  readonly status = this.statusSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  /** Present while the most recent poll failed — `status` (if any) is still the last-known-good value. */
  readonly error = this.errorSignal.asReadonly();

  /** `undefined` before the first successful fetch — the shell rollup dot's own §5.2 input. */
  readonly overall = computed<OverallHealth | undefined>(() => this.statusSignal()?.overall);

  /** The poll's own unsubscribe, held only while the poll is actually running (live unavailable). */
  private stopPollFn: (() => void) | null = null;

  /**
   * `false` while this store is (or should be) relying on the poll rather than live — see
   * {@link applyTransport}'s own doc comment for the full state table this tracks. Starts `false`
   * so the very first `applyTransport` call (the constructor's own reconnect-driven `effect()`,
   * firing once immediately) is always treated as a genuine transition, replacing the old
   * unconditional `void this.refresh()` this store used to run at construction.
   */
  private liveGated = false;

  constructor() {
    // Projects the `system` SSE topic onto `statusSignal` whenever a fresh sample arrives — see
    // class doc's "error" paragraph for why this also clears `errorSignal`.
    effect(() => {
      const status = this.live.systemStatus();
      if (status !== undefined) {
        this.statusSignal.set(status);
        this.errorSignal.set(undefined);
      }
    });

    // Re-evaluates poll-vs-live whenever `LiveFacade` (re)connects or drops, and fires once at
    // construction (an `effect()`'s first run) — mirrors `FleetStore`/`EventsStore`'s identical
    // reconnect-driven effect, live-axis-only per class doc.
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });

    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  /**
   * D1's frozen gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3), **live axis only** — see
   * class doc for why there is no `activeConsumers` term here at all.
   *
   * | `liveAvailable` | previous (`liveGated`) | Action |
   * |---|---|---|
   * | `true` | `false` (was polling) | stop poll; refresh once (the reconcile) |
   * | `true` | `true` (already live) | nothing |
   * | `false` | `true` (was live) | refresh once, then start poll |
   * | `false` | `false` (already polling) | nothing |
   */
  private applyTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      if (!this.liveGated) {
        this.stopPolling();
        void this.refresh();
        this.liveGated = true;
      }
      return;
    }
    this.liveGated = false;
    if (this.stopPollFn !== null) {
      return; // already polling
    }
    void this.refresh();
    this.stopPollFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopPollFn?.();
    this.stopPollFn = null;
  }

  /** Re-reads `GET /api/system/status`. Silent-degrade on failure (see class doc) — this is a
   *  background poller, not a user-initiated action, so no toast (mirrors `FleetStore.refresh`'s
   *  own `{quiet: true}` background-poll posture, made unconditional here since every call to this
   *  method is itself a background poll — the routed page has its own explicit "Refresh" affordance
   *  via `refresh()` too, and a manual retry shouldn't spam a toast either given the page already
   *  shows {@link error} inline). */
  async refresh(): Promise<void> {
    this.loadingSignal.set(true);
    try {
      const status = await this.api.systemStatus();
      this.statusSignal.set(status);
      this.errorSignal.set(undefined);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not read system status`, { error });
      this.errorSignal.set('Could not read system status.');
    } finally {
      this.loadingSignal.set(false);
    }
  }
}
