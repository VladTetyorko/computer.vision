import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { findVideoDevice } from '../fleet/device-logic';
import { PollScheduler } from '../poll-scheduler';
import { selectOpenUsage } from '../telemetry/telemetry-logic';
import type { AssetSummary, Device } from '../api/models';
import {
  bucketAssets,
  buildMarkers,
  snapshotFromSamples,
  type AssetTelemetrySnapshot,
} from './map-logic';

/** How often the fleet's asset list is re-read while the `/map` tab is visible — same cadence as `FleetStore`. */
const ASSET_POLL_INTERVAL_MS = 5_000;

/** How often each *streaming* asset's telemetry is re-read — matches `TelemetryStore`'s own cadence. */
const TELEMETRY_POLL_INTERVAL_MS = 2_000;

/** Matches `AssetController#telemetry`'s own default-overriding call site, same as `TelemetryStore`. */
const TELEMETRY_LIMIT = 200;

/** How often the "n seconds ago" readout ticks, independent of the telemetry poll cadence. */
const CLOCK_TICK_MS = 1_000;

/** Per-streaming-asset poll bookkeeping — the map's analog of one `TelemetryStore` instance. */
interface AssetTracker {
  stopPolling: (() => void) | null;
  /** Bumped whenever this asset's tracker restarts, so a superseded async lookup can no-op. */
  generation: number;
}

/**
 * Polls the whole fleet for the `/map` tab (docs/CYCLES-PLAN.md §6): `GET /api/assets` every 5s
 * drives `buckets`/`markers`; each asset currently bucketed `streaming` additionally gets its own
 * 2s telemetry poller — the fleet map's per-asset analog of `TelemetryStore`, reusing its pure
 * helper (`selectOpenUsage`, and `deriveTrail` via `map-logic.ts`) rather than the class itself:
 * this store already starts from the asset id (its own 5s poll already fetched it), so it resolves
 * the open usage directly with one `getAsset()` per newly-streaming asset rather than going through
 * `TelemetryStore` at all. `TelemetryStore.track(deviceId, assetId?)` itself gained this same
 * asset-id-first shortcut in docs/REALTIME-PLAN.md Phase R-a item 3 (it re-derives the owning asset
 * via `listAssets()`+`getAsset()` per fleet asset only when a caller has no `assetId` to give it) —
 * this store still doesn't use that class, since it needs *per-asset* trackers keyed by asset id,
 * not one poller for a single device. Every one of this store's timers — the 5s
 * asset poll, the 1s clock, and every per-asset 2s telemetry poller — runs off the app's single
 * shared `PollScheduler` (docs/CYCLES-PLAN.md §9, CU-b item 3) rather than its own `setInterval`s.
 *
 * **Concurrency cap (docs/CYCLES-PLAN.md §6's called-out risk):** only assets currently bucketed
 * `streaming` ever get a telemetry poller. `reconcileTrackers` runs after every asset refresh and
 * tears down any tracker whose asset is no longer streaming (stopped, or vanished) on the very
 * next 5s tick — a referee's fleet is a handful of machines, not hundreds, but an idle map tab
 * still shouldn't accumulate pollers for drones nobody is flying anymore.
 *
 * **Page-provided, not `providedIn: 'root'`** — like `TelemetryStore`, each host page (`MapPage`,
 * and now `CommandPage`) lists this in its own `providers` so every poller (the 5s asset poll, the
 * 1s clock, and every per-asset 2s telemetry poll) starts and stops with that page's own route,
 * never running while neither tab is open. Two pages providing it means two independent instances
 * (and therefore two independent 5s asset polls) when both happen to be mounted — never actually
 * simultaneous in this single-route-at-a-time SPA, so this costs nothing in practice.
 *
 * **Errors silent-degrade**, same rationale as `TelemetryStore`: this is a background overview,
 * not a user-initiated action, so a failed poll just leaves signals at their last-known values
 * rather than raising a toast.
 *
 * Moved here from `pages/map/map-store.ts` in docs/MVP3-PLAN.md §C-c when the Command dashboard
 * needed to embed `shared/map/fleet-map.ts` (which injects this store) as a second page — this codebase's
 * own "move a page-scoped thing to a shared home once a second page needs it" precedent (see
 * `core/map-logic.ts`'s doc comment). `features/map/map.ts` now imports this from here too; nothing
 * about its behavior changed.
 */
@Injectable()
export class FleetMapStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);

  private readonly assetsSignal = signal<readonly AssetSummary[]>([]);
  private readonly telemetryByAssetSignal = signal<ReadonlyMap<string, AssetTelemetrySnapshot>>(new Map());
  private readonly nowSignal = signal(Date.now());

  private stopAssetPolling: (() => void) | null = null;
  private readonly stopClock: () => void;
  private readonly trackers = new Map<string, AssetTracker>();

  readonly assets = this.assetsSignal.asReadonly();
  readonly buckets = computed(() => bucketAssets(this.assetsSignal()));
  readonly markers = computed(() =>
    buildMarkers(this.assetsSignal(), this.telemetryByAssetSignal(), this.nowSignal()),
  );

  constructor() {
    void this.refresh();
    // Returns `refresh()`'s own promise so `PollScheduler`'s in-flight guard applies — see
    // `FleetStore`'s identical comment (docs/MVP2-PLAN.md §S, S-b).
    this.stopAssetPolling = this.scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refresh());
    this.stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  /**
   * Re-reads `GET /api/assets` and reconciles telemetry trackers against the result. Public (like
   * `FleetStore.refresh`) so it is unit-testable without waiting on the internal interval, even
   * though nothing currently calls it besides that interval and the constructor.
   */
  async refresh(): Promise<void> {
    try {
      const assets = await this.api.listAssets();
      this.assetsSignal.set(assets);
      this.reconcileTrackers(assets);
    } catch {
      // Silent-degrade — see class doc.
    }
  }

  /** The Watch action's target device, resolved fresh on click — occasional, not worth caching. */
  async resolveWatchDevice(assetId: string): Promise<Device | undefined> {
    try {
      const details = await this.api.getAsset(assetId);
      return findVideoDevice(details.devices);
    } catch {
      return undefined; // best-effort — worst case the popup's Watch action just does nothing
    }
  }

  private reconcileTrackers(assets: readonly AssetSummary[]): void {
    const streamingIds = new Set(bucketAssets(assets).streaming.map((asset) => asset.assetId));
    for (const assetId of [...this.trackers.keys()]) {
      if (!streamingIds.has(assetId)) {
        this.stopTracker(assetId);
      }
    }
    for (const assetId of streamingIds) {
      if (!this.trackers.has(assetId)) {
        this.startTracker(assetId);
      }
    }
  }

  private startTracker(assetId: string): void {
    const tracker: AssetTracker = { stopPolling: null, generation: 0 };
    this.trackers.set(assetId, tracker);
    void this.initTracker(assetId, tracker);
  }

  private async initTracker(assetId: string, tracker: AssetTracker): Promise<void> {
    const generation = ++tracker.generation;
    try {
      const details = await this.api.getAsset(assetId);
      if (tracker.generation !== generation || !this.trackers.has(assetId)) {
        return; // superseded by a stop (asset no longer streaming) before this lookup finished
      }
      const usageId = selectOpenUsage(details.recentUsages)?.usageId;
      if (usageId === undefined) {
        return; // streaming per the last asset poll, but no open usage found (yet) — next reconcile retries
      }

      await this.pollTelemetry(assetId, usageId);
      if (tracker.generation !== generation || !this.trackers.has(assetId)) {
        return;
      }
      // Returns the poll's own promise so `PollScheduler`'s in-flight guard applies — see
      // `FleetStore`'s identical comment (docs/MVP2-PLAN.md §S, S-b).
      tracker.stopPolling = this.scheduler.schedule(TELEMETRY_POLL_INTERVAL_MS, () =>
        this.pollTelemetry(assetId, usageId),
      );
    } catch {
      // best-effort — see class doc; this asset's marker just falls back to lastKnownPosition
    }
  }

  private async pollTelemetry(assetId: string, usageId: string): Promise<void> {
    try {
      const samples = await this.api.usageTelemetry(usageId, TELEMETRY_LIMIT);
      this.telemetryByAssetSignal.update((map) => {
        const next = new Map(map);
        next.set(assetId, snapshotFromSamples(samples));
        return next;
      });
    } catch {
      // Silent-degrade: a missed poll just leaves that asset's snapshot at its last-known value.
    }
  }

  private stopTracker(assetId: string): void {
    const tracker = this.trackers.get(assetId);
    tracker?.stopPolling?.();
    this.trackers.delete(assetId);
    this.telemetryByAssetSignal.update((map) => {
      if (!map.has(assetId)) {
        return map;
      }
      const next = new Map(map);
      next.delete(assetId);
      return next;
    });
  }

  private teardown(): void {
    this.stopAssetPolling?.();
    this.stopClock();
    for (const assetId of [...this.trackers.keys()]) {
      this.stopTracker(assetId);
    }
  }
}
