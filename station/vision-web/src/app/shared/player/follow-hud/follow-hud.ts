import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import type { FollowStatus } from '../../../core/api/models';
import { followPresentation, type FollowPresentation } from './follow-logic';

/**
 * The follow-lock glass readout (docs/plans/active/TRACK-FOLLOW-PLAN.md §3.2/§3.3, wave W4) — one
 * `--hud-*` frosted pill group: `[● label #id]  [state text]  [Zoom ×2]?  [Re-acquire]?  [Release]`.
 * Renders **only while `follow` is non-null** — at rest (no lock ever issued, or after release) the
 * glass is exactly as it was before this wave; there is nothing to say "no lock" about, so this
 * component says nothing rather than rendering an empty/placeholder pill. `[Zoom ×2]` is F2's own
 * digital crop-follow toggle (§3.1 item 4, wave W6) — gated separately from Re-acquire/Release, see
 * {@link cropFollowEnabled}'s own doc comment.
 *
 * **Frozen in `shared/player/`, not `features/fly/`** (§3.3): the Fly cockpit is the first of four
 * planned hosts (`/live`, the Wall, and CREW-CONTROL's own seat all reach for the identical
 * component later), so this cannot assume a `CockpitFacade` or any Fly-specific plumbing.
 *
 * **Injects nothing** — no `VisionApi`, no store, no facade. Every write is the host's job: `follow`
 * arrives already resolved (the host's own tracks-poll read), `canRelease` arrives already resolved
 * (the host's own authority/watch-mode answer), and `(release)`/`(reacquire)` are plain outputs the
 * host turns into its own PATCH call — the identical "dumb component, host owns the write" shape
 * `shared/player/player.ts`'s `(trackFollowed)`/`(latencyChanged)` already follow. This is what
 * makes the component seat-agnostic: a future crew page mounts it with its own authority answer and
 * its own patch call, no revision needed here.
 *
 * **No optimistic UI**: clicking Release/Re-acquire only ever emits — this component never predicts
 * what `follow` will read next. The next tracks-poll read is what moves the pill, exactly like
 * `CvControlPanel`'s own lock chip (docs/extracts/TRACKING-ORCHESTRATION.md §3.3's honesty rule).
 */
@Component({
  selector: 'vision-follow-hud',
  imports: [],
  templateUrl: './follow-hud.html',
  styleUrl: './follow-hud.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FollowHud {
  /** The follow lock's own lifecycle (`CockpitFacade#follow`, or an equivalent per-host read) —
   *  required (every host must pass something, even `null`) so a host can never forget to wire it
   *  and silently show a stale/undefined pill. `null` = render nothing. */
  readonly follow = input.required<FollowStatus | null>();

  /** `false` (the default) = read-only: no Release/Re-acquire actions render regardless of
   *  {@link follow}'s state. Fly's own watch mode, `/live`, and the Wall tile all pass `false` —
   *  a watcher can see what a lock is doing but never end or restart it. */
  readonly canRelease = input<boolean>(false);

  /**
   * F2 digital crop-follow's own per-viewer setting (`SettingsFacade.cropFollowEnabled`,
   * docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1 item 4, wave W6) — passed straight through like
   * every other input here; this component injects no store (see class doc). Unlike
   * {@link canRelease}, this is **not** an authority gate: crop-follow is a purely client-side
   * rendering preference with nothing written to the server, so a read-only watcher (a Wall tile, a
   * Live viewer) gets the toggle too — only {@link follow}'s own state (via
   * `follow-logic.ts#FollowPresentation.showCropToggle`) decides whether it renders.
   */
  readonly cropFollowEnabled = input<boolean>(false);

  /** The operator's own explicit release click — the host PATCHes `buildReleaseLockPatch()`. */
  readonly release = output<void>();

  /** The operator's own explicit re-acquire click (only ever offered while `LOST` and
   *  `reacquirable`) — the host re-issues the original lock request for the same track id. */
  readonly reacquire = output<void>();

  /**
   * The operator's own "Zoom ×2" click, carrying the *desired* new value — the host flips its own
   * `SettingsFacade.cropFollowEnabled` signal and re-passes the result down next render, mirroring
   * `CvControlPanel#detectionEnabledChange`'s own `output<boolean>()` idiom rather than a bare
   * "toggled" event this component would have to invert state to interpret.
   */
  readonly cropFollowEnabledChange = output<boolean>();

  /** `null` whenever {@link follow} is `null` — see `follow-logic.ts#followPresentation`'s own doc
   *  comment for why every word/colour/gating decision lives there, not in this component. */
  protected readonly presentation = computed<FollowPresentation | null>(() => {
    const follow = this.follow();
    return follow === null ? null : followPresentation(follow, Date.now());
  });
}
