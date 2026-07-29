import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/fleet/device-logic';
import { deriveTrail, groupTelemetryByDevice, telemetryDevices } from '../../core/telemetry/telemetry-logic';
import { formatDuration } from '../../core/stream-info-logic';
import type { AssetDetails, TelemetrySample, UsageRecording, UsageTimeline } from '../../core/api/models';
import { ReplayMap } from './replay-map';
import {
  advancePlaybackClock,
  buildClipDownloadUrl,
  bucketDetections,
  capDetectionBuckets,
  clampToRange,
  isDetectionNear,
  nearestDetectionResult,
  nearestSample,
  parseDeepLinkOffsetMs,
  selectedClipWindow,
  shouldSeekVideo,
  trailPrefix,
  videoOffsetSeconds,
  videoTimeToAtMs,
  type DetectionDensityBucket,
  type PlaybackSpeed,
} from './replay-logic';

/** The server's own downsample ceiling (`UsageTimelineController`) — asked for once, up front. */
const MAX_POINTS = 2_000;

const PLAYBACK_SPEEDS: readonly PlaybackSpeed[] = [1, 4, 16];

/**
 * The flight-replay cockpit (`/assets/:assetId/replay/:usageId`, docs/MVP2-PLAN.md §R, R-b) — the
 * asset detail page's usage history "Replay" target for a finished usage. A referee's use case:
 * "verify where the machine was at minute 7."
 *
 * **Fetches once, scrubs in memory.** `load()` fetches the asset (for its display name/devices)
 * and the usage's timeline (`VisionApi.usageTimeline`, `maxPoints: 2000` — the server's own clamp
 * ceiling) exactly once on route activation; there is no poller and no re-fetch as the scrub
 * position changes. Every panel (map trail/marker, per-device telemetry, detections) is a
 * `computed()` over that one cached `timeline` signal plus the current `atMs` scrub signal, using
 * the pure functions in `replay-logic.ts` (`nearestSample`/`trailPrefix`/`bucketDetections`/
 * `nearestDetectionResult`) — O(log n) per scrub tick, which is what keeps dragging the scrub bar
 * smooth regardless of drag frame rate.
 *
 * **Playback** advances `atMs` via `requestAnimationFrame`, not `PollScheduler` — mirrors
 * `shared/player/player.ts`'s own precedent of using raw browser timers for a *playback* clock tied to a
 * single view's lifecycle rather than a "re-fetch while visible" poll (see vision-web/MODULE.md
 * Gotchas). `advancePlaybackClock` (`replay-logic.ts`) is the pure reducer driving each frame;
 * reaching the end of the window stops playback rather than looping.
 *
 * **Open usages are out of scope by design** (docs/MVP2-PLAN.md §R, R-b: "the history row shows
 * Replay only for finished usages… open one = watch live instead"). A direct link to an open
 * usage's replay URL still resolves (the endpoint itself works fine for one — `to` just defaults
 * to "now"), but this page shows a redirect notice instead of the cockpit rather than replaying a
 * window that is still growing underneath the scrub bar.
 *
 * **Two routes, one component** (`replay.routes.ts`): the asset detail page's usage-history
 * "Replay" link still reaches `/assets/:assetId/replay/:usageId` (path params, unaliased inputs —
 * unchanged); the event → replay deep link (docs/OPS-CORE-PLAN.md §Q1) reaches a second, flat
 * `/replay` route via query params instead (`?asset=…&usage=…&t=…`, aliased `assetIdParam`/
 * `usageIdParam`/`deepLinkOffsetParam` below) — `effectiveAssetId`/`effectiveUsageId` resolve
 * whichever source actually supplied a value. `assetId` is genuinely optional on the flat route
 * (an asset id isn't in the deep link's own pinned URL shape) — see `effectiveAssetId`'s own doc
 * comment for how the page degrades when it's absent; in practice, every deep-link call site this
 * batch adds (`shared/ui/notification-bell.ts`, `features/wall/wall.ts`) already has the asset id
 * in hand at click time and includes it anyway, so the degraded path is a defensive fallback, not
 * the common case.
 *
 * **Recording video pane + clip export (docs/OPS-CORE-PLAN.md §R, R-c)**: `GET
 * /api/usages/{usageId}/recording` is fetched once, alongside the timeline; when `available`, a
 * plain `<video controls>` bound straight to the returned mp4 `url` sits beside the scrub bar,
 * `[currentTime]` kept in sync with the scrub position two ways — see the guarded-effect pair
 * (`videoDrivenUpdate` flag) just below `atMs` for the full "who's driving whom, and how the
 * feedback loop is broken" writeup, mirroring `shared/map/fleet-map/fleet-map.ts#suppressAutoFitDisable`'s
 * identical idiom. `available: false`, or the `<video>` itself firing an `error` event (a
 * genuinely-missing recording segment, e.g. recording enabled only after this flight happened),
 * both land on the same compact empty state — never a broken player. "Download clip" builds an
 * `<a download>` href from the current clip-mark selection (or the whole flight, with none) via the
 * pure `replay-logic.ts#buildClipDownloadUrl`/`selectedClipWindow`.
 */
@Component({
  selector: 'vision-replay',
  imports: [RouterLink, ReplayMap],
  templateUrl: './replay.html',
  styleUrl: './replay.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReplayPage {
  /** Bound from the nested route by `withComponentInputBinding()` — both names match their `:param`s exactly. */
  readonly assetId = input<string | undefined>(undefined);
  readonly usageId = input<string | undefined>(undefined);

  /** The flat `/replay?asset=…&usage=…&t=…` deep link's own query params (docs/OPS-CORE-PLAN.md §Q1) — see class doc. */
  readonly assetIdParam = input<string | undefined>(undefined, { alias: 'asset' });
  readonly usageIdParam = input<string | undefined>(undefined, { alias: 'usage' });
  readonly deepLinkOffsetParam = input<string | undefined>(undefined, { alias: 't' });

  /** Whichever route actually supplied an asset id — `undefined` only for a hand-typed/malformed flat-route URL missing `?asset=`. */
  protected readonly effectiveAssetId = computed(() => this.assetId() ?? this.assetIdParam());
  protected readonly effectiveUsageId = computed(() => this.usageId() ?? this.usageIdParam());

  private readonly api = inject(VisionApi);

  protected readonly speeds = PLAYBACK_SPEEDS;

  protected readonly asset = signal<AssetDetails | undefined>(undefined);
  protected readonly timeline = signal<UsageTimeline | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  // --- Recording video pane (docs/OPS-CORE-PLAN.md §R, R-c) -----------------------------------
  protected readonly recording = signal<UsageRecording | undefined>(undefined);
  protected readonly videoErrored = signal(false);
  protected readonly videoAvailable = computed(
    () => this.recording()?.available === true && this.recording()?.url !== undefined && !this.videoErrored(),
  );
  private readonly recordingStartMs = computed(() => {
    const start = this.recording()?.start;
    return start !== undefined ? Date.parse(start) : undefined;
  });
  private readonly videoEl = viewChild<ElementRef<HTMLVideoElement>>('recordingVideo');
  /**
   * Set by `onVideoTimeUpdate` immediately before it writes `atMs`, checked (and cleared) by the
   * `atMs`→video sync effect below — the guarded-effect pair that stops the two from fighting each
   * other in a loop, mirroring `shared/map/fleet-map/fleet-map.ts#suppressAutoFitDisable`'s own
   * "flag set right before a programmatic write, checked by the listener that write would
   * otherwise re-trigger" idiom. Only one side is ever "driving" `atMs` at a time by construction:
   * this page's own `playing()` RAF clock when `true` (see `onVideoTimeUpdate`'s own early-return),
   * otherwise whichever of {a scrub/jump, the video's own native controls} last touched it.
   */
  private videoDrivenUpdate = false;

  // --- Clip export (docs/OPS-CORE-PLAN.md §R, R-c) ---------------------------------------------
  protected readonly clipSelectionStartMs = signal<number | undefined>(undefined);
  protected readonly clipSelectionEndMs = signal<number | undefined>(undefined);
  protected readonly hasClipSelection = computed(
    () => this.clipSelectionStartMs() !== undefined && this.clipSelectionEndMs() !== undefined,
  );
  private readonly clipWindow = computed(() => {
    const recordingStartMs = this.recordingStartMs();
    if (recordingStartMs === undefined) {
      return undefined;
    }
    return selectedClipWindow(recordingStartMs, this.clipSelectionStartMs(), this.clipSelectionEndMs(), this.fromMs(), this.toMs());
  });
  /** The "Download clip" `<a download>`'s own href — `undefined` (button hidden) whenever no recording/window is resolvable. */
  protected readonly downloadClipUrl = computed(() => {
    const url = this.recording()?.url;
    const window = this.clipWindow();
    return url && window ? buildClipDownloadUrl(url, window) : undefined;
  });

  protected readonly fromMs = computed(() => (this.timeline() ? Date.parse(this.timeline()!.from) : 0));
  protected readonly toMs = computed(() => (this.timeline() ? Date.parse(this.timeline()!.to) : 0));
  protected readonly usageOpen = computed(() => this.timeline() !== undefined && this.timeline()!.usage.endedAt === undefined);

  /** The scrub position, epoch ms — the one signal every panel below derives its "now" from. */
  protected readonly atMs = signal(0);

  protected readonly playing = signal(false);
  protected readonly speed = signal<PlaybackSpeed>(1);

  /** ~1000 steps across the window regardless of flight length — fine enough to feel continuous. */
  protected readonly scrubStepMs = computed(() => {
    const span = this.toMs() - this.fromMs();
    return Math.max(100, Math.min(5_000, Math.round(span / 1_000) || 100));
  });

  // --- Map -------------------------------------------------------------------------------------

  private readonly positionedSamples = computed(() =>
    (this.timeline()?.telemetry ?? []).filter((s) => s.latitude !== undefined && s.longitude !== undefined),
  );
  protected readonly fullTrail = computed(() => deriveTrail(this.timeline()?.telemetry ?? []));
  protected readonly trail = computed(() => trailPrefix(this.timeline()?.telemetry ?? [], this.atMs()));
  protected readonly markerSample = computed(() => nearestSample(this.positionedSamples(), this.atMs()));
  protected readonly markerPosition = computed(() => {
    const marker = this.markerSample();
    return marker && marker.latitude !== undefined && marker.longitude !== undefined
      ? { latitude: marker.latitude, longitude: marker.longitude, altitudeMeters: marker.altitudeMeters }
      : undefined;
  });

  // --- Telemetry-at-scrub, per source device (mirrors asset-detail's grouped panels) -----------

  protected readonly telemetryByDevice = computed(() => groupTelemetryByDevice(this.timeline()?.telemetry ?? []));
  protected readonly assetTelemetryDevices = computed(() => telemetryDevices(this.asset()?.devices ?? []));

  // --- Detections-at-scrub + the scrub bar's density strip ------------------------------------

  /**
   * Capped at the latest `DETECTION_STRIP_CAP` (200) buckets (docs/OPS-CORE-PLAN.md §Q3a) — see
   * `capDetectionBuckets`'s own doc comment for why this rarely trims anything under today's
   * `DEFAULT_DETECTION_BUCKETS`; kept as a real slice regardless so a future denser strip stays cheap.
   */
  private readonly cappedDetectionBuckets = computed(() =>
    capDetectionBuckets(bucketDetections(this.timeline()?.detections ?? [], this.fromMs(), this.toMs())),
  );
  protected readonly detectionBuckets = computed<readonly DetectionDensityBucket[]>(
    () => this.cappedDetectionBuckets().buckets,
  );
  /** Quiet "showing latest 200 of N" note — `null` when nothing was actually trimmed. */
  protected readonly detectionStripCapNote = computed(() => {
    const { buckets, totalCount } = this.cappedDetectionBuckets();
    return totalCount > buckets.length ? `Showing latest ${buckets.length} of ${totalCount} markers` : null;
  });
  /** Densest bucket's count — the density strip's markers scale their opacity relative to this. */
  protected readonly maxBucketCount = computed(() =>
    this.detectionBuckets().reduce((max, bucket) => Math.max(max, bucket.count), 1),
  );
  protected readonly nearestDetection = computed(() =>
    nearestDetectionResult(this.timeline()?.detections ?? [], this.atMs()),
  );
  protected readonly detectionIsNear = computed(() => isDetectionNear(this.nearestDetection(), this.atMs()));

  private rafHandle: number | null = null;
  private lastFrameMs: number | null = null;
  private readonly tick = (nowMs: number): void => {
    if (!this.playing()) {
      this.rafHandle = null;
      return;
    }
    const deltaMs = this.lastFrameMs === null ? 0 : nowMs - this.lastFrameMs;
    this.lastFrameMs = nowMs;
    const result = advancePlaybackClock(this.atMs(), deltaMs, this.speed(), this.fromMs(), this.toMs());
    this.atMs.set(result.atMs);
    if (result.playing) {
      this.rafHandle = requestAnimationFrame(this.tick);
    } else {
      this.playing.set(false);
      this.rafHandle = null;
    }
  };

  constructor() {
    // `effect()`, not a direct call — `withComponentInputBinding()` sets route-bound inputs via
    // `setInput()` after construction, mirroring `AssetDetailPage`/`LivePage`'s own precedent
    // (see `assetId`'s own doc comment on those pages). Reads the *effective* ids (whichever route
    // supplied them — see class doc) rather than the raw per-route inputs directly.
    effect(() => {
      void this.load(this.effectiveAssetId(), this.effectiveUsageId());
    });

    // The atMs→video guarded-effect half of the pair described on `videoDrivenUpdate`'s own doc
    // comment: skips the reseek entirely when this exact `atMs` change originated from the video's
    // own `timeupdate` (below), and otherwise only reseeks once drift exceeds `shouldSeekVideo`'s
    // threshold — ordinary 1× playback drifts by only a few ms/tick and would otherwise reseek the
    // `<video>` on every single frame for no visible benefit.
    effect(() => {
      const ms = this.atMs();
      const recordingStartMs = this.recordingStartMs();
      const video = this.videoEl()?.nativeElement;
      if (!video || recordingStartMs === undefined) {
        return;
      }
      if (this.videoDrivenUpdate) {
        this.videoDrivenUpdate = false;
        return;
      }
      const target = videoOffsetSeconds(ms, recordingStartMs);
      if (shouldSeekVideo(video.currentTime, target)) {
        video.currentTime = target;
      }
    });

    inject(DestroyRef).onDestroy(() => this.stopClock());
  }

  private async load(assetId: string | undefined, usageId: string | undefined): Promise<void> {
    if (!usageId) {
      // Only reachable via a hand-typed/malformed `/replay` URL missing `?usage=` — the deep link's
      // own pinned contract always carries one; no current UI in this app links here without it.
      this.loading.set(false);
      this.notFound.set(true);
      this.errorMessage.set('No usage specified.');
      return;
    }
    this.loading.set(true);
    this.notFound.set(false);
    this.recording.set(undefined);
    this.videoErrored.set(false);
    this.clipSelectionStartMs.set(undefined);
    this.clipSelectionEndMs.set(undefined);
    try {
      const [asset, timeline] = await Promise.all([
        assetId ? this.api.getAsset(assetId) : Promise.resolve(undefined),
        this.api.usageTimeline(usageId, { maxPoints: MAX_POINTS }),
      ]);
      this.asset.set(asset);
      this.timeline.set(timeline);
      const fromMs = Date.parse(timeline.from);
      const toMs = Date.parse(timeline.to);
      const requestedOffsetMs = parseDeepLinkOffsetMs(this.deepLinkOffsetParam());
      this.atMs.set(requestedOffsetMs === undefined ? fromMs : clampToRange(fromMs + requestedOffsetMs, fromMs, toMs));
      void this.loadRecording(usageId);
    } catch (error) {
      this.notFound.set(true);
      this.errorMessage.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** Fetched independently of the timeline — a recording lookup failure never blocks the rest of the cockpit, it just leaves the empty state showing. */
  private async loadRecording(usageId: string): Promise<void> {
    try {
      this.recording.set(await this.api.usageRecording(usageId));
    } catch {
      this.recording.set({ available: false });
    }
  }

  protected videoDeviceId(): string | undefined {
    return findVideoDevice(this.asset()?.devices ?? [])?.id;
  }

  /** The "back" link's own target — the asset detail page when known, else Command (the deep link's own degraded-path fallback — see `effectiveAssetId`'s doc comment). */
  protected readonly backLink = computed<readonly string[]>(() => {
    const assetId = this.effectiveAssetId();
    return assetId ? ['/assets', assetId] : ['/command'];
  });

  // --- Recording video pane (docs/OPS-CORE-PLAN.md §R, R-c) -----------------------------------

  /**
   * The `<video>`'s own `timeupdate` — moves the timeline cursor to match, but only while this
   * page's own RAF playback clock (`playing()`) isn't already the one driving `atMs` (pressing
   * Play/Pause here, not the video's native controls); otherwise the video's own naturally-lagged
   * `timeupdate` (it only reseeks once `shouldSeekVideo`'s threshold is crossed) would fight the
   * RAF clock's own, more frequent updates. See `videoDrivenUpdate`'s own doc comment for the guard.
   */
  protected onVideoTimeUpdate(): void {
    if (this.playing()) {
      return;
    }
    const video = this.videoEl()?.nativeElement;
    const recordingStartMs = this.recordingStartMs();
    if (!video || recordingStartMs === undefined) {
      return;
    }
    this.videoDrivenUpdate = true;
    this.atMs.set(videoTimeToAtMs(video.currentTime, recordingStartMs, this.fromMs(), this.toMs()));
  }

  /** A genuinely-missing recording segment (recording enabled after this flight happened, etc.) — falls back to the empty state, never a broken player. */
  protected onVideoError(): void {
    this.videoErrored.set(true);
  }

  // --- Clip export (docs/OPS-CORE-PLAN.md §R, R-c) ---------------------------------------------

  /** "Mark clip start" — captures the current scrub position as the clip's own opening frame. */
  protected markClipStart(): void {
    this.clipSelectionStartMs.set(this.atMs());
  }

  /** "Mark clip end" — captures the current scrub position as the clip's own closing frame. */
  protected markClipEnd(): void {
    this.clipSelectionEndMs.set(this.atMs());
  }

  protected clearClipSelection(): void {
    this.clipSelectionStartMs.set(undefined);
    this.clipSelectionEndMs.set(undefined);
  }

  // --- Playback controls -------------------------------------------------------------------

  protected togglePlay(): void {
    if (this.playing()) {
      this.pause();
      return;
    }
    if (this.atMs() >= this.toMs()) {
      this.atMs.set(this.fromMs()); // replaying after reaching the end starts over
    }
    this.playing.set(true);
    this.lastFrameMs = null;
    this.rafHandle = requestAnimationFrame(this.tick);
  }

  protected pause(): void {
    this.playing.set(false);
    this.stopClock();
  }

  private stopClock(): void {
    if (this.rafHandle !== null) {
      cancelAnimationFrame(this.rafHandle);
      this.rafHandle = null;
    }
  }

  protected setSpeed(speed: PlaybackSpeed): void {
    this.speed.set(speed);
  }

  /** The `<input type="range">`'s own `(input)` handler — dragging pauses playback and takes over. */
  protected onScrub(rawMs: string): void {
    this.pause();
    this.atMs.set(clampToRange(Number(rawMs), this.fromMs(), this.toMs()));
  }

  /** Clicking a detection density marker jumps the scrub there and pauses (docs/MVP2-PLAN.md §R, R-b). */
  protected jumpTo(atMs: number): void {
    this.pause();
    this.atMs.set(clampToRange(atMs, this.fromMs(), this.toMs()));
  }

  // --- Display formatting ---------------------------------------------------------------------

  protected bucketPercent(bucket: DetectionDensityBucket): number {
    const span = this.toMs() - this.fromMs();
    return span <= 0 ? 0 : ((bucket.atMs - this.fromMs()) / span) * 100;
  }

  protected elapsedLabel(): string {
    return formatDuration((this.atMs() - this.fromMs()) / 1000);
  }

  protected totalLabel(): string {
    return formatDuration((this.toMs() - this.fromMs()) / 1000);
  }

  protected clockLabel(atMs: number): string {
    return new Date(atMs).toLocaleTimeString();
  }

  protected sampleAtScrubFor(deviceId: string): TelemetrySample | undefined {
    return nearestSample(this.telemetryByDevice().get(deviceId) ?? [], this.atMs());
  }

  protected sampleTimeMs(sample: TelemetrySample): number {
    return Date.parse(sample.at);
  }
}
