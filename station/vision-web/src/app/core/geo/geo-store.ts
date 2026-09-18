import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { CorrectionResponse } from '../api/models';
import { PollScheduler } from '../poll-scheduler';
import { LiveFacade } from '../live/live-facade';
import { type AssetScopedTransport, resolveAssetScopedTransport } from '../live/live-fallback-logic';
import { isVisualGeoDisabledError } from './geo-logic';

/** How often the tracked asset's latest correction is re-read while **polling** (the fallback) is active — matches `DetectionsStore`'s own cadence. */
const POLL_INTERVAL_MS = 2_000;

/**
 * Tracks one asset's latest visual-geolocation correction (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3/§3.4,
 * wave H6) — the cockpit divergence chip + detail popover's (`features/fly/cockpit`) and `TacticalMap`'s
 * corrected-track layer's own source. Polls `GET /api/geo/corrections/live` every 2s while visible
 * — filtering the fleet-wide "latest per asset" response down to the one tracked asset, since §3.3 has
 * no single-asset "latest" route — or, when {@link LiveFacade} is open, subscribes to that asset's live
 * `geo:<assetId>` topic instead. Mirrors `core/detections/detections-store.ts#DetectionsStore`'s dual-transport
 * shape closely, simplified for geo's own narrower domain:
 *
 * - **No `streamId`/`assetId` split.** `DetectionsStore`/`TelemetryStore` key by a device/stream id
 *   with an *optional* `assetId` (their poll fallback works from either). A correction is inherently
 *   asset-scoped end to end — there is no device/stream identity in §3.3 at all — so `track(assetId)`
 *   takes only the asset id, and live is available whenever the connection is open, no `assetId
 *   !== undefined` branch to consider (it's always defined once tracking).
 * - **Latest-value-only, no accumulated history.** Unlike `DetectionsStore.liveResultsSignal`'s
 *   prepend-and-cap list, this store holds a single {@link CorrectionResponse} per transport — §3.4's
 *   own ring capacity 1 already means the live topic never carries more than "the freshest", and the
 *   cockpit chip/popover only ever needs "right now"; a finished flight's full corrected-track
 *   history is a *separate* concern, read directly by `features/replay/**` via
 *   `VisionApi#geoCorrections(usageId)` — this store is never involved in replay at all.
 *
 * **Component-provided, not `providedIn: 'root'`** — `CockpitPage` lists this in its own `providers`
 * (alongside `TelemetryStore`/`DetectionsStore`), so a fresh instance — and its poll/subscription —
 * starts/stops with the route.
 *
 * **Errors silent-degrade, no toast.** A failed poll leaves `latest()` at its last-known value; a
 * missing correction for the tracked asset (never computed yet) reads as `undefined` —
 * `geo-logic.ts#geoChipLabel` renders that as `'GEO —'`, never a fabricated value or a blocked
 * cockpit. **`disabled`** (below) is the one exception that must render as *nothing at all*, not
 * `'GEO —'` — see its own doc comment.
 */
@Injectable()
export class GeoStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveFacade);

  /** Kept fresh by the 2s poll while `transportSignal() === 'poll'`; stale/unused while `'live'`. */
  private readonly pollResultSignal = signal<CorrectionResponse | undefined>(undefined);
  /** Mirrors `LiveFacade.geoFor(assetId)` while `transportSignal() === 'live'`. */
  private readonly liveResultSignal = signal<CorrectionResponse | undefined>(undefined);
  /** The `assetId` passed to the current `track()` call, or `undefined` — drives the transport decision. */
  private readonly currentAssetIdSignal = signal<string | undefined>(undefined);
  /** Which source `latest` currently reads from — see `applyTransport`. */
  private readonly transportSignal = signal<AssetScopedTransport>('poll');

  private stopPollingFn: (() => void) | null = null;
  /** Bumped on every `track`/`reset` so a stale in-flight poll can tell it has been superseded. */
  private generation = 0;
  /** Whether a `track()` call is currently in effect — guards the transport effect. */
  private tracking = false;
  /** Defense in depth against a caller re-entering `track()` with an unchanged asset id — mirrors `DetectionsStore`'s identical `lastTrackKey` field. */
  private lastTrackAssetId: string | undefined;

  /** Whichever source is currently active — `undefined` until this asset has a computed correction. */
  readonly latest = computed<CorrectionResponse | undefined>(() =>
    this.transportSignal() === 'live' ? this.liveResultSignal() : this.pollResultSignal(),
  );

  /**
   * `true` once a poll has actually observed the D9 flag-off 409 (`vision.geo.visual.enabled=false`).
   * Distinct from `latest() === undefined`, which is *also* true for the entirely ordinary
   * "tracked asset has no fix yet" case — that one still renders the chip's honest `'GEO —'` dim
   * state (`geo-logic.ts#geoChipLabel`); this one means the whole feature must be absent, not empty
   * (VISUAL-GEO-V2-PLAN.md §3.8's own "Off state" row) — `GeoChip` gates its entire render on it.
   * Sticky within one `track()` session (never reset back to `false` by a later successful poll) —
   * `vision.geo.visual.enabled` is a deploy-time flag, not something that flips mid-session, so
   * there is no honest transition to animate back from once observed.
   */
  private readonly disabledSignal = signal(false);
  readonly disabled = this.disabledSignal.asReadonly();

  constructor() {
    // Mirrors `DetectionsStore`'s identical effect — see that class's own doc comment for the full
    // "subscription lifecycle vs. transport" split this relies on.
    effect(() => {
      const connectionState = this.live.connectionState();
      const assetId = this.currentAssetIdSignal();
      if (!this.tracking) {
        return;
      }
      this.applyTransport(resolveAssetScopedTransport(connectionState, assetId));
    });

    // Mirrors the live latest arrival straight through — no accumulation needed (see class doc);
    // runs regardless of `transportSignal`'s current value so a poll→live switch has continuity
    // immediately, exactly like `DetectionsStore`'s own always-on accumulator effect.
    effect(() => {
      const assetId = this.currentAssetIdSignal();
      if (assetId === undefined) {
        return;
      }
      const latest = this.live.geoFor(assetId)();
      if (latest === undefined || this.liveResultSignal() === latest) {
        return;
      }
      this.liveResultSignal.set(latest);
    });

    inject(DestroyRef).onDestroy(() => this.teardownTracking());
  }

  /**
   * Starts tracking `assetId`'s latest correction. A no-op when `assetId` is unchanged from the
   * current session — see `lastTrackAssetId`'s own doc comment.
   */
  track(assetId: string): void {
    if (this.lastTrackAssetId === assetId) {
      return;
    }
    this.lastTrackAssetId = assetId;

    this.generation++;
    this.tracking = false;
    this.teardownTracking(); // tears down the *previous* session's subscription/poll, if any
    this.pollResultSignal.set(undefined);
    this.liveResultSignal.set(undefined);
    this.disabledSignal.set(false);

    this.tracking = true;
    this.currentAssetIdSignal.set(assetId);
    this.live.trackGeo(assetId); // ref-counted; lasts for this whole track()/reset() session
    this.applyTransport(resolveAssetScopedTransport(this.live.connectionState(), assetId));
  }

  /** Stops tracking (poll + live subscription alike) and clears the latest correction. */
  reset(): void {
    this.generation++;
    this.tracking = false;
    this.lastTrackAssetId = undefined;
    this.teardownTracking();
    this.currentAssetIdSignal.set(undefined);
    this.pollResultSignal.set(undefined);
    this.liveResultSignal.set(undefined);
    this.disabledSignal.set(false);
    this.transportSignal.set('poll');
  }

  /**
   * Switches which source `latest` reads from and, correspondingly, whether the local poll is
   * running — **not** whether the live subscription itself exists (that's `track()`/`reset()`'s
   * job). Seeds the *other* signal from whatever was last visible so the chip never blanks for a
   * beat on the flip. Mirrors `DetectionsStore.applyTransport`.
   */
  private applyTransport(next: AssetScopedTransport): void {
    if (next === 'live') {
      if (this.liveResultSignal() === undefined && this.pollResultSignal() !== undefined) {
        this.liveResultSignal.set(this.pollResultSignal());
      }
      this.transportSignal.set(next);
      this.stopPolling();
      return;
    }
    if (this.pollResultSignal() === undefined && this.liveResultSignal() !== undefined) {
      this.pollResultSignal.set(this.liveResultSignal());
    }
    this.transportSignal.set(next);
    const assetId = this.currentAssetIdSignal();
    if (assetId === undefined || this.stopPollingFn !== null) {
      return; // nothing to poll, or already polling
    }
    const generation = this.generation;
    void this.pollOnce(assetId, generation);
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(assetId, generation));
  }

  /** Fetches the fleet-wide "latest per asset" list and filters it down to `assetId` client-side — see class doc for why (§3.3 has no single-asset route). */
  private async pollOnce(assetId: string, generation: number): Promise<void> {
    try {
      const response = await this.api.liveGeoCorrections();
      if (generation === this.generation) {
        this.pollResultSignal.set(response.corrections.find((correction) => correction.assetId === assetId));
      }
    } catch (error) {
      if (generation === this.generation && isVisualGeoDisabledError(error)) {
        this.disabledSignal.set(true);
        return;
      }
      // Silent-degrade otherwise: a missed poll just leaves `latest` (and therefore the chip) as it was.
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
      this.live.untrackGeo(assetId);
    }
  }
}
