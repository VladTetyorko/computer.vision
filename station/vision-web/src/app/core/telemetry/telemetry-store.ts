import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { AssetDetails, TelemetrySample } from '../api/models';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { ageSeconds, deriveTrail, findOwningAsset, isStale, selectOpenUsage } from './telemetry-logic';
import {
  type AssetScopedTransport,
  mergeTelemetrySamples,
  resolveAssetScopedTransport,
  trackSessionKey,
} from '../live/live-fallback-logic';

/** How often a tracked usage's telemetry is re-read while **polling** (the fallback) is active. */
const POLL_INTERVAL_MS = 2_000;

/** How often the "n seconds ago" readout ticks, independent of when a poll last landed. */
const CLOCK_TICK_MS = 1_000;

/** Matches `AssetController#telemetry`'s own default-overriding call site (docs/main/CYCLES-PLAN.md §2). */
const TELEMETRY_LIMIT = 200;

/**
 * Tracks one device's live telemetry: resolves the owning asset's currently-open usage, then either
 * subscribes to that asset's live SSE telemetry (docs/plans/done/REALTIME-PLAN.md §4, Phase R-c) or polls that
 * usage's trail every 2s — whichever `LiveStore.connectionState()` currently supports.
 *
 * **`track(deviceId, assetId?)`** (docs/plans/done/REALTIME-PLAN.md Phase R-a item 3, `assetId` new) resolves
 * the device's owning asset and its open usage, same as before R-c. **New in R-c**: `track()` always
 * does exactly **one** `GET /api/usages/{usageId}/telemetry` — history backfill, not the start of a
 * repeating poll — then, only when `assetId` is given, subscribes to `telemetry:<assetId>` on the
 * shared {@link LiveStore} for as long as this `track()`/`reset()` session lasts (ref-counted there,
 * so a second consumer watching the same asset costs nothing extra server-side). Whether this store
 * actually *reads* from that live subscription or falls back to its own 2s poll toggles purely with
 * `LiveStore.connectionState()` (`live-fallback-logic.ts#resolveAssetScopedTransport`) — the
 * subscription itself is not torn down and re-created on every connection blip, only on a genuine
 * `track()`/`reset()` — see this class's own `applyTransport`/`teardownTracking` for why that split
 * matters (a transient SSE drop must not spuriously PATCH-unsubscribe and PATCH-resubscribe).
 *
 * `assetId` omitted (`LivePage`/`WallTile`, which only ever have a bare `deviceId` — see R-a's own
 * paragraph on this) means there is no asset-scoped topic to subscribe to at all — this store then
 * always polls, exactly as it did before R-c, regardless of whether `LiveStore` is otherwise
 * connected (`resolveAssetScopedTransport` requires an `assetId` to ever return `'live'`).
 *
 * **Component-provided, not `providedIn: 'root'`.** `LivePage` and each `WallTile` list this in
 * their own `providers`, so a fresh instance — and its poll timer/live subscription — is created
 * and destroyed with that component (route leave, tile unmount), matching "lifecycle must stop on
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
  private readonly live = inject(LiveStore);

  /** The one-time backfill fetch on `track()` — history, never touched again until the next `track()`. */
  private readonly backfillSignal = signal<readonly TelemetrySample[]>([]);
  /** Kept fresh by the 2s poll while `transportSignal() === 'poll'`; stale/unused while `'live'`. */
  private readonly pollSamplesSignal = signal<readonly TelemetrySample[]>([]);
  /** The `assetId` passed to the current `track()` call, or `undefined` — drives the transport decision. */
  private readonly currentAssetIdSignal = signal<string | undefined>(undefined);
  /** Which source `samples` currently reads from — see `applyTransport`. */
  private readonly transportSignal = signal<AssetScopedTransport>('poll');
  private readonly nowSignal = signal(Date.now());

  private stopPollingFn: (() => void) | null = null;
  private readonly stopClock: () => void;

  /** Bumped on every `track`/`reset` so a stale async lookup can tell it has been superseded. */
  private generation = 0;
  /** Whether a `track()` call is currently in effect (has resolved an open usage) — guards the transport effect. */
  private tracking = false;
  /** The last known-open usage id — reused by `applyTransport`'s poll branch across transport flips. */
  private currentUsageId: string | undefined;
  /**
   * The `(deviceId, assetId)` pair the current/in-flight `track()` session is for, or `undefined`
   * after `reset()` — defense in depth (docs/plans/done/REALTIME-PLAN.md Phase R-c follow-up,
   * `core/telemetry/telemetry-logic.ts#trackingIdChanged`'s own doc comment) against a caller that
   * re-enters `track()` with an unchanged id: without this, every call unconditionally tore down and
   * rebuilt the session, and doing so from inside an already-executing caller effect let a write deep
   * inside that teardown (`currentAssetIdSignal`, below) get attributed back to the *caller's* effect,
   * re-notifying it with nothing it actually reads changed — a tight, self-sustaining track/untrack
   * loop, not merely a wasted re-fetch. A caller-side guard (`AssetDetailPage`/`FlyPage`'s own
   * `trackingIdChanged` checks) is the primary fix; this is the second layer, for any call site that
   * doesn't guard itself (`LivePage`/`WallTile`, both always passing the same `deviceId` with no
   * `assetId` — harmless today since `assetId` never actually changes there, but not guaranteed to
   * stay that way).
   */
  private lastTrackKey: string | undefined;

  /**
   * Reads live (`LiveStore.telemetryFor`, merged with the one-time backfill) or the poll fallback,
   * per `transportSignal` — every other computed below derives from this, not either source
   * directly, so the rest of this class's public API never needs to know which is active.
   */
  readonly samples = computed<readonly TelemetrySample[]>(() => {
    if (this.transportSignal() === 'live') {
      const assetId = this.currentAssetIdSignal();
      if (assetId !== undefined) {
        return mergeTelemetrySamples(this.backfillSignal(), this.live.telemetryFor(assetId)());
      }
    }
    return this.pollSamplesSignal();
  });

  /** Whether any telemetry has arrived yet for the currently tracked device. */
  readonly hasTelemetry = computed(() => this.samples().length > 0);

  readonly latest = computed<TelemetrySample | undefined>(() => {
    const samples = this.samples();
    return samples.length > 0 ? samples[samples.length - 1] : undefined;
  });

  /** Chronological lat/lon points for the map's breadcrumb trail. */
  readonly trail = computed(() => deriveTrail(this.samples()));

  /** Seconds since the latest sample; ticks every second independent of the 2s poll cadence. */
  readonly sampleAgeSeconds = computed(() => ageSeconds(this.latest()?.at, this.nowSignal()));

  /** Whether the latest sample is old enough to be a safety concern — the OSD highlights this. */
  readonly stale = computed(() => isStale(this.sampleAgeSeconds()));

  constructor() {
    this.stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));

    // Re-evaluates poll-vs-live whenever `LiveStore` (re)connects or drops, for as long as a
    // `track()` session is in effect — the *initial* choice is made directly, synchronously, in
    // `startTracking` below; this effect only ever handles a *later* transition mid-session
    // (docs/plans/done/REALTIME-PLAN.md §4, item 5). Deliberately does **not** touch the live subscription
    // itself (`LiveStore.trackTelemetry`/`untrackTelemetry`) — only `applyTransport`'s local poll
    // scheduler toggles here; see the class doc for why a transient drop must not spuriously
    // unsubscribe/resubscribe.
    effect(() => {
      const connectionState = this.live.connectionState();
      const assetId = this.currentAssetIdSignal();
      if (!this.tracking) {
        return;
      }
      this.applyTransport(resolveAssetScopedTransport(connectionState, assetId));
    });

    inject(DestroyRef).onDestroy(() => {
      this.stopClock();
      this.teardownTracking();
    });
  }

  /**
   * Starts tracking `deviceId`: looks up its owning asset's open usage, does one backfill fetch,
   * then subscribes live (if `assetId` is given) or polls. Fire-and-forget by design — callers
   * (typically an `effect` reacting to a device id signal) read `samples`/`latest`/etc. as they
   * fill in rather than awaiting this.
   *
   * Calling again (e.g. the route's `deviceId` changed) supersedes any in-flight lookup, tears down
   * the previous session's subscription/poll, and clears prior samples immediately, so a stale
   * device's trail never lingers into the next.
   *
   * **A no-op when `(deviceId, assetId)` is unchanged from the current/in-flight session** — see
   * `lastTrackKey`'s own doc comment for why this matters beyond avoiding a wasted re-fetch.
   */
  track(deviceId: string, assetId?: string): void {
    const key = trackSessionKey(deviceId, assetId);
    if (this.lastTrackKey === key) {
      return;
    }
    this.lastTrackKey = key;
    void this.startTracking(deviceId, assetId);
  }

  /** Stops tracking (poll + live subscription alike) and clears samples. */
  reset(): void {
    this.generation++;
    this.tracking = false;
    this.lastTrackKey = undefined;
    this.teardownTracking();
    this.currentAssetIdSignal.set(undefined);
    this.currentUsageId = undefined;
    this.backfillSignal.set([]);
    this.pollSamplesSignal.set([]);
    this.transportSignal.set('poll');
  }

  private async startTracking(deviceId: string, assetId?: string): Promise<void> {
    const generation = ++this.generation;
    this.tracking = false;
    this.teardownTracking(); // tears down the *previous* session's subscription/poll, if any
    this.currentAssetIdSignal.set(undefined);
    this.backfillSignal.set([]);
    this.pollSamplesSignal.set([]);
    this.currentUsageId = undefined;

    const usageId = await this.findOpenUsageId(deviceId, assetId);
    if (generation !== this.generation) {
      return; // superseded by a newer track()/reset()
    }
    this.currentUsageId = usageId;
    if (usageId === undefined) {
      return; // this device has no open usage — nothing to poll or subscribe to
    }

    const initial = await this.fetchSamples(usageId);
    if (generation !== this.generation) {
      return;
    }
    this.backfillSignal.set(initial);
    this.pollSamplesSignal.set(initial);

    this.tracking = true;
    this.currentAssetIdSignal.set(assetId);
    if (assetId !== undefined) {
      this.live.trackTelemetry(assetId); // ref-counted; lasts for this whole track()/reset() session
    }
    // `immediatePoll: false` — `pollSamplesSignal` was just populated by the backfill fetch above;
    // an immediate re-poll here would be a redundant, wasted second GET for data we already have.
    this.applyTransport(resolveAssetScopedTransport(this.live.connectionState(), assetId), { immediatePoll: false });
  }

  /**
   * Switches which source `samples` reads from and, correspondingly, whether the local poll is
   * running — **not** whether the live subscription itself exists (that's `track()`/`reset()`'s
   * job, once per session — see class doc). `'live'` just pauses the poll (no need to also fetch —
   * `LiveStore` is delivering data already); `'poll'` starts/resumes it. `immediatePoll` (default
   * `true`) skips the immediate fetch only when the caller already has fresh data in hand
   * (`startTracking`'s own initial backfill) — every other caller (the reconnect-driven `effect`
   * above, falling back from a dropped live connection) wants one right away, since `pollSamplesSignal`
   * may be stale (unused, possibly minutes old) after however long the live connection was up.
   */
  private applyTransport(next: AssetScopedTransport, options: { immediatePoll?: boolean } = {}): void {
    this.transportSignal.set(next);
    if (next === 'live') {
      this.stopPolling();
      return;
    }
    const usageId = this.currentUsageId;
    if (usageId === undefined || this.stopPollingFn !== null) {
      return; // nothing to poll, or already polling
    }
    if (options.immediatePoll ?? true) {
      void this.pollOnce(usageId);
    }
    // Returns the poll's own promise so `PollScheduler`'s in-flight guard applies — see
    // `FleetStore`'s identical comment (docs/plans/done/MVP2-PLAN.md §S, S-b).
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(usageId));
  }

  /**
   * A device belongs to at most one asset; that asset's open usage is what we poll/subscribe to.
   *
   * **O(1) path** (docs/plans/done/REALTIME-PLAN.md Phase R-a item 3): when `assetId` is given, one
   * `getAsset(assetId)` resolves the open usage directly — no need to find *which* asset owns
   * `deviceId` when the caller already knows. Falls back to the O(fleet-size) list-then-find below
   * only when `assetId` is omitted.
   */
  private async findOpenUsageId(deviceId: string, assetId?: string): Promise<string | undefined> {
    try {
      if (assetId !== undefined) {
        const details = await this.api.getAsset(assetId);
        return selectOpenUsage(details.recentUsages)?.usageId;
      }
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

  /** The one-time/backfill fetch — on failure, returns `[]` (the same starting state as a fresh reset). */
  private async fetchSamples(usageId: string): Promise<readonly TelemetrySample[]> {
    try {
      return await this.api.usageTelemetry(usageId, TELEMETRY_LIMIT);
    } catch {
      return [];
    }
  }

  private async pollOnce(usageId: string): Promise<void> {
    try {
      this.pollSamplesSignal.set(await this.api.usageTelemetry(usageId, TELEMETRY_LIMIT));
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

  /** Stops the local poll and releases the live subscription for whatever asset the *previous* session tracked. */
  private teardownTracking(): void {
    this.stopPolling();
    const assetId = this.currentAssetIdSignal();
    if (assetId !== undefined) {
      this.live.untrackTelemetry(assetId);
    }
  }
}
