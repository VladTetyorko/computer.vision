import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { DetectionResult, StreamTracksResponse, WorldObject } from '../api/models';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { cvStatus, deriveChips, freshResults } from './detections-logic';
import { type AssetScopedTransport, resolveAssetScopedTransport, trackSessionKey } from '../live/live-fallback-logic';
import { detectionsPausedNotice } from '../../shared/player/detection-overlay-logic';

/** How often a tracked stream's recent detections are re-read while **polling** (the fallback) is active. */
const POLL_INTERVAL_MS = 2_000;

/**
 * How often {@link trackTracks} re-reads `GET /api/streams/{id}/tracks` while a session is active
 * (docs/plans/done/TRACKING-PLAN.md §4.E) — feeds `CvControlPanel`'s flow strip and is the **only**
 * source the "Following #N" chip is allowed to confirm from (docs/extracts/TRACKING-ORCHESTRATION.md
 * §3.3's honesty rule). Mirrors this store's own detections `POLL_INTERVAL_MS` — fast enough to feel
 * live, slow enough to stay a background read.
 *
 * **Driven by `CockpitFacade`, not by whichever consumer happens to be mounted** (wave W4,
 * docs/plans/active/TRACK-FOLLOW-PLAN.md §3.5 "two feeds, two jobs" — superseding wave W5's original
 * "only while `CvControlPanel` is mounted" bound, `docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3): the
 * facade calls {@link followTracks} from a `wantsTracksPoll`-gated effect so the poll also keeps
 * running for a `LOST` lock even after the Vision drawer (and `CvControlPanel` with it) has closed —
 * see `CockpitFacade`'s own doc comment for the full `wanted` formula. `CvControlPanel` no longer
 * calls {@link trackTracks}/{@link untrackTracks} itself; it only reads {@link tracks} for its chip.
 */
const TRACKS_POLL_INTERVAL_MS = 2_000;

/** How often the CV status dot's recency check ticks, independent of the poll cadence. */
const CLOCK_TICK_MS = 1_000;

/** Matches `StreamController#detections`'s own default limit (docs/plans/done/MVP1-PLAN.md §C8) — also the cap on the live-accumulated list below. */
const DETECTIONS_LIMIT = 50;

/**
 * Tracks one stream's recent detections for the Live page's chip strip + CV status dot
 * (docs/plans/done/MVP1-PLAN.md §C8 bullet 4): polls `GET /api/streams/{streamId}/detections` every 2s while
 * visible, or — when `assetId` is given and {@link LiveStore} is open (docs/plans/done/REALTIME-PLAN.md §4,
 * Phase R-c) — subscribes to that asset's live `detections:<assetId>` topic instead.
 *
 * **`track(streamId, assetId?)`** (`assetId` new in R-c, mirroring `TelemetryStore.track`'s own
 * R-a-established `assetId?` split): the live `detections` topic is asset-scoped, not stream-scoped
 * (docs/plans/done/REALTIME-PLAN.md §4, item 2 — there is no way to subscribe to "this stream's detections"
 * over SSE, only "this asset's"), so a caller with no asset id in scope (`LivePage`/`WallTile` — a
 * bare `deviceId`/`streamId`, no asset context) always polls by `streamId`, regardless of whether
 * `LiveStore` is otherwise connected — exactly as before this cycle. `FlyPage`/`AssetDetailPage`
 * (both already have an asset id) pass it, and get live detections when available.
 *
 * **Latest-frame-only, accumulated client-side**: `LiveStore.detectionsFor` only ever holds the
 * single most recent result (the coalescing registry's own "detections keep only the latest per
 * asset", docs/plans/done/REALTIME-PLAN.md §4, item 3 — there is no backlog to replay). This store still
 * presents the same shape the poll fallback always has — a short recent-history list, newest first
 * — by prepending every newly-arrived live result onto a running `liveResultsSignal` itself
 * (capped at `DETECTIONS_LIMIT`, same as the poll's own `limit` query param), rather than
 * re-deriving history from a single latest value on every read. `liveResultsSignal` itself only ever
 * grows (until the cap) or gets replaced wholesale by `track()`/`reset()` — nothing ever removes a
 * single stale entry from it. Aging individual entries out is `results`' job, not this accumulator's;
 * see `freshResults` on the `results` computed below.
 *
 * **Subscription lifecycle vs. transport, kept separate** (mirrors `TelemetryStore`'s own split —
 * see that class's doc comment for the full reasoning): `track()` subscribes to `LiveStore` once
 * (ref-counted there) for the whole session, torn down only by a matching `reset()`/a later
 * `track()` call; whether `results` is actually *fed* from that subscription or from the local poll
 * toggles purely with `LiveStore.connectionState()`, so a transient SSE drop never spuriously
 * unsubscribes/resubscribes. Switching either direction seeds the *other* signal from whatever was
 * last visible, so the chip strip never blanks for a beat on a transport flip.
 *
 * **Component-provided, not `providedIn: 'root'`.** `LivePage` lists this in its own
 * `providers`, so a fresh instance — and its poll/subscription — starts/stops with the route,
 * exactly like `TelemetryStore`.
 *
 * **Errors silent-degrade, no toast.** A *throwing* poll leaves `pollResultsSignal` at its
 * last-known value (an empty/successful poll, by contrast, replaces it immediately, `[]` included);
 * either way, `results` itself never surfaces anything the status dot would already call stale —
 * `detections-logic.ts#freshResults` ages every entry out of both transports on the same clock
 * `detections-logic.ts#cvStatus` uses for the dot, so a stream that stopped producing detections
 * (switched off, no viewers, a CV outage, cv-service crashed) stops being drawn within one freshness
 * window regardless of which transport was feeding it or whether the server ever clears anything.
 *
 * **Also owns `GET /api/streams/{id}/tracks`** ({@link trackTracks}/{@link untrackTracks}/
 * {@link tracks}, wave W5, docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3) — previously a private poller
 * inside `CvControlPanel`, then (wave W9, decision E25) widened to a transport-aware feed **exactly
 * like {@link results}**: the `tracks:<assetId>` SSE topic now carries the whole response
 * (`StreamTracksResponse`, not just the world-object fold), so with an asset id in scope and
 * `LiveStore` open, {@link tracks} reads `LiveStore.tracksFor` and the poll below never starts;
 * without an asset id (`LivePage`/`WallTile`/`CrewFacade` — a bare `streamId`, no asset context) or
 * while `LiveStore` isn't open, the original 2s poll is the fallback it should always have been.
 * Reuses this class's own {@link currentAssetIdSignal} (set by {@link track}'s own `assetId?`
 * parameter) rather than adding one to {@link followTracks} — the tracks session and the detections
 * feed stay separate lifecycles (next paragraph), they merely share which asset id is in scope.
 *
 * **Deliberately a separate lifecycle from {@link track}/{@link reset}** (the detections feed): the
 * feed follows the active asset/stream regardless of whether the Vision drawer is even open (the box
 * overlay needs it continuously), while tracks tracking is scoped to `CockpitFacade`'s own
 * `wantsTracksPoll`-gated demand — see {@link TRACKS_POLL_INTERVAL_MS}'s own doc comment. Wave W9
 * repoints what that demand gate actually starts/stops: before, `wantsTracksPoll` gated the poll
 * directly (the only mechanism there was); now it gates whether {@link tracks} is "wanted" at all —
 * the transport flip above then picks live over that poll whenever it can, same posture as
 * {@link results}' own always-on subscription vs. gated poll split.
 *
 * **A third, independent thing this store exposes: {@link worldObjects}** (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.6, wave W3.1) — the world model's live `WorldObject[]` snapshot,
 * derived from `LiveStore.worldObjectsFor`, itself now a narrower view of the same `tracks:<assetId>`
 * snapshot {@link tracks} above can read live (wave W9) — see `LiveStore`'s own doc comment. Tied 1:1
 * to the **detections-feed** subscription lifecycle above (`track()`/`teardownTracking()`), **not**
 * the tracks lifecycle two paragraphs up — it starts/stops exactly when `trackDetections`/
 * `untrackDetections` do, for the same asset id, regardless of `wantsTracksPoll`. Frame-cadence,
 * live-only — there is no poll fallback for this field. Wiring only: nothing in this store or wave
 * renders a `WorldObject`/`RenderTier` — that's wave W3.2, a later, different step.
 */
@Injectable()
export class DetectionsStore {
  private readonly api = inject(VisionApi);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveStore);

  /** Kept fresh by the 2s poll while {@link tracksTransportSignal} reads `'poll'` — stale/unused
   *  while `'live'`. `null` before the first poll settles, before {@link trackTracks} has been
   *  called, or on any transport failure (including this endpoint not existing yet on an old/absent
   *  server) — mirrors {@link pollResultsSignal}. */
  private readonly pollTracksResponseSignal = signal<StreamTracksResponse | null>(null);
  /** Which source {@link tracks} currently reads from — mirrors {@link transportSignal}, but scoped
   *  to the tracks lifecycle ({@link tracksWanted}) rather than always active like the detections
   *  feed (wave W9, decision E25 — see class doc's "Also owns GET .../tracks"). */
  private readonly tracksTransportSignal = signal<AssetScopedTransport>('poll');
  /** Whether a `trackTracks()`/`followTracks(..., true)` session is currently in effect — guards the
   *  tracks-transport effect below, mirrors {@link tracking} for the detections feed. */
  private tracksWanted = false;

  /**
   * Whichever source is currently active for the tracks session, or `null` before anything has
   * settled / while not tracked at all — every downstream reader (lock chip, flow strip, detection
   * status) degrades to "hidden" from this one `null`, never a fabricated value. Transport-aware
   * exactly like {@link results} (wave W9, decision E25): reads `LiveStore.tracksFor` while
   * {@link tracksTransportSignal} is `'live'`, the poll's own signal otherwise.
   */
  readonly tracks = computed<StreamTracksResponse | null>(() => {
    if (this.tracksTransportSignal() === 'live') {
      const assetId = this.currentAssetIdSignal();
      return assetId === undefined ? null : this.live.tracksFor(assetId)();
    }
    return this.pollTracksResponseSignal();
  });

  private stopTracksPollFn: (() => void) | null = null;
  /** The stream a `trackTracks()` session is currently for, or `undefined` after `untrackTracks()` —
   *  guards a superseded poll from writing a stale response, mirrors {@link lastTrackKey}. */
  private tracksStreamId: string | undefined;

  /** Kept fresh by the 2s poll while `transportSignal() === 'poll'`; stale/unused while `'live'`. */
  private readonly pollResultsSignal = signal<readonly DetectionResult[]>([]);
  /** Accumulated by the live-update effect below while `transportSignal() === 'live'`. */
  private readonly liveResultsSignal = signal<readonly DetectionResult[]>([]);
  /** The `assetId` passed to the current `track()` call, or `undefined` — drives the transport decision. */
  private readonly currentAssetIdSignal = signal<string | undefined>(undefined);
  /** Which source `results` currently reads from — see `applyTransport`. */
  private readonly transportSignal = signal<AssetScopedTransport>('poll');
  private readonly nowSignal = signal(Date.now());

  private stopPollingFn: (() => void) | null = null;
  private readonly stopClock: () => void;

  /** Bumped on every `track`/`reset` so a stale in-flight poll can tell it has been superseded. */
  private generation = 0;
  /** Whether a `track()` call is currently in effect — guards the transport effect. */
  private tracking = false;
  /** The stream currently being tracked — reused by `applyTransport`'s poll branch across transport flips. */
  private currentStreamId: string | undefined;
  /**
   * The `(streamId, assetId)` pair the current session is for, or `undefined` after `reset()` —
   * defense in depth against a caller re-entering `track()` with an unchanged id; see
   * `TelemetryStore`'s identical `lastTrackKey` field for the full reasoning (this store's own
   * `track()` has no `await` at all, so the risk here is an even *tighter* same-tick re-notify loop,
   * not just an async one).
   */
  private lastTrackKey: string | undefined;

  /**
   * Whichever source is currently active, aged out through {@link freshResults} — every other
   * computed below derives from this, so nothing downstream (chip strip, box overlay, counts) can
   * see a result the status dot itself would already call stale. Re-evaluates on `nowSignal`'s own
   * tick, not a second timer.
   */
  readonly results = computed<readonly DetectionResult[]>(() =>
    freshResults(
      this.transportSignal() === 'live' ? this.liveResultsSignal() : this.pollResultsSignal(),
      this.nowSignal(),
    ),
  );

  /** The last ~8 distinct labels seen, each with its most recently recorded confidence. */
  readonly chips = computed(() => deriveChips(this.results()));

  /** `'on'` (green) when the most recent result is fresh, `'off'` (grey) otherwise. */
  readonly status = computed(() => cvStatus(this.results()[0]?.capturedAt, this.nowSignal()));

  /**
   * The most recently arrived result's `capturedAt`, regardless of freshness — unlike {@link results}
   * (which drops anything that has aged out), this keeps tracking "when did we last actually see
   * something" so a stalled feed can report *how long* it's been silent instead of merely going
   * blank (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.4, D7). Reads the same raw
   * poll/live accumulator {@link results} itself reads before filtering.
   */
  private readonly lastSeenAt = computed(() =>
    (this.transportSignal() === 'live' ? this.liveResultsSignal() : this.pollResultsSignal())[0]?.capturedAt,
  );

  /** "Detections paused — last seen Ns ago" once the feed has gone stale — `null` while fresh or
   *  before anything has ever arrived. Callers that only mean this while detection is actually
   *  supposed to be on (vs. deliberately switched off) gate on that separately — this store has no
   *  opinion on operator intent, only on what has and hasn't arrived. */
  readonly pausedNotice = computed(() => detectionsPausedNotice(this.lastSeenAt(), this.nowSignal()));

  /** The world model's latest per-asset object snapshot for the currently-tracked asset
   *  (`tracks:<assetId>`, wave W3.1), or `[]` when no asset id is in scope or nothing has arrived
   *  yet. Frame-cadence, live-only — there is no poll fallback for this field (unlike {@link results}
   *  above); a viewer without an active SSE connection simply sees no server-assigned render tiers,
   *  same honest-degrade posture as every other live-only signal in this app. */
  readonly worldObjects = computed<readonly WorldObject[]>(() => {
    const assetId = this.currentAssetIdSignal();
    return assetId === undefined ? [] : this.live.worldObjectsFor(assetId)();
  });

  constructor() {
    this.stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));

    // Mirrors `TelemetryStore`'s identical effect — see that class's own doc comment for the full
    // "subscription lifecycle vs. transport" split this relies on.
    effect(() => {
      const connectionState = this.live.connectionState();
      const assetId = this.currentAssetIdSignal();
      if (!this.tracking) {
        return;
      }
      this.applyTransport(resolveAssetScopedTransport(connectionState, assetId));
    });

    // Mirrors the effect above, scoped to the separate, demand-gated tracks lifecycle (wave W9,
    // decision E25) — only runs while a `trackTracks()`/`followTracks(..., true)` session wants
    // tracks at all ({@link tracksWanted}); reuses this store's own already-known asset id rather
    // than a parameter on `followTracks` — see class doc's "Also owns GET .../tracks".
    effect(() => {
      const connectionState = this.live.connectionState();
      const assetId = this.currentAssetIdSignal();
      if (!this.tracksWanted) {
        return;
      }
      this.applyTracksTransport(resolveAssetScopedTransport(connectionState, assetId));
    });

    // Accumulates every newly-arrived live latest-frame result — see class doc's "Latest-frame-only,
    // accumulated client-side". Runs regardless of `transportSignal`'s current value (cheap, and
    // keeps `liveResultsSignal` warm so a poll→live switch has continuity immediately, not just
    // from the next arrival) — reading `results`/`chips`/`status` is what actually gates on transport.
    effect(() => {
      const assetId = this.currentAssetIdSignal();
      if (assetId === undefined) {
        return;
      }
      const latest = this.live.detectionsFor(assetId)();
      if (latest === undefined) {
        return;
      }
      this.liveResultsSignal.update((existing) => {
        if (existing[0] === latest) {
          return existing; // this exact envelope was already applied — effect re-ran for an unrelated reason
        }
        return [latest, ...existing].slice(0, DETECTIONS_LIMIT);
      });
    });

    inject(DestroyRef).onDestroy(() => {
      this.stopClock();
      this.teardownTracking();
      this.stopTracksPoll();
    });
  }

  /**
   * Starts tracking `streamId`'s recent detections. Calling again (e.g. the stream changed)
   * supersedes any in-flight poll/subscription and clears prior results immediately, so a stale
   * stream's chips never linger into the next.
   *
   * **A no-op when `(streamId, assetId)` is unchanged from the current session** — see
   * `lastTrackKey`'s own doc comment for why this matters beyond avoiding redundant work.
   */
  track(streamId: string, assetId?: string): void {
    const key = trackSessionKey(streamId, assetId);
    if (this.lastTrackKey === key) {
      return;
    }
    this.lastTrackKey = key;

    const generation = ++this.generation;
    this.tracking = false;
    this.teardownTracking(); // tears down the *previous* session's subscription/poll, if any
    this.pollResultsSignal.set([]);
    this.liveResultsSignal.set([]);
    this.currentStreamId = streamId;

    this.tracking = true;
    this.currentAssetIdSignal.set(assetId);
    if (assetId !== undefined) {
      this.live.trackDetections(assetId); // ref-counted; lasts for this whole track()/reset() session
      this.live.trackWorldObjects(assetId); // ditto, for the tracks:<assetId> topic (wave W3.1) — see class doc
    }
    this.applyTransport(resolveAssetScopedTransport(this.live.connectionState(), assetId));
  }

  /** Stops tracking (poll + live subscription alike) and clears results. */
  reset(): void {
    this.generation++;
    this.tracking = false;
    this.lastTrackKey = undefined;
    this.teardownTracking();
    this.currentAssetIdSignal.set(undefined);
    this.currentStreamId = undefined;
    this.pollResultsSignal.set([]);
    this.liveResultsSignal.set([]);
    this.transportSignal.set('poll');
  }

  /**
   * Switches which source `results` reads from and, correspondingly, whether the local poll is
   * running — **not** whether the live subscription itself exists (that's `track()`/`reset()`'s
   * job — see class doc). Seeds the *other* signal from whatever was last visible so the chip strip
   * never blanks for a beat on the flip. Mirrors `TelemetryStore.applyTransport`.
   */
  private applyTransport(next: AssetScopedTransport): void {
    if (next === 'live') {
      if (this.liveResultsSignal().length === 0 && this.pollResultsSignal().length > 0) {
        this.liveResultsSignal.set(this.pollResultsSignal());
      }
      this.transportSignal.set(next);
      this.stopPolling();
      return;
    }
    if (this.pollResultsSignal().length === 0 && this.liveResultsSignal().length > 0) {
      this.pollResultsSignal.set(this.liveResultsSignal());
    }
    this.transportSignal.set(next);
    const streamId = this.currentStreamId;
    if (streamId === undefined || this.stopPollingFn !== null) {
      return; // nothing to poll, or already polling
    }
    const generation = this.generation;
    void this.pollOnce(streamId, generation);
    // Returns the poll's own promise so `PollScheduler`'s in-flight guard applies — see
    // `FleetStore`'s identical comment (docs/plans/done/MVP2-PLAN.md §S, S-b).
    this.stopPollingFn = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.pollOnce(streamId, generation));
  }

  private async pollOnce(streamId: string, generation: number): Promise<void> {
    try {
      const results = await this.api.streamDetections(streamId, DETECTIONS_LIMIT);
      if (generation === this.generation) {
        this.pollResultsSignal.set(results);
      }
    } catch {
      // Silent-degrade: a missed poll just leaves results (and therefore the CV dot) as they were.
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
      this.live.untrackDetections(assetId);
      this.live.untrackWorldObjects(assetId); // ditto, for the tracks:<assetId> topic (wave W3.1)
    }
  }

  // --- Tracks (docs/plans/done/TRACKING-PLAN.md §4.E, folded in from `CvControlPanel` in wave W5,
  // transport-flipped wave W9 decision E25 — see class doc's own "Also owns GET .../tracks"
  // paragraph). Independent of `track()`/`reset()`/`teardownTracking()` above (the detections feed)
  // — a separate stream id, a separate "wanted" gate, a separate `null`-degrades-honestly signal —
  // even though the live half of the transport flip below reads the very subscription that lifecycle
  // owns (`LiveStore.tracksFor`, ref-counted by `track()`'s own `trackWorldObjects` call).

  /**
   * Marks a tracks session "wanted" for `streamId` every {@link TRACKS_POLL_INTERVAL_MS} while the
   * poll fallback is what's actually feeding {@link tracks} (transport-aware since wave W9 — see
   * class doc). A no-op when `streamId` is unchanged from the current session, mirroring
   * {@link track}'s own `lastTrackKey` dedupe. Prefer {@link followTracks} — the boolean `wanted`
   * wrapper `CockpitFacade` drives from its own effect (wave W4) — over calling this directly; kept
   * public because `untrackTracks` needs a matching public start, and existing specs exercise this
   * pair directly.
   */
  trackTracks(streamId: string): void {
    if (this.tracksStreamId === streamId) {
      return;
    }
    this.tracksStreamId = streamId;
    this.tracksWanted = true;
    this.stopTracksPoll();
    this.pollTracksResponseSignal.set(null); // never show the previous stream's stale lock/flow strip
    this.applyTracksTransport(resolveAssetScopedTransport(this.live.connectionState(), this.currentAssetIdSignal()));
  }

  /** Stops the tracks session (poll + live read alike) and clears {@link tracks} — call on teardown
   *  (a closed drawer, an unmounted consumer) so a stale lock/flow-strip reading can never linger. */
  untrackTracks(): void {
    this.tracksStreamId = undefined;
    this.tracksWanted = false;
    this.stopTracksPoll();
    this.pollTracksResponseSignal.set(null);
    this.tracksTransportSignal.set('poll');
  }

  /**
   * Thin `wanted`-boolean wrapper over {@link trackTracks}/{@link untrackTracks}
   * (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.5, wave W4) — the shape `CockpitFacade`'s own
   * `wantsTracksPoll`-gated effect can call unconditionally on every re-run without an `if` at the
   * call site. `wanted` is re-evaluated idempotently: calling with the same `streamId`/`wanted` pair
   * twice in a row is a no-op either way ({@link trackTracks}'s own `lastTrackKey`-style dedupe on
   * the "on" branch, `untrackTracks`'s own already-stopped poll on the "off" branch). No `assetId`
   * parameter (wave W9): {@link tracks}' own transport flip reuses whatever {@link track} already
   * established via {@link currentAssetIdSignal}.
   */
  followTracks(streamId: string, wanted: boolean): void {
    if (wanted) {
      this.trackTracks(streamId);
    } else {
      this.untrackTracks();
    }
  }

  /**
   * Switches which source {@link tracks} reads from and, correspondingly, whether the poll fallback
   * is running — mirrors {@link applyTransport}, scoped to the tracks lifecycle. **Never** touches
   * the live subscription itself: that is ref-counted by {@link track}/{@link teardownTracking} via
   * `LiveStore.trackWorldObjects`/`untrackWorldObjects`, piggybacked on the detections-feed session
   * entirely independently of whether a tracks session currently wants live data at all (wave W9,
   * decision E25 — see class doc).
   */
  private applyTracksTransport(next: AssetScopedTransport): void {
    this.tracksTransportSignal.set(next);
    if (next === 'live') {
      this.stopTracksPoll();
      return;
    }
    const streamId = this.tracksStreamId;
    if (streamId === undefined || this.stopTracksPollFn !== null) {
      return; // nothing to poll, or already polling
    }
    void this.pollTracksOnce(streamId);
    this.stopTracksPollFn = this.scheduler.schedule(TRACKS_POLL_INTERVAL_MS, () => this.pollTracksOnce(streamId));
  }

  private async pollTracksOnce(streamId: string): Promise<void> {
    if (this.tracksStreamId !== streamId) {
      return; // superseded by a later trackTracks()/untrackTracks() call — never overwrite the newer session
    }
    try {
      const response = await this.api.getStreamTracks(streamId);
      if (this.tracksStreamId === streamId) {
        this.pollTracksResponseSignal.set(response);
      }
    } catch {
      if (this.tracksStreamId === streamId) {
        this.pollTracksResponseSignal.set(null); // honest degrade — flow strip + lock chip both hide, no toast
      }
    }
  }

  private stopTracksPoll(): void {
    if (this.stopTracksPollFn !== null) {
      this.stopTracksPollFn();
      this.stopTracksPollFn = null;
    }
  }
}
