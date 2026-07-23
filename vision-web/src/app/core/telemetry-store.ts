import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from './api/vision-api';
import type { AssetDetails, TelemetrySample } from './api/models';
import { PollScheduler } from './poll-scheduler';
import { ageSeconds, deriveTrail, findOwningAsset, isStale, selectOpenUsage } from './telemetry-logic';

/** How often a tracked usage's telemetry is re-read while polling is active. */
const POLL_INTERVAL_MS = 2_000;

/** How often the "n seconds ago" readout ticks, independent of when a poll last landed. */
const CLOCK_TICK_MS = 1_000;

/** Matches `AssetController#telemetry`'s own default-overriding call site (docs/CYCLES-PLAN.md §2). */
const TELEMETRY_LIMIT = 200;

/**
 * Tracks one device's live telemetry: resolves the owning asset's currently-open usage, then
 * polls that usage's trail every 2s while tracking is active.
 *
 * **Component-provided, not `providedIn: 'root'`.** `LivePage` and each `WallTile` list this in
 * their own `providers`, so a fresh instance — and its poll timer — is created and destroyed
 * with that component (route leave, tile unmount), matching "poll lifecycle must stop on
 * destroy/route-leave" without any manual wiring at the call site. A wall of many tiles gets one
 * instance per tile rather than one shared poller racing over unrelated devices.
 *
 * **Errors silent-degrade.** Telemetry is best-effort context, not a user-initiated action, so a
 * failed lookup or a missed poll never raises a toast (contrast `FleetStore.run()`, which is
 * exactly for actions the user asked for) — callers just see `hasTelemetry()` stay `false` and
 * render a "no telemetry" state instead.
 */
@Injectable()
export class TelemetryStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);

  private readonly samplesSignal = signal<readonly TelemetrySample[]>([]);
  private readonly nowSignal = signal(Date.now());

  private stopPollingFn: (() => void) | null = null;
  private readonly stopClock: () => void;

  /** Bumped on every `track`/`reset` so a stale async lookup can tell it has been superseded. */
  private generation = 0;

  readonly samples = this.samplesSignal.asReadonly();

  /** Whether any telemetry has arrived yet for the currently tracked device. */
  readonly hasTelemetry = computed(() => this.samplesSignal().length > 0);

  readonly latest = computed<TelemetrySample | undefined>(() => {
    const samples = this.samplesSignal();
    return samples.length > 0 ? samples[samples.length - 1] : undefined;
  });

  /** Chronological lat/lon points for the map's breadcrumb trail. */
  readonly trail = computed(() => deriveTrail(this.samplesSignal()));

  /** Seconds since the latest sample; ticks every second independent of the 2s poll cadence. */
  readonly sampleAgeSeconds = computed(() => ageSeconds(this.latest()?.at, this.nowSignal()));

  /** Whether the latest sample is old enough to be a safety concern — the OSD highlights this. */
  readonly stale = computed(() => isStale(this.sampleAgeSeconds()));

  constructor() {
    this.stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(() => {
      this.stopClock();
      this.stopPolling();
    });
  }

  /**
   * Starts tracking `deviceId`: looks up its owning asset's open usage, then polls that usage's
   * telemetry every 2s. Fire-and-forget by design — callers (typically an `effect` reacting to
   * a device id signal) read `samples`/`latest`/etc. as they fill in rather than awaiting this.
   *
   * Calling again (e.g. the route's `deviceId` changed) supersedes any in-flight lookup and
   * clears prior samples immediately, so a stale device's trail never lingers into the next.
   */
  track(deviceId: string): void {
    void this.startTracking(deviceId);
  }

  /** Stops polling and clears samples — call when the tracked device no longer has telemetry. */
  reset(): void {
    this.generation++;
    this.stopPolling();
    this.samplesSignal.set([]);
  }

  private async startTracking(deviceId: string): Promise<void> {
    const generation = ++this.generation;
    this.stopPolling();
    this.samplesSignal.set([]);

    const usageId = await this.findOpenUsageId(deviceId);
    if (generation !== this.generation || usageId === undefined) {
      return; // superseded by a newer track()/reset(), or this device has no open usage
    }

    await this.pollOnce(usageId);
    if (generation !== this.generation) {
      return;
    }
    // Returns the poll's own promise so `PollScheduler`'s in-flight guard applies — see
    // `FleetStore`'s identical comment (docs/MVP2-PLAN.md §S, S-b).
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(usageId));
  }

  /** A device belongs to at most one asset; that asset's open usage is what we poll. */
  private async findOpenUsageId(deviceId: string): Promise<string | undefined> {
    try {
      const summaries = await this.api.listAssets();
      const details = await Promise.all(
        summaries.map((summary) => this.api.getAsset(summary.assetId).catch(() => undefined)),
      );
      const resolved = details.filter((detail): detail is AssetDetails => detail !== undefined);
      const owner = findOwningAsset(resolved, deviceId);
      return owner ? selectOpenUsage(owner.recentUsages)?.usageId : undefined;
    } catch {
      return undefined; // best-effort lookup — see class doc on silent-degrade
    }
  }

  private async pollOnce(usageId: string): Promise<void> {
    try {
      this.samplesSignal.set(await this.api.usageTelemetry(usageId, TELEMETRY_LIMIT));
    } catch {
      // Silent-degrade: a missed poll just leaves samples/latest at their last-known values.
    }
  }

  private stopPolling(): void {
    if (this.stopPollingFn !== null) {
      this.stopPollingFn();
      this.stopPollingFn = null;
    }
  }
}
