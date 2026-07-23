import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { findVideoDevice } from '../../core/device-logic';
import { deriveTrail, groupTelemetryByDevice, telemetryDevices } from '../../core/telemetry-logic';
import { formatDuration } from '../../core/stream-info-logic';
import type { AssetDetails, TelemetrySample, UsageTimeline } from '../../core/api/models';
import { ReplayMap } from './replay-map';
import {
  advancePlaybackClock,
  bucketDetections,
  clampToRange,
  isDetectionNear,
  nearestDetectionResult,
  nearestSample,
  trailPrefix,
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
 * `ui/player.ts`'s own precedent of using raw browser timers for a *playback* clock tied to a
 * single view's lifecycle rather than a "re-fetch while visible" poll (see vision-web/MODULE.md
 * Gotchas). `advancePlaybackClock` (`replay-logic.ts`) is the pure reducer driving each frame;
 * reaching the end of the window stops playback rather than looping.
 *
 * **Open usages are out of scope by design** (docs/MVP2-PLAN.md §R, R-b: "the history row shows
 * Replay only for finished usages… open one = watch live instead"). A direct link to an open
 * usage's replay URL still resolves (the endpoint itself works fine for one — `to` just defaults
 * to "now"), but this page shows a redirect notice instead of the cockpit rather than replaying a
 * window that is still growing underneath the scrub bar.
 */
@Component({
  selector: 'vision-replay',
  imports: [RouterLink, ReplayMap],
  templateUrl: './replay.html',
  styleUrl: './replay.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReplayPage {
  /** Bound from the route by `withComponentInputBinding()` — both names match their `:param`s exactly. */
  readonly assetId = input.required<string>();
  readonly usageId = input.required<string>();

  private readonly api = inject(VisionApi);

  protected readonly speeds = PLAYBACK_SPEEDS;

  protected readonly asset = signal<AssetDetails | undefined>(undefined);
  protected readonly timeline = signal<UsageTimeline | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly errorMessage = signal<string | undefined>(undefined);

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

  protected readonly detectionBuckets = computed<readonly DetectionDensityBucket[]>(() =>
    bucketDetections(this.timeline()?.detections ?? [], this.fromMs(), this.toMs()),
  );
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
    // (see `assetId`'s own doc comment on those pages).
    effect(() => {
      void this.load(this.assetId(), this.usageId());
    });
    inject(DestroyRef).onDestroy(() => this.stopClock());
  }

  private async load(assetId: string, usageId: string): Promise<void> {
    this.loading.set(true);
    this.notFound.set(false);
    try {
      const [asset, timeline] = await Promise.all([
        this.api.getAsset(assetId),
        this.api.usageTimeline(usageId, { maxPoints: MAX_POINTS }),
      ]);
      this.asset.set(asset);
      this.timeline.set(timeline);
      this.atMs.set(Date.parse(timeline.from));
    } catch (error) {
      this.notFound.set(true);
      this.errorMessage.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected videoDeviceId(): string | undefined {
    return findVideoDevice(this.asset()?.devices ?? [])?.id;
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
