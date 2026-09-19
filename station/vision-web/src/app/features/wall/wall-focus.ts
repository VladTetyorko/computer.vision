import { ChangeDetectionStrategy, Component, DestroyRef, HostListener, effect, inject, input, output } from '@angular/core';
import { Player, type BoxesMode } from '../../shared/player/player';
import { IconButton } from '../../shared/ui/icon-button';
import { DetectionsFacade } from '../../core/detections/detections-facade';
import type { WallTileModel } from './wall-logic';

/**
 * The focus view (docs/plans/active/WALL-FLOW-PLAN.md §3.2 A4, wave W3) — L3, "drill-in stays on
 * the wall, never a navigation" (§3 law 2). `wall.ts` mounts this over the grid when
 * `WallFacade.focusedTile()` is non-null; it never routes anywhere itself — `(openCockpit)`/
 * `(watchLive)` hand the id back to the host page, which is the one place allowed to inject
 * `Router` (`architecture.spec.ts`'s routed-page carve-out).
 *
 * Own `DetectionsStore` (the non-routed-child carve-out `architecture.spec.ts` documents for
 * `wall-tile`/this component) — `WallTileModel` carries no per-frame boxes, only the facts a
 * fleet-summary join can give it, so the enlarged player needs its own live feed exactly like a
 * tile does, just without the `IntersectionObserver` suspend (this view only exists while it is
 * the one thing on screen).
 */
@Component({
  selector: 'vision-wall-focus',
  imports: [Player, IconButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DetectionsFacade],
  templateUrl: './wall-focus.html',
  styleUrl: './wall-focus.css',
})
export class WallFocus {
  readonly tile = input.required<WallTileModel>();
  readonly boxesMode = input.required<BoxesMode>();

  readonly closed = output<void>();
  readonly openCockpit = output<string>();
  readonly watchLive = output<string>();

  protected readonly detections = inject(DetectionsFacade);

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.closed.emit();
  }

  protected onBackdrop(): void {
    this.closed.emit();
  }

  protected onOpenCockpit(assetId: string): void {
    this.openCockpit.emit(assetId);
  }

  protected onWatchLive(): void {
    this.watchLive.emit(this.tile().deviceId);
  }

  constructor() {
    effect(() => {
      const current = this.tile();
      this.detections.track(current.streamId, current.assetId);
    });
    inject(DestroyRef).onDestroy(() => this.detections.reset());
  }
}
