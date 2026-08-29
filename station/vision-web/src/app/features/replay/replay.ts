import { ChangeDetectionStrategy, Component, computed, ElementRef, effect, inject, input, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ReplayMap } from './replay-map';
import { AfterActionPanel } from './after-action-panel';
import { PageBar, type PageBarCrumb } from '../../shared/ui/page-bar/page-bar';
import { pluralize } from '../../shared/ui/text-logic';
import { ReplayFacade } from './replay-facade';
import { shouldSeekVideo, videoOffsetSeconds, videoTimeToAtMs, type DetectionDensityBucket } from './replay-logic';

/**
 * The flight-replay cockpit (`/assets/:assetId/replay/:usageId`, docs/plans/done/MVP2-PLAN.md §R, R-b) — the
 * asset detail page's usage history "Replay" target for a finished usage. A referee's use case:
 * "verify where the machine was at minute 7."
 *
 * **`/replay?asset=…&usage=…&t=…`** (docs/plans/done/OPS-CORE-PLAN.md §Q1) is a second, flat route to the same
 * `ReplayPage` — the event → replay deep link's own target. `assetId`/`usageId` (path params) and
 * `assetIdParam`/`usageIdParam`/`deepLinkOffsetParam` (the flat route's query params) are bound from
 * the route by `withComponentInputBinding()` and must stay on this component (an Angular
 * requirement — only a component/directive can declare `input()`); everything else — the cached
 * `timeline`/`asset`/`recording` fetch, every scrub-time derivation, the playback clock, clip export
 * — lives in `ReplayFacade` (docs/plans/done/UI-ARCHITECTURE-PLAN.md), which this component injects
 * exclusively via `bind()`.
 *
 * **What's left here, deliberately** (the plan's own "truly-ephemeral, self-contained local view
 * state" carve-out): the `<video>` element's own `viewChild` query and the two-way
 * `atMs`↔`<video>.currentTime` DOM sync (the guarded-effect pair keyed off `videoDrivenUpdate`) —
 * both need direct access to the video DOM node, which only this component can hold. Every *value*
 * that sync reads/writes (`atMs`, `recordingStartMs`, `playing`) still lives on the facade; only the
 * DOM plumbing stays here. See `videoDrivenUpdate`'s own doc comment for the full "who's driving
 * whom, and how the feedback loop is broken" writeup (mirrors
 * `shared/map/fleet-map/fleet-map.ts#suppressAutoFitDisable`'s identical idiom).
 *
 * **`.surface-dark` boundary (docs/plans/done/VISUAL-REFRESH-PLAN.md F3/W4)**: only the Recording card
 * (`replay.html`'s `.recording-card`, the one section with an actual `<video>`) carries
 * `.surface-dark` — the rest of this page (Position map, Playback scrub bar, Telemetry/Detections
 * facts, the page-bar) stays on the app theme, since this is a themed data/review page with one
 * video-bearing card in it, not a video surface with data bolted on (contrast `CockpitPage`, whose
 * *entire* root is the enclave). See `replay.html`'s own comment on that section for the full
 * reasoning, including why the replay library (`replay-library.html`) needed no boundary decision
 * of its own (it never shows video).
 *
 * **Evidence package (docs/plans/done/AFTER-ACTION-PLAN.md, wave W2)**: `<vision-after-action-panel>`
 * is mounted twice in `replay.html` — inside the `usageOpen` branch too, not just the loaded
 * cockpit — since the package is genuinely servable mid-flight (§5 hazard 4); both mounts bind the
 * same four `facade.afterAction*` reads, `ReplayPage` itself holds no state for it.
 */
@Component({
  selector: 'vision-replay',
  imports: [FormsModule, RouterLink, ReplayMap, PageBar, AfterActionPanel],
  templateUrl: './replay.html',
  styleUrl: './replay.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ReplayFacade],
})
export class ReplayPage {
  /** Bound from the nested route by `withComponentInputBinding()` — both names match their `:param`s exactly. */
  readonly assetId = input<string | undefined>(undefined);
  readonly usageId = input<string | undefined>(undefined);

  /** The flat `/replay?asset=…&usage=…&t=…` deep link's own query params (docs/plans/done/OPS-CORE-PLAN.md §Q1) — see class doc. */
  readonly assetIdParam = input<string | undefined>(undefined, { alias: 'asset' });
  readonly usageIdParam = input<string | undefined>(undefined, { alias: 'usage' });
  readonly deepLinkOffsetParam = input<string | undefined>(undefined, { alias: 't' });

  protected readonly facade = inject(ReplayFacade);

  /**
   * `<vision-page-bar>`'s `crumb` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2) — replaces the old inline
   * `← {{ asset displayName }}` back-link. `facade.backLink()` is a route-segment array
   * (`['/assets', id]` or `['/command']`, the deep link's own degraded-path fallback); `crumb.to`
   * is a plain `string`, so this joins it the same way `RouterLink` would resolve it — safe here
   * because the first segment always starts with `/`, so `join('/')` never doubles a slash.
   */
  protected readonly crumb = computed<PageBarCrumb>(() => ({
    label: this.facade.asset()?.displayName ?? 'Back',
    to: this.facade.backLink().join('/'),
  }));

  private readonly videoEl = viewChild<ElementRef<HTMLVideoElement>>('recordingVideo');
  /**
   * Set by `onVideoTimeUpdate` immediately before it writes `facade.atMs`, checked (and cleared) by
   * the `atMs`→video sync effect below — the guarded-effect pair that stops the two from fighting
   * each other in a loop, mirroring `shared/map/fleet-map/fleet-map.ts#suppressAutoFitDisable`'s own
   * "flag set right before a programmatic write, checked by the listener that write would
   * otherwise re-trigger" idiom. Only one side is ever "driving" `atMs` at a time by construction:
   * the facade's own `playing()` RAF clock when `true` (see `onVideoTimeUpdate`'s own early-return),
   * otherwise whichever of {a scrub/jump, the video's own native controls} last touched it.
   */
  private videoDrivenUpdate = false;

  constructor() {
    // `withComponentInputBinding()` sets route-bound inputs via `setInput()` after construction —
    // `bind()` must run first (synchronously, here) so the facade's own `effect()` (which fires on
    // its next flush, not synchronously) reads the real input signals, not a placeholder.
    this.facade.bind({
      assetId: this.assetId,
      usageId: this.usageId,
      assetIdParam: this.assetIdParam,
      usageIdParam: this.usageIdParam,
      deepLinkOffsetParam: this.deepLinkOffsetParam,
    });

    // The atMs→video guarded-effect half of the pair described on `videoDrivenUpdate`'s own doc
    // comment: skips the reseek entirely when this exact `atMs` change originated from the video's
    // own `timeupdate` (below), and otherwise only reseeks once drift exceeds `shouldSeekVideo`'s
    // threshold — ordinary 1× playback drifts by only a few ms/tick and would otherwise reseek the
    // `<video>` on every single frame for no visible benefit.
    effect(() => {
      const ms = this.facade.atMs();
      const recordingStartMs = this.facade.recordingStartMs();
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
  }

  /**
   * The `<video>`'s own `timeupdate` — moves the timeline cursor to match, but only while the
   * facade's own RAF playback clock (`playing()`) isn't already the one driving `atMs` (pressing
   * Play/Pause, not the video's native controls); otherwise the video's own naturally-lagged
   * `timeupdate` (it only reseeks once `shouldSeekVideo`'s threshold is crossed) would fight the
   * RAF clock's own, more frequent updates. See `videoDrivenUpdate`'s own doc comment for the guard.
   */
  protected onVideoTimeUpdate(): void {
    if (this.facade.playing()) {
      return;
    }
    const video = this.videoEl()?.nativeElement;
    const recordingStartMs = this.facade.recordingStartMs();
    if (!video || recordingStartMs === undefined) {
      return;
    }
    this.videoDrivenUpdate = true;
    this.facade.atMs.set(videoTimeToAtMs(video.currentTime, recordingStartMs, this.facade.fromMs(), this.facade.toMs()));
  }

  /** A genuinely-missing recording segment (recording enabled after this flight happened, etc.) — falls back to the empty state, never a broken player. */
  protected onVideoError(): void {
    this.facade.videoErrored.set(true);
  }

  /** The scrub bar's per-bucket density marker tooltip — `pluralize` fixes the old literal
   *  `N detection(s)` placeholder (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2's pluralisation sweep). */
  protected bucketTitle(bucket: DetectionDensityBucket): string {
    return `${pluralize(bucket.count, 'detection')} near ${this.facade.clockLabel(bucket.atMs)}`;
  }
}
