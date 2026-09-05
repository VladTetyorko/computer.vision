import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { EmptyState } from '../../shared/ui/empty-state';
import type { StreamTrack } from '../../core/api/models';
import { targetRows } from './target-list-logic';

/**
 * The target list — "the second door" onto Follow (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.4,
 * wave W5). One row per entry of the stream's live `tracks[]` (label, id, state, age), click a row
 * to follow it. Exists because clicking a tracked box on the glass is sometimes a 12-px target on a
 * moving feed (D8) — this is the accessible path and the only one that works on a touch screen in a
 * moving vehicle; it is also the one place the feature's own gesture is discoverable by existing,
 * which is why mounting it is what lets `CvControlPanel`'s own former "click a box" hint be deleted.
 *
 * **Pure inputs/outputs** — mirrors `shared/player/follow-hud`'s "dumb component, host owns the
 * write" shape (§3.3): {@link tracks}/{@link followedTrackId} arrive already resolved from the
 * host's own tracks-poll read (`CvControlPanel`'s existing `DetectionsStore` injection — this
 * component reads nothing itself, and starts no request of its own: §3.4's own rule, "it renders the
 * same tracks[] the panel already polls; it adds no request"). {@link trackSelected} is a plain
 * output; the host turns it into the identical `buildFollowLockPatch(trackId)` PATCH the glass click
 * already sends (`CvControlPanel#selectTrack` mirrors `CockpitFacade#followTrack` the same way that
 * panel's own `releaseLock()` already mirrors `CockpitFacade#releaseFollow()` — same builder
 * function, two independent call sites, so the two doors can never drift apart).
 *
 * Ordering, the empty state's own copy, and the followed row's one-selection-language tint are all
 * decided by `target-list-logic.ts#targetRows` — this component only renders what that function
 * returns.
 */
@Component({
  selector: 'vision-target-list',
  imports: [EmptyState],
  templateUrl: './target-list.html',
  styleUrl: './target-list.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TargetList {
  /** The stream's live tracks, straight off `GET .../tracks` (`StreamTracksResponse.tracks`) — the
   *  same array the panel's own "Following #N" chip already polls. */
  readonly tracks = input.required<readonly StreamTrack[]>();

  /** The currently-held lock's track id, or `0` for none (`CvControlPanel#lockedTrackId`) — decides
   *  which row (if any) gets the app's one selection language (frontend-style §4) and sorts first. */
  readonly followedTrackId = input<number>(0);

  /** The operator clicked a row — the host performs the actual PATCH (see class doc). */
  readonly trackSelected = output<number>();

  protected readonly rows = computed(() => targetRows(this.tracks(), this.followedTrackId(), Date.now()));

  protected selectTrack(trackId: number): void {
    this.trackSelected.emit(trackId);
  }
}
