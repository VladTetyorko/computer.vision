import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import type { FleetSummary } from '../../core/api/models';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsFacade } from '../../core/settings/settings-facade';
import { EventsStore } from '../../core/events/events-store';
import { LiveFacade } from '../../core/live/live-facade';
import { PollScheduler } from '../../core/poll-scheduler';
import { isLiveAvailable } from '../../core/live/live-fallback-logic';
import {
  SUMMARY_FLOOR_INTERVAL_MS,
  anyNamesListedAsset,
  invalidationDelayMs,
  listedAssetIds,
} from '../../core/fleet/summary-refresh-logic';
import { activeGeofenceBreaches } from '../../core/geofence/geofence-logic';
import { activePipelineErrorMessagesByStreamId } from '../../core/system-events/system-events-logic';
import { cycleBoxesMode, type BoxesMode } from '../../shared/player/detection-overlay-logic';
import {
  buildWallTiles,
  releaseWallVideo,
  requestWallVideo,
  tileMinPx,
  wallActivityRows,
  type WallActivityRow,
  type WallTileModel,
} from './wall-logic';

/** Matches `command-facade.ts#SUMMARY_POLL_INTERVAL_MS` verbatim — the same fleet-summary read, the
 *  same **not-open fallback** cadence, just consumed by the wall instead of Command. Wave L8a
 *  (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L8a) gated it the same way too: while live is
 *  open the summary refetches on invalidation plus a floor, not on this timer. */
const SUMMARY_POLL_INTERVAL_MS = 5_000;

/** Matches `command-facade.ts#CLOCK_TICK_MS` verbatim, and exists for the same reason — see that
 *  constant's own doc comment. Local, no requests. */
const CLOCK_TICK_MS = 5_000;

/**
 * `WallPage`'s facade (docs/plans/active/WALL-FLOW-PLAN.md, wave W1) — the wall's one frozen surface
 * (§3.4): `wall.ts`/`wall-tile.ts`/`wall-focus.ts`/`wall-activity.ts` inject nothing else (`wall.ts`
 * is guarded by `architecture.spec.ts`; the other three are non-routed presentational children per
 * that spec's own carve-out, but stay dumb by design here too — see WALL-FLOW-PLAN.md §4's own
 * per-wave file scope: "so W2 and W3 never touch this file").
 *
 * **Replaces per-tile polling with one fleet-wide join** (§2.1 D4/D5, accepted decision #3): a 5s
 * `api.fleetSummary()` poll (the exact `command-facade.ts` precedent — same interval, same
 * silent-degrade-on-background-failure shape) feeds `buildWallTiles` alongside `FleetStore`'s
 * devices/streams, `EventsStore`'s shared detection-event feed, `LiveFacade.liveEvents()`-derived
 * pipeline-error/geofence-breach facts, and the wall clock — one 5s tick for identity, attention,
 * health and pulses across every tile, replacing the old per-tile `TelemetryStore`+`DetectionsStore`
 * pair `WallTile` used to stand up itself (D5). `DetectionsStore` still lives in `wall-tile.ts` (W2
 * scope) — per-frame detection *boxes* cannot come from a summary; only the *event* feed that drives
 * health/attention/pulses centralizes here.
 *
 * **Video is opt-in per tile, capped wall-wide** (ALWAYS-ON-FLOW-PLAN.md §4 Wave C1/C2) —
 * `videoUpStreamIds`/{@link isVideoUp}/{@link toggleVideo} own which tiles currently have a
 * `<vision-player>` mounted at all; `wall-tile.ts` renders a state-only placeholder otherwise. See
 * `wall-logic.ts#requestWallVideo`'s own doc comment for the cap number and the evict-vs-refuse and
 * no-auto-raise-on-critical decisions — this facade only ever calls it from an explicit tile click,
 * never from a `tiles()` severity read.
 *
 * **Degrades honestly, matching `command-facade.ts#refreshSummary` byte-for-byte**: a failed poll
 * (backend down, a forbidden org) simply keeps the last-known summary; when there has never been one
 * (`summarySignal() === undefined`), `tiles` is built with `assets: []`, which `wall-logic.ts#buildWallTiles`
 * already turns into an honest `ok`/`unknown` picture — no separate error signal is needed here
 * (unlike Command's own banner), since a wall tile's degrade path is silent by construction (§3.2's
 * own framing).
 *
 * **`unlinked` waits for the first summary response** (fix, live-verification defect: a matching
 * `streamId` existed on both `/api/streams` and `/api/fleet/summary`, yet a freshly-mounted tile
 * still rendered "Not linked to an asset") — `WallFacade` is request-scoped (no `providedIn: 'root'`,
 * §3.4), so `summarySignal` restarts at `undefined` on every navigation to `/wall` even when
 * `FleetStore`'s `streams()`/`devices()` are already warm (root-provided, possibly SSE-live from a
 * prior page). `buildWallTiles` only asserts `unlinked: true` once {@link summaryLoadedSignal} flips
 * (set the moment `refreshSummary` first resolves, success or failure — mirroring the "keeps the
 * last-known summary" degrade rule above), so the join has always had a real chance to run before a
 * tile is allowed to claim "confirmed no asset."
 */
@Injectable()
export class WallFacade {
  private readonly api = inject(VisionApi);
  private readonly fleet = inject(FleetStore);
  private readonly events = inject(EventsStore);
  private readonly live = inject(LiveFacade);
  private readonly scheduler = inject(PollScheduler);
  private readonly settings = inject(SettingsFacade);

  private readonly summarySignal = signal<FleetSummary | undefined>(undefined);
  /** Flips once, after `refreshSummary`'s first attempt settles (success or failure) — see the class
   *  doc comment's "`unlinked` waits for the first summary response" note. Never resets. */
  private readonly summaryLoadedSignal = signal(false);
  /** Drives the pipeline-error decay window and the pulse window on its own {@link CLOCK_TICK_MS}
   *  tick — the same arrangement, and the same wave-L8a reason for it, as
   *  `command-facade.ts#nowSignal`; see that field's own doc comment. */
  private readonly nowSignal = signal(Date.now());

  private readonly pipelineErrorMessagesByStreamId = computed(() =>
    activePipelineErrorMessagesByStreamId(this.live.liveEvents(), this.nowSignal()),
  );
  private readonly breaches = computed(() => activeGeofenceBreaches(this.live.liveEvents()));

  /** The frozen tile model (§3.4) — every field a tile/focus/activity component reads. */
  readonly tiles = computed<readonly WallTileModel[]>(() =>
    buildWallTiles({
      streams: this.fleet.streams(),
      devices: this.fleet.devices(),
      assets: this.summarySignal()?.assets ?? [],
      events: this.events.events(),
      pipelineErrors: this.pipelineErrorMessagesByStreamId(),
      breaches: this.breaches(),
      nowMs: this.nowSignal(),
      summaryLoaded: this.summaryLoadedSignal(),
    }),
  );

  readonly liveCount = computed(() => this.tiles().length);

  // --- Density (§3.1, D11 — replaces the old `Tiles per row` <select>) ------------------------

  /** Aliases `SettingsFacade.wallDensity` directly — unchanged key/type, so an operator's existing
   *  preference (2..6 from the old select) survives this wave; {@link tileMinPx} clamps it to the
   *  nearest of the three frozen stops. */
  readonly density = this.settings.wallDensity;
  setDensity(value: number): void {
    this.settings.wallDensity.set(value);
  }
  readonly tileMinPx = computed(() => tileMinPx(this.density()));

  // --- Declutter (D6 — one wall-level control, no longer per-tile) ----------------------------

  /** Aliases `SettingsFacade.declutterLevel` directly — the same shared, persisted preference the
   *  Fly cockpit and `/live` already read/write (H12, `CockpitFacade#boxesMode`'s identical
   *  simplification). */
  readonly boxesMode = this.settings.declutterLevel;
  /** F2 crop-follow — the same per-viewer `SettingsFacade` signal Fly/Live write; tiles get it as
   *  an input and echo changes back, per the wall's facade-owns-settings idiom (`boxesMode`). */
  readonly cropFollowEnabled = this.settings.cropFollowEnabled;
  cycleBoxesMode(): void {
    this.boxesMode.update((mode: BoxesMode) => cycleBoxesMode(mode));
  }

  // --- Focus (§3.2 A4 L3 — drill-in stays on the wall, never a navigation) --------------------

  private readonly focusedStreamIdSignal = signal<string | null>(null);
  readonly focusedStreamId = this.focusedStreamIdSignal.asReadonly();
  readonly focusedTile = computed<WallTileModel | null>(() => {
    const streamId = this.focusedStreamIdSignal();
    if (streamId === null) {
      return null;
    }
    return this.tiles().find((tile) => tile.streamId === streamId) ?? null;
  });

  focus(streamId: string): void {
    this.focusedStreamIdSignal.set(streamId);
  }
  clearFocus(): void {
    this.focusedStreamIdSignal.set(null);
  }

  // --- Activity drawer (§3.2 A4, W4 — this wall's streams only, last hour) -------------------

  private readonly activityOpenSignal = signal(false);
  readonly activityOpen = this.activityOpenSignal.asReadonly();
  toggleActivity(): void {
    this.activityOpenSignal.update((open) => !open);
  }
  readonly activity = computed<readonly WallActivityRow[]>(() =>
    wallActivityRows(this.events.events(), this.tiles(), this.nowSignal()),
  );
  readonly activityCount = computed(() => this.activity().length);

  // --- Video-on-request (§4 Wave C1/C2 — see `wall-logic.ts#requestWallVideo`'s own doc comment for
  // the cap number and the evict-vs-refuse and no-auto-raise-on-critical decisions) ----------------

  private readonly videoUpStreamIdsSignal = signal<readonly string[]>([]);
  /** O(1) membership for `wall-tile.ts`'s per-tile `[videoUp]` binding — a `@for` over every tile
   *  checking this on every tick makes an array `.includes()` the wrong shape once the wall is busy. */
  private readonly videoUpSet = computed(() => new Set(this.videoUpStreamIdsSignal()));
  isVideoUp(streamId: string): boolean {
    return this.videoUpSet().has(streamId);
  }

  /** The tile's own "Show/Hide video" toggle (`wall-tile.ts`'s `(videoToggled)`). */
  toggleVideo(streamId: string): void {
    this.videoUpStreamIdsSignal.update((raised) =>
      this.isVideoUp(streamId) ? releaseWallVideo(raised, streamId) : requestWallVideo(raised, streamId),
    );
  }

  constructor() {
    // "O(visible) discipline" (docs/plans/done/MVP2-PLAN.md §E, E-b bullet 5) — see `EventsStore`'s own
    // doc comment: the header bell has held a refcount since app boot, so this call is honest about
    // what it does (bumps the refcount) but not about ever actually pausing the poll on its own — D22.
    this.events.activate();
    inject(DestroyRef).onDestroy(() => this.events.release());

    // No `refreshSummary()` here: `applySummaryTransport` below fetches once on its own first run,
    // whichever transport it resolves to. Calling it here as well would double-fetch at construction
    // -- the same reason `MarksStore.activate()` routes through `applyTransport` instead of
    // refreshing directly.
    const stopClock = this.scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(stopClock);

    // D1 + L8a — identical to `command-facade.ts`'s own pair of effects; see those for the reasoning.
    effect(() => {
      this.applySummaryTransport(isLiveAvailable(this.live.connectionState()));
    });

    effect(() => {
      const detectionEvents = this.live.detectionEvents();
      const structural = this.live.fleet() !== undefined || this.live.devices() !== undefined;
      untracked(() => {
        const newEvents = detectionEvents.slice(this.processedDetectionEventCount);
        this.processedDetectionEventCount = detectionEvents.length;
        if (!this.summaryBootstrapped) {
          this.summaryBootstrapped = true;
          return;
        }
        if (!isLiveAvailable(this.live.connectionState())) {
          return; // the fallback poll already covers this case
        }
        if (!structural && !anyNamesListedAsset(newEvents, listedAssetIds(this.summarySignal()))) {
          return;
        }
        this.invalidateSummary();
      });
    });

    inject(DestroyRef).onDestroy(() => this.teardownSummaryTransport());

    // Never leaves the focus view pointed at a tile that just vanished (its stream stopped, or the
    // fleet-summary poll simply hasn't caught up yet) — honest "nothing to show" beats a frozen
    // focus view of a picture that no longer updates.
    effect(() => {
      if (this.focusedStreamIdSignal() !== null && this.focusedTile() === null) {
        this.clearFocus();
      }
    });

    // Mirrors the focus-pruning effect above for the same reason: a raised tile whose stream has
    // stopped (or dropped out of the fleet-summary join) would otherwise sit in `videoUpStreamIds`
    // forever, permanently occupying one of `MAX_CONCURRENT_WALL_PLAYERS`' slots for a tile that no
    // longer renders at all.
    effect(() => {
      const liveStreamIds = new Set(this.tiles().map((tile) => tile.streamId));
      const raised = this.videoUpStreamIdsSignal();
      if (raised.some((streamId) => !liveStreamIds.has(streamId))) {
        this.videoUpStreamIdsSignal.set(raised.filter((streamId) => liveStreamIds.has(streamId)));
      }
    });
  }

  /** See `command-facade.ts`' fields of the same names — this facade mirrors them exactly. */
  private processedDetectionEventCount = 0;
  private summaryBootstrapped = false;
  private summaryLiveGated = false;
  private stopSummaryPollFn: (() => void) | null = null;
  private stopSummaryFloorFn: (() => void) | null = null;
  private summaryInvalidationHandle: ReturnType<typeof setTimeout> | null = null;
  private lastSummaryFetchAtMs = 0;

  /** D1's gate for the fleet-summary read — see `command-facade.ts#applySummaryTransport`'s own table. */
  private applySummaryTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      if (this.summaryLiveGated) {
        return;
      }
      this.stopSummaryPoll();
      void this.refreshSummary();
      this.stopSummaryFloorFn = this.scheduler.schedule(SUMMARY_FLOOR_INTERVAL_MS, () => this.refreshSummary());
      this.summaryLiveGated = true;
      return;
    }
    this.summaryLiveGated = false;
    this.stopSummaryFloor();
    this.cancelPendingInvalidation();
    if (this.stopSummaryPollFn !== null) {
      return; // already polling
    }
    void this.refreshSummary();
    this.stopSummaryPollFn = this.scheduler.schedule(SUMMARY_POLL_INTERVAL_MS, () => this.refreshSummary());
  }

  /** See `command-facade.ts#invalidateSummary` for why this is a raw one-shot timer. */
  private invalidateSummary(): void {
    if (this.summaryInvalidationHandle !== null) {
      return;
    }
    const delay = invalidationDelayMs(this.lastSummaryFetchAtMs, Date.now());
    this.summaryInvalidationHandle = setTimeout(() => {
      this.summaryInvalidationHandle = null;
      void this.refreshSummary();
    }, delay);
  }

  private cancelPendingInvalidation(): void {
    if (this.summaryInvalidationHandle !== null) {
      clearTimeout(this.summaryInvalidationHandle);
      this.summaryInvalidationHandle = null;
    }
  }

  private stopSummaryPoll(): void {
    this.stopSummaryPollFn?.();
    this.stopSummaryPollFn = null;
  }

  private stopSummaryFloor(): void {
    this.stopSummaryFloorFn?.();
    this.stopSummaryFloorFn = null;
  }

  private teardownSummaryTransport(): void {
    this.stopSummaryPoll();
    this.stopSummaryFloor();
    this.cancelPendingInvalidation();
  }

  private async refreshSummary(): Promise<void> {
    this.lastSummaryFetchAtMs = Date.now();
    // Ticks the shared clock on every attempt, success or failure — the pipeline-error decay window
    // and the pulse window should keep advancing even while the summary itself fails to refresh
    // (mirrors `command-facade.ts#refreshSummary`'s identical unconditional tick).
    this.nowSignal.set(Date.now());
    try {
      this.summarySignal.set(await this.api.fleetSummary());
    } catch {
      // Silent-degrade — a background poll failure keeps showing the last-known summary, matching
      // every other poller in this app (and `command-facade.ts`'s own non-first-load path).
    } finally {
      // Set unconditionally, success or failure — a failed *first* attempt still resolves the
      // "have we ever heard back" question, and `buildWallTiles` needs that answer (not the summary
      // itself) to tell "no asset data yet" apart from "confirmed no link."
      this.summaryLoadedSignal.set(true);
    }
  }
}
