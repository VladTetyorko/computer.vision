import { DestroyRef, Injectable, type Signal, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { TrainingStore } from '../../core/training/training-store';
import { findVideoDevice } from '../../core/fleet/device-logic';
import { deriveTrail, groupTelemetryByDevice, telemetryDevices } from '../../core/telemetry/telemetry-logic';
import { formatDuration } from '../../core/stream-info-logic';
import type { AssetDetails, TelemetrySample, UsageRecording, UsageTimeline } from '../../core/api/models';
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
  trailPrefix,
  videoOffsetSeconds,
  type DetectionDensityBucket,
  type PlaybackSpeed,
} from './replay-logic';

/** The server's own downsample ceiling (`UsageTimelineController`) — asked for once, up front. */
const MAX_POINTS = 2_000;

/** The route-bound inputs `ReplayPage` feeds in once, at construction — see `bind`'s own doc comment. */
export interface ReplayRouteInputs {
  readonly assetId: Signal<string | undefined>;
  readonly usageId: Signal<string | undefined>;
  readonly assetIdParam: Signal<string | undefined>;
  readonly usageIdParam: Signal<string | undefined>;
  readonly deepLinkOffsetParam: Signal<string | undefined>;
}

/**
 * `ReplayPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — owns every read-model/command the page
 * used to own directly: the cached `timeline`/`asset`/`recording` fetch, every scrub-time `computed`
 * derivation (`replay-logic.ts`), the `requestAnimationFrame` playback clock, clip export, and (new,
 * docs/plans/done/CV-TRAINING-V2-PLAN.md §8) the "Add to dataset" replay-capture action. Every value/behavior
 * here is byte-for-byte what `ReplayPage` owned before this refactor.
 *
 * **What stays on the page instead** (docs/plans/done/UI-ARCHITECTURE-PLAN.md's own "truly-ephemeral,
 * self-contained local view state" carve-out): the `<video>` element's own `viewChild` query and the
 * two-way `atMs`↔`<video>.currentTime` DOM sync (the guarded-effect pair keyed off
 * `videoDrivenUpdate`) — both need direct access to the video DOM node, which only the component
 * itself can hold. This facade still owns every *value* that sync reads/writes (`atMs`,
 * `recordingStartMs`, `playing`) — only the DOM plumbing stays component-side.
 *
 * **"Add to dataset" (docs/plans/done/CV-TRAINING-V2-PLAN.md §8) — a second capture entry point, alongside
 * `DatasetDetailFacade`'s own live-stream capture.** `training` (`TrainingStore`, `providedIn:
 * 'root'`) supplies the dataset picker's own list for free — the same store `features/labeling/**`
 * already reads, refreshed here too (mirrors `DatasetsFacade`'s own unconditional
 * `training.refresh()` on construction) since a viewer may land on `/replay` without ever having
 * visited `/manage/training` first. `training.disabled()` (the store's own honest 404→"not enabled
 * here" signal) hides the whole control in `replay.html`, exactly like `DatasetsPage`'s own
 * `vision-empty` degrade — no second feature-flag probe. {@link addToDataset} reuses
 * `videoOffsetSeconds(atMs(), recordingStartMs())` — the *exact* scrub→seconds conversion the
 * `<video>` DOM sync already trusts — as the captured instant, so "the frame you're looking at" is
 * genuinely the frame the server extracts.
 */
@Injectable()
export class ReplayFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);

  readonly training = inject(TrainingStore);

  private assetIdSignal: Signal<string | undefined> = signal(undefined);
  private usageIdSignal: Signal<string | undefined> = signal(undefined);
  private assetIdParamSignal: Signal<string | undefined> = signal(undefined);
  private usageIdParamSignal: Signal<string | undefined> = signal(undefined);
  private deepLinkOffsetParamSignal: Signal<string | undefined> = signal(undefined);

  /** Whichever route actually supplied an asset id — `undefined` only for a hand-typed/malformed flat-route URL missing `?asset=`. */
  readonly effectiveAssetId = computed(() => this.assetIdSignal() ?? this.assetIdParamSignal());
  readonly effectiveUsageId = computed(() => this.usageIdSignal() ?? this.usageIdParamSignal());

  readonly speeds: readonly PlaybackSpeed[] = [1, 4, 16];

  readonly asset = signal<AssetDetails | undefined>(undefined);
  readonly timeline = signal<UsageTimeline | undefined>(undefined);
  readonly loading = signal(true);
  readonly notFound = signal(false);
  readonly errorMessage = signal<string | undefined>(undefined);

  // --- Recording video pane (docs/plans/done/OPS-CORE-PLAN.md §R, R-c) -----------------------------------
  readonly recording = signal<UsageRecording | undefined>(undefined);
  readonly videoErrored = signal(false);
  readonly videoAvailable = computed(
    () => this.recording()?.available === true && this.recording()?.url !== undefined && !this.videoErrored(),
  );
  /** Public — `ReplayPage`'s own DOM-sync effect reads this to convert a scrub position to a `<video>.currentTime`. */
  readonly recordingStartMs = computed(() => {
    const start = this.recording()?.start;
    return start !== undefined ? Date.parse(start) : undefined;
  });

  // --- Clip export (docs/plans/done/OPS-CORE-PLAN.md §R, R-c) ---------------------------------------------
  readonly clipSelectionStartMs = signal<number | undefined>(undefined);
  readonly clipSelectionEndMs = signal<number | undefined>(undefined);
  readonly hasClipSelection = computed(
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
  readonly downloadClipUrl = computed(() => {
    const url = this.recording()?.url;
    const window = this.clipWindow();
    return url && window ? buildClipDownloadUrl(url, window) : undefined;
  });

  // --- Add to dataset, from a replay frame (docs/plans/done/CV-TRAINING-V2-PLAN.md §8) ---------------------

  /** The "Add to dataset" picker's current selection — `''` = none chosen yet. */
  readonly datasetId = signal('');
  readonly capturing = signal(false);

  readonly fromMs = computed(() => (this.timeline() ? Date.parse(this.timeline()!.from) : 0));
  readonly toMs = computed(() => (this.timeline() ? Date.parse(this.timeline()!.to) : 0));
  readonly usageOpen = computed(() => this.timeline() !== undefined && this.timeline()!.usage.endedAt === undefined);

  /** The scrub position, epoch ms — the one signal every panel below derives its "now" from.
   * Public/writable — `ReplayPage`'s own `<video>` `timeupdate` handler writes it directly, the same
   * "component writes a store/facade signal directly" idiom `settings.wallDensity.set(...)` etc.
   * already use throughout this app. */
  readonly atMs = signal(0);

  readonly playing = signal(false);
  readonly speed = signal<PlaybackSpeed>(1);

  /** ~1000 steps across the window regardless of flight length — fine enough to feel continuous. */
  readonly scrubStepMs = computed(() => {
    const span = this.toMs() - this.fromMs();
    return Math.max(100, Math.min(5_000, Math.round(span / 1_000) || 100));
  });

  // --- Map -------------------------------------------------------------------------------------

  private readonly positionedSamples = computed(() =>
    (this.timeline()?.telemetry ?? []).filter((s) => s.latitude !== undefined && s.longitude !== undefined),
  );
  readonly fullTrail = computed(() => deriveTrail(this.timeline()?.telemetry ?? []));
  readonly trail = computed(() => trailPrefix(this.timeline()?.telemetry ?? [], this.atMs()));
  readonly markerSample = computed(() => nearestSample(this.positionedSamples(), this.atMs()));
  readonly markerPosition = computed(() => {
    const marker = this.markerSample();
    return marker && marker.latitude !== undefined && marker.longitude !== undefined
      ? { latitude: marker.latitude, longitude: marker.longitude, altitudeMeters: marker.altitudeMeters }
      : undefined;
  });

  // --- Telemetry-at-scrub, per source device (mirrors asset-detail's grouped panels) -----------

  readonly telemetryByDevice = computed(() => groupTelemetryByDevice(this.timeline()?.telemetry ?? []));
  readonly assetTelemetryDevices = computed(() => telemetryDevices(this.asset()?.devices ?? []));

  // --- Detections-at-scrub + the scrub bar's density strip ------------------------------------

  /**
   * Capped at the latest `DETECTION_STRIP_CAP` (200) buckets (docs/plans/done/OPS-CORE-PLAN.md §Q3a) — see
   * `capDetectionBuckets`'s own doc comment for why this rarely trims anything under today's
   * `DEFAULT_DETECTION_BUCKETS`; kept as a real slice regardless so a future denser strip stays cheap.
   */
  private readonly cappedDetectionBuckets = computed(() =>
    capDetectionBuckets(bucketDetections(this.timeline()?.detections ?? [], this.fromMs(), this.toMs())),
  );
  readonly detectionBuckets = computed<readonly DetectionDensityBucket[]>(() => this.cappedDetectionBuckets().buckets);
  /** Quiet "showing latest 200 of N" note — `null` when nothing was actually trimmed. */
  readonly detectionStripCapNote = computed(() => {
    const { buckets, totalCount } = this.cappedDetectionBuckets();
    return totalCount > buckets.length ? `Showing latest ${buckets.length} of ${totalCount} markers` : null;
  });
  /** Densest bucket's count — the density strip's markers scale their opacity relative to this. */
  readonly maxBucketCount = computed(() =>
    this.detectionBuckets().reduce((max, bucket) => Math.max(max, bucket.count), 1),
  );
  readonly nearestDetection = computed(() => nearestDetectionResult(this.timeline()?.detections ?? [], this.atMs()));
  readonly detectionIsNear = computed(() => isDetectionNear(this.nearestDetection(), this.atMs()));

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
    // `setInput()` after construction (see `bind`'s own doc comment); reads the *effective* ids
    // (whichever route supplied them — see class doc) rather than the raw per-route inputs directly.
    effect(() => {
      void this.load(this.effectiveAssetId(), this.effectiveUsageId());
    });

    // Mirrors `DatasetsFacade`'s own unconditional `training.refresh()` on construction — a viewer
    // may land on `/replay` without ever having visited `/manage/training` first, and `TrainingStore`
    // is lazy (`providedIn: 'root'`, not self-initializing), so nothing else guarantees this runs.
    void this.training.refresh();

    inject(DestroyRef).onDestroy(() => this.stopClock());
  }

  /**
   * Binds `ReplayPage`'s own route-bound input signals — called once, synchronously, from the
   * page's constructor right after injecting this facade. Signal *inputs* must stay on the
   * component (an Angular requirement — only a component/directive can declare `input()`); storing
   * the raw `Signal` references (not copies) here means `effectiveAssetId`/`effectiveUsageId`
   * above track the page's real inputs reactively, with no extra bridging signal/effect needed.
   */
  bind(inputs: ReplayRouteInputs): void {
    this.assetIdSignal = inputs.assetId;
    this.usageIdSignal = inputs.usageId;
    this.assetIdParamSignal = inputs.assetIdParam;
    this.usageIdParamSignal = inputs.usageIdParam;
    this.deepLinkOffsetParamSignal = inputs.deepLinkOffsetParam;
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
    this.datasetId.set('');
    try {
      const [asset, timeline] = await Promise.all([
        assetId ? this.api.getAsset(assetId) : Promise.resolve(undefined),
        this.api.usageTimeline(usageId, { maxPoints: MAX_POINTS }),
      ]);
      this.asset.set(asset);
      this.timeline.set(timeline);
      const fromMs = Date.parse(timeline.from);
      const toMs = Date.parse(timeline.to);
      const requestedOffsetMs = parseDeepLinkOffsetMs(this.deepLinkOffsetParamSignal());
      this.atMs.set(requestedOffsetMs === undefined ? fromMs : clampToRange(fromMs + requestedOffsetMs, fromMs, toMs));
      void this.loadRecording(usageId);
    } catch (error) {
      this.notFound.set(true);
      this.errorMessage.set(describeHttpError(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** Fetched independently of the timeline — a recording lookup failure never blocks the rest of the cockpit. */
  private async loadRecording(usageId: string): Promise<void> {
    try {
      this.recording.set(await this.api.usageRecording(usageId));
    } catch {
      this.recording.set({ available: false });
    }
  }

  videoDeviceId(): string | undefined {
    return findVideoDevice(this.asset()?.devices ?? [])?.id;
  }

  /** The "back" link's own target — the asset detail page when known, else Command (the deep link's own degraded-path fallback — see `effectiveAssetId`'s doc comment). */
  readonly backLink = computed<readonly string[]>(() => {
    const assetId = this.effectiveAssetId();
    return assetId ? ['/assets', assetId] : ['/command'];
  });

  // --- Clip export (docs/plans/done/OPS-CORE-PLAN.md §R, R-c) ---------------------------------------------

  /** "Mark clip start" — captures the current scrub position as the clip's own opening frame. */
  markClipStart(): void {
    this.clipSelectionStartMs.set(this.atMs());
  }

  /** "Mark clip end" — captures the current scrub position as the clip's own closing frame. */
  markClipEnd(): void {
    this.clipSelectionEndMs.set(this.atMs());
  }

  clearClipSelection(): void {
    this.clipSelectionStartMs.set(undefined);
    this.clipSelectionEndMs.set(undefined);
  }

  // --- Add to dataset, from a replay frame (docs/plans/done/CV-TRAINING-V2-PLAN.md §8) ---------------------

  /**
   * Captures the frame at the current scrub position into the selected dataset — the replay-driven
   * twin of `DatasetDetailFacade.capture()`'s live-stream capture. `replay.html`'s own control
   * already keeps this from rendering at all when `training.disabled()` or `!videoAvailable()`; the
   * guards here just make a stray call (e.g. a double-submit racing the button's own `[disabled]`)
   * a safe no-op rather than a duplicate request. Mirrors `DatasetDetailFacade.capture()`'s own
   * try/toast/finally shape exactly — an honest error toast (`describeHttpError`) on failure, never
   * a fabricated success.
   */
  async addToDataset(): Promise<void> {
    const usageId = this.effectiveUsageId();
    const datasetId = this.datasetId();
    const recordingStartMs = this.recordingStartMs();
    if (!usageId || !datasetId || recordingStartMs === undefined || this.capturing()) {
      return;
    }
    this.capturing.set(true);
    try {
      const sample = await this.api.captureReplaySample(usageId, datasetId, videoOffsetSeconds(this.atMs(), recordingStartMs));
      this.toasts.ok('Added this frame to the dataset.', {
        label: 'Open dataset',
        onClick: () => void this.router.navigate(['/manage/training', sample.datasetId]),
      });
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.capturing.set(false);
    }
  }

  // --- Playback controls -------------------------------------------------------------------

  togglePlay(): void {
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

  pause(): void {
    this.playing.set(false);
    this.stopClock();
  }

  private stopClock(): void {
    if (this.rafHandle !== null) {
      cancelAnimationFrame(this.rafHandle);
      this.rafHandle = null;
    }
  }

  setSpeed(speed: PlaybackSpeed): void {
    this.speed.set(speed);
  }

  /** The `<input type="range">`'s own `(input)` handler — dragging pauses playback and takes over. */
  onScrub(rawMs: string): void {
    this.pause();
    this.atMs.set(clampToRange(Number(rawMs), this.fromMs(), this.toMs()));
  }

  /** Clicking a detection density marker jumps the scrub there and pauses (docs/plans/done/MVP2-PLAN.md §R, R-b). */
  jumpTo(atMs: number): void {
    this.pause();
    this.atMs.set(clampToRange(atMs, this.fromMs(), this.toMs()));
  }

  // --- Display formatting ---------------------------------------------------------------------

  bucketPercent(bucket: DetectionDensityBucket): number {
    const span = this.toMs() - this.fromMs();
    return span <= 0 ? 0 : ((bucket.atMs - this.fromMs()) / span) * 100;
  }

  elapsedLabel(): string {
    return formatDuration((this.atMs() - this.fromMs()) / 1000);
  }

  totalLabel(): string {
    return formatDuration((this.toMs() - this.fromMs()) / 1000);
  }

  clockLabel(atMs: number): string {
    return new Date(atMs).toLocaleTimeString();
  }

  sampleAtScrubFor(deviceId: string): TelemetrySample | undefined {
    return nearestSample(this.telemetryByDevice().get(deviceId) ?? [], this.atMs());
  }

  sampleTimeMs(sample: TelemetrySample): number {
    return Date.parse(sample.at);
  }
}
