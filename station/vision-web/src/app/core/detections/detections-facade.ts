import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Store } from '@ngrx/store';
import type { DetectionResult, StreamTracksResponse, WorldObject } from '../api/models';
import { detectionsPausedNotice } from '../../shared/player/detection-overlay-logic';
import { LiveFacade } from '../live/live-facade';
import { resolveAssetScopedTransport, trackSessionKey } from '../live/live-fallback-logic';
import { PollScheduler } from '../poll-scheduler';
import { cvStatus, deriveChips, freshResults } from './detections-logic';
import { DetectionsPageActions } from './state/detections.actions';
import type { DetectionsSessionState } from './state/detections.model';
import { detectionsFeature } from './state/detections.reducer';

/** How often the CV status dot's recency check ticks, independent of the poll cadence. */
const CLOCK_TICK_MS = 1_000;

/**
 * The detections slice's read/dispatch boundary (docs/plans/done/NGRX-MIGRATION-PLAN.md wave N5,
 * replacing `DetectionsStore`). `@Injectable()`, **not** `providedIn: 'root'` — component-provided
 * exactly like the old store (`LivePage`/`WallTile`/`CockpitPage`/`CrewPage` list this in their own
 * `providers:` array). See `detections.model.ts`'s own class doc for why the detections feed
 * ({@link track}/{@link reset}) and the tracks poll ({@link trackTracks}/{@link untrackTracks}/
 * {@link followTracks}) are two independent lifecycles that happen to share one state entry.
 *
 * **Poll-vs-live for display** is derived right here, from `LiveFacade` + `resolveAssetScopedTransport`
 * — same posture as `TelemetryFacade`'s own class doc: a facade reading another facade is not the
 * boot-order hazard NGRX-MIGRATION-PLAN.md §9 documents, only an eagerly-injected cross-slice facade
 * inside a `createEffect` factory is.
 */
@Injectable()
export class DetectionsFacade {
  private readonly store = inject(Store);
  private readonly live = inject(LiveFacade);
  private readonly byStreamId = this.store.selectSignal(detectionsFeature.selectByStreamId);

  private readonly currentStreamId = signal<string | undefined>(undefined);
  private lastTrackKey: string | undefined;

  private readonly currentTracksStreamId = signal<string | undefined>(undefined);

  private readonly nowSignal = signal(Date.now());

  private entryFor(streamId: string | undefined): DetectionsSessionState | undefined {
    return streamId === undefined ? undefined : this.byStreamId()[streamId];
  }

  /** The feed lifecycle's own asset id, reused by {@link trackTracks} below regardless of whether its
   *  `streamId` argument matches — see `DetectionsStore#trackTracks`'s own `currentAssetIdSignal()`
   *  read, which is deliberately unconditional on its `streamId` parameter. */
  private readonly currentAssetId = computed(() => this.entryFor(this.currentStreamId())?.assetId);

  /** The currently-tracked stream's raw accumulator (poll or live, per the current transport) —
   *  `results`/`lastSeenAt` both derive from this rather than duplicating the transport check. */
  private readonly rawResults = computed<readonly DetectionResult[]>(() => {
    const entry = this.entryFor(this.currentStreamId());
    if (entry === undefined) {
      return [];
    }
    const transport = resolveAssetScopedTransport(this.live.connectionState(), entry.assetId);
    return transport === 'live' ? entry.liveResults : entry.pollResults;
  });

  /** Whichever source is currently active, aged out through `freshResults` — every other computed
   *  below derives from this, so nothing downstream can see a result the status dot would already
   *  call stale. */
  readonly results = computed(() => freshResults(this.rawResults(), this.nowSignal()));

  /** The last ~8 distinct labels seen, each with its most recently recorded confidence. */
  readonly chips = computed(() => deriveChips(this.results()));

  /** `'on'` (green) when the most recent result is fresh, `'off'` (grey) otherwise. */
  readonly status = computed(() => cvStatus(this.results()[0]?.capturedAt, this.nowSignal()));

  /** The most recently arrived result's `capturedAt`, regardless of freshness — see
   *  `DetectionsStore#lastSeenAt`'s own doc comment for why this reads the raw accumulator, not
   *  {@link results}. */
  private readonly lastSeenAt = computed(() => this.rawResults()[0]?.capturedAt);

  /** "Detections paused — last seen Ns ago" once the feed has gone stale — `null` while fresh or
   *  before anything has ever arrived. */
  readonly pausedNotice = computed(() => detectionsPausedNotice(this.lastSeenAt(), this.nowSignal()));

  /** The world model's latest per-asset object snapshot for the currently-tracked asset — tied to
   *  the detections-feed lifecycle (`track()`/`reset()`), not the tracks lifecycle. Frame-cadence,
   *  live-only — no poll fallback. */
  readonly worldObjects = computed<readonly WorldObject[]>(() => {
    const assetId = this.currentAssetId();
    return assetId === undefined ? [] : this.live.worldObjectsFor(assetId)();
  });

  /** Whichever source is currently active for the tracks session, or `null` before anything has
   *  settled / while not tracked — reuses whichever `assetId` the detections feed most recently
   *  wrote to this same entry (`detections.model.ts`'s own class doc). */
  readonly tracks = computed<StreamTracksResponse | null>(() => {
    const entry = this.entryFor(this.currentTracksStreamId());
    if (entry === undefined) {
      return null;
    }
    const transport = resolveAssetScopedTransport(this.live.connectionState(), entry.assetId);
    if (transport === 'live') {
      return entry.assetId === undefined ? null : this.live.tracksFor(entry.assetId)();
    }
    return entry.tracksPollResponse;
  });

  constructor() {
    const scheduler = inject(PollScheduler);
    const stopClock = scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    // Defense in depth — see `TelemetryFacade`'s identical constructor doc comment.
    inject(DestroyRef).onDestroy(() => {
      stopClock();
      this.reset();
      this.untrackTracks();
    });
  }

  /**
   * Starts tracking `streamId`'s recent detections. **A no-op when `(streamId, assetId)` is
   * unchanged from the current session** — mirrors `DetectionsStore#track`'s own `lastTrackKey`
   * guard.
   */
  track(streamId: string, assetId?: string): void {
    const key = trackSessionKey(streamId, assetId);
    if (this.lastTrackKey === key) {
      return;
    }
    this.lastTrackKey = key;
    const previous = this.currentStreamId();
    this.currentStreamId.set(streamId);
    if (previous !== undefined && previous !== streamId) {
      this.store.dispatch(DetectionsPageActions.reset({ streamId: previous }));
    }
    this.store.dispatch(DetectionsPageActions.tracked({ streamId, assetId }));
  }

  /** Stops tracking (poll + live subscription alike) and clears results — the detections-feed
   *  lifecycle only; a tracks session for the same stream, if any, is untouched. */
  reset(): void {
    const streamId = this.currentStreamId();
    this.lastTrackKey = undefined;
    this.currentStreamId.set(undefined);
    if (streamId !== undefined) {
      this.store.dispatch(DetectionsPageActions.reset({ streamId }));
    }
  }

  /**
   * Marks a tracks session "wanted" for `streamId` — a no-op when `streamId` is unchanged from the
   * current session, mirroring `DetectionsStore#trackTracks`'s own dedupe. Prefer {@link followTracks}.
   */
  trackTracks(streamId: string): void {
    if (this.currentTracksStreamId() === streamId) {
      return;
    }
    const previous = this.currentTracksStreamId();
    this.currentTracksStreamId.set(streamId);
    if (previous !== undefined && previous !== streamId) {
      this.store.dispatch(DetectionsPageActions.tracksUntracked({ streamId: previous }));
    }
    this.store.dispatch(DetectionsPageActions.tracksTracked({ streamId, assetId: this.currentAssetId() }));
  }

  /** Stops the tracks session (poll + live read alike) and clears {@link tracks}. */
  untrackTracks(): void {
    const streamId = this.currentTracksStreamId();
    this.currentTracksStreamId.set(undefined);
    if (streamId !== undefined) {
      this.store.dispatch(DetectionsPageActions.tracksUntracked({ streamId }));
    }
  }

  /** Thin `wanted`-boolean wrapper over {@link trackTracks}/{@link untrackTracks} — see
   *  `DetectionsStore#followTracks`'s own doc comment. */
  followTracks(streamId: string, wanted: boolean): void {
    if (wanted) {
      this.trackTracks(streamId);
    } else {
      this.untrackTracks();
    }
  }
}
