import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { findVideoDevice } from '../fleet/device-logic';
import { PollScheduler } from '../poll-scheduler';
import { selectOpenUsage } from '../telemetry/telemetry-logic';
import { LiveStore } from '../live/live-store';
import { isLiveAvailable, mergeTelemetrySamples } from '../live/live-fallback-logic';
import type { AssetSummary, Device, TelemetrySample } from '../api/models';
import {
  bucketAssets,
  buildMarkers,
  snapshotFromSamples,
  type AssetTelemetrySnapshot,
} from './map-logic';

/**
 * How often the fleet's asset list is re-read while the map is mounted **and live is unavailable**
 * — the not-open fallback (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1, wave L7a). While
 * `LiveStore` is `'open'` this poll does not run at all: `LiveStore.fleet()` (the `fleet` topic,
 * scoped per connection since wave L6 — see this class's own doc comment) already carries the
 * identical `AssetSummary[]` shape `GET /api/assets` returns, for free, on every connection.
 */
const ASSET_POLL_INTERVAL_MS = 5_000;

/** Matches `AssetController#telemetry`'s own default-overriding call site, same as `TelemetryStore`. */
const TELEMETRY_LIMIT = 200;

/**
 * How often a streaming asset's telemetry is re-read **while live is unavailable** — the not-open
 * fallback for the `telemetry:<assetId>` topic, gated per D1 exactly like {@link
 * ASSET_POLL_INTERVAL_MS} above (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1).
 *
 * <p>Wave L7b originally deleted this poll outright rather than gating it, because the plan's own
 * L7b row said only "retire … in favour of `trackTelemetry`/`telemetryFor`" while its L7a
 * neighbour said "gate per D1; the REST call stays as the not-open fallback". The plan's §7
 * acceptance measurement (`core/live/poll-rate.spec.ts`) caught the consequence: with SSE down, a
 * streaming asset's marker froze at whatever position its one-time backfill happened to capture and
 * still reported `live: true`, while the 5s asset poll went on delivering fresher
 * `lastKnownPosition` values that {@link markers} then outranked with the stale trail. D1 is the
 * frozen rule and it wins: the poll exists, and runs only when live does not.
 */
const TELEMETRY_POLL_INTERVAL_MS = 2_000;

/** How often the "n seconds ago" readout ticks, independent of any poll/live cadence (L7d — stays a local timer). */
const CLOCK_TICK_MS = 1_000;

/**
 * Per-streaming-asset tracker bookkeeping (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L7b) —
 * one `trackTelemetry(assetId)` subscription (ref-counted on `LiveStore`, paired with exactly one
 * `untrackTelemetry(assetId)` — see {@link stopTracker}/{@link teardown}) plus a one-time REST
 * backfill (L7c) merged against whatever live samples arrive on `telemetry:<assetId>`.
 */
interface AssetTracker {
  /**
   * The one-time `GET /api/usages/{usageId}/telemetry` backfill result (L7c) — `TelemetryStore`'s
   * own "always one backfill fetch" precedent, reused rather than reinvented, since the
   * `telemetry:<assetId>` topic carries no snapshot-on-connect (`LiveUpdateRegistry`'s own javadoc:
   * *"a viewer connecting for the first time … sees nothing until the next sample arrives"*) and
   * this store's marker popup wants the same 200-sample trail `TelemetryStore` does. A plain
   * `WritableSignal` (not a settled value) so {@link markers} can merge it reactively against
   * `LiveStore.telemetryFor(assetId)` regardless of arrival order — see this interface's own
   * "ordering hazard" note below.
   */
  readonly backfill: ReturnType<typeof signal<readonly TelemetrySample[]>>;
  /** Bumped whenever this asset's tracker restarts, so a superseded async lookup can no-op. */
  generation: number;
  /**
   * The open usage {@link backfill} was read from, once resolved — the fallback poll's own target,
   * and `undefined` until {@link initTracker} has found one (a streaming asset with no open usage
   * yet has nothing to poll, exactly as it has nothing to backfill).
   */
  usageId: string | undefined;
  /** The fallback poll's unsubscribe, held only while it is actually running (live unavailable). */
  stopPolling: (() => void) | null;
}

/**
 * Polls the whole fleet for the fleet map (originally the `/map` tab, docs/main/CYCLES-PLAN.md §6;
 * now embedded in the Command dashboard, docs/plans/done/MVP3-PLAN.md §C-c — see class doc below).
 *
 * <h2>L7 — `fleet` + `telemetry:&lt;assetId&gt;` replace two REST polls (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L7)</h2>
 * This store used to run a 5s `GET /api/assets` poll plus, per currently-`streaming` asset, its own
 * 2s `GET /api/usages/{id}/telemetry` poll — the single largest request cost in the app (§2 of that
 * plan: ~12 req/min plus 30×N for N streaming assets). Both are now primarily served by
 * {@link LiveStore}, gated exactly like every other L1–L8 store on **D1**: *"poll runs iff there is
 * demand AND live is not open."*
 *
 * **This store's demand axis is satisfied by construction, not by a ref-count.** Unlike the
 * `providedIn: 'root'` stores D1 was written against (`MarksStore`/`FleetStore`/`EventsStore`,
 * which need an explicit `activeConsumers` count because they outlive any one route),
 * `FleetMapStore` is **page-provided** — `CommandPage` lists it in its own `providers`, so a fresh
 * instance (and every one of its timers) is constructed exactly when the page mounts and destroyed
 * exactly when it doesn't. The whole *lifetime* of this store already **is** the demand signal;
 * there is no missing ref-count to "restore" here, and adding one would just track something this
 * class's own constructor/`DestroyRef.onDestroy` already track for free. So `applyTransport` below
 * — this store's own {@link liveGated}-driven state machine, byte-for-byte the same shape as
 * `MarksStore#applyTransport`'s frozen D1 table — collapses to the **live axis alone**:
 *
 * | `liveAvailable` | previous (`liveGated`) | Action |
 * |---|---|---|
 * | `true`  | `false` (was polling) | stop poll; refresh once (the reconcile) |
 * | `true`  | `true`  (was live)    | nothing |
 * | `false` | `true`  (was live)    | refresh once, then start poll |
 * | `false` | `false` (was polling) | nothing (already polling) |
 *
 * **L7a — assets.** `LiveStore.fleet()` carries exactly `AssetSummary[]`, the shape `GET
 * /api/assets` already returns, scoped per connection since wave L6 (before L6 the `fleet` topic
 * was unscoped — see that wave's own doc comment; this store could not have safely joined it
 * before then). It is already read by `features/fly/drone-picker-facade.ts`,
 * `features/fly/cockpit-facade.ts` and `shared/ui/notification-bell.ts` — this store joins existing
 * consumers, not the first one. `assetsSignal` below is fed atomically by whichever transport is
 * current: the poll's own `refresh()`, or the `effect()` folding every new `fleet` envelope —
 * mirrors `FleetStore`'s identical "one signal, either transport writes it" shape for its own
 * `devices` topic, simpler than `TelemetryStore`'s two-signal `computed()` select since a `fleet`
 * envelope is a full snapshot, not a delta to merge.
 *
 * **L7b — telemetry.** Each currently-`streaming` asset gets a {@link AssetTracker}: one
 * `LiveStore.trackTelemetry(assetId)` (ref-counted opt-in) started the moment `reconcileTrackers`
 * notices it, paired with exactly one `untrackTelemetry(assetId)` when that asset stops streaming
 * ({@link stopTracker}) or this store tears down ({@link teardown}) — never zero, never two.
 * `reconcileTrackers` itself is unchanged in shape (still runs after every asset refresh, still
 * tears down/starts trackers by diffing the `streaming` bucket) — only what a tracker *does*
 * changed, from "schedule/cancel a 2s poll" to "subscribe/unsubscribe".
 *
 * **L7c — the backfill.** `telemetry:<assetId>` has no snapshot-on-connect (see
 * {@link AssetTracker#backfill}'s own doc comment), so `initTracker` still resolves the asset's open
 * usage (one `getAsset()`, same as before) and does exactly **one** `GET
 * /api/usages/{id}/telemetry}` — `TelemetryStore.track`'s own precedent, reused rather than a second
 * invented shape. **Ordering hazard**: `trackTelemetry(assetId)` is called immediately in
 * {@link startTracker}, *before* the usage lookup / backfill fetch resolves — so live samples can
 * legitimately start arriving on `LiveStore.telemetryFor(assetId)` while the backfill is still in
 * flight. Nothing is lost or duplicated: `markers` merges `tracker.backfill()` with
 * `live.telemetryFor(assetId)()` via `mergeTelemetrySamples` (dedup by `(deviceId, at)`, re-sorted)
 * on every read, regardless of which one landed first.
 *
 * **L7d — the 1s clock stays.** `nowSignal`'s tick (the "n seconds ago" readout) is local and costs
 * no requests either way; untouched by this wave.
 *
 * **Concurrency cap (docs/main/CYCLES-PLAN.md §6's called-out risk) — preserved.** Only assets
 * currently bucketed `streaming` ever get a tracker. `reconcileTrackers` runs after every asset
 * refresh (poll or live) and tears down any tracker whose asset is no longer streaming (stopped, or
 * vanished) — a referee's fleet is a handful of machines, not hundreds, but an idle map tab still
 * shouldn't accumulate live subscriptions for drones nobody is flying anymore.
 *
 * **Page-provided, not `providedIn: 'root'`** — like `TelemetryStore`, the host page lists this in
 * its own `providers` so every timer (the asset poll while not-live, the 1s clock, and every
 * tracker's telemetry subscription) starts and stops with that page's own route, never running
 * while no page needing it is open. `CommandPage` is the only host today (`MapPage`, the original
 * host, is deleted — `/map` now redirects there, see `features/map/map.routes.ts`'s own doc
 * comment) — the two-hosts period this comment used to describe is over; a second host providing
 * this again in the future would cost nothing new (two independent instances, never simultaneous in
 * this single-route-at-a-time SPA), it just isn't the current shape.
 *
 * **Errors silent-degrade**, same rationale as `TelemetryStore`: this is a background overview, not
 * a user-initiated action, so a failed poll/fetch just leaves signals at their last-known values
 * rather than raising a toast.
 *
 * Moved here from `pages/map/map-store.ts` in docs/plans/done/MVP3-PLAN.md §C-c when the Command dashboard
 * needed to embed `shared/map/fleet-map.ts` (which injects this store) as a second page — this codebase's
 * own "move a page-scoped thing to a shared home once a second page needs it" precedent (see
 * `core/map-logic.ts`'s doc comment). `features/command/command.ts` is that store's sole importer now
 * (`features/map/` holds only a route redirect since `MapPage` was deleted — no `features/map/map.ts`
 * component exists); nothing about this store's own behavior changed by the move.
 */
@Injectable()
export class FleetMapStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveStore);

  private readonly assetsSignal = signal<readonly AssetSummary[]>([]);
  private readonly nowSignal = signal(Date.now());

  private stopAssetPolling: (() => void) | null = null;
  private readonly stopClock: () => void;
  private readonly trackers = new Map<string, AssetTracker>();

  /**
   * Which assets currently have a tracker, as a signal rather than a bare {@link trackers} read.
   * Reading a plain `Map` inside the {@link markers} `computed` is correct today only because of a
   * temporal coupling: {@link reconcileTrackers} is *only* ever called immediately after an
   * `assetsSignal.set(...)`, so the invalidation always precedes the mutation and the lazy
   * recompute always observes the finished map — picking up each tracker's `backfill`/`telemetryFor`
   * as dependencies. That is a real invariant, but an unwritten one: any future path that adds or
   * drops a tracker *without* also writing `assetsSignal` would silently stop invalidating
   * `markers`. Making membership itself a signal removes the dependence on that ordering.
   */
  private readonly trackedAssetIdsSignal = signal<readonly string[]>([]);

  /**
   * `false` while this store is (or should be) relying on the asset poll rather than live — see
   * {@link applyTransport}'s own doc comment (in the class doc above) for the full state table this
   * tracks. Starts `false` so this store's very first `applyTransport` call — whichever way
   * `liveAvailable` resolves — is always treated as a genuine transition, never a spurious no-op
   * before this store has ever actually fetched anything.
   */
  private liveGated = false;

  readonly assets = this.assetsSignal.asReadonly();
  readonly buckets = computed(() => bucketAssets(this.assetsSignal()));
  readonly markers = computed(() => {
    const telemetryByAsset = new Map<string, AssetTelemetrySnapshot>();
    for (const assetId of this.trackedAssetIdsSignal()) {
      const tracker = this.trackers.get(assetId);
      if (tracker === undefined) {
        continue;
      }
      const merged = mergeTelemetrySamples(tracker.backfill(), this.live.telemetryFor(assetId)());
      telemetryByAsset.set(assetId, snapshotFromSamples(merged));
    }
    return buildMarkers(this.assetsSignal(), telemetryByAsset, this.nowSignal());
  });

  constructor() {
    // Bootstraps the asset transport directly (mirrors `MarksStore.activate()`'s own direct call —
    // this store has no `activate()` of its own to do it instead; see class doc's "demand axis is
    // satisfied by construction").
    this.applyTransport(isLiveAvailable(this.live.connectionState()));
    this.stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));

    // Re-evaluates poll-vs-live whenever `LiveStore` (re)connects or drops — mirrors
    // `MarksStore`/`FleetStore`/`EventsStore`'s identical reconnect-driven effect.
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });

    // Applies each `fleet` snapshot atomically the moment one arrives — independent of whether the
    // poll is currently running, so a snapshot that lands before the connectionState effect above
    // has paused polling (or one that arrives while genuinely subscribed) is never dropped. Mirrors
    // `FleetStore`'s identical `devices`-projection effect.
    effect(() => {
      const snapshot = this.live.fleet();
      if (snapshot !== undefined) {
        this.assetsSignal.set(snapshot);
        this.reconcileTrackers(snapshot);
      }
    });

    inject(DestroyRef).onDestroy(() => this.teardown());
  }

  /**
   * Re-reads `GET /api/assets` and reconciles telemetry trackers against the result. Public (like
   * `FleetStore.refresh`) so it is unit-testable without waiting on the internal interval, even
   * though nothing currently calls it besides `applyTransport` (poll tick, or the one-time reconcile
   * on a live transition) and the constructor's own bootstrap.
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

  /**
   * D1's frozen gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3), collapsed to the live axis
   * alone — see class doc for why this store has no `activeConsumers` term to compose against.
   * Called both by the reconnect-driven `effect()` in the constructor and directly, once, at
   * construction (there is no `activate()` to make that first call instead).
   */
  private applyTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      if (!this.liveGated) {
        this.stopPolling();
        void this.refresh();
        this.liveGated = true;
        this.applyTrackerTransports();
      }
      return;
    }
    const wasLive = this.liveGated;
    this.liveGated = false;
    if (wasLive) {
      this.applyTrackerTransports();
    }
    if (this.stopAssetPolling !== null) {
      return; // already polling
    }
    void this.refresh();
    // Returns `refresh()`'s own promise so `PollScheduler`'s in-flight guard applies — see
    // `FleetStore`'s identical comment (docs/plans/done/MVP2-PLAN.md §S, S-b).
    this.stopAssetPolling = this.scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopAssetPolling?.();
    this.stopAssetPolling = null;
  }

  /** Re-applies D1's live axis to every live tracker — the telemetry counterpart of {@link stopPolling}. */
  private applyTrackerTransports(): void {
    for (const tracker of this.trackers.values()) {
      this.applyTrackerTransport(tracker);
    }
  }

  /**
   * D1 for one tracker's telemetry: poll iff live is unavailable and an open usage is known.
   * Idempotent in both directions, so it is safe to call from {@link initTracker} (a tracker that
   * has only just learned its usage) and from {@link applyTransport} (every tracker, on a
   * transition) without tracking which of the two got there first.
   */
  private applyTrackerTransport(tracker: AssetTracker): void {
    if (this.liveGated || tracker.usageId === undefined) {
      tracker.stopPolling?.();
      tracker.stopPolling = null;
      return;
    }
    if (tracker.stopPolling !== null) {
      return; // already polling
    }
    const usageId = tracker.usageId;
    // Returns the promise so `PollScheduler`'s in-flight guard applies, as everywhere else here.
    tracker.stopPolling = this.scheduler.schedule(TELEMETRY_POLL_INTERVAL_MS, () =>
      this.pollTelemetry(tracker, usageId),
    );
  }

  /** One fallback telemetry read, folded into the same signal {@link markers} already merges. */
  private async pollTelemetry(tracker: AssetTracker, usageId: string): Promise<void> {
    const samples = await this.fetchBackfill(usageId);
    if (tracker.stopPolling !== null) {
      tracker.backfill.set(samples); // still the live tracker for this asset, still degraded
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
    const tracker: AssetTracker = {
      backfill: signal<readonly TelemetrySample[]>([]),
      generation: 0,
      usageId: undefined,
      stopPolling: null,
    };
    this.trackers.set(assetId, tracker);
    this.publishTrackedIds();
    // Subscribes immediately — before the usage lookup/backfill below even starts — so live samples
    // are never missed while this store is still resolving which usage to backfill from. Paired
    // with exactly one `untrackTelemetry` in `stopTracker`/`teardown`.
    this.live.trackTelemetry(assetId);
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
        return; // streaming per the last asset refresh, but no open usage found (yet) — next reconcile retries
      }

      const samples = await this.fetchBackfill(usageId);
      if (tracker.generation !== generation || !this.trackers.has(assetId)) {
        return;
      }
      tracker.backfill.set(samples);
      tracker.usageId = usageId;
      this.applyTrackerTransport(tracker);
    } catch {
      // best-effort — see class doc; this asset's marker just falls back to lastKnownPosition
    }
  }

  /** The one-time backfill fetch (L7c) — on failure, leaves the tracker's trail at `[]` (live deltas alone still merge in fine). */
  private async fetchBackfill(usageId: string): Promise<readonly TelemetrySample[]> {
    try {
      return await this.api.usageTelemetry(usageId, TELEMETRY_LIMIT);
    } catch {
      return [];
    }
  }

  private stopTracker(assetId: string): void {
    const tracker = this.trackers.get(assetId);
    tracker?.stopPolling?.();
    if (tracker !== undefined) {
      tracker.stopPolling = null;
    }
    this.trackers.delete(assetId);
    this.publishTrackedIds();
    this.live.untrackTelemetry(assetId);
  }

  /** Republishes {@link trackedAssetIdsSignal} off {@link trackers} — every add/remove must call it. */
  private publishTrackedIds(): void {
    this.trackedAssetIdsSignal.set([...this.trackers.keys()]);
  }

  private teardown(): void {
    this.stopPolling();
    this.stopClock();
    for (const assetId of [...this.trackers.keys()]) {
      this.stopTracker(assetId);
    }
  }
}
