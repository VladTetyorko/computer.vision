import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { PollScheduler } from '../poll-scheduler';
import type { OverallHealth, SystemStatus } from '../api/models';

/** How often `GET /api/system/status` is re-read while the tab is visible — slower than
 *  `FleetStore`'s 5s device/stream poll (docs/plans/active/SYSTEM-STATUS-PLAN.md §5.1's page is a
 *  diagnostics surface an operator opens deliberately, not a live cockpit readout), and a multiple
 *  of `PollScheduler`'s 1s heartbeat as every registered cadence must be. */
const POLL_INTERVAL_MS = 15_000;

const LOG_PREFIX = '[system-status]';

/**
 * Single source of truth for the platform's own self-reported health
 * (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.3's frozen `GET /api/system/status` contract, wave S3).
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
 */
@Injectable({ providedIn: 'root' })
export class SystemStatusStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);

  private readonly statusSignal = signal<SystemStatus | undefined>(undefined);
  private readonly loadingSignal = signal(false);
  /** Set on every failed fetch, cleared on every successful one — independent of {@link statusSignal},
   *  which a failure never clears (see class doc). */
  private readonly errorSignal = signal<string | undefined>(undefined);

  /** `undefined` until the first fetch ever succeeds, or if it never has — see class doc. */
  readonly status = this.statusSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  /** Present while the most recent poll failed — `status` (if any) is still the last-known-good value. */
  readonly error = this.errorSignal.asReadonly();

  /** `undefined` before the first successful fetch — the shell rollup dot's own §5.2 input. */
  readonly overall = computed<OverallHealth | undefined>(() => this.statusSignal()?.overall);

  constructor() {
    void this.refresh(); // one-time immediate fetch, mirrors `FleetStore`'s constructor-time refresh.
    const stop = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.refresh());
    inject(DestroyRef).onDestroy(stop);
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
